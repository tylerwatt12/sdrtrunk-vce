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
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.P25EncryptionDetails;
import io.github.dsheirer.module.decode.p25.P25SiteIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25;
import io.github.dsheirer.preference.application.ApplicationPreference;
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
import javax.swing.Timer;

/**
 * Session-only Now Playing activity model that keeps stable rows independent from temporary traffic chains.
 */
public class ChannelActivityModel implements IChannelMetadataUpdateListener
{
    private static final int P25_CLASSIFICATION_DELAY_MILLISECONDS = 500;
    private static final int CONTROL_DECODE_HANG_MILLISECONDS = 15000;
    private static final int TRAFFIC_GRANT_AGE_OUT_MILLISECONDS = 1000;
    private static final int ACTIVITY_SWEEPER_INTERVAL_MILLISECONDS = 250;

    private final AliasModel mAliasModel;
    private final ApplicationPreference mApplicationPreference;
    private final NowPlayingPreference mNowPlayingPreference;
    private final ChannelActivityTableModel mConventionalTable =
        new ChannelActivityTableModel("Conventional", null, false);
    private final Map<Channel,ChannelActivityTableModel> mTrunkedTables = new IdentityHashMap<>();
    private final Map<Channel,SiteActivitySession> mSiteSessions = new IdentityHashMap<>();
    private final Set<Integer> mClosedTrunkedChannelIds = new HashSet<>();
    private final Map<ChannelMetadata,ChannelActivityRow> mMetadataRows = new IdentityHashMap<>();
    private final Map<ChannelMetadata,Channel> mPendingP25MetadataChannels = new IdentityHashMap<>();
    private final Map<Channel,Set<ChannelActivityRow>> mChannelRows = new IdentityHashMap<>();
    private final Map<ChannelActivityRow,ChannelActivityTableModel> mRowTables = new IdentityHashMap<>();
    private final Map<ChannelActivityRow,ExpiringRow> mPendingTrafficGrantAgeOuts = new IdentityHashMap<>();
    private final Map<ChannelActivityRow,ExpiringRow> mPendingControlIdleRows = new IdentityHashMap<>();
    private final Map<ChannelActivityTableModel,Set<ChannelActivityRow>> mPendingTableRefreshes = new IdentityHashMap<>();
    private final Map<Channel,Timer> mPendingP25ClassificationTimers = new IdentityHashMap<>();
    private final Map<Channel,List<ChannelMetadata>> mPendingP25ClassificationMetadata = new IdentityHashMap<>();
    private final Map<Channel,P25SiteIdentifier> mSiteIdentifiers = new IdentityHashMap<>();
    private final List<Listener<ChannelActivityTableModel>> mTableAddListeners = new ArrayList<>();
    private final List<Listener<ChannelActivityTableModel>> mTableChangeListeners = new ArrayList<>();
    private Timer mActivitySweeperTimer;

    private record ExpiringRow(ChannelActivityTableModel table, Channel parentChannel, long expiresAt)
    {
    }

    public ChannelActivityModel(AliasModel aliasModel, ApplicationPreference applicationPreference,
                                NowPlayingPreference nowPlayingPreference)
    {
        mAliasModel = aliasModel;
        mApplicationPreference = applicationPreference;
        mNowPlayingPreference = nowPlayingPreference;
        NowPlayingActivityDebugFeed.startIfEnabled();
    }

    public ChannelActivityTableModel getConventionalTable()
    {
        return mConventionalTable;
    }

    public void addTableAddListener(Listener<ChannelActivityTableModel> listener)
    {
        mTableAddListeners.add(listener);
    }

    public void addTableChangeListener(Listener<ChannelActivityTableModel> listener)
    {
        mTableChangeListeners.add(listener);
    }

