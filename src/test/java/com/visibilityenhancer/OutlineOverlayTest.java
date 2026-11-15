package com.visibilityenhancer;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.BeforeRender;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import org.junit.Before;
import org.junit.Test;

import static com.visibilityenhancer.LayeringTestSupport.*;
import static org.junit.Assert.*;

public class OutlineOverlayTest
{
   private final VisibilityEnhancer plugin = new VisibilityEnhancer();
   private final Map<String, Object> settings = new HashMap<>();
   private final LayeringTestSupport.ActorState self = new LayeringTestSupport.ActorState(false, 1);
   private final LayeringTestSupport.ActorState other = new LayeringTestSupport.ActorState(false, 2);
   private final LayeringTestSupport.ActorState npc = new LayeringTestSupport.ActorState(true, 3);
   private final LayeringTestSupport.ActorState thrall = new LayeringTestSupport.ActorState(true,
           VisibilityEnhancer.THRALL_IDS.iterator().next());
   private final List<Actor> lines = new ArrayList<>();
   private final List<Actor> tiles = new ArrayList<>();
   private final List<Color> colors = new ArrayList<>();
   private final BufferedImage canvas = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
   private boolean failDraw;
   private VisibilityEnhancerOverlay ground;
   private VisibilityEnhancerOutlineOverlay outlines;

   private void set(String name, Object value) throws Exception
   {
      Field field = VisibilityEnhancer.class.getDeclaredField(name);
      field.setAccessible(true);
      field.set(plugin, value);
   }

   @Before public void setUp() throws Exception
   {
      VisibilityEnhancerConfig defaults = new VisibilityEnhancerConfig() {};
      VisibilityEnhancerConfig config = proxy(VisibilityEnhancerConfig.class, (p, m, a) ->
              settings.containsKey(m.getName()) ? settings.get(m.getName()) : m.invoke(defaults, a));
      Client client = proxy(Client.class, (p, m, a) ->
      {
         switch (m.getName())
         {
            case "getLocalPlayer": return self.player();
            case "getNpcs": return Arrays.asList(npc.actor, thrall.actor);
            case "getPlayers": return Arrays.asList(self.actor, other.actor);
            case "getGameState": return GameState.LOGGED_IN;
            default: return defaultValue(m.getReturnType());
         }
      });
      set("client", client);
      set("config", config);
      plugin.getGhostedPlayers().add(other.player());
      settings.put("hideStackedOutlines", false);
      for (String key : Arrays.asList("highlightSelf", "highlightOthers", "highlightNpcs", "highlightThralls"))
         settings.put(key, HighlightStyle.BOTH);
      ground = new VisibilityEnhancerOverlay(client, plugin, config, null, null)
      {
         @Override void renderFloorTile(Graphics2D g, Actor actor, Color color, HighlightStyle style)
         {
            tiles.add(actor);
            canvas.setRGB(2, 2, Color.RED.getRGB());
         }
         @Override void renderOutlineLayers(Player actor, Color color) { line(actor, color); }
         @Override void renderOutlineLayers(NPC actor, Color color) { line(actor, color); }
         private void line(Actor actor, Color color)
         {
            if (failDraw) throw new IllegalStateException("Synthetic outline failure");
            lines.add(actor);
            colors.add(color);
            canvas.setRGB(2, 2, Color.GREEN.getRGB());
         }
      };
      set("overlay", ground);
      outlines = new VisibilityEnhancerOutlineOverlay(ground);
   }

   @Test @SuppressWarnings("unchecked")
   public void standardPrioritiesOrderTilesOutlinesLabelsThenOverheadsInEitherEnableOrder() throws Exception
   {
      Field field = OverlayManager.class.getDeclaredField("OVERLAY_COMPARATOR");
      field.setAccessible(true);
      Comparator<Overlay> comparator = (Comparator<Overlay>) field.get(null);
      assertEquals(OverlayLayer.ABOVE_SCENE, ground.getLayer());
      assertEquals(OverlayLayer.ABOVE_SCENE, outlines.getLayer());
      assertEquals(OverlayPosition.DYNAMIC, outlines.getPosition());
      Overlay labels = new VisibilityEnhancerLabelOverlay(ground);
      Overlay overheads = new VisibilityEnhancerOverheadOverlay(ground);
      assertEquals(OverlayLayer.UNDER_WIDGETS, overheads.getLayer());
      assertEquals(OverlayLayer.ABOVE_SCENE, labels.getLayer());
      assertEquals(OverlayPosition.DYNAMIC, labels.getPosition());
      assertEquals(Overlay.PRIORITY_LOW, ground.getPriority(), 0);
      assertEquals(Overlay.PRIORITY_HIGH, outlines.getPriority(), 0);
      assertEquals(Overlay.PRIORITY_HIGHEST, labels.getPriority(), 0);
      for (boolean otherFirst : new boolean[]{false, true})
      {
         Overlay mask = new Overlay()
         {
            { setPosition(OverlayPosition.DYNAMIC); setLayer(OverlayLayer.ABOVE_SCENE); setPriority(PRIORITY_MED); }
            @Override public Dimension render(Graphics2D g) { return null; }
         };
         List<Overlay> order = new ArrayList<>(Arrays.asList(labels, outlines, ground));
         order.add(otherFirst ? 0 : order.size(), mask);
         order.sort(comparator);
         assertEquals(Arrays.asList(ground, mask, outlines, labels), order);
      }
   }

