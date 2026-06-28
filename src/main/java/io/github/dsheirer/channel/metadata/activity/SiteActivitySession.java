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

/**
 * Session-only activity state for one started trunked playlist item.
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

        if(row.getControlRole() == ChannelActivityRow.ControlRole.NONE)
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

        long frequency = channelDescriptor.getDownlinkFrequency();
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
        current.setLcn(getLcn(channelDescriptor));
        current.setRole(ChannelActivityRow.Role.CURRENT_CONTROL);
        current.setOrigin(ChannelActivityRow.Origin.DECODED_CURRENT_CONTROL);
        current.setControlRole(ChannelActivityRow.ControlRole.CURRENT);
        current.setState(State.CONTROL);
        current.clearCallDetails();
        mCurrentControlFrequency = frequency;

        return new ControlUpdate(current, demoted);
    }

    public ChannelActivityRow alternateControl(IChannelDescriptor channelDescriptor)
    {
        if(channelDescriptor == null || channelDescriptor.getDownlinkFrequency() <= 0)
        {
            return null;
        }

        long frequency = channelDescriptor.getDownlinkFrequency();
        ChannelActivityRow row = getOrCreateControlRow(frequency);
        row.setLcn(getLcn(channelDescriptor));

        if(row.getControlRole() != ChannelActivityRow.ControlRole.CURRENT)
        {
            row.setRole(ChannelActivityRow.Role.ALTERNATE_CONTROL);
            row.setOrigin(ChannelActivityRow.Origin.DECODED_ALTERNATE_CONTROL);
            row.setControlRole(ChannelActivityRow.ControlRole.ALTERNATE);
            row.setState(State.IDLE);
            row.clearCallDetails();
        }

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

    public List<ChannelActivityRow> removeConfiguredOnlyControlsExcept(long configuredFrequency)
    {
        List<ChannelActivityRow> remove = new ArrayList<>();

        for(ChannelActivityRow row: new ArrayList<>(mControlRows.values()))
        {
            if(row.getFrequency() != configuredFrequency &&
                row.getOrigin() == ChannelActivityRow.Origin.CONFIGURED_CONTROL &&
                row.getControlRole() == ChannelActivityRow.ControlRole.NONE)
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
        }

        row.setChannel(mParentChannel);
        row.setFrequency(frequency);
        row.setTimeslot(null);

        return row;
    }

    private void demoteCurrentControl(ChannelActivityRow row)
    {
        row.setControlRole(ChannelActivityRow.ControlRole.NONE);
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
