package com.example;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

@Config(name = "nowplaying")
public class NowPlayingConfig implements ConfigData {

    // Which side of the screen the HUD panel should stick to
    public enum Side {
        LEFT,
        RIGHT
    }

    // ─────────────────────────────
    // Layout
    // ─────────────────────────────

    @ConfigEntry.Gui.Tooltip
    public Side sidePosition = Side.RIGHT;

    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.BoundedDiscrete(min = 0, max = 100)
    public int yPosition = 10;

    /**
     * Background opacity (0 = fully transparent, 100 = fully opaque)
     */
    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.BoundedDiscrete(min = 0, max = 100)
    public int backgroundOpacity = 55;

    // ─────────────────────────────
    // Visibility toggles
    // ─────────────────────────────

    @ConfigEntry.Gui.Tooltip
    public boolean showCoverArt = true;

    @ConfigEntry.Gui.Tooltip
    public boolean showMediaTitle = true;

    @ConfigEntry.Gui.Tooltip
    public boolean showArtistName = true;

    @ConfigEntry.Gui.Tooltip
    public boolean showTimeline = true;

    @ConfigEntry.Gui.Tooltip
    public boolean showPlayStatusIcon = true;
}
