package com.visibilityenhancer;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;
import javax.inject.Inject;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.HeadIcon;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.SpriteID;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.util.Text;

public class VisibilityEnhancerOverlay extends Overlay
{
	private final Client client;
	private final VisibilityEnhancer plugin;
	private final VisibilityEnhancerConfig config;
	private final ModelOutlineRenderer modelOutlineRenderer;
	private final SpriteManager spriteManager;

	private final Set<WorldPoint> renderedTiles = new HashSet<>();
	private final List<Player> sortedGhosts = new ArrayList<>(32);

	private static final int MESSAGE_DISPLAY_DURATION_MS = 4000;
	private static final int MESSAGE_COOLDOWN_MS = 10000;
	private static final int FAST_TYPING_COOLDOWN_MS = 1000;

	private static final String[] WOO_MESSAGES = {
			"Wooo wooo wooooo",
			"Woo woo?",
			"Woooooooo.",
			"Wooo...",
			"Woo!",
			"Woooooooooo!",
			"Woo.",
			"Woooo...",
			"Wooo ooo ooo...",
			"Woo... woo...",
			"W-woo?",
			"Woooo woo.",
			"Wooooooooooooo!",
			"Woo wooo!",
			"Wooo?"
	};

	private static class StackFadeState
	{
		int firstSeenCycle;
		Integer unstackCycle = null;
		float fadeMultiplierAtUnstack = 0f;
		int lastCount = 0;
	}

	private final Map<WorldPoint, StackFadeState> stackFadeStates = new HashMap<>();

	private static class SpamTracker
	{
		String originalText;
		String wooText;
		Instant firstSeen;
		Instant lastSpoke;
	}

	private final Map<Player, SpamTracker> spamTrackerMap = new WeakHashMap<>();

