package com.visibilityenhancer;

import java.util.Set;
import java.util.function.BiFunction;
import lombok.experimental.Delegate;
import net.runelite.api.Actor;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.plugins.gpu.GpuPlugin;

/** Keeps the supported renderer in charge, substituting only selected actor-model inputs. */
final class GpuOpacityDrawCallbacks implements DrawCallbacks
{
   // HD is an optional Plugin Hub plugin with its own class loader. Do not link against it.
   private static final String HD_ZONE_RENDERER = "rs117.hd.renderer.zone.ZoneRenderer";
   /** Consecutive rendered frames without any client callback before this wrapper counts as bypassed. */
   static final int BYPASS_FRAMES = 10;

   static boolean supports(DrawCallbacks callbacks)
   {
      // Both consume or copy model arrays before drawTemp returns. HD's legacy renderer
      // uses a different draw callback and must not enable the render-time opacity path.
      // DrawCallbacks has no public delegate/capability contract for arbitrary wrappers.
      // client.isGpu() alone cannot establish these model-lifetime guarantees.
      return callbacks instanceof GpuPlugin
              || (callbacks != null && HD_ZONE_RENDERER.equals(callbacks.getClass().getName()));
   }

   private interface TempDraw
   {
      void drawTemp(Projection projection, Scene scene, GameObject object, Model model,
                    int orientation, int x, int y, int z);
      void preSceneDraw(Scene scene, Projection entityProjection, float cameraX, float cameraY, float cameraZ,
                        float cameraPitch, float cameraYaw, int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds);
      void draw(int overlayColor);
   }

   @Delegate(excludes = TempDraw.class)
   private final DrawCallbacks delegate;

   private BiFunction<Renderable, Model, Model> prepareModel;
   private volatile boolean closed;
   private final StackedHighlightTracker highlights = new StackedHighlightTracker();
   // Installed directly over GPU/HD (false), or over another plugin's callback wrapper (true).
   private final boolean overUnknownWrapper, hdRenderer;
   // Model views are only handed to the renderer on the thread that installed this wrapper (the client thread).
   private final Thread installThread = Thread.currentThread();
   // Evidence that the client's callbacks still pass through this wrapper, possibly under another plugin's wrapper.
   private int callsSinceFrame, scenesSinceFrame, framesWithoutCalls;
   private boolean callsSeen, sceneSeen;

   GpuOpacityDrawCallbacks(DrawCallbacks delegate, BiFunction<Renderable, Model, Model> prepareModel)
   {
      this(delegate, prepareModel, false,
              delegate != null && HD_ZONE_RENDERER.equals(delegate.getClass().getName()));
   }

   GpuOpacityDrawCallbacks(DrawCallbacks delegate, BiFunction<Renderable, Model, Model> prepareModel,
                           boolean overUnknownWrapper, boolean hdRenderer)
   {
      this.delegate = delegate;
      this.prepareModel = prepareModel;
      this.overUnknownWrapper = overUnknownWrapper;
      this.hdRenderer = hdRenderer;
   }

   void close()
   {
      if (closed) return;
      closed = true;
      highlights.beginFrame(false);
      prepareModel = null; // Do not retain the plugin through another plugin's wrapper.
   }

   /**
    * Plugin shutdown while another plugin's wrapper sits on top of ours: ours cannot be removed from
    * that chain. Keep it dormant (shutdown already stopped every effect) so a
    * re-enable resumes through it. Unlike close(), the preparation callback is kept.
    */
   void suspend()
   {
      highlights.beginFrame(false);
   }

   boolean isClosed()
   {
      return closed;
   }

   void beginHighlightFrame(boolean enabled)
   {
      highlights.beginFrame(enabled && !closed);
   }

   Actor getStackHighlightActor(Actor actor)
   {
      return highlights.preferredActor(actor);
   }

   DrawCallbacks getDelegate()
   {
      return delegate;
   }

   boolean isHdZoneRenderer()
   {
      return hdRenderer;
   }

   boolean isOverUnknownWrapper()
   {
      return overUnknownWrapper;
   }

   /** Once per rendered frame in stable game states (client thread): folds in the previous frame's callbacks. */
   void frameTick()
   {
      if (callsSinceFrame > 0)
      {
         callsSeen = true;
         framesWithoutCalls = 0;
      }
      else if (framesWithoutCalls <= BYPASS_FRAMES)
      {
         framesWithoutCalls++;
      }
      if (scenesSinceFrame > 0) sceneSeen = true;
      callsSinceFrame = 0;
      scenesSinceFrame = 0;
   }

   /** The client's per-frame callbacks recently reached this wrapper, whether it is outermost or wrapped. */
   boolean inDrawPath()
   {
      return callsSeen && framesWithoutCalls <= BYPASS_FRAMES;
   }

   /** No client callback reached this wrapper for several rendered frames: something replaced the chain. */
   boolean isBypassed()
   {
      return framesWithoutCalls > BYPASS_FRAMES;
   }

   /** A zone renderer's top-level scene setup has passed through this wrapper (HD's legacy renderer never sends it). */
   boolean sceneCallbacksSeen()
   {
      return sceneSeen;
   }

   @Override
   public void draw(int overlayColor)
   {
      // Sent once per frame through the whole chain, also on frames without a scene (e.g. the lobby).
      callsSinceFrame++;
      delegate.draw(overlayColor);
   }

   @Override
   public void preSceneDraw(Scene scene, Projection entityProjection, float cameraX, float cameraY, float cameraZ,
                            float cameraPitch, float cameraYaw, int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
   {
      callsSinceFrame++;
      if (scene != null && scene.getWorldViewId() == WorldView.TOPLEVEL) scenesSinceFrame++;
      delegate.preSceneDraw(scene, entityProjection, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw,
              minLevel, level, maxLevel, hideRoofIds);
   }

   @Override
   public void drawTemp(Projection projection, Scene scene, GameObject object, Model model,
                        int orientation, int x, int y, int z)
   {
      BiFunction<Renderable, Model, Model> prepare = prepareModel;
      if (closed || prepare == null || Thread.currentThread() != installThread)
      {
         delegate.drawTemp(projection, scene, object, model, orientation, x, y, z);
         return;
      }
      // Players and NPCs arrive on the client thread. GPU uploads synchronously; HD ZoneRenderer
      // copies model arrays before returning when it queues asynchronous uploads.
      // Worker-thread scenery callbacks, and anything an outer wrapper forwards from another
      // thread, are delegated without interception.
      Renderable renderable = object == null ? null : object.getRenderable();
      Model prepared = object == null ? model : prepare.apply(renderable, model);
      if (renderable instanceof Actor && model != null)
      {
         // Record the scene's chosen actor even when 0% suppresses its upload:
         // completely hidden stacks still need one correctly selected highlight.
         highlights.record((Actor) renderable, prepared != null);
      }
      if (prepared != null)
      {
         delegate.drawTemp(projection, scene, object, prepared, orientation, x, y, z);
      }
   }
}
