/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.identifier.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import java.util.List;
import org.junit.jupiter.api.Test;

class PatchGroupManagerTest
{
    @Test
    void harrisMembersAccumulateAndAReusedSequenceReplacesTheWholeGeneration()
    {
        PatchGroupManager manager = new PatchGroupManager();
        assertTrue(manager.addPatchGroup(patch(9, PatchGroup.Type.PATCH, 501), 1_000L));
        assertTrue(manager.addPatchGroup(patch(9, PatchGroup.Type.PATCH, 502), 2_000L));
        assertEquals(List.of(501, 502), members(manager, 2_000L));
        assertFalse(manager.addPatchGroup(patch(9, PatchGroup.Type.PATCH, 501), 3_000L));
        assertTrue(manager.addPatchGroup(patch(10, PatchGroup.Type.SIMULSELECT, 503), 4_000L));
        assertEquals(List.of(503), members(manager, 4_000L));
        assertEquals(10, manager.getPatchGroup(500, 4_000L).getValue().getVersion());
        assertEquals(PatchGroup.Type.SIMULSELECT, manager.getPatchGroup(500, 4_000L).getValue().getType());
        assertFalse(manager.addPatchGroup(patch(9, PatchGroup.Type.PATCH, 501), 3_500L));
        assertEquals(List.of(503), members(manager, 4_000L));
    }

    @Test
    void harrisCancellationMustMatchSequenceAndCannotReviveFromDelayedOrBareMessages()
    {
        PatchGroupManager manager = new PatchGroupManager();
        manager.addPatchGroup(patch(10, PatchGroup.Type.PATCH, 501), 4_000L);
        assertFalse(manager.deactivatePatchGroup(patch(9, PatchGroup.Type.PATCH), 5_000L));
        assertFalse(manager.deactivatePatchGroup(patch(10, PatchGroup.Type.PATCH), 3_000L));
        assertEquals(List.of(501), members(manager, 5_000L));
        assertTrue(manager.deactivatePatchGroup(patch(10, PatchGroup.Type.PATCH), 6_000L));
        assertNull(manager.getPatchGroup(500, 6_000L));
        assertFalse(manager.addPatchGroup(patch(10, PatchGroup.Type.PATCH, 501), 5_500L));
        assertFalse(manager.addPatchGroup(patch(10, PatchGroup.Type.PATCH, 501), 6_000L));
        PatchGroupIdentifier bare = patch(0, PatchGroup.Type.UNKNOWN);
        assertFalse(manager.addPatchGroup(bare, 7_000L));
        assertSame(bare, manager.update(bare, 7_000L));
        assertSame(bare, manager.update(bare, 40_000L));
        assertNull(manager.getPatchGroup(500, 40_000L));
        assertTrue(manager.addPatchGroup(patch(11, PatchGroup.Type.PATCH, 502), 41_000L));
        assertEquals(List.of(502), members(manager, 41_000L));
    }

    @Test
    void cancellationBeforeAnyActivationStillRejectsAnOlderActivation()
    {
        PatchGroupManager manager = new PatchGroupManager();
        assertFalse(manager.deactivatePatchGroup(patch(9, PatchGroup.Type.PATCH), 2_000L));
        assertFalse(manager.addPatchGroup(patch(9, PatchGroup.Type.PATCH, 501), 1_000L));
        assertNull(manager.getPatchGroup(500, 2_000L));
        assertTrue(manager.addPatchGroup(patch(10, PatchGroup.Type.PATCH, 502), 3_000L));
    }

    @Test
    void bareGrantsKeepKnownTypeAndSequenceWithoutRefreshingEveryMember()
    {
        PatchGroupManager manager = new PatchGroupManager();
        manager.addPatchGroup(patch(9, PatchGroup.Type.SIMULSELECT, 501), 1_000L);
        PatchGroupIdentifier additional = patch(9, PatchGroup.Type.SIMULSELECT, 502);
        additional.getValue().addPatchedRadio(APCO25RadioIdentifier.createTo(601));
        manager.addPatchGroup(additional, 20_000L);
        PatchGroupIdentifier bare = patch(0, PatchGroup.Type.UNKNOWN);
        assertFalse(manager.addPatchGroup(bare, 30_000L));
        PatchGroupIdentifier current = (PatchGroupIdentifier)manager.update(bare, 30_000L);
        assertEquals(PatchGroup.Type.SIMULSELECT, current.getValue().getType());
        assertEquals(9, current.getValue().getVersion());
        assertEquals(List.of(502), members(manager, 31_001L));
        assertEquals(List.of(601), manager.getPatchGroup(500, 31_001L).getValue()
            .getPatchedRadioIdentifiers().stream().map(identifier -> identifier.getValue()).toList());
        assertNull(manager.getPatchGroup(500, 60_001L));
        assertFalse(manager.addPatchGroup(bare, 60_002L));
        assertNull(manager.getPatchGroup(500, 60_002L));
    }

