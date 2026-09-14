/*
 * Copyright (c) 2018-2021, Woox <https://github.com/wooxsolo>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.visibilityenhancer;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.MainBufferProvider;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;

/**
 * Actor-only adaptation of RuneLite's ModelOutlineRenderer (validated with 1.12.38).
 * Upstream source blob: 80f168a75fbcf05f79f2d46a5bb2da257bfac83c.
 * Preserves destination alpha in the premultiplied UI buffer and shares model
 * preparation between glow and outline. Used only by Visibility's outlines.
 */
@Singleton
final class VisibilityOutlineRenderer
{
	@AllArgsConstructor
	private static class PixelDistanceDelta
	{
		private final int dx;
		private final int dy;
	}

	@AllArgsConstructor
	private static class PixelDistanceGroupIndex
	{
		@Getter(AccessLevel.PRIVATE)
		private final double distance;
		private final int distanceGroupIndex;
		private final double alphaMultiply;
	}

	private static final int MAX_OUTLINE_WIDTH = 50;
	private static final int MAX_FEATHER = 4;
	private static final int DIRECT_WRITE_OUTLINE_WIDTH_THRESHOLD = 10;

	private final Client client;

	// Vertex positions projected on the screen.
	private final int[] projectedVerticesX = new int[6500];
	private final int[] projectedVerticesY = new int[6500];

	// Window boundaries for the ingame world
	private int clipX1;
	private int clipY1;
	private int clipX2;
	private int clipY2;

	// Boundaries for the outline
	private int croppedX1;
	private int croppedY1;
	private int croppedX2;
	private int croppedY2;
	private int croppedWidth;
	private int croppedHeight;

	// Bitset with pixel positions that would be rendered to within the cropped area by the model.
	private int[] visited = new int[0];
	// Spare cropped bitset for a second layer; never a screen image or a cached actor.
	private int[] silhouette = new int[0];

	// Memory used for queueing the pixels for the outline of the model.
	// Pixels are grouped by x and y distance to the closest pixel drawn on the model.
	// A block buffer is used so memory can be reused after a group has been processed
	// without using the JVM garbage collector.
	private final OutlinePixelBuffer outlinePixelsBlockBuffer = new OutlinePixelBuffer();
	private int[][] outlinePixelsBlockIndices = new int[0][];
	private int[] outlinePixelsBlockIndicesLengths = new int[0];
	private int[] outlinePixelsLastBlockLength;
	private int outlineArrayWidth;

	// An array of pixel group indices ordered by distance for each outline width and feather.
	// These are calculated once upon first usage and then stored here to skip reevaluation.
	private PixelDistanceGroupIndex[][][] precomputedGroupIndices = new PixelDistanceGroupIndex[0][][];

	// An array of pixel distance deltas for each outline width and direction (right/up/left/down).
	// These are calculated once upon first usage and then stored here to skip reevaluation.
	private PixelDistanceDelta[][][] precomputedDistanceDeltas = new PixelDistanceDelta[0][][];

	@Inject
	VisibilityOutlineRenderer(Client client)
	{
		this.client = client;
	}

	/**
	 * Calculate the next power of two of a value.
	 *
	 * @param value The value to find the next power of two of.
	 * @return Returns the next power of two.
	 */
	private static int nextPowerOfTwo(int value)
	{
		value--;
		value |= value >> 1;
		value |= value >> 2;
		value |= value >> 4;
		value |= value >> 8;
		value |= value >> 16;
		value++;
		return value;
	}

	/**
	 * Determine if a triangle goes counter clockwise
	 *
	 * @return Returns true if the triangle goes counter clockwise and should be culled, otherwise false
	 */
	private static boolean cullFace(int x1, int y1, int x2, int y2, int x3, int y3)
	{
		return (y2 - y1) * (x3 - x2) - (x2 - x1) * (y3 - y2) <= 0;
	}

