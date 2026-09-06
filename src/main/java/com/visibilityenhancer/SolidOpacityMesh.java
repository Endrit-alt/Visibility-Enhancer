/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.visibilityenhancer;

import java.nio.IntBuffer;
import java.util.Arrays;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;

/**
 * CPU-owned snapshot using the standard GPU temporary vertex format.
 * UV mapping and priority ordering follow RuneLite's BSD-licensed ModelUploader (license above).
 * No live Model or animation arrays survive the drawTemp call.
 */
final class SolidOpacityMesh
{
   static final int STRIDE = 6;
   static final int MAX_FACES = 8192;
   final int[] vertices;
   final float opacity;
   final float distance;
   final int opaqueVertexCount;
   final int translucentFaces;
   final boolean prioritySorted;

   private SolidOpacityMesh(int[] vertices, float opacity, float distance, int opaqueVertexCount,
                            int translucentFaces, boolean prioritySorted)
   {
      this.vertices = vertices;
      this.opacity = opacity;
      this.distance = distance;
      this.opaqueVertexCount = opaqueVertexCount;
      this.translucentFaces = translucentFaces;
      this.prioritySorted = prioritySorted;
   }

   enum Rejection
   {
      MISSING_MODEL_OR_PROJECTION, NOT_PARTIAL_OPACITY, COLOUR_OVERRIDE,
      MODEL_SIZE_LIMIT, INVALID_GEOMETRY, NEAR_CAMERA, INVALID_TEXTURE_MAPPING,
      INVALID_FACE_PRIORITY, NO_VISIBLE_FACES
   }

   static final class Capture
   {
      final SolidOpacityMesh mesh;
      final Rejection rejection;

      private Capture(SolidOpacityMesh mesh, Rejection rejection)
      {
         this.mesh = mesh;
         this.rejection = rejection;
      }
   }

   /** Client-thread scratch only. Completed meshes never reference any of these arrays. */
   static final class Scratch
   {
      final float[] projected = new float[3], u = new float[3], v = new float[3];
      float[] worldX = new float[0], worldY = new float[0], worldZ = new float[0];
      float[] screenX = new float[0], screenY = new float[0];
      int[] vertexDepth = new int[0];
      int[] faceDepth = new int[0], order = new int[0], transparency = new int[0];
      long[] sortKeys = new long[0];
      int[] sorted = new int[0], grouped = new int[0], result = new int[0];
      final int[] counts = new int[12], starts = new int[12], positions = new int[12];
      final long[] sums = new long[12];
      IntBuffer packed = IntBuffer.allocate(0);

      void ensureCapacity(int vertices, int faces)
      {
         if (worldX.length < vertices)
         {
            worldX = new float[vertices]; worldY = new float[vertices]; worldZ = new float[vertices];
            screenX = new float[vertices]; screenY = new float[vertices]; vertexDepth = new int[vertices];
         }
         if (faceDepth.length < faces)
         {
            faceDepth = new int[faces]; order = new int[faces]; transparency = new int[faces];
            sortKeys = new long[faces]; sorted = new int[faces]; grouped = new int[faces]; result = new int[faces];
            packed = IntBuffer.allocate(faces * 3 * STRIDE);
         }
      }
   }

   private static Capture reject(Rejection reason)
   {
      return new Capture(null, reason);
   }

   static SolidOpacityMesh capture(Model model, int alpha, Projection projection,
                                   int orientation, int x, int y, int z)
   {
      return capture(model, alpha, projection, orientation, x, y, z, Renderable.RENDERMODE_DEFAULT).mesh;
   }

   static Capture capture(Model model, int alpha, Projection projection,
                          int orientation, int x, int y, int z, int renderMode)
   {
      return capture(model, alpha, projection, orientation, x, y, z, renderMode, new Scratch());
   }