	@Inject
	private VisibilityEnhancerOverlay(
			Client client,
			VisibilityEnhancer plugin,
			VisibilityEnhancerConfig config,
			ModelOutlineRenderer modelOutlineRenderer,
			SpriteManager spriteManager)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.modelOutlineRenderer = modelOutlineRenderer;
		this.spriteManager = spriteManager;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(OverlayPriority.HIGH);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!plugin.isActive())
		{
			return null;
		}

		Player local = client.getLocalPlayer();
		WorldPoint localPoint = local != null ? local.getWorldLocation() : null;
		LocalPoint localLocalPoint = local != null ? local.getLocalLocation() : null;

		// Thralls outlines
		HighlightStyle thrallStyle = config.highlightThralls();
		if (thrallStyle != HighlightStyle.NONE && !plugin.isPeekHeld())
		{
			Color thrallsColor = config.thrallsOutlineColor();

			for (NPC npc : client.getNpcs())
			{
				if (npc != null && VisibilityEnhancer.THRALL_IDS.contains(npc.getId()))
				{
					if (thrallStyle == HighlightStyle.TILE
							|| thrallStyle == HighlightStyle.TRUE_TILE
							|| thrallStyle == HighlightStyle.BOTH
							|| thrallStyle == HighlightStyle.BOTH_TRUE)
					{
						renderFloorTile(graphics, npc, thrallsColor, thrallStyle);
					}

					if (thrallStyle == HighlightStyle.OUTLINE
							|| thrallStyle == HighlightStyle.BOTH
							|| thrallStyle == HighlightStyle.BOTH_TRUE)
					{
						renderOutlineLayers(npc, thrallsColor);
					}
				}
			}
		}

		// Self outlines
		HighlightStyle selfStyle = config.highlightSelf();
		if (local != null && selfStyle != HighlightStyle.NONE)
		{
			Model localModel = local.getModel();
			if (localModel == null || localModel.getOverrideAmount() == 0)
			{
				if (selfStyle == HighlightStyle.TILE
						|| selfStyle == HighlightStyle.TRUE_TILE
						|| selfStyle == HighlightStyle.BOTH
						|| selfStyle == HighlightStyle.BOTH_TRUE)
				{
					renderFloorTile(graphics, local, config.selfOutlineColor(), selfStyle);
				}

				if (selfStyle == HighlightStyle.OUTLINE
						|| selfStyle == HighlightStyle.BOTH
						|| selfStyle == HighlightStyle.BOTH_TRUE)
				{
					renderOutlineLayers(local, config.selfOutlineColor());
				}
			}
		}

		// Others outlines
		HighlightStyle othersStyle = config.highlightOthers();
		if (othersStyle != HighlightStyle.NONE && !plugin.isPeekHeld())
		{
			renderedTiles.clear();
			boolean hideStacked = config.hideStackedOutlines();
			Color othersColor = config.othersOutlineColor();

			sortedGhosts.clear();
			sortedGhosts.addAll(plugin.getGhostedPlayers());

			if (localLocalPoint != null)
			{
				sortedGhosts.sort((p1, p2) ->
				{
					LocalPoint lp1 = p1.getLocalLocation();
					LocalPoint lp2 = p2.getLocalLocation();
					if (lp1 == null || lp2 == null)
					{
						return 0;
					}

					return Integer.compare(lp2.distanceTo(localLocalPoint), lp1.distanceTo(localLocalPoint));
				});
			}

			for (Player player : sortedGhosts)
			{
				WorldPoint playerPoint = player.getWorldLocation();
				if (playerPoint == null)
				{
					continue;
				}

				Model pModel = player.getModel();
				if (pModel != null && pModel.getOverrideAmount() != 0)
				{
					continue;
				}

				if (hideStacked)
				{
					if (localPoint != null && playerPoint.equals(localPoint))
					{
						continue;
					}
					if (renderedTiles.contains(playerPoint))
					{
						continue;
					}
					renderedTiles.add(playerPoint);
				}

				if (othersStyle == HighlightStyle.TILE
						|| othersStyle == HighlightStyle.TRUE_TILE
						|| othersStyle == HighlightStyle.BOTH
						|| othersStyle == HighlightStyle.BOTH_TRUE)
				{
					renderFloorTile(graphics, player, othersColor, othersStyle);
				}

				if (othersStyle == HighlightStyle.OUTLINE
						|| othersStyle == HighlightStyle.BOTH
						|| othersStyle == HighlightStyle.BOTH_TRUE)
				{
					renderOutlineLayers(player, othersColor);
				}
			}
		}

		// Stack warnings should render independently of highlightOthers
		renderStackWarnings(graphics);

		boolean othersCustomPrayers = config.othersTransparentPrayers() && !plugin.isPeekHeld();

		if (othersCustomPrayers)
		{
			Set<WorldPoint> renderedPrayerTiles = new HashSet<>();
			List<Rectangle> renderedTextBounds = new ArrayList<>();

			for (Player p : client.getPlayers())
			{
				if (p != null && !plugin.getGhostedPlayers().contains(p))
				{
					String text = p.getOverheadText();
					if (text != null && !text.isEmpty())
					{
						int zOffset = 20;
						Point textPoint = p.getCanvasTextLocation(graphics, text, p.getLogicalHeight() + zOffset);
						if (textPoint != null)
						{
							graphics.setFont(FontManager.getRunescapeBoldFont());
							FontMetrics fontMetrics = graphics.getFontMetrics();

							String cleanText = Text.removeTags(text);
							int textWidth = fontMetrics.stringWidth(cleanText);
							int textHeight = fontMetrics.getHeight();
							int drawX = textPoint.getX() - 1;
							int drawY = textPoint.getY() + 6;

							renderedTextBounds.add(new Rectangle(drawX, drawY - textHeight, textWidth, textHeight));
						}
					}
				}
			}

			for (Player player : plugin.getGhostedPlayers())
			{
				drawOverheadText(graphics, player, renderedTextBounds);

				WorldPoint playerPoint = player.getWorldLocation();

				if (playerPoint != null)
				{
					if (localPoint != null && playerPoint.equals(localPoint))
					{
						continue;
					}

					if (renderedPrayerTiles.contains(playerPoint))
					{
						continue;
					}

					renderedPrayerTiles.add(playerPoint);
				}

				drawTransparentPrayer(graphics, player, config.prayersOpacity());

				int ratio = player.getHealthRatio();
				int scale = player.getHealthScale();
				if (ratio > -1 && scale > 0)
				{
					drawTransparentHpBar(graphics, player, ratio, scale, config.hpBarOpacity());
				}

				List<VisibilityEnhancer.CustomHitsplat> hitsplats = plugin.getCustomHitsplats().get(player);
				if (hitsplats != null && !hitsplats.isEmpty())
				{
					drawTransparentHitsplats(graphics, player, hitsplats, config.hitsplatsOpacity());
				}
			}
		}

		return null;
	}

	private void renderOutlineLayers(Player player, Color color)
	{
		if (config.enableGlow())
		{
			modelOutlineRenderer.drawOutline(player, config.glowWidth(), color, config.glowFeather());
		}
		if (config.enableOutline())
		{
			modelOutlineRenderer.drawOutline(player, config.outlineWidth(), color, config.outlineFeather());
		}
	}

	private void renderOutlineLayers(NPC npc, Color color)
	{
		if (config.enableGlow())
		{
			modelOutlineRenderer.drawOutline(npc, config.glowWidth(), color, config.glowFeather());
		}
		if (config.enableOutline())
		{
			modelOutlineRenderer.drawOutline(npc, config.outlineWidth(), color, config.outlineFeather());
		}
	}

	private void renderFloorTile(Graphics2D graphics, Actor actor, Color color, HighlightStyle style)
	{
		LocalPoint lp = actor.getLocalLocation();

		if (style == HighlightStyle.TRUE_TILE || style == HighlightStyle.BOTH_TRUE)
		{
			WorldPoint wp = actor.getWorldLocation();
			if (wp != null)
			{
				lp = LocalPoint.fromWorld(client, wp);
			}
		}

		if (lp == null)
		{
			return;
		}

		Polygon poly = Perspective.getCanvasTilePoly(client, lp);
		if (poly != null)
		{
			Stroke primaryStroke;
			if (config.borderDashed())
			{
				primaryStroke = new BasicStroke(
						config.outlineWidth(),
						BasicStroke.CAP_BUTT,
						BasicStroke.JOIN_MITER,
						10.0f,
						new float[]{10.0f, 10.0f},
						0.0f
				);
			}
			else
			{
				primaryStroke = new BasicStroke(config.outlineWidth());
			}

			if (config.enableGlow())
			{
				Stroke glowStroke = new BasicStroke(config.outlineWidth() + config.glowWidth());
				Color glowColor = new Color(
						color.getRed(),
						color.getGreen(),
						color.getBlue(),
						Math.max(0, color.getAlpha() - 100)
				);
				graphics.setColor(glowColor);
				graphics.setStroke(glowStroke);
				graphics.draw(poly);
			}

			if (config.enableOutline())
			{
				graphics.setColor(color);
				graphics.setStroke(primaryStroke);
				graphics.draw(poly);
			}

			if (config.fillFloorTile())
			{
				graphics.setColor(config.tileFillColor());
				graphics.fill(poly);
			}
		}
	}

	private void drawTransparentPrayer(Graphics2D graphics, Player player, int opacityPercent)
	{
		HeadIcon icon = player.getOverheadIcon();
		if (icon == null)
		{
			return;
		}

		int spriteId = getSpriteId(icon);
		if (spriteId == -1)
		{
			return;
		}

		BufferedImage prayerImage = spriteManager.getSprite(spriteId, 0);
		if (prayerImage == null)
		{
			return;
		}

		int zOffset = 20;
		Point point = player.getCanvasImageLocation(prayerImage, player.getLogicalHeight() + zOffset);
		if (point == null)
		{
			return;
		}

		int drawX = point.getX();
		int drawY = point.getY() - 25;

		float alpha = opacityPercent / 100f;
		Composite originalComposite = graphics.getComposite();
		graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		graphics.drawImage(prayerImage, drawX, drawY, null);
		graphics.setComposite(originalComposite);
	}

	private void drawTransparentHpBar(Graphics2D graphics, Player player, int ratio, int scale, int opacityPercent)
	{
		int alpha = (int) ((opacityPercent / 100f) * 255);
		if (alpha <= 0)
		{
			return;
		}

		Point point = player.getCanvasTextLocation(graphics, "", player.getLogicalHeight() + 15);
		if (point == null)
		{
			return;
		}

		int width = 30;
		int height = 5;
		int fill = (int) (((float) ratio / scale) * width);
		int x = point.getX() - (width / 2);
		int y = point.getY() - 2;

		graphics.setColor(new Color(255, 0, 0, alpha));
		graphics.fillRect(x, y, width, height);

		graphics.setColor(new Color(0, 255, 0, alpha));
		graphics.fillRect(x, y, fill, height);
	}

	private void drawTransparentHitsplats(Graphics2D graphics, Player player, List<VisibilityEnhancer.CustomHitsplat> hitsplats, int opacityPercent)
	{
		int alpha = (int) ((opacityPercent / 100f) * 255);
		if (alpha <= 0)
		{
			return;
		}

		int bgAlpha = config.hideHitsplatBackground() ? 0 : (int) (alpha * 0.8f);

		LocalPoint lp = player.getLocalLocation();
		if (lp == null)
		{
			return;
		}

		Point basePoint = Perspective.localToCanvas(client, lp, client.getPlane(), player.getLogicalHeight() / 2);
		if (basePoint == null)
		{
			return;
		}

		graphics.setFont(FontManager.getRunescapeBoldFont().deriveFont(13f));
		FontMetrics fm = graphics.getFontMetrics();

		int boxTextHeight = fm.getAscent() - 1;
		int paddingX = 1;
		int paddingY = 1;
		int boxHeight = boxTextHeight + (paddingY * 2);
		int ySpacing = boxHeight + 2;
		int size = hitsplats.size();

		int shiftDown = 0;
		Point hpPoint = player.getCanvasTextLocation(graphics, "", player.getLogicalHeight() + 15);
		if (hpPoint != null)
		{
			int hpBarBottom = (hpPoint.getY() - 2) + 5;
			int ceilingY = hpBarBottom + 2;

			int highestOffsetY = 0;
			if (size == 2)
			{
				highestOffsetY = -(ySpacing / 2);
			}
			else if (size == 4)
			{
				highestOffsetY = -ySpacing;
			}
			else if (size > 1)
			{
				int totalRows = (size + 1) / 2;
				highestOffsetY = -((totalRows - 1) * ySpacing / 2);
			}

			int highestBoxY = basePoint.getY() + highestOffsetY - (boxHeight / 2) - 10;
			if (highestBoxY < ceilingY)
			{
				shiftDown = ceilingY - highestBoxY;
			}
		}

		int maxTextWidth = 0;
		for (VisibilityEnhancer.CustomHitsplat hit : hitsplats)
		{
			int w = fm.stringWidth(String.valueOf(hit.getAmount()));
			if (w > maxTextWidth)
			{
				maxTextWidth = w;
			}
		}

		int maxBoxWidth = maxTextWidth + 1 + (paddingX * 2);
		int xSpacing = maxBoxWidth + 2;

		for (int i = 0; i < size; i++)
		{
			VisibilityEnhancer.CustomHitsplat hit = hitsplats.get(i);
			String text = String.valueOf(hit.getAmount());
			int textWidth = fm.stringWidth(text);
			int boxWidth = textWidth + 1 + (paddingX * 2);

			int offsetX = 0;
			int offsetY = 0;

			if (size == 1)
			{
				offsetX = 0;
				offsetY = 0;
			}
			else if (size == 2)
			{
				offsetX = 0;
				offsetY = (i == 0) ? -(ySpacing / 2) : (ySpacing / 2);
			}
			else if (size == 4)
			{
				if (i == 0)
				{
					offsetX = 0;
					offsetY = -ySpacing;
				}
				else if (i == 1)
				{
					offsetX = -(xSpacing / 2);
					offsetY = 0;
				}
				else if (i == 2)
				{
					offsetX = (xSpacing / 2);
					offsetY = 0;
				}
				else if (i == 3)
				{
					offsetX = 0;
					offsetY = ySpacing;
				}
			}
			else
			{
				int row = i / 2;
				int col = i % 2;
				int totalRows = (size + 1) / 2;

				if (row == totalRows - 1 && size % 2 != 0)
				{
					offsetX = 0;
				}
				else
				{
					offsetX = (col == 0) ? -(xSpacing / 2) : (xSpacing / 2);
				}

				offsetY = (row * ySpacing) - ((totalRows - 1) * ySpacing / 2);
			}

			int boxX = basePoint.getX() + offsetX - (boxWidth / 2);
			int boxY = basePoint.getY() + offsetY - (boxHeight / 2) - 10 + shiftDown;

			if (bgAlpha > 0)
			{
				Color backColor = hit.getAmount() == 0
						? new Color(50, 90, 160, bgAlpha)
						: new Color(180, 40, 40, bgAlpha);
				graphics.setColor(backColor);
				graphics.fillRoundRect(boxX, boxY, boxWidth, boxHeight, 2, 2);
			}

			int textDrawX = boxX + paddingX;
			int textDrawY = boxY + boxTextHeight + paddingY + 1;

			Color textShadowColor = new Color(0, 0, 0, alpha);
			Color textColor = new Color(255, 255, 255, alpha);

			graphics.setColor(textShadowColor);
			graphics.drawString(text, textDrawX + 1, textDrawY + 1);

			graphics.setColor(textColor);
			graphics.drawString(text, textDrawX, textDrawY);
		}
	}

	private void drawOverheadText(Graphics2D graphics, Player player, List<Rectangle> renderedTextBounds)
	{
		String text = player.getOverheadText();
		if (text == null || text.isEmpty())
		{
			return;
		}

		// Convert engine-escaped brackets back to normal symbols
		text = text.replace("<lt>", "<").replace("<gt>", ">");

		SpamTracker tracker = spamTrackerMap.computeIfAbsent(player, p -> new SpamTracker());
		Instant now = Instant.now();

		if (tracker.originalText != null && tracker.originalText.equals(text))
		{
			long elapsedSinceFirst = Duration.between(tracker.firstSeen, now).toMillis();

			if (elapsedSinceFirst > MESSAGE_DISPLAY_DURATION_MS && elapsedSinceFirst < MESSAGE_COOLDOWN_MS)
			{
				return;
			}
			else if (elapsedSinceFirst >= MESSAGE_COOLDOWN_MS)
			{
				tracker.firstSeen = now;
				tracker.lastSpoke = now;
				tracker.wooText = WOO_MESSAGES[ThreadLocalRandom.current().nextInt(WOO_MESSAGES.length)];
			}
		}
		else
		{
			if (tracker.lastSpoke != null
					&& Duration.between(tracker.lastSpoke, now).toMillis() < FAST_TYPING_COOLDOWN_MS)
			{
				return;
			}

			tracker.originalText = text;
			tracker.wooText = WOO_MESSAGES[ThreadLocalRandom.current().nextInt(WOO_MESSAGES.length)];
			tracker.firstSeen = now;
			tracker.lastSpoke = now;
		}

		String displayText = config.funGhostChat() ? tracker.wooText : text;

		int zOffset = 20;
		Point textPoint = player.getCanvasTextLocation(graphics, displayText, player.getLogicalHeight() + zOffset);
		if (textPoint == null)
		{
			return;
		}

		graphics.setFont(FontManager.getRunescapeBoldFont());
		FontMetrics fontMetrics = graphics.getFontMetrics();

		String cleanText = Text.removeTags(displayText);
		int textWidth = fontMetrics.stringWidth(cleanText);
		int textHeight = fontMetrics.getHeight();

		int drawX = textPoint.getX() - 1;
		int drawY = textPoint.getY() + 6;

		Rectangle currentBounds = new Rectangle(drawX, drawY - textHeight, textWidth, textHeight);

		boolean isOverlapping = true;
		while (isOverlapping)
		{
			isOverlapping = false;
			for (Rectangle drawnBounds : renderedTextBounds)
			{
				if (currentBounds.intersects(drawnBounds))
				{
					drawY -= (textHeight + 2);
					currentBounds.setLocation(drawX, drawY - textHeight);
					isOverlapping = true;
					break;
				}
			}
		}

		renderedTextBounds.add(currentBounds);

		Point adjustedPoint = new Point(drawX, drawY);
		OverlayUtil.renderTextLocation(graphics, adjustedPoint, displayText, Color.YELLOW);
	}

	private int getSpriteId(HeadIcon icon)
	{
		switch (icon)
		{
			case MELEE:
				return SpriteID.PRAYER_PROTECT_FROM_MELEE;
			case RANGED:
				return SpriteID.PRAYER_PROTECT_FROM_MISSILES;
			case MAGIC:
				return SpriteID.PRAYER_PROTECT_FROM_MAGIC;
			case RETRIBUTION:
				return SpriteID.PRAYER_RETRIBUTION;
			case SMITE:
				return SpriteID.PRAYER_SMITE;
			case REDEMPTION:
				return SpriteID.PRAYER_REDEMPTION;
			default:
				return -1;
		}
	}

	private void renderStackWarnings(Graphics2D graphics)
	{
		if (!config.enableStackWarnings())
		{
			stackFadeStates.clear(); // Clean up instantly if toggled off in settings
			return;
		}

		Player local = client.getLocalPlayer();
		if (local == null)
		{
			stackFadeStates.clear();
			return;
		}

		WorldPoint localPoint = local.getWorldLocation();
		Map<WorldPoint, Integer> tileCounts = new HashMap<>();

		for (Player p : client.getPlayers())
		{
			if (p == null)
			{
				continue;
			}

			WorldPoint wp = p.getWorldLocation();
			if (wp != null)
			{
				tileCounts.put(wp, tileCounts.getOrDefault(wp, 0) + 1);
			}
		}

		boolean inCombat = local.getInteracting() != null || local.getHealthRatio() > -1;
		boolean combatCheckPassed = !config.stackWarningOnlyInCombat() || inCombat;

		Set<WorldPoint> currentlyStacked = new HashSet<>();

		// Only populate currentlyStacked if we pass the combat check.
		// If we fail the check, it acts as if everything unstacked, triggering a smooth fade out.
		if (combatCheckPassed)
		{
			for (Map.Entry<WorldPoint, Integer> entry : tileCounts.entrySet())
			{
				WorldPoint wp = entry.getKey();
				int count = entry.getValue();

				if (config.stackWarningOnlySelf() && (localPoint == null || !localPoint.equals(wp)))
				{
					continue;
				}

				if (count >= config.stackThreshold())
				{
					currentlyStacked.add(wp);
				}
			}
		}

		int currentCycle = client.getGameCycle();
		int delayCycles = config.stackWarningDelay() * 30;

		List<WorldPoint> toRemove = new ArrayList<>();

		// 1. Update existing fade states
		for (Map.Entry<WorldPoint, StackFadeState> entry : stackFadeStates.entrySet())
		{
			WorldPoint wp = entry.getKey();
			StackFadeState state = entry.getValue();
			boolean isStacked = currentlyStacked.contains(wp);

			if (isStacked)
			{
				if (state.unstackCycle != null)
				{
					// If it was fading out but stacked again, resume fading in smoothly
					int equivalentElapsed = (int) (state.fadeMultiplierAtUnstack * 30.0f) + delayCycles;
					state.firstSeenCycle = currentCycle - equivalentElapsed;
					state.unstackCycle = null;
				}

				int elapsed = currentCycle - state.firstSeenCycle;
				if (elapsed >= delayCycles)
				{
					state.fadeMultiplierAtUnstack = Math.min(1.0f, (elapsed - delayCycles) / 30.0f);
				}
				else
				{
					state.fadeMultiplierAtUnstack = 0f;
				}
				state.lastCount = tileCounts.get(wp); // Keep the number accurate while stacked
			}
			else
			{
				if (state.unstackCycle == null)
				{
					state.unstackCycle = currentCycle; // Lock in the moment it stopped stacking
				}

				float fadeOutAmount = (currentCycle - state.unstackCycle) / 30.0f;
				float currentFade = state.fadeMultiplierAtUnstack - fadeOutAmount;

				if (currentFade <= 0f)
				{
					toRemove.add(wp);
				}
			}
		}

		// 2. Add brand new stacked tiles to the tracker
		for (WorldPoint wp : currentlyStacked)
		{
			if (!stackFadeStates.containsKey(wp))
			{
				StackFadeState newState = new StackFadeState();
				newState.firstSeenCycle = currentCycle;
				newState.lastCount = tileCounts.get(wp);
				stackFadeStates.put(wp, newState);
			}
		}

		for (WorldPoint wp : toRemove)
		{
			stackFadeStates.remove(wp);
		}

		// 3. Render all tracked states
		for (Map.Entry<WorldPoint, StackFadeState> entry : stackFadeStates.entrySet())
		{
			WorldPoint wp = entry.getKey();
			StackFadeState state = entry.getValue();

			float fadeMultiplier = 0f;
			if (state.unstackCycle != null)
			{
				fadeMultiplier = state.fadeMultiplierAtUnstack - ((currentCycle - state.unstackCycle) / 30.0f);
			}
			else
			{
				int elapsed = currentCycle - state.firstSeenCycle;
				if (elapsed >= delayCycles)
				{
					fadeMultiplier = (elapsed - delayCycles) / 30.0f;
				}
			}
			fadeMultiplier = Math.max(0f, Math.min(1.0f, fadeMultiplier));

			if (fadeMultiplier <= 0f)
			{
				continue; // Don't draw if completely invisible
			}

			LocalPoint lp = LocalPoint.fromWorld(client, wp);
			if (lp == null)
			{
				continue;
			}

			Color baseColor = config.stackWarningColor();
			double pulseRange = 0.6;
			double sine = Math.sin(currentCycle * 0.15);
			int basePulseAlpha = (int) (baseColor.getAlpha() * (1.0 - (pulseRange * (sine + 1.0) / 2.0)));
			basePulseAlpha = Math.max(0, Math.min(255, basePulseAlpha));

			int pulseAlpha = (int) (basePulseAlpha * fadeMultiplier);
			Color pulseColor = new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), pulseAlpha);

			Polygon poly = Perspective.getCanvasTilePoly(client, lp);
			if (poly != null)
			{
				int pillarHeight = config.stackWarningPillarHeight();
				Point[] basePoints = null;
				Point[] topPoints = null;
				boolean projectionFailed = false;

				java.awt.geom.Area clipArea = new java.awt.geom.Area(poly);

				if (pillarHeight > 0)
				{
					int size = Perspective.LOCAL_TILE_SIZE / 2;
					int[][] corners = {{-size, -size}, {size, -size}, {size, size}, {-size, size}};
					basePoints = new Point[4];
					topPoints = new Point[4];

					for (int i = 0; i < 4; i++)
					{
						LocalPoint cornerLocal = new LocalPoint(lp.getX() + corners[i][0], lp.getY() + corners[i][1]);
						basePoints[i] = Perspective.localToCanvas(client, cornerLocal, client.getPlane(), 0);
						topPoints[i] = Perspective.localToCanvas(client, cornerLocal, client.getPlane(), pillarHeight);

						if (basePoints[i] == null || topPoints[i] == null)
						{
							projectionFailed = true;
							break;
						}
					}
				}

				if (pillarHeight > 0 && !projectionFailed)
				{
					Point bottomCenter = Perspective.localToCanvas(client, lp, client.getPlane(), 0);
					Point topCenter = Perspective.localToCanvas(client, lp, client.getPlane(), pillarHeight);

					if (bottomCenter != null && topCenter != null && bottomCenter.getY() != topCenter.getY())
					{
						int beamAlpha = Math.max(0, pulseAlpha / 2);
						Color bottomColor = new Color(pulseColor.getRed(), pulseColor.getGreen(), pulseColor.getBlue(), beamAlpha);
						Color topColor = new Color(pulseColor.getRed(), pulseColor.getGreen(), pulseColor.getBlue(), 0);

						java.awt.GradientPaint beamGradient = new java.awt.GradientPaint(
								(float) bottomCenter.getX(), (float) bottomCenter.getY(), bottomColor,
								(float) topCenter.getX(), (float) topCenter.getY(), topColor
						);
						graphics.setPaint(beamGradient);

						for (int i = 0; i < 4; i++)
						{
							int next = (i + 1) % 4;
							Polygon wall = new Polygon();
							wall.addPoint((int)basePoints[i].getX(), (int)basePoints[i].getY());
							wall.addPoint((int)basePoints[next].getX(), (int)basePoints[next].getY());
							wall.addPoint((int)topPoints[next].getX(), (int)topPoints[next].getY());
							wall.addPoint((int)topPoints[i].getX(), (int)topPoints[i].getY());
							graphics.fill(wall);

							clipArea.add(new java.awt.geom.Area(wall));
						}
					}
				}

				graphics.setStroke(new BasicStroke(2));
				graphics.setColor(pulseColor);
				graphics.draw(poly);

				if (poly.npoints == 4 && pulseAlpha > 0)
				{
					java.awt.Shape originalClip = graphics.getClip();
					graphics.setClip(clipArea);

					Stroke glowStroke = new BasicStroke(16, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER);
					Stroke coreStroke = new BasicStroke(4, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER);

					int glowAlpha = Math.min(255, (int)(pulseAlpha * 0.30));
					int coreAlpha = Math.min(255, (int)(pulseAlpha * 0.75));

					Color glowStart = new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), glowAlpha);
					Color coreStart = new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), coreAlpha);
					Color transparent = new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), 0);

					for (int pass = 0; pass < 2; pass++)
					{
						graphics.setStroke(pass == 0 ? glowStroke : coreStroke);
						Color startColor = pass == 0 ? glowStart : coreStart;

						for (int i = 0; i < 4; i++)
						{
							int prev = (i + 3) % 4;
							int next = (i + 1) % 4;

							int x1 = poly.xpoints[i];
							int y1 = poly.ypoints[i];

							float edgeFraction = 0.30f;

							int endXNext = (int) (x1 + (poly.xpoints[next] - x1) * edgeFraction);
							int endYNext = (int) (y1 + (poly.ypoints[next] - y1) * edgeFraction);

							int endXPrev = (int) (x1 + (poly.xpoints[prev] - x1) * edgeFraction);
							int endYPrev = (int) (y1 + (poly.ypoints[prev] - y1) * edgeFraction);

							java.awt.geom.Path2D floorCorner = new java.awt.geom.Path2D.Float();
							floorCorner.moveTo(endXPrev, endYPrev);
							floorCorner.lineTo(x1, y1);
							floorCorner.lineTo(endXNext, endYNext);

							float radius = (float) Math.max(1.0, Math.hypot(endXNext - x1, endYNext - y1));
							java.awt.RadialGradientPaint rgp = new java.awt.RadialGradientPaint(
									x1, y1, radius,
									new float[]{0f, 1f},
									new Color[]{startColor, transparent}
							);
							graphics.setPaint(rgp);
							graphics.draw(floorCorner);

							if (pillarHeight > 0 && !projectionFailed)
							{
								int bx = (int) basePoints[i].getX();
								int by = (int) basePoints[i].getY();
								int tx = (int) topPoints[i].getX();
								int ty = (int) topPoints[i].getY();

								if (bx != tx || by != ty)
								{
									graphics.setPaint(new java.awt.GradientPaint(bx, by, startColor, tx, ty, transparent));
									graphics.drawLine(bx, by, tx, ty);
								}
							}
						}
					}

					graphics.setClip(originalClip);
				}

				if (config.showStackWarningNumber())
				{
					int halfSize = Perspective.LOCAL_TILE_SIZE / 2;
					LocalPoint fixedCorner = new LocalPoint(lp.getX() - halfSize, lp.getY() - halfSize);

					Point center2D = Perspective.localToCanvas(client, lp, client.getPlane());
					Point corner2D = Perspective.localToCanvas(client, fixedCorner, client.getPlane());

					if (center2D != null && corner2D != null)
					{
						double dx = corner2D.getX() - center2D.getX();
						double dy = corner2D.getY() - center2D.getY();

						double length = Math.sqrt(dx * dx + dy * dy);
						if (length > 0)
						{
							dx /= length;
							dy /= length;
						}

						int pixelOffset = 15;
						int anchorX = (int) (corner2D.getX() + (dx * pixelOffset));
						int anchorY = (int) (corner2D.getY() + (dy * pixelOffset));

						// We use state.lastCount here so the number stays frozen as it fades out!
						String countText = String.valueOf(state.lastCount);
						graphics.setFont(FontManager.getRunescapeBoldFont());
						FontMetrics fm = graphics.getFontMetrics();

						int textWidth = fm.stringWidth(countText);
						int textHeight = fm.getAscent();
						int drawX = anchorX - (textWidth / 2);
						int drawY = anchorY + (textHeight / 2) - 2;

						int textAlpha = (int) (baseColor.getAlpha() * fadeMultiplier);

						Color textColor = new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), textAlpha);
						Color shadowColor = new Color(0, 0, 0, textAlpha);

						graphics.setColor(shadowColor);
						graphics.drawString(countText, drawX + 1, drawY + 1);

						graphics.setColor(textColor);
						graphics.drawString(countText, drawX, drawY);
					}
				}
			}
		}
	}
}