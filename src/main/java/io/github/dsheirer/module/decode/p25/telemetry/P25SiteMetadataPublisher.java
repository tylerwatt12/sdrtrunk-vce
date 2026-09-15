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

package io.github.dsheirer.module.decode.p25.telemetry;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.metadata.site.SiteMetadataPublicationRateLimiter;
import io.github.dsheirer.metadata.site.SiteMetadataSnapshotRequest;
import io.github.dsheirer.metadata.site.SiteReceiverContext;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Publishes stabilized P25 site metadata from standard/control channels.
 */
public class P25SiteMetadataPublisher
{
    public static final long DEFAULT_EVENT_INTERVAL_MILLISECONDS = 5000;
    private final Channel mChannel;
    private final Supplier<P25NetworkConfigurationSnapshot> mSnapshotSupplier;
    private final BooleanSupplier mHasInterModuleEventBus;
    private final Consumer<SiteMetadataSnapshotRequest> mRequestPublisher;
    private final SiteMetadataPublicationRateLimiter mRateLimiter;
    private final LongSupplier mSourceFrequencySupplier;

    public P25SiteMetadataPublisher(Channel channel,
                                    Supplier<P25NetworkConfigurationSnapshot> snapshotSupplier,
                                    BooleanSupplier hasInterModuleEventBus,
                                    Consumer<SiteMetadataSnapshotRequest> requestPublisher)
    {
        this(channel, snapshotSupplier, hasInterModuleEventBus, requestPublisher,
            new SiteMetadataPublicationRateLimiter(DEFAULT_EVENT_INTERVAL_MILLISECONDS), () -> 0);
    }

    public P25SiteMetadataPublisher(Channel channel,
                                    Supplier<P25NetworkConfigurationSnapshot> snapshotSupplier,
                                    BooleanSupplier hasInterModuleEventBus,
                                    Consumer<SiteMetadataSnapshotRequest> requestPublisher,
                                    LongSupplier sourceFrequencySupplier)
    {
        this(channel, snapshotSupplier, hasInterModuleEventBus, requestPublisher,
            new SiteMetadataPublicationRateLimiter(DEFAULT_EVENT_INTERVAL_MILLISECONDS), sourceFrequencySupplier);
    }

    public P25SiteMetadataPublisher(Channel channel, Supplier<P25NetworkConfigurationSnapshot> snapshotSupplier,
                                    BooleanSupplier hasInterModuleEventBus,
                                    Consumer<SiteMetadataSnapshotRequest> requestPublisher,
                                    SiteMetadataPublicationRateLimiter rateLimiter)
    {
        this(channel, snapshotSupplier, hasInterModuleEventBus, requestPublisher, rateLimiter, () -> 0);
    }

    public P25SiteMetadataPublisher(Channel channel, Supplier<P25NetworkConfigurationSnapshot> snapshotSupplier,
                                    BooleanSupplier hasInterModuleEventBus,
                                    Consumer<SiteMetadataSnapshotRequest> requestPublisher,
                                    SiteMetadataPublicationRateLimiter rateLimiter,
                                    LongSupplier sourceFrequencySupplier)
    {
        mChannel = channel;
        mSnapshotSupplier = snapshotSupplier;
        mHasInterModuleEventBus = hasInterModuleEventBus;
        mRequestPublisher = requestPublisher;
        mRateLimiter = rateLimiter;
        mSourceFrequencySupplier = sourceFrequencySupplier != null ? sourceFrequencySupplier : () -> 0;
    }

    public void publish(long timestamp)
    {
        if(mChannel == null || mChannel.isTrafficChannel())
        {
            return;
        }

        if(mHasInterModuleEventBus == null || !mHasInterModuleEventBus.getAsBoolean())
        {
            return;
        }

        if(mSnapshotSupplier != null && mRequestPublisher != null && mRateLimiter != null &&
            mRateLimiter.tryAcquire())
        {
            long eventTimestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
            long sourceFrequency = mSourceFrequencySupplier.getAsLong();
            SiteReceiverContext receiverContext = SiteReceiverContext.capture(mChannel, null, sourceFrequency);
            mRequestPublisher.accept(new SiteMetadataSnapshotRequest(mChannel, receiverContext, mSnapshotSupplier,
                eventTimestamp));
        }
    }
}
