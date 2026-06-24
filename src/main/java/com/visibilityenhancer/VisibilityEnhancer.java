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
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.PluginManager;

@PluginDescriptor(
        name = "Visibility",
        description = "Teammate opacity, ground-view filters, and outlines for raids and other PvM content.",
        tags = {"Visibility", "ghostly", "Experience", "pvm", "raid", "raids", "raids visibility enhancer", "visibility enhancer", "opacity", "outline", "equipment", "visibility", "Ghost", "teammates", "team"}
)
public class VisibilityEnhancer extends Plugin
{
   private static final int OVERRIDE_OPAQUE_DELAY_CYCLES = 2;
   private static final int OVERRIDE_CLEAR_DELAY_CYCLES = 30;
   private static final int CRITICAL_GRAPHIC_GRACE_PERIOD_CYCLES = 120;
   private static final int COX_MAX_AFFECTED_PLAYERS = 100;

   private final Map<Player, Integer> lastCombatCycleMap = new HashMap<>();
   private static final int COMBAT_TIMEOUT_CYCLES = 300; // 10 game ticks of "memory"

   // Tracks the exact game tick a player was last seen doing something combat-related
   private final Map<Player, Integer> combatTimerMap = new HashMap<>();
   private static final int COMBAT_TIMEOUT_TICKS = 32; // Mimics the 10-second OSRS combat timer

   @Inject
   private PluginManager pluginManager;

   @Inject
   private ConfigManager configManager;

   private boolean isProjectileOverrideActive = false;

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
   private final Set<Projectile> forceOpaqueProjectiles = new HashSet<>();
   private final Map<byte[], byte[]> originalTransparencies = new WeakHashMap<>();

   private final Map<Player, Set<byte[]>> playerTrackedTransparencies = new WeakHashMap<>();

   private final Set<Player> immunePlayers = new HashSet<>();
   private final Set<Player> supportedPlayers = new HashSet<>();

   private final Map<Player, Integer> overrideStartCycle = new HashMap<>();
   private final Map<Player, Integer> overrideLastSeenCycle = new HashMap<>();
   private final Set<Player> overrideForcedPlayers = new HashSet<>();

   // NEW Sets to manage logic for ALL players simultaneously
   private final Map<Player, Integer> lastCriticalGraphicCycleMap = new HashMap<>();
   private final Set<Player> criticalGraphicPlayers = new HashSet<>();
   private final Set<Player> exemptPlayers = new HashSet<>();
   private final Set<Player> culledPlayers = new HashSet<>();

   private final Map<byte[], Model> arrayToModel = new HashMap<>();
   private final Map<byte[], Integer> arrayState = new HashMap<>();

   private final Hooks.RenderableDrawListener drawListener = this::shouldDraw;

   private Player cachedLocalPlayer;
   private final List<Player> inRange = new ArrayList<>();
   private final Set<Player> currentInRange = new HashSet<>();
   private final Set<Player> noLongerGhosted = new HashSet<>();

   private boolean wasActive = false;
   private int currentRegionId = -1;

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

   private static final Set<Integer> TRANS_NULL_IDS = ImmutableSet.of(
           2204, 2206, 2208, 2224, 2241,  2253, 2255, 2237, 2238, 2244, //1
           1577, 1578, 1568, 1569, 1375, 1555, 1580, 1586, 1583, 1585, 1591, 1593, 1594, 1601, 1596, 1598 //2
   );

   private static final Set<Integer> RESTRICTED_PROJECTILE_REGIONS = ImmutableSet.of(
           //12613, // ToB Maiden
           //13123, 13379, // ToB Sote
           //12612, // ToB Xarpus
           //12611, // ToB Verzik
           //15188, // ToA Ba-Ba
           //15184 Wardens p1
           14164, // ToA Kephri
           12889, // CoX Olm
           11601, // Nex
           9043,  // Inferno
           5789, 6045, // Yama
           14180,  // Doom of Mokhaiotl
           13210, // Scurrius
           5939, // Hueycotl
           15515, // Nightmare
           13106 // Zalcano
   );


