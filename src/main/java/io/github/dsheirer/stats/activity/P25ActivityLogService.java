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
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.channel.quality.ControlChannelQualitySnapshot;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.metadata.site.SiteMetadataListener;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataEvent;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataListener;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.p25.P25CallStartEvent;
import io.github.dsheirer.module.decode.p25.P25GrantObservationEvent;
import io.github.dsheirer.module.decode.p25.P25TalkerAliasEvent;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.sample.Listener;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns statistics collection and maintenance and keeps SQLite work off decoder/UI threads.
 */
public class P25ActivityLogService implements SiteMetadataListener, ProtocolSiteMetadataListener
{
    private static final Logger mLog = LoggerFactory.getLogger(P25ActivityLogService.class);
    private static final long DEDUPE_RETENTION_MILLISECONDS = 60000;

    private final UserPreferences mUserPreferences;
    private final P25ActivityLogMapper mMapper = new P25ActivityLogMapper();
    private final BiConsumer<Channel,IDecodeEvent> mDecodeEventListener = this::receiveDecodeEvent;
    private final Listener<ControlChannelQualitySnapshot> mQualityListener = this::receiveControlChannelQuality;
    private final Map<String,Long> mRecentDedupeKeys = new LinkedHashMap<>(256, 0.75f, true);
    private final Map<String,TrunkedSiteEvidence> mObservedTrunkedSites = new ConcurrentHashMap<>();
    private final List<P25ActivityCommitListener> mCommitListeners = new CopyOnWriteArrayList<>();
    private volatile P25ActivityLogWriter mWriter;
    private volatile boolean mCollectionEnabled;
    private Path mCurrentDatabasePath;
    private P25ActivityLogWriter.WriterStatus mLastWriterStatus;

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

    public Listener<ControlChannelQualitySnapshot> getControlChannelQualityListener()
    {
        return mQualityListener;
    }

    public void receiveRecordedCall(CompletedAudioCall call)
    {
        receiveCallOutput(call, P25ActivityLogRecords.CallOutput.RECORDED);
    }

    public void receiveStreamedCall(CompletedAudioCall call)
    {
        receiveCallOutput(call, P25ActivityLogRecords.CallOutput.STREAMED);
    }

    private void receiveCallOutput(CompletedAudioCall call, P25ActivityLogRecords.CallOutput output)
    {
        P25ActivityLogWriter writer = getCollectionWriter();

        if(writer != null)
        {
            P25ActivityLogRecords.CompletedCallOutput completedCallOutput =
                mMapper.mapCompletedCallOutput(call, output);

            if(completedCallOutput != null)
            {
                writer.enqueue(completedCallOutput);
            }
        }
    }

    private void receiveControlChannelQuality(ControlChannelQualitySnapshot snapshot)
    {
        P25ActivityLogWriter writer = getCollectionWriter();

        if(snapshot != null && !snapshot.active() && snapshot.guid() != null)
        {
            mObservedTrunkedSites.computeIfPresent(snapshot.guid(), (guid, evidence) ->
                evidence.channel() == snapshot.channel() ? null : evidence);
        }

        TrunkedSiteEvidence evidence = snapshot != null && snapshot.guid() != null ?
            mObservedTrunkedSites.get(snapshot.guid()) : null;
        boolean observedTrunkedSite = hasCurrentTrunkedSiteEvidence(snapshot, evidence);

        if(evidence != null && !observedTrunkedSite)
        {
            mObservedTrunkedSites.remove(snapshot.guid(), evidence);
        }

        if(writer != null && shouldPersistControlChannelQuality(snapshot, observedTrunkedSite) &&
            snapshot.active() && snapshot.guid() != null && !snapshot.guid().isBlank() && snapshot.frequencyHz() > 0)
        {
            writer.enqueue(new P25ActivityLogRecords.ControlChannelQuality(snapshot.observedAtMs(), snapshot.guid(),
                snapshot.frequencyHz(), snapshot.signalDbfs(), snapshot.averageSignalDbfs(),
                snapshot.minimumSignalDbfs(), snapshot.maximumSignalDbfs(), snapshot.decodeHealthPercent(),
                snapshot.validFrames(), snapshot.invalidFrames(), snapshot.correctedBits(), snapshot.syncLossBits(),
                snapshot.droppedBits(), snapshot.lastValidDecodeMs()));
        }
    }

    /**
     * Requires metadata evidence from the same running channel and decoder configuration. DMR and NXDN decoder types
     * can also be conventional, so a GUID or decoder type alone is not durable proof that later quality samples are
     * trunked. The quality monitor's inactive snapshot clears this evidence when the channel stops.
     */
    static boolean hasCurrentTrunkedSiteEvidence(ControlChannelQualitySnapshot snapshot,
                                                  TrunkedSiteEvidence evidence)
    {
        if(snapshot == null || snapshot.channel() == null || evidence == null ||
            evidence.channel() != snapshot.channel())
        {
            return false;
        }

        DecoderType decoderType = decoderType(snapshot.channel());
        return snapshot.channel().getDecodeConfiguration() == evidence.decodeConfiguration() &&
            decoderType == evidence.decoderType();
    }

