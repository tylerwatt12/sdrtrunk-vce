/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
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

package io.github.dsheirer.gui.configuration.radioreference;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.rrapi.type.Talkgroup;

/**
 * Applies receiver-local playback policy while importing RadioReference talkgroups.
 */
final class RadioReferenceAliasPlaybackPolicy
{
    private RadioReferenceAliasPlaybackPolicy()
    {
    }

    /**
     * Marks a fully encrypted RadioReference talkgroup as muted when the user selected that import option.
     */
    static void apply(Alias alias, Talkgroup talkgroup, boolean muteFullyEncrypted)
    {
        if(alias != null && talkgroup != null && muteFullyEncrypted &&
            TalkgroupEncryption.lookup(talkgroup.getEncryptionState()) == TalkgroupEncryption.FULL)
        {
            alias.setCallPriority(Priority.DO_NOT_MONITOR);
        }
    }
}