   @Test public void lateOverlayDelegatesOnlyToSharedOutlineState()
   {
      Dimension expected = new Dimension(2, 3);
      VisibilityEnhancerOverlay shared = new VisibilityEnhancerOverlay(null, null, null, null, null)
      {
         @Override public Dimension render(Graphics2D g) { throw new AssertionError("No second ground pass"); }
         @Override Dimension renderOverheads(Graphics2D g) { throw new AssertionError("No overhead draw"); }
         @Override Dimension renderOutlines(Graphics2D g) { return expected; }
      };
      assertSame(expected, new VisibilityEnhancerOutlineOverlay(shared).render(null));
   }

   @Test public void allGroupsDrawOnceAfterTheMaskWhileGroundPixelsStayMasked()
   {
      ground.render(null);
      assertTrue(lines.isEmpty());
      assertEquals(4, tiles.size());
      assertEquals(Color.RED.getRGB(), canvas.getRGB(2, 2));
      canvas.setRGB(2, 2, 0); // Simulate the intervening scene mask.
      outlines.render(null);
      assertEquals(Arrays.asList(thrall.actor, self.actor, other.actor, npc.actor), lines);
      assertEquals(Color.GREEN.getRGB(), canvas.getRGB(2, 2));
      assertEquals(4, tiles.size()); // Tile drawing was not repeated in the late pass.
      outlines.render(null);
      assertEquals(4, lines.size());
   }

   @Test public void stylesAndPerGroupColorsArePreserved()
   {
      Color color = new Color(12, 34, 56, 78);
      for (String key : Arrays.asList("selfOutlineColor", "othersOutlineColor", "npcOutlineColor", "thrallsOutlineColor"))
         settings.put(key, color);
      for (HighlightStyle style : HighlightStyle.values())
      {
         for (String key : Arrays.asList("highlightSelf", "highlightOthers", "highlightNpcs", "highlightThralls"))
            settings.put(key, style);
         lines.clear(); tiles.clear(); colors.clear();
         ground.beginFrame();
         ground.render(null);
         assertTrue(lines.isEmpty());
         outlines.render(null);
         boolean line = style == HighlightStyle.OUTLINE || style == HighlightStyle.BOTH || style == HighlightStyle.BOTH_TRUE;
         boolean tile = style != HighlightStyle.NONE && style != HighlightStyle.OUTLINE;
         assertEquals(style.name(), line ? 4 : 0, lines.size());
         assertEquals(style.name(), tile ? 4 : 0, tiles.size());
         for (Color actual : colors) assertEquals(color, actual);
      }
   }

   @Test public void disabledLineAndGlowDoNotQueueModelWork()
   {
      settings.put("enableGlow", false);
      settings.put("enableOutline", false);
      ground.render(null);
      outlines.render(null);
      assertTrue(lines.isEmpty());
      assertEquals(4, tiles.size());
   }

   @Test public void overrideSafetyStillSelectsNoPlayerOrNpcOutline()
   {
      self.override = other.override = npc.override = thrall.override = 50;
      settings.put("highlightThralls", HighlightStyle.NONE);
      // Both NPCs now follow generic NPC selection and its colour-override guard.
      ground.render(null);
      outlines.render(null);
      assertTrue(lines.isEmpty());
      assertTrue(tiles.isEmpty());
      assertEquals(50, self.override);
      assertEquals(50, other.override);
      assertEquals(50, npc.override);
   }

   @Test public void peekOnlyRetainsLocalHighlight() throws Exception
   {
      set("peekHeld", true);
      ground.render(null);
      outlines.render(null);
      assertEquals(Arrays.asList(self.actor), lines);
      assertEquals(Arrays.asList(self.actor), tiles);
   }

   @Test public void inactiveLatePassClearsWithoutDrawingOrReplaying() throws Exception
   {
      ground.render(null);
      set("pluginToggledOn", false);
      outlines.render(null);
      set("pluginToggledOn", true);
      outlines.render(null);
      assertTrue(lines.isEmpty());
   }

   @Test public void duplicateGroundPassDoesNotRepeatSelectionOrDrawing()
   {
      ground.render(null);
      ground.render(null);
      outlines.render(null);
      assertEquals(4, lines.size());
      assertEquals(4, tiles.size());
   }

   @Test public void beforeRenderDropsQueueWhenLatePassWasSkipped() throws Exception
   {
      ground.render(null);
      set("pluginToggledOn", false);
      plugin.onBeforeRender(new BeforeRender());
      set("pluginToggledOn", true);
      outlines.render(null);
      assertTrue(lines.isEmpty());
   }

   @Test public void clientThreadShutdownDropsQueuedActorReferences() throws Exception
   {
      ground.render(null);
      Method method = VisibilityEnhancer.class.getDeclaredMethod("finishRendererShutdown");
      method.setAccessible(true);
      assertEquals(true, method.invoke(plugin));
      outlines.render(null);
      assertTrue(lines.isEmpty());
   }

   @Test public void failedOutlinePassClearsQueueInFinally()
   {
      ground.render(null);
      failDraw = true;
      try
      {
         outlines.render(null);
         fail("Expected synthetic failure");
      }
      catch (IllegalStateException expected) { }
      failDraw = false;
      outlines.render(null);
      assertTrue(lines.isEmpty());
   }
}
