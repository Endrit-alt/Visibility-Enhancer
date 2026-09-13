package com.visibilityenhancer;

import java.awt.Color;

import static net.runelite.api.HitsplatID.*;

/** Type-aware colours for our compact hitsplats, not a replacement for native sprite artwork. */
final class HitsplatStyle
{
   private static final Color RED = new Color(180, 40, 40);
   private static final Color BLUE = new Color(50, 90, 160);
   private static final Color GREEN = new Color(40, 160, 40);
   private static final Color DARK_GREEN = new Color(20, 85, 35);
   private static final Color PURPLE = new Color(170, 55, 170);
   private static final Color ORANGE = new Color(205, 110, 35);
   private static final Color CYAN = new Color(40, 150, 175);
   private static final Color YELLOW = new Color(185, 155, 35);
   private static final Color WHITE = new Color(180, 180, 180);

   private HitsplatStyle() {}

   static Color colorFor(int type, int amount)
   {
      switch (type)
      {
         case BLOCK_ME:
         case BLOCK_OTHER:
            return BLUE;
         case POISON:
            return GREEN;
         case VENOM:
            return DARK_GREEN;
         case HEAL:
            return PURPLE;
         case DISEASE:
         case DISEASE_BLOCKED:
         case DAMAGE_ME_ORANGE:
         case DAMAGE_OTHER_ORANGE:
         case DAMAGE_MAX_ME_ORANGE:
         case BURN:
            return ORANGE;
         case CYAN_UP:
         case CYAN_DOWN:
         case DAMAGE_ME_CYAN:
         case DAMAGE_OTHER_CYAN:
         case DAMAGE_MAX_ME_CYAN:
            return CYAN;
         case DAMAGE_ME_YELLOW:
         case DAMAGE_OTHER_YELLOW:
         case DAMAGE_MAX_ME_YELLOW:
            return YELLOW;
         case DAMAGE_ME_WHITE:
         case DAMAGE_OTHER_WHITE:
         case DAMAGE_MAX_ME_WHITE:
            return WHITE;
         case DAMAGE_ME:
         case DAMAGE_OTHER:
         case DAMAGE_MAX_ME:
         case BLEED:
            return RED;
         default:
            // Unknown artwork keeps the old visible fallback; never discard the message.
            return amount == 0 ? BLUE : RED;
      }
   }

   static boolean isCombatHit(int type)
   {
      switch (type)
      {
         case BLOCK_ME:
         case BLOCK_OTHER:
         case DAMAGE_ME:
         case DAMAGE_OTHER:
         case DAMAGE_MAX_ME:
         case DAMAGE_ME_CYAN:
         case DAMAGE_OTHER_CYAN:
         case DAMAGE_MAX_ME_CYAN:
         case DAMAGE_ME_ORANGE:
         case DAMAGE_OTHER_ORANGE:
         case DAMAGE_MAX_ME_ORANGE:
         case DAMAGE_ME_YELLOW:
         case DAMAGE_OTHER_YELLOW:
         case DAMAGE_MAX_ME_YELLOW:
         case DAMAGE_ME_WHITE:
         case DAMAGE_OTHER_WHITE:
         case DAMAGE_MAX_ME_WHITE:
         case DAMAGE_ME_POISE:
         case DAMAGE_OTHER_POISE:
         case DAMAGE_MAX_ME_POISE:
         case POISON:
         case VENOM:
         case BLEED:
         case BURN:
            return true;
         default:
            // Healing, disease/stat changes, prayer/sanity changes, doom and corruption
            // are not HP-damage signals. Future unknown types must be reviewed explicitly.
            return false;
      }
   }
}
