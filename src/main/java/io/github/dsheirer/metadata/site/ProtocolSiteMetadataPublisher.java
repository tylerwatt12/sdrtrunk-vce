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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Publishes useful protocol site metadata on a bounded latest-value interval.
 */
public class ProtocolSiteMetadataPublisher
{
    public static final long DEFAULT_EVENT_INTERVAL_MILLISECONDS = 5000;
    private final Channel mChannel;
    private final Supplier<? extends SiteMetadataSnapshot> mSnapshotSupplier;
    private final BooleanSupplier mHasInterModuleEventBus;
    private final Consumer<ProtocolSiteMetadataSnapshotRequest> mRequestPublisher;
    private final SiteMetadataPublicationRateLimiter mRateLimiter;
    private final LongSupplier mSourceFrequencySupplier;

    public ProtocolSiteMetadataPublisher(Channel channel,
                                         Supplier<? extends SiteMetadataSnapshot> snapshotSupplier,
                                         BooleanSupplier hasInterModuleEventBus,
                                         Consumer<ProtocolSiteMetadataSnapshotRequest> requestPublisher)
    {
        this(channel, snapshotSupplier, hasInterModuleEventBus, requestPublisher,
            new SiteMetadataPublicationRateLimiter(DEFAULT_EVENT_INTERVAL_MILLISECONDS), () -> 0);
    }

    public ProtocolSiteMetadataPublisher(Channel channel,
                                         Supplier<? extends SiteMetadataSnapshot> snapshotSupplier,
                                         BooleanSupplier hasInterModuleEventBus,
                                         Consumer<ProtocolSiteMetadataSnapshotRequest> requestPublisher,
                                         LongSupplier sourceFrequencySupplier)
    {
        this(channel, snapshotSupplier, hasInterModuleEventBus, requestPublisher,
            new SiteMetadataPublicationRateLimiter(DEFAULT_EVENT_INTERVAL_MILLISECONDS), sourceFrequencySupplier);
    }

    public ProtocolSiteMetadataPublisher(Channel channel, Supplier<? extends SiteMetadataSnapshot> snapshotSupplier,
                                         BooleanSupplier hasInterModuleEventBus,
                                         Consumer<ProtocolSiteMetadataSnapshotRequest> requestPublisher,
                                         SiteMetadataPublicationRateLimiter rateLimiter)
    {
        this(channel, snapshotSupplier, hasInterModuleEventBus, requestPublisher, rateLimiter, () -> 0);
    }

    public ProtocolSiteMetadataPublisher(Channel channel, Supplier<? extends SiteMetadataSnapshot> snapshotSupplier,
                                         BooleanSupplier hasInterModuleEventBus,
                                         Consumer<ProtocolSiteMetadataSnapshotRequest> requestPublisher,
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

    /**
     * Attempts to publish the latest snapshot.
     */
    public void publish(long timestamp)
    {
        if(mChannel == null || !mChannel.isStandardChannel() || mHasInterModuleEventBus == null ||
            !mHasInterModuleEventBus.getAsBoolean())
        {
            return;
        }

        if(mSnapshotSupplier != null && mRequestPublisher != null && mRateLimiter != null &&
            mRateLimiter.tryAcquire())
        {
            long eventTimestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
            long sourceFrequency = mSourceFrequencySupplier.getAsLong();
            SiteReceiverContext receiverContext = SiteReceiverContext.capture(mChannel, null, sourceFrequency);
            mRequestPublisher.accept(new ProtocolSiteMetadataSnapshotRequest(mChannel, receiverContext,
                mSnapshotSupplier, eventTimestamp));
        }
    }
}
