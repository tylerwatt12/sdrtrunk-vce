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

/**
 * Renderer-neutral mutable state for one browser Live Systems activity table.
 *
 * <p>All mutation is confined to {@link ChannelActivityModel}'s activity worker. Published snapshots are immutable,
 * so web consumers never need a lock or access to live receiver state.</p>
 */
public final class ChannelActivityTableState
{
    private static final Comparator<ChannelActivityRow> ROW_SORT =
        Comparator.comparingLong(ChannelActivityRow::getFrequency)
            .thenComparing(row -> row.getTimeslot() != null ? row.getTimeslot() : 0)
            .thenComparing(ChannelActivityRow::getKey);

    private String mTitle;
    private final Channel mOwnerChannel;
    private boolean mControlActive;
    private List<ChannelActivitySnapshot.IdentifierField> mIdentifiers = List.of();
    private final List<ChannelActivityRow> mRows = new ArrayList<>();
    private final Map<String,ChannelActivityRow> mRowsByKey = new HashMap<>();
    private final Listener<ChannelActivitySnapshot> mSnapshotListener;
    private volatile ChannelActivitySnapshot mLatestSnapshot;

    public ChannelActivityTableState(String title, Channel ownerChannel,
                                     Listener<ChannelActivitySnapshot> snapshotListener)
    {
        mTitle = title;
        mOwnerChannel = ownerChannel;
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

    public List<ChannelActivitySnapshot.IdentifierField> getIdentifiers()
    {
        return mIdentifiers;
    }

    public void setIdentifiers(List<ChannelActivitySnapshot.IdentifierField> identifiers)
    {
        List<ChannelActivitySnapshot.IdentifierField> values = identifiers != null ? List.copyOf(identifiers) : List.of();

        if(!mIdentifiers.equals(values))
        {
            mIdentifiers = values;
            publish();
        }
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
     * Worker-thread view of the live rows. Consumers outside the activity worker use the immutable snapshot.
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

    public ChannelActivitySnapshot getLatestSnapshot()
    {
        return mLatestSnapshot;
    }

    private void publish()
    {
        ChannelActivitySnapshot snapshot = ChannelActivitySnapshot.from(this);
        mLatestSnapshot = snapshot;

        if(mSnapshotListener != null)
        {
            mSnapshotListener.receive(snapshot);
        }
    }
}