	/**
	 * Get an array of pixel outline group indices ordered by distance for a specific outline width.
	 *
	 * @param outlineWidth The outline width.
	 * @param feather The feather of the outline.
	 * @return Returns the list of pixel distances.
	 */
	private PixelDistanceGroupIndex[] getPriorityList(int outlineWidth, int feather)
	{
		if (precomputedGroupIndices.length <= outlineWidth)
		{
			precomputedGroupIndices = Arrays.copyOf(precomputedGroupIndices, outlineWidth + 1);
		}
		if (precomputedGroupIndices[outlineWidth] == null)
		{
			precomputedGroupIndices[outlineWidth] = new PixelDistanceGroupIndex[feather + 1][];
		}
		else if (precomputedGroupIndices[outlineWidth].length <= feather)
		{
			precomputedGroupIndices[outlineWidth] = Arrays.copyOf(precomputedGroupIndices[outlineWidth], feather + 1);
		}

		if (precomputedGroupIndices[outlineWidth][feather] == null)
		{
			double fadedDistance = (double) feather / MAX_FEATHER * (outlineWidth - 0.5);
			List<PixelDistanceGroupIndex> ps = new ArrayList<>();
			for (int x = 0; x <= outlineWidth; x++)
			{
				for (int y = 0; y <= outlineWidth; y++)
				{
					if (x == 0 && y == 0)
					{
						continue;
					}

					double dist = Math.hypot(x, y);
					if (dist > outlineWidth)
					{
						continue;
					}

					double outerDist = outlineWidth - dist + 0.5;
					double multipliedAlpha = outerDist < fadedDistance ? outerDist / fadedDistance : 1.0;
					ps.add(new PixelDistanceGroupIndex(dist, x + y * outlineArrayWidth, multipliedAlpha));
				}
			}
			ps.sort(Comparator.comparingDouble(PixelDistanceGroupIndex::getDistance));
			precomputedGroupIndices[outlineWidth][feather] = ps.toArray(new PixelDistanceGroupIndex[0]);
		}
		return precomputedGroupIndices[outlineWidth][feather];
	}

	private void ensureDistanceDeltasCreated(int outlineWidth)
	{
		if (precomputedDistanceDeltas.length <= outlineWidth)
		{
			precomputedDistanceDeltas = Arrays.copyOf(precomputedDistanceDeltas, outlineWidth + 1);
		}
		if (precomputedDistanceDeltas[outlineWidth] == null)
		{
			precomputedDistanceDeltas[outlineWidth] = new PixelDistanceDelta[4][];
		}
		if (precomputedDistanceDeltas[outlineWidth][0] != null)
		{
			return;
		}

		List<PixelDistanceDelta> distances = new ArrayList<>();
		for (int dy = -outlineWidth; dy <= outlineWidth; dy++)
		{
			for (int dx = 1; dx <= outlineWidth; dx++)
			{
				if (Math.abs(dy) > dx)
				{
					continue;
				}

				double dist = Math.hypot(dx, dy);
				if (dist > outlineWidth)
				{
					continue;
				}

				distances.add(new PixelDistanceDelta(dx, dy));
			}
		}

		for (int direction = 0; direction < 4; direction++)
		{
			precomputedDistanceDeltas[outlineWidth][direction] = distances.toArray(new PixelDistanceDelta[0]);

			// Turn 90 deg ccw
			for (int i = 0; i < distances.size(); i++)
			{
				PixelDistanceDelta pdd = distances.get(i);
				distances.set(i, new PixelDistanceDelta(pdd.dy, -pdd.dx));
			}
		}
	}

	/**
	 * Enqueues a pixel for outlining.
	 *
	 * @param distanceGroupIndex The group index to enqueue the pixel into.
	 * @param x The x position of the pixel.
	 * @param y The y position of the pixel.
	 */
	private void enqueueOutlinePixel(int distanceGroupIndex, int x, int y)
	{
		if (outlinePixelsLastBlockLength[distanceGroupIndex] == OutlinePixelBuffer.BLOCK_SIZE)
		{
			int minimumBlockIndicesSize = outlinePixelsBlockIndicesLengths[distanceGroupIndex] + 1;
			if (minimumBlockIndicesSize > outlinePixelsBlockIndices[distanceGroupIndex].length)
			{
				outlinePixelsBlockIndices[distanceGroupIndex] = Arrays.copyOf(
					outlinePixelsBlockIndices[distanceGroupIndex],
					nextPowerOfTwo(minimumBlockIndicesSize));
			}
			outlinePixelsBlockIndices[distanceGroupIndex][outlinePixelsBlockIndicesLengths[distanceGroupIndex]] =
				outlinePixelsBlockBuffer.useNewBlock();
			outlinePixelsBlockIndicesLengths[distanceGroupIndex]++;
			outlinePixelsLastBlockLength[distanceGroupIndex] = 0;
		}

		int[] memory = outlinePixelsBlockBuffer.getMemory();
		int block = outlinePixelsBlockIndices[distanceGroupIndex][outlinePixelsBlockIndicesLengths[distanceGroupIndex] - 1];
		int blockPos = outlinePixelsLastBlockLength[distanceGroupIndex]++;
		memory[(block << OutlinePixelBuffer.BLOCK_BITS) + blockPos] = (y << 16) | x;
	}