    /**
     * Identifies control-channel decoders that publish the shared trunked-site quality contract.  The existing
     * GUID-keyed quality bucket table is structurally protocol-neutral; its historical P25 name is retained for
     * deployed-schema compatibility.
     */
    static boolean isTrunkedControlChannelQuality(ControlChannelQualitySnapshot snapshot)
    {
        DecoderType decoderType = snapshot != null ? decoderType(snapshot.channel()) : null;
        return decoderType == DecoderType.P25_PHASE1 || decoderType == DecoderType.P25_PHASE2 ||
            decoderType == DecoderType.DMR || decoderType == DecoderType.NXDN;
    }

    /**
     * P25 preserves its existing persistence behavior.  DMR and NXDN monitors can also be attached to conventional
     * channels, so their samples are retained only after useful trunked-site metadata has identified the receiver.
     */
    static boolean shouldPersistControlChannelQuality(ControlChannelQualitySnapshot snapshot,
                                                       boolean observedTrunkedSite)
    {
        if(!isTrunkedControlChannelQuality(snapshot))
        {
            return false;
        }

        DecoderType decoderType = snapshot != null ? decoderType(snapshot.channel()) : null;

        if(decoderType == DecoderType.P25_PHASE1 || decoderType == DecoderType.P25_PHASE2)
        {
            return true;
        }

        return observedTrunkedSite;
    }

    private static DecoderType decoderType(Channel channel)
    {
        return channel != null && channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;
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
        boolean collectionEnabled = preference.isStatsLoggingEnabled();
        boolean collectionWasEnabled = mCollectionEnabled;
        mCollectionEnabled = collectionEnabled;

        Path databasePath = P25ActivityLogPath.getDatabasePath(mUserPreferences);
        int retentionDays = preference.getStatsLoggingRetentionDays();
        boolean detailedEventHistoryEnabled = preference.isStatsDetailedHistoryEnabled();

        if(mWriter != null && databasePath.equals(mCurrentDatabasePath) &&
            mWriter.getStatus().state() != P25ActivityLogStatus.State.FAILED &&
            mWriter.getStatus().state() != P25ActivityLogStatus.State.STOPPED)
        {
            mWriter.setRetentionDays(retentionDays);
            mWriter.setDetailedEventHistoryEnabled(detailedEventHistoryEnabled);

            if(collectionWasEnabled && !collectionEnabled)
            {
                clearDedupeKeys();
                mObservedTrunkedSites.clear();
            }

            return;
        }

        stopWriter();
        mCurrentDatabasePath = databasePath;
        mWriter = new P25ActivityLogWriter(databasePath, retentionDays, detailedEventHistoryEnabled,
            this::notifyActivityCommitted);
        mWriter.start();
        mLog.info("Stats database writer started for collection and retention maintenance [{}]", databasePath);
    }

    private synchronized void stopWriter()
    {
        if(mWriter != null)
        {
            mWriter.close();
            mLastWriterStatus = mWriter.getStatus();
            mWriter = null;
            mCurrentDatabasePath = null;

            clearDedupeKeys();
            mObservedTrunkedSites.clear();

            mLog.info("Stats database writer stopped");
        }
    }

    private void receiveDecodeEvent(Channel channel, IDecodeEvent event)
    {
        P25ActivityLogWriter writer = getCollectionWriter();

        if(writer == null)
        {
            return;
        }

        P25ActivityLogRecords.ActivityEvent record = mMapper.map(channel, event);

        if(record != null)
        {
            boolean logActivity = shouldLog(record);

            if(logActivity)
            {
                writer.enqueue(record);
            }
            else
            {
                P25ActivityLogRecords.TalkerAliasUpdate talkerAlias = mMapper.mapTalkerAliasUpdate(record);

                if(talkerAlias != null && shouldLogTalkerAlias(talkerAlias))
                {
                    writer.enqueue(talkerAlias);
                }
            }
        }
    }

