package com.visibilityenhancer;

import com.google.common.collect.ImmutableSet;
import com.google.inject.Provides;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import javax.inject.Inject;
import lombok.AllArgsConstructor;
import lombok.Getter;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.kit.KitType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.Hooks;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;

@PluginDescriptor(
        name = "Visibility Enhancer",
        description = "Teammate opacity, ground-view filters, and outlines for raids.",
        tags = {"raid", "raids", "raids visibility enhancer", "opacity", "outline", "equipment"}
)
public class VisibilityEnhancer extends Plugin
{
   private static final int OVERRIDE_OPAQUE_DELAY_CYCLES = 2;
   private static final int OVERRIDE_CLEAR_DELAY_CYCLES = 2;

   @Inject
   private Client client;

   @Inject
   private ClientThread clientThread;

   @Inject
   private VisibilityEnhancerConfig config;

   @Inject
   private OverlayManager overlayManager;

   @Inject
   private VisibilityEnhancerOverlay overlay;

   @Inject
   private Hooks hooks;

   @Inject
   private KeyManager keyManager;

   @Getter
   private boolean pluginToggledOn = true;

   @Getter
   private boolean hotkeyHeld = false;

   private Instant lastPress;

   @Getter
   private final Set<Player> ghostedPlayers = new HashSet<>();

   private final Map<Player, int[]> originalEquipmentMap = new HashMap<>();
   private final Set<Projectile> myProjectiles = new HashSet<>();
   private final Map<byte[], byte[]> originalTransparencies = new WeakHashMap<>();

   private final Map<Player, Integer> overrideStartCycle = new HashMap<>();
   private final Map<Player, Integer> overrideLastSeenCycle = new HashMap<>();
   private final Set<Player> overrideForcedPlayers = new HashSet<>();

   private final Hooks.RenderableDrawListener drawListener = this::shouldDraw;

   private Player cachedLocalPlayer;

   private final List<Player> inRange = new ArrayList<>();
   private final Set<Player> currentInRange = new HashSet<>();
   private final Set<Player> noLongerGhosted = new HashSet<>();

   private boolean wasActive = false;

   private final HotkeyListener hotkeyListener = new HotkeyListener(() -> config.toggleHotkey())
   {
      @Override
      public void hotkeyPressed()
      {
         if (!pluginToggledOn)
         {
            pluginToggledOn = true;
            hotkeyHeld = true;
            lastPress = null;
         }
         else if (lastPress != null
                 && !hotkeyHeld
                 && config.doubleTapDelay() > 0
                 && Duration.between(lastPress, Instant.now()).compareTo(Duration.ofMillis(config.doubleTapDelay())) < 0)
         {
            pluginToggledOn = false;
            lastPress = null;

            clientThread.invokeLater(() ->
            {
               clearAllGhosting();
            });
         }
         else
         {
            hotkeyHeld = true;
            lastPress = Instant.now();
         }
      }

      @Override
      public void hotkeyReleased()
      {
         hotkeyHeld = false;
      }
   };

   private static final Set<Integer> EXEMPT_ANIMATIONS = ImmutableSet.<Integer>builder()
           .add(AnimationID.CONSUMING)
           .add(1378)
           .add(7642).add(7643)
           .add(7514)
           .add(1062)
           .add(1203)
           .add(7644).add(7640).add(7638)
           .add(10172)
           .add(5062)
           .add(9168)
           .add(8104)
           .build();

   public static final Set<Integer> THRALL_IDS = ImmutableSet.of(
           NpcID.ARCEUUS_THRALL_GHOST_LESSER, NpcID.ARCEUUS_THRALL_SKELETON_LESSER, NpcID.ARCEUUS_THRALL_ZOMBIE_LESSER,
           NpcID.ARCEUUS_THRALL_GHOST_SUPERIOR, NpcID.ARCEUUS_THRALL_SKELETON_SUPERIOR, NpcID.ARCEUUS_THRALL_ZOMBIE_SUPERIOR,
           NpcID.ARCEUUS_THRALL_GHOST_GREATER, NpcID.ARCEUUS_THRALL_SKELETON_GREATER, NpcID.ARCEUUS_THRALL_ZOMBIE_GREATER
   );

