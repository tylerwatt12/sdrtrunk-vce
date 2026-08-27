/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import io.github.dsheirer.channel.metadata.activity.ChannelActivityEvent;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityModel;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySnapshot;
import io.github.dsheirer.sample.Listener;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Mutable authoritative activity source used to exercise the real web-adapter lifecycle in tests. */
final class TestChannelActivitySource implements StatsLiveService.ActivitySource
{
    private final Map<String,ChannelActivitySnapshot> mSnapshots = new LinkedHashMap<>();
    private final List<Listener<ChannelActivityEvent>> mListeners = new CopyOnWriteArrayList<>();
    private long mRevision;

    @Override
    public synchronized ChannelActivityModel.SnapshotSet snapshot()
    {
        return new ChannelActivityModel.SnapshotSet(mRevision, List.copyOf(mSnapshots.values()));
    }

    @Override
    public void addListener(Listener<ChannelActivityEvent> listener)
    {
        if(listener != null)
        {
            mListeners.add(listener);
        }
    }

    @Override
    public void removeListener(Listener<ChannelActivityEvent> listener)
    {
        mListeners.remove(listener);
    }

    void publish(ChannelActivityEvent event)
    {
        if(event == null || event.operation() == null || event.snapshot() == null)
        {
            throw new IllegalArgumentException("A complete channel-activity event is required");
        }

        ChannelActivityEvent published;

        synchronized(this)
        {
            if(event.operation() == ChannelActivityEvent.Operation.REMOVE)
            {
                mSnapshots.remove(event.snapshot().tableId());
            }
            else
            {
                mSnapshots.put(event.snapshot().tableId(), event.snapshot());
            }

            published = new ChannelActivityEvent(event.operation(), event.snapshot(), ++mRevision);
        }

        mListeners.forEach(listener -> listener.receive(published));
    }
}
