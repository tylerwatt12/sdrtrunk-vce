/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.identifier;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.IdentityResolutionEvidence;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import java.util.List;
import org.junit.jupiter.api.Test;

class MutableIdentifierCollectionTalkgroupObservationTest
{
    @Test
    void explicitLocalFieldIsIndependentOfCanonicalEqualityAndChangesReachBothUpdateModes()
    {
        var homeOnly = APCO25FullyQualifiedTalkgroupIdentifier.createTo(5_678, 0xABCDE, 0x456, 5_678);
        var first = APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(5_678, 0xABCDE, 0x456, 5_678);
        var second = APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(1_234, 0xABCDE, 0x456, 5_678);
        assertNull(homeOnly.getLocalAddress());
        assertEquals(homeOnly, first);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(homeOnly.hashCode(), new APCO25FullyQualifiedTalkgroupIdentifier(0, 0xABCDE, 0x456, 5_678, Role.TO) {}.hashCode());
        for(boolean silent: List.of(false, true))
        {
            var identifiers = new MutableIdentifierCollection(List.of(homeOnly));
            if(silent) identifiers.silentUpdate(first); else identifiers.update(first);
            assertSame(first, identifiers.getToIdentifier());
            if(silent) identifiers.silentUpdate(second); else identifiers.update(second);
            assertSame(second, identifiers.getToIdentifier());
            if(silent) identifiers.silentUpdate(homeOnly); else identifiers.update(homeOnly);
            var retained = (APCO25FullyQualifiedTalkgroupIdentifier)identifiers.getToIdentifier();
            assertEquals(second, retained);
            assertEquals(second.getLocalAddress(), retained.getLocalAddress());
            assertEquals(IdentityResolutionEvidence.DIRECT, retained.getResolutionEvidence());
            assertSame(homeOnly, retained.getRecordingIdentifier());
        }
    }

    @Test
    void directCanonicalOnlyObservationRefreshesRawFactsForBothRolesWithoutNotificationChurn()
    {
        for(boolean silent: List.of(false, true))
        {
            for(Role role: List.of(Role.FROM, Role.TO))
            {
                for(int local: List.of(1_234, 5_678))
                {
                    var direct = APCO25FullyQualifiedTalkgroupIdentifier.createWithLocalAddress(local,
                        0xABCDE, 0x456, 5_678, role, IdentityResolutionEvidence.DIRECT);
                    var observed = new APCO25FullyQualifiedTalkgroupIdentifier(5_678, 0xABCDE, 0x456, 5_678, role);
                    var identifiers = new MutableIdentifierCollection(List.of(direct));
                    var notifications = new java.util.ArrayList<IdentifierUpdateNotification>();
                    identifiers.setIdentifierUpdateListener(notifications::add);
                    if(silent) identifiers.silentUpdate(observed); else identifiers.update(observed);
                    var retained = (APCO25FullyQualifiedTalkgroupIdentifier)identifiers.getIdentifier(
                        direct.getIdentifierClass(), direct.getForm(), role);
                    assertEquals(local, retained.getLocalAddress());
                    assertEquals(IdentityResolutionEvidence.DIRECT, retained.getResolutionEvidence());
                    assertSame(observed, retained.getRecordingIdentifier());
                    assertFalse(IdentifierObservation.same(direct, retained));
                    assertTrue(IdentifierObservation.same(retained, retained));
                    assertEquals(silent ? 0 : 2, notifications.size());
                    var repeated = new APCO25FullyQualifiedTalkgroupIdentifier(5_678, 0xABCDE, 0x456, 5_678, role);
                    if(silent) identifiers.silentUpdate(repeated); else identifiers.update(repeated);
                    assertSame(retained, identifiers.getIdentifier(direct.getIdentifierClass(), direct.getForm(), role));
                    assertEquals(silent ? 0 : 2, notifications.size());
                    var before = new PatchGroup(APCO25Talkgroup.create(20), 3);
                    before.addPatchedTalkgroup(direct);
                    var after = new PatchGroup(APCO25Talkgroup.create(20), 3);
                    after.addPatchedTalkgroup(retained);
                    assertFalse(IdentifierObservation.same(APCO25PatchGroup.create(before), APCO25PatchGroup.create(after)),
                        "Changed raw member facts must reach patch snapshots despite equal canonical identity");
                }
            }
        }
    }

    @Test
    void directObservationStrengthensMappingAndCanonicalOnlyObservationPreservesRawFacts()
    {
        var mapped = APCO25FullyQualifiedTalkgroupIdentifier.createWithLocalAddress(1_234, 0xABCDE, 0x456, 5_678,
            Role.TO, IdentityResolutionEvidence.CONFIRMED_ASSIGNMENT, APCO25Talkgroup.create(1_234));
        var observed = APCO25FullyQualifiedTalkgroupIdentifier.createTo(5_678, 0xABCDE, 0x456, 5_678);
        var identifiers = new MutableIdentifierCollection(List.of(mapped));
        identifiers.update(observed);
        var retained = (APCO25FullyQualifiedTalkgroupIdentifier)identifiers.getToIdentifier();
        assertEquals(1_234, retained.getLocalAddress());
        assertEquals(IdentityResolutionEvidence.CONFIRMED_ASSIGNMENT, retained.getResolutionEvidence());
        assertSame(observed, retained.getRecordingIdentifier());
        identifiers.update(APCO25FullyQualifiedTalkgroupIdentifier.createTo(5_678, 0xABCDE, 0x456, 5_678));
        assertSame(retained, identifiers.getToIdentifier());
        var direct = APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(1_234, 0xABCDE, 0x456, 5_678);
        identifiers.update(direct);
        assertSame(direct, identifiers.getToIdentifier());
        identifiers.update(mapped);
        var refreshed = (APCO25FullyQualifiedTalkgroupIdentifier)identifiers.getToIdentifier();
        assertEquals(IdentityResolutionEvidence.DIRECT, refreshed.getResolutionEvidence());
        assertSame(mapped.getRecordingIdentifier(), refreshed.getRecordingIdentifier());
        assertFalse(IdentifierObservation.same(direct, refreshed));
    }

    @Test
    void samePrimaryPatchStillReplacesChangedMemberObservation()
    {
        var before = new PatchGroup(APCO25Talkgroup.create(20), 3);
        before.addPatchedTalkgroup(APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(1_234, 0xABCDE, 0x456, 5_678));
        var after = new PatchGroup(APCO25Talkgroup.create(20), 3);
        after.addPatchedTalkgroup(APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(1_235, 0xABCDE, 0x456, 5_678));
        var updated = APCO25PatchGroup.create(after);
        var identifiers = new MutableIdentifierCollection(List.of(APCO25PatchGroup.create(before)));
        identifiers.update(updated);
        assertSame(updated, identifiers.getToIdentifier());
    }
}
