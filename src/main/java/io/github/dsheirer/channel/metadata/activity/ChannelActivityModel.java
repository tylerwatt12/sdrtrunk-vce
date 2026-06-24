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
import io.github.dsheirer.module.decode.p25.P25SiteIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.properties.SystemProperties;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Session-only Now Playing activity model that keeps stable rows independent from temporary traffic chains.
 */
public class ChannelActivityModel implements IChannelMetadataUpdateListener
{
    public static final String RETAIN_IDLE_CALL_DETAILS_PROPERTY =
        "now.playing.activity.retain.idle.call.details";

    private final AliasModel mAliasModel;
    private final ChannelActivityTableModel mConventionalTable =
        new ChannelActivityTableModel("Conventional", null, false);
    private final Map<Channel,ChannelActivityTableModel> mTrunkedTables = new IdentityHashMap<>();
    private final Set<Integer> mClosedTrunkedChannelIds = new HashSet<>();
    private final Map<ChannelMetadata,ChannelActivityRow> mMetadataRows = new IdentityHashMap<>();
    private final Map<Channel,List<ChannelActivityRow>> mChannelRows = new IdentityHashMap<>();
    private final Map<Channel,P25SiteIdentifier> mSiteIdentifiers = new IdentityHashMap<>();
    private final List<Listener<ChannelActivityTableModel>> mTableAddListeners = new ArrayList<>();
    private final List<Listener<ChannelActivityTableModel>> mTableChangeListeners = new ArrayList<>();

    public ChannelActivityModel(AliasModel aliasModel)
    {
        mAliasModel = aliasModel;
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

            if(isP25TrunkedCandidate(channel))
            {
                ChannelActivityTableModel table = getOrCreateTrunkedTable(channel);

                if(table != null)
                {
                    long frequency = getConfiguredFrequency(channel);

                    if(frequency > 0)
                    {
                        ChannelActivityRow row = table.getOrCreate(controlKey(channel, frequency), channel,
                            ChannelActivityRow.Role.CURRENT_CONTROL, frequency, null);
                        row.setState(State.CONTROL);
                        row.setDecoder(getDecoder(channel));
                        table.refresh(row);
                    }
                }
            }
            else if(metadataList != null)
            {
                for(ChannelMetadata metadata: metadataList)
                {
                    long frequency = getFrequency(metadata, channel);
                    Integer timeslot = metadata.hasTimeslot() ? metadata.getTimeslot() : null;
                    ChannelActivityRow row = mConventionalTable.getOrCreate(conventionalKey(channel, frequency, timeslot),
                        channel, ChannelActivityRow.Role.CONVENTIONAL, frequency, timeslot);
                    updateFromMetadata(row, metadata, channel);
                    mMetadataRows.put(metadata, row);
                    addChannelRow(channel, row);
                }
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
            List<ChannelActivityRow> rows = mChannelRows.get(channel);

            if(rows != null)
            {
                for(ChannelActivityRow row: rows)
                {
                    setIdle(row);
                }

                mConventionalTable.sortAndRefresh();
            }

            ChannelActivityTableModel trunked = mTrunkedTables.get(channel);

            if(trunked != null)
            {
                for(ChannelActivityRow row: trunked.getRows())
                {
                    if(row.getChannel() == channel && row.getRole() == ChannelActivityRow.Role.CURRENT_CONTROL)
                    {
                        setIdle(row);
                    }
                }

                trunked.sortAndRefresh();
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
                updateFromMetadata(row, channelMetadata, row.getChannel());
                mConventionalTable.refresh(row);
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
            ChannelActivityTableModel table = getOrCreateTrunkedTable(parentChannel);

            if(table == null)
            {
                return;
            }

            for(ChannelActivityRow row: table.getRows())
            {
                if(row.getRole() == ChannelActivityRow.Role.CURRENT_CONTROL && row.getFrequency() != frequency)
                {
                    row.setRole(ChannelActivityRow.Role.ALTERNATE_CONTROL);
                    setIdle(row);
                }
            }

            ChannelActivityRow row = table.getOrCreate(controlKey(parentChannel, frequency), parentChannel,
                ChannelActivityRow.Role.CURRENT_CONTROL, frequency, null);
            row.setState(State.CONTROL);
            row.setDecoder(getDecoder(parentChannel));
            table.sortAndRefresh();
        });
    }

