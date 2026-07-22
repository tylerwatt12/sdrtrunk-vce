/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.application.service;

import io.github.dsheirer.channel.metadata.activity.ChannelActivityEvent;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityModel;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySelectionDescriptor;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySnapshot;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.sample.Listener;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resolves stable browser Live selection identifiers into short-lived internal runtime handles.
 *
 * <p>The resolver consumes immutable activity snapshots and never executes network, file, or database work on an
 * activity callback.  Exact selections disappear when their row ends.  A site selection remains available through a
 * temporary empty/control-replacement interval and binds to the preferred replacement control row when it appears.</p>
 */
public class LiveContextResolver implements AutoCloseable
{
    private static final String ACTIVITY_CONSUMER = "live-context-resolver";
    private static final long SITE_GAP_RETENTION_NANOS = TimeUnit.SECONDS.toNanos(15);
    private final ChannelProcessingManager mChannelProcessingManager;
    private final ChannelActivityModel mActivityModel;
    private final Map<String,ChannelActivitySelectionDescriptor> mSelections = new ConcurrentHashMap<>();
    private final Map<String,Long> mSiteGapStartedNanos = new ConcurrentHashMap<>();
    private final Map<String,Map<String,ChannelActivitySelectionDescriptor>> mTableSelections = new HashMap<>();
    private final Listener<ChannelActivityEvent> mActivityListener = this::receive;
    private final AtomicBoolean mRunning = new AtomicBoolean();

    public LiveContextResolver(ChannelProcessingManager channelProcessingManager)
    {
        if(channelProcessingManager == null)
        {
            throw new IllegalArgumentException("Channel processing manager cannot be null");
        }

        mChannelProcessingManager = channelProcessingManager;
        mActivityModel = channelProcessingManager.getChannelActivityModel();
    }

    /**
     * Starts snapshot tracking.  Repeated calls have no effect.
     */
    public void start()
    {
        if(mRunning.compareAndSet(false, true))
        {
            mChannelProcessingManager.setChannelActivityEnabled(ACTIVITY_CONSUMER, true);
            mActivityModel.addActivityListener(mActivityListener);
        }
    }

    /**
     * Resolves the latest descriptor and active chains for a selection.  Chain references are looked up on demand so a
     * browser never pins a stopped processing chain in the resolver.
     */
    public Optional<LiveContext> resolve(String selectionId)
    {
        if(selectionId == null || selectionId.isBlank())
        {
            return Optional.empty();
        }

        ChannelActivitySelectionDescriptor selection = mSelections.get(selectionId);

        if(selection == null)
        {
            return Optional.empty();
        }

        if(selection.isSite())
        {
            Long gapStarted = mSiteGapStartedNanos.get(selectionId);

            if(gapStarted != null && System.nanoTime() - gapStarted > SITE_GAP_RETENTION_NANOS)
            {
                mSelections.remove(selectionId, selection);
                return Optional.empty();
            }
        }

        Integer rowChannelId = selection.rowChannelId();
        ProcessingChain processingChain = mChannelProcessingManager.getProcessingChainByFrequency(
            selection.frequencyHz(), selection.timeslot(), rowChannelId);
        Channel rowChannel = rowChannelId != null ?
            mChannelProcessingManager.getProcessingChannel(rowChannelId) : null;

        if(rowChannel == null && processingChain != null)
        {
            rowChannel = mChannelProcessingManager.getChannel(processingChain);
        }

        Channel ownerChannel = selection.ownerChannelId() != null ?
            mChannelProcessingManager.getProcessingChannel(selection.ownerChannelId()) : null;
        ProcessingChain eventProcessingChain = selection.isSite() && ownerChannel != null ?
            mChannelProcessingManager.getProcessingChain(ownerChannel) : processingChain;
        return Optional.of(new LiveContext(selection, ownerChannel, rowChannel, processingChain,
            eventProcessingChain));
    }