   private static final Set<Integer> BOSS_PROJECTILES = ImmutableSet.<Integer>builder()
           .add(2206, 2228, 2242, 2243, 2224, 2225, 2241, 2137, 2138, 2266)
           .add(1583, 1584, 1585, 1586, 1591, 1604, 1606, 1607, 1555, 1560, 1577, 1578)
           .add(1339, 1340, 1341, 1343, 1345, 1354, 1327, 1291)
           .add(2010, 2011, 1764)
           .build();

   @Getter
   @AllArgsConstructor
   public static class CustomHitsplat
   {
      private final int amount;
      private final int despawnTick;
   }

   @Getter
   private final Map<Player, List<CustomHitsplat>> customHitsplats = new HashMap<>();

   @Override
   protected void startUp()
   {
      overlayManager.add(overlay);
      hooks.registerRenderableDrawListener(drawListener);
      keyManager.registerKeyListener(hotkeyListener);
      pluginToggledOn = true;
      hotkeyHeld = false;
      wasActive = false;
   }

   @Override
   protected void shutDown()
   {
      overlayManager.remove(overlay);
      hooks.unregisterRenderableDrawListener(drawListener);
      keyManager.unregisterKeyListener(hotkeyListener);

      clientThread.invokeLater(this::clearAllGhosting);

      cachedLocalPlayer = null;
      wasActive = false;
   }

   public boolean isActive()
   {
      if (!pluginToggledOn)
      {
         return false;
      }

      if (!config.enableAreaFiltering())
      {
         return true;
      }

      Player local = client.getLocalPlayer();
      if (local == null)
      {
         return false;
      }

      LocalPoint lp = local.getLocalLocation();
      if (lp == null)
      {
         return false;
      }

      int regionId = WorldPoint.fromLocalInstance(client, lp).getRegionID();

      switch (regionId)
      {
         case 12613:
            return config.tobMaiden();
         case 13125:
            return config.tobBloat();
         case 13122:
            return config.tobNylo();
         case 13123:
         case 13379:
            return config.tobSote();
         case 12612:
            return config.tobXarpus();
         case 12611:
            return config.tobVerzik();

         case 15700:
            return config.toaZebak();
         case 14164:
            return config.toaKephri();
         case 14676:
            return config.toaAkkha();
         case 15188:
            return config.toaBaba();
         case 15184:
         case 15696:
            return config.toaWardens();

         case 12889:
            return config.coxOlm();

         case 11601:
            return config.otherNex();
         case 15515:
            return config.otherNightmare();
         case 11827:
         case 11828:
         case 12084:
            return config.otherRoyalTitans();
      }

      if (client.getVarbitValue(Varbits.IN_RAID) == 1)
      {
         return config.coxRest();
      }

      return false;
   }

   @Subscribe
   public void onHitsplatApplied(HitsplatApplied event)
   {
      if (!isActive())
      {
         return;
      }

      if (event.getActor() instanceof Player)
      {
         Player p = (Player) event.getActor();
         if (config.othersTransparentPrayers() && ghostedPlayers.contains(p))
         {
            int amount = event.getHitsplat().getAmount();

            if (amount == 0 && config.hideZeroHitsplats())
            {
               return;
            }

            customHitsplats.computeIfAbsent(p, k -> new ArrayList<>())
                    .add(new CustomHitsplat(amount, client.getTickCount() + 4));
         }
      }
   }

   @Subscribe
   public void onClientTick(ClientTick event)
   {
      if (!isActive())
      {
         return;
      }

      cachedLocalPlayer = client.getLocalPlayer();

      if (config.hideOthersProjectiles())
      {
         for (Player p : ghostedPlayers)
         {
            if (p.getGraphic() != -1)
            {
               p.setGraphic(-1);
            }

            if (p.getSpotAnims() != null)
            {
               for (ActorSpotAnim spotAnim : p.getSpotAnims())
               {
                  p.removeSpotAnim(spotAnim.getId());
               }
            }
         }
      }
   }