	/**
	 * Checks that the visited bitset is big enough to hold a certain amount of pixels and sets them to 0.
	 *
	 * @param pixelAmount The amount of pixels needed.
	 */
	private void resetVisited(int pixelAmount)
	{
		int size = (pixelAmount >>> 5);
		if (visited.length < size)
		{
			visited = new int[nextPowerOfTwo(size)];
		}
		Arrays.fill(visited, 0, size, 0);
	}

	/**
	 * Ensures that the outline buffer arrays are large enough to fit the current outline.
	 */
	private void initializeOutlineBuffers()
	{
		int arraySizes = outlineArrayWidth * outlineArrayWidth;
		if (outlinePixelsBlockIndicesLengths.length < arraySizes)
		{
			outlinePixelsBlockIndices = new int[arraySizes][];
			outlinePixelsBlockIndicesLengths = new int[arraySizes];
			outlinePixelsLastBlockLength = new int[arraySizes];
			for (int i = 0; i < arraySizes; i++)
			{
				outlinePixelsBlockIndices[i] = new int[0];
			}
		}

		for (int i = 0; i < arraySizes; i++)
		{
			outlinePixelsLastBlockLength[i] = OutlinePixelBuffer.BLOCK_SIZE;
		}
	}

	/**
	 * Frees all blocks currently in use by the outline block buffer.
	 */
	private void freeAllBlockMemory()
	{
		for (int i = 0; i < outlineArrayWidth * outlineArrayWidth; i++)
		{
			while (outlinePixelsBlockIndicesLengths[i] > 0)
			{
				outlinePixelsBlockIndicesLengths[i]--;
				outlinePixelsBlockBuffer.freeBlock(outlinePixelsBlockIndices[i][outlinePixelsBlockIndicesLengths[i]]);
			}
			outlinePixelsLastBlockLength[i] = OutlinePixelBuffer.BLOCK_SIZE;
		}
	}

	/**
	 * Simulates a horizontal line rasterization and marks pixels visited.
	 *
	 * @param pixelY The y position of the line
	 * @param x1 The starting x position
	 * @param x2 The ending x position
	 */
	private void simulateHorizontalLineRasterizationForOutline(int pixelY, int x1, int x2)
	{
		if (x2 > clipX2)
		{
			x2 = clipX2;
		}
		if (x1 < clipX1)
		{
			x1 = clipX1;
		}
		if (x1 >= x2)
		{
			return;
		}

		int pixelPos1 = (pixelY - croppedY1) * croppedWidth + (x1 - croppedX1);
		int pixelPos2 = pixelPos1 + x2 - x1;
		int pixelPosIndex1 = pixelPos1 >> 5;
		int pixelPosIndex2 = pixelPos2 >> 5;
		if (pixelPosIndex1 == pixelPosIndex2)
		{
			visited[pixelPosIndex1] |= ((1 << (pixelPos2 & 31)) - 1) ^ ((1 << (pixelPos1 & 31)) - 1);
		}
		else
		{
			visited[pixelPosIndex1] |= -(1 << (pixelPos1 & 31));
			visited[pixelPosIndex2] |= (1 << (pixelPos2 & 31)) - 1;
			for (int i = pixelPosIndex1 + 1; i < pixelPosIndex2; i++)
			{
				visited[i] = 0xFFFFFFFF;
			}
		}
	}