    synchronized void receive(ChannelActivityEvent event)
    {
        if(!mRunning.get() || event == null || event.snapshot() == null)
        {
            return;
        }

        ChannelActivitySnapshot snapshot = event.snapshot();

        if(event.operation() == ChannelActivityEvent.Operation.REMOVE)
        {
            removeTable(snapshot.tableId());
            return;
        }

        Map<String,ChannelActivitySelectionDescriptor> previous = mTableSelections.get(snapshot.tableId());
        Map<String,ChannelActivitySelectionDescriptor> next = descriptors(snapshot);
        long now = System.nanoTime();

        for(ChannelActivitySelectionDescriptor descriptor: next.values())
        {
            if(descriptor.isSite())
            {
                mSiteGapStartedNanos.remove(descriptor.selectionId());
            }
        }

        //A logical site selection survives a brief interval with no control row.  Exact rows intentionally do not.
        if(previous != null)
        {
            for(ChannelActivitySelectionDescriptor descriptor: previous.values())
            {
                if(descriptor.isSite())
                {
                    long gapStarted = mSiteGapStartedNanos.computeIfAbsent(descriptor.selectionId(),
                        ignored -> now);

                    if(now - gapStarted <= SITE_GAP_RETENTION_NANOS)
                    {
                        next.putIfAbsent(descriptor.selectionId(), descriptor);
                    }
                }
            }
        }

        replaceTable(snapshot.tableId(), next);
    }

    private static Map<String,ChannelActivitySelectionDescriptor> descriptors(ChannelActivitySnapshot snapshot)
    {
        Map<String,ChannelActivitySelectionDescriptor> descriptors = new HashMap<>();
        Map<String,Integer> siteRanks = new HashMap<>();

        for(ChannelActivitySnapshot.Row row: snapshot.rows())
        {
            ChannelActivitySelectionDescriptor descriptor = row.selectionDescriptor(snapshot.tableId(),
                snapshot.title());

            if(!descriptor.isSite())
            {
                descriptors.put(descriptor.selectionId(), descriptor);
                continue;
            }

            int rank = siteRank(row);
            Integer existingRank = siteRanks.get(descriptor.selectionId());
            ChannelActivitySelectionDescriptor existing = descriptors.get(descriptor.selectionId());

            if(existing == null || existingRank == null || rank > existingRank ||
                (rank == existingRank && descriptor.rowKey().compareTo(existing.rowKey()) < 0))
            {
                descriptors.put(descriptor.selectionId(), descriptor);
                siteRanks.put(descriptor.selectionId(), rank);
            }
        }

        return descriptors;
    }

    private static int siteRank(ChannelActivitySnapshot.Row row)
    {
        List<String> tags = row.tags();

        if(tags.contains("CURRENT_CONTROL"))
        {
            return 4;
        }

        if("CONTROL".equals(row.status()))
        {
            return 3;
        }

        if(tags.contains("CONFIGURED"))
        {
            return 2;
        }

        if(tags.contains("ALTERNATE_CONTROL"))
        {
            return 1;
        }

        return 0;
    }

    private void replaceTable(String tableId, Map<String,ChannelActivitySelectionDescriptor> next)
    {
        Map<String,ChannelActivitySelectionDescriptor> previous = mTableSelections.put(tableId, Map.copyOf(next));

        if(previous != null)
        {
            for(String selectionId: previous.keySet())
            {
                if(!next.containsKey(selectionId))
                {
                    mSelections.remove(selectionId, previous.get(selectionId));
                    mSiteGapStartedNanos.remove(selectionId);
                }
            }
        }

        mSelections.putAll(next);
    }

    private void removeTable(String tableId)
    {
        Map<String,ChannelActivitySelectionDescriptor> removed = mTableSelections.remove(tableId);

        if(removed != null)
        {
            for(Map.Entry<String,ChannelActivitySelectionDescriptor> entry: removed.entrySet())
            {
                mSelections.remove(entry.getKey(), entry.getValue());
                mSiteGapStartedNanos.remove(entry.getKey());
            }
        }
    }

    public void stop()
    {
        if(mRunning.compareAndSet(true, false))
        {
            mActivityModel.removeActivityListener(mActivityListener);
            mChannelProcessingManager.setChannelActivityEnabled(ACTIVITY_CONSUMER, false);
        }

        synchronized(this)
        {
            mSelections.clear();
            mSiteGapStartedNanos.clear();
            mTableSelections.clear();
        }
    }

    @Override
    public void close()
    {
        stop();
    }
}