   @Subscribe
   public void onProjectileMoved(ProjectileMoved event)
   {
      if (!isActive())
      {
         return;
      }

      Projectile proj = event.getProjectile();

      if (myProjectiles.contains(proj))
      {
         return;
      }

      if (client.getGameCycle() > proj.getStartCycle() + 150)
      {
         return;
      }

      Player local = client.getLocalPlayer();
      if (local == null)
      {
         return;
      }

      LocalPoint lp = local.getLocalLocation();
      if (lp == null)
      {
         return;
      }

      int dx = proj.getX1() - lp.getX();
      int dy = proj.getY1() - lp.getY();
      int distSq = (dx * dx) + (dy * dy);

      if (distSq < (192 * 192) && local.getAnimation() != -1)
      {
         myProjectiles.add(proj);
      }
   }

   @Subscribe
   public void onPlayerDespawned(PlayerDespawned event)
   {
      Player p = event.getPlayer();

      if (ghostedPlayers.contains(p) || originalEquipmentMap.containsKey(p))
      {
         restorePlayer(p);
      }

      ghostedPlayers.remove(p);
      originalEquipmentMap.remove(p);
      customHitsplats.remove(p);
      overrideStartCycle.remove(p);
      overrideLastSeenCycle.remove(p);
      overrideForcedPlayers.remove(p);
   }

   @Subscribe
   public void onPlayerChanged(PlayerChanged event)
   {
      if (!isActive())
      {
         return;
      }

      Player p = event.getPlayer();
      Player local = client.getLocalPlayer();

      if (p == null || local == null)
      {
         return;
      }

      if (p == local)
      {
         if (config.selfClearGround())
         {
            applyClothingFilter(p);
         }
         else
         {
            restoreClothing(p);
         }

         applyConfiguredOpacity(p);
         return;
      }

      if (ghostedPlayers.contains(p))
      {
         if (config.othersClearGround())
         {
            applyClothingFilter(p);
         }
         else
         {
            restoreClothing(p);
         }

         applyConfiguredOpacity(p);
      }
   }

   @Subscribe
   public void onConfigChanged(ConfigChanged event)
   {
      if (event.getGroup().equals("visibilityenhancer"))
      {
         clientThread.invokeLater(this::checkStateTransition);

         if (event.getKey().equals("selfClearGround") && !config.selfClearGround())
         {
            clientThread.invokeLater(() ->
            {
               Player local = client.getLocalPlayer();
               if (local != null)
               {
                  restoreClothing(local);
                  PlayerComposition comp = local.getPlayerComposition();
                  if (comp != null)
                  {
                     comp.setHash();
                  }
               }
            });
         }
      }
   }

   @Subscribe
   public void onGameTick(GameTick event)
   {
      checkStateTransition();

      if (!isActive())
      {
         return;
      }

      Player local = client.getLocalPlayer();
      if (local == null)
      {
         clearAllGhosting();
         return;
      }

      myProjectiles.removeIf(p -> client.getGameCycle() >= p.getEndCycle());

      if (config.othersTransparentPrayers())
      {
         customHitsplats.values().forEach(list ->
                 list.removeIf(h -> client.getTickCount() >= h.getDespawnTick()));
      }
      else
      {
         customHitsplats.clear();
      }

      if (config.selfClearGround())
      {
         applyClothingFilter(local);
      }
      else if (originalEquipmentMap.containsKey(local))
      {
         restoreClothing(local);
      }

      LocalPoint localLoc = local.getLocalLocation();
      if (localLoc == null)
      {
         clearAllGhosting();
         return;
      }

      inRange.clear();
      currentInRange.clear();
      noLongerGhosted.clear();

      boolean ignoreFriends = config.ignoreFriends();
      int maxDist = config.proximityRange();
      int localX = localLoc.getSceneX();
      int localY = localLoc.getSceneY();

      for (Player p : client.getPlayers())
      {
         if (p == null || p == local)
         {
            continue;
         }

         boolean isFriend = ignoreFriends && (p.isFriend() || client.isFriended(p.getName(), false));

         if (isFriend)
         {
            if (ghostedPlayers.contains(p))
            {
               restorePlayer(p);
            }
            continue;
         }

         LocalPoint pLoc = p.getLocalLocation();
         if (pLoc != null)
         {
            int dist = Math.max(
                    Math.abs(localX - pLoc.getSceneX()),
                    Math.abs(localY - pLoc.getSceneY())
            );

            if (dist <= maxDist)
            {
               inRange.add(p);
            }
         }
      }

      if (config.limitAffectedPlayers() && inRange.size() > config.maxAffectedPlayers())
      {
         inRange.sort((p1, p2) ->
         {
            LocalPoint lp1 = p1.getLocalLocation();
            LocalPoint lp2 = p2.getLocalLocation();

            if (lp1 == null || lp2 == null)
            {
               return 0;
            }

            int dist1 = Math.max(Math.abs(localX - lp1.getSceneX()), Math.abs(localY - lp1.getSceneY()));
            int dist2 = Math.max(Math.abs(localX - lp2.getSceneX()), Math.abs(localY - lp2.getSceneY()));

            return Integer.compare(dist1, dist2);
         });

         currentInRange.addAll(inRange.subList(0, config.maxAffectedPlayers()));
      }
      else
      {
         currentInRange.addAll(inRange);
      }

      int othersOpacity = config.playerOpacity();
      boolean hideOthersClothes = config.othersClearGround();

      for (Player p : currentInRange)
      {
         if (othersOpacity < 100)
         {
            applyOpacity(p, othersOpacity);
         }
         else
         {
            restoreOpacity(p);
         }

         if (hideOthersClothes)
         {
            applyClothingFilter(p);
         }
         else if (originalEquipmentMap.containsKey(p))
         {
            restoreClothing(p);
         }
      }

      noLongerGhosted.addAll(ghostedPlayers);
      noLongerGhosted.removeAll(currentInRange);

      for (Player p : noLongerGhosted)
      {
         restorePlayer(p);
      }

      ghostedPlayers.clear();
      ghostedPlayers.addAll(currentInRange);
   }