   static Capture capture(Model model, int alpha, Projection projection,
                          int orientation, int x, int y, int z, int renderMode, Scratch scratch)
   {
      if (model == null || projection == null) return reject(Rejection.MISSING_MODEL_OR_PROJECTION);
      // This guard precedes all model processing, including native transparency handling.
      if (model.getOverrideAmount() != 0) return reject(Rejection.COLOUR_OVERRIDE);
      if (alpha <= 0 || alpha >= 255) return reject(Rejection.NOT_PARTIAL_OPACITY);
      int faces = model.getFaceCount(), count = model.getVerticesCount();
      if (faces > MAX_FACES || count > 16384) return reject(Rejection.MODEL_SIZE_LIMIT);
      if (faces <= 0 || count <= 0) return reject(Rejection.NO_VISIBLE_FACES);
      boolean prioritySorted = renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH;
      float[] xs = model.getVerticesX(), ys = model.getVerticesY(), zs = model.getVerticesZ();
      int[] a = model.getFaceIndices1(), b = model.getFaceIndices2(), c = model.getFaceIndices3();
      int[] colors1 = model.getFaceColors1(), colors2 = model.getFaceColors2(), colors3 = model.getFaceColors3();
      byte[] nativeAlpha = model.getFaceTransparencies(), bias = model.getFaceBias();
      byte[] priorities = prioritySorted ? model.getFaceRenderPriorities() : null;
      short[] textures = model.getFaceTextures();
      if (xs == null || ys == null || zs == null || xs.length < count || ys.length < count || zs.length < count
              || a == null || b == null || c == null || a.length < faces || b.length < faces || c.length < faces
              || colors1 == null || colors2 == null || colors3 == null
              || colors1.length < faces || colors2.length < faces || colors3.length < faces
              || (nativeAlpha != null && nativeAlpha.length < faces)
              || (textures != null && textures.length < faces) || (bias != null && bias.length < faces)
              || (priorities != null && priorities.length < faces)) return reject(Rejection.INVALID_GEOMETRY);
      scratch.ensureCapacity(count, faces);
      float[] projected = scratch.projected;
      projection.project(x, y, z, projected);
      float distance = projected[2];
      if (!Float.isFinite(distance)) return reject(Rejection.INVALID_GEOMETRY);
      if (distance < 50) return reject(Rejection.NEAR_CAMERA);
      float sine = Perspective.SINE[orientation & 2047] / 65536f;
      float cosine = Perspective.COSINE[orientation & 2047] / 65536f;
      float[] worldX = scratch.worldX, worldY = scratch.worldY, worldZ = scratch.worldZ;
      float[] screenX = scratch.screenX, screenY = scratch.screenY;
      int[] vertexDepth = scratch.vertexDepth;
      for (int i = 0; i < count; i++)
      {
         worldX[i] = xs[i] * cosine + zs[i] * sine + x;
         worldY[i] = ys[i] + y;
         worldZ[i] = zs[i] * cosine - xs[i] * sine + z;
         if (!Float.isFinite(worldX[i]) || !Float.isFinite(worldY[i]) || !Float.isFinite(worldZ[i]))
            return reject(Rejection.INVALID_GEOMETRY);
         projection.project(worldX[i], worldY[i], worldZ[i], projected);
         if (!Float.isFinite(projected[0]) || !Float.isFinite(projected[1]) || !Float.isFinite(projected[2]))
            return reject(Rejection.INVALID_GEOMETRY);
         if (projected[2] < 50) return reject(Rejection.NEAR_CAMERA);
         screenX[i] = projected[0] / projected[2];
         screenY[i] = projected[1] / projected[2];
         vertexDepth[i] = (int) projected[2] - (int) distance;
      }
      if (prioritySorted) model.calculateBoundsCylinder();
      int radius = prioritySorted ? model.getRadius() : 0;
      int[] faceDepth = scratch.faceDepth, order = scratch.order, transparency = scratch.transparency;
      int visible = 0;
      boolean hasTranslucentFaces = false;
      byte modelTransparency = model.getTransparency();
      for (int face = 0; face < faces; face++)
      {
         if (colors3[face] == -2) continue;
         int ia = a[face], ib = b[face], ic = c[face];
         if (ia < 0 || ib < 0 || ic < 0 || ia >= count || ib >= count || ic >= count)
            return reject(Rejection.INVALID_GEOMETRY);
         // Match the GPU's priority averages: only front-facing triangles contribute.
         if (prioritySorted && (screenX[ia] - screenX[ib]) * (screenY[ic] - screenY[ib])
                 - (screenX[ic] - screenX[ib]) * (screenY[ia] - screenY[ib]) <= 0) continue;
         if (priorities != null && (priorities[face] & 255) > 11) return reject(Rejection.INVALID_FACE_PRIORITY);
         faceDepth[face] = radius + (vertexDepth[ia] + vertexDepth[ib] + vertexDepth[ic]) / 3;
         transparency[face] = nativeTransparency(modelTransparency, nativeAlpha == null ? 0 : nativeAlpha[face] & 255);
         hasTranslucentFaces |= transparency[face] > 0 && transparency[face] < 255;
         order[visible++] = face;
      }
      int[] sorted = prioritySorted || hasTranslucentFaces ? orderFaces(order, visible, faceDepth, priorities, scratch) : order;
      IntBuffer packed = scratch.packed;
      packed.clear();
      float[] u = scratch.u, v = scratch.v;
      int opaqueVertices = 0, translucentFaces = 0;
      // Opaque body first, then naturally translucent details, just like the native renderer.
      // The plugin's configured opacity is applied only after both are in the private image.
      for (int pass = 0; pass < 2; pass++)
      {
         int[] passOrder = pass == 0 && !prioritySorted ? order : sorted;
         for (int faceIndex = 0; faceIndex < visible; faceIndex++)
         {
            int face = passOrder[faceIndex];
            int t = transparency[face];
            if (t == 255 || (pass == 0 ? t != 0 : t == 0)) continue;
            int ia = a[face], ib = b[face], ic = c[face];
            int texture = textures == null ? 0 : textures[face] + 1;
            if (texture != 0)
            {
               computeFaceUvs(model, face, u, v);
               for (int i = 0; i < 3; i++)
                  if (!Float.isFinite(u[i]) || !Float.isFinite(v[i])) return reject(Rejection.INVALID_TEXTURE_MAPPING);
            }
            else
            {
               // Untextured faces don't use UVs; an unused degenerate mapping must not reject an actor.
               u[0] = u[2] = v[0] = v[1] = 0; u[1] = v[2] = 1;
            }
            int alphaBias = (t << 24) | (bias == null ? 0 : (bias[face] & 255) << 16);
            put(packed, worldX[ia], worldY[ia], worldZ[ia], alphaBias | colors1[face], texture, u[0], v[0]);
            put(packed, worldX[ib], worldY[ib], worldZ[ib], alphaBias | (colors3[face] == -1 ? colors1[face] : colors2[face]), texture, u[1], v[1]);
            put(packed, worldX[ic], worldY[ic], worldZ[ic], alphaBias | (colors3[face] == -1 ? colors1[face] : colors3[face]), texture, u[2], v[2]);
            if (pass == 1) translucentFaces++;
         }
         if (pass == 0) opaqueVertices = packed.position() / STRIDE;
      }
      if (packed.position() == 0) return reject(Rejection.NO_VISIBLE_FACES);
      // Each queued actor owns its exact-length snapshot until finish(). Reusing the scratch
      // for another actor/animation must never overwrite an already queued model.
      return new Capture(new SolidOpacityMesh(Arrays.copyOf(packed.array(), packed.position()),
              1f - alpha / 255f, distance, opaqueVertices, translucentFaces, prioritySorted), null);
   }