    public void p25CurrentControl(Channel parentChannel, IChannelDescriptor channelDescriptor)
    {
        if(parentChannel == null || channelDescriptor == null || channelDescriptor.getDownlinkFrequency() <= 0)
        {
            return;
        }

        runOnSwing(() -> {
            ChannelActivityTableModel table = getOrCreateTrunkedTable(parentChannel);

            if(table == null)
            {
                return;
            }

            long frequency = channelDescriptor.getDownlinkFrequency();

            for(ChannelActivityRow row: table.getRows())
            {
                if(row.getRole() == ChannelActivityRow.Role.CURRENT_CONTROL && row.getFrequency() != frequency)
                {
                    row.setRole(ChannelActivityRow.Role.ALTERNATE_CONTROL);
                    setIdle(row);
                }
            }

            ChannelActivityRow row = table.getOrCreate(controlKey(parentChannel, frequency), parentChannel,
                ChannelActivityRow.Role.CURRENT_CONTROL, frequency, getTimeslot(channelDescriptor));
            row.setLcn(getLcn(channelDescriptor));
            row.setState(State.CONTROL);
            row.setDecoder(getDecoder(parentChannel));
            table.sortAndRefresh();
        });
    }

    public void p25AlternateControl(Channel parentChannel, IChannelDescriptor channelDescriptor)
    {
        if(parentChannel == null || channelDescriptor == null || channelDescriptor.getDownlinkFrequency() <= 0)
        {
            return;
        }

        runOnSwing(() -> {
            ChannelActivityTableModel table = getOrCreateTrunkedTable(parentChannel);

            if(table == null)
            {
                return;
            }

            long frequency = channelDescriptor.getDownlinkFrequency();
            ChannelActivityRow row = table.getOrCreate(controlKey(parentChannel, frequency), parentChannel,
                ChannelActivityRow.Role.ALTERNATE_CONTROL, frequency, getTimeslot(channelDescriptor));
            row.setLcn(getLcn(channelDescriptor));
            setIdle(row);
            row.setDecoder(getDecoder(parentChannel));
            table.sortAndRefresh();
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
            ChannelActivityTableModel table = getOrCreateTrunkedTable(parentChannel);

            if(table == null)
            {
                return;
            }

            long frequency = channelDescriptor.getDownlinkFrequency();
            Integer timeslot = getTimeslot(channelDescriptor);
            Channel rowChannel = trafficChannel != null ? trafficChannel : parentChannel;
            ChannelActivityRow row = table.getOrCreate(trafficKey(parentChannel, frequency, timeslot), rowChannel,
                ChannelActivityRow.Role.TRAFFIC, frequency, timeslot);
            row.setLcn(getLcn(channelDescriptor));
            row.setState(getState(eventType));
            row.setDecoder(getDecoder(rowChannel));
            updateCallDetails(row, identifiers, rowChannel);
            addChannelRow(rowChannel, row);
            table.sortAndRefresh();
        });
    }

