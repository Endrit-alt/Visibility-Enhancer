package com.visibilityenhancer;

import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Draws selected model outlines and their glow synchronously after the tile pass. */
public class VisibilityEnhancerOutlineOverlay extends Overlay
{
	private final VisibilityEnhancerOverlay overlay;

	@Inject
	VisibilityEnhancerOutlineOverlay(VisibilityEnhancerOverlay overlay)
	{
		this.overlay = overlay;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(PRIORITY_HIGH);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		return overlay.renderOutlines(graphics);
	}
}
