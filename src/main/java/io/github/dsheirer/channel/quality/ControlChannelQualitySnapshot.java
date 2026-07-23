/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.quality;

import io.github.dsheirer.controller.channel.Channel;

/**
 * Immutable live quality measurement for the currently tuned trunked control channel.
 */
public record ControlChannelQualitySnapshot(Channel channel, String guid, long frequencyHz, long observedAtMs,
                                            boolean active, Double signalDbfs, Double averageSignalDbfs,
                                            Double minimumSignalDbfs, Double maximumSignalDbfs,
                                            Double decodeHealthPercent, long validFrames, long invalidFrames,
                                            long correctedBits, long syncLossBits, long droppedBits,
                                            long lastValidDecodeMs)
{
}