   // RuneLite's model-wide native fade calculation, separate from our final image opacity.
   static int nativeTransparency(byte modelTransparency, int faceTransparency)
   {
      if (modelTransparency == -1) return 255;
      int t = modelTransparency & 255;
      return t > 0 && faceTransparency < 253
              ? faceTransparency + ((253 - faceTransparency) * t >> 8) : faceTransparency;
   }

   /** Stable far-to-near order, with the client's special 10/11 priority interleaving. */
   static int[] orderFaces(int[] faces, int[] depths, byte[] priorities)
   {
      Scratch scratch = new Scratch();
      scratch.ensureCapacity(0, faces.length);
      return Arrays.copyOf(orderFaces(faces, faces.length, depths, priorities, scratch), faces.length);
   }

   private static int[] orderFaces(int[] faces, int size, int[] depths, byte[] priorities, Scratch scratch)
   {
      long[] keys = scratch.sortKeys;
      for (int i = 0; i < size; i++) keys[i] = (-(long) depths[faces[i]] << 32) | faces[i];
      Arrays.sort(keys, 0, size);
      int[] sorted = scratch.sorted;
      for (int i = 0; i < size; i++) sorted[i] = (int) keys[i];
      if (priorities == null) return sorted;
      int[] counts = scratch.counts, starts = scratch.starts, positions = scratch.positions;
      long[] sums = scratch.sums;
      Arrays.fill(counts, 0);
      Arrays.fill(sums, 0);
      for (int i = 0; i < size; i++)
      {
         int face = sorted[i];
         counts[priorities[face]]++;
         sums[priorities[face]] += depths[face];
      }
      int offset = 0;
      for (int p = 0; p < 12; p++)
      {
         starts[p] = positions[p] = offset;
         offset += counts[p];
      }
      int[] grouped = scratch.grouped;
      for (int i = 0; i < size; i++)
      {
         int face = sorted[i];
         grouped[positions[priorities[face]]++] = face;
      }
      // Groups 10 and 11 are contiguous, in the same order as the native dynamic list.
      int[] result = scratch.result;
      int next = starts[10], out = 0;
      for (int p = 0; p < 10; p++)
      {
         if (p == 0 || p == 3 || p == 5)
         {
            int a = p == 0 ? 1 : p == 3 ? 3 : 6, b = p == 0 ? 2 : p == 3 ? 4 : 8;
            int n = counts[a] + counts[b];
            long threshold = n == 0 ? 0 : (sums[a] + sums[b]) / n;
            while (next < size && depths[grouped[next]] > threshold) result[out++] = grouped[next++];
         }
         for (int i = starts[p]; i < starts[p] + counts[p]; i++) result[out++] = grouped[i];
      }
      while (next < size) result[out++] = grouped[next++];
      return result;
   }

