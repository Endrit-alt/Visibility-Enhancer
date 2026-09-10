package com.visibilityenhancer;

import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Draws selected model outlines after scene masks, but before character UI. */
public class VisibilityEnhancerOutlineOverlay extends Overlay
{
	private final VisibilityEnhancerOverlay overlay;

	@Inject
	VisibilityEnhancerOutlineOverlay(VisibilityEnhancerOverlay overlay)
	{
		this.overlay = overlay;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		// Dynamic overlays draw low-to-high. Inspected Tile Layers versions use 0.6 / 2.
		setPriority(3f);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		return overlay.renderOutlines(graphics);
	}
}