	/**
	 * Simulates rasterization of a triangle and marks pixels visited.
	 *
	 * @param x1 The x position of the first vertex in the triangle
	 * @param y1 The y position of the first vertex in the triangle
	 * @param x2 The x position of the second vertex in the triangle
	 * @param y2 The y position of the second vertex in the triangle
	 * @param x3 The x position of the third vertex in the triangle
	 * @param y3 The y position of the third vertex in the triangle
	 */
	private void simulateTriangleRasterizationForOutline(
		int x1, int y1, int x2, int y2, int x3, int y3)
	{
		// Swap vertices so y1 <= y2 <= y3 using bubble sort
		if (y1 > y2)
		{
			int yp = y1;
			int xp = x1;
			y1 = y2;
			y2 = yp;
			x1 = x2;
			x2 = xp;
		}
		if (y2 > y3)
		{
			int yp = y2;
			int xp = x2;
			y2 = y3;
			y3 = yp;
			x2 = x3;
			x3 = xp;
		}
		if (y1 > y2)
		{
			int yp = y1;
			int xp = x1;
			y1 = y2;
			y2 = yp;
			x1 = x2;
			x2 = xp;
		}

		if (y1 > clipY2)
		{
			// All points are outside clip boundaries
			return;
		}

		int slope1 = 0;
		if (y1 != y2)
		{
			slope1 = (x2 - x1 << 14) / (y2 - y1);
		}

		int slope2 = 0;
		if (y3 != y2)
		{
			slope2 = (x3 - x2 << 14) / (y3 - y2);
		}

		int slope3 = 0;
		if (y1 != y3)
		{
			slope3 = (x1 - x3 << 14) / (y1 - y3);
		}

		if (y2 > clipY2)
		{
			y2 = clipY2;
		}
		if (y3 > clipY2)
		{
			y3 = clipY2;
		}
		if (y1 == y3 || y3 < clipY1)
		{
			return;
		}

		x1 <<= 14;
		x2 <<= 14;
		x3 = x1;

		if (y1 < clipY1)
		{
			x3 -= (y1 - clipY1) * slope3;
			x1 -= (y1 - clipY1) * slope1;
			y1 = clipY1;
		}
		if (y2 < clipY1)
		{
			x2 -= (y2 - clipY1) * slope2;
			y2 = clipY1;
		}

		int pixelY = y1;
		int height1 = y2 - y1;
		int height2 = y3 - y2;
		if (y1 != y2 && slope3 < slope1 || y1 == y2 && slope3 > slope2)
		{
			while (height1-- > 0)
			{
				simulateHorizontalLineRasterizationForOutline(pixelY, x3 >> 14, x1 >> 14);
				x3 += slope3;
				x1 += slope1;
				pixelY++;
			}

			while (height2-- > 0)
			{
				simulateHorizontalLineRasterizationForOutline(pixelY, x3 >> 14, x2 >> 14);
				x3 += slope3;
				x2 += slope2;
				pixelY++;
			}
		}
		else
		{
			while (height1-- > 0)
			{
				simulateHorizontalLineRasterizationForOutline(pixelY, x1 >> 14, x3 >> 14);
				x1 += slope1;
				x3 += slope3;
				pixelY++;
			}

			while (height2-- > 0)
			{
				simulateHorizontalLineRasterizationForOutline(pixelY, x2 >> 14, x3 >> 14);
				x3 += slope3;
				x2 += slope2;
				pixelY++;
			}
		}
	}

	/**
	 * Translates the vertices 3D points to the screen canvas 2D points.
	 *
	 * @param localX The local x position of the vertices.
	 * @param localY The local y position of the vertices.
	 * @param localZ The local z position of the vertices.
	 * @param vertexOrientation The orientation of the vertices.
	 * @return Returns true if any of them are inside the clip area, otherwise false.
	 */
	private boolean projectVertices(WorldView wv, Model model, int localX, int localY, int localZ, final int vertexOrientation)
	{
		final int vertexCount = model.getVerticesCount();
		Perspective.modelToCanvas(client, wv,
			vertexCount,
			localX, localY, localZ,
			vertexOrientation,
			model.getVerticesX(), model.getVerticesZ(), model.getVerticesY(),
			projectedVerticesX, projectedVerticesY);

		boolean anyVisible = false;

		for (int i = 0; i < vertexCount; i++)
		{
			int x = projectedVerticesX[i];
			int y = projectedVerticesY[i];

			if (y != Integer.MIN_VALUE)
			{
				boolean visibleX = x >= clipX1 && x < clipX2;
				boolean visibleY = y >= clipY1 && y < clipY2;
				anyVisible |= visibleX && visibleY;

				croppedX1 = Math.min(croppedX1, x);
				croppedX2 = Math.max(croppedX2, x + 1);
				croppedY1 = Math.min(croppedY1, y);
				croppedY2 = Math.max(croppedY2, y + 1);
			}
			else
			{
				// Vertex is too close or behind camera and isn't rendered
				projectedVerticesY[i] = Integer.MIN_VALUE;
			}
		}

		return anyVisible;
	}

