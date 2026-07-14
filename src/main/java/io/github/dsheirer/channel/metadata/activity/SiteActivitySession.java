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

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Session-only activity state for one started trunked configuration item.
 *
 * Control and traffic rows are intentionally stored in separate maps so that a frequency can be both a learned
 * traffic frequency and a learned control frequency during the same session without changing row identity.
 */
public class SiteActivitySession
{
    private final String mSessionId;
    private final Channel mParentChannel;
    private final ChannelActivityTableModel mTableModel;
    private final Map<Long,ChannelActivityRow> mControlRows = new HashMap<>();
    private final Map<String,ChannelActivityRow> mTrafficRows = new HashMap<>();
    private final Map<Long,String> mCallsigns = new HashMap<>();
    private Long mCurrentControlFrequency;

    public SiteActivitySession(Channel parentChannel, ChannelActivityTableModel tableModel)
    {
        mParentChannel = parentChannel;
        mTableModel = tableModel;
        mSessionId = parentChannel != null ? String.valueOf(parentChannel.getChannelID()) : "unknown";
    }

    public String getSessionId()
    {
        return mSessionId;
    }

    public Channel getParentChannel()
    {
        return mParentChannel;
    }

    public ChannelActivityTableModel getTableModel()
    {
        return mTableModel;
    }

    public ChannelActivityRow configuredControl(long frequency)
    {
        if(frequency <= 0)
        {
            return null;
        }

        ChannelActivityRow row = getOrCreateControlRow(frequency);

        if(!row.hasTag(ChannelTag.CURRENT_CONTROL) && !row.hasTag(ChannelTag.ALTERNATE_CONTROL))
        {
            row.setRole(ChannelActivityRow.Role.CONFIGURED_CONTROL);
            row.setOrigin(ChannelActivityRow.Origin.CONFIGURED_CONTROL);
            row.setState(State.IDLE);
            row.clearCallDetails();
        }

        return row;
    }

    public ControlUpdate currentControl(IChannelDescriptor channelDescriptor)
    {
        if(channelDescriptor == null || channelDescriptor.getDownlinkFrequency() <= 0)
        {
            return ControlUpdate.empty();
        }

        return currentControl(channelDescriptor.getDownlinkFrequency(), getLcn(channelDescriptor));
    }

    public ControlUpdate currentControl(long frequency, String lcn)
    {
        if(frequency <= 0)
        {
            return ControlUpdate.empty();
        }

        List<ChannelActivityRow> demoted = new ArrayList<>();

        if(mCurrentControlFrequency != null && mCurrentControlFrequency != frequency)
        {
            ChannelActivityRow previous = mControlRows.get(mCurrentControlFrequency);

            if(previous != null)
            {
                demoteCurrentControl(previous);
                demoted.add(previous);
            }
        }

        ChannelActivityRow current = getOrCreateControlRow(frequency);
        current.setLcn(lcn);
        current.setRole(ChannelActivityRow.Role.CURRENT_CONTROL);
        current.setOrigin(ChannelActivityRow.Origin.DECODED_CURRENT_CONTROL);
        current.setState(State.CONTROL);
        current.clearCallDetails();
        mCurrentControlFrequency = frequency;
        addTag(frequency, ChannelTag.CURRENT_CONTROL);

        return new ControlUpdate(current, demoted);
    }

    public ChannelActivityRow alternateControl(IChannelDescriptor channelDescriptor)
    {
        if(channelDescriptor == null || channelDescriptor.getDownlinkFrequency() <= 0)
        {
            return null;
        }

        return alternateControl(channelDescriptor.getDownlinkFrequency(), getLcn(channelDescriptor));
    }

    public ChannelActivityRow alternateControl(long frequency, String lcn)
    {
        if(frequency <= 0)
        {
            return null;
        }

        ChannelActivityRow row = getOrCreateControlRow(frequency);
        row.setLcn(lcn);

        if(!row.hasTag(ChannelTag.CURRENT_CONTROL))
        {
            row.setRole(ChannelActivityRow.Role.ALTERNATE_CONTROL);
            row.setOrigin(ChannelActivityRow.Origin.DECODED_ALTERNATE_CONTROL);
            row.setState(State.IDLE);
            row.clearCallDetails();
            addTag(frequency, ChannelTag.ALTERNATE_CONTROL);
        }

        return row;
    }

    public ChannelActivityRow announcedData(long frequency, String lcn)
    {
        if(frequency <= 0)
        {
            return null;
        }

        String key = trafficKey(frequency, null);
        ChannelActivityRow row = mTrafficRows.get(key);

        if(row == null)
        {
            row = mTableModel.getOrCreate(key, mParentChannel, ChannelActivityRow.Role.TRAFFIC, frequency, null);
            mTrafficRows.put(key, row);
            inheritFrequencyTags(row);
        }

        row.setChannel(mParentChannel);
        row.setFrequency(frequency);
        row.setLcn(lcn);
        row.setOrigin(ChannelActivityRow.Origin.DECODED_DATA_ANNOUNCEMENT);
        addTag(frequency, ChannelTag.DATA_ANNOUNCED);
        return row;
    }