   private static void put(IntBuffer out, float x, float y, float z, int color, int texture, float u, float v)
   {
      out.put(Float.floatToIntBits(x)).put(Float.floatToIntBits(y)).put(Float.floatToIntBits(z)).put(color);
      out.put((((int) (u * 256f) & 65535) << 16) | (texture & 65535));
      out.put((int) (v * 256f) & 65535);
   }

	static void computeFaceUvs(Model model, int face, float[] u, float[] v)
	{
		final float[] vertexX = model.getVerticesX();
		final float[] vertexY = model.getVerticesY();
		final float[] vertexZ = model.getVerticesZ();

		final int[] indices1 = model.getFaceIndices1();
		final int[] indices2 = model.getFaceIndices2();
		final int[] indices3 = model.getFaceIndices3();

		final byte[] textureFaces = model.getTextureFaces();
		final int[] texIndices1 = model.getTexIndices1();
		final int[] texIndices2 = model.getTexIndices2();
		final int[] texIndices3 = model.getTexIndices3();

		if (textureFaces != null && textureFaces[face] != -1)
		{
			final int triangleA = indices1[face];
			final int triangleB = indices2[face];
			final int triangleC = indices3[face];

			int tfaceIdx = textureFaces[face] & 0xff;
			int texA = texIndices1[tfaceIdx];
			int texB = texIndices2[tfaceIdx];
			int texC = texIndices3[tfaceIdx];

			// v1 = vertex[texA]
			float v1x = vertexX[texA];
			float v1y = vertexY[texA];
			float v1z = vertexZ[texA];
			// v2 = vertex[texB] - v1
			float v2x = vertexX[texB] - v1x;
			float v2y = vertexY[texB] - v1y;
			float v2z = vertexZ[texB] - v1z;
			// v3 = vertex[texC] - v1
			float v3x = vertexX[texC] - v1x;
			float v3y = vertexY[texC] - v1y;
			float v3z = vertexZ[texC] - v1z;

			// v4 = vertex[triangleA] - v1
			float v4x = vertexX[triangleA] - v1x;
			float v4y = vertexY[triangleA] - v1y;
			float v4z = vertexZ[triangleA] - v1z;
			// v5 = vertex[triangleB] - v1
			float v5x = vertexX[triangleB] - v1x;
			float v5y = vertexY[triangleB] - v1y;
			float v5z = vertexZ[triangleB] - v1z;
			// v6 = vertex[triangleC] - v1
			float v6x = vertexX[triangleC] - v1x;
			float v6y = vertexY[triangleC] - v1y;
			float v6z = vertexZ[triangleC] - v1z;

			// v7 = v2 x v3
			float v7x = v2y * v3z - v2z * v3y;
			float v7y = v2z * v3x - v2x * v3z;
			float v7z = v2x * v3y - v2y * v3x;

			// v8 = v3 x v7
			float v8x = v3y * v7z - v3z * v7y;
			float v8y = v3z * v7x - v3x * v7z;
			float v8z = v3x * v7y - v3y * v7x;

			// f = 1 / (v8 ⋅ v2)
			float f = 1.0F / (v8x * v2x + v8y * v2y + v8z * v2z);

			// u0 = (v8 ⋅ v4) * f
			u[0] = (v8x * v4x + v8y * v4y + v8z * v4z) * f;
			// u1 = (v8 ⋅ v5) * f
			u[1] = (v8x * v5x + v8y * v5y + v8z * v5z) * f;
			// u2 = (v8 ⋅ v6) * f
			u[2] = (v8x * v6x + v8y * v6y + v8z * v6z) * f;

			// v8 = v2 x v7
			v8x = v2y * v7z - v2z * v7y;
			v8y = v2z * v7x - v2x * v7z;
			v8z = v2x * v7y - v2y * v7x;

			// f = 1 / (v8 ⋅ v3)
			f = 1.0F / (v8x * v3x + v8y * v3y + v8z * v3z);

			// v0 = (v8 ⋅ v4) * f
			v[0] = (v8x * v4x + v8y * v4y + v8z * v4z) * f;
			// v1 = (v8 ⋅ v5) * f
			v[1] = (v8x * v5x + v8y * v5y + v8z * v5z) * f;
			// v2 = (v8 ⋅ v6) * f
			v[2] = (v8x * v6x + v8y * v6y + v8z * v6z) * f;
		}
		else
		{
			// Without a texture face, the client assigns tex = triangle, but the resulting
			// calculations can be reduced:
			//
			// v1 = vertex[texA]
			// v2 = vertex[texB] - v1
			// v3 = vertex[texC] - v1
			//
			// v4 = 0
			// v5 = v2
			// v6 = v3
			//
			// v7 = v2 x v3
			//
			// v8 = v3 x v7
			// u0 = (v8 . v4) / (v8 . v2) // 0 because v4 is 0
			// u1 = (v8 . v5) / (v8 . v2) // 1 because v5=v2
			// u2 = (v8 . v6) / (v8 . v2) // 0 because v8 is perpendicular to v3/v6
			//
			// v8 = v2 x v7
			// v0 = (v8 . v4) / (v8 ⋅ v3) // 0 because v4 is 0
			// v1 = (v8 . v5) / (v8 ⋅ v3) // 0 because v8 is perpendicular to v5/v2
			// v2 = (v8 . v6) / (v8 ⋅ v3) // 1 because v6=v3

			u[0] = 0f;
			v[0] = 0f;

			u[1] = 1f;
			v[1] = 0f;

			u[2] = 0f;
			v[2] = 1f;
		}
	}
}
