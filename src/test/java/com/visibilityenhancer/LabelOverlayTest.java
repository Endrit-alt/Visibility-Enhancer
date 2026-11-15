package com.visibilityenhancer;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.reflect.Field;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import org.junit.Before;
import org.junit.Test;
import static com.visibilityenhancer.LayeringTestSupport.*;
import static org.junit.Assert.*;

public class LabelOverlayTest
{
   private final VisibilityEnhancer plugin = new VisibilityEnhancer();
   private final Map<String, Object> settings = new HashMap<>();
   private final BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
   private final int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
   private VisibilityEnhancerOverlay ground;
   private VisibilityEnhancerLabelOverlay labels;
   private int cycle = 100, scans;
   private GameState gameState = GameState.LOGGED_IN;
   private Scene scene;

   private static void set(Object target, String name, Object value) throws Exception
   {
      Field f = target.getClass().getDeclaredField(name);
      f.setAccessible(true);
      f.set(target, value);
   }

   @Before public void setup() throws Exception
   {
      byte[][][] tileSettings = new byte[4][184][184];
      int[][][] tileHeights = new int[4][185][185];
      scene = proxy(Scene.class, (p, m, a) -> m.getName().equals("getExtendedTileSettings")
              ? tileSettings : defaultValue(m.getReturnType()));
      WorldView world = proxy(WorldView.class, (p, m, a) -> {
         switch (m.getName())
         {
            case "getId": return WorldView.TOPLEVEL;
            case "getBaseX": case "getBaseY": return 3200;
            case "getSizeX": case "getSizeY": return 104;
            case "getScene": return scene;
            case "getTileSettings": return tileSettings;
            case "getTileHeights": return tileHeights;
            default: return defaultValue(m.getReturnType());
         }
      });
      Player player = proxy(Player.class, (p, m, a) -> {
         switch (m.getName())
         {
            case "getWorldLocation": return new WorldPoint(3208, 3208, 0);
            case "getLocalLocation": return new LocalPoint(1088, 1088);
            default: return defaultValue(m.getReturnType());
         }
      });
      Client client = proxy(Client.class, (p, m, a) -> {
         switch (m.getName())
         {
            case "getLocalPlayer": return player;
            case "getPlayers": scans++; return Arrays.asList(player, player);
            case "getNpcs": return Collections.emptyList();
            case "getGameState": return gameState;
            case "getGameCycle": return cycle;
            case "getScene": return scene;
            case "findWorldViewFromWorldPoint": case "getWorldView": case "getTopLevelWorldView": return world;
            case "getCameraX": return 1088;
            case "getCameraY": return 512;
            case "getCameraZ": return -300;
            case "getCameraPitch": return 256;
            case "getViewportWidth": case "getViewportHeight": return 512;
            case "getScale": return 256;
            default: return defaultValue(m.getReturnType());
         }
      });
      VisibilityEnhancerConfig defaults = new VisibilityEnhancerConfig() {};
      settings.put("enableStackWarnings", true);
      settings.put("showStackWarningNumber", true);
      settings.put("stackWarningOnlyInCombat", false);
      settings.put("stackWarningDelay", 0);
      settings.put("stackThreshold", 2);
      settings.put("stackWarningColor", Color.RED);
      settings.put("highlightSelf", HighlightStyle.NONE);
      settings.put("highlightOthers", HighlightStyle.NONE);
      VisibilityEnhancerConfig config = proxy(VisibilityEnhancerConfig.class, (p, m, a) ->
              settings.containsKey(m.getName()) ? settings.get(m.getName()) : m.invoke(defaults, a));
      set(plugin, "client", client);
      set(plugin, "config", config);
      ground = new VisibilityEnhancerOverlay(client, plugin, config, null, null);
      labels = new VisibilityEnhancerLabelOverlay(ground);
      set(plugin, "overlay", ground);
   }

   private void groundFrame()
   {
      ground.beginFrame();
      Graphics2D g = image.createGraphics();
      try { ground.render(g); } finally { g.dispose(); }
   }

   private void visibleWarning()
   {
      groundFrame();
      cycle += 30;
      groundFrame();
      assertTrue(Arrays.stream(pixels).anyMatch(p -> p != 0));
   }

   private void labelPass()
   {
      Graphics2D g = image.createGraphics();
      try { labels.render(g); } finally { g.dispose(); }
   }

   @Test public void warningGeometryAndLabelsUseSeparatePassesWithOneStateUpdate()
   {
      visibleWarning();
      int scansBefore = scans;
      int[] tilePixels = pixels.clone();
      Graphics2D g = image.createGraphics();
      try { ground.render(g); } finally { g.dispose(); }
      assertArrayEquals(tilePixels, pixels);
      Arrays.fill(pixels, 0);
      labelPass();
      assertEquals(scansBefore, scans);
      assertTrue("Only the label/shadow is drawn into this empty image", Arrays.stream(pixels).anyMatch(p -> p != 0));
      int[] once = pixels.clone();
      labelPass();
      assertArrayEquals(once, pixels);
   }

   @Test public void skippedLabelPassAndOffToggleDoNotReplayLabels()
   {
      visibleWarning();
      ground.beginFrame();
      Arrays.fill(pixels, 0);
      labelPass();
      assertTrue(Arrays.stream(pixels).allMatch(p -> p == 0));
      groundFrame();
      settings.put("showStackWarningNumber", false);
      Arrays.fill(pixels, 0);
      labelPass();
      settings.put("showStackWarningNumber", true);
      labelPass();
      assertTrue(Arrays.stream(pixels).allMatch(p -> p == 0));
   }

   @Test public void sceneChangesShutdownAndInactiveStateClearLabelsAndFade() throws Exception
   {
      for (int scenario = 0; scenario < 3; scenario++)
      {
         set(plugin, "pluginToggledOn", true);
         gameState = GameState.LOGGED_IN;
         visibleWarning();
         if (scenario == 0)
         {
            gameState = GameState.LOADING;
            GameStateChanged event = new GameStateChanged();
            event.setGameState(gameState);
            plugin.onGameStateChanged(event);
         }
         else if (scenario == 1) ground.resetSceneState();
         else { set(plugin, "pluginToggledOn", false); ground.beginFrame(); }
         Arrays.fill(pixels, 0);
         labelPass();
         assertTrue(Arrays.stream(pixels).allMatch(p -> p == 0));
         set(plugin, "pluginToggledOn", true);
         gameState = GameState.LOGGED_IN;
         groundFrame(); // Fresh fade starts at zero, rather than reviving an old count.
         labelPass();
         assertTrue(Arrays.stream(pixels).allMatch(p -> p == 0));
      }
   }

   @Test public void labelsDelegateOnlyToSharedLabelState()
   {
      Dimension expected = new Dimension(1, 2);
      VisibilityEnhancerOverlay shared = new VisibilityEnhancerOverlay(null, null, null, null, null)
      {
         @Override Dimension renderLabels(Graphics2D g) { return expected; }
         @Override public Dimension render(Graphics2D g) { throw new AssertionError("No tiles"); }
         @Override Dimension renderOutlines(Graphics2D g) { throw new AssertionError("No outlines"); }
         @Override Dimension renderOverheads(Graphics2D g) { throw new AssertionError("No overheads"); }
      };
      assertSame(expected, new VisibilityEnhancerLabelOverlay(shared).render(null));
   }
}
