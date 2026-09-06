package com.visibilityenhancer;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Local experiment for the standard GPU renderer, not a renderer extension API.
 * Uses only our own GL objects and validates the active GPU vertex format/program.
 * A private colour/depth target makes each actor solid before one final blend.
 * HD and sub-worldviews use the regular opacity path.
 */
@Slf4j
final class SolidGpuCompositor
{
   private static final int MAX_ACTORS = 24;
   private static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;
   private static final long MAX_TARGET_BYTES = 128L * 1024 * 1024;
   private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
   private static final float[] CLEAR_DEPTH = {0};
   private static final float[] CLEAR_COLOR = {0, 0, 0, 0};
   private static final Comparator<SolidOpacityMesh> BACK_TO_FRONT =
           Comparator.comparingDouble((SolidOpacityMesh m) -> m.distance).reversed();
   private static final String VERTEX = "#version 330\n"
           + "out vec2 uv; void main() { vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);"
           + " uv = p; gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }";
   private static final String FRAGMENT = "#version 330\n"
           + "uniform sampler2D actorImage; uniform float actorOpacity; in vec2 uv; out vec4 color;"
           + "void main() { vec4 c = texture(actorImage, uv);"
           // Resolve averages opaque colour with the clear colour, yielding premultiplied RGB.
           // Un-premultiply before standard alpha blending to avoid dark MSAA fringes.
           + " color = c.a > 0.0 ? vec4(c.rgb / c.a, c.a * actorOpacity) : vec4(0.0); }";

   private final BooleanSupplier enabled;
   private final BiPredicate<Scene, GameObject> drawObject;
   private final List<SolidOpacityMesh> actors = new ArrayList<>();
   private GLCapabilities owner;
   private Scene frameScene;
   private boolean failed;
   private boolean reportedActive;
   private boolean reportedUnsupportedMesh;
   private String frameRejection = "FRAME_NOT_READY";
   private final Diagnostics diagnostics = new Diagnostics();
   private int frameBytes;
   private int gpuProgram;
   private int baseLocation, entityLocation, tintLocation;
   private int width, height, samples;
   private int actorFbo, actorColor, actorDepth, resolveFbo, imageTexture;
   private int program, opacityLocation, vao, vbo;
   private IntBuffer upload;
   private SolidOpacityMesh.Scratch meshScratch;
   private final int[] frameViewport = new int[4], savedBase = new int[3], savedTint = new int[4];
   private final float[] savedEntity = new float[16];

   SolidGpuCompositor(BooleanSupplier enabled, BiPredicate<Scene, GameObject> drawObject)
   {
      this.enabled = enabled;
      this.drawObject = drawObject;
   }