	/**
	 * Simulates rendering of the model and marks every pixel visited.
	 */
	private void simulateModelRasterizationForOutline(Model model)
	{
		final int triangleCount = model.getFaceCount();
		final int[] indices1 = model.getFaceIndices1();
		final int[] indices2 = model.getFaceIndices2();
		final int[] indices3 = model.getFaceIndices3();
		final byte[] triangleTransparencies = model.getFaceTransparencies();

		for (int i = 0; i < triangleCount; i++)
		{
			if (projectedVerticesY[indices1[i]] != Integer.MIN_VALUE &&
				projectedVerticesY[indices2[i]] != Integer.MIN_VALUE &&
				projectedVerticesY[indices3[i]] != Integer.MIN_VALUE &&
				// 254 and 255 counts as fully transparent
				(triangleTransparencies == null || (triangleTransparencies[i] & 255) < 254))
			{
				final int index1 = indices1[i];
				final int index2 = indices2[i];
				final int index3 = indices3[i];
				final int v1x = projectedVerticesX[index1];
				final int v1y = projectedVerticesY[index1];
				final int v2x = projectedVerticesX[index2];
				final int v2y = projectedVerticesY[index2];
				final int v3x = projectedVerticesX[index3];
				final int v3y = projectedVerticesY[index3];

				if (!cullFace(v1x, v1y, v2x, v2y, v3x, v3y))
				{
					simulateTriangleRasterizationForOutline(v1x, v1y, v2x, v2y, v3x, v3y);
				}
			}
		}
	}

	/**
	 * Draws the outline of a pixel according to the distance deltas of an outline.
	 *
	 * @param imageData The image data to draw to.
	 * @param imageWidth The width of the image to draw to.
	 * @param x The x position of the pixel.
	 * @param y The y position of the pixel.
	 * @param distanceDeltas The distance deltas of the outline width.
	 * @param color The color to draw the outline in.
	 */
	private void rasterDistanceDeltas(int[] imageData, int imageWidth, int x, int y,
		PixelDistanceDelta[] distanceDeltas, int color)
	{
		for (PixelDistanceDelta delta : distanceDeltas)
		{
			int cx = x + delta.dx;
			int cy = y + delta.dy;
			int visitedPixelPos = (cy - croppedY1) * croppedWidth + (cx - croppedX1);
			if (cx >= clipX1 && cx < clipX2 && cy >= clipY1 && cy < clipY2 &&
				(visited[visitedPixelPos >> 5] & (1 << (visitedPixelPos & 31))) == 0)
			{
				imageData[cy * imageWidth + cx] = color;
			}
		}
	}

