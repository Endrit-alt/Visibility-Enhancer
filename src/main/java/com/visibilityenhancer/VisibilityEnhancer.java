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
        name = "Visibility",
        description = "Teammate opacity, ground-view filters, and outlines for raids and other PvM content.",
        tags = {"Visibility", "ghostly", "Experience", "pvm", "raid", "raids", "raids visibility enhancer", "visibility enhancer", "opacity", "outline", "equipment", "visibility", "Ghost", "teammates", "team"}
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

   @Getter
   private boolean peekHeld = false;

   private Instant lastPress;

   @Getter
   private final Set<Player> ghostedPlayers = new HashSet<>();

   private final Set<Player> fallbackHiddenPlayers = new HashSet<>();

   private final Map<Player, int[]> originalEquipmentMap = new HashMap<>();
   private final Set<Projectile> myProjectiles = new HashSet<>();
   private final Map<byte[], byte[]> originalTransparencies = new WeakHashMap<>();

   // Tracks all modified transparency arrays per player to prevent orphaned models
   private final Map<Player, Set<byte[]>> playerTrackedTransparencies = new WeakHashMap<>();

   // Tracks players whose base models cannot support transparency
   private final Set<Player> immunePlayers = new HashSet<>();

   // Tracks players whose base models have proven they do support transparency
   private final Set<Player> supportedPlayers = new HashSet<>();

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

   private final HotkeyListener peekListener = new HotkeyListener(() -> config.peekHotkey())
   {
      @Override
      public void hotkeyPressed()
      {
         peekHeld = true;
         clientThread.invokeLater(() ->
         {
            checkStateTransition();
            forceOpacityUpdate();
         });
      }

      @Override
      public void hotkeyReleased()
      {
         peekHeld = false;
         clientThread.invokeLater(() ->
         {
            checkStateTransition();

            if (isActive())
            {
               forceOpacityUpdate();
            }
         });
      }
   };

   private static final Set<Integer> EXEMPT_ANIMATIONS = ImmutableSet.<Integer>builder()
           .add(1378, 7642, 7643, 7514, 1062, 1203, 7644, 7640, 7638, 10172, 5062, 9168, 8104)

           .add(AnimationID.CONSUMING)

           .add(AnimationID.BOOK_HOME_TELEPORT_1, AnimationID.BOOK_HOME_TELEPORT_2, AnimationID.BOOK_HOME_TELEPORT_3, AnimationID.BOOK_HOME_TELEPORT_4, AnimationID.BOOK_HOME_TELEPORT_5)
           .add(AnimationID.COW_HOME_TELEPORT_1, AnimationID.COW_HOME_TELEPORT_2, AnimationID.COW_HOME_TELEPORT_3, AnimationID.COW_HOME_TELEPORT_4, AnimationID.COW_HOME_TELEPORT_5, AnimationID.COW_HOME_TELEPORT_6)
           .add(AnimationID.LEAGUE_HOME_TELEPORT_1, AnimationID.LEAGUE_HOME_TELEPORT_2, AnimationID.LEAGUE_HOME_TELEPORT_3, AnimationID.LEAGUE_HOME_TELEPORT_4, AnimationID.LEAGUE_HOME_TELEPORT_5, AnimationID.LEAGUE_HOME_TELEPORT_6)
           .add(AnimationID.SHATTERED_LEAGUE_HOME_TELEPORT_1, AnimationID.SHATTERED_LEAGUE_HOME_TELEPORT_2, AnimationID.SHATTERED_LEAGUE_HOME_TELEPORT_3, AnimationID.SHATTERED_LEAGUE_HOME_TELEPORT_4, AnimationID.SHATTERED_LEAGUE_HOME_TELEPORT_5, AnimationID.SHATTERED_LEAGUE_HOME_TELEPORT_6)

           .add(AnimationID.HERBLORE_PESTLE_AND_MORTAR, AnimationID.HERBLORE_POTIONMAKING, AnimationID.HERBLORE_MAKE_TAR)
           .add(AnimationID.HERBLORE_MIXOLOGY_CONCENTRATE, AnimationID.HERBLORE_MIXOLOGY_CRYSTALIZE, AnimationID.HERBLORE_MIXOLOGY_HOMOGENIZE, AnimationID.HERBLORE_MIXOLOGY_REFINER)
           .add(AnimationID.FARMING_HARVEST_FRUIT_TREE, AnimationID.FARMING_HARVEST_BUSH, AnimationID.FARMING_HARVEST_HERB, AnimationID.FARMING_USE_COMPOST, AnimationID.FARMING_CURE_WITH_POTION)
           .add(AnimationID.FARMING_PLANT_SEED, AnimationID.FARMING_HARVEST_FLOWER, AnimationID.FARMING_MIX_ULTRACOMPOST, AnimationID.FARMING_HARVEST_ALLOTMENT)

           .add(AnimationID.GEM_CUTTING_OPAL, AnimationID.GEM_CUTTING_JADE, AnimationID.GEM_CUTTING_REDTOPAZ, AnimationID.GEM_CUTTING_SAPPHIRE, AnimationID.GEM_CUTTING_EMERALD, AnimationID.GEM_CUTTING_RUBY, AnimationID.GEM_CUTTING_DIAMOND, AnimationID.GEM_CUTTING_AMETHYST)
           .add(AnimationID.CRAFTING_LEATHER, AnimationID.CRAFTING_GLASSBLOWING, AnimationID.CRAFTING_SPINNING, AnimationID.CRAFTING_POTTERS_WHEEL, AnimationID.CRAFTING_POTTERY_OVEN, AnimationID.CRAFTING_LOOM, AnimationID.CRAFTING_CRUSH_BLESSED_BONES, AnimationID.CRAFTING_BATTLESTAVES)
           .add(AnimationID.SMITHING_SMELTING, AnimationID.SMITHING_CANNONBALL, AnimationID.SMITHING_ANVIL, AnimationID.SMITHING_IMCANDO_HAMMER)
           .add(AnimationID.FLETCHING_BOW_CUTTING, AnimationID.FLETCHING_ATTACH_HEADS, AnimationID.FLETCHING_ATTACH_FEATHERS_TO_ARROWSHAFT)
           .add(AnimationID.FLETCHING_ATTACH_STOCK_TO_BRONZE_LIMBS, AnimationID.FLETCHING_ATTACH_STOCK_TO_BLURITE_LIMBS, AnimationID.FLETCHING_ATTACH_STOCK_TO_IRON_LIMBS, AnimationID.FLETCHING_ATTACH_STOCK_TO_STEEL_LIMBS, AnimationID.FLETCHING_ATTACH_STOCK_TO_MITHRIL_LIMBS, AnimationID.FLETCHING_ATTACH_STOCK_TO_ADAMANTITE_LIMBS, AnimationID.FLETCHING_ATTACH_STOCK_TO_RUNITE_LIMBS, AnimationID.FLETCHING_ATTACH_STOCK_TO_DRAGON_LIMBS)
           .add(AnimationID.FLETCHING_STRING_NORMAL_SHORTBOW, AnimationID.FLETCHING_STRING_NORMAL_LONGBOW, AnimationID.FLETCHING_STRING_OAK_SHORTBOW, AnimationID.FLETCHING_STRING_OAK_LONGBOW, AnimationID.FLETCHING_STRING_WILLOW_SHORTBOW, AnimationID.FLETCHING_STRING_WILLOW_LONGBOW)
           .add(AnimationID.FLETCHING_STRING_MAPLE_SHORTBOW, AnimationID.FLETCHING_STRING_MAPLE_LONGBOW, AnimationID.FLETCHING_STRING_YEW_SHORTBOW, AnimationID.FLETCHING_STRING_YEW_LONGBOW, AnimationID.FLETCHING_STRING_MAGIC_SHORTBOW, AnimationID.FLETCHING_STRING_MAGIC_LONGBOW)
           .add(AnimationID.FLETCHING_ATTACH_BOLT_TIPS_TO_BRONZE_BOLT, AnimationID.FLETCHING_ATTACH_BOLT_TIPS_TO_IRON_BROAD_BOLT, AnimationID.FLETCHING_ATTACH_BOLT_TIPS_TO_BLURITE_BOLT, AnimationID.FLETCHING_ATTACH_BOLT_TIPS_TO_STEEL_BOLT, AnimationID.FLETCHING_ATTACH_BOLT_TIPS_TO_MITHRIL_BOLT, AnimationID.FLETCHING_ATTACH_BOLT_TIPS_TO_ADAMANT_BOLT, AnimationID.FLETCHING_ATTACH_BOLT_TIPS_TO_RUNE_BOLT, AnimationID.FLETCHING_ATTACH_BOLT_TIPS_TO_DRAGON_BOLT)

           .add(AnimationID.WOODCUTTING_BRONZE, AnimationID.WOODCUTTING_IRON, AnimationID.WOODCUTTING_STEEL, AnimationID.WOODCUTTING_BLACK, AnimationID.WOODCUTTING_MITHRIL, AnimationID.WOODCUTTING_ADAMANT, AnimationID.WOODCUTTING_RUNE, AnimationID.WOODCUTTING_GILDED, AnimationID.WOODCUTTING_DRAGON, AnimationID.WOODCUTTING_DRAGON_OR, AnimationID.WOODCUTTING_INFERNAL, AnimationID.WOODCUTTING_3A_AXE, AnimationID.WOODCUTTING_CRYSTAL, AnimationID.WOODCUTTING_TRAILBLAZER)
           .add(AnimationID.WOODCUTTING_2H_BRONZE, AnimationID.WOODCUTTING_2H_IRON, AnimationID.WOODCUTTING_2H_STEEL, AnimationID.WOODCUTTING_2H_BLACK, AnimationID.WOODCUTTING_2H_MITHRIL, AnimationID.WOODCUTTING_2H_ADAMANT, AnimationID.WOODCUTTING_2H_RUNE, AnimationID.WOODCUTTING_2H_DRAGON, AnimationID.WOODCUTTING_2H_CRYSTAL, AnimationID.WOODCUTTING_2H_CRYSTAL_INACTIVE, AnimationID.WOODCUTTING_2H_3A)
           .add(AnimationID.WOODCUTTING_ENT_BRONZE, AnimationID.WOODCUTTING_ENT_IRON, AnimationID.WOODCUTTING_ENT_STEEL, AnimationID.WOODCUTTING_ENT_BLACK, AnimationID.WOODCUTTING_ENT_MITHRIL, AnimationID.WOODCUTTING_ENT_ADAMANT, AnimationID.WOODCUTTING_ENT_RUNE, AnimationID.WOODCUTTING_ENT_GILDED, AnimationID.WOODCUTTING_ENT_DRAGON, AnimationID.WOODCUTTING_ENT_DRAGON_OR, AnimationID.WOODCUTTING_ENT_INFERNAL, AnimationID.WOODCUTTING_ENT_INFERNAL_OR, AnimationID.WOODCUTTING_ENT_3A, AnimationID.WOODCUTTING_ENT_CRYSTAL, AnimationID.WOODCUTTING_ENT_CRYSTAL_INACTIVE, AnimationID.WOODCUTTING_ENT_TRAILBLAZER)
           .add(AnimationID.WOODCUTTING_ENT_2H_BRONZE, AnimationID.WOODCUTTING_ENT_2H_IRON, AnimationID.WOODCUTTING_ENT_2H_STEEL, AnimationID.WOODCUTTING_ENT_2H_BLACK, AnimationID.WOODCUTTING_ENT_2H_MITHRIL, AnimationID.WOODCUTTING_ENT_2H_ADAMANT, AnimationID.WOODCUTTING_ENT_2H_RUNE, AnimationID.WOODCUTTING_ENT_2H_DRAGON, AnimationID.WOODCUTTING_ENT_2H_CRYSTAL, AnimationID.WOODCUTTING_ENT_2H_CRYSTAL_INACTIVE, AnimationID.WOODCUTTING_ENT_2H_3A)
           .add(AnimationID.FIREMAKING)
           .add(AnimationID.FIREMAKING_FORESTERS_CAMPFIRE_ARCTIC_PINE, AnimationID.FIREMAKING_FORESTERS_CAMPFIRE_BLISTERWOOD, AnimationID.FIREMAKING_FORESTERS_CAMPFIRE_LOGS, AnimationID.FIREMAKING_FORESTERS_CAMPFIRE_MAGIC, AnimationID.FIREMAKING_FORESTERS_CAMPFIRE_MAHOGANY, AnimationID.FIREMAKING_FORESTERS_CAMPFIRE_MAPLE, AnimationID.FIREMAKING_FORESTERS_CAMPFIRE_OAK, AnimationID.FIREMAKING_FORESTERS_CAMPFIRE_REDWOOD, AnimationID.FIREMAKING_FORESTERS_CAMPFIRE_TEAK, AnimationID.FIREMAKING_FORESTERS_CAMPFIRE_WILLOW, AnimationID.FIREMAKING_FORESTERS_CAMPFIRE_YEW)

           .add(AnimationID.MINING_BRONZE_PICKAXE, AnimationID.MINING_IRON_PICKAXE, AnimationID.MINING_STEEL_PICKAXE, AnimationID.MINING_BLACK_PICKAXE, AnimationID.MINING_MITHRIL_PICKAXE, AnimationID.MINING_ADAMANT_PICKAXE, AnimationID.MINING_RUNE_PICKAXE, AnimationID.MINING_GILDED_PICKAXE, AnimationID.MINING_DRAGON_PICKAXE, AnimationID.MINING_DRAGON_PICKAXE_UPGRADED, AnimationID.MINING_DRAGON_PICKAXE_OR, AnimationID.MINING_DRAGON_PICKAXE_OR_TRAILBLAZER, AnimationID.MINING_INFERNAL_PICKAXE, AnimationID.MINING_3A_PICKAXE, AnimationID.MINING_CRYSTAL_PICKAXE, AnimationID.MINING_TRAILBLAZER_PICKAXE, AnimationID.MINING_TRAILBLAZER_PICKAXE_2, AnimationID.MINING_TRAILBLAZER_PICKAXE_3)
           .add(AnimationID.MINING_MOTHERLODE_BRONZE, AnimationID.MINING_MOTHERLODE_IRON, AnimationID.MINING_MOTHERLODE_STEEL, AnimationID.MINING_MOTHERLODE_BLACK, AnimationID.MINING_MOTHERLODE_MITHRIL, AnimationID.MINING_MOTHERLODE_ADAMANT, AnimationID.MINING_MOTHERLODE_RUNE, AnimationID.MINING_MOTHERLODE_GILDED, AnimationID.MINING_MOTHERLODE_DRAGON, AnimationID.MINING_MOTHERLODE_DRAGON_UPGRADED, AnimationID.MINING_MOTHERLODE_DRAGON_OR, AnimationID.MINING_MOTHERLODE_DRAGON_OR_TRAILBLAZER, AnimationID.MINING_MOTHERLODE_INFERNAL, AnimationID.MINING_MOTHERLODE_3A, AnimationID.MINING_MOTHERLODE_CRYSTAL, AnimationID.MINING_MOTHERLODE_TRAILBLAZER)
           .add(AnimationID.MINING_CRASHEDSTAR_BRONZE, AnimationID.MINING_CRASHEDSTAR_IRON, AnimationID.MINING_CRASHEDSTAR_STEEL, AnimationID.MINING_CRASHEDSTAR_BLACK, AnimationID.MINING_CRASHEDSTAR_MITHRIL, AnimationID.MINING_CRASHEDSTAR_ADAMANT, AnimationID.MINING_CRASHEDSTAR_RUNE, AnimationID.MINING_CRASHEDSTAR_GILDED, AnimationID.MINING_CRASHEDSTAR_DRAGON, AnimationID.MINING_CRASHEDSTAR_DRAGON_UPGRADED, AnimationID.MINING_CRASHEDSTAR_DRAGON_OR, AnimationID.MINING_CRASHEDSTAR_DRAGON_OR_TRAILBLAZER, AnimationID.MINING_CRASHEDSTAR_INFERNAL, AnimationID.MINING_CRASHEDSTAR_3A, AnimationID.MINING_CRASHEDSTAR_CRYSTAL)
           .add(AnimationID.DENSE_ESSENCE_CHIPPING, AnimationID.DENSE_ESSENCE_CHISELING)

           .add(AnimationID.FISHING_BIG_NET, AnimationID.FISHING_NET, AnimationID.FISHING_POLE_CAST, AnimationID.FISHING_CAGE, AnimationID.FISHING_HARPOON, AnimationID.FISHING_BARBTAIL_HARPOON, AnimationID.FISHING_DRAGON_HARPOON, AnimationID.FISHING_DRAGON_HARPOON_OR, AnimationID.FISHING_INFERNAL_HARPOON, AnimationID.FISHING_CRYSTAL_HARPOON, AnimationID.FISHING_TRAILBLAZER_HARPOON, AnimationID.FISHING_OILY_ROD, AnimationID.FISHING_KARAMBWAN, AnimationID.FISHING_BARBARIAN_ROD)
           .add(AnimationID.FISHING_CRUSHING_INFERNAL_EELS, AnimationID.FISHING_CRUSHING_INFERNAL_EELS_IMCANDO_HAMMER, AnimationID.FISHING_CUTTING_SACRED_EELS)
           .add(AnimationID.FISHING_BAREHAND, AnimationID.FISHING_BAREHAND_WINDUP_1, AnimationID.FISHING_BAREHAND_WINDUP_2, AnimationID.FISHING_BAREHAND_CAUGHT_SHARK_1, AnimationID.FISHING_BAREHAND_CAUGHT_SHARK_2, AnimationID.FISHING_BAREHAND_CAUGHT_SWORDFISH_1, AnimationID.FISHING_BAREHAND_CAUGHT_SWORDFISH_2, AnimationID.FISHING_BAREHAND_CAUGHT_TUNA_1, AnimationID.FISHING_BAREHAND_CAUGHT_TUNA_2)
           .add(AnimationID.FISHING_PEARL_ROD, AnimationID.FISHING_PEARL_FLY_ROD, AnimationID.FISHING_PEARL_BARBARIAN_ROD, AnimationID.FISHING_PEARL_ROD_2, AnimationID.FISHING_PEARL_FLY_ROD_2, AnimationID.FISHING_PEARL_BARBARIAN_ROD_2, AnimationID.FISHING_PEARL_OILY_ROD)
           .add(AnimationID.COOKING_FIRE, AnimationID.COOKING_RANGE, AnimationID.COOKING_WINE, AnimationID.MAKING_SUNFIRE_WINE)

           .add(AnimationID.HUNTER_LAY_BOXTRAP_BIRDSNARE, AnimationID.HUNTER_LAY_NETTRAP, AnimationID.HUNTER_LAY_MANIACAL_MONKEY_BOULDER_TRAP, AnimationID.HUNTER_CHECK_BIRD_SNARE)

           .add(AnimationID.MAGIC_CHARGING_ORBS, AnimationID.MAGIC_MAKE_TABLET, AnimationID.MAGIC_ENCHANTING_JEWELRY, AnimationID.MAGIC_ENCHANTING_AMULET_1, AnimationID.MAGIC_ENCHANTING_AMULET_2, AnimationID.MAGIC_ENCHANTING_AMULET_3, AnimationID.MAGIC_ENCHANTING_BOLTS)
           .add(AnimationID.MAGIC_LUNAR_SHARED, AnimationID.MAGIC_LUNAR_CURE_PLANT, AnimationID.MAGIC_LUNAR_PLANK_MAKE, AnimationID.MAGIC_LUNAR_STRING_JEWELRY, AnimationID.MAGIC_ARCEUUS_RESURRECT_CROPS)
           .add(AnimationID.BURYING_BONES, AnimationID.USING_GILDED_ALTAR, AnimationID.SACRIFICE_BLESSED_BONE_SHARDS)
           .add(AnimationID.ECTOFUNTUS_FILL_SLIME_BUCKET, AnimationID.ECTOFUNTUS_GRIND_BONES, AnimationID.ECTOFUNTUS_INSERT_BONES, AnimationID.ECTOFUNTUS_EMPTY_BIN)

           .add(AnimationID.LOOKING_INTO, AnimationID.DIG, AnimationID.CONSTRUCTION, AnimationID.CONSTRUCTION_IMCANDO, AnimationID.SAND_COLLECTION, AnimationID.PISCARILIUS_CRANE_REPAIR, AnimationID.HOME_MAKE_TABLET)
           .add(AnimationID.MILKING_COW, AnimationID.CHURN_MILK_SHORT, AnimationID.CHURN_MILK_MEDIUM, AnimationID.CHURN_MILK_LONG)
           .add(AnimationID.CLEANING_SPECIMENS_1, AnimationID.CLEANING_SPECIMENS_2, AnimationID.THIEVING_VARLAMORE_STEALING_VALUABLES)

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
      keyManager.registerKeyListener(peekListener);
      pluginToggledOn = true;
      hotkeyHeld = false;
      peekHeld = false;
      wasActive = false;
   }

   @Override
   protected void shutDown()
   {
      overlayManager.remove(overlay);
      hooks.unregisterRenderableDrawListener(drawListener);
      keyManager.unregisterKeyListener(hotkeyListener);
      keyManager.unregisterKeyListener(peekListener);

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

      if (peekHeld)
      {
         return true;
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

            List<CustomHitsplat> list = customHitsplats.computeIfAbsent(p, k -> new ArrayList<>());
            CustomHitsplat newHit = new CustomHitsplat(amount, client.getTickCount() + 4);

            if (list.size() < 4)
            {
               list.add(newHit);
            }
            else
            {
               // We have 4 hitsplats. Find the best candidate to evict.
               // Priority: Oldest 0-damage hit > Oldest damage hit.
               int targetIndex = -1;
               boolean foundZero = false;

               for (int i = 0; i < list.size(); i++)
               {
                  CustomHitsplat h = list.get(i);
                  if (h.getAmount() == 0)
                  {
                     if (!foundZero)
                     {
                        targetIndex = i;
                        foundZero = true;
                     }
                     else if (h.getDespawnTick() < list.get(targetIndex).getDespawnTick())
                     {
                        targetIndex = i; // Older zero-damage hit
                     }
                  }
                  else if (!foundZero)
                  {
                     if (targetIndex == -1 || h.getDespawnTick() < list.get(targetIndex).getDespawnTick())
                     {
                        targetIndex = i; // Oldest non-zero hit
                     }
                  }
               }

               // If the new hit is a 0, and the only things we can evict are actual damage hits, just discard the 0.
               if (amount == 0 && !foundZero)
               {
                  return;
               }

               // Replace the chosen hitsplat in place so the grid doesn't shuffle visually
               if (targetIndex != -1)
               {
                  list.set(targetIndex, newHit);
               }
            }
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

      if (config.distanceBasedOpacity() && !peekHeld)
      {
         for (Player p : currentInRange)
         {
            if (shouldHideWithFallback(p))
            {
               restoreOpacity(p);
               continue;
            }

            int pOpacity = getEffectiveOpacity(p);

            if (pOpacity < 100)
            {
               applyOpacity(p, pOpacity);
            }
            else
            {
               restoreOpacity(p);
            }
         }
      }

      if (peekHeld || config.hideOthersProjectiles())
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
      fallbackHiddenPlayers.remove(p);
      originalEquipmentMap.remove(p);
      customHitsplats.remove(p);
      overrideStartCycle.remove(p);
      overrideLastSeenCycle.remove(p);
      overrideForcedPlayers.remove(p);
      immunePlayers.remove(p);
      supportedPlayers.remove(p);
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
         updateGhostedPlayer(p, config.othersClearGround());
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

   private void updatePlayersInRange()
   {
      Player local = client.getLocalPlayer();
      if (local == null)
      {
         return;
      }

      LocalPoint localLoc = local.getLocalLocation();
      if (localLoc == null)
      {
         return;
      }

      inRange.clear();
      currentInRange.clear();

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

      if (inRange.size() > config.maxAffectedPlayers())
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

      noLongerGhosted.clear();

      updatePlayersInRange();

      boolean hideOthersClothes = config.othersClearGround();

      for (Player p : currentInRange)
      {
         updateGhostedPlayer(p, hideOthersClothes);
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

      int selfOpacity = getEffectiveSelfOpacity();
      int othersOpacity = getEffectiveOthersOpacity();

      if (selfOpacity < 100)
      {
         applyOpacity(local, selfOpacity);
      }
      else
      {
         restoreOpacity(local);
      }

      int othersAlpha = clampAlpha(othersOpacity);
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
               applyModelAlpha(null, m, selfAlpha);
            }
            else
            {
               restoreModelAlpha(m);
            }
         }
         else if (state == STATE_OTHERS)
         {
            if (othersOpacity < 100)
            {
               applyModelAlpha(null, m, othersAlpha);
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

      if (renderable instanceof Projectile && (peekHeld || config.hideOthersProjectiles()))
      {
         Projectile proj = (Projectile) renderable;

         // FIX: Never hide critical raid projectiles (like the Sotetseg death ball)
         if (BOSS_PROJECTILES.contains(proj.getId()))
         {
            return true;
         }

         Actor target = proj.getInteracting();
         return target == null || target == cachedLocalPlayer || myProjectiles.contains(proj);
      }

      if (renderable instanceof Player)
      {
         Player player = (Player) renderable;

         if (!drawingUI && fallbackHiddenPlayers.contains(player))
         {
            return false;
         }

         boolean isGhost = ghostedPlayers.contains(player);

         if (drawingUI && isGhost && config.othersTransparentPrayers())
         {
            return false;
         }
      }

      if (renderable instanceof NPC && (peekHeld || config.hideThralls()))
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

   private int getEffectiveSelfOpacity()
   {
      return config.selfClearGround() ? 100 : config.selfOpacity();
   }

   private int getEffectiveOthersOpacity()
   {
      return config.othersClearGround() ? 100 : config.playerOpacity();
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
         return getEffectiveSelfOpacity();
      }

      if (peekHeld)
      {
         return 0;
      }

      int baseOpacity = getEffectiveOthersOpacity();

      if (config.distanceBasedOpacity())
      {
         LocalPoint localLoc = local.getLocalLocation();
         LocalPoint playerLoc = player.getLocalLocation();

         if (localLoc != null && playerLoc != null)
         {
            double dist = localLoc.distanceTo(playerLoc);

            double minDist = config.fadeStartDistance() * 128.0;
            double maxDist = Math.max(minDist + 1.0, config.fadeEndDistance() * 128.0);

            if (dist <= minDist)
            {
               return baseOpacity;
            }
            else if (dist >= maxDist)
            {
               return 100;
            }
            else
            {
               double fraction = (dist - minDist) / (maxDist - minDist);
               fraction = fraction * fraction;
               return (int) (baseOpacity + ((100 - baseOpacity) * fraction));
            }
         }
      }

      return baseOpacity;
   }

   private void forceOpacityUpdate()
   {
      if (client.getLocalPlayer() == null)
      {
         return;
      }

      updatePlayersInRange();

      boolean hideOthersClothes = config.othersClearGround();

      for (Player p : currentInRange)
      {
         updateGhostedPlayer(p, hideOthersClothes);
      }

      ghostedPlayers.addAll(currentInRange);
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

   private boolean isOpacityUnsupported(Player player)
   {
      if (player == null)
      {
         return false;
      }

      Model model = player.getModel();
      if (model == null)
      {
         return !supportedPlayers.contains(player);
      }

      byte[] trans = model.getFaceTransparencies();
      boolean hasTransparencyArray = trans != null && trans.length > 0;
      boolean isBaseState = player.getAnimation() == -1 && player.getGraphic() == -1;

      if (isBaseState)
      {
         if (hasTransparencyArray)
         {
            supportedPlayers.add(player);
            immunePlayers.remove(player);
            return false;
         }

         supportedPlayers.remove(player);
         immunePlayers.add(player);
         return true;
      }

      if (immunePlayers.contains(player))
      {
         return true;
      }

      if (supportedPlayers.contains(player))
      {
         return false;
      }

      // Unknown while animated or graphiced:
      // hide until a clean base-state model proves support.
      return true;
   }

   private boolean shouldHideWithFallback(Player player)
   {
      if (player == null || player == client.getLocalPlayer())
      {
         if (player != null)
         {
            fallbackHiddenPlayers.remove(player);
         }
         return false;
      }

      int opacity = getEffectiveOpacity(player);
      if (opacity >= 100)
      {
         fallbackHiddenPlayers.remove(player);
         return false;
      }

      boolean unsupported = isOpacityUnsupported(player);
      if (unsupported)
      {
         fallbackHiddenPlayers.add(player);
         return true;
      }

      fallbackHiddenPlayers.remove(player);
      return false;
   }

   private void updateGhostedPlayer(Player player, boolean hideOthersClothes)
   {
      if (player == null)
      {
         return;
      }

      if (shouldHideWithFallback(player))
      {
         restoreOpacity(player);

         if (originalEquipmentMap.containsKey(player))
         {
            restoreClothing(player);
         }

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

      if (hideOthersClothes)
      {
         applyClothingFilter(player);
      }
      else if (originalEquipmentMap.containsKey(player))
      {
         restoreClothing(player);
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
               applyModelAlpha(player, newModel, alpha);
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
      applyModelAlpha(null, m, alpha);
   }

   private void applyModelAlpha(Player p, Model m, int alpha)
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

      if (p != null)
      {
         playerTrackedTransparencies.computeIfAbsent(p, k -> new HashSet<>()).add(trans);
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

      byte[] trans = model.getFaceTransparencies();

      boolean isBaseState = (p.getAnimation() == -1 && p.getGraphic() == -1);

      if (isBaseState)
      {
         if (trans == null || trans.length == 0)
         {
            immunePlayers.add(p);
            return;
         }
         else
         {
            immunePlayers.remove(p);
         }
      }
      else
      {
         if (immunePlayers.contains(p))
         {
            return;
         }
      }

      if (shouldForceOpaqueForOverride(p, model))
      {
         restoreOpacity(p);
         return;
      }

      int alpha = clampAlpha(opacityPercent);
      applyModelAlpha(p, model, alpha);
   }

   private void restoreOpacity(Player p)
   {
      Model model = p.getModel();
      if (model != null)
      {
         restoreModelAlpha(model);
      }

      Set<byte[]> trackedArrays = playerTrackedTransparencies.remove(p);
      if (trackedArrays != null)
      {
         for (byte[] trans : trackedArrays)
         {
            byte[] original = originalTransparencies.get(trans);
            if (original != null && original.length == trans.length)
            {
               System.arraycopy(original, 0, trans, 0, original.length);
            }
         }
      }
   }

   private void restorePlayer(Player p)
   {
      restoreOpacity(p);
      restoreClothing(p);

      fallbackHiddenPlayers.remove(p);
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
      allAffected.addAll(fallbackHiddenPlayers);
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
      fallbackHiddenPlayers.clear();
      inRange.clear();
      currentInRange.clear();
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
      playerTrackedTransparencies.clear();
      immunePlayers.clear();
      supportedPlayers.clear();
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