    public void p25TrafficIdle(Channel parentChannel, long frequency, Integer timeslot)
    {
        if(parentChannel == null || frequency <= 0)
        {
            return;
        }

        runOnSwing(() -> {
            ChannelActivityTableModel table = mTrunkedTables.get(parentChannel);

            if(table != null)
            {
                ChannelActivityRow row = table.get(trafficKey(parentChannel, frequency, timeslot));

                if(row == null && timeslot != null)
                {
                    row = table.get(trafficKey(parentChannel, frequency, null));
                }

                if(row != null)
                {
                    setIdle(row);
                    table.refresh(row);
                }
            }
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
            ChannelActivityTableModel table = mTrunkedTables.get(parentChannel);

            if(table != null && isBlank(parentChannel.getName()))
            {
                String title = buildP25Title(parentChannel, merged);

                if(title != null && !title.equals(table.getTitle()))
                {
                    table.setTitle(title);

                    for(Listener<ChannelActivityTableModel> listener: mTableChangeListeners)
                    {
                        listener.receive(table);
                    }
                }
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
        row.setState(stateIdentifier != null ? stateIdentifier.getValue() : State.IDLE);

        DecoderTypeConfigurationIdentifier decoderIdentifier = metadata.getDecoderTypeConfigurationIdentifier();
        row.setDecoder(decoderIdentifier != null ? decoderIdentifier.toString() : getDecoder(channel));

        row.setSource(metadata.getFromIdentifier());
        row.setSourceAliases(metadata.getFromIdentifierAliases());
        row.setTarget(metadata.getToIdentifier());
        row.setTargetAliases(metadata.getToIdentifierAliases());

        if(row.getState() == State.IDLE && !retainIdleCallDetails())
        {
            row.clearCallDetails();
        }
    }

    private void updateCallDetails(ChannelActivityRow row, IdentifierCollection identifiers, Channel channel)
    {
        if(identifiers != null)
        {
            Identifier<?> source = identifiers.getFromIdentifier();
            Identifier<?> target = identifiers.getToIdentifier();
            row.setSource(source);
            row.setSourceAliases(getAliases(source, identifiers, channel));
            row.setTarget(target);
            row.setTargetAliases(getAliases(target, identifiers, channel));
        }
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

    private void addChannelRow(Channel channel, ChannelActivityRow row)
    {
        if(channel != null && row != null)
        {
            List<ChannelActivityRow> rows = mChannelRows.computeIfAbsent(channel, key -> new ArrayList<>());

            if(!rows.contains(row))
            {
                rows.add(row);
            }
        }
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

    private boolean isP25TrunkedCandidate(Channel channel)
    {
        DecoderType decoder = channel.getDecodeConfiguration() != null ? channel.getDecodeConfiguration().getDecoderType() : null;
        return (decoder == DecoderType.P25_PHASE1 || decoder == DecoderType.P25_PHASE2) &&
            channel.getSourceConfiguration() instanceof SourceConfigTunerMultipleFrequency;
    }

    private String getTrunkedTitle(Channel channel)
    {
        if(!isBlank(channel.getName()))
        {
            return channel.getName();
        }

        String title = buildP25Title(channel, mSiteIdentifiers.get(channel));

        if(title != null)
        {
            return title;
        }

        DecoderType decoder = channel.getDecodeConfiguration() != null ? channel.getDecodeConfiguration().getDecoderType() : null;
        return (decoder != null ? decoder.getShortDisplayString() : "P25") + ": " + channel.toString();
    }

    private String buildP25Title(Channel channel, P25SiteIdentifier siteIdentifier)
    {
        if(siteIdentifier != null && siteIdentifier.getWacn() != null && siteIdentifier.getSystem() != null &&
            siteIdentifier.getRfss() != null && siteIdentifier.getSite() != null)
        {
            String decoder = getDecoder(channel);
            return (decoder != null ? decoder : "P25") + ": " + formatIdentifier(siteIdentifier.getWacn(), 5) + ":" +
                formatIdentifier(siteIdentifier.getSystem(), 3) + " " +
                formatIdentifier(siteIdentifier.getRfss(), 2) + "-" +
                formatIdentifier(siteIdentifier.getSite(), 2);
        }

        return null;
    }

    private String formatIdentifier(Identifier<?> identifier, int width)
    {
        if(identifier != null && identifier.getValue() instanceof Number number)
        {
            return String.format("%0" + width + "X", number.intValue());
        }

        return identifier != null ? identifier.toString() : "";
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

    private Integer getLcn(IChannelDescriptor channelDescriptor)
    {
        if(channelDescriptor instanceof APCO25Channel apco25Channel)
        {
            return apco25Channel.getValue().getDownlinkLogicalChannelNumber();
        }

        return null;
    }

    private Integer getTimeslot(IChannelDescriptor channelDescriptor)
    {
        if(channelDescriptor instanceof APCO25Channel apco25Channel && apco25Channel.isTDMAChannel())
        {
            return apco25Channel.getTimeslot();
        }

        return null;
    }

    private State getState(DecodeEventType eventType)
    {
        if(eventType != null)
        {
            if(DecodeEventType.VOICE_CALLS_ENCRYPTED.contains(eventType) || eventType == DecodeEventType.DATA_CALL_ENCRYPTED)
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
        return "CONV:" + channel.getChannelID() + ":" + frequency + ":" + (timeslot != null ? timeslot : 0);
    }

    private String controlKey(Channel channel, long frequency)
    {
        return "CTRL:" + channel.getChannelID() + ":" + frequency;
    }

    private String trafficKey(Channel channel, long frequency, Integer timeslot)
    {
        return "TRAF:" + channel.getChannelID() + ":" + frequency + ":" + (timeslot != null ? timeslot : 0);
    }

    private boolean retainIdleCallDetails()
    {
        return SystemProperties.getInstance().get(RETAIN_IDLE_CALL_DETAILS_PROPERTY, false);
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
