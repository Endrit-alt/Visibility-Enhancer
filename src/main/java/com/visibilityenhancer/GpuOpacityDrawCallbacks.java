package com.visibilityenhancer;

import java.util.function.BiFunction;
import lombok.experimental.Delegate;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.plugins.gpu.GpuPlugin;

/** Keeps the supported renderer in charge, substituting only its player-model input. */
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
   }

   @Delegate(excludes = TempDraw.class)
   private final DrawCallbacks delegate;

   private final BiFunction<Renderable, Model, Model> prepareModel;

   GpuOpacityDrawCallbacks(DrawCallbacks delegate, BiFunction<Renderable, Model, Model> prepareModel)
   {
      this.delegate = delegate;
      this.prepareModel = prepareModel;
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
      // Players arrive on the client thread. GPU uploads synchronously; HD ZoneRenderer
      // copies model arrays before returning when it queues asynchronous uploads.
      // Worker-thread scenery callbacks are delegated without interception.
      Model prepared = object == null ? model : prepareModel.apply(object.getRenderable(), model);
      if (prepared != null)
      {
         delegate.drawTemp(projection, scene, object, prepared, orientation, x, y, z);
      }
   }
}
