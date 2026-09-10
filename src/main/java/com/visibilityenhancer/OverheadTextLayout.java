package com.visibilityenhancer;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Player;

/** Packs chat upward, with a small release margin to avoid one-pixel overlap flicker. */
final class OverheadTextLayout
{
   private static final int GAP = 2;
   private static final int RELEASE_MARGIN_X = 2;
   private static final int RELEASE_MARGIN_Y = 6;
   private final List<Rectangle> occupied = new ArrayList<>();
   private Set<Player> raisedLastFrame = new HashSet<>();
   private Set<Player> raisedThisFrame = new HashSet<>();

   void beginFrame()
   {
      occupied.clear();
      Set<Player> scratch = raisedLastFrame;
      raisedLastFrame = raisedThisFrame;
      raisedThisFrame = scratch;
      raisedThisFrame.clear();
   }

   void clear()
   {
      occupied.clear();
      raisedLastFrame.clear();
      raisedThisFrame.clear();
   }

   void reserve(Rectangle bounds)
   {
      occupied.add(bounds);
   }

   Rectangle place(Player player, Rectangle naturalBounds)
   {
      Rectangle placed = new Rectangle(naturalBounds);
      Rectangle probe = new Rectangle();
      boolean wasRaised = raisedLastFrame.contains(player);
      boolean moved;
      do
      {
         moved = false;
         probe.setBounds(placed);
         // A previously lifted message needs clear separation before dropping back.
         // Always start from this frame's anchor, never a cached screen position.
         if (wasRaised) probe.grow(RELEASE_MARGIN_X, RELEASE_MARGIN_Y);
         for (Rectangle bounds : occupied)
         {
            int above = bounds.y - placed.height - GAP;
            if (above < placed.y && probe.intersects(bounds))
            {
               placed.y = above;
               moved = true;
               break;
            }
         }
      }
      while (moved);

      if (placed.y < naturalBounds.y) raisedThisFrame.add(player);
      occupied.add(placed);
      return placed;
   }
}
