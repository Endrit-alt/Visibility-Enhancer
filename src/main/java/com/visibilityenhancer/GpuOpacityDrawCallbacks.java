package com.visibilityenhancer;

import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.Set;
import lombok.experimental.Delegate;
import net.runelite.api.Actor;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.plugins.gpu.GpuPlugin;

/** Keeps the supported renderer in charge, substituting only selected actor-model inputs. */
final class GpuOpacityDrawCallbacks implements DrawCallbacks
{
   // HD is an optional Plugin Hub plugin with its own class loader. Do not link against it.
   private static final String HD_ZONE_RENDERER = "rs117.hd.renderer.zone.ZoneRenderer";

   static boolean supports(DrawCallbacks callbacks)
   {
      // Both consume or copy model arrays before drawTemp returns. HD's legacy renderer
      // uses a different draw callback and must not enable the render-time opacity path.
      return callbacks instanceof GpuPlugin
              || (callbacks != null && HD_ZONE_RENDERER.equals(callbacks.getClass().getName()));
   }

   private interface TempDraw
   {
      void drawTemp(Projection projection, Scene scene, GameObject object, Model model,
                    int orientation, int x, int y, int z);

      void preSceneDraw(Scene scene, Projection projection, float cameraX, float cameraY, float cameraZ,
                        float cameraPitch, float cameraYaw, int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds);

      void postSceneDraw(Scene scene);
   }

   @Delegate(excludes = TempDraw.class)
   private final DrawCallbacks delegate;

   private final BiFunction<Renderable, Model, Model> prepareModel;
   private final SolidGpuCompositor solid;
   private final StackedHighlightTracker highlights = new StackedHighlightTracker();

   GpuOpacityDrawCallbacks(DrawCallbacks delegate, BiFunction<Renderable, Model, Model> prepareModel)
   {
      this(delegate, prepareModel, () -> false, (scene, object) -> true);
   }

   GpuOpacityDrawCallbacks(DrawCallbacks delegate, BiFunction<Renderable, Model, Model> prepareModel,
                           BooleanSupplier solidEnabled, BiPredicate<Scene, GameObject> drawObject)
   {
      this.delegate = delegate;
      this.prepareModel = prepareModel;
      // No HD GL calls or GPU subclasses with unknown scene-program contracts.
      solid = delegate.getClass() == GpuPlugin.class ? new SolidGpuCompositor(solidEnabled, drawObject) : null;
   }

   void close()
   {
      highlights.beginFrame(false);
      if (solid != null) solid.close();
   }

   void beginHighlightFrame(boolean enabled)
   {
      highlights.beginFrame(enabled);
   }

   Actor getStackHighlightActor(Actor actor)
   {
      return highlights.preferredActor(actor);
   }

   @Override
   public void preSceneDraw(Scene scene, Projection projection, float cameraX, float cameraY, float cameraZ,
                            float cameraPitch, float cameraYaw, int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
   {
      delegate.preSceneDraw(scene, projection, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw,
              minLevel, level, maxLevel, hideRoofIds);
      if (solid != null) solid.begin(scene);
   }

   @Override
   public void postSceneDraw(Scene scene)
   {
      if (solid != null) solid.finish(scene);
      delegate.postSceneDraw(scene);
   }

   DrawCallbacks getDelegate()
   {
      return delegate;
   }

   boolean isHdZoneRenderer()
   {
      return HD_ZONE_RENDERER.equals(delegate.getClass().getName());
   }

   @Override
   public void drawTemp(Projection projection, Scene scene, GameObject object, Model model,
                        int orientation, int x, int y, int z)
   {
      // Players and NPCs arrive on the client thread. GPU uploads synchronously; HD ZoneRenderer
      // copies model arrays before returning when it queues asynchronous uploads.
      // Worker-thread scenery callbacks are delegated without interception.
      Renderable renderable = object == null ? null : object.getRenderable();
      Model prepared = object == null ? model : prepareModel.apply(renderable, model);
      if (renderable instanceof Actor && model != null)
      {
         // Record the scene's chosen actor even when 0% suppresses its upload:
         // completely hidden stacks still need one correctly selected highlight.
         highlights.record((Actor) renderable, prepared != null);
      }
      if (prepared != null)
      {
         if (solid != null && solid.submit(projection, scene, object, model, prepared, orientation, x, y, z)) return;
         delegate.drawTemp(projection, scene, object, prepared, orientation, x, y, z);
      }
   }
}
