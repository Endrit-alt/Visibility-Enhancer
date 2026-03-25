package com.visibilityenhancer;

import java.awt.Color;
import net.runelite.client.config.*;

@ConfigGroup("visibilityenhancer")
public interface VisibilityEnhancerConfig extends Config
{
	// --- OPACITY SECTION ---
	@ConfigSection(
			name = "Opacity & Range",
			description = "Control how transparent players and projectiles appear.",
			position = 1
	)
	String opacitySection = "opacitySection";

	@Range(min = 0, max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
			keyName = "selfOpacity",
			name = "My Opacity",
			position = 1,
			section = opacitySection,
			description = "Transparency of your own character"
	)
	default int selfOpacity() { return 100; }

	@Range(min = 0, max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
			keyName = "playerOpacity",
			name = "Others Opacity",
			position = 2,
			section = opacitySection,
			description = "Transparency of nearby players"
	)
	default int playerOpacity() { return 100; }

	@Range(min = 0, max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
			keyName = "myProjectileOpacity",
			name = "My Projectile Opacity",
			position = 3,
			section = opacitySection,
			description = "Transparency of projectiles you fire"
	)
	default int myProjectileOpacity() { return 100; }

	@Range(min = 1, max = 50)
	@ConfigItem(
			keyName = "proximityRange",
			name = "Others Distance",
			position = 4,
			section = opacitySection,
			description = "Radius (in tiles) around you where other players will be affected"
	)
	default int proximityRange() { return 10; }

	@ConfigItem(
			keyName = "ignoreFriends",
			name = "Ignore Friends",
			position = 5,
			section = opacitySection,
			description = "Prevents friends from being affected/transparent"
	)
	default boolean ignoreFriends() { return false; }

	@ConfigItem(
			keyName = "limitAffectedPlayers",
			name = "Limit Max Others",
			position = 6,
			section = opacitySection,
			description = "Limits the number of players affected for performance"
	)
	default boolean limitAffectedPlayers() { return true; }

	@Range(min = 1, max = 100)
	@ConfigItem(
			keyName = "maxAffectedPlayers",
			name = "Max Others",
			position = 7,
			section = opacitySection,
			description = "The maximum number of players to apply effects to"
	)
	default int maxAffectedPlayers() { return 8; }


	// --- EXTRAS SECTION ---
	@ConfigSection(
			name = "Visibility Extras",
			description = "Ground view filters and projectile cleanup.",
			position = 10
	)
	String extrasSection = "extrasSection";

	@ConfigItem(
			keyName = "selfClearGround",
			name = "Clear Ground (Self)",
			position = 1,
			section = extrasSection,
			description = "Hides your Cape, Shield, Legs, and Boots to see ground markers better."
	)
	default boolean selfClearGround() { return false; }

	@ConfigItem(
			keyName = "othersClearGround",
			name = "Clear Ground (Others)",
			position = 2,
			section = extrasSection,
			description = "Hides Cape, Shield, Legs, and Boots on nearby affected players."
	)
	default boolean othersClearGround() { return false; }

	@ConfigItem(
			keyName = "hideOthersProjectiles",
			name = "Hide Others' Projectiles",
			position = 3,
			section = extrasSection,
			description = "Completely hides projectiles that didn't come from you"
	)
	default boolean hideOthersProjectiles() { return false; }

	@ConfigItem(
			keyName = "customTransparentPrayers",
			name = "Transparent Others Extras",
			position = 4,
			section = extrasSection,
			description = "Hides native overheads, hitsplats, and HP bars for others, replacing them with transparent versions"
	)
	default boolean othersTransparentPrayers() { return false; }

	@Range(min = 0, max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
			keyName = "prayersOpacity",
			name = "  ↳ Prayers Opacity",
			position = 5,
			section = extrasSection,
			description = "(Requires 'Transparent Others Extras' enabled)<br>Transparency of replaced overhead prayers"
	)
	default int prayersOpacity() { return 15; }

	@Range(min = 0, max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
			keyName = "hpBarOpacity",
			name = "  ↳ HP Bar Opacity",
			position = 6,
			section = extrasSection,
			description = "(Requires 'Transparent Others Extras' enabled)<br>Transparency of replaced HP bars"
	)
	default int hpBarOpacity() { return 20; }

	@Range(min = 0, max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
			keyName = "hitsplatsOpacity",
			name = "  ↳ Hitsplats Opacity",
			position = 7,
			section = extrasSection,
			description = "(Requires 'Transparent Others Extras' enabled)<br>Transparency of replaced hitsplats"
	)
	default int hitsplatsOpacity() { return 30; }

