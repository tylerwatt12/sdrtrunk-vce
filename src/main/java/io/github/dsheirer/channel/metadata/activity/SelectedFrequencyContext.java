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

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.ProcessingChain;

/**
 * Selected-frequency context broadcast by the Now Playing activity tables, including the logical site owner used by
 * the Events view when a control row is selected.
 *
 * @param frequency selected row frequency in hertz
 * @param timeslot selected row timeslot, when applicable
 * @param rowType selected activity row type
 * @param decoderHint selected row decoder text
 * @param sessionId stable selected activity table/session identifier
 * @param ownerChannel owner channel for the activity table, when applicable
 * @param rowChannel channel associated with the selected row, when applicable
 * @param processingChain exact-frequency active processing chain, when one exists
 * @param siteProcessingChain site-owner processing chain used by the Events view for control-channel selections
 * @param siteEventSelection true when the selected row represents a trunked site in the Events view
 * @param clearRequested true when listeners should detach and clear
 */
public record SelectedFrequencyContext(long frequency, Integer timeslot, ChannelActivityRow.Role rowType,
                                       String decoderHint, String sessionId, Channel ownerChannel, Channel rowChannel,
                                       ProcessingChain processingChain, ProcessingChain siteProcessingChain,
                                       boolean siteEventSelection, boolean clearRequested)
{
    public static SelectedFrequencyContext clear()
    {
        return new SelectedFrequencyContext(0, null, null, null, null, null, null, null, null, false, true);
    }

    public boolean hasFrequency()
    {
        return frequency > 0;
    }

    public boolean hasExactProcessingChain()
    {
        return processingChain != null;
    }

    /**
     * Events for a trunked site are produced by the site-owner control processing chain even when the selected
     * control frequency temporarily has no exact processing chain.
     */
    public ProcessingChain eventProcessingChain()
    {
        return siteEventSelection ? siteProcessingChain : processingChain;
    }
}