   @Subscribe
   public void onBeforeRender(BeforeRender event)
   {
      if (!isActive())
      {
         return;
      }

      wasActive = true;

      Player local = client.getLocalPlayer();
      if (local == null)
      {
         return;
      }

      int selfOpacity = config.selfOpacity();

      if (selfOpacity < 100)
      {
         applyOpacity(local, selfOpacity);
      }
      else
      {
         restoreOpacity(local);
      }

      int othersAlpha = clampAlpha(config.playerOpacity());
      int selfAlpha = clampAlpha(selfOpacity);

      Map<byte[], Model> arrayToModel = new HashMap<>();
      Map<byte[], Integer> arrayState = new HashMap<>();

      final int STATE_RESTORE = 0;
      final int STATE_MINE = 1;
      final int STATE_OTHERS = 2;

      for (Projectile proj : client.getProjectiles())
      {
         Model m = proj.getModel();
         if (m == null)
         {
            continue;
         }

         byte[] trans = m.getFaceTransparencies();
         if (trans == null || trans.length == 0)
         {
            continue;
         }

         Actor target = proj.getInteracting();
         boolean isBossProjectile = BOSS_PROJECTILES.contains(proj.getId());
         boolean isTargetingMeOrGround = (target == local || target == null);
         boolean isMine = myProjectiles.contains(proj);

         Integer currentState = arrayState.get(trans);

         if (isBossProjectile || isTargetingMeOrGround)
         {
            arrayState.put(trans, STATE_RESTORE);
            arrayToModel.put(trans, m);
         }
         else if (isMine)
         {
            if (currentState == null || currentState != STATE_RESTORE)
            {
               arrayState.put(trans, STATE_MINE);
               arrayToModel.put(trans, m);
            }
         }
         else
         {
            if (currentState == null)
            {
               arrayState.put(trans, STATE_OTHERS);
               arrayToModel.put(trans, m);
            }
         }
      }

      for (Map.Entry<byte[], Integer> entry : arrayState.entrySet())
      {
         byte[] trans = entry.getKey();
         int state = entry.getValue();
         Model m = arrayToModel.get(trans);

         if (state == STATE_RESTORE)
         {
            restoreModelAlpha(m);
         }
         else if (state == STATE_MINE)
         {
            if (selfOpacity < 100)
            {
               applyModelAlpha(m, selfAlpha);
            }
            else
            {
               restoreModelAlpha(m);
            }
         }
         else if (state == STATE_OTHERS)
         {
            if (config.playerOpacity() < 100)
            {
               applyModelAlpha(m, othersAlpha);
            }
            else
            {
               restoreModelAlpha(m);
            }
         }
      }
   }

