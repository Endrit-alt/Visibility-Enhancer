package com.visibilityenhancer;

import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Draws replacement chat, prayer icons, health bars, and hitsplats with character UI. */
public class VisibilityEnhancerOverheadOverlay extends Overlay
{
	private final VisibilityEnhancerOverlay overlay;

	@Inject
	VisibilityEnhancerOverheadOverlay(VisibilityEnhancerOverlay overlay)
	{
		this.overlay = overlay;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.UNDER_WIDGETS);
		setPriority(PRIORITY_HIGH);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		return overlay.renderOverheads(graphics);
	}
}
