/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Renderer-neutral mutable state for one Systems activity table.
 *
 * <p>All mutation is confined to {@link ChannelActivityModel}'s activity worker.  Published web and desktop views are
 * immutable, so neither renderer needs a lock or access to live receiver state.</p>
 */
public final class ChannelActivityTableState
{
    private static final Comparator<ChannelActivityRow> ROW_SORT =
        Comparator.comparingLong(ChannelActivityRow::getFrequency)
            .thenComparing(row -> row.getTimeslot() != null ? row.getTimeslot() : 0)
            .thenComparing(ChannelActivityRow::getKey);

    private String mTitle;
    private final Channel mOwnerChannel;
    private final boolean mCloseable;
    private boolean mControlActive;
    private final List<ChannelActivityRow> mRows = new ArrayList<>();
    private final Map<String,ChannelActivityRow> mRowsByKey = new HashMap<>();
    private final List<Listener<ChannelActivityTableView>> mViewListeners = new CopyOnWriteArrayList<>();
    private final Listener<ChannelActivitySnapshot> mSnapshotListener;
    private final AtomicBoolean mCloseRequested = new AtomicBoolean();
    private volatile ChannelActivitySnapshot mLatestSnapshot;
    private volatile ChannelActivityTableView mLatestView;

    public ChannelActivityTableState(String title, Channel ownerChannel, boolean closeable,
                                     Listener<ChannelActivitySnapshot> snapshotListener)
    {
        mTitle = title;
        mOwnerChannel = ownerChannel;
        mCloseable = closeable;
        mSnapshotListener = snapshotListener;
        publish();
    }

    public String getTableId()
    {
        return mOwnerChannel != null ? "channel-" + mOwnerChannel.getChannelID() : "conventional";
    }

    public String getTitle()
    {
        return mTitle;
    }

    public void setTitle(String title)
    {
        mTitle = title;
        publish();
    }

    public Channel getOwnerChannel()
    {
        return mOwnerChannel;
    }

    public boolean isCloseable()
    {
        return mCloseable;
    }

    public boolean isControlActive()
    {
        return mControlActive;
    }

    public boolean setControlActive(boolean controlActive)
    {
        boolean changed = mControlActive != controlActive;
        mControlActive = controlActive;

        if(changed)
        {
            publish();
        }

        return changed;
    }

    public ChannelActivityRow getOrCreate(String key, Channel channel, ChannelActivityRow.Role role, long frequency,
                                          Integer timeslot)
    {
        ChannelActivityRow row = mRowsByKey.get(key);

        if(row == null)
        {
            row = new ChannelActivityRow(key, channel, role, frequency, timeslot);
            mRowsByKey.put(key, row);
            mRows.add(row);
            mRows.sort(ROW_SORT);
        }
        else
        {
            row.setChannel(channel);
        }

        return row;
    }

    public ChannelActivityRow get(String key)
    {
        return mRowsByKey.get(key);
    }

    public void remove(ChannelActivityRow row)
    {
        if(row != null && mRows.remove(row))
        {
            mRowsByKey.remove(row.getKey());
            publish();
        }
    }

    /**
     * Worker-thread view of the live rows.  Renderers must consume {@link #getLatestView()} instead.
     */
    public List<ChannelActivityRow> getRows()
    {
        return new ArrayList<>(mRows);
    }

    public void clear()
    {
        mRows.clear();
        mRowsByKey.clear();
        publish();
    }

    public void refresh(ChannelActivityRow row)
    {
        if(row != null && mRowsByKey.get(row.getKey()) == row)
        {
            publish();
        }
    }

    public void refresh(Collection<ChannelActivityRow> rows)
    {
        if(rows != null && !rows.isEmpty())
        {
            publish();
        }
    }

    public void refreshAllRows()
    {
        publish();
    }

    public ChannelActivitySnapshot getLatestSnapshot()
    {
        return mLatestSnapshot;
    }

    ChannelActivityTableView getLatestView()
    {
        return mLatestView;
    }

    void addViewListener(Listener<ChannelActivityTableView> listener)
    {
        if(listener != null)
        {
            mViewListeners.add(listener);
            listener.receive(mLatestView);
        }
    }

    void removeViewListener(Listener<ChannelActivityTableView> listener)
    {
        mViewListeners.remove(listener);
    }

    void requestClose()
    {
        mCloseRequested.set(true);
    }

    boolean consumeCloseRequest()
    {
        return mCloseRequested.compareAndSet(true, false);
    }

    boolean isCloseRequested()
    {
        return mCloseRequested.get();
    }

    private void publish()
    {
        ChannelActivitySnapshot snapshot = ChannelActivitySnapshot.from(this);
        List<ChannelActivityRow> rowCopies = mRows.stream().map(ChannelActivityRow::copy).toList();
        ChannelActivityTableView view = new ChannelActivityTableView(getTableId(), mTitle, mOwnerChannel, mCloseable,
            mControlActive, rowCopies);
        mLatestSnapshot = snapshot;
        mLatestView = view;

        if(mSnapshotListener != null)
        {
            mSnapshotListener.receive(snapshot);
        }

        for(Listener<ChannelActivityTableView> listener: mViewListeners)
        {
            listener.receive(view);
        }
    }
}
