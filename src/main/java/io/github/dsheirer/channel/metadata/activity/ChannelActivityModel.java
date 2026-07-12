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
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.channel.metadata.ChannelMetadata;
import io.github.dsheirer.channel.metadata.ChannelMetadataField;
import io.github.dsheirer.channel.metadata.IChannelMetadataUpdateListener;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.DecoderTypeConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.decoder.ChannelStateIdentifier;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.P25EncryptionDetails;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import java.awt.EventQueue;
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
import javax.swing.Timer;

/**
 * Session-only Now Playing activity model that keeps stable rows independent from temporary traffic chains.
 */
public class ChannelActivityModel implements IChannelMetadataUpdateListener
{
    private static final int CONTROL_DECODE_HANG_MILLISECONDS = 15000;
    private static final int ACTIVITY_SWEEPER_INTERVAL_MILLISECONDS = 250;

    private final AliasModel mAliasModel;
    private final NowPlayingPreference mNowPlayingPreference;
    private final ChannelActivityTableModel mConventionalTable =
        new ChannelActivityTableModel("Conventional", null, false);
    private final Map<Channel,ChannelActivityTableModel> mTrunkedTables = new IdentityHashMap<>();
    private final Map<Channel,SiteActivitySession> mSiteSessions = new IdentityHashMap<>();
    private final Set<Integer> mClosedTrunkedChannelIds = new HashSet<>();
    private final Map<ChannelMetadata,ChannelActivityRow> mMetadataRows = new IdentityHashMap<>();
    private final Map<ChannelActivityRow,ChannelActivityTableModel> mRowTables = new IdentityHashMap<>();
    private final Map<ChannelActivityRow,ExpiringRow> mPendingControlIdleRows = new IdentityHashMap<>();
    private final Map<ChannelActivityTableModel,Set<ChannelActivityRow>> mPendingTableRefreshes = new IdentityHashMap<>();
    private final Map<Channel,SiteIdentity> mSiteIdentities = new IdentityHashMap<>();
    private final List<Listener<ChannelActivityTableModel>> mTableAddListeners = new ArrayList<>();
    private final List<Listener<ChannelActivityTableModel>> mTableChangeListeners = new ArrayList<>();
    private final List<Listener<ChannelActivityEvent>> mActivityListeners = new CopyOnWriteArrayList<>();
    private final Listener<ChannelActivitySnapshot> mTableSnapshotListener = snapshot ->
        notifyActivityListeners(ChannelActivityEvent.Operation.UPSERT, snapshot);
    private Timer mActivitySweeperTimer;
    private volatile boolean mEnabled;

    private record ExpiringRow(ChannelActivityTableModel table, Channel parentChannel, long expiresAt)
    {
    }

    private record RowReference(SiteActivitySession session, ChannelActivityTableModel table, ChannelActivityRow row)
    {
    }

    private record SiteIdentity(Integer wacn, Integer system, Integer rfss, Integer site)
    {
        private boolean hasAny()
        {
            return wacn != null || system != null || rfss != null || site != null;
        }
    }

    public ChannelActivityModel(AliasModel aliasModel, NowPlayingPreference nowPlayingPreference)
    {
        mAliasModel = aliasModel;
        mNowPlayingPreference = nowPlayingPreference;
        mConventionalTable.addSnapshotListener(mTableSnapshotListener);
    }

    public ChannelActivityTableModel getConventionalTable()
    {
        return mConventionalTable;
    }

    /**
     * Current activity tables in display order for a renderer that is attaching after the model was populated.
     */
    public List<ChannelActivityTableModel> getTables()
    {
        List<ChannelActivityTableModel> tables = new ArrayList<>(mTrunkedTables.size() + 1);
        tables.add(mConventionalTable);
        tables.addAll(mTrunkedTables.values());
        return List.copyOf(tables);
    }

    public boolean isEnabled()
    {
        return mEnabled;
    }

    public void setEnabled(boolean enabled)
    {
        if(mEnabled != enabled)
        {
            mEnabled = enabled;
            runOnSwing(this::clear);
        }
    }

    public void addTableAddListener(Listener<ChannelActivityTableModel> listener)
    {
        mTableAddListeners.add(listener);
    }

    public void removeTableAddListener(Listener<ChannelActivityTableModel> listener)
    {
        mTableAddListeners.remove(listener);
    }

    public void addTableChangeListener(Listener<ChannelActivityTableModel> listener)
    {
        mTableChangeListeners.add(listener);
    }

