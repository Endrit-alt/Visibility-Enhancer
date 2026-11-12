/*
 * The hitmark definition decoding, sprite loading and sprite layout in this file are adapted from
 * CustomizeALot (CustomizeALotHitmarkDefinition, CustomizeALotSprite, CustomizeALotRenderLayout).
 *
 * Copyright (c) 2026, Ruined Heir
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.visibilityenhancer;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.SpritePixels;
import net.runelite.client.ui.FontManager;

/**
 * The game's own hitsplat artwork: definitions and sprites are read from the client's game cache, so every
 * type (tinted, max, poise, doom, ...) looks like the native splat. Each splat is composed once at full
 * opacity into an image, which the overlay then draws at the configured hitsplat opacity, so overlapping
 * pieces and the number never darken each other. Client thread only (overlay rendering).
 */
final class HitsplatArtwork
{
   /** Returned when the game's definition says this hitsplat is not shown at all. */
   static final BufferedImage HIDDEN = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

   private static final int HITMARK_CONFIG_ARCHIVE = 32;
   private static final int MAX_TRANSFORM_DEPTH = 8;
   private static final int RETRY_CYCLES = 50;
   private static final int MAX_IMAGES = 128;
   private static final int DEFAULT_HEIGHT = 20;
   private static final Charset CP1252 = Charset.forName("windows-1252");

   private final Client client;
   private final Map<Integer, Definition> definitions = new HashMap<>();
   private final Map<Integer, Sprite> sprites = new HashMap<>();
   // Cache data that could not be read yet (e.g. while the client is still loading): retry later, not every frame.
   private final Map<Integer, Integer> definitionRetry = new HashMap<>();
   private final Map<Integer, Integer> spriteRetry = new HashMap<>();
   private final Map<Long, BufferedImage> images = new LinkedHashMap<Long, BufferedImage>(64, 0.75f, true)
   {
      @Override
      protected boolean removeEldestEntry(Map.Entry<Long, BufferedImage> eldest)
      {
         return size() > MAX_IMAGES;
      }
   };

   HitsplatArtwork(Client client)
   {
      this.client = client;
   }

   void clear()
   {
      definitions.clear();
      sprites.clear();
      definitionRetry.clear();
      spriteRetry.clear();
      images.clear();
   }

   /**
    * The native-looking splat at full opacity, {@link #HIDDEN} if the game would not show it, or null when the
    * artwork is not available (then the caller draws its simple fallback).
    *
    * @param numberOnly draw only the number (the "hide hitsplat background" option)
    */
   BufferedImage render(int hitsplatType, int amount, boolean numberOnly)
   {
      int cycle = client.getGameCycle();
      int type = hitsplatType;
      Definition definition = definition(type, cycle);
      for (int depth = 0; definition != null && definition.transforms != null && depth < MAX_TRANSFORM_DEPTH; depth++)
      {
         int next = definition.transformedType(client);
         if (next == Definition.TRANSFORM_UNAVAILABLE || next == type) break;
         if (next < 0) return HIDDEN;
         type = next;
         definition = definition(type, cycle);
      }
      if (definition == null || !definition.hasContent()) return null;

      long key = ((long) type << 33) ^ ((long) amount << 1) ^ (numberOnly ? 1 : 0);
      BufferedImage image = images.get(key);
      if (image != null) return image;

      Sprite first = null, middle = null, second = null, last = null;
      if (!numberOnly)
      {
         first = sprite(definition.firstSpriteId, cycle);
         middle = sprite(definition.middleSpriteId, cycle);
         second = sprite(definition.secondSpriteId, cycle);
         last = sprite(definition.lastSpriteId, cycle);
         if (missing(definition.firstSpriteId, first) || missing(definition.middleSpriteId, middle)
                 || missing(definition.secondSpriteId, second) || missing(definition.lastSpriteId, last))
         {
            return null; // Not loaded yet: keep the fallback for now rather than a splat with missing pieces.
         }
      }
      image = compose(first, middle, second, last, definition.formatAmount(amount),
              new Color(definition.textColor | 0xFF000000, true), definition.textYOffset,
              FontManager.getRunescapeSmallFont());
      if (image != null) images.put(key, image);
      return image;
   }

   private static boolean missing(int spriteId, Sprite sprite)
   {
      return spriteId >= 0 && sprite == null;
   }

   private Definition definition(int type, int cycle)
   {
      Definition cached = definitions.get(type);
      if (cached != null) return cached;
      Integer retry = definitionRetry.get(type);
      if (retry != null && cycle - retry < RETRY_CYCLES) return null;
      Definition loaded = null;
      try
      {
         byte[] data = client.getIndexConfig().loadData(HITMARK_CONFIG_ARCHIVE, type);
         loaded = data == null ? null : Definition.decode(data);
      }
      catch (RuntimeException ex)
      {
         loaded = null; // Unknown/changed data: never break hitsplats, use the fallback.
      }
      if (loaded == null) definitionRetry.put(type, cycle);
      else
      {
         definitionRetry.remove(type);
         definitions.put(type, loaded);
      }
      return loaded;
   }