    @Test
    void motorolaDeletesOnlyNamedMembersUntilTheSupergroupTargetDissolvesIt()
    {
        PatchGroupManager manager = new PatchGroupManager();
        manager.addPatchGroup(patch(0, PatchGroup.Type.PATCH, 501, 502, 503), 1_000L);
        assertTrue(manager.removePatchGroup(patch(0, PatchGroup.Type.PATCH, 502), 2_000L));
        assertEquals(List.of(501, 503), members(manager, 2_000L));
        assertFalse(manager.removePatchGroup(patch(0, PatchGroup.Type.PATCH, 501), 1_500L));
        assertFalse(manager.removePatchGroup(patch(0, PatchGroup.Type.PATCH, 999), 3_000L));
        assertEquals(List.of(501, 503), members(manager, 3_000L));
        assertTrue(manager.removePatchGroup(patch(0, PatchGroup.Type.PATCH, 500), 4_000L));
        assertNull(manager.getPatchGroup(500, 4_000L));
        assertFalse(manager.addPatchGroup(patch(0, PatchGroup.Type.UNKNOWN), 5_000L));
        assertFalse(manager.addPatchGroup(patch(0, PatchGroup.Type.PATCH, 501), 3_500L));
    }

    @Test
    void emptyMotorolaDeletionAndFinalMemberDeletionBothDissolveThePatch()
    {
        PatchGroupManager manager = new PatchGroupManager();
        manager.addPatchGroup(patch(0, PatchGroup.Type.PATCH, 501), 1_000L);
        assertTrue(manager.removePatchGroup(patch(0, PatchGroup.Type.PATCH), 2_000L));
        assertNull(manager.getPatchGroup(500, 2_000L));
        manager.addPatchGroup(patch(0, PatchGroup.Type.PATCH, 502), 3_000L);
        assertTrue(manager.removePatchGroup(patch(0, PatchGroup.Type.PATCH, 502), 4_000L));
        assertNull(manager.getPatchGroup(500, 4_000L));
    }

    @Test
    void identifierLookupNeverBorrowsAFutureGenerationAndKnownUpdatesStillReplaceIt()
    {
        PatchGroupManager manager = new PatchGroupManager();
        manager.addPatchGroup(patch(9, PatchGroup.Type.PATCH, 501), 2_000L);
        var talkgroup = APCO25Talkgroup.create(500);
        assertSame(talkgroup, manager.update(talkgroup, 1_000L));
        assertNull(manager.getPatchGroup(500, 1_000L));
        assertSame(manager.getPatchGroup(500, 2_000L), manager.update(talkgroup, 2_000L));
        PatchGroupIdentifier newer = patch(10, PatchGroup.Type.SIMULSELECT, 502);
        assertSame(newer, manager.update(newer, 3_000L));
        assertEquals(List.of(502), members(manager, 3_000L));
        manager.clear();
        assertNull(manager.getPatchGroup(500, 3_000L));
    }

    private static PatchGroupIdentifier patch(int version, PatchGroup.Type type, int... members)
    {
        PatchGroup group = new PatchGroup(APCO25Talkgroup.create(500), version, type);
        for(int member: members)
        {
            group.addPatchedTalkgroup(APCO25Talkgroup.create(member));
        }
        return APCO25PatchGroup.create(group);
    }

    private static List<Integer> members(PatchGroupManager manager, long timestamp)
    {
        return manager.getPatchGroup(500, timestamp).getValue().getPatchedTalkgroupIdentifiers()
            .stream().map(identifier -> identifier.getValue()).toList();
    }
}