    public void close(ChannelActivityTableModel tableModel)
    {
        runOnSwing(() -> {
            if(tableModel != null && tableModel.getOwnerChannel() != null)
            {
                Channel owner = tableModel.getOwnerChannel();
                mClosedTrunkedChannelIds.add(owner.getChannelID());
                mTrunkedTables.remove(owner);
                mSiteSessions.remove(owner);
                cancelPendingP25Classification(owner);

                for(ChannelActivityRow row: tableModel.getRows())
                {
                    cancelPendingTrafficGrantAgeOut(row);
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
        if(channel == null || channel.isTrafficChannel())
        {
            return;
        }

        runOnSwing(() -> {
            mClosedTrunkedChannelIds.remove(channel.getChannelID());

            if(isP25TrunkedControlParent(channel))
            {
                ensureConfiguredControlRow(channel, "channel-started-control");
            }
            else if(isP25(channel))
            {
                scheduleP25Classification(channel, metadataList);
            }
            else if(metadataList != null)
            {
                addConventionalRows(channel, metadataList, "channel-started-conventional");
            }
        });
    }

    public void channelStopped(Channel channel)
    {
        if(channel == null)
        {
            return;
        }

        runOnSwing(() -> {
            cancelPendingP25Classification(channel);
            Set<ChannelActivityRow> rows = mChannelRows.get(channel);

            if(rows != null)
            {
                Iterator<ChannelActivityRow> iterator = rows.iterator();

                while(iterator.hasNext())
                {
                    ChannelActivityRow row = iterator.next();
                    ChannelActivityTableModel table = mRowTables.get(row);

                    if(channel.isTrafficChannel())
                    {
                        if(row.getChannel() == channel && row.getRole() == ChannelActivityRow.Role.TRAFFIC &&
                            table != null && table.getOwnerChannel() != null)
                        {
                            removeTrafficChannelRow(channel, row);
                            row.setChannel(table.getOwnerChannel());
                            row.setDecoder(getDecoder(table.getOwnerChannel()));
                        }
                        else
                        {
                            iterator.remove();
                        }
                    }
                    else
                    {
                        cancelPendingTrafficGrantAgeOut(row);
                        cancelPendingControlIdle(row);

                        if(row.isControlRow())
                        {
                            markConfiguredControl(row, channel);
                        }
                        else
                        {
                            setIdle(row);
                        }

                        refreshRow(row);
                    }
                }
            }

            ChannelActivityTableModel trunked = mTrunkedTables.get(channel);

            if(trunked != null)
            {
                setControlActive(trunked, false);

                for(ChannelActivityRow row: trunked.getRows())
                {
                    if(row.getChannel() == channel &&
                        row.getControlRole() == ChannelActivityRow.ControlRole.CURRENT)
                    {
                        cancelPendingControlIdle(row);
                        markConfiguredControl(row, channel);
                        trunked.refresh(row);
                    }
                }
            }
        });
    }

    @Override
    public void updated(ChannelMetadata channelMetadata, ChannelMetadataField channelMetadataField)
    {
        runOnSwing(() -> {
            ChannelActivityRow row = mMetadataRows.get(channelMetadata);

            if(row != null)
            {
                if(isP25ControlMetadata(channelMetadata, row.getChannel()))
                {
                    removeConventionalMetadataRow(channelMetadata, row);
                    ensureConfiguredControlRow(row.getChannel(), "metadata-control-state");
                    return;
                }

                updateFromMetadata(row, channelMetadata, row.getChannel());
                mConventionalTable.refresh(row);
            }
            else
            {
                Channel pendingChannel = mPendingP25MetadataChannels.get(channelMetadata);

                if(isP25ControlMetadata(channelMetadata, pendingChannel))
                {
                    cancelPendingP25Classification(pendingChannel);
                    ensureConfiguredControlRow(pendingChannel, "pending-p25-control-state");
                }
            }
        });
    }

    public void p25CurrentControl(Channel parentChannel, long frequency)
    {
        if(parentChannel == null || frequency <= 0)
        {
            return;
        }

        runOnSwing(() -> {
            sweepActivityExpirations();
            SiteActivitySession session = getOrCreateSiteSession(parentChannel);
            ChannelActivityTableModel table = session != null ? session.getTableModel() : null;

            if(table == null)
            {
                return;
            }

            ChannelActivityRow row = session.configuredControl(frequency);
            NowPlayingActivityDebugFeed.Snapshot before = NowPlayingActivityDebugFeed.capture(row);
            rememberRow(table, row);
            row.setDecoder(getDecoder(parentChannel));
            addChannelRow(parentChannel, row);
            table.refresh(row);
            NowPlayingActivityDebugFeed.logRow("p25-current-control-configured", table, row, before, parentChannel,
                null, null);
        });
    }

    public void p25CurrentControl(Channel parentChannel, IChannelDescriptor channelDescriptor)
    {
        if(parentChannel == null || channelDescriptor == null || channelDescriptor.getDownlinkFrequency() <= 0)
        {
            return;
        }

        runOnSwing(() -> {
            sweepActivityExpirations();
            SiteActivitySession session = getOrCreateSiteSession(parentChannel);
            ChannelActivityTableModel table = session != null ? session.getTableModel() : null;

            if(table == null)
            {
                return;
            }

            SiteActivitySession.ControlUpdate update = session.currentControl(channelDescriptor);
            ChannelActivityRow row = update.current();
            NowPlayingActivityDebugFeed.Snapshot before = NowPlayingActivityDebugFeed.capture(row);
            rememberRow(table, row);
            row.setDecoder(getDecoder(parentChannel));
            addChannelRow(parentChannel, row);
            cancelPendingControlIdle(row);
            scheduleControlIdle(row, table, parentChannel);
            setControlActive(table, true);
            table.refresh(row);
            NowPlayingActivityDebugFeed.logRow("p25-current-control", table, row, before, parentChannel, null,
                "controlIdleMs=" + CONTROL_DECODE_HANG_MILLISECONDS);

            for(ChannelActivityRow demoted: update.demoted())
            {
                NowPlayingActivityDebugFeed.Snapshot demotedBefore = NowPlayingActivityDebugFeed.capture(demoted);
                cancelPendingControlIdle(demoted);
                demoted.setDecoder(getDecoder(parentChannel));
                table.refresh(demoted);
                NowPlayingActivityDebugFeed.logRow("p25-current-control-demoted", table, demoted, demotedBefore,
                    parentChannel, null, null);
            }
        });
    }

    public void p25AlternateControl(Channel parentChannel, IChannelDescriptor channelDescriptor)
    {
        if(parentChannel == null || channelDescriptor == null || channelDescriptor.getDownlinkFrequency() <= 0)
        {
            return;
        }

        runOnSwing(() -> {
            sweepActivityExpirations();
            SiteActivitySession session = getOrCreateSiteSession(parentChannel);
            ChannelActivityTableModel table = session != null ? session.getTableModel() : null;

            if(table == null)
            {
                return;
            }

            ChannelActivityRow row = session.alternateControl(channelDescriptor);
            NowPlayingActivityDebugFeed.Snapshot before = NowPlayingActivityDebugFeed.capture(row);
            rememberRow(table, row);
            cancelPendingControlIdle(row);
            row.setDecoder(getDecoder(parentChannel));
            addChannelRow(parentChannel, row);
            table.refresh(row);
            NowPlayingActivityDebugFeed.logRow("p25-alternate-control", table, row, before, parentChannel, null, null);
        });
    }

    public void p25TrafficGrant(Channel parentChannel, Channel trafficChannel, IChannelDescriptor channelDescriptor,
                                IdentifierCollection identifiers, DecodeEventType eventType)
    {
        if(parentChannel == null || channelDescriptor == null || channelDescriptor.getDownlinkFrequency() <= 0)
        {
            return;
        }

        runOnSwing(() -> {
            sweepActivityExpirations();
            SiteActivitySession session = getOrCreateSiteSession(parentChannel);
            ChannelActivityTableModel table = session != null ? session.getTableModel() : null;

            if(table == null)
            {
                return;
            }

            ensureConfiguredControlRowIfMissing(table, parentChannel, "p25-traffic-grant-control-seed");

            Channel rowChannel = trafficChannel != null ? trafficChannel : parentChannel;
            ChannelActivityRow row = session.traffic(trafficChannel, channelDescriptor);
            NowPlayingActivityDebugFeed.Snapshot before = NowPlayingActivityDebugFeed.capture(row);
            rememberRow(table, row);
            cancelPendingTrafficGrantAgeOut(row);
            boolean newCall = row.getState() == State.IDLE || isTargetChanged(row, identifiers);
            boolean wasEncrypted = !newCall && row.getState() == State.ENCRYPTED;

            if(newCall)
            {
                row.clearCallDetails();
            }

            row.setDecoder(getDecoder(rowChannel));
            updateCallDetails(row, identifiers, rowChannel);
            row.setState(getStickyTrafficState(row, getState(eventType), wasEncrypted));
            logP25EncryptionDebug("traffic-grant", row, parentChannel, identifiers, null, eventType, row.getState());
            addChannelRow(rowChannel, row);
            addChannelRow(parentChannel, row);
            table.refresh(row);
            long expiresAt = scheduleTrafficGrantAgeOut(row, table, parentChannel);
            NowPlayingActivityDebugFeed.logRow("p25-traffic-grant", table, row, before, parentChannel, eventType,
                "newCall=" + newCall + " trafficChannel=" + describeChannel(rowChannel) + " ageOutAt=" + expiresAt +
                    " ageOutMs=" + TRAFFIC_GRANT_AGE_OUT_MILLISECONDS);
        });
    }

    public void p25SiteIdentifier(Channel parentChannel, P25SiteIdentifier siteIdentifier)
    {
        if(parentChannel == null || siteIdentifier == null)
        {
            return;
        }

        runOnSwing(() -> {
            P25SiteIdentifier merged = merge(mSiteIdentifiers.get(parentChannel), siteIdentifier);
            mSiteIdentifiers.put(parentChannel, merged);
            updateTrunkedTitle(parentChannel);
            ensureConfiguredControlRowIfMissing(parentChannel, "p25-site-identifier-control-seed");
        });
    }

    public void channelConfigurationChanged(Channel channel)
    {
        if(channel == null)
        {
            return;
        }

        runOnSwing(() -> {
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
        logP25EncryptionDebug("metadata", row, channel, null, metadata.getEncryptionIdentifier(), null, row.getState());

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

    private long scheduleTrafficGrantAgeOut(ChannelActivityRow row, ChannelActivityTableModel table, Channel parentChannel)
    {
        cancelPendingTrafficGrantAgeOut(row);

        if(row != null)
        {
            long expiresAt = System.currentTimeMillis() + TRAFFIC_GRANT_AGE_OUT_MILLISECONDS;
            mPendingTrafficGrantAgeOuts.put(row, new ExpiringRow(table, parentChannel, expiresAt));
            startActivitySweeper();
            return expiresAt;
        }

        return -1;
    }

    private void applyTrafficGrantAgeOut(ChannelActivityRow row, ChannelActivityTableModel table, Channel parentChannel,
                                         String origin)
    {
        if(row != null)
        {
            NowPlayingActivityDebugFeed.Snapshot before = NowPlayingActivityDebugFeed.capture(row);
            Channel previousChannel = row.getChannel();
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

            removeTrafficChannelRow(previousChannel, row);

            if(table != null)
            {
                queueRefresh(table, row);
            }

            NowPlayingActivityDebugFeed.logRow(origin, table, row, before, parentChannel, null,
                "previousChannel=" + describeChannel(previousChannel));
        }
    }

    private void cancelPendingTrafficGrantAgeOut(ChannelActivityRow row)
    {
        if(row != null)
        {
            mPendingTrafficGrantAgeOuts.remove(row);
            stopActivitySweeperIfIdle();
        }
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
        if(mActivitySweeperTimer != null && mPendingTrafficGrantAgeOuts.isEmpty() &&
            mPendingControlIdleRows.isEmpty())
        {
            mActivitySweeperTimer.stop();
        }
    }

    private void sweepActivityExpirations()
    {
        long now = System.currentTimeMillis();

        Iterator<Map.Entry<ChannelActivityRow,ExpiringRow>> trafficIterator =
            mPendingTrafficGrantAgeOuts.entrySet().iterator();

        while(trafficIterator.hasNext())
        {
            Map.Entry<ChannelActivityRow,ExpiringRow> entry = trafficIterator.next();

            if(entry.getValue().expiresAt() <= now)
            {
                trafficIterator.remove();
                applyTrafficGrantAgeOut(entry.getKey(), entry.getValue().table(), entry.getValue().parentChannel(),
                    "p25-traffic-ageout");
            }
        }

        Iterator<Map.Entry<ChannelActivityRow,ExpiringRow>> controlIterator =
            mPendingControlIdleRows.entrySet().iterator();

        while(controlIterator.hasNext())
        {
            Map.Entry<ChannelActivityRow,ExpiringRow> entry = controlIterator.next();

            if(entry.getValue().expiresAt() <= now)
            {
                controlIterator.remove();
                applyControlIdle(entry.getKey(), entry.getValue().table(), entry.getValue().parentChannel());
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
            NowPlayingActivityDebugFeed.Snapshot before = NowPlayingActivityDebugFeed.capture(row);
            markConfiguredControl(row, parentChannel);

            if(table != null)
            {
                setControlActive(table, false);
                queueRefresh(table, row);
            }

            NowPlayingActivityDebugFeed.logRow("p25-control-idle", table, row, before, parentChannel, null, null);
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

    private void refreshRow(ChannelActivityRow row)
    {
        ChannelActivityTableModel table = mRowTables.get(row);

        if(table != null)
        {
            table.refresh(row);
        }
    }

    private void logP25EncryptionDebug(String origin, ChannelActivityRow row, Channel parentChannel,
                                       IdentifierCollection identifiers, Identifier<?> encryptionIdentifier,
                                       DecodeEventType eventType, State state)
    {
        if(mApplicationPreference != null && mApplicationPreference.isP25EncryptionCsvDebugLogger())
        {
            P25EncryptionDebugLogger.log(origin, row, parentChannel, identifiers, encryptionIdentifier, eventType,
                state);
        }
    }

    private void addChannelRow(Channel channel, ChannelActivityRow row)
    {
        if(channel != null && row != null)
        {
            mChannelRows.computeIfAbsent(channel, key -> Collections.newSetFromMap(new IdentityHashMap<>()))
                .add(row);
        }
    }

    private void removeTrafficChannelRow(Channel channel, ChannelActivityRow row)
    {
        if(channel != null && channel.isTrafficChannel() && row != null)
        {
            removeChannelRow(channel, row);
        }
    }

    private void removeChannelRow(Channel channel, ChannelActivityRow row)
    {
        if(channel != null && row != null)
        {
            Set<ChannelActivityRow> rows = mChannelRows.get(channel);

            if(rows != null)
            {
                rows.remove(row);

                if(rows.isEmpty())
                {
                    mChannelRows.remove(channel);
                }
            }
        }
    }

    private void scheduleP25Classification(Channel channel, List<ChannelMetadata> metadataList)
    {
        cancelPendingP25Classification(channel);

        List<ChannelMetadata> pending = metadataList != null ? new ArrayList<>(metadataList) : Collections.emptyList();
        mPendingP25ClassificationMetadata.put(channel, pending);

        for(ChannelMetadata metadata: pending)
        {
            mPendingP25MetadataChannels.put(metadata, channel);
        }

        Timer timer = new Timer(P25_CLASSIFICATION_DELAY_MILLISECONDS, event -> {
            mPendingP25ClassificationTimers.remove(channel);
            List<ChannelMetadata> delayed = mPendingP25ClassificationMetadata.remove(channel);

            if(delayed != null)
            {
                for(ChannelMetadata metadata: delayed)
                {
                    mPendingP25MetadataChannels.remove(metadata);

                    if(isP25ControlMetadata(metadata, channel))
                    {
                        ensureConfiguredControlRow(channel, "p25-classification-control-state");
                        return;
                    }
                }

                addConventionalRows(channel, delayed, "p25-classification-conventional");
            }
        });
        timer.setRepeats(false);
        mPendingP25ClassificationTimers.put(channel, timer);
        timer.start();
    }

    private void cancelPendingP25Classification(Channel channel)
    {
        if(channel == null)
        {
            return;
        }

        Timer timer = mPendingP25ClassificationTimers.remove(channel);

        if(timer != null)
        {
            timer.stop();
        }

        List<ChannelMetadata> pending = mPendingP25ClassificationMetadata.remove(channel);

        if(pending != null)
        {
            for(ChannelMetadata metadata: pending)
            {
                mPendingP25MetadataChannels.remove(metadata);
            }
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
            if(isP25ControlMetadata(metadata, channel))
            {
                ensureConfiguredControlRow(channel, "conventional-row-control-state");
                continue;
            }

            long frequency = getFrequency(metadata, channel);
            Integer timeslot = metadata.hasTimeslot() ? metadata.getTimeslot() : null;
            ChannelActivityRow row = mConventionalTable.getOrCreate(conventionalKey(channel, frequency, timeslot),
                channel, ChannelActivityRow.Role.CONVENTIONAL, frequency, timeslot);
            row.setOrigin(ChannelActivityRow.Origin.CONVENTIONAL_METADATA);
            rememberRow(mConventionalTable, row);
            updateFromMetadata(row, metadata, channel);
            mMetadataRows.put(metadata, row);
            addChannelRow(channel, row);
            mConventionalTable.refresh(row);
            NowPlayingActivityDebugFeed.logRow("conventional-metadata-add", mConventionalTable, row, null, channel,
                null, "action=" + action);
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
        NowPlayingActivityDebugFeed.Snapshot before = NowPlayingActivityDebugFeed.capture(row);
        rememberRow(table, row);
        row.setDecoder(getDecoder(channel));
        addChannelRow(channel, row);
        table.refresh(row);
        NowPlayingActivityDebugFeed.logRow("configured-control-row", table, row, before, channel, null,
            "action=" + action);
        return row;
    }

    private void removeConventionalMetadataRow(ChannelMetadata metadata, ChannelActivityRow row)
    {
        mMetadataRows.remove(metadata);
        mConventionalTable.remove(row);
        mRowTables.remove(row);
        removeChannelRow(row.getChannel(), row);
        cancelPendingTrafficGrantAgeOut(row);
        cancelPendingControlIdle(row);
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
            cancelPendingTrafficGrantAgeOut(row);
            table.remove(row);
            mRowTables.remove(row);
            removeChannelRow(row.getChannel(), row);
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

    private String describeChannel(Channel channel)
    {
        if(channel == null)
        {
            return null;
        }

        return "id=" + channel.getChannelID() + " name=" + channel.getName() + " traffic=" + channel.isTrafficChannel();
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

    private boolean isP25TrunkedControlParent(Channel channel)
    {
        return isP25(channel) && channel != null && channel.getDecodeConfiguration() instanceof DecodeConfigP25 p25 &&
            p25.getTrafficChannelPoolSize() > 0;
    }

    private boolean isP25(Channel channel)
    {
        DecoderType decoder = channel != null && channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;
        return decoder == DecoderType.P25_PHASE1 || decoder == DecoderType.P25_PHASE2;
    }

    private boolean isP25ControlMetadata(ChannelMetadata metadata, Channel channel)
    {
        return isP25(channel) && metadata != null && metadata.getChannelStateIdentifier() != null &&
            metadata.getChannelStateIdentifier().getValue() == State.CONTROL;
    }

    private String getTrunkedTitle(Channel channel)
    {
        return buildP25Title(channel, mSiteIdentifiers.get(channel));
    }

    private String buildP25Title(Channel channel, P25SiteIdentifier siteIdentifier)
    {
        String decoder = getDecoder(channel);
        String wacn = formatIdentifier(siteIdentifier != null ? siteIdentifier.getWacn() : null, 5);
        String system = formatIdentifier(siteIdentifier != null ? siteIdentifier.getSystem() : null, 3);
        String rfss = formatIdentifier(siteIdentifier != null ? siteIdentifier.getRfss() : null, 2);
        String site = formatIdentifier(siteIdentifier != null ? siteIdentifier.getSite() : null, 2);
        String channelName = channel != null && channel.getName() != null ? channel.getName() : "";

        return (decoder != null ? decoder : "P25") + ": " + wacn + ":" + system + " " + rfss + "-" + site +
            " (" + channelName + ")";
    }

    private String formatIdentifier(Identifier<?> identifier, int width)
    {
        if(identifier != null && identifier.getValue() instanceof Number number)
        {
            return String.format("%0" + width + "X", number.intValue());
        }

        return identifier != null ? identifier.toString() : "?".repeat(width);
    }

    private P25SiteIdentifier merge(P25SiteIdentifier existing, P25SiteIdentifier update)
    {
        if(existing == null)
        {
            return update;
        }

        return new P25SiteIdentifier(
            update.getWacn() != null ? update.getWacn() : existing.getWacn(),
            update.getSystem() != null ? update.getSystem() : existing.getSystem(),
            update.getRfss() != null ? update.getRfss() : existing.getRfss(),
            update.getSite() != null ? update.getSite() : existing.getSite());
    }

    private boolean isBlank(String value)
    {
        return value == null || value.isBlank();
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
}
