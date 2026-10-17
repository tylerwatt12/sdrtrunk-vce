/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.module.decode.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dsheirer.audio.broadcast.TalkerAliasOwnershipFixtures;
import io.github.dsheirer.identifier.IdentifierCollection;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrunkedCallSnapshotTalkerAliasTest
{
    @Test
    void statisticsSnapshotsRequireATalkerAliasOwnedByTheirSource()
    {
        for(var sample: TalkerAliasOwnershipFixtures.samples())
        {
            assertEquals(sample.raw().getValue(), TrunkedCallSnapshot.talkerAlias(
                new IdentifierCollection(List.of(sample.source(), sample.owned()))));
            assertNull(TrunkedCallSnapshot.talkerAlias(
                new IdentifierCollection(List.of(sample.other(), sample.owned()))));
            assertNull(TrunkedCallSnapshot.talkerAlias(
                new IdentifierCollection(List.of(sample.source(), sample.raw()))));
        }
    }
}
