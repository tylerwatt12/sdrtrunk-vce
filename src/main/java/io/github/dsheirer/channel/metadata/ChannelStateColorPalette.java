/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.channel.metadata;

import io.github.dsheirer.channel.state.State;
import java.awt.Color;
import java.util.Map;

/**
 * Provides low-intensity, theme-aware channel state colors for Swing status cells.
 */
public final class ChannelStateColorPalette
{
    private static final double LIGHT_THEME_TINT = 0.16;
    private static final double DARK_THEME_TINT = 0.28;
    private static final double MINIMUM_TEXT_CONTRAST = 4.5;
    private static final Map<State,Color> ACCENTS = Map.of(
        State.ACTIVE, new Color(0x35A6BD),
        State.CALL, new Color(0x2F73B7),
        State.CONTROL, new Color(0xD99A22),
        State.DATA, new Color(0x3FA36A),
        State.ENCRYPTED, new Color(0xC1427A),
        State.FADE, new Color(0x7B8792),
        State.RESET, new Color(0xC95B6B),
        State.TEARDOWN, new Color(0x59636D));

    private ChannelStateColorPalette()
    {
    }

    /**
     * Blends a state's accent into the current table surface so the indication remains subtle in light and dark
     * themes. Idle and unknown states retain the normal table background.
     */
    public static Color background(State state, Color surface)
    {
        Color safeSurface = surface != null ? surface : Color.WHITE;
        Color accent = state != null ? ACCENTS.get(state) : null;

        if(accent == null)
        {
            return safeSurface;
        }

        double weight = relativeLuminance(safeSurface) < 0.5 ? DARK_THEME_TINT : LIGHT_THEME_TINT;
        return blend(safeSurface, accent, weight);
    }

    /**
     * Keeps the theme's normal table foreground when it remains legible, with a black/white fallback for custom
     * themes whose foreground does not provide accessible contrast against the tinted surface.
     */
    public static Color foreground(Color preferred, Color background)
    {
        Color safeBackground = background != null ? background : Color.WHITE;

        if(preferred != null && contrastRatio(preferred, safeBackground) >= MINIMUM_TEXT_CONTRAST)
        {
            return preferred;
        }

        return contrastRatio(Color.WHITE, safeBackground) >= contrastRatio(Color.BLACK, safeBackground) ?
            Color.WHITE : Color.BLACK;
    }

    private static Color blend(Color surface, Color accent, double accentWeight)
    {
        double surfaceWeight = 1.0 - accentWeight;
        return new Color(
            (int)Math.round(surface.getRed() * surfaceWeight + accent.getRed() * accentWeight),
            (int)Math.round(surface.getGreen() * surfaceWeight + accent.getGreen() * accentWeight),
            (int)Math.round(surface.getBlue() * surfaceWeight + accent.getBlue() * accentWeight));
    }

    private static double contrastRatio(Color first, Color second)
    {
        double lighter = Math.max(relativeLuminance(first), relativeLuminance(second));
        double darker = Math.min(relativeLuminance(first), relativeLuminance(second));
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double relativeLuminance(Color color)
    {
        return 0.2126 * linear(color.getRed()) + 0.7152 * linear(color.getGreen()) +
            0.0722 * linear(color.getBlue());
    }

    private static double linear(int component)
    {
        double value = component / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
