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

package io.github.dsheirer.stats.activity;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.metadata.site.SiteMetadataListener;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.p25.P25CallStartEvent;
import io.github.dsheirer.module.decode.p25.P25GrantObservationEvent;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns optional P25 activity logging and keeps SQLite work off decoder/UI threads.
 */
public class P25ActivityLogService implements SiteMetadataListener
{
    private static final Logger mLog = LoggerFactory.getLogger(P25ActivityLogService.class);
    private static final long DEDUPE_RETENTION_MILLISECONDS = 60000;

    private final UserPreferences mUserPreferences;
    private final P25ActivityLogMapper mMapper = new P25ActivityLogMapper();
    private final BiConsumer<Channel,IDecodeEvent> mDecodeEventListener = this::receiveDecodeEvent;
    private final Map<String,Long> mRecentDedupeKeys = new HashMap<>();
    private final List<P25ActivityCommitListener> mCommitListeners = new CopyOnWriteArrayList<>();
    private volatile P25ActivityLogWriter mWriter;
    private Path mCurrentDatabasePath;

    public P25ActivityLogService(UserPreferences userPreferences)
    {
        mUserPreferences = userPreferences;
        MyEventBus.getGlobalEventBus().register(this);
        updateWriterState();
    }

    /**
     * Listener for decoded events.
     */
    public BiConsumer<Channel,IDecodeEvent> getDecodeEventListener()
    {
        return mDecodeEventListener;
    }

    public void dispose()
    {
        MyEventBus.getGlobalEventBus().unregister(this);
        stopWriter();
    }

    @Subscribe
    public void preferenceUpdated(PreferenceType preferenceType)
    {
        if(preferenceType == PreferenceType.APPLICATION || preferenceType == PreferenceType.DIRECTORY)
        {
            updateWriterState();
        }
    }

    private synchronized void updateWriterState()
    {
        ApplicationPreference preference = mUserPreferences.getApplicationPreference();

        if(!preference.isStatsLoggingEnabled())
        {
            stopWriter();
            return;
        }

        Path databasePath = P25ActivityLogPath.getDatabasePath(mUserPreferences);
        int retentionDays = preference.getStatsLoggingRetentionDays();
        boolean detailedEventHistoryEnabled = preference.isStatsDetailedHistoryEnabled();

        if(mWriter != null && databasePath.equals(mCurrentDatabasePath))
        {
            mWriter.setRetentionDays(retentionDays);
            mWriter.setDetailedEventHistoryEnabled(detailedEventHistoryEnabled);
            return;
        }

        stopWriter();
        mCurrentDatabasePath = databasePath;
        mWriter = new P25ActivityLogWriter(databasePath, retentionDays, detailedEventHistoryEnabled,
            this::notifyActivityCommitted);
        mWriter.start();
        mLog.info("Stats database logging enabled [{}]", databasePath);
    }

    private synchronized void stopWriter()
    {
        if(mWriter != null)
        {
            mWriter.close();
            mWriter = null;
            mCurrentDatabasePath = null;

            synchronized(mRecentDedupeKeys)
            {
                mRecentDedupeKeys.clear();
            }

            mLog.info("P25 database logging disabled");
        }
    }

    private void receiveDecodeEvent(Channel channel, IDecodeEvent event)
    {
        P25ActivityLogWriter writer = mWriter;

        if(writer == null)
        {
            return;
        }

        P25ActivityLogRecords.ActivityEvent record = mMapper.map(channel, event);

        if(record != null && shouldLog(record))
        {
            writer.enqueue(record);
        }
    }

    @Subscribe
    public void receiveCallStart(P25CallStartEvent event)
    {
        P25ActivityLogWriter writer = mWriter;

        if(writer == null)
        {
            return;
        }

        P25ActivityLogRecords.ActivityEvent record = mMapper.map(event);

        if(record != null)
        {
            writer.enqueue(record);
        }
    }

    @Subscribe
    public void receiveGrantObservation(P25GrantObservationEvent event)
    {
        P25ActivityLogWriter writer = mWriter;

        if(writer == null)
        {
            return;
        }

        P25ActivityLogRecords.ActivityEvent record = mMapper.map(event);

        if(record != null)
        {
            writer.enqueue(record);
        }
    }

    @Override
    public void receiveSiteMetadata(SiteMetadataEvent event)
    {
        P25ActivityLogWriter writer = mWriter;

        if(writer == null)
        {
            return;
        }

        P25ActivityLogRecords.SiteSnapshot record = mMapper.map(event);

        if(record != null)
        {
            writer.enqueue(record);
        }
    }

    private boolean shouldLog(P25ActivityLogRecords.ActivityEvent record)
    {
        if(record.dedupeKey() == null)
        {
            return true;
        }

        long now = System.currentTimeMillis();

        synchronized(mRecentDedupeKeys)
        {
            cleanupDedupeKeys(now);
            Long previous = mRecentDedupeKeys.put(record.dedupeKey(), now);
            return previous == null;
        }
    }

    private void cleanupDedupeKeys(long now)
    {
        Iterator<Map.Entry<String,Long>> iterator = mRecentDedupeKeys.entrySet().iterator();

        while(iterator.hasNext())
        {
            Map.Entry<String,Long> entry = iterator.next();

            if(now - entry.getValue() > DEDUPE_RETENTION_MILLISECONDS)
            {
                iterator.remove();
            }
        }
    }

    public void addActivityCommitListener(P25ActivityCommitListener listener)
    {
        if(listener != null)
        {
            mCommitListeners.add(listener);
        }
    }

    public void removeActivityCommitListener(P25ActivityCommitListener listener)
    {
        mCommitListeners.remove(listener);
    }

    private void notifyActivityCommitted(List<Long> rowIds)
    {
        for(P25ActivityCommitListener listener: mCommitListeners)
        {
            listener.activityCommitted(rowIds);
        }
    }

}