   private Sprite sprite(int spriteId, int cycle)
   {
      if (spriteId < 0) return null;
      Sprite cached = sprites.get(spriteId);
      if (cached != null) return cached;
      Integer retry = spriteRetry.get(spriteId);
      if (retry != null && cycle - retry < RETRY_CYCLES) return null;
      Sprite loaded = null;
      try
      {
         SpritePixels[] pixels = client.getSprites(client.getIndexSprites(), spriteId, 0);
         if (pixels != null && pixels.length > 0 && pixels[0] != null)
         {
            BufferedImage image = pixels[0].toBufferedImage();
            if (image != null) loaded = new Sprite(image, pixels[0].getOffsetX(), pixels[0].getOffsetY());
         }
      }
      catch (RuntimeException ex)
      {
         loaded = null;
      }
      if (loaded == null) spriteRetry.put(spriteId, cycle);
      else
      {
         spriteRetry.remove(spriteId);
         sprites.put(spriteId, loaded);
      }
      return loaded;
   }

   /**
    * The game's layout: first piece, then the second, the middle repeated to fit the number, then the last;
    * the number is centred over the middle pieces with a 1px black shadow.
    */
   static BufferedImage compose(Sprite first, Sprite middle, Sprite second, Sprite last, String text,
                                Color textColor, int textYOffset, Font font)
   {
      BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
      Graphics2D measure = scratch.createGraphics();
      measure.setFont(font);
      FontMetrics metrics = measure.getFontMetrics();
      measure.dispose();

      int textWidth = metrics.stringWidth(text);
      int middleWidth = width(middle);
      int repeatCount = 0;
      if (middleWidth > 0)
      {
         repeatCount = second == null && last == null ? 1 : textWidth / middleWidth + 1;
      }

      int cursorX = 0;
      int firstX = cursorX - offsetX(first);
      cursorX += width(first) + 2;
      int secondX = cursorX - offsetX(second);
      cursorX += width(second);
      int middleX = cursorX - offsetX(middle);
      int textX = cursorX;
      if (middleWidth > 0)
      {
         int repeatedWidth = repeatCount * middleWidth;
         cursorX += repeatedWidth;
         textX += (repeatedWidth - textWidth) / 2;
      }
      else
      {
         cursorX += textWidth;
      }
      int lastX = cursorX - offsetX(last);
      boolean textOnly = first == null && middle == null && second == null && last == null;
      int textBaseline = textOnly
              ? textYOffset + (DEFAULT_HEIGHT - metrics.getHeight()) / 2 + metrics.getAscent()
              : textYOffset + 15;

      Bounds bounds = new Bounds();
      bounds.include(first, firstX);
      bounds.include(second, secondX);
      for (int i = 0; i < repeatCount; i++) bounds.include(middle, middleX + i * middleWidth);
      bounds.include(last, lastX);
      if (!text.isEmpty())
      {
         bounds.include(textX, textBaseline - metrics.getAscent(),
                 textX + Math.max(1, textWidth) + 1, textBaseline + metrics.getDescent() + 1);
      }
      if (!bounds.initialized) return null;

      int w = bounds.maxX - bounds.minX, h = bounds.maxY - bounds.minY;
      if (w <= 0 || h <= 0 || w > 512 || h > 512) return null;
      BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
      Graphics2D g = image.createGraphics();
      try
      {
         g.translate(-bounds.minX, -bounds.minY);
         draw(g, first, firstX);
         draw(g, second, secondX);
         for (int i = 0; i < repeatCount; i++) draw(g, middle, middleX + i * middleWidth);
         draw(g, last, lastX);
         if (!text.isEmpty())
         {
            g.setFont(font);
            g.setColor(Color.BLACK);
            g.drawString(text, textX + 1, textBaseline + 1);
            g.setColor(textColor);
            g.drawString(text, textX, textBaseline);
         }
      }
      finally
      {
         g.dispose();
      }
      return image;
   }

   private static void draw(Graphics2D g, Sprite sprite, int x)
   {
      if (sprite != null) g.drawImage(sprite.image, x + sprite.offsetX, sprite.offsetY, null);
   }

   private static int width(Sprite sprite)
   {
      return sprite == null ? 0 : sprite.image.getWidth();
   }

   private static int offsetX(Sprite sprite)
   {
      return sprite == null ? 0 : sprite.offsetX;
   }

   static final class Sprite
   {
      final BufferedImage image;
      final int offsetX, offsetY;

      Sprite(BufferedImage image, int offsetX, int offsetY)
      {
         this.image = image;
         this.offsetX = offsetX;
         this.offsetY = offsetY;
      }
   }

   private static final class Bounds
   {
      int minX, minY, maxX, maxY;
      boolean initialized;

      void include(Sprite sprite, int x)
      {
         if (sprite == null) return;
         int left = x + sprite.offsetX, top = sprite.offsetY;
         include(left, top, left + sprite.image.getWidth(), top + sprite.image.getHeight());
      }

