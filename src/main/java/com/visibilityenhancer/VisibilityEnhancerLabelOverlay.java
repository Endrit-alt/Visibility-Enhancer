package com.visibilityenhancer;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Stack-count labels only, including their shadows; no tiles, models or overheads. */
public class VisibilityEnhancerLabelOverlay extends Overlay
{
	private final VisibilityEnhancerOverlay overlay;

	@Inject
	VisibilityEnhancerLabelOverlay(VisibilityEnhancerOverlay overlay)
	{
		this.overlay = overlay;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(PRIORITY_HIGHEST);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		return overlay.renderLabels(graphics);
	}

	static final class StackCountLabel
	{
		private final String text;
		private final int anchorX, anchorY;
		private final Color color;

		StackCountLabel(int count, int anchorX, int anchorY, Color color)
		{
			this.text = String.valueOf(count);
			this.anchorX = anchorX;
			this.anchorY = anchorY;
			this.color = color;
		}

		void render(Graphics2D graphics)
		{
			graphics.setFont(FontManager.getRunescapeBoldFont());
			FontMetrics fm = graphics.getFontMetrics();
			int x = anchorX - fm.stringWidth(text) / 2;
			int y = anchorY + fm.getAscent() / 2 - 2;
			graphics.setColor(new Color(0, 0, 0, color.getAlpha()));
			graphics.drawString(text, x + 1, y + 1);
			graphics.setColor(color);
			graphics.drawString(text, x, y);
		}
	}
}
