/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.metadata.site;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import java.util.List;

/**
 * Stable, session-observed site metadata for an external consumer.
 */
public record SiteMetadataEvent(Channel channel, SiteReceiverContext receiverContext,
                                P25NetworkConfigurationSnapshot snapshot,
                                long observedAtEpochMilliseconds)
{
    private static final String ROLE_PRIMARY_CONTROL = "primary_control";
    private static final String ROLE_CURRENT_CONTROL = "current_control";
    private static final String ROLE_SECONDARY_CONTROL = "secondary_control";

    public SiteMetadataEvent(Channel channel, P25NetworkConfigurationSnapshot snapshot,
                             long observedAtEpochMilliseconds)
    {
        this(channel, snapshot, observedAtEpochMilliseconds, 0);
    }

    public SiteMetadataEvent(Channel channel, P25NetworkConfigurationSnapshot snapshot,
                             long observedAtEpochMilliseconds, long sourceFrequency)
    {
        this(channel, SiteReceiverContext.capture(channel,
            snapshot != null ? snapshot.protocol() : null, sourceFrequency), snapshot,
            observedAtEpochMilliseconds);
    }

    public boolean isUseful()
    {
        return receiverContext != null && receiverContext.isStandardChannel() &&
            !receiverContext.isTrafficChannel() && snapshot != null && snapshot.isUseful();
    }

    /** Exact producer-time frequency, or zero when the producer could not observe it. */
    public long sourceFrequency()
    {
        return receiverContext != null && receiverContext.sourceFrequency() != null ?
            receiverContext.sourceFrequency() : 0;
    }

    /** True only while the optional live channel still represents the captured receiver. */
    public boolean matchesCurrentChannel()
    {
        return receiverContext != null && receiverContext.matchesCurrentChannel(channel);
    }

    /**
     * Indicates that the frequency being decoded is advertised by this snapshot as one of the current site's
     * control channels.  A complete P25 identity is safe to bind only with this proof; otherwise an older snapshot
     * from a previous tuning epoch could claim the saved receiver.
     */
    public boolean isSourceAdvertisedControlChannel()
    {
        return snapshot != null && isAdvertisedControlChannel(snapshot.channels(), sourceFrequency());
    }

    /** Shared definition used by the receiver learner and the asynchronous statistics writer. */
    public static boolean isAdvertisedControlChannel(List<P25NetworkConfigurationSnapshot.Channel> channels,
                                                     long sourceFrequency)
    {
        if(sourceFrequency <= 0 || channels == null)
        {
            return false;
        }

        for(P25NetworkConfigurationSnapshot.Channel channel: channels)
        {
            if(channel != null && channel.downlink() != null && channel.downlink() == sourceFrequency &&
                isCurrentSiteControlRole(channel.role()))
            {
                return true;
            }
        }

        return false;
    }

    private static boolean isCurrentSiteControlRole(String role)
    {
        return ROLE_PRIMARY_CONTROL.equals(role) || ROLE_CURRENT_CONTROL.equals(role) ||
            ROLE_SECONDARY_CONTROL.equals(role);
    }

    /**
     * Protocol-neutral view of this legacy P25 event.
     */
    public ProtocolSiteMetadataEvent asProtocolSiteMetadataEvent()
    {
        return new ProtocolSiteMetadataEvent(channel, receiverContext, snapshot, observedAtEpochMilliseconds);
    }
}