    @Subscribe
    public void receiveCallStart(P25CallStartEvent event)
    {
        P25ActivityLogWriter writer = getCollectionWriter();

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
        P25ActivityLogWriter writer = getCollectionWriter();

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
    public void receiveTalkerAlias(P25TalkerAliasEvent event)
    {
        P25ActivityLogWriter writer = getCollectionWriter();

        if(writer == null)
        {
            return;
        }

        P25ActivityLogRecords.TalkerAliasUpdate update = mMapper.map(event);

        if(update != null && shouldLogTalkerAlias(update))
        {
            writer.enqueue(update);
        }
    }

    @Override
    public void receiveSiteMetadata(SiteMetadataEvent event)
    {
        P25ActivityLogWriter writer = getCollectionWriter();

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

    @Override
    public void receiveProtocolSiteMetadata(ProtocolSiteMetadataEvent event)
    {
        P25ActivityLogWriter writer = getCollectionWriter();

        if(writer == null)
        {
            return;
        }

        var snapshot = TrunkedSiteMetadataMapper.map(event);
        Channel channel = event != null ? event.channel() : null;
        String guid = channel != null ? channel.getRadresGuid() : null;

        if(snapshot != null)
        {
            if(snapshot.guid() != null && !snapshot.guid().isBlank())
            {
                mObservedTrunkedSites.put(snapshot.guid(),
                    new TrunkedSiteEvidence(channel,
                        channel != null ? channel.getDecodeConfiguration() : null, decoderType(channel)));
            }

            writer.enqueue(new P25ActivityLogRecords.TrunkedSiteSnapshot(
                snapshot.observedAtEpochMilliseconds(), snapshot));
        }
        else if(guid != null && !guid.isBlank())
        {
            mObservedTrunkedSites.remove(guid);
        }
    }

    record TrunkedSiteEvidence(Channel channel, DecodeConfiguration decodeConfiguration, DecoderType decoderType)
    {
    }

    /**
     * Routes runtime database maintenance through the same connection and background writer used for observations.
     */
    @Subscribe
    public void receiveMaintenanceRequest(StatsDatabaseMaintenanceRequest request)
    {
        P25ActivityLogWriter writer = mWriter;

        if(writer != null)
        {
            writer.submitMaintenance(request);
        }
        else if(request != null)
        {
            request.result().completeExceptionally(
                new IllegalStateException("Statistics database writer is not available"));
        }
    }

    private P25ActivityLogWriter getCollectionWriter()
    {
        return mCollectionEnabled ? mWriter : null;
    }

    private void clearDedupeKeys()
    {
        synchronized(mRecentDedupeKeys)
        {
            mRecentDedupeKeys.clear();
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

    private boolean shouldLogTalkerAlias(P25ActivityLogRecords.TalkerAliasUpdate update)
    {
        long now = System.currentTimeMillis();
        String key = String.join("|", "talker-alias", update.contextKey(), Integer.toString(update.radioId()),
            update.talkerAlias());

        synchronized(mRecentDedupeKeys)
        {
            cleanupDedupeKeys(now);
            Long previous = mRecentDedupeKeys.put(key, now);
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
            else
            {
                break;
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

    /**
     * Configured preferences and current effective writer health for the web status API and desktop diagnostics.
     */
    public synchronized P25ActivityLogStatus getStatus()
    {
        ApplicationPreference preference = mUserPreferences.getApplicationPreference();
        boolean summaryConfigured = preference.isStatsLoggingEnabled();
        boolean historyConfigured = preference.isStatsDetailedHistoryEnabled();
        P25ActivityLogWriter.WriterStatus writerStatus = mWriter != null ? mWriter.getStatus() : mLastWriterStatus;
        P25ActivityLogStatus.State state = summaryConfigured ? P25ActivityLogStatus.State.STOPPED :
            P25ActivityLogStatus.State.DISABLED;
        long lastSuccessfulWriteMs = 0;
        long recordsWritten = 0;
        long recordsDropped = 0;
        String lastError = null;
        boolean historyWriterEnabled = false;

        if(writerStatus != null)
        {
            if(summaryConfigured || writerStatus.state() == P25ActivityLogStatus.State.FAILED)
            {
                state = writerStatus.state();
            }

            lastSuccessfulWriteMs = writerStatus.lastSuccessfulWriteMs();
            recordsWritten = writerStatus.recordsWritten();
            recordsDropped = writerStatus.recordsDropped();
            lastError = writerStatus.lastError();
            historyWriterEnabled = writerStatus.detailedHistoryEnabled();
        }

        boolean summaryActive = summaryConfigured && state == P25ActivityLogStatus.State.RUNNING;
        boolean historyActive = summaryActive && historyConfigured && historyWriterEnabled;
        return new P25ActivityLogStatus(summaryConfigured, historyConfigured, summaryActive, historyActive,
            preference.getStatsLoggingRetentionDays(), state,
            P25ActivityLogPath.getDatabasePath(mUserPreferences).toString(), lastSuccessfulWriteMs,
            recordsWritten, recordsDropped, lastError);
    }

    private void notifyActivityCommitted(List<Long> rowIds)
    {
        for(P25ActivityCommitListener listener: mCommitListeners)
        {
            listener.activityCommitted(rowIds);
        }
    }

}