	/**
	 * Enqueues pixels that are adjacent above or below the model
	 * or draws them directly to the clients image buffer.
	 *
	 * @param directWrite If true the pixels are drawn to the image buffer, otherwise they are enqueued for drawing.
	 * @param color The color to draw if directWrite == true
	 * @param outlineWidth The outline width to draw if directWrite == true
	 */
	private void processInitialOutlinePixels(boolean directWrite, Color color, int outlineWidth)
	{
		MainBufferProvider bufferProvider = (MainBufferProvider) client.getBufferProvider();
		BufferedImage image = (BufferedImage) bufferProvider.getImage();
		int imageWidth = image.getWidth();
		int[] imageData = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		int colorRGB = color.getRGB();

		// Up and down
		for (int x = 0; x < croppedWidth; x += 32)
		{
			int v1 = visited[x >> 5];
			for (int y = 1; y < croppedHeight; y++)
			{
				int v2 = visited[(y * croppedWidth + x) >> 5];
				if (v1 != v2)
				{
					if (directWrite)
					{
						// Special case handling outlineWidth == 1 gives a
						// small but noticeable performance improvement.
						if (outlineWidth == 1)
						{
							for (int bit = 0; bit < 32; bit++)
							{
								int bv1 = (v1 >>> bit) & 1;
								int bv2 = (v2 >>> bit) & 1;
								if (bv1 != bv2)
								{
									imageData[(croppedY1 + y - bv2) * imageWidth + (croppedX1 + x + bit)] = colorRGB;
								}
							}
						}
						else
						{
							PixelDistanceDelta[] distancesDown = precomputedDistanceDeltas[outlineWidth][3];
							PixelDistanceDelta[] distancesUp = precomputedDistanceDeltas[outlineWidth][1];
							for (int bit = 0; bit < 32; bit++)
							{
								int bv1 = (v1 >>> bit) & 1;
								int bv2 = (v2 >>> bit) & 1;
								if (bv1 == 1 && bv2 == 0)
								{
									rasterDistanceDeltas(imageData, imageWidth, croppedX1 + x + bit, croppedY1 + y - 1,
										distancesDown, colorRGB);
								}
								else if (bv1 == 0 && bv2 == 1)
								{
									rasterDistanceDeltas(imageData, imageWidth, croppedX1 + x + bit, croppedY1 + y,
										distancesUp, colorRGB);
								}
							}
						}
					}
					else
					{
						for (int bit = 0; bit < 32; bit++)
						{
							int bv1 = (v1 >>> bit) & 1;
							int bv2 = (v2 >>> bit) & 1;
							if (bv1 != bv2)
							{
								enqueueOutlinePixel(outlineArrayWidth, croppedX1 + x + bit, croppedY1 + y - bv2);
							}
						}
					}
				}

				v1 = v2;
			}
		}

		// Left and right
		for (int y = 0; y < croppedHeight; y++)
		{
			int rowPosition = y * croppedWidth;
			int lastV = 0;
			for (int x = 0; x < croppedWidth; x += 32)
			{
				int v = visited[(rowPosition + x) >> 5];

				// Test adjacent pixels in the same 32-bit segment
				if (v != 0 && v != 0xFFFFFFFF)
				{
					int end = Math.min(32, clipX2 - croppedX1 - x);
					int lastBv = v & 1;
					if (directWrite)
					{
						// Special case handling outlineWidth == 1 gives a
						// small but noticeable performance improvement.
						if (outlineWidth == 1)
						{
							for (int bit = 1; bit < end; bit++)
							{
								int bv = (v >>> bit) & 1;
								if (bv != lastBv)
								{
									imageData[(croppedY1 + y) * imageWidth + (croppedX1 + x + bit - bv)] = colorRGB;
								}
								lastBv = bv;
							}
						}
						else
						{
							PixelDistanceDelta[] distancesRight = precomputedDistanceDeltas[outlineWidth][0];
							PixelDistanceDelta[] distancesLeft = precomputedDistanceDeltas[outlineWidth][2];
							for (int bit = 1; bit < end; bit++)
							{
								int bv = (v >>> bit) & 1;
								if (bv == 1 && lastBv == 0)
								{
									rasterDistanceDeltas(imageData, imageWidth, croppedX1 + x + bit, croppedY1 + y,
										distancesLeft, colorRGB);
								}
								else if (bv == 0 && lastBv == 1)
								{
									rasterDistanceDeltas(imageData, imageWidth, croppedX1 + x + bit - 1, croppedY1 + y,
										distancesRight, colorRGB);
								}
								lastBv = bv;
							}
						}
					}
					else
					{
						for (int bit = 1; bit < end; bit++)
						{
							int bv = (v >>> bit) & 1;
							if (bv != lastBv)
							{
								enqueueOutlinePixel(1, croppedX1 + x + bit - bv, croppedY1 + y);
							}
							lastBv = bv;
						}
					}
				}

				// Test adjacent pixels in different 32-bit segments
				if ((lastV >>> 31) != (v & 1) && x > 0)
				{
					if (directWrite)
					{
						if (outlineWidth == 1)
						{
							imageData[(croppedY1 + y) * imageWidth + (croppedX1 + x - (v & 1))] = colorRGB;
						}
						else
						{
							if ((v & 1) == 1)
							{
								PixelDistanceDelta[] distancesLeft = precomputedDistanceDeltas[outlineWidth][2];
								rasterDistanceDeltas(imageData, imageWidth, croppedX1 + x, croppedY1 + y,
									distancesLeft, colorRGB);
							}
							else
							{
								PixelDistanceDelta[] distancesRight = precomputedDistanceDeltas[outlineWidth][0];
								rasterDistanceDeltas(imageData, imageWidth, croppedX1 + x - 1, croppedY1 + y,
									distancesRight, colorRGB);
							}
						}
					}
					else
					{
						enqueueOutlinePixel(1, croppedX1 + x - (v & 1), croppedY1 + y);
					}
				}

				lastV = v;
			}
		}
	}