	@ConfigItem(
			keyName = "hideHitsplatBackground",
			name = "  ↳ Hide Hitsplat Bg",
			position = 8,
			section = extrasSection,
			description = "(Requires 'Transparent Others Extras' enabled)<br>Removes the hitsplat background box completely, showing only the numbers"
	)
	default boolean hideHitsplatBackground() { return false; }

	@ConfigItem(
			keyName = "hideThralls",
			name = "Hide Thralls",
			position = 9,
			section = extrasSection,
			description = "Completely hides all Arceeus thralls"
	)
	default boolean hideThralls() { return false; }


	// --- OUTLINE SECTION ---
	@ConfigSection(
			name = "Highlights",
			description = "Settings for player outlines and floor tiles.",
			position = 20
	)
	String outlineSection = "outlineSection";

	@ConfigItem(
			keyName = "hideStackedOutlines",
			name = "Hide Stacked Highlights",
			position = 1,
			section = outlineSection,
			description = "Only shows one highlight per tile if players are standing on each other"
	)
	default boolean hideStackedOutlines() { return true; }

	@ConfigItem(
			keyName = "highlightSelf",
			name = "Highlight Myself",
			position = 2,
			section = outlineSection,
			description = "Choose how to highlight your own character"
	)
	default HighlightStyle highlightSelf() { return HighlightStyle.NONE; }

	@Alpha
	@ConfigItem(
			keyName = "selfOutlineColor",
			name = "My Color",
			position = 3,
			section = outlineSection,
			description = "The color of your own highlight"
	)
	default Color selfOutlineColor() { return Color.WHITE; }

	@ConfigItem(
			keyName = "highlightOthers",
			name = "Highlight Others",
			position = 4,
			section = outlineSection,
			description = "Choose how to highlight nearby affected players"
	)
	default HighlightStyle highlightOthers() { return HighlightStyle.NONE; }

	@Alpha
	@ConfigItem(
			keyName = "othersOutlineColor",
			name = "Others Color",
			position = 5,
			section = outlineSection,
			description = "The color of other players' highlights"
	)
	default Color othersOutlineColor() { return new Color(255, 255, 255, 150); }

	@ConfigItem(
			keyName = "highlightThralls",
			name = "Highlight Thralls",
			position = 6,
			section = outlineSection,
			description = "Choose how to highlight Arceeus thralls"
	)
	default HighlightStyle highlightThralls() { return HighlightStyle.NONE; }

	@Alpha
	@ConfigItem(
			keyName = "thrallsOutlineColor",
			name = "Thralls Color",
			position = 7,
			section = outlineSection,
			description = "The color of thrall highlights"
	)
	default Color thrallsOutlineColor() { return new Color(0, 255, 255, 150); }


	// --- OUTLINE STYLE SECTION ---
	@ConfigSection(
			name = "Highlight Style",
			description = "Visual aesthetics of the highlights (Global settings).",
			position = 30
	)
	String styleSection = "styleSection";

	@Range(min = 1, max = 10)
	@ConfigItem(
			keyName = "outlineWidth",
			name = "Line Thickness",
			position = 1,
			section = styleSection,
			description = "Thickness of the primary outline"
	)
	default int outlineWidth() { return 1; }

	@Range(min = 0, max = 10)
	@ConfigItem(
			keyName = "outlineFeather",
			name = "Line Blur (Feather)",
			position = 2,
			section = styleSection,
			description = "How soft the edges of the primary line are"
	)
	default int outlineFeather() { return 0; }

	@ConfigItem(
			keyName = "enableGlow",
			name = "Add Outer Glow",
			position = 3,
			section = styleSection,
			description = "Adds a secondary, wider blurred layer behind the primary line"
	)
	default boolean enableGlow() { return false; }

	@Range(min = 1, max = 20)
	@ConfigItem(
			keyName = "glowWidth",
			name = "Glow Thickness",
			position = 4,
			section = styleSection,
			description = "Width of the glow layer"
	)
	default int glowWidth() { return 4; }

	@Range(min = 1, max = 10)
	@ConfigItem(
			keyName = "glowFeather",
			name = "Glow Blur",
			position = 5,
			section = styleSection,
			description = "Softness/Feathering of the glow layer"
	)
	default int glowFeather() { return 4; }

	@ConfigItem(
			keyName = "fillFloorTile",
			name = "Fill Floor Tile",
			position = 6,
			section = styleSection,
			description = "Fills the inside of the floor tile if a 'Tile' highlight is selected"
	)
	default boolean fillFloorTile() { return false; }

	// --- HOTKEY SECTION ---
	@ConfigSection(
			name = "Hotkeys",
			description = "Hotkey settings for the plugin.",
			position = 40
	)
	String hotkeySection = "hotkeySection";

