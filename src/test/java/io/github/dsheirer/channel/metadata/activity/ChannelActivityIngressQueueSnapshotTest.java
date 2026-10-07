/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.channel.metadata.activity;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.channel.metadata.ChannelMetadata;
import io.github.dsheirer.channel.metadata.ChannelMetadataField;
import io.github.dsheirer.identifier.*;
import io.github.dsheirer.identifier.alias.DmrTalkerAliasIdentifier;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.identifier.encryption.APCO25EncryptionKey;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChannelActivityIngressQueueSnapshotTest
{
    @Test
    void trafficFactsKeepTheirAcceptedReferencesWhenTheDecoderReusesItsCollection()
    {
        var queue = new ChannelActivityIngressQueue(4, 1);
        var source = DMRRadio.createFrom(101);
        var target = DMRTalkgroup.create(91);
        var talker = DmrTalkerAliasIdentifier.create("First talker");
        var encryption = EncryptionKeyIdentifier.create(APCO25EncryptionKey.create(0x84, 7));
        var identifiers = new MutableIdentifierCollection(List.of(source, target, talker, encryption));
        assertTrue(offer(queue, identifiers, 1_000L, 1_200L));

        identifiers.update(DMRRadio.createFrom(202));
        identifiers.update(DMRTalkgroup.create(92));
        identifiers.update(DmrTalkerAliasIdentifier.create("Next talker"));
        identifiers.update(EncryptionKeyIdentifier.create(APCO25EncryptionKey.create(0x80, 0)));

        var accepted = queue.poll();
        assertSame(source, accepted.metadataSource());
        assertSame(target, accepted.metadataTarget());
        assertSame(talker, accepted.metadataTalkerAlias());
        assertSame(encryption, accepted.metadataEncryption());
        assertEquals(1_000L, accepted.callStart());
        assertEquals(1_200L, accepted.observedAt());
        assertNull(accepted.fifth(), "The mutable collection itself must not escape into the worker command");
        assertNull(queue.poll());
    }

    @Test
    void saturationRejectsBeforeReadingFactsAndPreservesTheLifecycleReserve()
    {
        var queue = new ChannelActivityIngressQueue(4, 1);
        for(int index = 0; index < queue.regularCapacity(); index++)
        {
            assertTrue(queue.offer(index, false, null, null, null, null, null, null, 0));
        }
        IdentifierCollection mustNotRead = new IdentifierCollection()
        {
            @Override public Identifier getFromIdentifier() { throw new AssertionError("Full queue read source"); }
            @Override public Identifier getToIdentifier() { throw new AssertionError("Full queue read target"); }
            @Override public Identifier getEncryptionIdentifier() { throw new AssertionError("Full queue read encryption"); }
            @Override public Identifier getIdentifier(IdentifierClass type, Form form, Role role)
            {
                throw new AssertionError("Full queue read fixed identifier");
            }
        };
        for(int attempt = 0; attempt < 16; attempt++)
        {
            assertFalse(offer(queue, mustNotRead, 1_000L, 1_200L));
        }
        assertTrue(queue.offer(99, true, null, null, null, null, null, null, 0));
        assertFalse(queue.offer(100, true, null, null, null, null, null, null, 0));
        for(int index = 0; index < 3; index++) assertEquals(index, queue.poll().operation());
        var lifecycle = queue.poll();
        assertEquals(99, lifecycle.operation());
        assertTrue(lifecycle.lifecycle());
        assertNull(queue.poll());
        assertTrue(offer(queue, new IdentifierCollection(), 2_000L, 2_100L),
            "Repeated rejected offers must not leak reserved regular capacity");
    }

    @Test
    void reusedCellsCannotLeakParticipantsOrTrafficOrderingIntoOtherOperations()
    {
        var queue = new ChannelActivityIngressQueue(2, 1);
        var source = DMRRadio.createFrom(101);
        for(int cycle = 0; cycle < 24; cycle++)
        {
            assertTrue(offer(queue, new IdentifierCollection(List.of(source)), 1_000L, 1_200L));
            assertSame(source, queue.poll().metadataSource());
            assertTrue(queue.offerMetadata(18, new ChannelMetadata(null, 2), ChannelMetadataField.DECODER_STATE));
            var metadata = queue.poll();
            assertNull(metadata.metadataSource());
            assertNull(metadata.metadataTalkerAlias());
            assertNull(metadata.metadataEncryption());
            assertEquals(0L, metadata.callStart());
            assertEquals(0L, metadata.observedAt());
            assertTrue(queue.offer(19, true, "Lifecycle", null, null, null, null, null, 0));
            var lifecycle = queue.poll();
            assertNull(lifecycle.metadata());
            assertNull(lifecycle.metadataSource());
            assertNull(lifecycle.metadataTarget());
            assertNull(lifecycle.metadataTimeslot());
            assertEquals(0L, lifecycle.callStart());
            assertEquals(0L, lifecycle.observedAt());
        }
        assertEquals(0, queue.size());
    }

    @Test
    void aliasOnlyOrInvalidRadioFactsDoNotMasqueradeAsAValidTrunkedSource()
    {
        var queue = new ChannelActivityIngressQueue(4, 1);
        var talker = DmrTalkerAliasIdentifier.create("Unattributed talker");
        assertTrue(offer(queue, new IdentifierCollection(List.of(talker)), 1_000L, 1_200L));
        var aliasOnly = queue.poll();
        assertNull(aliasOnly.metadataSource());
        assertSame(talker, aliasOnly.metadataTalkerAlias());
        assertTrue(offer(queue, new IdentifierCollection(List.of(DMRRadio.createFrom(0), DMRTalkgroup.create(0))),
            1_000L, 1_200L));
        var invalid = queue.poll();
        assertNull(invalid.metadataSource());
        assertNull(invalid.metadataTarget());
    }

    @Test
    void canonicalOnlyIssiPartiesAreValidEvenWithoutALocalWorkingAddress()
    {
        var queue = new ChannelActivityIngressQueue(4, 1);
        var source = APCO25FullyQualifiedRadioIdentifier.createFrom(0, 0xBEE00, 0x348, 70_001);
        var target = APCO25FullyQualifiedRadioIdentifier.createTo(0, 0xBEE00, 0x348, 70_002);
        assertTrue(offer(queue, new IdentifierCollection(List.of(source, target)), 1_000L, 1_200L));
        var accepted = queue.poll();
        assertSame(source, accepted.metadataSource());
        assertSame(target, accepted.metadataTarget());
    }

    private static boolean offer(ChannelActivityIngressQueue queue, IdentifierCollection identifiers,
                                 long start, long observed)
    {
        return queue.offerTraffic(17, null, null, null, 1, identifiers, DecodeEventType.CALL_GROUP,
            851_012_500L, start, observed);
    }
}
