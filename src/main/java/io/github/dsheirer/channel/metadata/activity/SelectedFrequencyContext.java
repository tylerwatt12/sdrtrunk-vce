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
 * Selected channel context broadcast by the Now Playing activity tables.  A site selection has one stable owner and
 * follows that owner's active processing chain while its displayed control frequency changes.  Conventional and
 * traffic selections remain exact.
 *
 * @param frequency selected row frequency in hertz
 * @param timeslot selected row timeslot, when applicable
 * @param decoderHint selected row decoder text
 * @param ownerChannel owner channel for the activity table, when applicable
 * @param rowChannel channel associated with the selected row, when applicable
 * @param processingChain processing chain used by every lower view
 * @param selectionScope logical scope of the user selection
 * @param clearRequested true when listeners should detach and clear
 */
public record SelectedFrequencyContext(long frequency, Integer timeslot, String decoderHint, Channel ownerChannel,
                                       Channel rowChannel, ProcessingChain processingChain,
                                       ChannelActivitySelectionScope selectionScope,
                                       boolean clearRequested)
{
    public static SelectedFrequencyContext clear()
    {
        return new SelectedFrequencyContext(0, null, null, null, null, null, null, true);
    }

    public boolean hasFrequency()
    {
        return frequency > 0;
    }

    public boolean isSiteSelection()
    {
        return selectionScope == ChannelActivitySelectionScope.SITE;
    }

    /**
     * Indicates if this context represents the same user selection.  Binding details such as a site's current control
     * frequency and processing-chain instance are deliberately excluded.
     */
    public boolean hasSameLogicalSelection(SelectedFrequencyContext other)
    {
        if(other == null || clearRequested || other.clearRequested || selectionScope != other.selectionScope)
        {
            return false;
        }

        if(isSiteSelection())
        {
            return ownerChannel != null && ownerChannel == other.ownerChannel;
        }

        if(frequency != other.frequency)
        {
            return false;
        }

        if(ownerChannel != null || other.ownerChannel != null)
        {
            //A trunked traffic child is processing-chain transport state.  It can be released to the owner and later
            //replaced while the user's exact frequency/timeslot selection remains unchanged.
            return ownerChannel != null && ownerChannel == other.ownerChannel && sameTimeslot(other);
        }

        //Conventional configurations have no site owner, so channel identity disambiguates equal-frequency rows.
        return rowChannel != null && rowChannel == other.rowChannel && sameTimeslot(other);
    }

    private boolean sameTimeslot(SelectedFrequencyContext other)
    {
        return timeslot == null ? other.timeslot == null : timeslot.equals(other.timeslot);
    }
}
