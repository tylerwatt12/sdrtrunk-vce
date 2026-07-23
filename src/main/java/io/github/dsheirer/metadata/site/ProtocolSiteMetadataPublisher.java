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
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Publishes useful protocol site metadata immediately when it changes and periodically while unchanged.
 */
public class ProtocolSiteMetadataPublisher
{
    public static final long DEFAULT_EVENT_INTERVAL_MILLISECONDS = 5000;
    private final Channel mChannel;
    private final Supplier<? extends SiteMetadataSnapshot> mSnapshotSupplier;
    private final BooleanSupplier mHasInterModuleEventBus;
    private final Consumer<ProtocolSiteMetadataEvent> mEventPublisher;
    private final long mEventIntervalMilliseconds;
    private SiteMetadataSnapshot mLastPublishedSnapshot;
    private long mLastPublishedTimestamp;

    public ProtocolSiteMetadataPublisher(Channel channel,
                                         Supplier<? extends SiteMetadataSnapshot> snapshotSupplier,
                                         BooleanSupplier hasInterModuleEventBus,
                                         Consumer<ProtocolSiteMetadataEvent> eventPublisher)
    {
        this(channel, snapshotSupplier, hasInterModuleEventBus, eventPublisher,
            DEFAULT_EVENT_INTERVAL_MILLISECONDS);
    }

    ProtocolSiteMetadataPublisher(Channel channel, Supplier<? extends SiteMetadataSnapshot> snapshotSupplier,
                                  BooleanSupplier hasInterModuleEventBus,
                                  Consumer<ProtocolSiteMetadataEvent> eventPublisher,
                                  long eventIntervalMilliseconds)
    {
        mChannel = channel;
        mSnapshotSupplier = snapshotSupplier;
        mHasInterModuleEventBus = hasInterModuleEventBus;
        mEventPublisher = eventPublisher;
        mEventIntervalMilliseconds = eventIntervalMilliseconds;
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

        long eventTimestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
        boolean changed = !Objects.equals(snapshot, mLastPublishedSnapshot);
        boolean intervalElapsed = eventTimestamp - mLastPublishedTimestamp >= mEventIntervalMilliseconds;

        if(changed || intervalElapsed)
        {
            mLastPublishedSnapshot = snapshot;
            mLastPublishedTimestamp = eventTimestamp;

            if(mEventPublisher != null)
            {
                mEventPublisher.accept(new ProtocolSiteMetadataEvent(mChannel, snapshot, eventTimestamp));
            }
        }
    }

    public void reset()
    {
        mLastPublishedSnapshot = null;
        mLastPublishedTimestamp = 0;
    }
}