    public void removeTableChangeListener(Listener<ChannelActivityTableModel> listener)
    {
        mTableChangeListeners.remove(listener);
    }

    public void addActivityListener(Listener<ChannelActivityEvent> listener)
    {
        if(listener != null)
        {
            mActivityListeners.add(listener);
            listener.receive(new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT,
                ChannelActivitySnapshot.from(mConventionalTable)));

            for(ChannelActivityTableModel table: mTrunkedTables.values())
            {
                listener.receive(new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT,
                    ChannelActivitySnapshot.from(table)));
            }
        }
    }

    public void removeActivityListener(Listener<ChannelActivityEvent> listener)
    {
        mActivityListeners.remove(listener);
    }

    private void notifyActivityListeners(ChannelActivityEvent.Operation operation, ChannelActivitySnapshot snapshot)
    {
        if(snapshot != null)
        {
            ChannelActivityEvent event = new ChannelActivityEvent(operation, snapshot);

            for(Listener<ChannelActivityEvent> listener: mActivityListeners)
            {
                listener.receive(event);
            }
        }
    }

    public void close(ChannelActivityTableModel tableModel)
    {
        runOnSwingIfEnabled(() -> {
            if(tableModel != null && tableModel.getOwnerChannel() != null)
            {
                Channel owner = tableModel.getOwnerChannel();
                ChannelActivitySnapshot removedSnapshot = ChannelActivitySnapshot.from(tableModel);
                mClosedTrunkedChannelIds.add(owner.getChannelID());
                mTrunkedTables.remove(owner);
                mSiteSessions.remove(owner);
                tableModel.removeSnapshotListener(mTableSnapshotListener);
                notifyActivityListeners(ChannelActivityEvent.Operation.REMOVE, removedSnapshot);

                for(ChannelActivityRow row: tableModel.getRows())
                {
                    clearTrafficGrantAgeOut(row);
                    cancelPendingControlIdle(row);
                    mRowTables.remove(row);
                }

                mPendingTableRefreshes.remove(tableModel);
                stopActivitySweeperIfIdle();
            }
        });
    }

    public void channelStarted(Channel channel, List<ChannelMetadata> metadataList)
    {
        if(!mEnabled || channel == null || channel.isTrafficChannel())
        {
            return;
        }

        runOnSwingIfEnabled(() -> {
            mClosedTrunkedChannelIds.remove(channel.getChannelID());

            if(isP25TrunkedControlParent(channel))
            {
                ensureConfiguredControlRow(channel, "channel-started-control");
            }
            else if(metadataList != null)
            {
                addConventionalRows(channel, metadataList, "channel-started-conventional");
            }
        });
    }

    public void channelStopped(Channel channel)
    {
        if(!mEnabled || channel == null)
        {
            return;
        }

        runOnSwingIfEnabled(() -> {
            for(RowReference reference: getRowsForStoppedChannel(channel))
            {
                ChannelActivityRow row = reference.row();
                ChannelActivityTableModel table = reference.table();

                if(channel.isTrafficChannel())
                {
                    if(row.getRole() == ChannelActivityRow.Role.TRAFFIC && table != null &&
                        table.getOwnerChannel() != null)
                    {
                        reference.session().releaseTrafficChannel(channel, row);
                        row.setDecoder(getDecoder(table.getOwnerChannel()));
                        table.refresh(row);
                    }
                }
                else
                {
                    clearTrafficGrantAgeOut(row);
                    cancelPendingControlIdle(row);

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
                ChannelActivityTableModel trunked = mTrunkedTables.get(channel);

                if(trunked != null)
                {
                    setControlActive(trunked, false);
                }
            }
        });
    }

    @Override
    public void updated(ChannelMetadata channelMetadata, ChannelMetadataField channelMetadataField)
    {
        if(!mEnabled)
        {
            return;
        }

        runOnSwingIfEnabled(() -> {
            ChannelActivityRow row = mMetadataRows.get(channelMetadata);

            if(row != null)
            {
                updateFromMetadata(row, channelMetadata, row.getChannel());
                mConventionalTable.refresh(row);
            }
        });
    }

    public void p25CurrentControl(Channel parentChannel, long frequency)
    {
        if(!mEnabled || parentChannel == null || !isP25TrunkedControlParent(parentChannel) || frequency <= 0)
        {
            return;
        }

        runOnSwingIfEnabled(() -> {
            sweepActivityExpirations();
            SiteActivitySession session = getOrCreateSiteSession(parentChannel);
            ChannelActivityTableModel table = session != null ? session.getTableModel() : null;

            if(table == null)
            {
                return;
            }

            expireTrafficRows(session, table, parentChannel);
            ChannelActivityRow row = session.configuredControl(frequency);
            rememberRow(table, row);
            row.setDecoder(getDecoder(parentChannel));
            table.refresh(row);
        });
    }

    public void receiveSiteMetadata(SiteMetadataEvent event)
    {
        if(!mEnabled || event == null || event.channel() == null || event.snapshot() == null)
        {
            return;
        }

        Channel parentChannel = event.channel();
        P25NetworkConfigurationSnapshot snapshot = event.snapshot();

        if(!parentChannel.isStandardChannel() || !isP25TrunkedControlParent(parentChannel))
        {
            return;
        }

        runOnSwingIfEnabled(() -> {
            sweepActivityExpirations();
            SiteIdentity identity = getSiteIdentity(snapshot);

            if(identity != null && identity.hasAny())
            {
                mSiteIdentities.put(parentChannel, identity);
                updateTrunkedTitle(parentChannel);
            }

            SiteActivitySession session = getOrCreateSiteSession(parentChannel);
            ChannelActivityTableModel table = session != null ? session.getTableModel() : null;

            if(table == null)
            {
                return;
            }

            expireTrafficRows(session, table, parentChannel);
            Set<Long> promotedControlFrequencies = new HashSet<>();

            for(P25NetworkConfigurationSnapshot.Channel channel: list(snapshot.channels()))
            {
                if(channel == null || channel.downlink() == null || channel.downlink() <= 0)
                {
                    continue;
                }

                if(isCurrentControlRole(channel.role()))
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
                else if(isSecondaryControlRole(channel.role()))
                {
                    promotedControlFrequencies.add(channel.downlink());
                    ChannelActivityRow row = session.alternateControl(channel.downlink(), channel.descriptor());
                    rememberRow(table, row);
                    cancelPendingControlIdle(row);
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
        });
    }

    public void p25TrafficGrant(Channel parentChannel, Channel trafficChannel, IChannelDescriptor channelDescriptor,
                                IdentifierCollection identifiers, DecodeEventType eventType)
    {
        if(!mEnabled || parentChannel == null || !isP25TrunkedControlParent(parentChannel) || channelDescriptor == null ||
            channelDescriptor.getDownlinkFrequency() <= 0)
        {
            return;
        }

        runOnSwingIfEnabled(() -> {
            sweepActivityExpirations();
            SiteActivitySession session = getOrCreateSiteSession(parentChannel);
            ChannelActivityTableModel table = session != null ? session.getTableModel() : null;

            if(table == null)
            {
                return;
            }

            expireTrafficRows(session, table, parentChannel);
            ensureConfiguredControlRowIfMissing(table, parentChannel, "p25-traffic-grant-control-seed");

            Channel rowChannel = trafficChannel != null ? trafficChannel : parentChannel;
            ChannelActivityRow row = session.traffic(trafficChannel, channelDescriptor);
            rememberRow(table, row);
            clearTrafficGrantAgeOut(row);
            boolean newCall = row.getState() == State.IDLE || isTargetChanged(row, identifiers);
            boolean wasEncrypted = !newCall && row.getState() == State.ENCRYPTED;

            if(newCall)
            {
                row.clearCallDetails();
            }

            row.setDecoder(getDecoder(rowChannel));
            updateCallDetails(row, identifiers, rowChannel);
            row.setState(getStickyTrafficState(row, getState(eventType), wasEncrypted));
            table.refresh(row);
            scheduleTrafficGrantAgeOut(row);
        });
    }

    public void p25TrafficEncryptionDetails(Channel parentChannel, IChannelDescriptor channelDescriptor,
                                            IdentifierCollection identifiers, DecodeEventType eventType)
    {
        long frequency = channelDescriptor != null ? channelDescriptor.getDownlinkFrequency() : 0;
        Integer timeslot = getTimeslot(channelDescriptor);
        String encryptionDetails = P25EncryptionDetails.format(identifiers);

        if(!mEnabled || parentChannel == null || !isP25TrunkedControlParent(parentChannel) || frequency <= 0 ||
            encryptionDetails == null)
        {
            return;
        }

        runOnSwingIfEnabled(() -> {
            SiteActivitySession session = mSiteSessions.get(parentChannel);
            ChannelActivityTableModel table = session != null ? session.getTableModel() : null;

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
        });
    }

    public void channelConfigurationChanged(Channel channel)
    {
        if(!mEnabled || channel == null)
        {
            return;
        }

        runOnSwingIfEnabled(() -> {
            updateTrunkedTitle(channel);

            if(isP25TrunkedControlParent(channel))
            {
                ensureConfiguredControlRow(channel, "channel-configuration-control-seed");
                reconcileConfiguredControlRows(channel);
            }
        });
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
        }

        DecoderTypeConfigurationIdentifier decoderIdentifier = metadata.getDecoderTypeConfigurationIdentifier();
        row.setDecoder(decoderIdentifier != null ? decoderIdentifier.toString() : getDecoder(channel));

        row.setSource(metadata.getFromIdentifier());
        row.setSourceAliases(metadata.getFromIdentifierAliases());
        row.setTarget(metadata.getToIdentifier());
        row.setTargetAliases(metadata.getToIdentifierAliases());
        row.setEncryptionDetails(P25EncryptionDetails.format(metadata.getEncryptionIdentifier()));
        row.setState(getStickyTrafficState(row, state, wasEncrypted));

        if(row.getState() == State.IDLE && !retainIdleCallDetails())
        {
            row.clearCallDetails();
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

            String encryptionDetails = P25EncryptionDetails.format(identifiers);

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

        AliasList aliasList = mAliasModel.getAliasList(identifiers);

        if(aliasList == null && channel != null)
        {
            aliasList = mAliasModel.getAliasList(channel.getAliasListName());
        }

        return aliasList != null ? aliasList.getAliases(identifier) : Collections.emptyList();
    }

    private void setIdle(ChannelActivityRow row)
    {
        row.setState(State.IDLE);

        if(!retainIdleCallDetails())
        {
            row.clearCallDetails();
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

    private void applyTrafficGrantAgeOut(ChannelActivityRow row, ChannelActivityTableModel table, Channel parentChannel,
                                         String origin)
    {
        if(row != null)
        {
            row.clearTrafficGrantExpiresAt();
            row.setChannel(parentChannel);
            row.setDecoder(getDecoder(parentChannel));

            if(row.getControlRole() == ChannelActivityRow.ControlRole.CURRENT)
            {
                markConfiguredControl(row, parentChannel);
            }
            else if(row.getControlRole() == ChannelActivityRow.ControlRole.ALTERNATE)
            {
                row.setRole(ChannelActivityRow.Role.ALTERNATE_CONTROL);
                setIdle(row);
            }
            else
            {
                setIdle(row);
            }

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

    private void expireTrafficRows(SiteActivitySession session, ChannelActivityTableModel table, Channel parentChannel)
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
                applyTrafficGrantAgeOut(row, table, parentChannel, "p25-traffic-ageout");
            }
        }

        flushQueuedRefreshes();
    }

    private void scheduleControlIdle(ChannelActivityRow row, ChannelActivityTableModel table, Channel parentChannel)
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
        if(mActivitySweeperTimer == null)
        {
            mActivitySweeperTimer = new Timer(ACTIVITY_SWEEPER_INTERVAL_MILLISECONDS,
                event -> sweepActivityExpirations());
            mActivitySweeperTimer.setRepeats(true);
        }

        if(!mActivitySweeperTimer.isRunning())
        {
            mActivitySweeperTimer.start();
        }
    }

    private void stopActivitySweeperIfIdle()
    {
        if(mActivitySweeperTimer != null && mPendingControlIdleRows.isEmpty())
        {
            mActivitySweeperTimer.stop();
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

    private void applyControlIdle(ChannelActivityRow row, ChannelActivityTableModel table, Channel parentChannel)
    {
        if(row != null && row.getControlRole() == ChannelActivityRow.ControlRole.CURRENT &&
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

    private void queueRefresh(ChannelActivityTableModel table, ChannelActivityRow row)
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

        Map<ChannelActivityTableModel,Set<ChannelActivityRow>> refreshes =
            new IdentityHashMap<>(mPendingTableRefreshes);
        mPendingTableRefreshes.clear();

        for(Map.Entry<ChannelActivityTableModel,Set<ChannelActivityRow>> entry: refreshes.entrySet())
        {
            entry.getKey().refresh(entry.getValue());
        }
    }

    private void rememberRow(ChannelActivityTableModel table, ChannelActivityRow row)
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
            ChannelActivityTableModel table = session.getTableModel();

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

    private ChannelActivityRow ensureConfiguredControlRowIfMissing(ChannelActivityTableModel table, Channel channel,
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

        if(frequency <= 0 || session.getTableModel().get(session.controlKey(frequency)) != null)
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
        ChannelActivityTableModel table = session.getTableModel();
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
        ChannelActivityTableModel table = session != null ? session.getTableModel() : null;

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
            row.setControlRole(ChannelActivityRow.ControlRole.NONE);

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

        ChannelActivityTableModel table = getOrCreateTrunkedTable(channel);

        if(table == null)
        {
            return null;
        }

        return mSiteSessions.computeIfAbsent(channel, key -> new SiteActivitySession(channel, table));
    }

    private ChannelActivityTableModel getOrCreateTrunkedTable(Channel channel)
    {
        if(mClosedTrunkedChannelIds.contains(channel.getChannelID()))
        {
            return null;
        }

        ChannelActivityTableModel table = mTrunkedTables.get(channel);

        if(table == null)
        {
            table = new ChannelActivityTableModel(getTrunkedTitle(channel), channel, true);
            table.addSnapshotListener(mTableSnapshotListener);
            mTrunkedTables.put(channel, table);

            for(Listener<ChannelActivityTableModel> listener: mTableAddListeners)
            {
                listener.receive(table);
            }
        }

        return table;
    }

    private void updateTrunkedTitle(Channel channel)
    {
        ChannelActivityTableModel table = mTrunkedTables.get(channel);

        if(table != null)
        {
            String title = getTrunkedTitle(channel);

            if(!title.equals(table.getTitle()))
            {
                table.setTitle(title);
                notifyTableChanged(table);
            }
        }
    }

    private void setControlActive(ChannelActivityTableModel table, boolean controlActive)
    {
        if(table != null && table.setControlActive(controlActive))
        {
            notifyTableChanged(table);
        }
    }

    private void notifyTableChanged(ChannelActivityTableModel table)
    {
        for(Listener<ChannelActivityTableModel> listener: mTableChangeListeners)
        {
            listener.receive(table);
        }
    }

    private void clear()
    {
        if(mActivitySweeperTimer != null)
        {
            mActivitySweeperTimer.stop();
        }

        mConventionalTable.clear();

        for(ChannelActivityTableModel table: mTrunkedTables.values())
        {
            ChannelActivitySnapshot removedSnapshot = ChannelActivitySnapshot.from(table);
            table.clear();
            table.removeSnapshotListener(mTableSnapshotListener);
            notifyActivityListeners(ChannelActivityEvent.Operation.REMOVE, removedSnapshot);
        }

        mTrunkedTables.clear();
        mSiteSessions.clear();
        mClosedTrunkedChannelIds.clear();
        mMetadataRows.clear();
        mRowTables.clear();
        mPendingControlIdleRows.clear();
        mPendingTableRefreshes.clear();
        mSiteIdentities.clear();
    }

    private boolean isP25TrunkedControlParent(Channel channel)
    {
        DecoderType decoder = channel != null && channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;
        return channel != null && channel.isStandardChannel() &&
            (decoder == DecoderType.P25_PHASE1 || decoder == DecoderType.P25_PHASE2);
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

        return new SiteIdentity(wacn, system, rfss, site);
    }

    private boolean isCurrentControlRole(String role)
    {
        return "primary_control".equals(role) || "current_control".equals(role);
    }

    private boolean isSecondaryControlRole(String role)
    {
        return "secondary_control".equals(role);
    }

    private String getTrunkedTitle(Channel channel)
    {
        return buildP25Title(channel, mSiteIdentities.get(channel));
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

    private int getTrafficGrantAgeOutMilliseconds()
    {
        return mNowPlayingPreference != null ? mNowPlayingPreference.getTrafficGrantAgeOutMilliseconds() :
            NowPlayingPreference.DEFAULT_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS;
    }

    private void runOnSwing(Runnable runnable)
    {
        if(EventQueue.isDispatchThread())
        {
            runnable.run();
        }
        else
        {
            EventQueue.invokeLater(runnable);
        }
    }

    private void runOnSwingIfEnabled(Runnable runnable)
    {
        runOnSwing(() -> {
            if(mEnabled)
            {
                runnable.run();
            }
        });
    }
}