	/**
	 * Process the outline queue and draw an outline of the pixels
	 * in the queue to the client image buffer.
	 *
	 * @param outlineWidth The width of the outline.
	 * @param color The color of the outline.
	 */
	private void processOutlinePixelQueue(int outlineWidth, Color color, int feather)
	{
		MainBufferProvider bufferProvider = (MainBufferProvider) client.getBufferProvider();
		BufferedImage image = (BufferedImage) bufferProvider.getImage();
		int imageWidth = image.getWidth();
		int[] imageData = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		PixelDistanceGroupIndex[] ps = getPriorityList(outlineWidth, feather);

		for (PixelDistanceGroupIndex p : ps)
		{
			final int[] blockMemory = outlinePixelsBlockBuffer.getMemory();

			final int colorARGB;
			final int inverseAlpha;
			{
				int alpha = (int) Math.round(color.getAlpha() * p.alphaMultiply);
				inverseAlpha = 256 - alpha;
				colorARGB = (alpha << 24)
					| ((color.getRed() * alpha) / 255) << 16
					| ((color.getGreen() * alpha) / 255) << 8
					| ((color.getBlue() * alpha) / 255);
			}

			final int groupIndex = p.distanceGroupIndex;
			final int nextGroupIndexY = groupIndex + outlineArrayWidth;
			final int nextGroupIndexX = groupIndex + 1;

			while (outlinePixelsBlockIndicesLengths[groupIndex] > 0)
			{
				final int block = outlinePixelsBlockIndices[groupIndex][outlinePixelsBlockIndicesLengths[groupIndex] - 1];
				final int blockStart = block << OutlinePixelBuffer.BLOCK_BITS;
				final int blockEnd = blockStart + outlinePixelsLastBlockLength[groupIndex];
				for (int i = blockStart; i < blockEnd; i++)
				{
					int x = blockMemory[i] & 0xFFFF;
					int y = blockMemory[i] >>> 16;
					int visitedPixelPos = (y - croppedY1) * croppedWidth + (x - croppedX1);
					if ((visited[visitedPixelPos >> 5] & (1 << (visitedPixelPos & 31))) != 0)
					{
						continue;
					}
					visited[visitedPixelPos >> 5] |= 1 << (visitedPixelPos & 31);

					int pixelPos = y * imageWidth + x;
					int dst = imageData[pixelPos];
					imageData[pixelPos] = blendOutlinePixel(colorARGB, dst, inverseAlpha);

					if (x - 1 >= clipX1)
					{
						enqueueOutlinePixel(nextGroupIndexX, x - 1, y);
					}
					if (x + 1 < clipX2)
					{
						enqueueOutlinePixel(nextGroupIndexX, x + 1, y);
					}
					if (y - 1 >= clipY1)
					{
						enqueueOutlinePixel(nextGroupIndexY, x, y - 1);
					}
					if (y + 1 < clipY2)
					{
						enqueueOutlinePixel(nextGroupIndexY, x, y + 1);
					}
				}

				outlinePixelsBlockBuffer.freeBlock(block);
				outlinePixelsBlockIndicesLengths[groupIndex]--;
				outlinePixelsLastBlockLength[groupIndex] = OutlinePixelBuffer.BLOCK_SIZE;
			}
		}
	}

	/**
	 * Source-over for the premultiplied UI buffer, with upstream RGB rounding.
	 * Carry destination alpha separately: multiplying a packed ARGB int loses
	 * its high byte and makes existing overlays appear brighter at intersections.
	 * Software rendering ignores the alpha byte; its RGB output is unchanged.
	 */
	static int blendOutlinePixel(int source, int destination, int inverseAlpha)
	{
		int alpha = (source >>> 24) + (((destination >>> 24) * inverseAlpha) >>> 8);
		return (alpha << 24)
			| ((source & 0x0000FF00) + (((destination & 0x0000FF00) * inverseAlpha) >>> 8) & 0x0000FF00)
			| ((source & 0x00FF00FF) + (((destination & 0x00FF00FF) * inverseAlpha) >>> 8) & 0x00FF00FF);
	}

	/**
	 * Projects and rasterizes the model once, padded for the widest enabled layer.
	 *
	 * @param localX The local x position of the model
	 * @param localY The local y position of the model
	 * @param localZ The local z position of the model
	 * @param orientation The orientation of the model
	 * @param outlineWidth The width of the outline
	 */
	private boolean prepareModelOutline(WorldView wv, Model model,
		int localX, int localY, int localZ, int orientation,
		int outlineWidth)
	{
		croppedX1 = Integer.MAX_VALUE;
		croppedX2 = Integer.MIN_VALUE;
		croppedY1 = Integer.MAX_VALUE;
		croppedY2 = Integer.MIN_VALUE;

		clipX1 = client.getViewportXOffset();
		clipY1 = client.getViewportYOffset();
		clipX2 = client.getViewportWidth() + clipX1;
		clipY2 = client.getViewportHeight() + clipY1;

		if (!projectVertices(wv, model, localX, localY, localZ, orientation))
		{
			// No vertex of the model is visible on the screen, so we can
			// assume there are no parts of the model to outline.
			return false;
		}

		croppedX1 = Math.max(croppedX1 - outlineWidth, clipX1);
		croppedX2 = Math.min(croppedX2 + outlineWidth, clipX2);
		croppedX2 += ~(croppedX2 - croppedX1 - 1) & 31; // Increases width to next multiple of 32 so bitset segments align
		croppedY1 = Math.max(croppedY1 - outlineWidth, clipY1);
		croppedY2 = Math.min(croppedY2 + outlineWidth, clipY2);
		croppedWidth = croppedX2 - croppedX1;
		croppedHeight = croppedY2 - croppedY1;

		resetVisited(croppedWidth * croppedHeight);

		simulateModelRasterizationForOutline(model);
		return true;
	}

