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
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioCallDuplicateDetectorTest
{
    @Test
    void detectsDuplicateTalkgroups()
    {
        List<Identifier<?>> identifiers1 = List.of(APCO25Talkgroup.create(100));
        List<Identifier<?>> identifiers2 = List.of(APCO25Talkgroup.create(100));

        assertTrue(AudioCallDuplicateDetector.isDuplicate(identifiers1, identifiers2));
    }

    @Test
    void detectsPatchGroupAndTalkgroupAsDuplicate()
    {
        PatchGroup patchGroup = new PatchGroup(APCO25Talkgroup.create(200));
        List<Identifier<?>> identifiers1 = List.of(APCO25PatchGroup.create(patchGroup));
        List<Identifier<?>> identifiers2 = List.of(APCO25Talkgroup.create(200));

        assertTrue(AudioCallDuplicateDetector.isDuplicate(identifiers1, identifiers2));
    }

    @Test
    void detectsDuplicateSourceRadios()
    {
        List<Identifier<?>> identifiers1 = List.of(APCO25RadioIdentifier.createFrom(9999));
        List<Identifier<?>> identifiers2 = List.of(APCO25RadioIdentifier.createFrom(9999));

        assertTrue(AudioCallDuplicateDetector.isDuplicate(identifiers1, identifiers2));
    }

    @Test
    void ignoresDifferentIdentifiers()
    {
        List<Identifier<?>> identifiers1 = List.of(APCO25Talkgroup.create(100), APCO25RadioIdentifier.createFrom(10));
        List<Identifier<?>> identifiers2 = List.of(APCO25Talkgroup.create(200), APCO25RadioIdentifier.createFrom(20));

        assertFalse(AudioCallDuplicateDetector.isDuplicate(identifiers1, identifiers2));
    }
}
