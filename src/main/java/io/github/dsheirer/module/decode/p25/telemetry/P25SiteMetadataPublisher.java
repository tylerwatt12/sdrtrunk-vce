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
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
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
    private final Consumer<SiteMetadataEvent> mEventPublisher;
    private final SiteMetadataPublicationRateLimiter mRateLimiter;

    public P25SiteMetadataPublisher(Channel channel,
                                    Supplier<P25NetworkConfigurationSnapshot> snapshotSupplier,
                                    BooleanSupplier hasInterModuleEventBus,
                                    Consumer<SiteMetadataEvent> eventPublisher)
    {
        this(channel, snapshotSupplier, hasInterModuleEventBus, eventPublisher,
            new SiteMetadataPublicationRateLimiter(DEFAULT_EVENT_INTERVAL_MILLISECONDS));
    }

    public P25SiteMetadataPublisher(Channel channel, Supplier<P25NetworkConfigurationSnapshot> snapshotSupplier,
                                    BooleanSupplier hasInterModuleEventBus,
                                    Consumer<SiteMetadataEvent> eventPublisher,
                                    SiteMetadataPublicationRateLimiter rateLimiter)
    {
        mChannel = channel;
        mSnapshotSupplier = snapshotSupplier;
        mHasInterModuleEventBus = hasInterModuleEventBus;
        mEventPublisher = eventPublisher;
        mRateLimiter = rateLimiter;
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

        P25NetworkConfigurationSnapshot snapshot = mSnapshotSupplier != null ? mSnapshotSupplier.get() : null;

        if(snapshot != null && snapshot.isUseful() && mRateLimiter != null && mRateLimiter.tryAcquire())
        {
            if(mEventPublisher != null)
            {
                long eventTimestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
                mEventPublisher.accept(new SiteMetadataEvent(mChannel, snapshot, eventTimestamp));
            }
        }
    }
}
