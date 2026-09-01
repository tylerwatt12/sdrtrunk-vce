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
import io.github.dsheirer.rrapi.type.Talkgroup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioReferenceAliasPlaybackPolicyTest
{
    @Test
    void fullyEncryptedTalkgroupIsMutedWhenRequested()
    {
        Alias alias = new Alias("Encrypted");
        Talkgroup talkgroup = talkgroup(TalkgroupEncryption.FULL);

        RadioReferenceAliasPlaybackPolicy.apply(alias, talkgroup, true);

        assertFalse(alias.isListen());
    }

    @Test
    void partialEncryptionAndDisabledPolicyRemainAudible()
    {
        Alias partial = new Alias("Partial");
        RadioReferenceAliasPlaybackPolicy.apply(partial, talkgroup(TalkgroupEncryption.PARTIAL), true);
        assertTrue(partial.isListen());

        Alias disabled = new Alias("Disabled");
        RadioReferenceAliasPlaybackPolicy.apply(disabled, talkgroup(TalkgroupEncryption.FULL), false);
        assertTrue(disabled.isListen());
    }

    private static Talkgroup talkgroup(TalkgroupEncryption encryption)
    {
        Talkgroup talkgroup = new Talkgroup();
        talkgroup.setEncryptionState(switch(encryption)
        {
            case UNENCRYPTED -> 0;
            case PARTIAL -> 1;
            case FULL -> 2;
            case UNKNOWN -> -1;
        });
        return talkgroup;
    }
}
