package com.visibilityenhancer;

import java.lang.reflect.Proxy;
import net.runelite.api.Actor;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/** Shared mock fixtures for the scene-overlay layering tests. */
final class LayeringTestSupport
{
   static Object defaultValue(Class<?> type)
   {
      if (type == boolean.class) return false;
      if (type == byte.class) return (byte) 0;
      if (type == short.class) return (short) 0;
      if (type == int.class) return 0;
      if (type == long.class) return 0L;
      if (type == float.class) return 0F;
      if (type == double.class) return 0D;
      if (type == char.class) return (char) 0;
      return null;
   }

   static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler)
   {
      return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, args) ->
      {
         if (m.getName().equals("hashCode")) return System.identityHashCode(p);
         if (m.getName().equals("equals")) return p == args[0];
         if (m.getName().equals("toString")) return type.getSimpleName();
         return handler.invoke(p, m, args);
      }));
   }

   static final class ActorState
   {
      WorldPoint world = new WorldPoint(3200, 3200, 0);
      LocalPoint local = new LocalPoint(1024, 1024);
      WorldView view;
      byte override;
      int modelRequests;
      final Model model = proxy(Model.class, (p, m, a) -> m.getName().equals("getOverrideAmount")
              ? override : defaultValue(m.getReturnType()));
      final Actor actor;

      ActorState(boolean npc, int id)
      {
         NPCComposition composition = proxy(NPCComposition.class, (p, m, a) ->
                 m.getName().equals("isInteractible") ? true : defaultValue(m.getReturnType()));
         Class<? extends Actor> actorType = npc ? NPC.class : Player.class;
         actor = proxy(actorType, (p, m, a) ->
         {
            switch (m.getName())
            {
               case "getId": return id;
               case "getWorldLocation": return world;
               case "getLocalLocation": return local;
               case "getWorldView": return view;
               case "getModel": modelRequests++; return model;
               case "getName": return "Sotetseg";
               case "getTransformedComposition": return composition;
               default: return defaultValue(m.getReturnType());
            }
         });
      }

      Player player() { return (Player) actor; }
   }

}
