/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.preference.call;

/** One immutable receiver-wide completed-call admission policy. */
public record CallFilteringSettings(boolean enabled, Integer minimumUsableVoicePercent)
{
    public static final CallFilteringSettings DISABLED = new CallFilteringSettings(false, null);

    public CallFilteringSettings
    {
        if(minimumUsableVoicePercent != null &&
            (minimumUsableVoicePercent < 1 || minimumUsableVoicePercent > 100))
        {
            throw new IllegalArgumentException("Minimum usable voice must be between 1 and 100 percent");
        }
        if(enabled && minimumUsableVoicePercent == null)
        {
            throw new IllegalArgumentException("Choose a minimum usable voice percentage before enabling filtering");
        }
    }
}
