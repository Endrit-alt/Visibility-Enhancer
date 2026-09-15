package com.visibilityenhancer;

import lombok.experimental.Delegate;
import net.runelite.api.Model;

/**
 * Renderer-only view of a completed model. Never modify the client's cached models
 * or shared animation alpha buffers. Reuse only with renderers which consume or copy
 * all needed data before drawTemp returns (GPU and 117 HD's zone renderer).
 */
final class GpuOpacityModel implements Model
{
   // Vanilla texture IDs remain the same when HD replaces the cape material.
   private static final int FIRE_CAPE_TEXTURE = 40;
   private static final int INFERNAL_CAPE_TEXTURE = 59;

   private interface OpacityMethods
   {
      byte[] getFaceTransparencies();
      Model getModel();
   }

   @Delegate(excludes = OpacityMethods.class)
   private Model delegate;

   private byte[] transparencies = new byte[0];

   Model update(Model model, int alpha)
   {
      return update(model, alpha, false);
   }

   Model update(Model model, int alpha, boolean hideHdCapeTextures)
   {
      delegate = model;
      int faceCount = model.getFaceCount();
      if (transparencies.length != faceCount)
      {
         transparencies = new byte[faceCount];
      }

      byte[] original = model.getFaceTransparencies();
      short[] textures = hideHdCapeTextures ? model.getFaceTextures() : null;
      int target = Math.max(0, Math.min(255, alpha));
      for (int i = 0; i < faceCount; i++)
      {
         int nativeAlpha = original != null && i < original.length ? original[i] & 0xff : 0;
         boolean hideCapeFace = textures != null && i < textures.length
                 && (textures[i] == FIRE_CAPE_TEXTURE || textures[i] == INFERNAL_CAPE_TEXTURE);
         // HD's cape shader ignores partial model alpha, but its uploader skips 255
         // before material selection. Hide those faces only in this renderer-owned view.
         transparencies[i] = (byte) (hideCapeFace ? 255 : Math.max(nativeAlpha, target));
      }
      return this;
   }

   @Override
   public byte[] getFaceTransparencies()
   {
      return transparencies;
   }

   @Override
   public Model getModel()
   {
      return this;
   }
}
