/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.channel.metadata.ChannelMetadata;
import io.github.dsheirer.channel.metadata.ChannelMetadataField;
import io.github.dsheirer.channel.metadata.IChannelMetadataUpdateListener;
import io.github.dsheirer.channel.quality.ControlChannelQualitySnapshot;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.configuration.DecoderTypeConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.decoder.ChannelStateIdentifier;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataEvent;
import io.github.dsheirer.metadata.site.SiteMetadataSnapshot;
import io.github.dsheirer.metadata.site.TrunkedSiteMetadataClassifier;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.channel.DMRChannel;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.nxdn.telemetry.NXDNNetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.preference.encryption.VoiceEncryptionDisplay;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Session-only browser activity model that keeps stable rows independent from temporary traffic chains.
 */
public class ChannelActivityModel implements IChannelMetadataUpdateListener, AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(ChannelActivityModel.class);
    private static final int CONTROL_DECODE_HANG_MILLISECONDS = 15000;
    private static final int ACTIVITY_SWEEPER_INTERVAL_MILLISECONDS = 250;
    private static final int INGRESS_CAPACITY = 1024;
    private static final int LIFECYCLE_INGRESS_RESERVE = 64;
    private static final int CHANNEL_STARTED = 1;
    private static final int CHANNEL_STOPPED = 2;
    private static final int CONTROL_QUALITY = 3;
    private static final int AUDIO_CALL = 4;
    private static final int METADATA_UPDATED = 5;
    private static final int P25_CURRENT_CONTROL = 6;
    private static final int SITE_METADATA = 7;
    private static final int PROTOCOL_SITE_METADATA = 8;
    private static final int TRUNKED_TRAFFIC = 9;
    private static final int TRUNKED_CURRENT_CONTROL = 10;
    private static final int TRAFFIC_ENCRYPTION = 11;
    private static final int TRAFFIC_TALKER_ALIAS = 12;
    private static final int CONFIGURATION_CHANGED = 13;

    private final AliasModel mAliasModel;
    private final NowPlayingPreference mNowPlayingPreference;
    private final ChannelActivityTableState mConventionalTable;
    private final Map<Channel,ChannelActivityTableState> mTrunkedTables = new IdentityHashMap<>();
    private final Map<Channel,SiteActivitySession> mSiteSessions = new IdentityHashMap<>();
    private final Map<ChannelMetadata,ChannelActivityRow> mMetadataRows = new IdentityHashMap<>();
    private final Map<ChannelActivityRow,ChannelActivityTableState> mRowTables = new IdentityHashMap<>();
    private final Map<ChannelActivityRow,ExpiringRow> mPendingControlIdleRows = new IdentityHashMap<>();
    private final Map<ChannelActivityTableState,Set<ChannelActivityRow>> mPendingTableRefreshes = new IdentityHashMap<>();
    private final Map<Channel,SiteIdentity> mSiteIdentities = new IdentityHashMap<>();
    private final Map<Channel,Object> mActiveIncarnations = new IdentityHashMap<>();
    private final Map<String,ChannelActivitySnapshot> mLatestSnapshotsById = new HashMap<>();
    private final List<Listener<ChannelActivityEvent>> mActivityListeners = new CopyOnWriteArrayList<>();
    private final ChannelActivityIngressQueue mIngress;
    private final AtomicLong mDroppedIngressCount = new AtomicLong();
    private final AtomicLong mDroppedLifecycleCount = new AtomicLong();
    private final AtomicLong mAcceptedIngressCount = new AtomicLong();
    private final AtomicLong mProcessedIngressCount = new AtomicLong();
    private final AtomicBoolean mLifecycleReconcileNeeded = new AtomicBoolean();
    private volatile List<ChannelActivityTableState> mTables;
    private volatile SnapshotSet mSnapshotSet = new SnapshotSet(0, List.of());
    private boolean mActivitySweeperRunning;
    private volatile boolean mWorkerRunning = true;
    private volatile boolean mClosed;
    private volatile Thread mWorker;
    private volatile Supplier<List<ActiveChannel>> mActiveChannelSupplier;

    private record ExpiringRow(ChannelActivityTableState table, Channel parentChannel, long expiresAt)
    {
    }

    private record RowReference(SiteActivitySession session, ChannelActivityTableState table, ChannelActivityRow row)
    {
    }

    private record SiteIdentity(Integer wacn, Integer system, Integer rfss, Integer site, Integer nac)
    {
        private boolean hasAny()
        {
            return wacn != null || system != null || rfss != null || site != null || nac != null;
        }
    }

    /**
     * Immutable authoritative renderer snapshot.  Revision changes whenever any table changes or is removed.
     */
    public record SnapshotSet(long revision, List<ChannelActivitySnapshot> tables)
    {
        public SnapshotSet
        {
            tables = tables != null ? List.copyOf(tables) : List.of();
        }
    }

    /**
     * One active processing-chain incarnation used to reconcile dropped start/stop observations worker-side.
     */
    public record ActiveChannel(Channel channel, List<ChannelMetadata> metadata, Object incarnation)
    {
        public ActiveChannel
        {
            metadata = metadata != null ? List.copyOf(metadata) : List.of();
        }
    }

    public ChannelActivityModel(AliasModel aliasModel, NowPlayingPreference nowPlayingPreference)
    {
        this(aliasModel, nowPlayingPreference, INGRESS_CAPACITY, LIFECYCLE_INGRESS_RESERVE);
    }

    ChannelActivityModel(AliasModel aliasModel, NowPlayingPreference nowPlayingPreference, int ingressCapacity,
                         int lifecycleIngressReserve)
    {
        mAliasModel = aliasModel;
        mNowPlayingPreference = nowPlayingPreference;
        mIngress = new ChannelActivityIngressQueue(ingressCapacity, lifecycleIngressReserve);
        mConventionalTable = new ChannelActivityTableState("Conventional", null, this::tableSnapshotUpdated);
        updateTablesSnapshot();
        mWorker = new ObserverThreadFactory("channel activity").newThread(this::runWorker);
        mWorker.start();
    }

    public ChannelActivityTableState getConventionalTable()
    {
        return mConventionalTable;
    }

    /**
     * Current activity tables in display order for a renderer that is attaching after the model was populated.
     */
    public List<ChannelActivityTableState> getTables()
    {
        return mTables;
    }

    public SnapshotSet getSnapshotSet()
    {
        return mSnapshotSet;
    }

    public void setActiveChannelSupplier(Supplier<List<ActiveChannel>> activeChannelSupplier)
    {
        mActiveChannelSupplier = activeChannelSupplier;
        mLifecycleReconcileNeeded.set(true);
        signalWorker();
    }

    private boolean offer(int operation, Object first, Object second, Object third, Object fourth, Object fifth,
                          Object sixth, long value)
    {
        if(mClosed)
        {
            return false;
        }

        boolean accepted = mIngress.offer(operation, isLifecycleOperation(operation), first, second,
            third, fourth, fifth, sixth, value);

        if(accepted)
        {
            mAcceptedIngressCount.incrementAndGet();
            signalWorker();
        }
        else
        {
            mDroppedIngressCount.incrementAndGet();

            if(isLifecycleOperation(operation))
            {
                mDroppedLifecycleCount.incrementAndGet();
                mLifecycleReconcileNeeded.set(true);
                signalWorker();
            }
        }

        return accepted;
    }

    private static boolean isLifecycleOperation(int operation)
    {
        return operation == CHANNEL_STARTED || operation == CHANNEL_STOPPED;
    }

    private void signalWorker()
    {
        Thread worker = mWorker;

        if(worker != null)
        {
            LockSupport.unpark(worker);
        }
    }

    public void addActivityListener(Listener<ChannelActivityEvent> listener)
    {
        if(listener != null)
        {
            mActivityListeners.add(listener);
            long revision = mSnapshotSet.revision();

            for(ChannelActivitySnapshot snapshot: mSnapshotSet.tables())
            {
                deliver(listener, new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT, snapshot,
                    revision), "activity");
            }
        }
    }

    public void removeActivityListener(Listener<ChannelActivityEvent> listener)
    {
        mActivityListeners.remove(listener);
    }

    int getActivityListenerCount()
    {
        return mActivityListeners.size();
    }

    private void notifyActivityListeners(ChannelActivityEvent.Operation operation, ChannelActivitySnapshot snapshot)
    {
        if(snapshot != null)
        {
            ChannelActivityEvent event = new ChannelActivityEvent(operation, snapshot, mSnapshotSet.revision());

            for(Listener<ChannelActivityEvent> listener: mActivityListeners)
            {
                deliver(listener, event, "activity");
            }
        }
    }

    private void tableSnapshotUpdated(ChannelActivitySnapshot snapshot)
    {
        if(snapshot != null)
        {
            mLatestSnapshotsById.put(snapshot.tableId(), snapshot);
            publishSnapshotSet();
            notifyActivityListeners(ChannelActivityEvent.Operation.UPSERT, snapshot);
        }
    }

    private void tableSnapshotRemoved(ChannelActivitySnapshot snapshot)
    {
        if(snapshot != null)
        {
            mLatestSnapshotsById.remove(snapshot.tableId());
            publishSnapshotSet();
            notifyActivityListeners(ChannelActivityEvent.Operation.REMOVE, snapshot);
        }
    }

    private void publishSnapshotSet()
    {
        List<ChannelActivitySnapshot> snapshots = new ArrayList<>(mLatestSnapshotsById.values());
        snapshots.sort(java.util.Comparator
            .comparingInt((ChannelActivitySnapshot snapshot) -> "conventional".equals(snapshot.tableId()) ? 0 : 1)
            .thenComparing(ChannelActivitySnapshot::tableId));
        mSnapshotSet = new SnapshotSet(mSnapshotSet.revision() + 1, snapshots);
    }

    private void removeTrunkedTable(ChannelActivityTableState tableState)
    {
        if(tableState != null && tableState.getOwnerChannel() != null)
        {
            Channel owner = tableState.getOwnerChannel();
            ChannelActivitySnapshot removedSnapshot = tableState.getLatestSnapshot();
            mTrunkedTables.remove(owner);
            mSiteSessions.remove(owner);
            updateTablesSnapshot();
            tableSnapshotRemoved(removedSnapshot);

            for(ChannelActivityRow row: tableState.getRows())
            {
                clearTrafficGrantAgeOut(row);
                cancelPendingControlIdle(row);
                mRowTables.remove(row);
            }

            mPendingTableRefreshes.remove(tableState);
            stopActivitySweeperIfIdle();
        }
    }

    public void channelStarted(Channel channel, List<ChannelMetadata> metadataList)
    {
        channelStarted(channel, metadataList, channel);
    }

    public void channelStarted(Channel channel, List<ChannelMetadata> metadataList, Object incarnation)
    {
        if(channel == null)
        {
            return;
        }

        offer(CHANNEL_STARTED, channel, metadataList, incarnation, null, null, null, 0);
    }

    private void processChannelStarted(Channel channel, List<ChannelMetadata> metadataList, Object incarnation)
    {
        mActiveIncarnations.put(channel, incarnation != null ? incarnation : channel);

        if(channel.isTrafficChannel())
        {
            return;
        }

        if(isConfiguredTrunkedControlParent(channel))
        {
            ensureConfiguredControlRow(channel, "channel-started-control");
        }
        else if(metadataList != null)
        {
            addConventionalRows(channel, metadataList, "channel-started-conventional");
        }
    }

    public void channelStopped(Channel channel)
    {
        if(channel == null)
        {
            return;
        }

        offer(CHANNEL_STOPPED, channel, null, null, null, null, null, 0);
    }

    private void processChannelStopped(Channel channel)
    {
        mActiveIncarnations.remove(channel);

        for(RowReference reference: getRowsForStoppedChannel(channel))
        {
            ChannelActivityRow row = reference.row();
            ChannelActivityTableState table = reference.table();

            if(channel.isTrafficChannel())
            {
                if(row.getRole() == ChannelActivityRow.Role.TRAFFIC && table != null &&
                    table.getOwnerChannel() != null)
                {
                    clearVoiceQualityOnIdle(row);
                    reference.session().releaseTrafficChannel(channel, row);
                    row.setDecoder(getDecoder(table.getOwnerChannel()));
                    table.refresh(row);
                }
            }
            else
            {
                clearTrafficGrantAgeOut(row);
                cancelPendingControlIdle(row);
                row.clearControlQuality();
                row.clearVoiceQuality();

                if(table == mConventionalTable)
                {
                    removeConventionalRow(row);
                    continue;
                }
                else if(row.isControlRow())
                {
                    markConfiguredControl(row, channel);
                }
                else
                {
                    setIdle(row);
                }

                table.refresh(row);
            }
        }

        if(!channel.isTrafficChannel())
        {
            ChannelActivityTableState trunked = mTrunkedTables.get(channel);

            if(trunked != null)
            {
                setControlActive(trunked, false);
            }
        }
    }

    public void receiveControlChannelQuality(ControlChannelQualitySnapshot snapshot)
    {
        if(snapshot == null || snapshot.channel() == null || snapshot.frequencyHz() <= 0)
        {
            return;
        }

        offer(CONTROL_QUALITY, snapshot, null, null, null, null, null, 0);
    }

    private void processControlChannelQuality(ControlChannelQualitySnapshot snapshot)
    {
        SiteActivitySession session = isConfiguredTrunkedControlParent(snapshot.channel()) ?
            getOrCreateSiteSession(snapshot.channel()) : mSiteSessions.get(snapshot.channel());
        ChannelActivityTableState table = session != null ? session.getTableState() : null;

        if(table == null)
        {
            return;
        }

        ChannelActivityRow row = table.get(session.controlKey(snapshot.frequencyHz()));

        if(snapshot.active() && (row == null || row.getRole() != ChannelActivityRow.Role.CURRENT_CONTROL ||
            !table.isControlActive()))
        {
            updateCurrentControl(session, table, snapshot.channel(), snapshot.frequencyHz());
            row = table.get(session.controlKey(snapshot.frequencyHz()));
        }
        else if(snapshot.active())
        {
            cancelPendingControlIdle(row);
            scheduleControlIdle(row, table, snapshot.channel());
        }

        if(row != null)
        {
            if(snapshot.active())
            {
                row.setControlQuality(snapshot.signalDbfs(), snapshot.decodeHealthPercent(), snapshot.validFrames(),
                    snapshot.invalidFrames(), snapshot.correctedBits(), snapshot.syncLossBits(), snapshot.droppedBits(),
                    snapshot.observedAtMs());
            }
            else
            {
                row.clearControlQuality();
            }

            table.refresh(row);
        }
    }

    /**
     * Carries transient 20 millisecond voice-frame diagnostics to the matching Systems row.  The first frame, each
     * subsequent second, and the final result refresh the view; the per-frame audio path never writes these values to
     * the activity database.
     */
    public void receiveAudioCallEvent(Channel channel, AudioCallEvent event)
    {
        if(channel == null || event == null || event.snapshot() == null ||
            event.snapshot().callId() == null)
        {
            return;
        }

        AudioCallSnapshot snapshot = event.snapshot();
        boolean created = event.eventType() == AudioCallEventType.CALL_CREATED;
        boolean completed = event.eventType() == AudioCallEventType.CALL_COMPLETED;
        VoiceCallQuality quality = snapshot.voiceCallQuality();
        boolean hasMeasurements = quality != null && quality.hasMeasurements();
        long observedFrames = hasMeasurements ? quality.observedFrameCount() : 0;
        boolean sampledAudioFrame = hasMeasurements && event.eventType() == AudioCallEventType.AUDIO_FRAME &&
            (observedFrames == 1 || observedFrames % 50 == 0);

        if(!created && !completed && !sampledAudioFrame)
        {
            return;
        }

        offer(AUDIO_CALL, channel, event, null, null, null, null, 0);
    }

    private void processAudioCallEvent(Channel channel, AudioCallEvent event)
    {
        AudioCallSnapshot snapshot = event.snapshot();
        boolean created = event.eventType() == AudioCallEventType.CALL_CREATED;
        boolean completed = event.eventType() == AudioCallEventType.CALL_COMPLETED;
        VoiceCallQuality quality = snapshot.voiceCallQuality();
        boolean hasMeasurements = quality != null && quality.hasMeasurements();
        ChannelActivityRow row = findVoiceQualityRow(channel, snapshot);

        if(row == null)
        {
            return;
        }

            /*
             * A linked segment may not carry diagnostic observations. Transfer ownership at creation while retaining
             * the prior segment's displayed quality, so any later terminal completion still belongs to this row.
             */
        if(created)
        {
            if(snapshot.linkedCallId() != null && snapshot.linkedCallId().equals(row.getVoiceCallId()) &&
                row.getVoiceCallQuality() != null)
            {
                row.setVoiceQuality(snapshot.callId(), row.getVoiceCallQuality());
                refreshVoiceQualityRow(row);
            }

            return;
        }

            /*
             * CALL_COMPLETED is also emitted for linked one-minute audio segment rollovers. Preserve the live value
             * when a continuation is expected, and require exact ownership so a delayed completion cannot overwrite
             * a newer call before that call's first sampled voice frame.
             */
        if(completed && !snapshot.callId().equals(row.getVoiceCallId()))
        {
            return;
        }

        if(completed && !event.continuationExpected() &&
            mNowPlayingPreference.isClearVoiceDecodeQualityOnCallEnd())
        {
            row.clearVoiceQuality();
        }
        else if(hasMeasurements)
        {
            row.setVoiceQuality(snapshot.callId(), quality);
        }

        refreshVoiceQualityRow(row);
    }

    private void refreshVoiceQualityRow(ChannelActivityRow row)
    {
        ChannelActivityTableState table = mRowTables.get(row);

        if(table != null)
        {
            table.refresh(row);
        }
    }

    private ChannelActivityRow findVoiceQualityRow(Channel channel, AudioCallSnapshot snapshot)
    {
        long frequency = getFrequency(snapshot.identifierCollection());
        Integer timeslot = snapshot.timeslot() > 0 ? snapshot.timeslot() : null;

        for(ChannelActivityRow row: mConventionalTable.getRows())
        {
            if(row.getChannel() == channel && matchesVoiceChannel(row, frequency, timeslot))
            {
                return row;
            }
        }

        for(SiteActivitySession session: mSiteSessions.values())
        {
            for(ChannelActivityRow row: session.getTrafficRows())
            {
                if(row.getChannel() == channel && matchesVoiceChannel(row, frequency, timeslot))
                {
                    return row;
                }
            }
        }

        return null;
    }

    private boolean matchesVoiceChannel(ChannelActivityRow row, long frequency, Integer timeslot)
    {
        Integer rowTimeslot = row.getTimeslot() != null && row.getTimeslot() > 0 ? row.getTimeslot() : null;
        return (frequency <= 0 || row.getFrequency() == frequency) && java.util.Objects.equals(rowTimeslot, timeslot);
    }

    private long getFrequency(IdentifierCollection identifiers)
    {
        Identifier<?> identifier = identifiers != null ? identifiers.getIdentifier(IdentifierClass.CONFIGURATION,
            Form.CHANNEL_FREQUENCY, Role.ANY) : null;
        return identifier instanceof FrequencyConfigurationIdentifier frequency && frequency.getValue() != null ?
            frequency.getValue() : 0L;
    }

    @Override
    public void updated(ChannelMetadata channelMetadata, ChannelMetadataField channelMetadataField)
    {
        if(mClosed)
        {
            return;
        }

        if(channelMetadata != null)
        {
            offer(METADATA_UPDATED, channelMetadata, channelMetadataField, null, null, null, null, 0);
        }
    }

    private void processMetadataUpdated(ChannelMetadata channelMetadata)
    {
        ChannelActivityRow row = mMetadataRows.get(channelMetadata);

        if(row != null)
        {
            updateFromMetadata(row, channelMetadata, row.getChannel());
            mConventionalTable.refresh(row);
        }
    }

    public void p25CurrentControl(Channel parentChannel, long frequency)
    {
        if(parentChannel == null || frequency <= 0)
        {
            return;
        }

        offer(P25_CURRENT_CONTROL, parentChannel, null, null, null, null, null, frequency);
    }

    private void processP25CurrentControl(Channel parentChannel, long frequency)
    {
        if(!isP25TrunkedControlParent(parentChannel))
        {
            return;
        }

        sweepActivityExpirations();
        SiteActivitySession session = getOrCreateSiteSession(parentChannel);
        ChannelActivityTableState table = session != null ? session.getTableState() : null;

        if(table == null)
        {
            return;
        }

        expireTrafficRows(session, table, parentChannel);
        ChannelActivityRow row = session.configuredControl(frequency);
        rememberRow(table, row);
        row.setDecoder(getDecoder(parentChannel));
        table.refresh(row);
    }

    public void receiveSiteMetadata(SiteMetadataEvent event)
    {
        if(event == null || event.channel() == null || event.snapshot() == null)
        {
            return;
        }

        offer(SITE_METADATA, event, null, null, null, null, null, 0);
    }

    private void processSiteMetadata(SiteMetadataEvent event)
    {
        Channel parentChannel = event.channel();
        P25NetworkConfigurationSnapshot snapshot = event.snapshot();

        if(!parentChannel.isStandardChannel() || !isP25TrunkedControlParent(parentChannel))
        {
            return;
        }

        sweepActivityExpirations();
        SiteIdentity identity = getSiteIdentity(snapshot);

        if(identity != null && identity.hasAny())
        {
            mSiteIdentities.put(parentChannel, identity);
            updateTrunkedTitle(parentChannel);
        }

        SiteActivitySession session = getOrCreateSiteSession(parentChannel);
        ChannelActivityTableState table = session != null ? session.getTableState() : null;

        if(table == null)
        {
            return;
        }

        table.setIdentifiers(identifierFields(snapshot));
        expireTrafficRows(session, table, parentChannel);
        Set<Long> promotedControlFrequencies = new HashSet<>();

        for(P25NetworkConfigurationSnapshot.Channel channel: list(snapshot.channels()))
        {
            if(channel == null || channel.downlink() == null || channel.downlink() <= 0)
            {
                continue;
            }

            if(channel.callsign() != null && !channel.callsign().isBlank())
            {
                for(ChannelActivityRow callsignRow: session.callsign(channel.downlink(), channel.callsign()))
                {
                    table.refresh(callsignRow);
                }
            }

            ChannelTag networkTag = ChannelTag.fromNetworkRole(channel.role());

            if(networkTag == ChannelTag.CURRENT_CONTROL)
            {
                promotedControlFrequencies.add(channel.downlink());
                SiteActivitySession.ControlUpdate update = session.currentControl(channel.downlink(),
                    channel.descriptor());
                ChannelActivityRow row = update.current();
                rememberRow(table, row);
                row.setDecoder(getDecoder(parentChannel));
                cancelPendingControlIdle(row);
                scheduleControlIdle(row, table, parentChannel);
                setControlActive(table, true);
                table.refresh(row);

                for(ChannelActivityRow demoted: update.demoted())
                {
                    cancelPendingControlIdle(demoted);
                    demoted.setDecoder(getDecoder(parentChannel));
                    table.refresh(demoted);
                }
            }
            else if(networkTag == ChannelTag.ALTERNATE_CONTROL)
            {
                promotedControlFrequencies.add(channel.downlink());
                ChannelActivityRow row = session.alternateControl(channel.downlink(), channel.descriptor());
                rememberRow(table, row);
                cancelPendingControlIdle(row);
                row.setDecoder(getDecoder(parentChannel));
                table.refresh(row);
            }
            else if(networkTag == ChannelTag.DATA_ANNOUNCED)
            {
                ChannelActivityRow row = session.announcedData(channel.downlink(), channel.descriptor());
                rememberRow(table, row);
                row.setDecoder(getDecoder(parentChannel));
                table.refresh(row);
            }
        }

        for(ChannelActivityRow row: session.reconcilePromotedControls(promotedControlFrequencies,
            getConfiguredFrequency(parentChannel)))
        {
            cancelPendingControlIdle(row);
            table.remove(row);
            mRowTables.remove(row);
        }
    }

    /**
     * Applies positively identified trunking metadata to an explicitly trunked DMR channel or an NXDN channel.
     * Conventional DMR is never promoted by over-the-air signaling.
     */
    public void receiveProtocolSiteMetadata(ProtocolSiteMetadataEvent event)
    {
        if(event == null)
        {
            return;
        }

        offer(PROTOCOL_SITE_METADATA, event, null, null, null, null, null, 0);
    }

    private void processProtocolSiteMetadata(ProtocolSiteMetadataEvent event)
    {
        if(!TrunkedSiteMetadataClassifier.isKnownTrunkingMetadata(event))
        {
            return;
        }

        Channel parentChannel = event.channel();

        if(parentChannel != null && parentChannel.getDecodeConfiguration() instanceof DecodeConfigDMR dmr &&
            !dmr.isTrunked())
        {
            return;
        }

        SiteActivitySession session = getOrCreateSiteSession(parentChannel);

        if(session == null)
        {
            return;
        }

        session.getTableState().setIdentifiers(identifierFields(event.snapshot()));

        removeConventionalRows(parentChannel);
        ensureConfiguredControlRowIfMissing(session, "protocol-site-metadata-control-seed");
    }

    public void p25TrafficGrant(Channel parentChannel, Channel trafficChannel, IChannelDescriptor channelDescriptor,
                                IdentifierCollection identifiers, DecodeEventType eventType)
    {
        if(parentChannel == null || !isP25TrunkedControlParent(parentChannel) || channelDescriptor == null ||
            channelDescriptor.getDownlinkFrequency() <= 0)
        {
            return;
        }

        trunkedTrafficEvent(parentChannel, trafficChannel, channelDescriptor, getTimeslot(channelDescriptor),
            identifiers, eventType, 0);
    }

    /**
     * Publishes a DMR or NXDN trunked call event into the shared Systems activity model. DMR requires explicit trunked
     * configuration; NXDN can still be promoted by positively identified metadata or traffic-manager activity.
     *
     * @param parentChannel control channel that owns the traffic-channel manager
     * @param trafficChannel allocated child channel, or null when the grant could not be allocated
     * @param channelDescriptor traffic channel/frequency descriptor
     * @param timeslot one-based TDMA timeslot, or null for FDMA
     * @param identifiers call identifiers
     * @param eventType voice/data call type
     * @param controlFrequency current control frequency, or zero when unknown
     */
    public void trunkedTrafficEvent(Channel parentChannel, Channel trafficChannel,
                                    IChannelDescriptor channelDescriptor, Integer timeslot,
                                    IdentifierCollection identifiers, DecodeEventType eventType,
                                    long controlFrequency)
    {
        if(parentChannel == null || channelDescriptor == null ||
            channelDescriptor.getDownlinkFrequency() <= 0)
        {
            return;
        }

        offer(TRUNKED_TRAFFIC, parentChannel, trafficChannel, channelDescriptor, timeslot, identifiers, eventType,
            controlFrequency);
    }

    private void processTrunkedTrafficEvent(Channel parentChannel, Channel trafficChannel,
                                            IChannelDescriptor channelDescriptor, Integer timeslot,
                                            IdentifierCollection identifiers, DecodeEventType eventType,
                                            long controlFrequency)
    {
        if(!isTrunkingCapableParent(parentChannel) || ChannelTag.fromService(eventType) == null)
        {
            return;
        }

        Integer normalizedTimeslot = timeslot != null && timeslot > 0 ? timeslot : null;
        sweepActivityExpirations();
        SiteActivitySession session = getOrCreateSiteSession(parentChannel);
        ChannelActivityTableState table = session != null ? session.getTableState() : null;

        if(table == null)
        {
            return;
        }

        removeConventionalRows(parentChannel);
        expireTrafficRows(session, table, parentChannel);
        if(controlFrequency > 0)
        {
            updateCurrentControl(session, table, parentChannel, controlFrequency);
        }
        else
        {
            ensureConfiguredControlRowIfMissing(table, parentChannel, "trunked-traffic-event-control-seed");
        }

        Channel rowChannel = trafficChannel != null ? trafficChannel : parentChannel;
        ChannelActivityRow row = session.traffic(trafficChannel, channelDescriptor, normalizedTimeslot);
        rememberRow(table, row);
        clearTrafficGrantAgeOut(row);
        boolean newCall = row.getState() == State.IDLE || isTargetChanged(row, identifiers);
        boolean wasEncrypted = !newCall && row.getState() == State.ENCRYPTED;

        if(newCall)
        {
            row.clearCallDetails();
            row.clearVoiceQuality();
        }

        row.setDecoder(getDecoder(rowChannel));
        updateCallDetails(row, identifiers, rowChannel);
        ChannelTag serviceTag = ChannelTag.fromService(eventType);

        if(serviceTag != null)
        {
            session.addTag(channelDescriptor.getDownlinkFrequency(), serviceTag);
        }

        State state = getState(eventType);

        if(state == State.ENCRYPTED && row.getEncryptionDetails() == null)
        {
            row.setEncryptionDetails(VoiceEncryptionDisplay.ENCRYPTED);
        }

        row.setState(getStickyTrafficState(row, state, wasEncrypted));
        table.refresh(row);
        scheduleTrafficGrantAgeOut(row);
    }

    /**
     * Updates an already-observed DMR or NXDN trunked site when its control frequency changes.  This deliberately does
     * not create a table by itself, which keeps conventional DMR/NXDN channels in the Conventional table.
     */
    public void trunkedCurrentControl(Channel parentChannel, long frequency)
    {
        if(parentChannel == null || frequency <= 0)
        {
            return;
        }

        offer(TRUNKED_CURRENT_CONTROL, parentChannel, null, null, null, null, null, frequency);
    }

    private void processTrunkedCurrentControl(Channel parentChannel, long frequency)
    {
        if(!isTrunkingCapableParent(parentChannel))
        {
            return;
        }

        SiteActivitySession session = mSiteSessions.get(parentChannel);
        ChannelActivityTableState table = session != null ? session.getTableState() : null;

        if(table != null)
        {
            updateCurrentControl(session, table, parentChannel, frequency);
        }
    }

    public void p25TrafficEncryptionDetails(Channel parentChannel, IChannelDescriptor channelDescriptor,
                                            IdentifierCollection identifiers, DecodeEventType eventType)
    {
        if(parentChannel == null || channelDescriptor == null ||
            channelDescriptor.getDownlinkFrequency() <= 0)
        {
            return;
        }

        offer(TRAFFIC_ENCRYPTION, parentChannel, channelDescriptor, identifiers, eventType, null, null, 0);
    }

    private void processTrafficEncryptionDetails(Channel parentChannel, IChannelDescriptor channelDescriptor,
                                                 IdentifierCollection identifiers, DecodeEventType eventType)
    {
        if(!isP25TrunkedControlParent(parentChannel))
        {
            return;
        }

        long frequency = channelDescriptor.getDownlinkFrequency();
        Integer timeslot = getTimeslot(channelDescriptor);
        String encryptionDetails = VoiceEncryptionDisplay.format(identifiers);

        if(encryptionDetails == null)
        {
            return;
        }

        SiteActivitySession session = mSiteSessions.get(parentChannel);
        ChannelActivityTableState table = session != null ? session.getTableState() : null;

        if(table == null)
        {
            return;
        }

        ChannelActivityRow row = session.traffic(frequency, timeslot);

        if(row == null || !isTrafficState(row.getState()))
        {
            return;
        }

        if(encryptionDetails.equals(row.getEncryptionDetails()) && row.getState() == State.ENCRYPTED)
        {
            return;
        }

        row.setEncryptionDetails(encryptionDetails);
        row.setState(State.ENCRYPTED);
        table.refresh(row);
    }

    /**
     * Applies a talker alias decoded after the initial traffic grant to the active Systems row.
     */
    public void p25TrafficTalkerAlias(Channel parentChannel, IChannelDescriptor channelDescriptor,
                                      Identifier<?> talkerAlias)
    {
        if(parentChannel == null || channelDescriptor == null ||
            channelDescriptor.getDownlinkFrequency() <= 0 || talkerAlias == null)
        {
            return;
        }

        offer(TRAFFIC_TALKER_ALIAS, parentChannel, channelDescriptor, talkerAlias, null, null, null, 0);
    }

    private void processTrafficTalkerAlias(Channel parentChannel, IChannelDescriptor channelDescriptor,
                                           Identifier<?> talkerAlias)
    {
        if(!isP25TrunkedControlParent(parentChannel) || talkerAlias.getForm() != Form.TALKER_ALIAS)
        {
            return;
        }

        long frequency = channelDescriptor.getDownlinkFrequency();
        Integer timeslot = getTimeslot(channelDescriptor);
        SiteActivitySession session = mSiteSessions.get(parentChannel);
        ChannelActivityTableState table = session != null ? session.getTableState() : null;

        if(table == null)
        {
            return;
        }

        ChannelActivityRow row = session.traffic(frequency, timeslot);

        if(row == null || !isTrafficState(row.getState()) || talkerAlias.equals(row.getTalkerAlias()))
        {
            return;
        }

        row.setTalkerAlias(talkerAlias);
        table.refresh(row);
    }

    public void channelConfigurationChanged(Channel channel)
    {
        if(channel == null)
        {
            return;
        }

        offer(CONFIGURATION_CHANGED, channel, null, null, null, null, null, 0);
    }

    private void processChannelConfigurationChanged(Channel channel)
    {
        updateTrunkedTitle(channel);

        if(isConfiguredTrunkedControlParent(channel))
        {
            removeConventionalRows(channel);
            ensureConfiguredControlRow(channel, "channel-configuration-control-seed");
            reconcileConfiguredControlRows(channel);
        }
        else
        {
            ChannelActivityTableState trunkedTable = mTrunkedTables.get(channel);

            if(trunkedTable != null && channel.getDecodeConfiguration() instanceof DecodeConfigDMR)
            {
                removeTrunkedTable(trunkedTable);
            }

            for(ChannelActivityRow row: mConventionalTable.getRows())
            {
                if(row.getChannel() == channel)
                {
                    mConventionalTable.refresh(row);
                }
            }
        }
    }

    private void updateFromMetadata(ChannelActivityRow row, ChannelMetadata metadata, Channel channel)
    {
        if(metadata == null)
        {
            return;
        }

        row.setFrequency(getFrequency(metadata, channel));
        row.setTimeslot(metadata.hasTimeslot() ? metadata.getTimeslot() : null);

        ChannelStateIdentifier stateIdentifier = metadata.getChannelStateIdentifier();
        State state = stateIdentifier != null ? stateIdentifier.getValue() : State.IDLE;
        boolean newCall = row.getState() == State.IDLE && state != State.IDLE;
        boolean wasEncrypted = !newCall && row.getState() == State.ENCRYPTED;

        if(newCall)
        {
            row.clearCallDetails();
            row.clearVoiceQuality();
        }

        DecoderTypeConfigurationIdentifier decoderIdentifier = metadata.getDecoderTypeConfigurationIdentifier();
        row.setDecoder(decoderIdentifier != null ? decoderIdentifier.toString() : getDecoder(channel));

        row.setSource(metadata.getFromIdentifier());
        row.setSourceAliases(metadata.getFromIdentifierAliases());
        row.setTalkerAlias(metadata.getTalkerAliasIdentifier());
        row.setTarget(metadata.getToIdentifier());
        row.setTargetAliases(metadata.getToIdentifierAliases());
        State displayState = getStickyTrafficState(row, state, wasEncrypted);
        String encryptionDetails = VoiceEncryptionDisplay.format(metadata.getEncryptionIdentifier());

        if(encryptionDetails != null)
        {
            row.setEncryptionDetails(encryptionDetails);
        }
        else if(displayState == State.ENCRYPTED)
        {
            if(row.getEncryptionDetails() == null)
            {
                row.setEncryptionDetails(VoiceEncryptionDisplay.ENCRYPTED);
            }
        }
        else
        {
            row.setEncryptionDetails(null);
        }

        ChannelTag serviceTag = ChannelTag.fromService(state);

        if(serviceTag != null)
        {
            row.addTag(serviceTag);
        }

        row.setState(displayState);

        if(row.getState() == State.IDLE)
        {
            clearVoiceQualityOnIdle(row);

            if(!retainIdleCallDetails())
            {
                row.clearCallDetails();
            }
        }
    }

    private boolean isTargetChanged(ChannelActivityRow row, IdentifierCollection identifiers)
    {
        if(row != null && identifiers != null)
        {
            Identifier<?> target = identifiers.getToIdentifier();
            return target != null && row.getTarget() != null && !target.equals(row.getTarget());
        }

        return false;
    }

    private void updateCallDetails(ChannelActivityRow row, IdentifierCollection identifiers, Channel channel)
    {
        if(identifiers != null)
        {
            Identifier<?> source = identifiers.getFromIdentifier();
            Identifier<?> target = identifiers.getToIdentifier();
            Identifier<?> talkerAlias = identifiers.getIdentifier(IdentifierClass.USER, Form.TALKER_ALIAS, Role.FROM);
            boolean targetChanged = target != null && row.getTarget() != null && !target.equals(row.getTarget());

            if(target != null)
            {
                row.setTarget(target);
                row.setTargetAliases(getAliases(target, identifiers, channel));
            }

            if(source != null && (row.getSource() == null || targetChanged))
            {
                row.setSource(source);
                row.setSourceAliases(getAliases(source, identifiers, channel));
            }

            if(talkerAlias != null)
            {
                row.setTalkerAlias(talkerAlias);
            }

            String encryptionDetails = VoiceEncryptionDisplay.format(identifiers);

            if(encryptionDetails != null)
            {
                row.setEncryptionDetails(encryptionDetails);
            }
        }
    }

    private State getStickyTrafficState(ChannelActivityRow row, State state, boolean wasEncrypted)
    {
        if(state == State.IDLE)
        {
            return State.IDLE;
        }

        if(isTrafficState(state) && (state == State.ENCRYPTED || wasEncrypted ||
            (row != null && row.getEncryptionDetails() != null)))
        {
            return State.ENCRYPTED;
        }

        return state;
    }

    private boolean isTrafficState(State state)
    {
        return state == State.ACTIVE || state == State.CALL || state == State.DATA || state == State.ENCRYPTED;
    }

    private List<Alias> getAliases(Identifier<?> identifier, IdentifierCollection identifiers, Channel channel)
    {
        if(identifier == null || mAliasModel == null)
        {
            return Collections.emptyList();
        }

        AliasList aliasList = channel != null ? mAliasModel.getAliasListForChannel(channel) :
            mAliasModel.getAliasList(identifiers);

        return aliasList != null ? aliasList.getAliases(identifier) : Collections.emptyList();
    }

    private void setIdle(ChannelActivityRow row)
    {
        row.setState(State.IDLE);
        clearVoiceQualityOnIdle(row);

        if(!retainIdleCallDetails())
        {
            row.clearCallDetails();
        }
    }

    private void clearVoiceQualityOnIdle(ChannelActivityRow row)
    {
        if(row != null && mNowPlayingPreference.isClearVoiceDecodeQualityOnCallEnd())
        {
            row.clearVoiceQuality();
        }
    }

    private long scheduleTrafficGrantAgeOut(ChannelActivityRow row)
    {
        clearTrafficGrantAgeOut(row);

        if(row != null)
        {
            long expiresAt = System.currentTimeMillis() + getTrafficGrantAgeOutMilliseconds();
            row.setTrafficGrantExpiresAt(expiresAt);
            return expiresAt;
        }

        return -1;
    }

    private void applyTrafficGrantAgeOut(ChannelActivityRow row, ChannelActivityTableState table,
                                         Channel parentChannel)
    {
        if(row != null)
        {
            row.clearTrafficGrantExpiresAt();
            row.setChannel(parentChannel);
            row.setDecoder(getDecoder(parentChannel));

            setIdle(row);

            if(table != null)
            {
                queueRefresh(table, row);
            }
        }
    }

    private void clearTrafficGrantAgeOut(ChannelActivityRow row)
    {
        if(row != null)
        {
            row.clearTrafficGrantExpiresAt();
        }
    }

    private void expireTrafficRows(SiteActivitySession session, ChannelActivityTableState table, Channel parentChannel)
    {
        if(session == null || table == null)
        {
            return;
        }

        long now = System.currentTimeMillis();

        for(ChannelActivityRow row: session.getTrafficRows())
        {
            if(row.getTrafficGrantExpiresAt() > 0 && row.getTrafficGrantExpiresAt() <= now &&
                isTrafficState(row.getState()))
            {
                applyTrafficGrantAgeOut(row, table, parentChannel);
            }
        }

        flushQueuedRefreshes();
    }

    private void scheduleControlIdle(ChannelActivityRow row, ChannelActivityTableState table, Channel parentChannel)
    {
        cancelPendingControlIdle(row);

        if(row == null)
        {
            return;
        }

        mPendingControlIdleRows.put(row, new ExpiringRow(table, parentChannel,
            System.currentTimeMillis() + CONTROL_DECODE_HANG_MILLISECONDS));
        startActivitySweeper();
    }

    private void cancelPendingControlIdle(ChannelActivityRow row)
    {
        if(row != null)
        {
            mPendingControlIdleRows.remove(row);
            stopActivitySweeperIfIdle();
        }
    }

    private void startActivitySweeper()
    {
        mActivitySweeperRunning = true;
    }

    private void stopActivitySweeperIfIdle()
    {
        if(mPendingControlIdleRows.isEmpty())
        {
            mActivitySweeperRunning = false;
        }
    }

    private void sweepActivityExpirations()
    {
        long now = System.currentTimeMillis();

        Iterator<Map.Entry<ChannelActivityRow,ExpiringRow>> controlIterator =
            mPendingControlIdleRows.entrySet().iterator();

        while(controlIterator.hasNext())
        {
            Map.Entry<ChannelActivityRow,ExpiringRow> entry = controlIterator.next();

            if(entry.getValue().expiresAt() <= now)
            {
                ChannelActivityRow row = entry.getKey();
                ExpiringRow expiration = entry.getValue();
                controlIterator.remove();
                applyControlIdle(row, expiration.table(), expiration.parentChannel());
            }
        }

        flushQueuedRefreshes();
        stopActivitySweeperIfIdle();
    }

    private void applyControlIdle(ChannelActivityRow row, ChannelActivityTableState table, Channel parentChannel)
    {
        if(row != null && row.hasTag(ChannelTag.CURRENT_CONTROL) &&
            row.getState() == State.CONTROL)
        {
            markConfiguredControl(row, parentChannel);

            if(table != null)
            {
                setControlActive(table, false);
                queueRefresh(table, row);
            }
        }
    }

    private void queueRefresh(ChannelActivityTableState table, ChannelActivityRow row)
    {
        if(table != null && row != null)
        {
            mPendingTableRefreshes.computeIfAbsent(table,
                key -> Collections.newSetFromMap(new IdentityHashMap<>())).add(row);
        }
    }

    private void flushQueuedRefreshes()
    {
        if(mPendingTableRefreshes.isEmpty())
        {
            return;
        }

        Map<ChannelActivityTableState,Set<ChannelActivityRow>> refreshes =
            new IdentityHashMap<>(mPendingTableRefreshes);
        mPendingTableRefreshes.clear();

        for(Map.Entry<ChannelActivityTableState,Set<ChannelActivityRow>> entry: refreshes.entrySet())
        {
            entry.getKey().refresh(entry.getValue());
        }
    }

    private void rememberRow(ChannelActivityTableState table, ChannelActivityRow row)
    {
        if(table != null && row != null)
        {
            mRowTables.put(row, table);
        }
    }

    private List<RowReference> getRowsForStoppedChannel(Channel channel)
    {
        if(channel == null)
        {
            return List.of();
        }

        List<RowReference> references = new ArrayList<>();

        if(!channel.isTrafficChannel())
        {
            for(ChannelActivityRow row: mConventionalTable.getRows())
            {
                if(row.getChannel() == channel)
                {
                    references.add(new RowReference(null, mConventionalTable, row));
                }
            }
        }

        for(SiteActivitySession session: new ArrayList<>(mSiteSessions.values()))
        {
            ChannelActivityTableState table = session.getTableState();

            for(ChannelActivityRow row: session.getRowsForStoppedChannel(channel))
            {
                references.add(new RowReference(session, table, row));
            }
        }

        return references;
    }

    private void removeConventionalRow(ChannelActivityRow row)
    {
        if(row != null)
        {
            mConventionalTable.remove(row);
            mRowTables.remove(row);
            mMetadataRows.entrySet().removeIf(entry -> entry.getValue() == row);
        }
    }

    private void removeConventionalRows(Channel channel)
    {
        if(channel != null)
        {
            for(ChannelActivityRow row: new ArrayList<>(mConventionalTable.getRows()))
            {
                if(row.getChannel() == channel)
                {
                    removeConventionalRow(row);
                }
            }
        }
    }

    private void updateCurrentControl(SiteActivitySession session, ChannelActivityTableState table,
                                      Channel parentChannel, long frequency)
    {
        SiteActivitySession.ControlUpdate update = session.currentControl(frequency, null);
        ChannelActivityRow current = update.current();

        if(current != null)
        {
            rememberRow(table, current);
            current.setDecoder(getDecoder(parentChannel));
            setControlActive(table, true);
            table.refresh(current);
            scheduleControlIdle(current, table, parentChannel);
        }

        for(ChannelActivityRow demoted: update.demoted())
        {
            demoted.setDecoder(getDecoder(parentChannel));
            table.refresh(demoted);
        }
    }

    private void addConventionalRows(Channel channel, List<ChannelMetadata> metadataList, String action)
    {
        if(channel == null || metadataList == null)
        {
            return;
        }

        for(ChannelMetadata metadata: metadataList)
        {
            long frequency = getFrequency(metadata, channel);
            Integer timeslot = metadata.hasTimeslot() ? metadata.getTimeslot() : null;
            ChannelActivityRow row = mConventionalTable.getOrCreate(conventionalKey(channel, frequency, timeslot),
                channel, ChannelActivityRow.Role.CONVENTIONAL, frequency, timeslot);
            row.setOrigin(ChannelActivityRow.Origin.CONVENTIONAL_METADATA);
            rememberRow(mConventionalTable, row);
            updateFromMetadata(row, metadata, channel);
            mMetadataRows.put(metadata, row);
            mConventionalTable.refresh(row);
        }
    }

    private ChannelActivityRow ensureConfiguredControlRow(Channel channel, String action)
    {
        SiteActivitySession session = getOrCreateSiteSession(channel);
        return ensureConfiguredControlRow(session, action);
    }

    private ChannelActivityRow ensureConfiguredControlRowIfMissing(Channel channel, String action)
    {
        SiteActivitySession session = getOrCreateSiteSession(channel);
        return ensureConfiguredControlRowIfMissing(session, action);
    }

    private ChannelActivityRow ensureConfiguredControlRowIfMissing(ChannelActivityTableState table, Channel channel,
                                                                   String action)
    {
        SiteActivitySession session = getOrCreateSiteSession(channel);
        return ensureConfiguredControlRowIfMissing(session, action);
    }

    private ChannelActivityRow ensureConfiguredControlRowIfMissing(SiteActivitySession session, String action)
    {
        if(session == null || session.getParentChannel() == null)
        {
            return null;
        }

        long frequency = getConfiguredFrequency(session.getParentChannel());

        if(frequency <= 0 || session.getTableState().get(session.controlKey(frequency)) != null)
        {
            return null;
        }

        return ensureConfiguredControlRow(session, action);
    }

    private ChannelActivityRow ensureConfiguredControlRow(SiteActivitySession session, String action)
    {
        if(session == null || session.getParentChannel() == null)
        {
            return null;
        }

        Channel channel = session.getParentChannel();
        ChannelActivityTableState table = session.getTableState();
        long frequency = getConfiguredFrequency(channel);

        if(frequency <= 0)
        {
            return null;
        }

        ChannelActivityRow row = session.configuredControl(frequency);
        rememberRow(table, row);
        row.setDecoder(getDecoder(channel));
        table.refresh(row);
        return row;
    }

    private void reconcileConfiguredControlRows(Channel channel)
    {
        SiteActivitySession session = mSiteSessions.get(channel);
        ChannelActivityTableState table = session != null ? session.getTableState() : null;

        if(table == null)
        {
            return;
        }

        long configuredFrequency = getConfiguredFrequency(channel);

        for(ChannelActivityRow row: session.removeConfiguredOnlyControlsExcept(configuredFrequency))
        {
            cancelPendingControlIdle(row);
            clearTrafficGrantAgeOut(row);
            table.remove(row);
            mRowTables.remove(row);
        }
    }

    private void markConfiguredControl(ChannelActivityRow row, Channel parentChannel)
    {
        if(row != null)
        {
            cancelPendingControlIdle(row);
            if(!isActiveTraffic(row))
            {
                row.setChannel(parentChannel);
                row.setRole(ChannelActivityRow.Role.CONFIGURED_CONTROL);
                setIdle(row);
            }
        }
    }

    private boolean isActiveTraffic(ChannelActivityRow row)
    {
        return row != null && row.getRole() == ChannelActivityRow.Role.TRAFFIC && row.getState() != State.IDLE;
    }

    private SiteActivitySession getOrCreateSiteSession(Channel channel)
    {
        if(channel == null)
        {
            return null;
        }

        ChannelActivityTableState table = getOrCreateTrunkedTable(channel);

        if(table == null)
        {
            return null;
        }

        return mSiteSessions.computeIfAbsent(channel, key -> new SiteActivitySession(channel, table));
    }

    private ChannelActivityTableState getOrCreateTrunkedTable(Channel channel)
    {
        ChannelActivityTableState table = mTrunkedTables.get(channel);

        if(table == null)
        {
            table = new ChannelActivityTableState(getTrunkedTitle(channel), channel, this::tableSnapshotUpdated);
            mTrunkedTables.put(channel, table);
            updateTablesSnapshot();
        }

        return table;
    }

    private void updateTrunkedTitle(Channel channel)
    {
        ChannelActivityTableState table = mTrunkedTables.get(channel);

        if(table != null)
        {
            String title = getTrunkedTitle(channel);

            if(!title.equals(table.getTitle()))
            {
                table.setTitle(title);
            }
        }
    }

    private void setControlActive(ChannelActivityTableState table, boolean controlActive)
    {
        if(table != null)
        {
            table.setControlActive(controlActive);
        }
    }

    private static <T> void deliver(Listener<T> listener, T event, String type)
    {
        try
        {
            listener.receive(event);
        }
        catch(Throwable throwable)
        {
            rethrowFatal(throwable);
            mLog.error("Error delivering channel activity {} update", type, throwable);
        }
    }

    private void updateTablesSnapshot()
    {
        List<ChannelActivityTableState> tables = new ArrayList<>(mTrunkedTables.size() + 1);
        tables.add(mConventionalTable);
        tables.addAll(mTrunkedTables.values());
        mTables = List.copyOf(tables);
    }

    private void clear()
    {
        mActivitySweeperRunning = false;

        mConventionalTable.clear();

        for(ChannelActivityTableState table: mTrunkedTables.values())
        {
            ChannelActivitySnapshot removedSnapshot = table.getLatestSnapshot();
            tableSnapshotRemoved(removedSnapshot);
        }

        mTrunkedTables.clear();
        mSiteSessions.clear();
        mMetadataRows.clear();
        mRowTables.clear();
        mPendingControlIdleRows.clear();
        mPendingTableRefreshes.clear();
        mSiteIdentities.clear();
        mActiveIncarnations.clear();
        updateTablesSnapshot();
    }

    private boolean isP25TrunkedControlParent(Channel channel)
    {
        DecoderType decoder = channel != null && channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;
        return channel != null && channel.isStandardChannel() &&
            (decoder == DecoderType.P25_PHASE1 || decoder == DecoderType.P25_PHASE2);
    }

    private boolean isConfiguredTrunkedControlParent(Channel channel)
    {
        return isP25TrunkedControlParent(channel) || channel != null && channel.isStandardChannel() &&
            channel.getDecodeConfiguration() instanceof DecodeConfigDMR dmr && dmr.isTrunked();
    }

    private boolean isTrunkingCapableParent(Channel channel)
    {
        DecoderType decoder = channel != null && channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;
        return channel != null && channel.isStandardChannel() &&
            (decoder == DecoderType.P25_PHASE1 || decoder == DecoderType.P25_PHASE2 ||
                channel.getDecodeConfiguration() instanceof DecodeConfigDMR dmr && dmr.isTrunked() ||
                decoder == DecoderType.NXDN);
    }

    private SiteIdentity getSiteIdentity(P25NetworkConfigurationSnapshot snapshot)
    {
        if(snapshot == null)
        {
            return null;
        }

        P25NetworkConfigurationSnapshot.Network network = snapshot.network();
        P25NetworkConfigurationSnapshot.CurrentSite currentSite = snapshot.currentSite();
        Integer wacn = network != null ? network.wacn() : null;
        Integer system = network != null && network.system() != null ? network.system() :
            currentSite != null ? currentSite.system() : null;
        Integer rfss = currentSite != null ? currentSite.rfss() : null;
        Integer site = currentSite != null ? currentSite.site() : null;
        Integer nac = network != null && network.nac() != null ? network.nac() :
            currentSite != null ? currentSite.nac() : null;

        return new SiteIdentity(wacn, system, rfss, site, nac);
    }

    private List<ChannelActivitySnapshot.IdentifierField> identifierFields(SiteMetadataSnapshot snapshot)
    {
        List<ChannelActivitySnapshot.IdentifierField> fields = new ArrayList<>();

        if(snapshot instanceof P25NetworkConfigurationSnapshot p25)
        {
            SiteIdentity identity = getSiteIdentity(p25);

            if(identity != null)
            {
                addIdentifier(fields, "System", "WACN", formatOptionalIdentifier(identity.wacn(), 5));
                addIdentifier(fields, "System", "System ID", formatOptionalIdentifier(identity.system(), 3));
                addIdentifier(fields, "Site", "RFSS", formatOptionalIdentifier(identity.rfss(), 2));
                addIdentifier(fields, "Site", "Site ID", formatOptionalIdentifier(identity.site(), 2));
                addIdentifier(fields, "Site", "NAC", formatOptionalIdentifier(identity.nac(), 3));
            }
        }
        else if(snapshot instanceof DMRNetworkConfigurationSnapshot dmr)
        {
            addIdentifier(fields, "System", "Network ID", dmr.network());
            addIdentifier(fields, "Site", "Site ID", dmr.site());
            addIdentifier(fields, "Site", "Color Code TS1", dmr.colorCodeTimeslot1());
            addIdentifier(fields, "Site", "Color Code TS2", dmr.colorCodeTimeslot2());
        }
        else if(snapshot instanceof NXDNNetworkConfigurationSnapshot nxdn)
        {
            NXDNNetworkConfigurationSnapshot.Location location = nxdn.currentLocation();
            addIdentifier(fields, "System", "Category", location != null ? location.category() : null);
            addIdentifier(fields, "System", "System ID", location != null ? location.system() : null);
            addIdentifier(fields, "System", "Integrator", location != null ? location.integrator() : null);
            addIdentifier(fields, "Site", "Site ID", location != null && location.site() != null ?
                location.site() : nxdn.typeDSite());
            addIdentifier(fields, "Site", "RAN", nxdn.ran());
            addIdentifier(fields, "Site", "Station", nxdn.station() != null ? nxdn.station().identifier() : null);
        }

        return List.copyOf(fields);
    }

    private void addIdentifier(List<ChannelActivitySnapshot.IdentifierField> fields, String group, String label,
                               Object value)
    {
        if(value != null && !String.valueOf(value).isBlank())
        {
            fields.add(new ChannelActivitySnapshot.IdentifierField(group, label, String.valueOf(value)));
        }
    }

    private String formatOptionalIdentifier(Integer value, int width)
    {
        return value != null ? formatIdentifier(value, width) : null;
    }

    private String getTrunkedTitle(Channel channel)
    {
        DecoderType decoder = channel != null && channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;

        if(decoder == DecoderType.P25_PHASE1 || decoder == DecoderType.P25_PHASE2)
        {
            return buildP25Title(channel, mSiteIdentities.get(channel));
        }

        return buildGenericTrunkedTitle(channel);
    }

    private String buildGenericTrunkedTitle(Channel channel)
    {
        String decoder = getDecoder(channel);
        List<String> identity = new ArrayList<>();
        addTitlePart(identity, channel != null ? channel.getSystem() : null);
        addTitlePart(identity, channel != null ? channel.getSite() : null);
        addTitlePart(identity, channel != null ? channel.getName() : null);
        String description = identity.isEmpty() ? "Trunked System" : String.join(" / ", identity);
        return (decoder != null ? decoder : "Trunked") + ": " + description;
    }

    private void addTitlePart(List<String> parts, String value)
    {
        if(value != null && !value.isBlank())
        {
            String cleaned = value.trim();

            if(parts.stream().noneMatch(cleaned::equalsIgnoreCase))
            {
                parts.add(cleaned);
            }
        }
    }

    private String buildP25Title(Channel channel, SiteIdentity siteIdentity)
    {
        String decoder = getDecoder(channel);
        String wacn = formatIdentifier(siteIdentity != null ? siteIdentity.wacn() : null, 5);
        String system = formatIdentifier(siteIdentity != null ? siteIdentity.system() : null, 3);
        String rfss = formatIdentifier(siteIdentity != null ? siteIdentity.rfss() : null, 2);
        String site = formatIdentifier(siteIdentity != null ? siteIdentity.site() : null, 2);
        String channelName = channel != null && channel.getName() != null ? channel.getName() : "";

        return (decoder != null ? decoder : "P25") + ": " + wacn + ":" + system + " " + rfss + "-" + site +
            " (" + channelName + ")";
    }

    private String formatIdentifier(Integer value, int width)
    {
        if(value != null)
        {
            return String.format("%0" + width + "X", value);
        }

        return "?".repeat(width);
    }

    private long getFrequency(ChannelMetadata metadata, Channel channel)
    {
        if(metadata != null)
        {
            FrequencyConfigurationIdentifier frequency = metadata.getFrequencyConfigurationIdentifier();

            if(frequency != null && frequency.getValue() != null)
            {
                return frequency.getValue();
            }
        }

        return getConfiguredFrequency(channel);
    }

    private long getConfiguredFrequency(Channel channel)
    {
        if(channel != null)
        {
            if(channel.getSourceConfiguration() instanceof SourceConfigTuner tuner)
            {
                return tuner.getFrequency();
            }
            else if(channel.getSourceConfiguration() instanceof SourceConfigTunerMultipleFrequency multi)
            {
                return multi.getPreferredFrequency();
            }
        }

        return 0;
    }

    private State getState(DecodeEventType eventType)
    {
        if(eventType != null)
        {
            if(isEncrypted(eventType))
            {
                return State.ENCRYPTED;
            }
            else if(DecodeEventType.DATA_CALLS.contains(eventType))
            {
                return State.DATA;
            }
            else if(eventType.isVoiceCallEvent())
            {
                return State.CALL;
            }
        }

        return State.ACTIVE;
    }

    private boolean isEncrypted(DecodeEventType eventType)
    {
        return eventType != null &&
            (DecodeEventType.VOICE_CALLS_ENCRYPTED.contains(eventType) || eventType == DecodeEventType.DATA_CALL_ENCRYPTED);
    }

    private String getDecoder(Channel channel)
    {
        if(channel != null && channel.getDecodeConfiguration() != null &&
            channel.getDecodeConfiguration().getDecoderType() != null)
        {
            return channel.getDecodeConfiguration().getDecoderType().getShortDisplayString();
        }

        return null;
    }

    private String conventionalKey(Channel channel, long frequency, Integer timeslot)
    {
        return "CONVENTIONAL:" + channel.getChannelID() + ":" + frequency + ":" + (timeslot != null ? timeslot : 0);
    }

    private Integer getTimeslot(IChannelDescriptor channelDescriptor)
    {
        if(channelDescriptor instanceof APCO25Channel apco25Channel && apco25Channel.isTDMAChannel())
        {
            return apco25Channel.getTimeslot();
        }
        else if(channelDescriptor instanceof DMRChannel dmrChannel)
        {
            return dmrChannel.getTimeslot();
        }

        return null;
    }

    private boolean retainIdleCallDetails()
    {
        return mNowPlayingPreference != null && mNowPlayingPreference.isRetainIdleCallDetails();
    }

    private static <T> List<T> list(List<T> values)
    {
        return values != null ? values : Collections.emptyList();
    }

    /**
     * Current traffic-grant age-out used by the Systems activity rows.
     *
     * @return age-out interval in milliseconds
     */
    public int getTrafficGrantAgeOutMilliseconds()
    {
        return mNowPlayingPreference != null ? mNowPlayingPreference.getTrafficGrantAgeOutMilliseconds() :
            NowPlayingPreference.DEFAULT_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS;
    }

    private void runWorker()
    {
        long nextSweep = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ACTIVITY_SWEEPER_INTERVAL_MILLISECONDS);

        try
        {
            while(mWorkerRunning)
            {
                try
                {
                    int drained = 0;
                    ChannelActivityIngressQueue.Entry entry;

                    while(drained < INGRESS_CAPACITY && (entry = mIngress.poll()) != null)
                    {
                        try
                        {
                            process(entry);
                        }
                        catch(Throwable throwable)
                        {
                            rethrowFatal(throwable);
                            mLog.error("Error processing channel activity observation", throwable);
                        }
                        finally
                        {
                            mProcessedIngressCount.incrementAndGet();
                        }

                        drained++;
                    }

                    if(mLifecycleReconcileNeeded.compareAndSet(true, false))
                    {
                        reconcileLifecycle();
                    }

                    long now = System.nanoTime();

                    if(mActivitySweeperRunning && now >= nextSweep)
                    {
                        sweepActivityExpirations();
                        nextSweep = now + TimeUnit.MILLISECONDS.toNanos(ACTIVITY_SWEEPER_INTERVAL_MILLISECONDS);
                    }

                    if(drained == 0)
                    {
                        long parkNanos = mActivitySweeperRunning ? Math.max(1, nextSweep - now) :
                            TimeUnit.MILLISECONDS.toNanos(50);
                        LockSupport.parkNanos(this, parkNanos);
                    }
                }
                catch(Throwable throwable)
                {
                    rethrowFatal(throwable);
                    mLog.error("Error maintaining channel activity state", throwable);
                }
            }
        }
        finally
        {
            if(mClosed)
            {
                clear();
                mIngress.clear();
            }

            mWorkerRunning = false;

            if(Thread.currentThread() == mWorker)
            {
                mWorker = null;
            }
        }
    }

    private static void rethrowFatal(Throwable throwable)
    {
        if(throwable instanceof VirtualMachineError error)
        {
            throw error;
        }
    }

    private void reconcileLifecycle()
    {
        Supplier<List<ActiveChannel>> supplier = mActiveChannelSupplier;

        if(supplier == null)
        {
            return;
        }

        try
        {
            List<ActiveChannel> activeChannels = supplier.get();
            Map<Channel,ActiveChannel> activeByChannel = new IdentityHashMap<>();

            for(ActiveChannel active: list(activeChannels))
            {
                if(active != null && active.channel() != null)
                {
                    activeByChannel.put(active.channel(), active);
                }
            }

            for(Channel known: new ArrayList<>(mActiveIncarnations.keySet()))
            {
                if(!activeByChannel.containsKey(known))
                {
                    processChannelStopped(known);
                }
            }

            for(ActiveChannel active: activeByChannel.values())
            {
                if(mActiveIncarnations.get(active.channel()) != active.incarnation())
                {
                    processChannelStarted(active.channel(), active.metadata(), active.incarnation());
                }
            }
        }
        catch(RuntimeException runtimeException)
        {
            mLifecycleReconcileNeeded.set(true);
            mLog.error("Error reconciling channel activity lifecycle", runtimeException);
        }
    }

    @SuppressWarnings("unchecked")
    private void process(ChannelActivityIngressQueue.Entry entry)
    {
        switch(entry.operation())
        {
            case CHANNEL_STARTED -> processChannelStarted((Channel)entry.first(),
                (List<ChannelMetadata>)entry.second(), entry.third());
            case CHANNEL_STOPPED -> processChannelStopped((Channel)entry.first());
            case CONTROL_QUALITY -> processControlChannelQuality((ControlChannelQualitySnapshot)entry.first());
            case AUDIO_CALL -> processAudioCallEvent((Channel)entry.first(), (AudioCallEvent)entry.second());
            case METADATA_UPDATED -> processMetadataUpdated((ChannelMetadata)entry.first());
            case P25_CURRENT_CONTROL -> processP25CurrentControl((Channel)entry.first(), entry.value());
            case SITE_METADATA -> processSiteMetadata((SiteMetadataEvent)entry.first());
            case PROTOCOL_SITE_METADATA -> processProtocolSiteMetadata((ProtocolSiteMetadataEvent)entry.first());
            case TRUNKED_TRAFFIC -> processTrunkedTrafficEvent((Channel)entry.first(), (Channel)entry.second(),
                (IChannelDescriptor)entry.third(), (Integer)entry.fourth(), (IdentifierCollection)entry.fifth(),
                (DecodeEventType)entry.sixth(), entry.value());
            case TRUNKED_CURRENT_CONTROL -> processTrunkedCurrentControl((Channel)entry.first(), entry.value());
            case TRAFFIC_ENCRYPTION -> processTrafficEncryptionDetails((Channel)entry.first(),
                (IChannelDescriptor)entry.second(), (IdentifierCollection)entry.third(),
                (DecodeEventType)entry.fourth());
            case TRAFFIC_TALKER_ALIAS -> processTrafficTalkerAlias((Channel)entry.first(),
                (IChannelDescriptor)entry.second(), (Identifier<?>)entry.third());
            case CONFIGURATION_CHANGED -> processChannelConfigurationChanged((Channel)entry.first());
            default -> mLog.warn("Ignoring unknown channel activity operation [{}]", entry.operation());
        }
    }

    long getDroppedIngressCount()
    {
        return mDroppedIngressCount.get();
    }

    long getDroppedLifecycleCount()
    {
        return mDroppedLifecycleCount.get();
    }

    int getTrackedActiveChannelCount()
    {
        return mActiveIncarnations.size();
    }

    int getRegularIngressCapacity()
    {
        return mIngress.regularCapacity();
    }

    public boolean isWorkerAlive()
    {
        Thread worker = mWorker;
        return worker != null && worker.isAlive();
    }

    boolean awaitIdle(long timeout, TimeUnit timeUnit)
    {
        long deadline = System.nanoTime() + timeUnit.toNanos(timeout);
        long accepted = mAcceptedIngressCount.get();

        while(System.nanoTime() < deadline)
        {
            if(mProcessedIngressCount.get() >= accepted &&
                !mLifecycleReconcileNeeded.get() && mIngress.size() == 0)
            {
                return true;
            }

            signalWorker();
            LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(1));
        }

        return mProcessedIngressCount.get() >= accepted &&
            !mLifecycleReconcileNeeded.get() && mIngress.size() == 0;
    }

    @Override
    public void close()
    {
        if(mClosed)
        {
            return;
        }

        mClosed = true;
        mWorkerRunning = false;
        signalWorker();
        Thread worker = mWorker;

        if(worker != null && worker != Thread.currentThread())
        {
            try
            {
                worker.join(TimeUnit.SECONDS.toMillis(2));
            }
            catch(InterruptedException interruptedException)
            {
                Thread.currentThread().interrupt();
            }
        }
    }
}
