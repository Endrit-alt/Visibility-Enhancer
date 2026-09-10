package com.visibilityenhancer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Actor;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/** Per-frame scene-selected actors, not guesses based on the affected-player collection order. */
final class StackedHighlightTracker
{
   private final Map<TileKey, Actor> actors = new HashMap<>();
   private final Set<TileKey> visibleTiles = new HashSet<>();
   private boolean enabled;

   void beginFrame(boolean enabled)
   {
      this.enabled = enabled;
      actors.clear();
      visibleTiles.clear();
   }

   void record(Actor actor, boolean visible)
   {
      if (!enabled) return;
      TileKey tile = tileKey(actor);
      if (tile == null) return;

      // A visible actor beats one hidden by our 0% draw skip. For multiple draws
      // on the same tile, retain the later submission. This is not a per-pixel depth test.
      if (visible || !visibleTiles.contains(tile))
      {
         actors.put(tile, actor);
         if (visible) visibleTiles.add(tile);
      }
   }

   Actor preferredActor(Actor actor)
   {
      return enabled ? actors.get(tileKey(actor)) : null;
   }

   static TileKey tileKey(Actor actor)
   {
      if (!(actor instanceof Player) && !(actor instanceof NPC)) return null;
      WorldPoint world = actor.getWorldLocation();
      if (world == null) return null;
      LocalPoint local = actor.getLocalLocation();
      // Local coordinates follow the rendered movement, unlike the server's destination tile.
      return new TileKey(actor.getWorldView(), world.getPlane(),
              local == null ? world.getX() : local.getSceneX(),
              local == null ? world.getY() : local.getSceneY(), local != null, actor instanceof NPC);
   }

   static final class TileKey
   {
      private final WorldView view;
      private final int plane, x, y;
      private final boolean local, npc;

      private TileKey(WorldView view, int plane, int x, int y, boolean local, boolean npc)
      {
         this.view = view;
         this.plane = plane;
         this.x = x;
         this.y = y;
         this.local = local;
         this.npc = npc;
      }

      @Override
      public boolean equals(Object other)
      {
         if (!(other instanceof TileKey)) return false;
         TileKey key = (TileKey) other;
         return view == key.view && plane == key.plane && x == key.x && y == key.y
                 && local == key.local && npc == key.npc;
      }

      @Override
      public int hashCode()
      {
         int hash = 31 * (31 * (31 * System.identityHashCode(view) + plane) + x) + y;
         return 31 * (31 * hash + (local ? 1 : 0)) + (npc ? 1 : 0);
      }
   }
}
