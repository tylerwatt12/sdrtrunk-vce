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
    private final Consumer<ProtocolSiteMetadataEvent> mEventPublisher;
    private final SiteMetadataPublicationRateLimiter mRateLimiter;

    public ProtocolSiteMetadataPublisher(Channel channel,
                                         Supplier<? extends SiteMetadataSnapshot> snapshotSupplier,
                                         BooleanSupplier hasInterModuleEventBus,
                                         Consumer<ProtocolSiteMetadataEvent> eventPublisher)
    {
        this(channel, snapshotSupplier, hasInterModuleEventBus, eventPublisher,
            new SiteMetadataPublicationRateLimiter(DEFAULT_EVENT_INTERVAL_MILLISECONDS));
    }

    public ProtocolSiteMetadataPublisher(Channel channel, Supplier<? extends SiteMetadataSnapshot> snapshotSupplier,
                                         BooleanSupplier hasInterModuleEventBus,
                                         Consumer<ProtocolSiteMetadataEvent> eventPublisher,
                                         SiteMetadataPublicationRateLimiter rateLimiter)
    {
        mChannel = channel;
        mSnapshotSupplier = snapshotSupplier;
        mHasInterModuleEventBus = hasInterModuleEventBus;
        mEventPublisher = eventPublisher;
        mRateLimiter = rateLimiter;
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

        SiteMetadataSnapshot snapshot = mSnapshotSupplier != null ? mSnapshotSupplier.get() : null;

        if(snapshot == null || !snapshot.isUseful())
        {
            return;
        }

        if(mRateLimiter != null && mRateLimiter.tryAcquire())
        {
            if(mEventPublisher != null)
            {
                long eventTimestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
                mEventPublisher.accept(new ProtocolSiteMetadataEvent(mChannel, snapshot, eventTimestamp));
            }
        }
    }
}