   private static final Set<Integer> EXEMPT_ANIMATIONS = ImmutableSet.<Integer>builder()
           .add(1378, 7642, 7643, 7514, 1062, 1203, 7644, 7640, 7638, 10172, 5062, 9168, 8104)
           .add(714, 4069, 1500, 7040, 9131, 9286, 3945, 836, 2881, 4423) // Teleport animations
           .add(687, 684, 681, 677, 678, 679, 674, 688, 689, 670, 691, 702, 699, 671, 669, 665, 666, 667, 655) //spells
           .add(1161, 1162, 1163, 1164, 1165, 1166, 1167, 1168, 1169, 1156, 424, 9780) // Trident cast (1167), Strike cast (1162), Shield Blocks
           .add(366, 367, 368, 369, 370, 371, 372, 373, 374, 375, 376, 377, 378, 379, 380, 381, 382, 383, 384, 385, 386,
                   387, 388, 389, 390, 391, 392, 393, 394, 395, 396, 397, 398, 399, 400, 401, 402, 403, 404, 405, 406, 407,
                   408, 409, 410, 411, 412, 413, 414, 415, 416, 417, 418, 419, 420, 421, 422, 423, 424, 425, 426, 427, 428,
                   429, 430, 431, 432, 433, 434, 435, 436, 437, 438, 439, 440, 420, 424, 426, 707, 708, 709, 710, 711, 712,
                   713, 714, 715, 716, 717, 718, 719, 720, 721, 722, 723, 724, 725, 726, 727, 728, 729, 730, 731, 732, 733,
                   734, 735, 736, 737, 738, 739, 740, 741, 742, 743, 744, 745, 746, 747, 748, 749, 750, 751, 752, 753, 754,
                   755, 756, 757, 758, 759, 760, 761, 762, 763, 764, 765, 766, 767, 768, 769, 770, 771, 772, 773, 774, 775,
                   776, 777, 778, 779, 780, 781, 782, 783, 784, 785, 786, 787, 788, 789, 790, 791, 792, 793, 794, 795, 796,
                   797, 798, 799, 800, 801, 802, 803, 804, 805, 806, 807, 808, 809, 810, 811, 812, 813, 814, 815, 816, 817,
                   818, 819, 820, 821, 822, 823, 824, 825, 826, 827, 828, 829, 830, 831, 832, 833, 834, 835, 836, 837, 838,
                   839, 840, 841, 842, 843, 844, 845, 846, 847, 848, 849, 850, 851, 852, 853, 854, 855, 856, 857, 858, 859,
                   860, 861, 862, 863, 864, 865, 866, 867, 868, 869, 870, 871, 872, 873, 874, 875, 876, 877, 878, 879, 880,
                   881, 882, 883, 884, 885, 886, 887, 888, 889, 890, 891, 892, 893, 894, 895, 896, 897, 898, 899, 900, 901,
                   902, 903, 904, 905, 906, 907, 908, 909, 910, 911, 912, 913, 914, 931, 1062, 4071, 4177, 4426, 4427, 4428, 4429,
                   4462, 5061, 5063, 7642, 8457, 8458, 8459, 8460, 8461, 8462, 8463, 8464, 8465, 8466, 8467, 8468, 8469, 8470,
                   8471, 8472, 8473, 8474, 8475, 8476, 8477, 8478, 8479, 8480, 8481, 8482, 8483, 8484, 8485, 8486, 8487, 8488,
                   9848, 8970, 8972, 8973, 8974, 8975, 8976, 8977, 8978, 8979, 8980, 8981, 8982, 8983, 8985, 8987, 8993, 8995,
                   8997, 8998, 8999, 9002, 9168, 9471, 9493, 9713, 9714, 9715, 9799, 11222, 11430, 11275, 12397) //opacity breakers

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
           NpcID.ARCEUUS_THRALL_GHOST_GREATER, NpcID.ARCEUUS_THRALL_SKELETON_GREATER, NpcID.ARCEUUS_THRALL_ZOMBIE_GREATER,
           15703,
           15706,
           15709
   );

   // Whitelist for critical SpotAnims/Graphics (the visual effects themselves)
   private static final Set<Integer> CRITICAL_SPOTANIMS = ImmutableSet.<Integer>builder()
           //SpotanimID.java
           .add(2145, 2146, 317) //Kephri dung
           .add(2132, 2133, 2134, 2135 ,2136, 2137, 2138, 2139) //Sight Monkey Room
           .add(1568, 1569, 1570, 1571, 1572, 1573) //bloat
           .add(246, 1349, 1350, 1351, 1352, 1353, 1354, 1355, 1356, 1357, 1358, 1359, 1360, 1361, 1362, 1363) //olm
           .add(1604, 1605) //sotesegg
           .add (1997, 1998, 2002, 2003) //nex
           .add (2197, 2198, 2199, 2200, 2203) //wardens
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
      currentRegionId = -1;

      isProjectileOverrideActive = false;
      if (pluginManager != null)
      {
         for (Plugin p : pluginManager.getPlugins())
         {
            if ("Projectile Override".equals(p.getName()))
            {
               // isPluginEnabled checks if it is currently active in the user's config
               isProjectileOverrideActive = pluginManager.isPluginEnabled(p);
               break;
            }
         }
      }
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
      currentRegionId = -1;
   }

   public boolean isActive()
   {
      if (client.getGameState() != GameState.LOGGED_IN)
      {
         return false;
      }

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

      switch (currentRegionId)
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
         case 11669:
            return config.otherRoyalTitans();
         case 7216:
            return config.otherFortisColosseum();
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

         // --- NEW: They took damage, so they are in combat ---
         combatTimerMap.put(p, client.getTickCount());

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
                        targetIndex = i;
                     }
                  }
                  else if (!foundZero)
                  {
                     if (targetIndex == -1 || h.getDespawnTick() < list.get(targetIndex).getDespawnTick())
                     {
                        targetIndex = i;
                     }
                  }
               }

               if (amount == 0 && !foundZero)
               {
                  return;
               }

               if (targetIndex != -1)
               {
                  list.set(targetIndex, newHit);
               }
            }
         }
      }
   }

   @Subscribe
   public void onAnimationChanged(AnimationChanged event)
   {
      if (!isActive())
      {
         return;
      }

      if (event.getActor() instanceof Player)
      {
         Player p = (Player) event.getActor();
         int anim = p.getAnimation();

         // If the animation is not idle, and NOT a skilling/utility animation
         if (anim != -1 && !EXEMPT_ANIMATIONS.contains(anim))
         {
            combatTimerMap.put(p, client.getTickCount());
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

      // Clear the sets exactly once per tick to recalculate everyone flawlessly
      exemptPlayers.clear();
      criticalGraphicPlayers.clear();
      culledPlayers.clear();

      List<Player> playersToCheck = new ArrayList<>(currentInRange);
      if (cachedLocalPlayer != null)
      {
         playersToCheck.add(cachedLocalPlayer);
      }

      int currentCycle = client.getGameCycle();
      int currentTick = client.getTickCount();

      for (Player p : playersToCheck)
      {
         // --- 1. Combat Interaction Check ---
         Actor target = p.getInteracting();
         if (target != null && target.getCombatLevel() > 0)
         {
            combatTimerMap.put(p, currentTick);
         }

         // --- 2. Critical Graphic Check ---
         boolean activelyHasCriticalGraphic = false;
         int currentGraphic = p.getGraphic();

         if (currentGraphic != -1 && CRITICAL_SPOTANIMS.contains(currentGraphic))
         {
            activelyHasCriticalGraphic = true;
         }
         else if (p.getSpotAnims() != null)
         {
            for (ActorSpotAnim spotAnim : p.getSpotAnims())
            {
               if (CRITICAL_SPOTANIMS.contains(spotAnim.getId()))
               {
                  activelyHasCriticalGraphic = true;
                  break;
               }
            }
         }

         if (activelyHasCriticalGraphic)
         {
            lastCriticalGraphicCycleMap.put(p, currentCycle);
         }

         Integer lastGraphicCycle = lastCriticalGraphicCycleMap.get(p);
         boolean hasCriticalGraphic = lastGraphicCycle != null &&
                 (currentCycle - lastGraphicCycle <= CRITICAL_GRAPHIC_GRACE_PERIOD_CYCLES);

         if (hasCriticalGraphic)
         {
            criticalGraphicPlayers.add(p);
         }

         // --- 3. Forced Override Check ---
         Model model = p.getModel();
         boolean hasOverride = shouldForceOpaqueForOverride(p, model);

         if (hasCriticalGraphic || hasOverride)
         {
            exemptPlayers.add(p);
         }

         // --- 4. Distance Math & 0% Cull Check ---
         int opacity = getEffectiveOpacity(p);

         if (opacity == 0 && !exemptPlayers.contains(p))
         {
            culledPlayers.add(p);
         }
      }

      // --- Rest of tick logic ---
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

      if (peekHeld || isHideOthersProjectilesEnabled())
      {
         for (Player p : ghostedPlayers)
         {
            int currentGraphic = p.getGraphic();
            if (currentGraphic != -1 && !CRITICAL_SPOTANIMS.contains(currentGraphic))
            {
               p.setGraphic(-1);
            }

            if (p.getSpotAnims() != null)
            {
               for (ActorSpotAnim spotAnim : p.getSpotAnims())
               {
                  if (!CRITICAL_SPOTANIMS.contains(spotAnim.getId()))
                  {
                     p.removeSpotAnim(spotAnim.getId());
                  }
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

      Player local = client.getLocalPlayer();
      if (local == null)
      {
         return;
      }

      Actor target = proj.getTargetActor();

      if (target == null
              || isLocalPlayerTarget(target, local)
              || isProjectileLandingNearLocal(proj, local)
              || isProjectileMovedLandingNearLocal(event, local))
      {
         forceOpaqueProjectiles.add(proj);
      }

      if (myProjectiles.contains(proj))
      {
         return;
      }

      // --- Ownership Filter ---
      // Boss attacks target Players. AoE attacks target the ground (null).
      // If this projectile targets a player or the ground, it is impossible for
      // it to be your PvM attack. This stops you from "stealing" boss projectiles
      // when standing in melee range.
      if (target == null || target instanceof Player)
      {
         return;
      }

      if (client.getGameCycle() > proj.getStartCycle() + 150)
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

      // 192 units = 1.5 tiles
      if (distSq < (192 * 192) && local.getAnimation() != -1)
      {
         myProjectiles.add(proj);
      }
   }

   @Subscribe
   public void onPluginChanged(PluginChanged event)
   {
      if (event.getPlugin() != null && "Projectile Override".equals(event.getPlugin().getName()))
      {
         // isLoaded() returns true when the plugin starts, and false when it shuts down
         isProjectileOverrideActive = event.isLoaded();
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

      lastCriticalGraphicCycleMap.remove(p);
      criticalGraphicPlayers.remove(p);
      exemptPlayers.remove(p);
      culledPlayers.remove(p);
      lastCombatCycleMap.remove(p);
      combatTimerMap.remove(p);
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

      if (originalEquipmentMap.containsKey(p) && p.getPlayerComposition() != null)
      {
         originalEquipmentMap.put(p, p.getPlayerComposition().getEquipmentIds().clone());
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

      int maxAffectedPlayers = getMaxAffectedPlayers();

      if (inRange.size() > maxAffectedPlayers)
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

         currentInRange.addAll(inRange.subList(0, maxAffectedPlayers));
      }
      else
      {
         currentInRange.addAll(inRange);
      }
   }

   private int getMaxAffectedPlayers()
   {
      if (isInChambersOfXeric())
      {
         return COX_MAX_AFFECTED_PLAYERS;
      }

      return config.maxAffectedPlayers();
   }

   private boolean isInChambersOfXeric()
   {
      return currentRegionId == 12889 || client.getVarbitValue(Varbits.IN_RAID) == 1;
   }

   @Subscribe
   public void onGameTick(GameTick event)
   {
      Player local = client.getLocalPlayer();
      if (local != null && local.getLocalLocation() != null)
      {
         currentRegionId = WorldPoint.fromLocalInstance(client, local.getLocalLocation()).getRegionID();
      }
      else
      {
         currentRegionId = -1;
      }

      checkStateTransition();

      if (!isActive())
      {
         return;
      }

      LocalPoint localLoc = local.getLocalLocation();

      myProjectiles.removeIf(p -> client.getGameCycle() >= p.getEndCycle());
      forceOpaqueProjectiles.removeIf(p -> client.getGameCycle() >= p.getEndCycle());

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

      int selfOpacity = getEffectiveOpacity(local);

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

      arrayToModel.clear();
      arrayState.clear();

      final int STATE_OTHERS = 0;
      final int STATE_MINE = 1;
      final int STATE_RESTORE = 2;

// Append the check to your skipProjectiles boolean
      boolean skipProjectiles = RESTRICTED_PROJECTILE_REGIONS.contains(currentRegionId)
              || client.getPlayers().size() <= 1
              || client.getVarbitValue(Varbits.IN_RAID) == 1
              || isProjectileOverrideActiveForCurrentArea();

      if (!skipProjectiles)
      {
         for (Projectile proj : client.getProjectiles())
         {
            if (TRANS_NULL_IDS.contains(proj.getId()))
            {
               continue; // Forces the plugin to skip it, just like trans == null
            }

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

            Actor target = proj.getTargetActor();
            boolean isTargetingMeOrGround = target == null
                    || isLocalPlayerTarget(target, local)
                    || isProjectileLandingNearLocal(proj, local)
                    || forceOpaqueProjectiles.contains(proj);
            boolean isMine = myProjectiles.contains(proj);

            int currentState = arrayState.getOrDefault(trans, -1);

            if (isTargetingMeOrGround)
            {
               if (currentState < STATE_RESTORE)
               {
                  arrayState.put(trans, STATE_RESTORE);
                  arrayToModel.put(trans, m);
               }
            }
            else if (isMine)
            {
               if (currentState < STATE_MINE)
               {
                  arrayState.put(trans, STATE_MINE);
                  arrayToModel.put(trans, m);
               }
            }
            else
            {
               if (currentState < STATE_OTHERS)
               {
                  arrayState.put(trans, STATE_OTHERS);
                  arrayToModel.put(trans, m);
               }
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
            if (config.playerOpacity() < 100)
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

      if (renderable instanceof Projectile && (peekHeld || isHideOthersProjectilesEnabled()))
      {
         Projectile proj = (Projectile) renderable;

         if (TRANS_NULL_IDS.contains(proj.getId()))
         {
            return true;
         }

         if (RESTRICTED_PROJECTILE_REGIONS.contains(currentRegionId)
                 || client.getPlayers().size() <= 1
                 || client.getVarbitValue(Varbits.IN_RAID) == 1)
         {
            return true;
         }

         Actor target = proj.getTargetActor();
         return target == null
                 || isLocalPlayerTarget(target, cachedLocalPlayer)
                 || isProjectileLandingNearLocal(proj, cachedLocalPlayer)
                 || forceOpaqueProjectiles.contains(proj)
                 || myProjectiles.contains(proj);
      }

      if (renderable instanceof Player)
      {
         Player player = (Player) renderable;

         // Peek Through (Hold): show only yourself
         Player local = client.getLocalPlayer();

         if (peekHeld && player != client.getLocalPlayer())
         {
            return false;
         }

         // --- Fast O(1) Absolute Culling Check ---
         if (!drawingUI && culledPlayers.contains(player))
         {
            return false;
         }

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

   private boolean isLocalPlayerTarget(Actor target, Player local)
   {
      if (target == null || local == null)
      {
         return false;
      }

      if (target == local)
      {
         return true;
      }

      if (target instanceof Player)
      {
         return Objects.equals(target.getName(), local.getName());
      }

      return false;
   }

   private boolean isHideOthersProjectilesEnabled()
   {
      return config.hideOthersProjectiles() && !isProjectileOverrideActiveForCurrentArea();
   }

   private boolean isProjectileOverrideActiveForCurrentArea()
   {
      if (!isProjectileOverrideActive || configManager == null)
      {
         return false;
      }

      String[] keys = getProjectileOverrideKeysForCurrentRegion();

      for (String key : keys)
      {
         if (isProjectileOverrideKeyNonDefault(key))
         {
            return true;
         }
      }

      return false;
   }

   private boolean isProjectileOverrideKeyNonDefault(String key)
   {
      String value = configManager.getConfiguration("projectileoverride", key);

      if (value == null)
      {
         return false;
      }

      return !value.equalsIgnoreCase("default")
              && !value.equalsIgnoreCase("DEFAULT");
   }

   private String[] getProjectileOverrideKeysForCurrentRegion()
   {
      switch (currentRegionId)
      {
         case 15700: // ToA Zebak
            return new String[]{"zebak", "zebak-rocks"};

         case 14676: // ToA Akkha
            return new String[]{"akkha"};

         case 15184: // ToA Wardens
         case 15696:
            return new String[]{"wardens", "wardens-divine"};

         case 12889: // CoX Olm
            return new String[]{"olm"};

         case 13123: // ToB Sotetseg
         case 13379:
            return new String[]{"sotetseg"};

         case 9043: // Inferno
            return new String[]{"inferno"};

         case 14180: // Doom of Mokhaiotl
            return new String[]{"dom", "dom-rocks"};

         case 13210: // Scurrius
            return new String[]{"scurrius"};

         case 5939: // Hueycoatl
            return new String[]{"hueycoatl"};

         case 7216: // Fortis Colosseum, manticore projectiles
            return new String[]{"manticore"};

         default:
            return new String[0];
      }
   }

   private boolean isInCombat(Player player)
   {
      if (player == null)
      {
         return false;
      }

      Integer lastCombatTick = combatTimerMap.get(player);
      if (lastCombatTick == null)
      {
         return false;
      }

      // If they attacked, got hit, or targeted an enemy within the last 16 ticks, they are in combat.
      return (client.getTickCount() - lastCombatTick) <= COMBAT_TIMEOUT_TICKS;
   }

   private int getEffectiveOpacity(Player player)
   {
      Player local = client.getLocalPlayer();
      if (player == null || local == null)
      {
         return 100;
      }

      if (peekHeld)
      {
         if (player == local)
         {
            return config.selfClearGround() ? 100 : config.selfOpacity();
         }

         return 0;
      }

      boolean isLocal = (player == local);
      int baseOpacity = isLocal ?
              (config.selfClearGround() ? 100 : config.selfOpacity()) :
              (config.othersClearGround() ? 100 : config.playerOpacity());

      int calculatedOpacity = baseOpacity;

      if (!isLocal && config.distanceBasedOpacity())
      {
         LocalPoint localLoc = local.getLocalLocation();
         LocalPoint playerLoc = player.getLocalLocation();

         if (localLoc != null && playerLoc != null)
         {
            double dist = localLoc.distanceTo(playerLoc);

            double minDist = config.fadeStartDistance() * 128.0;
            double maxDist = Math.max(minDist + 1.0, config.fadeEndDistance() * 128.0);

            if (dist > minDist && dist < maxDist)
            {
               double fraction = (dist - minDist) / (maxDist - minDist);
               fraction = fraction * fraction;
               calculatedOpacity = (int) (baseOpacity + ((100 - baseOpacity) * fraction));
            }
            else if (dist >= maxDist)
            {
               calculatedOpacity = 100;
            }
         }
      }

      // Check exceptions if they are dropping to 0% opacity
      if (calculatedOpacity == 0)
      {
         if (criticalGraphicPlayers.contains(player))
         {
            return 1;
         }

         // If it's another player and they are NOT in combat, force 1% so they aren't fully culled
         if (!isLocal && !isInCombat(player))
         {
            return 1;
         }
      }

      return calculatedOpacity;
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

      // --- 1. ABSOLUTE PRIORITY: Persistent Boss Mechanics ---
      if (model.getOverrideAmount() != 0)
      {
         Integer startCycle = overrideStartCycle.get(player);
         if (startCycle == null)
         {
            overrideStartCycle.put(player, currentCycle);
            startCycle = currentCycle;
         }

         if (overrideForcedPlayers.contains(player))
         {
            return true;
         }

         // Must be active for delay to prove it isn't 1-tick garbage combat data
         if (currentCycle - startCycle >= OVERRIDE_OPAQUE_DELAY_CYCLES)
         {
            overrideForcedPlayers.add(player);
            overrideLastSeenCycle.put(player, currentCycle);
            return true;
         }
      }
      else
      {
         overrideStartCycle.remove(player);
      }

      // --- 2. COMBAT SHIELD ---
      if (isExemptAnimation(player))
      {
         overrideLastSeenCycle.remove(player);
         overrideForcedPlayers.remove(player);
         return false;
      }

      // --- 3. Linger Delay ---
      Integer lastSeenCycle = overrideLastSeenCycle.get(player);
      boolean withinClearDelay = (lastSeenCycle != null && currentCycle - lastSeenCycle <= OVERRIDE_CLEAR_DELAY_CYCLES);

      if (overrideForcedPlayers.contains(player))
      {
         if (withinClearDelay)
         {
            return true;
         }
         overrideForcedPlayers.remove(player);
      }

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

   private boolean isProjectileLandingNearLocal(Projectile proj, Player local)
   {
      if (proj == null || local == null)
      {
         return false;
      }

      LocalPoint localLoc = local.getLocalLocation();
      WorldPoint targetWorldPoint = proj.getTargetPoint();

      if (localLoc == null || targetWorldPoint == null)
      {
         return false;
      }

      LocalPoint targetPoint = LocalPoint.fromWorld(client, targetWorldPoint);

      if (targetPoint == null)
      {
         return false;
      }

      int dx = targetPoint.getX() - localLoc.getX();
      int dy = targetPoint.getY() - localLoc.getY();
      int distSq = (dx * dx) + (dy * dy);

      return distSq <= (192 * 192);
   }

   private boolean isProjectileMovedLandingNearLocal(ProjectileMoved event, Player local)
   {
      if (event == null || local == null)
      {
         return false;
      }

      LocalPoint localLoc = local.getLocalLocation();
      LocalPoint targetPoint = event.getPosition();

      if (localLoc == null || targetPoint == null)
      {
         return false;
      }

      int dx = targetPoint.getX() - localLoc.getX();
      int dy = targetPoint.getY() - localLoc.getY();
      int distSq = (dx * dx) + (dy * dy);

      return distSq <= (192 * 192);
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
      forceOpaqueProjectiles.clear();
      customHitsplats.clear();
      overrideStartCycle.clear();
      overrideLastSeenCycle.clear();
      overrideForcedPlayers.clear();
      lastCombatCycleMap.clear();

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

      lastCriticalGraphicCycleMap.clear();
      criticalGraphicPlayers.clear();
      exemptPlayers.clear();
      culledPlayers.clear();
      combatTimerMap.clear();
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