   void begin(Scene scene)
   {
      if (scene == null || scene.getWorldViewId() != WorldView.TOPLEVEL) return;
      actors.clear();
      frameBytes = 0;
      frameScene = null;
      frameRejection = "FRAME_NOT_READY";
      if (!enabled.getAsBoolean())
      {
         close();
         failed = false;
         return;
      }
      if (failed) { frameRejection = "RENDERER_FAILED"; return; }
      try
      {
         GLCapabilities current = GL.getCapabilities();
         if (!current.OpenGL33) { frameRejection = "OPENGL_VERSION"; return; }
         if (owner != null && owner != current) forgetResources();
         owner = current;
         // These live read-only checks must not be cached across frames: GPU can rebuild
         // shaders or framebuffer attachments, including reusing an old numeric GL name.
         gpuProgram = glGetInteger(GL_CURRENT_PROGRAM);
         if (glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING) == 0 || gpuProgram == 0 || glGetInteger(GL_DEPTH_FUNC) != GL_GREATER
                 || glGetAttribLocation(gpuProgram, "vertf") != 0
                 || glGetAttribLocation(gpuProgram, "abhsl") != 1
                 || glGetAttribLocation(gpuProgram, "tex") != 2) { frameRejection = "GPU_PROGRAM_LAYOUT"; return; }
         baseLocation = glGetUniformLocation(gpuProgram, "base");
         entityLocation = glGetUniformLocation(gpuProgram, "entityProj");
         tintLocation = glGetUniformLocation(gpuProgram, "entityTint");
         if (baseLocation < 0 || entityLocation < 0 || tintLocation < 0) { frameRejection = "GPU_UNIFORM_LAYOUT"; return; }
         // GPU currently uses a floating-point reversed-depth renderbuffer.
         if (glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                 GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL_RENDERBUFFER) { frameRejection = "DEPTH_ATTACHMENT"; return; }
         int depth = glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                 GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
         int previousRenderbuffer = glGetInteger(GL_RENDERBUFFER_BINDING);
         int sampleCount;
         // This is the only binding changed by the checks themselves.
         try
         {
            glBindRenderbuffer(GL_RENDERBUFFER, depth);
            if (glGetRenderbufferParameteri(GL_RENDERBUFFER, GL_RENDERBUFFER_INTERNAL_FORMAT) != GL_DEPTH_COMPONENT32F)
            { frameRejection = "DEPTH_FORMAT"; return; }
            sampleCount = glGetRenderbufferParameteri(GL_RENDERBUFFER, GL_RENDERBUFFER_SAMPLES);
         }
         finally
         {
            glBindRenderbuffer(GL_RENDERBUFFER, previousRenderbuffer);
         }
         glGetIntegerv(GL_VIEWPORT, frameViewport);
         int w = frameViewport[2], h = frameViewport[3];
         if (w <= 0 || h <= 0 || (long) w * h * (8L * Math.max(1, sampleCount) + 4) > MAX_TARGET_BYTES)
         { frameRejection = "FRAMEBUFFER_SIZE_LIMIT"; return; }
         // Save all graphics state only when resource creation actually needs to change it.
         // Existing plugin-owned targets are already reused until their dimensions/MSAA change.
         if (actorFbo == 0 || width != w || height != h || samples != sampleCount)
         {
            try (State state = new State()) { ensureResources(w, h, sampleCount); }
         }
         checkError("initialization");
         frameScene = scene;
      }
      catch (RuntimeException | LinkageError ex)
      {
         fail(ex);
      }
   }

   boolean submit(Projection projection, Scene scene, GameObject object, Model original, Model prepared,
                  int orientation, int x, int y, int z)
   {
      if (!enabled.getAsBoolean() || !(prepared instanceof GpuOpacityModel) || prepared == original
              || object == null || original == null) return false;
      Renderable renderable = object.getRenderable();
      if (!(renderable instanceof Player) && !(renderable instanceof NPC)) return false;
      try
      {
         String unavailable = scene == null || scene.getWorldViewId() != WorldView.TOPLEVEL ? "SUB_WORLDVIEW"
                 : frameScene == null ? frameRejection : scene != frameScene ? "SCENE_MISMATCH"
                 : actors.size() >= MAX_ACTORS ? "ACTOR_LIMIT" : null;
         if (unavailable != null) { report(renderable, original, unavailable); return false; }
         long bytes = (long) original.getFaceCount() * 3 * SolidOpacityMesh.STRIDE * Integer.BYTES;
         if (bytes > MAX_FRAME_BYTES - frameBytes) { report(renderable, original, "FRAME_MESH_BUDGET"); return false; }
         if (meshScratch == null) meshScratch = new SolidOpacityMesh.Scratch();
         SolidOpacityMesh.Capture capture = SolidOpacityMesh.capture(original, ((GpuOpacityModel) prepared).getTargetAlpha(),
                 projection, orientation, x, y, z, renderable.getRenderMode(), meshScratch);
         if (capture.mesh == null) { report(renderable, original, capture.rejection.name()); return false; }
         SolidOpacityMesh mesh = capture.mesh;
         // Keep every other plugin's GPU draw-object filter in force.
         if (!drawObject.test(scene, object)) { report(renderable, original, "DRAW_FILTER"); return true; }
         actors.add(mesh);
         frameBytes += mesh.vertices.length * Integer.BYTES;
         report(renderable, original, "QUEUED" + (mesh.prioritySorted ? "_PRIORITY" : "_DEPTH")
                 + (mesh.translucentFaces > 0 ? "_WITH_NATIVE_ALPHA" : ""));
         return true;
      }
      catch (RuntimeException ex)
      {
         report(renderable, original, "INVALID_MODEL_DATA");
         // Malformed/unsupported geometry must not make a character disappear.
         if (!reportedUnsupportedMesh)
         {
            reportedUnsupportedMesh = true;
            log.debug("Solid transparency kept an unsupported model on normal opacity", ex);
         }
         return false;
      }
   }

   void finish(Scene scene)
   {
      if (frameScene == null || scene != frameScene) return;
      try
      {
         if (actors.isEmpty()) return;
         if (GL.getCapabilities() != owner) throw new IllegalStateException("GPU context changed during scene draw");
         try (State state = new State())
         {
            if (state.program != gpuProgram) throw new IllegalStateException("GPU scene program changed during draw");
            glGetUniformiv(gpuProgram, baseLocation, savedBase);
            glGetUniformiv(gpuProgram, tintLocation, savedTint);
            glGetUniformfv(gpuProgram, entityLocation, savedEntity);
            try
            {
               glDisable(GL_SCISSOR_TEST);
               glColorMask(true, true, true, true);
               glBindVertexArray(vao);
               glBindBuffer(GL_ARRAY_BUFFER, vbo);
               actors.sort(BACK_TO_FRONT);
               for (SolidOpacityMesh actor : actors) drawActor(actor, scene, state);
               checkError("compositing");
            }
            finally
            {
               glUseProgram(gpuProgram);
               glUniform3iv(baseLocation, savedBase);
               glUniform4iv(tintLocation, savedTint);
               glUniformMatrix4fv(entityLocation, false, savedEntity);
            }
         }
         if (!reportedActive)
         {
            reportedActive = true;
            log.debug("Experimental solid transparency composited {} actor(s) with the standard GPU renderer", actors.size());
         }
      }
      catch (RuntimeException | LinkageError ex)
      {
         // Resources/programs were validated before uploads were intercepted. An unexpected
         // driver failure may lose this frame; all subsequent frames use ordinary opacity.
         fail(ex);
      }
      finally
      {
         actors.clear();
         frameScene = null;
      }
   }

   private void drawActor(SolidOpacityMesh actor, Scene scene, State state)
   {
      int x = state.viewport[0], y = state.viewport[1];
      glBindFramebuffer(GL_READ_FRAMEBUFFER, state.drawFbo);
      glBindFramebuffer(GL_DRAW_FRAMEBUFFER, actorFbo);
      glDepthMask(true);
      glClearBufferfv(GL_DEPTH, 0, CLEAR_DEPTH);
      // Same size, format and sample count: walls/terrain/opaque actors still occlude us.
      // Never copy the character depth back; the ground must remain visible through it.
      glBlitFramebuffer(x, y, x + width, y + height, 0, 0, width, height, GL_DEPTH_BUFFER_BIT, GL_NEAREST);
      glViewport(0, 0, width, height);
      glClearBufferfv(GL_COLOR, 0, CLEAR_COLOR);
      glUseProgram(gpuProgram);
      glUniform3i(baseLocation, 0, 0, 0);
      glUniformMatrix4fv(entityLocation, false, IDENTITY);
      glUniform4i(tintLocation, scene.getOverrideHue(), scene.getOverrideSaturation(), scene.getOverrideLuminance(), scene.getOverrideAmount());
      glEnable(GL_DEPTH_TEST);
      glDepthFunc(GL_GREATER);
      glEnable(GL_CULL_FACE);
      glDisable(GL_BLEND);
      if (upload == null || upload.capacity() < actor.vertices.length)
         upload = BufferUtils.createIntBuffer(actor.vertices.length);
      upload.clear();
      upload.put(actor.vertices).flip();
      glBufferData(GL_ARRAY_BUFFER, upload, GL_STREAM_DRAW);
      // Player/cape priority models use the client's painter ordering, not self-depth.
      // The copied scene depth is still tested, so walls and terrain keep occluding them.
      glDepthMask(!actor.prioritySorted);
      glDrawArrays(GL_TRIANGLES, 0, actor.opaqueVertexCount);
      if (actor.prioritySorted && actor.translucentFaces > 0 && actor.opaqueVertexCount > 0)
      {
         // GPU's sorted-no-depth path follows its colour pass with a depth-only body pass.
         // Keep the priority-ordered colour, but stop native rear effects bleeding through it.
         glColorMask(false, false, false, false);
         glDepthMask(true);
         glDrawArrays(GL_TRIANGLES, 0, actor.opaqueVertexCount);
         glColorMask(true, true, true, true);
      }
      // Keep native translucent gear/effects inside the same image instead of rejecting the actor.
      // RGB becomes premultiplied against transparent black; alpha records actual coverage.
      glDepthMask(false);
      glEnable(GL_BLEND);
      glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
      glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
      glDrawArrays(GL_TRIANGLES, actor.opaqueVertexCount, actor.translucentFaces * 3);

      glBindFramebuffer(GL_READ_FRAMEBUFFER, actorFbo);
      glBindFramebuffer(GL_DRAW_FRAMEBUFFER, resolveFbo);
      glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
      glBindFramebuffer(GL_DRAW_FRAMEBUFFER, state.drawFbo);
      glViewport(x, y, width, height);
      glUseProgram(program);
      glUniform1f(opacityLocation, actor.opacity);
      glActiveTexture(GL_TEXTURE0);
      glBindTexture(GL_TEXTURE_2D, imageTexture);
      glDisable(GL_CULL_FACE);
      glDisable(GL_DEPTH_TEST);
      glDepthMask(false);
      glEnable(GL_BLEND);
      glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
      glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
      glDrawArrays(GL_TRIANGLES, 0, 3);
   }

   private void ensureResources(int w, int h, int sampleCount)
   {
      if (actorFbo != 0 && width == w && height == h && samples == sampleCount) return;
      deleteResources();
      width = w;
      height = h;
      samples = sampleCount;
      actorFbo = glGenFramebuffers();
      glBindFramebuffer(GL_FRAMEBUFFER, actorFbo);
      actorColor = renderbuffer(GL_RGBA8, GL_COLOR_ATTACHMENT0);
      actorDepth = renderbuffer(GL_DEPTH_COMPONENT32F, GL_DEPTH_ATTACHMENT);
      requireComplete();
      imageTexture = glGenTextures();
      glActiveTexture(GL_TEXTURE0);
      glBindTexture(GL_TEXTURE_2D, imageTexture);
      glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
      glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
      resolveFbo = glGenFramebuffers();
      glBindFramebuffer(GL_FRAMEBUFFER, resolveFbo);
      glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, imageTexture, 0);
      requireComplete();
      int vert = shader(GL_VERTEX_SHADER, VERTEX);
      int frag = 0;
      try
      {
         frag = shader(GL_FRAGMENT_SHADER, FRAGMENT);
         program = glCreateProgram();
         glAttachShader(program, vert);
         glAttachShader(program, frag);
         glLinkProgram(program);
         if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
            throw new IllegalStateException(glGetProgramInfoLog(program));
      }
      finally
      {
         glDeleteShader(vert);
         if (frag != 0) glDeleteShader(frag);
      }
      glUseProgram(program);
      glUniform1i(glGetUniformLocation(program, "actorImage"), 0);
      opacityLocation = glGetUniformLocation(program, "actorOpacity");
      vao = glGenVertexArrays();
      vbo = glGenBuffers();
      glBindVertexArray(vao);
      glBindBuffer(GL_ARRAY_BUFFER, vbo);
      glEnableVertexAttribArray(0);
      glVertexAttribPointer(0, 3, GL_FLOAT, false, 24, 0L);
      glEnableVertexAttribArray(1);
      glVertexAttribIPointer(1, 1, GL_INT, 24, 12L);
      glEnableVertexAttribArray(2);
      glVertexAttribIPointer(2, 4, GL_SHORT, 24, 16L);
   }

   private int renderbuffer(int format, int attachment)
   {
      int id = glGenRenderbuffers();
      glBindRenderbuffer(GL_RENDERBUFFER, id);
      glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, format, width, height);
      glFramebufferRenderbuffer(GL_FRAMEBUFFER, attachment, GL_RENDERBUFFER, id);
      return id;
   }

   private static void requireComplete()
   {
      int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
      if (status != GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("Incomplete solid-opacity framebuffer: " + status);
   }

   private static void checkError(String phase)
   {
      int error = glGetError();
      if (error != GL_NO_ERROR) throw new IllegalStateException("OpenGL error during solid-opacity " + phase + ": " + error);
   }

   private static int shader(int type, String source)
   {
      int id = glCreateShader(type);
      glShaderSource(id, source);
      glCompileShader(id);
      if (glGetShaderi(id, GL_COMPILE_STATUS) == GL_FALSE)
      {
         String error = glGetShaderInfoLog(id);
         glDeleteShader(id);
         throw new IllegalStateException(error);
      }
      return id;
   }

   private void fail(Throwable ex)
   {
      failed = true;
      frameRejection = "RENDERER_FAILED";
      frameScene = null;
      actors.clear();
      log.warn("Experimental solid transparency disabled; using normal opacity. Toggle it off/on to retry.", ex);
   }

   private void report(Renderable actor, Model model, String status)
   {
      if (!log.isDebugEnabled() || !diagnostics.shouldReport(System.identityHashCode(actor), status, System.nanoTime())) return;
      // Never log player names, chat, or retain actor/model references. NPC IDs identify test cases.
      try
      {
         byte[] alpha = model.getFaceTransparencies();
         int partial = 0;
         if (alpha != null)
            for (int i = 0; i < Math.min(alpha.length, model.getFaceCount()); i++)
               if ((alpha[i] & 255) > 0 && (alpha[i] & 255) < 255) partial++;
         log.debug("Solid transparency: {} actorKey={} status={} faces={} vertices={} nativePartialFaces={} nativeModelAlpha={} renderMode={}",
                 actor instanceof NPC ? "NPC id=" + ((NPC) actor).getId() : "Player",
                 Integer.toHexString(System.identityHashCode(actor)), status, model.getFaceCount(), model.getVerticesCount(),
                 partial, model.getTransparency() & 255, actor.getRenderMode());
      }
      catch (RuntimeException ignored)
      {
         log.debug("Solid transparency: status={} (model metadata unavailable)", status);
      }
   }

   /** Bounded, rate-limited debug diagnostics; no per-frame spam or live actor references. */
   static final class Diagnostics
   {
      private final Map<String, Long> reports = new LinkedHashMap<>();
      private long windowStart;
      private int windowCount;

      boolean shouldReport(int actor, String status, long now)
      {
         String key = actor + ":" + status;
         Long last = reports.get(key);
         if (last != null && now - last < 30_000_000_000L) return false;
         if (now - windowStart >= 1_000_000_000L) { windowStart = now; windowCount = 0; }
         if (windowCount >= 8) return false;
         windowCount++;
         reports.remove(key);
         reports.put(key, now);
         if (reports.size() > 128) reports.remove(reports.keySet().iterator().next());
         return true;
      }
   }

   void close()
   {
      actors.clear();
      frameScene = null;
      meshScratch = null;
      if (owner == null) return;
      try
      {
         // Renderer switches may already have destroyed the old context. Never delete
         // names in a new GPU/HD context: those names could belong to someone else.
         if (GL.getCapabilities() == owner) deleteResources();
      }
      catch (IllegalStateException ex)
      {
         // No current context; destroying its owner releases its GL objects.
      }
      finally
      {
         forgetResources();
         owner = null;
      }
   }

   private void deleteResources()
   {
      if (actorFbo != 0) glDeleteFramebuffers(actorFbo);
      if (resolveFbo != 0) glDeleteFramebuffers(resolveFbo);
      if (actorColor != 0) glDeleteRenderbuffers(actorColor);
      if (actorDepth != 0) glDeleteRenderbuffers(actorDepth);
      if (imageTexture != 0) glDeleteTextures(imageTexture);
      if (program != 0) glDeleteProgram(program);
      if (vao != 0) glDeleteVertexArrays(vao);
      if (vbo != 0) glDeleteBuffers(vbo);
      forgetResources();
   }

   private void forgetResources()
   {
      actorFbo = resolveFbo = actorColor = actorDepth = imageTexture = program = vao = vbo = 0;
      upload = null;
      reportedActive = false;
      reportedUnsupportedMesh = false;
   }

   /** Save and restore every GL binding/setting that this compositor changes. */
   private static final class State implements AutoCloseable
   {
      final int program = glGetInteger(GL_CURRENT_PROGRAM);
      final int drawFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
      final int readFbo = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
      final int renderbuffer = glGetInteger(GL_RENDERBUFFER_BINDING);
      final int vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
      final int arrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
      final int unpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
      final int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
      final int texture;
      final int[] viewport = new int[4];
      final boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
      final int depthFunction = glGetInteger(GL_DEPTH_FUNC);
      final boolean depth = glIsEnabled(GL_DEPTH_TEST), blend = glIsEnabled(GL_BLEND);
      final boolean cull = glIsEnabled(GL_CULL_FACE), scissor = glIsEnabled(GL_SCISSOR_TEST);
      final int srcRgb = glGetInteger(GL_BLEND_SRC_RGB), dstRgb = glGetInteger(GL_BLEND_DST_RGB);
      final int srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA), dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
      final int equationRgb = glGetInteger(GL_BLEND_EQUATION_RGB), equationAlpha = glGetInteger(GL_BLEND_EQUATION_ALPHA);
      final ByteBuffer colorMask = BufferUtils.createByteBuffer(4);

      State()
      {
         glGetIntegerv(GL_VIEWPORT, viewport);
         glGetBooleanv(GL_COLOR_WRITEMASK, colorMask);
         glActiveTexture(GL_TEXTURE0);
         texture = glGetInteger(GL_TEXTURE_BINDING_2D);
         glActiveTexture(activeTexture);
      }

      @Override
      public void close()
      {
         glUseProgram(program);
         glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFbo);
         glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo);
         glBindRenderbuffer(GL_RENDERBUFFER, renderbuffer);
         glBindVertexArray(vao);
         glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer);
         glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
         glActiveTexture(GL_TEXTURE0);
         glBindTexture(GL_TEXTURE_2D, texture);
         glActiveTexture(activeTexture);
         glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
         glDepthMask(depthMask);
         glDepthFunc(depthFunction);
         glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0, colorMask.get(2) != 0, colorMask.get(3) != 0);
         glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
         glBlendEquationSeparate(equationRgb, equationAlpha);
         enabled(GL_DEPTH_TEST, depth);
         enabled(GL_BLEND, blend);
         enabled(GL_CULL_FACE, cull);
         enabled(GL_SCISSOR_TEST, scissor);
      }

      private static void enabled(int cap, boolean enabled)
      {
         if (enabled) glEnable(cap); else glDisable(cap);
      }
   }
}