      void include(int left, int top, int right, int bottom)
      {
         if (!initialized)
         {
            minX = left; minY = top; maxX = right; maxY = bottom;
            initialized = true;
            return;
         }
         minX = Math.min(minX, left);
         minY = Math.min(minY, top);
         maxX = Math.max(maxX, right);
         maxY = Math.max(maxY, bottom);
      }
   }

   /** The game's hitmark config (cache archive 32), as decoded by CustomizeALot. */
   static final class Definition
   {
      static final int TRANSFORM_UNAVAILABLE = Integer.MIN_VALUE;

      int firstSpriteId = -1, middleSpriteId = -1, secondSpriteId = -1, lastSpriteId = -1;
      int textColor = 0xFFFFFF;
      String textTemplate = "";
      int textYOffset;
      int transformVarbit = -1, transformVarp = -1;
      int[] transforms;

      boolean hasContent()
      {
         return firstSpriteId >= 0 || middleSpriteId >= 0 || secondSpriteId >= 0 || lastSpriteId >= 0
                 || !textTemplate.isEmpty();
      }

      String formatAmount(int amount)
      {
         return textTemplate.replace("%1", Integer.toString(amount));
      }

      int transformedType(Client client)
      {
         int selector;
         try
         {
            selector = transformVarbit != -1 ? client.getVarbitValue(transformVarbit)
                    : transformVarp != -1 ? client.getVarpValue(transformVarp) : -1;
         }
         catch (RuntimeException ex)
         {
            return TRANSFORM_UNAVAILABLE;
         }
         int index = selector >= 0 && selector < transforms.length - 1 ? selector : transforms.length - 1;
         return transforms[index];
      }

      static Definition decode(byte[] data)
      {
         Definition d = new Definition();
         Buffer buffer = new Buffer(data);
         while (true)
         {
            int opcode = buffer.readUnsignedByte();
            switch (opcode)
            {
               case 0: return d;
               case 1: buffer.readNullableLargeSmart(); break; // game font (RuneLite's small font is used)
               case 2: d.textColor = buffer.readMedium(); break;
               case 3: d.firstSpriteId = buffer.readNullableLargeSmart(); break;
               case 4: d.secondSpriteId = buffer.readNullableLargeSmart(); break;
               case 5: d.middleSpriteId = buffer.readNullableLargeSmart(); break;
               case 6: d.lastSpriteId = buffer.readNullableLargeSmart(); break;
               case 7: buffer.readShort(); break;
               case 8: d.textTemplate = buffer.readStringCp1252NullCircumfixed(); break;
               case 9: buffer.readUnsignedShort(); break; // duration
               case 10: buffer.readShort(); break;
               case 11: break;
               case 12: buffer.readUnsignedByte(); break;
               case 13: d.textYOffset = buffer.readShort(); break;
               case 14: buffer.readUnsignedShort(); break;
               case 15:
               case 16: break;
               case 17:
               case 18: d.decodeTransforms(buffer, opcode == 18); break;
               default: throw new IllegalArgumentException("Unknown hitmark opcode " + opcode);
            }
         }
      }

      private void decodeTransforms(Buffer buffer, boolean hasFallback)
      {
         transformVarbit = normalize(buffer.readUnsignedShort());
         transformVarp = normalize(buffer.readUnsignedShort());
         int fallback = hasFallback ? normalize(buffer.readUnsignedShort()) : -1;
         int count = buffer.readUnsignedByte();
         transforms = new int[count + 2];
         for (int i = 0; i <= count; i++) transforms[i] = normalize(buffer.readUnsignedShort());
         transforms[count + 1] = fallback;
      }

      private static int normalize(int value)
      {
         return value == 65535 ? -1 : value;
      }
   }

   private static final class Buffer
   {
      private final byte[] data;
      private int offset;

      Buffer(byte[] data)
      {
         this.data = data;
      }

      int readUnsignedByte()
      {
         return data[offset++] & 0xFF;
      }

      int readUnsignedShort()
      {
         offset += 2;
         return ((data[offset - 2] & 0xFF) << 8) | (data[offset - 1] & 0xFF);
      }

      int readShort()
      {
         int value = readUnsignedShort();
         return value > 32767 ? value - 65536 : value;
      }

      int readMedium()
      {
         offset += 3;
         return ((data[offset - 3] & 0xFF) << 16) | ((data[offset - 2] & 0xFF) << 8) | (data[offset - 1] & 0xFF);
      }

      int readInt()
      {
         offset += 4;
         return ((data[offset - 4] & 0xFF) << 24) | ((data[offset - 3] & 0xFF) << 16)
                 | ((data[offset - 2] & 0xFF) << 8) | (data[offset - 1] & 0xFF);
      }

      int readNullableLargeSmart()
      {
         if (data[offset] < 0) return readInt() & Integer.MAX_VALUE;
         int value = readUnsignedShort();
         return value == 32767 ? -1 : value;
      }

      String readStringCp1252NullCircumfixed()
      {
         if (readUnsignedByte() != 0) throw new IllegalStateException("Expected circumfixed string prefix");
         int start = offset;
         while (data[offset++] != 0)
         {
            // scan to terminator
         }
         int length = offset - start - 1;
         return length == 0 ? "" : new String(data, start, length, CP1252);
      }
   }
}
