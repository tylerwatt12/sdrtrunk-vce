/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.identifier.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PatchGroupTest
{
    @Test
    void membershipVersionsRetainOrderAndCannotBeChangedByReaders()
    {
        PatchGroup patch = new PatchGroup(APCO25Talkgroup.create(100));
        TalkgroupIdentifier firstGroup = APCO25Talkgroup.create(200);
        TalkgroupIdentifier secondGroup = APCO25Talkgroup.create(201);
        RadioIdentifier firstRadio = APCO25RadioIdentifier.createTo(300);
        RadioIdentifier secondRadio = APCO25RadioIdentifier.createTo(301);

        assertFalse(patch.hasPatchedTalkgroups());
        assertFalse(patch.hasPatchedRadios());
        assertTrue(patch.addPatchedTalkgroups(List.of(firstGroup, secondGroup, firstGroup)));
        assertTrue(patch.addPatchedRadios(List.of(firstRadio, secondRadio, firstRadio)));
        List<TalkgroupIdentifier> groups = patch.getPatchedTalkgroupIdentifiers();
        List<RadioIdentifier> radios = patch.getPatchedRadioIdentifiers();
        assertEquals(List.of(firstGroup, secondGroup), groups);
        assertEquals(List.of(firstRadio, secondRadio), radios);
        assertThrows(UnsupportedOperationException.class, groups::clear);
        assertThrows(UnsupportedOperationException.class, radios::clear);

        patch.removePatchedTalkgroup(firstGroup);
        patch.removePatchedRadio(firstRadio);
        patch.addPatchedTalkgroup(firstGroup);
        patch.addPatchedRadio(firstRadio);
        assertEquals(List.of(firstGroup, secondGroup), groups, "a captured membership version stays stable");
        assertEquals(List.of(firstRadio, secondRadio), radios, "a captured membership version stays stable");
        assertEquals(List.of(secondGroup, firstGroup), patch.getPatchedTalkgroupIdentifiers());
        assertEquals(List.of(secondRadio, firstRadio), patch.getPatchedRadioIdentifiers());
        assertEquals("P:100 [201, 200] [301, 300]", patch.toString());
    }

    @Test
    void duplicateAndAbsentUpdatesRetainExistingLists()
    {
        PatchGroup patch = new PatchGroup(APCO25Talkgroup.create(100));
        TalkgroupIdentifier group = APCO25Talkgroup.create(200);
        RadioIdentifier radio = APCO25RadioIdentifier.createTo(300);
        patch.addPatchedTalkgroup(group);
        patch.addPatchedRadio(radio);
        List<TalkgroupIdentifier> groups = patch.getPatchedTalkgroupIdentifiers();
        List<RadioIdentifier> radios = patch.getPatchedRadioIdentifiers();

        assertFalse(patch.addPatchedTalkgroup(APCO25Talkgroup.create(200)));
        assertFalse(patch.addPatchedRadio(APCO25RadioIdentifier.createTo(300)));
        assertFalse(patch.addPatchedTalkgroups(List.of(group)));
        assertFalse(patch.addPatchedRadios(List.of(radio)));
        patch.removePatchedTalkgroup(APCO25Talkgroup.create(999));
        patch.removePatchedRadio(APCO25RadioIdentifier.createTo(999));
        assertSame(groups, patch.getPatchedTalkgroupIdentifiers());
        assertSame(radios, patch.getPatchedRadioIdentifiers());

        patch.addPatchedTalkgroup(APCO25Talkgroup.create(201));
        assertSame(radios, patch.getPatchedRadioIdentifiers(), "changing one member kind retains the other list");
        groups = patch.getPatchedTalkgroupIdentifiers();
        patch.addPatchedRadio(APCO25RadioIdentifier.createTo(301));
        assertSame(groups, patch.getPatchedTalkgroupIdentifiers());
    }

    @Test
    void patchIdentityAndVersionDoNotDependOnMembership()
    {
        TalkgroupIdentifier primary = APCO25Talkgroup.create(100);
        PatchGroup patch = new PatchGroup(primary, 7);
        PatchGroup samePrimary = new PatchGroup(APCO25Talkgroup.create(100), 8);
        int hash = patch.hashCode();
        patch.addPatchedTalkgroup(APCO25Talkgroup.create(200));
        patch.addPatchedRadio(APCO25RadioIdentifier.createTo(300));

        assertSame(primary, patch.getPatchGroup());
        assertEquals(7, patch.getVersion());
        assertEquals(0, new PatchGroup(primary).getVersion());
        assertTrue(patch.getPatchGroup().isValid());
        assertEquals(samePrimary, patch);
        assertEquals(hash, patch.hashCode());
        assertEquals(samePrimary.hashCode(), patch.hashCode());
        assertNotEquals(new PatchGroup(APCO25Talkgroup.create(101)), patch);
        patch.removePatchedTalkgroup(APCO25Talkgroup.create(200));
        patch.removePatchedRadio(APCO25RadioIdentifier.createTo(300));
        assertEquals("P:100", patch.toString());
        assertFalse(patch.hasPatchedTalkgroups());
        assertFalse(patch.hasPatchedRadios());
    }

    @Test
    void membershipWritersCompleteWhileTalkgroupFormattingIsBlocked() throws Exception
    {
        CountDownLatch formatting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PatchGroup patch = new PatchGroup(APCO25Talkgroup.create(100));
        patch.addPatchedTalkgroup(new BlockingTalkgroup(200, formatting, release));
        patch.addPatchedTalkgroup(APCO25Talkgroup.create(201));
        patch.addPatchedRadio(APCO25RadioIdentifier.createTo(300));

        try(var workers = Executors.newFixedThreadPool(2))
        {
            var rendered = workers.submit(patch::toString);
            try
            {
                assertTrue(formatting.await(2, TimeUnit.SECONDS), "formatter did not reach the captured member");
                workers.submit(() -> {
                    patch.addPatchedTalkgroup(APCO25Talkgroup.create(202));
                    patch.removePatchedTalkgroup(APCO25Talkgroup.create(201));
                    patch.addPatchedRadio(APCO25RadioIdentifier.createTo(301));
                    patch.removePatchedRadio(APCO25RadioIdentifier.createTo(300));
                }).get(2, TimeUnit.SECONDS);
            }
            finally
            {
                release.countDown();
            }
            assertEquals("P:100 [200, 201] [300]", rendered.get(2, TimeUnit.SECONDS));
            assertEquals("P:100 [200, 202] [301]", patch.toString());
        }
    }

    @Test
    void membershipWritersCompleteWhileRadioFormattingIsBlocked() throws Exception
    {
        CountDownLatch formatting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PatchGroup patch = new PatchGroup(APCO25Talkgroup.create(100));
        patch.addPatchedTalkgroup(APCO25Talkgroup.create(200));
        patch.addPatchedRadio(new BlockingRadio(300, formatting, release));
        patch.addPatchedRadio(APCO25RadioIdentifier.createTo(301));

        try(var workers = Executors.newFixedThreadPool(2))
        {
            var rendered = workers.submit(patch::toString);
            try
            {
                assertTrue(formatting.await(2, TimeUnit.SECONDS), "formatter did not reach the captured radio");
                workers.submit(() -> {
                    patch.addPatchedRadio(APCO25RadioIdentifier.createTo(302));
                    patch.removePatchedRadio(APCO25RadioIdentifier.createTo(301));
                    patch.addPatchedTalkgroup(APCO25Talkgroup.create(201));
                    patch.removePatchedTalkgroup(APCO25Talkgroup.create(200));
                }).get(2, TimeUnit.SECONDS);
            }
            finally
            {
                release.countDown();
            }
            assertEquals("P:100 [200] [300, 301]", rendered.get(2, TimeUnit.SECONDS));
            assertEquals("P:100 [201] [300, 302]", patch.toString());
        }
    }

    @Test
    void formattingCapturesBothMemberKindsBeforeFormattingThePrimary() throws Exception
    {
        CountDownLatch formatting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PatchGroup patch = new PatchGroup(new BlockingTalkgroup(100, formatting, release));
        patch.addPatchedTalkgroup(APCO25Talkgroup.create(200));
        patch.addPatchedRadio(APCO25RadioIdentifier.createTo(300));

        try(var workers = Executors.newFixedThreadPool(2))
        {
            var rendered = workers.submit(patch::toString);
            try
            {
                assertTrue(formatting.await(2, TimeUnit.SECONDS), "formatter did not reach the primary");
                workers.submit(() -> {
                    patch.removePatchedTalkgroup(APCO25Talkgroup.create(200));
                    patch.removePatchedRadio(APCO25RadioIdentifier.createTo(300));
                    patch.addPatchedTalkgroup(APCO25Talkgroup.create(201));
                    patch.addPatchedRadio(APCO25RadioIdentifier.createTo(301));
                }).get(2, TimeUnit.SECONDS);
            }
            finally
            {
                release.countDown();
            }
            assertEquals("P:100 [200] [300]", rendered.get(2, TimeUnit.SECONDS));
            assertEquals("P:100 [201] [301]", patch.toString());
        }
    }

    @Test
    void concurrentWritersKeepDistinctMembersAndAddSharedMembersOnce() throws Exception
    {
        PatchGroup patch = new PatchGroup(APCO25Talkgroup.create(100));
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger sharedGroupsAdded = new AtomicInteger();
        AtomicInteger sharedRadiosAdded = new AtomicInteger();

        try(var workers = Executors.newFixedThreadPool(2))
        {
            var first = workers.submit(() -> addMembers(patch, start, 200, sharedGroupsAdded, sharedRadiosAdded));
            var second = workers.submit(() -> addMembers(patch, start, 300, sharedGroupsAdded, sharedRadiosAdded));
            start.countDown();
            first.get(3, TimeUnit.SECONDS);
            second.get(3, TimeUnit.SECONDS);
        }

        List<TalkgroupIdentifier> groups = patch.getPatchedTalkgroupIdentifiers();
        List<RadioIdentifier> radios = patch.getPatchedRadioIdentifiers();
        assertEquals(1, sharedGroupsAdded.get());
        assertEquals(1, sharedRadiosAdded.get());
        assertEquals(33, groups.size());
        assertEquals(33, radios.size());
        assertEquals(33, new HashSet<>(groups).size());
        assertEquals(33, new HashSet<>(radios).size());
        for(int base: List.of(200, 300))
        {
            for(int offset = 0; offset < 16; offset++)
            {
                assertTrue(groups.contains(APCO25Talkgroup.create(base + offset)));
                assertTrue(radios.contains(APCO25RadioIdentifier.createTo(base + offset)));
            }
        }
    }

    private static void addMembers(PatchGroup patch, CountDownLatch start, int base,
                                   AtomicInteger sharedGroupsAdded, AtomicInteger sharedRadiosAdded)
    {
        await(start);
        if(patch.addPatchedTalkgroup(APCO25Talkgroup.create(999))) sharedGroupsAdded.incrementAndGet();
        if(patch.addPatchedRadio(APCO25RadioIdentifier.createTo(999))) sharedRadiosAdded.incrementAndGet();
        for(int offset = 0; offset < 16; offset++)
        {
            assertTrue(patch.addPatchedTalkgroup(APCO25Talkgroup.create(base + offset)));
            assertTrue(patch.addPatchedRadio(APCO25RadioIdentifier.createTo(base + offset)));
        }
    }

    private static void await(CountDownLatch latch)
    {
        try
        {
            if(!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for test coordination");
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static final class BlockingTalkgroup extends TalkgroupIdentifier
    {
        private final CountDownLatch mFormatting;
        private final CountDownLatch mRelease;

        private BlockingTalkgroup(int value, CountDownLatch formatting, CountDownLatch release)
        {
            super(value, Role.TO);
            mFormatting = formatting;
            mRelease = release;
        }

        @Override
        public Protocol getProtocol()
        {
            return Protocol.APCO25;
        }

        @Override
        public String toString()
        {
            mFormatting.countDown();
            await(mRelease);
            return getValue().toString();
        }
    }

    private static final class BlockingRadio extends RadioIdentifier
    {
        private final CountDownLatch mFormatting;
        private final CountDownLatch mRelease;

        private BlockingRadio(int value, CountDownLatch formatting, CountDownLatch release)
        {
            super(value, Role.TO);
            mFormatting = formatting;
            mRelease = release;
        }

        @Override
        public Protocol getProtocol()
        {
            return Protocol.APCO25;
        }

        @Override
        public String toString()
        {
            mFormatting.countDown();
            await(mRelease);
            return getValue().toString();
        }
    }
}