	@ConfigItem(
			keyName = "toggleHotkey",
			name = "Toggle Plugin",
			position = 1,
			section = hotkeySection,
			description = "Press this key to enable or disable the plugin's effects."
	)
	default Keybind toggleHotkey() { return Keybind.NOT_SET; }

	@ConfigItem(
			keyName = "doubleTapDelay",
			name = "Double-tap delay",
			description = "Delay for the double-tap to toggle the plugin off. 0 to disable.",
			position = 2,
			section = hotkeySection
	)
	@Units(Units.MILLISECONDS)
	default int doubleTapDelay() { return 250; }


	// --- AREA FILTERING SECTION ---
	@ConfigSection(
			name = "Area Filtering",
			description = "Automatically enable/disable the plugin based on your location.",
			position = 50
	)
	String areaFilteringSection = "areaFilteringSection";

	@ConfigItem(
			keyName = "enableAreaFiltering",
			name = "Enable Area Filtering",
			position = 1,
			section = areaFilteringSection,
			description = "If enabled, the plugin will ONLY apply its effects in the selected rooms below."
	)
	default boolean enableAreaFiltering() { return false; }

	// --- THEATRE OF BLOOD ---
	@ConfigSection(name = "Theatre of Blood", description = "ToB Rooms", position = 51)
	String tobSection = "tobSection";

	@ConfigItem(keyName = "tobMaiden", name = "Maiden", section = tobSection, position = 1, description = "Enable in Maiden room")
	default boolean tobMaiden() { return true; }

	@ConfigItem(keyName = "tobBloat", name = "Bloat", section = tobSection, position = 2, description = "Enable in Bloat room")
	default boolean tobBloat() { return true; }

	@ConfigItem(keyName = "tobNylo", name = "Nylocas", section = tobSection, position = 3, description = "Enable in Nylocas room")
	default boolean tobNylo() { return true; }

	@ConfigItem(keyName = "tobSote", name = "Sotetseg", section = tobSection, position = 4, description = "Enable in Sotetseg room")
	default boolean tobSote() { return true; }

	@ConfigItem(keyName = "tobXarpus", name = "Xarpus", section = tobSection, position = 5, description = "Enable in Xarpus room")
	default boolean tobXarpus() { return true; }

	@ConfigItem(keyName = "tobVerzik", name = "Verzik", section = tobSection, position = 6, description = "Enable in Verzik room")
	default boolean tobVerzik() { return true; }


	// --- TOMBS OF AMASCUT ---
	@ConfigSection(name = "Tombs of Amascut", description = "ToA Rooms", position = 52)
	String toaSection = "toaSection";

	@ConfigItem(keyName = "toaZebak", name = "Zebak", section = toaSection, position = 1, description = "Enable in Zebak room")
	default boolean toaZebak() { return true; }

	@ConfigItem(keyName = "toaKephri", name = "Kephri", section = toaSection, position = 2, description = "Enable in Kephri room")
	default boolean toaKephri() { return true; }

	@ConfigItem(keyName = "toaAkkha", name = "Akkha", section = toaSection, position = 3, description = "Enable in Akkha room")
	default boolean toaAkkha() { return true; }

	@ConfigItem(keyName = "toaBaba", name = "Ba-Ba", section = toaSection, position = 4, description = "Enable in Ba-Ba room")
	default boolean toaBaba() { return true; }

	@ConfigItem(keyName = "toaWardens", name = "Wardens", section = toaSection, position = 5, description = "Enable in Wardens room")
	default boolean toaWardens() { return true; }


	// --- CHAMBERS OF XERIC ---
	@ConfigSection(name = "Chambers of Xeric", description = "CoX Rooms", position = 53)
	String coxSection = "coxSection";

	@ConfigItem(keyName = "coxOlm", name = "The Great Olm", section = coxSection, position = 1, description = "Static region check for Olm.")
	default boolean coxOlm() { return true; }

	// --- OTHER BOSSES ---
	@ConfigSection(name = "Other Bosses", description = "Other Boss Rooms", position = 54)
	String otherSection = "otherSection";

	@ConfigItem(keyName = "otherNex", name = "Nex", section = otherSection, position = 1, description = "Enable in Nex room")
	default boolean otherNex() { return true; }

	@ConfigItem(keyName = "otherNightmare", name = "The Nightmare", section = otherSection, position = 2, description = "Enable in The Nightmare / Phosani's Nightmare")
	default boolean otherNightmare() { return true; }

	@ConfigItem(keyName = "otherRoyalTitans", name = "Royal Titans", section = otherSection, position = 3, description = "Enable in the Royal Titans arena")
	default boolean otherRoyalTitans() { return true; }
}