package com.visibilityenhancer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.IterableHashTable;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Renderable;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.HitsplatApplied;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class VisibilityEnhancerRegressionTest
{
   private VisibilityEnhancer plugin;
   private Stub<Client> client;
   private Stub<VisibilityEnhancerConfig> config;
   private Stub<Player> local;
   private Stub<Player> other;
   private Stub<Model> model;

   @Before
   public void setUp() throws Exception
   {
      plugin = new VisibilityEnhancer();
      client = new Stub<>(Client.class);
      config = new Stub<>(VisibilityEnhancerConfig.class);
      local = player();
      other = player();
      model = new Stub<>(Model.class);
      model.set("getOverrideAmount", (byte) 0);
      model.set("getFaceTransparencies", new byte[]{0, 20});
      other.set("getModel", model.value);
      client.set("getLocalPlayer", local.value);
      client.set("getGameState", GameState.LOGGED_IN);
      client.set("getGameCycle", 0);
      client.set("getTickCount", 100);
      setField("client", client.value);
      setField("config", config.value);
      players("currentInRange").add(other.value);
   }

   @Test
   public void overrideHasPriorityOverOpacityAndRoomFloor() throws Exception
   {
      setField("currentRegionId", 12611);
      activateOverride();
      assertEquals(100, opacity());
      assertFalse(fallback());
   }

   @Test
   public void overrideProtectsUnsupportedModelAndStaleFallback() throws Exception
   {
      markCombat();
      model.set("getFaceTransparencies", null);
      assertTrue(fallback());
      activateOverride();
      assertTrue(draw());
      assertFalse(players("fallbackHiddenPlayers").contains(other.value));
      plugin.onClientTick(null);
      assertFalse(players("culledPlayers").contains(other.value));
   }

   @Test
   public void overrideRestoresAlphaBeforeImmuneModelEarlyReturn() throws Exception
   {
      byte[] alpha = {0, 20};
      model.set("getFaceTransparencies", alpha);
      invoke("applyOpacity", new Class<?>[]{Player.class, int.class}, other.value, 1);
      assertTrue(Byte.toUnsignedInt(alpha[0]) > 0);
      players("immunePlayers").add(other.value);
      other.set("getAnimation", 999999);
      activateOverride();
      invoke("applyOpacity", new Class<?>[]{Player.class, int.class}, other.value, 1);
      assertArrayEquals(new byte[]{0, 20}, alpha);
   }

   @Test
   public void criticalGraphicProtectsUnsupportedPlayer() throws Exception
   {
      markCombat();
      model.set("getFaceTransparencies", null);
      assertTrue(fallback());
      other.set("getGraphic", 2145);
      plugin.onClientTick(null);
      assertEquals(1, opacity());
      assertTrue(draw());
      assertFalse(fallback());
      assertFalse(players("culledPlayers").contains(other.value));
   }

   @Test
   public void criticalGraphicAtPartialOpacityAlsoPreventsFallback() throws Exception
   {
      config.set("playerOpacity", 50);
      players("criticalGraphicPlayers").add(other.value);
      model.set("getFaceTransparencies", null);
      assertFalse(fallback());
   }

   @Test
   public void bothFollowRoomsProtectUnsupportedPlayersInCombat() throws Exception
   {
      markCombat();
      model.set("getFaceTransparencies", null);
      for (int region : new int[]{12611, 15186})
      {
         setField("currentRegionId", region);
         assertEquals(1, opacity());
         assertFalse(fallback());
         plugin.onClientTick(null);
         assertFalse(players("culledPlayers").contains(other.value));
         assertTrue(draw());
      }
   }

   @Test
   public void nonCombatFloorProtectsUnsupportedPlayer() throws Exception
   {
      model.set("getFaceTransparencies", null);
      assertEquals(1, opacity());
      assertFalse(fallback());
   }

   @Test
   public void firstSeenAnimatedPlayerStillKeepsFollowRoomProtection() throws Exception
   {
      markCombat();
      setField("currentRegionId", 15186);
      other.set("getAnimation", 999999);
      assertEquals(1, opacity());
      assertFalse(fallback());
   }

   @Test
   public void unprotectedUnsupportedPlayerStillUsesFallback() throws Exception
   {
      markCombat();
      model.set("getFaceTransparencies", null);
      config.set("playerOpacity", 50);
      assertTrue(fallback());
      assertFalse(draw());
   }

   @Test
   public void unprotectedZeroOpacityCombatPlayerIsStillCulled() throws Exception
   {
      markCombat();
      plugin.onClientTick(null);
      assertEquals(0, opacity());
      assertTrue(players("culledPlayers").contains(other.value));
      assertFalse(draw());
   }

   @Test
   public void fullyOpaquePlayerDoesNotUseFallback() throws Exception
   {
      config.set("playerOpacity", 100);
      model.set("getFaceTransparencies", null);
      assertFalse(fallback());
   }

   @Test
   public void explicitPeekBehaviorIsUnchanged() throws Exception
   {
      activateOverride();
      setField("currentRegionId", 12611);
      setField("peekHeld", true);
      assertFalse(draw());
   }

   @Test
   public void overrideStillRequiresTwoCycles() throws Exception
   {
      model.set("getOverrideAmount", (byte) 1);
      assertFalse(forced());
      client.set("getGameCycle", 1);
      assertFalse(forced());
      client.set("getGameCycle", 2);
      assertTrue(forced());
   }

   @Test
   public void longOverrideLingersFromLastObservationNotActivation() throws Exception
   {
      activateOverride();
      client.set("getGameCycle", 250);
      assertTrue(forced());
      model.set("getOverrideAmount", (byte) 0);
      client.set("getGameCycle", 251);
      assertTrue(forced());
      client.set("getGameCycle", 280);
      assertTrue(forced());
      client.set("getGameCycle", 281);
      assertFalse(forced());
   }

   @Test
   public void briefOverrideGapDoesNotRestartActivationDelay() throws Exception
   {
      activateOverride();
      client.set("getGameCycle", 250);
      assertTrue(forced());
      model.set("getOverrideAmount", (byte) 0);
      client.set("getGameCycle", 251);
      assertTrue(forced());
      model.set("getOverrideAmount", (byte) 1);
      client.set("getGameCycle", 260);
      assertTrue(forced());
      model.set("getOverrideAmount", (byte) 0);
      client.set("getGameCycle", 290);
      assertTrue(forced());
      client.set("getGameCycle", 291);
      assertFalse(forced());
   }

   @Test
   public void exemptAnimationCannotInterruptActiveOverrideButStillCancelsLinger() throws Exception
   {
      activateOverride();
      Set<Integer> exemptAnimations = field("EXEMPT_ANIMATIONS");
      other.set("getAnimation", exemptAnimations.iterator().next());
      client.set("getGameCycle", 250);
      assertTrue(forced());
      model.set("getOverrideAmount", (byte) 0);
      assertFalse(forced());
   }

   @Test
   public void localPlayerAlsoGetsRefreshedOverrideGrace() throws Exception
   {
      client.set("getLocalPlayer", other.value);
      activateOverride();
      client.set("getGameCycle", 250);
      assertEquals(100, opacity());
      model.set("getOverrideAmount", (byte) 0);
      client.set("getGameCycle", 280);
      assertEquals(100, opacity());
      client.set("getGameCycle", 281);
      assertEquals(0, opacity());
   }

   @Test
   public void cleanupUsesEntryKeysAndPreservesCriticalEffects() throws Exception
   {
      SpotAnims animations = attachEffects();
      config.set("hideOthersProjectiles", true);
      // Even a misleading legacy getter must not cause a critical entry to be cleared.
      other.set("getGraphic", 9999);
      plugin.onClientTick(null);
      assertEquals(1, animations.entries.size());
      assertEquals(2145, animations.get(9999).getId());
   }

   @Test
   public void peekAlsoUsesSafeSpotAnimationCleanup() throws Exception
   {
      SpotAnims animations = attachEffects();
      setField("peekHeld", true);
      plugin.onClientTick(null);
      assertEquals(1, animations.entries.size());
      assertNotNull(animations.get(9999));
   }

   @Test
   public void cleanupDisabledLeavesEffectsAlone() throws Exception
   {
      SpotAnims animations = attachEffects();
      plugin.onClientTick(null);
      assertEquals(3, animations.entries.size());
   }

   @Test
   public void localPlayerAndUnselectedPlayersEffectsAreNotRemoved() throws Exception
   {
      config.set("hideOthersProjectiles", true);
      SpotAnims localEffects = new SpotAnims();
      localEffects.add(2, 9999);
      SpotAnims otherEffects = new SpotAnims();
      otherEffects.add(3, 9998);
      local.set("getSpotAnims", localEffects);
      other.set("getSpotAnims", otherEffects);
      local.on("removeSpotAnim", args -> { throw new AssertionError("Local effect removed"); });
      other.on("removeSpotAnim", args -> { throw new AssertionError("Unselected effect removed"); });
      plugin.onClientTick(null);
      assertEquals(1, localEffects.entries.size());
      assertEquals(1, otherEffects.entries.size());
   }

   @Test
   public void followingOrTradingAnotherPlayerDoesNotStartCombat() throws Exception
   {
      Stub<Player> target = player();
      target.set("getCombatLevel", 126);
      other.set("getInteracting", target.value);
      plugin.onClientTick(null);
      assertFalse(combatTimers().containsKey(other.value));
      assertEquals(1, opacity());
      assertFalse(players("culledPlayers").contains(other.value));
   }

   @Test
   public void combatNpcTargetStillRefreshesCombat() throws Exception
   {
      Stub<NPC> target = new Stub<>(NPC.class);
      target.set("getCombatLevel", 100);
      other.set("getInteracting", target.value);
      plugin.onClientTick(null);
      assertEquals(Integer.valueOf(100), combatTimers().get(other.value));
      assertEquals(0, opacity());
   }

   @Test
   public void nonCombatNpcDoesNotStartCombat() throws Exception
   {
      other.set("getInteracting", new Stub<>(NPC.class).value);
      plugin.onClientTick(null);
      assertFalse(combatTimers().containsKey(other.value));
   }

   @Test
   public void followingDoesNotRefreshOrEraseExistingCombatTimer() throws Exception
   {
      markCombat();
      Stub<Player> target = player();
      target.set("getCombatLevel", 126);
      other.set("getInteracting", target.value);
      client.set("getTickCount", 120);
      plugin.onClientTick(null);
      assertEquals(Integer.valueOf(100), combatTimers().get(other.value));
      assertEquals(0, opacity());
      client.set("getTickCount", 133);
      plugin.onClientTick(null);
      assertEquals(1, opacity());
   }

   @Test
   public void attackAnimationStillDetectsPlayerCombat() throws Exception
   {
      other.set("getInteracting", player().value);
      other.set("getAnimation", 999999);
      AnimationChanged event = new AnimationChanged();
      event.setActor(other.value);
      plugin.onAnimationChanged(event);
      assertEquals(Integer.valueOf(100), combatTimers().get(other.value));
   }

   @Test
   public void hitsplatsStillDetectCombat() throws Exception
   {
      HitsplatApplied event = new HitsplatApplied();
      event.setActor(other.value);
      plugin.onHitsplatApplied(event);
      assertEquals(Integer.valueOf(100), combatTimers().get(other.value));
   }

   private Stub<Player> player()
   {
      Stub<Player> stub = new Stub<>(Player.class);
      stub.set("getAnimation", -1);
      stub.set("getGraphic", -1);
      return stub;
   }

   private void activateOverride() throws Exception
   {
      model.set("getOverrideAmount", (byte) 1);
      client.set("getGameCycle", 0);
      assertFalse(forced());
      client.set("getGameCycle", 2);
      assertTrue(forced());
   }

   private boolean forced() throws Exception
   {
      return (boolean) invoke("shouldForceOpaqueForOverride", new Class<?>[]{Player.class, Model.class}, other.value, model.value);
   }

   private int opacity() throws Exception
   {
      return (int) invoke("getEffectiveOpacity", new Class<?>[]{Player.class}, other.value);
   }

   private boolean fallback() throws Exception
   {
      return (boolean) invoke("shouldHideWithFallback", new Class<?>[]{Player.class}, other.value);
   }

   private boolean draw() throws Exception
   {
      return (boolean) invoke("shouldDraw", new Class<?>[]{Renderable.class, boolean.class}, other.value, false);
   }

   private void markCombat() throws Exception
   {
      combatTimers().put(other.value, 100);
   }

   private Map<Player, Integer> combatTimers() throws Exception
   {
      return field("combatTimerMap");
   }

   private Set<Player> players(String name) throws Exception
   {
      return field(name);
   }

   @SuppressWarnings("unchecked")
   private <T> T field(String name) throws Exception
   {
      Field field = VisibilityEnhancer.class.getDeclaredField(name);
      field.setAccessible(true);
      return (T) field.get(plugin);
   }

   private void setField(String name, Object value) throws Exception
   {
      Field field = VisibilityEnhancer.class.getDeclaredField(name);
      field.setAccessible(true);
      field.set(plugin, value);
   }

   private Object invoke(String name, Class<?>[] types, Object... arguments) throws Exception
   {
      Method method = VisibilityEnhancer.class.getDeclaredMethod(name, types);
      method.setAccessible(true);
      return method.invoke(plugin, arguments);
   }

   private SpotAnims attachEffects()
   {
      SpotAnims animations = new SpotAnims();
      animations.add(2, 9999);
      animations.add(3, 9998);
      // The critical entry's key deliberately matches a non-critical effect ID.
      animations.add(9999, 2145);
      other.set("getSpotAnims", animations);
      other.on("removeSpotAnim", args -> animations.entries.remove((Integer) args[0]));
      other.on("setGraphic", args -> { throw new AssertionError("Legacy graphic setter used"); });
      plugin.getGhostedPlayers().add(other.value);
      return animations;
   }

   private static class SpotAnims implements IterableHashTable<ActorSpotAnim>
   {
      private final Map<Integer, ActorSpotAnim> entries = new LinkedHashMap<>();

      void add(int key, int effectId)
      {
         Stub<ActorSpotAnim> animation = new Stub<>(ActorSpotAnim.class);
         animation.set("getHash", (long) key);
         animation.set("getId", effectId);
         entries.put(key, animation.value);
      }

      @Override
      public ActorSpotAnim get(long key)
      {
         return entries.get((int) key);
      }

      @Override
      public void put(ActorSpotAnim value, long key)
      {
         entries.put((int) key, value);
      }

      @Override
      public Iterator<ActorSpotAnim> iterator()
      {
         // Fail-fast iterator also checks that cleanup collects keys before removal.
         return entries.values().iterator();
      }
   }

   /** Minimal interface doubles keep the regression suite free of new dependencies. */
   private static class Stub<T>
   {
      private final Map<String, Function<Object[], Object>> methods = new HashMap<>();
      private final T value;

      Stub(Class<T> type)
      {
         value = type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) ->
         {
            if (method.getDeclaringClass() == Object.class)
            {
               switch (method.getName())
               {
                  case "hashCode": return System.identityHashCode(proxy);
                  case "equals": return proxy == args[0];
                  case "toString": return type.getSimpleName() + " test double";
                  default: throw new AssertionError(method);
               }
            }
            Function<Object[], Object> implementation = methods.get(method.getName());
            if (implementation != null)
            {
               return implementation.apply(args);
            }
            Class<?> result = method.getReturnType();
            if (result == boolean.class) return false;
            if (result == byte.class) return (byte) 0;
            if (result == short.class) return (short) 0;
            if (result == int.class) return 0;
            if (result == long.class) return 0L;
            if (result == float.class) return 0F;
            if (result == double.class) return 0D;
            if (result == char.class) return '\0';
            return null;
         }));
      }

      void set(String method, Object result)
      {
         on(method, args -> result);
      }

      void on(String method, Function<Object[], Object> implementation)
      {
         methods.put(method, implementation);
      }
   }
}
