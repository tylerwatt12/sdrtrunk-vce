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

package io.github.dsheirer.radioresolve.activitylog;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.metadata.site.SiteMetadataListener;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.sample.Listener;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
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
    private final Listener<IDecodeEvent> mDecodeEventListener = this::receiveDecodeEvent;
    private final Map<String,Long> mRecentDedupeKeys = new HashMap<>();
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
    public Listener<IDecodeEvent> getDecodeEventListener()
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

        if(mWriter != null && databasePath.equals(mCurrentDatabasePath))
        {
            mWriter.setRetentionDays(retentionDays);
            return;
        }

        stopWriter();
        mCurrentDatabasePath = databasePath;
        mWriter = new P25ActivityLogWriter(databasePath, retentionDays);
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

    private void receiveDecodeEvent(IDecodeEvent event)
    {
        P25ActivityLogWriter writer = mWriter;

        if(writer == null)
        {
            return;
        }

        P25ActivityLogRecords.ActivityEvent record = mMapper.map(event);

        if(record != null && shouldLog(record))
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

}
