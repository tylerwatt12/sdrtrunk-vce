/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import io.github.dsheirer.audio.call.VoiceCallQuality;

/**
 * Transient control- and voice-channel quality shown in the existing Decode column.
 */
public record ChannelActivityDecodeQuality(Double controlPercent, long controlValidFrames,
                                           long controlInvalidFrames, long controlCorrectedBits,
                                           long controlSyncLossBits, long controlDroppedBits,
                                           long controlLastValidDecodeMs,
                                           VoiceCallQuality voice)
{
    public boolean hasControl()
    {
        return controlLastValidDecodeMs > 0 || controlPercent != null && Double.isFinite(controlPercent);
    }

    public boolean hasVoice()
    {
        return voice != null && voice.hasMeasurements();
    }
}