    public ChannelActivityRow traffic(Channel trafficChannel, IChannelDescriptor channelDescriptor)
    {
        if(channelDescriptor == null || channelDescriptor.getDownlinkFrequency() <= 0)
        {
            return null;
        }

        long frequency = channelDescriptor.getDownlinkFrequency();
        Integer timeslot = getTimeslot(channelDescriptor);
        String key = trafficKey(frequency, timeslot);
        Channel rowChannel = trafficChannel != null ? trafficChannel : mParentChannel;
        ChannelActivityRow row = mTrafficRows.get(key);

        if(row == null)
        {
            row = mTableModel.getOrCreate(key, rowChannel, ChannelActivityRow.Role.TRAFFIC, frequency, timeslot);
            row.setOrigin(ChannelActivityRow.Origin.TRAFFIC_GRANT);
            mTrafficRows.put(key, row);
            inheritFrequencyTags(row);
        }

        row.setChannel(rowChannel);
        row.setRole(ChannelActivityRow.Role.TRAFFIC);
        row.setOrigin(ChannelActivityRow.Origin.TRAFFIC_GRANT);
        row.setFrequency(frequency);
        row.setTimeslot(timeslot);
        row.setLcn(getLcn(channelDescriptor));

        return row;
    }

    public ChannelActivityRow traffic(long frequency, Integer timeslot)
    {
        ChannelActivityRow row = mTrafficRows.get(trafficKey(frequency, timeslot));

        if(row == null && timeslot != null)
        {
            row = mTrafficRows.get(trafficKey(frequency, null));
        }

        return row;
    }

    public List<ChannelActivityRow> getTrafficRows()
    {
        return new ArrayList<>(mTrafficRows.values());
    }

    /**
     * Stores the latest BSI callsign for a frequency and applies it to all rows sharing that channel.
     */
    public List<ChannelActivityRow> callsign(long frequency, String callsign)
    {
        if(frequency <= 0 || callsign == null || callsign.isBlank())
        {
            return List.of();
        }

        String value = callsign.trim();
        mCallsigns.put(frequency, value);
        List<ChannelActivityRow> updated = new ArrayList<>();
        ChannelActivityRow control = mControlRows.get(frequency);

        if(control != null)
        {
            control.setCallsign(value);
            updated.add(control);
        }

        for(ChannelActivityRow traffic: mTrafficRows.values())
        {
            if(traffic.getFrequency() == frequency)
            {
                traffic.setCallsign(value);
                updated.add(traffic);
            }
        }

        return updated;
    }

    public void addTag(long frequency, ChannelTag tag)
    {
        ChannelActivityRow control = mControlRows.get(frequency);

        if(control != null)
        {
            control.addTag(tag);
        }

        for(ChannelActivityRow traffic: mTrafficRows.values())
        {
            if(traffic.getFrequency() == frequency)
            {
                traffic.addTag(tag);
            }
        }
    }

    public void removeTag(long frequency, ChannelTag tag)
    {
        ChannelActivityRow control = mControlRows.get(frequency);

        if(control != null)
        {
            control.removeTag(tag);
        }

        for(ChannelActivityRow traffic: mTrafficRows.values())
        {
            if(traffic.getFrequency() == frequency)
            {
                traffic.removeTag(tag);
            }
        }
    }

    /**
     * Rows owned by this session that should be updated when a channel stops.
     * The parent channel owns the whole session; traffic channels only own the traffic rows currently attached to them.
     */
    public List<ChannelActivityRow> getRowsForStoppedChannel(Channel channel)
    {
        if(channel == null)
        {
            return List.of();
        }

        if(channel == mParentChannel)
        {
            return mTableModel.getRows();
        }

        List<ChannelActivityRow> rows = new ArrayList<>();

        for(ChannelActivityRow row: mTrafficRows.values())
        {
            if(row.getChannel() == channel)
            {
                rows.add(row);
            }
        }

        return rows;
    }

    /**
     * Releases a stopped traffic channel from a persistent traffic row without removing the row from this session.
     */
    public void releaseTrafficChannel(Channel trafficChannel, ChannelActivityRow row)
    {
        if(trafficChannel != null && trafficChannel.isTrafficChannel() && row != null &&
            row.getChannel() == trafficChannel)
        {
            row.setChannel(mParentChannel);
        }
    }

    public List<ChannelActivityRow> removeConfiguredOnlyControlsExcept(long configuredFrequency)
    {
        List<ChannelActivityRow> remove = new ArrayList<>();

        for(ChannelActivityRow row: new ArrayList<>(mControlRows.values()))
        {
            if(row.getFrequency() != configuredFrequency &&
                row.getOrigin() == ChannelActivityRow.Origin.CONFIGURED_CONTROL &&
                !row.hasTag(ChannelTag.CURRENT_CONTROL) && !row.hasTag(ChannelTag.ALTERNATE_CONTROL))
            {
                remove.add(row);
            }
        }

        for(ChannelActivityRow row: remove)
        {
            mControlRows.remove(row.getFrequency());
        }

        return remove;
    }