	private static boolean canDirectWrite(int outlineWidth, Color color, int feather)
	{
		// We can improve performance and reduce memory needed when drawing
		// only a small outline around the model by skipping the pixel queueing
		// and instead raster pixels directly. This only looks right for opaque
		// outlines since some pixels of the outline can get drawn more than once.
		// Performance becomes worse than queueing when using larger outline widths,
		// usually around 10 px outline width according to some basic testing.
		return color.getAlpha() == 255 && outlineWidth <= DIRECT_WRITE_OUTLINE_WIDTH_THRESHOLD &&
			(feather == 0 || outlineWidth == 1); // Feather has no effect on outlineWidth == 1
	}

	private void drawPreparedOutline(int outlineWidth, Color color, int feather)
	{
		boolean directWrite = canDirectWrite(outlineWidth, color, feather);
		if (directWrite)
		{
			ensureDistanceDeltasCreated(outlineWidth);
		}
		else
		{
			outlineArrayWidth = outlineWidth + 2;
			initializeOutlineBuffers();
		}

		try
		{
			processInitialOutlinePixels(directWrite, color, outlineWidth);

			if (!directWrite)
			{
				processOutlinePixelQueue(outlineWidth, color, feather);
			}
		}
		finally
		{
			freeAllBlockMemory();
		}
	}

	public void drawOutline(Actor actor, int outlineWidth, Color color, int feather)
	{
		drawOutlineLayers(actor, color, outlineWidth, feather, 0, 0);
	}

	/**
	 * Draws glow then primary line with one model request, projection and silhouette.
	 * A nonpositive width disables that layer. Only buffer capacity, not prepared
	 * model/silhouette contents, is reused across actor draws.
	 */
	void drawOutlineLayers(Actor actor, Color color,
		int glowWidth, int glowFeather, int outlineWidth, int outlineFeather)
	{
		glowWidth = Math.max(0, Math.min(glowWidth, MAX_OUTLINE_WIDTH));
		outlineWidth = Math.max(0, Math.min(outlineWidth, MAX_OUTLINE_WIDTH));
		if ((glowWidth == 0 && outlineWidth == 0) || color.getAlpha() == 0)
		{
			return;
		}
		glowFeather = Math.max(0, Math.min(glowFeather, MAX_FEATHER));
		outlineFeather = Math.max(0, Math.min(outlineFeather, MAX_FEATHER));

		LocalPoint lp = actor.getLocalLocation();
		if (lp == null)
		{
			return;
		}
		Model model = actor.getModel();
		if (model == null)
		{
			return;
		}
		WorldView wv = actor.getWorldView();
		if (!prepareModelOutline(wv, model, lp.getX(), lp.getY(),
			Perspective.getFootprintTileHeight(client, lp, wv.getPlane(), actor.getFootprintSize()) - actor.getAnimationHeightOffset(),
			actor.getCurrentOrientation(), Math.max(glowWidth, outlineWidth)))
		{
			return;
		}

		// Queued outlines mark their pixels visited. Keep a pristine bitset for the
		// second layer, then swap buffers instead of copying it back. Opaque direct
		// writes do not mutate the bitset and need no copy; single layers also skip it.
		boolean preserveSilhouette = glowWidth > 0 && outlineWidth > 0 &&
			!canDirectWrite(glowWidth, color, glowFeather);
		if (preserveSilhouette)
		{
			int size = croppedWidth * croppedHeight >>> 5;
			if (silhouette.length < size)
			{
				silhouette = new int[nextPowerOfTwo(size)];
			}
			System.arraycopy(visited, 0, silhouette, 0, size);
		}

		if (glowWidth > 0)
		{
			drawPreparedOutline(glowWidth, color, glowFeather);
		}
		if (outlineWidth > 0)
		{
			if (preserveSilhouette)
			{
				int[] used = visited;
				visited = silhouette;
				silhouette = used;
			}
			drawPreparedOutline(outlineWidth, color, outlineFeather);
		}
	}

}
