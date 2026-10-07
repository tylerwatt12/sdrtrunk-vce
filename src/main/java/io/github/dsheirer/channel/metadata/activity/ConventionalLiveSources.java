/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Current conventional membership shared by the live event and message viewers. Classification belongs to the
 * activity model: a decoder type alone cannot distinguish a conventional channel from a trunked control or traffic
 * channel. This observer-side cache contains only the latest immutable snapshot, never channel or message history.
 */
public final class ConventionalLiveSources implements Supplier<List<ConventionalLiveSources.Source>>
{
    private final ChannelActivityModel mActivityModel;
    private ChannelActivityModel.SnapshotSet mSnapshot;
    private List<Source> mSources = List.of();
    private Set<Key> mKeys = Set.of();

    public ConventionalLiveSources(ChannelActivityModel activityModel)
    {
        mActivityModel = activityModel;
    }

    @Override
    public synchronized List<Source> get()
    {
        ChannelActivityModel.SnapshotSet snapshot = mActivityModel != null ? mActivityModel.getSnapshotSet() : null;

        if(snapshot != mSnapshot)
        {
            mSources = fromSnapshot(snapshot);
            mKeys = Set.copyOf(mSources.stream().map(source ->
                new Key(source.configurationId(), source.frequencyHz())).toList());
            mSnapshot = snapshot;
        }

        return mSources;
    }

    public synchronized boolean matches(String configurationId, Long frequencyHz)
    {
        get();
        return frequencyHz != null && mKeys.contains(new Key(configurationId, frequencyHz));
    }

    public static List<Source> fromSnapshot(ChannelActivityModel.SnapshotSet snapshot)
    {
        Map<Key,Source> sources = new LinkedHashMap<>();

        if(snapshot != null)
        {
            for(ChannelActivitySnapshot table: snapshot.tables())
            {
                if(!"conventional".equals(table.tableId()))
                {
                    continue;
                }

                for(ChannelActivitySnapshot.Row row: table.rows())
                {
                    if("CONVENTIONAL".equals(row.role()) && row.configurationId() != null &&
                        !row.configurationId().isBlank() && row.frequencyHz() > 0)
                    {
                        Source source = new Source(row.configurationId(), row.frequencyHz(), row.channelName());
                        // DMR timeslots belong to one receiver source and must not create duplicate taps.
                        sources.putIfAbsent(new Key(source.configurationId(), source.frequencyHz()), source);
                    }
                }
            }
        }

        return sources.values().stream().sorted(Comparator.comparing(Source::configurationId)
            .thenComparingLong(Source::frequencyHz)).toList();
    }

    public record Source(String configurationId, long frequencyHz, String channelName)
    {
    }

    private record Key(String configurationId, long frequencyHz)
    {
    }
}