    /**
     * Reconciles decoded control rows to the latest promoted site snapshot while preserving the configured frequency.
     */
    public List<ChannelActivityRow> reconcilePromotedControls(Set<Long> promotedFrequencies,
                                                              long configuredFrequency)
    {
        Set<Long> promoted = promotedFrequencies != null ? promotedFrequencies : Set.of();
        List<ChannelActivityRow> remove = new ArrayList<>();

        for(ChannelActivityRow row: new ArrayList<>(mControlRows.values()))
        {
            if(row.getOrigin() == ChannelActivityRow.Origin.CONFIGURED_CONTROL ||
                promoted.contains(row.getFrequency()))
            {
                continue;
            }

            if(row.getFrequency() == configuredFrequency)
            {
                row.setRole(ChannelActivityRow.Role.CONFIGURED_CONTROL);
                row.setOrigin(ChannelActivityRow.Origin.CONFIGURED_CONTROL);
                row.removeTag(ChannelTag.CURRENT_CONTROL);
                row.setState(State.IDLE);
                row.clearCallDetails();
                mTableModel.refresh(row);
            }
            else
            {
                mControlRows.remove(row.getFrequency());
                remove.add(row);
            }

            if(mCurrentControlFrequency != null && mCurrentControlFrequency == row.getFrequency())
            {
                mCurrentControlFrequency = null;
            }
        }

        return remove;
    }

    public void forget(ChannelActivityRow row)
    {
        if(row != null)
        {
            if(row.isControlRow())
            {
                mControlRows.remove(row.getFrequency());

                if(mCurrentControlFrequency != null && mCurrentControlFrequency == row.getFrequency())
                {
                    mCurrentControlFrequency = null;
                }
            }
            else if(row.getRole() == ChannelActivityRow.Role.TRAFFIC)
            {
                mTrafficRows.remove(trafficKey(row.getFrequency(), row.getTimeslot()));
            }
        }
    }

    public String controlKey(long frequency)
    {
        return "CONTROL:" + mSessionId + ":" + frequency;
    }

    public String trafficKey(long frequency, Integer timeslot)
    {
        return "TRAFFIC:" + mSessionId + ":" + frequency + ":" + (timeslot != null ? timeslot : 0);
    }

    private ChannelActivityRow getOrCreateControlRow(long frequency)
    {
        ChannelActivityRow row = mControlRows.get(frequency);

        if(row == null)
        {
            row = mTableModel.getOrCreate(controlKey(frequency), mParentChannel,
                ChannelActivityRow.Role.CONFIGURED_CONTROL, frequency, null);
            row.setOrigin(ChannelActivityRow.Origin.CONFIGURED_CONTROL);
            mControlRows.put(frequency, row);
            inheritFrequencyTags(row);
        }

        row.setChannel(mParentChannel);
        row.setFrequency(frequency);
        row.setTimeslot(null);
        applyCallsign(row);

        return row;
    }

    private void inheritFrequencyTags(ChannelActivityRow row)
    {
        ChannelActivityRow control = mControlRows.get(row.getFrequency());

        if(control != null && control != row)
        {
            row.addTags(control.getTags());
        }

        for(ChannelActivityRow traffic: mTrafficRows.values())
        {
            if(traffic != row && traffic.getFrequency() == row.getFrequency())
            {
                row.addTags(traffic.getTags());
            }
        }

        applyCallsign(row);
    }

    private void applyCallsign(ChannelActivityRow row)
    {
        if(row != null)
        {
            row.setCallsign(mCallsigns.get(row.getFrequency()));
        }
    }

    private void demoteCurrentControl(ChannelActivityRow row)
    {
        removeTag(row.getFrequency(), ChannelTag.CURRENT_CONTROL);
        row.setRole(ChannelActivityRow.Role.CONFIGURED_CONTROL);
        row.setState(State.IDLE);
        row.clearCallDetails();
    }

    private String getLcn(IChannelDescriptor channelDescriptor)
    {
        if(channelDescriptor == null)
        {
            return null;
        }

        String lcn = channelDescriptor.toString();
        Integer timeslot = getTimeslot(channelDescriptor);

        if(timeslot != null)
        {
            lcn = lcn.replace(" TS1", "").replace(" TS2", "");
            return lcn + " TS:" + timeslot;
        }

        return lcn;
    }

    private Integer getTimeslot(IChannelDescriptor channelDescriptor)
    {
        if(channelDescriptor instanceof APCO25Channel apco25Channel && apco25Channel.isTDMAChannel())
        {
            return apco25Channel.getTimeslot();
        }

        return null;
    }

    public static class ControlUpdate
    {
        private static final ControlUpdate EMPTY = new ControlUpdate(null, List.of());

        private final ChannelActivityRow mCurrent;
        private final List<ChannelActivityRow> mDemoted;

        public ControlUpdate(ChannelActivityRow current, List<ChannelActivityRow> demoted)
        {
            mCurrent = current;
            mDemoted = demoted != null ? demoted : List.of();
        }

        public static ControlUpdate empty()
        {
            return EMPTY;
        }

        public ChannelActivityRow current()
        {
            return mCurrent;
        }

        public List<ChannelActivityRow> demoted()
        {
            return mDemoted;
        }
    }
}