   private boolean shouldDraw(Renderable renderable, boolean drawingUI)
   {
      if (!isActive())
      {
         return true;
      }

      if (renderable instanceof Projectile && config.hideOthersProjectiles())
      {
         Projectile proj = (Projectile) renderable;
         Actor target = proj.getInteracting();
         return target == null || target == cachedLocalPlayer || myProjectiles.contains(proj);
      }

      if (renderable instanceof Player)
      {
         Player player = (Player) renderable;
         boolean isGhost = ghostedPlayers.contains(player);

         if (drawingUI && isGhost && config.othersTransparentPrayers())
         {
            return false;
         }
      }

      if (renderable instanceof NPC && config.hideThralls())
      {
         NPC npc = (NPC) renderable;

         if (THRALL_IDS.contains(npc.getId()))
         {
            return false;
         }

         net.runelite.api.NPCComposition comp = npc.getComposition();
         if (comp != null && comp.isFollower() && npc != client.getFollower())
         {
            return false;
         }
      }

      return true;
   }

   private int getEffectiveOpacity(Player player)
   {
      Player local = client.getLocalPlayer();
      if (player == null || local == null)
      {
         return 100;
      }

      if (player == local)
      {
         return config.selfOpacity();
      }

      return config.playerOpacity();
   }

   private void checkStateTransition()
   {
      boolean currentlyActive = isActive();

      if (wasActive && !currentlyActive)
      {
         clearAllGhosting();
      }

      wasActive = currentlyActive;
   }

   private boolean isExemptAnimation(Player player)
   {
      return player != null && EXEMPT_ANIMATIONS.contains(player.getAnimation());
   }

   private boolean shouldForceOpaqueForOverride(Player player, Model model)
   {
      if (player == null || model == null)
      {
         return false;
      }

      int currentCycle = client.getGameCycle();

      if (isExemptAnimation(player))
      {
         overrideStartCycle.remove(player);
         overrideLastSeenCycle.remove(player);
         overrideForcedPlayers.remove(player);
         return false;
      }

      if (model.getOverrideAmount() != 0)
      {
         overrideLastSeenCycle.put(player, currentCycle);

         Integer startCycle = overrideStartCycle.get(player);
         if (startCycle == null)
         {
            overrideStartCycle.put(player, currentCycle);
            return overrideForcedPlayers.contains(player);
         }

         if (overrideForcedPlayers.contains(player))
         {
            return true;
         }

         if (currentCycle - startCycle >= OVERRIDE_OPAQUE_DELAY_CYCLES)
         {
            overrideForcedPlayers.add(player);
            return true;
         }

         return false;
      }

      Integer lastSeenCycle = overrideLastSeenCycle.get(player);

      if (overrideForcedPlayers.contains(player))
      {
         if (lastSeenCycle != null && currentCycle - lastSeenCycle <= OVERRIDE_CLEAR_DELAY_CYCLES)
         {
            return true;
         }

         overrideForcedPlayers.remove(player);
      }

      if (lastSeenCycle != null && currentCycle - lastSeenCycle <= OVERRIDE_CLEAR_DELAY_CYCLES)
      {
         return true;
      }

      overrideStartCycle.remove(player);
      overrideLastSeenCycle.remove(player);
      return false;
   }

   private void applyConfiguredOpacity(Player player)
   {
      if (player == null)
      {
         return;
      }

      int opacity = getEffectiveOpacity(player);
      if (opacity < 100)
      {
         applyOpacity(player, opacity);
      }
      else
      {
         restoreOpacity(player);
      }
   }

   private void applyClothingFilter(Player player)
   {
      Model currentModel = player.getModel();
      if (currentModel != null && shouldForceOpaqueForOverride(player, currentModel))
      {
         restoreClothing(player);
         return;
      }

      PlayerComposition comp = player.getPlayerComposition();
      if (comp == null)
      {
         return;
      }

      int[] equipmentIds = comp.getEquipmentIds();

      if (!originalEquipmentMap.containsKey(player))
      {
         originalEquipmentMap.put(player, equipmentIds.clone());
      }

      int[] slotsToHide = {
              KitType.CAPE.getIndex(),
              KitType.SHIELD.getIndex(),
              KitType.LEGS.getIndex(),
              KitType.BOOTS.getIndex()
      };

      boolean changed = false;
      for (int slot : slotsToHide)
      {
         if (equipmentIds[slot] != -1)
         {
            equipmentIds[slot] = -1;
            changed = true;
         }
      }

      if (changed)
      {
         comp.setHash();

         Model newModel = player.getModel();
         if (newModel != null)
         {
            int targetOpacity = getEffectiveOpacity(player);
            if (shouldForceOpaqueForOverride(player, newModel))
            {
               targetOpacity = 100;
            }

            if (targetOpacity < 100)
            {
               int alpha = clampAlpha(targetOpacity);
               applyModelAlpha(newModel, alpha);
            }
            else
            {
               restoreModelAlpha(newModel);
            }
         }
      }
   }

