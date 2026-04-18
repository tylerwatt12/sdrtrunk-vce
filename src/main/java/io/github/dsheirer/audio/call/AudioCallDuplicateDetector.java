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

package io.github.dsheirer.audio.call;

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import java.util.List;

/**
 * Duplicate-call comparison helpers for immutable audio call data.
 */
public final class AudioCallDuplicateDetector
{
    private AudioCallDuplicateDetector()
    {
    }

    /**
     * Checks both lists of identifiers to determine if there are talkgroups, patch groups, or radio identifiers that
     * are the same in both lists.
     */
    public static boolean isDuplicate(List<Identifier<?>> identifiers1, List<Identifier<?>> identifiers2)
    {
        for(Identifier<?> identifier1 : identifiers1)
        {
            if(identifier1 instanceof TalkgroupIdentifier talkgroupIdentifier1)
            {
                int talkgroup1 = talkgroupIdentifier1.getValue();

                for(Identifier<?> identifier2 : identifiers2)
                {
                    if((identifier2 instanceof TalkgroupIdentifier talkgroupIdentifier2 &&
                        talkgroupIdentifier2.getValue() == talkgroup1) ||
                        (identifier2 instanceof PatchGroupIdentifier patchGroupIdentifier2 &&
                            patchGroupIdentifier2.getValue().getPatchGroup().getValue() == talkgroup1))
                    {
                        return true;
                    }
                }
            }
            else if(identifier1 instanceof PatchGroupIdentifier patchGroupIdentifier1)
            {
                int talkgroup1 = patchGroupIdentifier1.getValue().getPatchGroup().getValue();

                for(Identifier<?> identifier2 : identifiers2)
                {
                    if((identifier2 instanceof TalkgroupIdentifier talkgroupIdentifier2 &&
                        talkgroupIdentifier2.getValue() == talkgroup1) ||
                        (identifier2 instanceof PatchGroupIdentifier patchGroupIdentifier2 &&
                            patchGroupIdentifier2.getValue().getPatchGroup().getValue() == talkgroup1))
                    {
                        return true;
                    }
                }
            }
            else if(identifier1 instanceof RadioIdentifier radioIdentifier1)
            {
                int radio1 = radioIdentifier1.getValue();

                for(Identifier<?> identifier2 : identifiers2)
                {
                    if(identifier2 instanceof RadioIdentifier radioIdentifier2 && radioIdentifier2.getValue() == radio1)
                    {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}
