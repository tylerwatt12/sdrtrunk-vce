/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.audio.broadcast.openmhz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static io.github.dsheirer.audio.broadcast.TalkerAliasOwnershipFixtures.recording;

import io.github.dsheirer.audio.broadcast.TalkerAliasOwnershipFixtures;
import org.junit.jupiter.api.Test;

class OpenMHzBroadcasterTest
{
    @Test
    void canonicalRadioNameCannotBeAttachedToAnUnknownNumericSource()
    {
        var sample = TalkerAliasOwnershipFixtures.canonicalWithoutNumericSource();
        assertEquals("", OpenMHzBroadcaster.getTalkerAlias(recording(sample.source(), sample.owned())));
    }

    @Test
    void uploadAliasRequiresTheCurrentRadioOwnerAcrossProtocols()
    {
        for(var sample: TalkerAliasOwnershipFixtures.samples())
        {
            assertEquals(sample.raw().getValue(),
                OpenMHzBroadcaster.getTalkerAlias(recording(sample.source(), sample.owned())));
            assertEquals("", OpenMHzBroadcaster.getTalkerAlias(recording(sample.other(), sample.owned())));
            assertEquals("", OpenMHzBroadcaster.getTalkerAlias(recording(sample.source(), sample.raw())));
            assertEquals("", OpenMHzBroadcaster.getTalkerAlias(recording(sample.owned())));
        }
    }
}