   private void restoreClothing(Player player)
   {
      if (!originalEquipmentMap.containsKey(player))
      {
         return;
      }

      PlayerComposition comp = player.getPlayerComposition();
      if (comp != null)
      {
         int[] original = originalEquipmentMap.get(player);
         int[] current = comp.getEquipmentIds();
         System.arraycopy(original, 0, current, 0, original.length);
         comp.setHash();
      }

      originalEquipmentMap.remove(player);
   }

   private void applyModelAlpha(Model m, int alpha)
   {
      byte[] trans = m.getFaceTransparencies();
      if (trans == null || trans.length == 0)
      {
         return;
      }

      byte[] original = originalTransparencies.get(trans);
      if (original == null)
      {
         original = trans.clone();
         originalTransparencies.put(trans, original);
      }

      for (int i = 0; i < trans.length; i++)
      {
         int origAlpha = original[i] & 0xFF;
         int combinedAlpha = Math.max(origAlpha, alpha);

         if ((trans[i] & 0xFF) != combinedAlpha)
         {
            trans[i] = (byte) combinedAlpha;
         }
      }
   }

   private void restoreModelAlpha(Model m)
   {
      if (m == null)
      {
         return;
      }

      byte[] trans = m.getFaceTransparencies();
      if (trans == null || trans.length == 0)
      {
         return;
      }

      byte[] original = originalTransparencies.get(trans);
      if (original != null && original.length == trans.length)
      {
         System.arraycopy(original, 0, trans, 0, original.length);
      }
   }

   private void applyOpacity(Player p, int opacityPercent)
   {
      Model model = p.getModel();
      if (model == null)
      {
         return;
      }

      if (shouldForceOpaqueForOverride(p, model))
      {
         restoreOpacity(p);
         return;
      }

      int alpha = clampAlpha(opacityPercent);
      applyModelAlpha(model, alpha);
   }

   private void restoreOpacity(Player p)
   {
      Model model = p.getModel();
      if (model == null)
      {
         return;
      }

      restoreModelAlpha(model);
   }

   private void restorePlayer(Player p)
   {
      restoreOpacity(p);
      restoreClothing(p);

      overrideStartCycle.remove(p);
      overrideLastSeenCycle.remove(p);
      overrideForcedPlayers.remove(p);

      PlayerComposition comp = p.getPlayerComposition();
      if (comp != null)
      {
         comp.setHash();
      }
   }

   private void clearAllGhosting()
   {
      Set<Player> allAffected = new HashSet<>(ghostedPlayers);
      allAffected.addAll(originalEquipmentMap.keySet());

      Player local = client.getLocalPlayer();
      if (local != null)
      {
         allAffected.add(local);
      }

      for (Player p : allAffected)
      {
         restorePlayer(p);
      }

      ghostedPlayers.clear();
      originalEquipmentMap.clear();
      myProjectiles.clear();
      customHitsplats.clear();
      overrideStartCycle.clear();
      overrideLastSeenCycle.clear();
      overrideForcedPlayers.clear();

      for (Map.Entry<byte[], byte[]> entry : originalTransparencies.entrySet())
      {
         byte[] trans = entry.getKey();
         byte[] original = entry.getValue();

         if (trans != null && original != null && trans.length == original.length)
         {
            System.arraycopy(original, 0, trans, 0, original.length);
         }
      }
      originalTransparencies.clear();
   }

   private int clampAlpha(int opacityPercent)
   {
      if (opacityPercent >= 100)
      {
         return 0;
      }

      return (int) ((100 - opacityPercent) * 2.5);
   }

   @Provides
   VisibilityEnhancerConfig provideConfig(ConfigManager configManager)
   {
      return configManager.getConfig(VisibilityEnhancerConfig.class);
   }
}