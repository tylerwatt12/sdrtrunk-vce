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
 * @param decoderHint selected row decoder text
 * @param ownerChannel owner channel for the activity table, when applicable
 * @param rowChannel channel associated with the selected row, when applicable
 * @param processingChain exact-frequency active processing chain, when one exists
 * @param eventProcessingChain processing chain used by the Events view
 * @param selectionScope logical scope of the user selection
 * @param clearRequested true when listeners should detach and clear
 */
public record SelectedFrequencyContext(long frequency, Integer timeslot, String decoderHint, Channel ownerChannel,
                                       Channel rowChannel, ProcessingChain processingChain,
                                       ProcessingChain eventProcessingChain,
                                       ChannelActivitySelectionScope selectionScope,
                                       boolean clearRequested)
{
    public static SelectedFrequencyContext clear()
    {
        return new SelectedFrequencyContext(0, null, null, null, null, null, null, null, true);
    }

    public boolean hasFrequency()
    {
        return frequency > 0;
    }

    public boolean hasExactProcessingChain()
    {
        return processingChain != null;
    }

    public boolean isSiteSelection()
    {
        return selectionScope == ChannelActivitySelectionScope.SITE;
    }
}
