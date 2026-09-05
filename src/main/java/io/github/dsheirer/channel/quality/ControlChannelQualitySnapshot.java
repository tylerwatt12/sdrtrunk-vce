/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.quality;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.metadata.site.SiteReceiverContext;

/**
 * Immutable live quality measurement for the currently tuned trunked control channel.
 */
public record ControlChannelQualitySnapshot(Channel channel, SiteReceiverContext receiverContext,
                                            long frequencyHz, long observedAtMs,
                                            boolean active, Double signalDbfs, Double averageSignalDbfs,
                                            Double minimumSignalDbfs, Double maximumSignalDbfs,
                                            Double decodeHealthPercent, long validFrames, long invalidFrames,
                                            long correctedBits, long syncLossBits, long droppedBits,
                                            long lastValidDecodeMs)
{
    /** Compatibility constructor that immediately freezes the supplied channel facts. */
    public ControlChannelQualitySnapshot(Channel channel, String configurationId, long frequencyHz,
                                         long observedAtMs, boolean active, Double signalDbfs,
                                         Double averageSignalDbfs, Double minimumSignalDbfs,
                                         Double maximumSignalDbfs, Double decodeHealthPercent, long validFrames,
                                         long invalidFrames, long correctedBits, long syncLossBits, long droppedBits,
                                         long lastValidDecodeMs)
    {
        this(channel, context(channel, configurationId, frequencyHz), frequencyHz, observedAtMs, active,
            signalDbfs, averageSignalDbfs, minimumSignalDbfs, maximumSignalDbfs, decodeHealthPercent, validFrames,
            invalidFrames, correctedBits, syncLossBits, droppedBits, lastValidDecodeMs);
    }

    public String configurationId()
    {
        return receiverContext != null ? receiverContext.configurationId() : null;
    }

    /** True only while the opaque live-channel token still represents the producer-time receiver facts. */
    public boolean matchesCurrentChannel()
    {
        return receiverContext != null && receiverContext.matchesCurrentQualityReceiver(channel, active);
    }

    private static SiteReceiverContext context(Channel channel, String configurationId, long frequencyHz)
    {
        SiteReceiverContext context = SiteReceiverContext.capture(channel, null, frequencyHz);
        return context != null ? context.withConfigurationId(configurationId) :
            SiteReceiverContext.detached(configurationId, frequencyHz);
    }
}
