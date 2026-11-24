/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.osp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.edac.CRCP25;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.p25.P25AffiliationEvent;
import io.github.dsheirer.module.decode.p25.P25IdentityResolver;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.bandplan.P25BandplanOverrideRegistry;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DecoderState;
import io.github.dsheirer.module.decode.p25.phase1.message.SymbolMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUMessageFactory;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequence;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequenceMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.AMBTCHeader;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.block.UnconfirmedDataBlock;
import io.github.dsheirer.module.decode.p25.reference.Response;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Constructed decoded-block packets, not captures. AABC-B/E 6.2.21.2, Figure 6.2.21-2. */
class AMBTCUnitRegistrationResponseTest
{
    private static final int NAC = 0x293;
    private static final long TIMESTAMP = 1_000L;
    private static final int HOME_WACN = 0xABCDE;
    private static final int HOME_SYSTEM = 0x123;
    private static final int SUBSCRIBER = 0x234567;
    private static final int WORKING = 0x523456;
    private static final P25SiteIdentity SITE = new P25SiteIdentity(0xBEE00, 0x348, 1, 1);

    @Test
    void newerOutcomesAndWorkingAddressDoNotDependOnPacketChecksumBits()
    {
        for(int rv = 0; rv < 4; rv++)
        {
            for(int checksumValue = 0; checksumValue < 4; checksumValue++)
            {
                PDUSequence sequence = newerSequence(rv, checksumValue);
                AMBTCUnitRegistrationResponse response = admitted(sequence);
                assertEquals(checksumValue, sequence.getDataBlock(0).getMessage().getInt(IntField.length2(70)));
                assertEquals(Response.fromValue(rv), response.getResponse());
                assertRadio(response, WORKING,
                    sequence.getDataBlock(0).getMessage().getInt(IntField.length24(16)));
                assertTrue(response.toString().contains("REGISTRATION " + Response.fromValue(rv)));
            }
        }
    }

    @Test
    void legacyOutcomesKeepTheAssignedAddressInTheFirstBlock()
    {
        for(int rv = 0; rv < 4; rv++)
        {
            PDUSequence sequence = sequence(2, rv, WORKING, SUBSCRIBER);
            AMBTCUnitRegistrationResponse response = admitted(sequence);
            assertEquals(0xFFFFFE, sequence.getHeader().getMessage().getInt(IntField.length24(24)));
            assertEquals(Response.fromValue(rv), response.getResponse());
            assertRadio(response, WORKING, SUBSCRIBER);
        }
    }

    @Test
    void correctedPacketsReachRealRegistrationEventsAndOnlyAcceptanceEstablishesAssignments()
    {
        for(int blocks: new int[]{1, 2})
        {
            for(int rv = 0; rv < 4; rv++)
            {
                // In newer packets, force checksum bits to disagree with the actual outcome.
                PDUSequence sequence = blocks == 1 ? newerSequence(rv, (rv + 1) % 4) :
                    sequence(2, rv, WORKING, SUBSCRIBER);
                Projection projection = project(admitted(sequence));
                P25AffiliationEvent event = assertInstanceOf(P25AffiliationEvent.class,
                    projection.events().getFirst());
                assertEquals(1, projection.events().size());
                assertEquals(DecodeEventType.REGISTER, event.getEventType());
                assertEquals(P25AffiliationEvent.Outcome.from(Response.fromValue(rv)), event.getOutcome());
                assertEquals(Response.fromValue(rv) + " UNIT REGISTRATION", event.getDetails());
                assertEquals(WORKING, event.getRadioId());
                assertNull(event.getTalkgroupId());
                var radio = assertInstanceOf(APCO25FullyQualifiedRadioIdentifier.class, event.getRadioIdentifier());
                assertEquals(HOME_WACN, radio.getWacn());
                assertEquals(HOME_SYSTEM, radio.getSystem());
                assertEquals(radio.getRadio(), event.getRadioPresence().subscriber().subscriberId());
                assertEquals(rv == 0 ? 1 : 0, projection.resolver().snapshot(TIMESTAMP + 1).assignments().size());
                MutableIdentifierCollection laterCall = new MutableIdentifierCollection(
                    List.of(APCO25RadioIdentifier.createFrom(WORKING)));
                projection.resolver().enrich(SITE, laterCall, TIMESTAMP + 1);
                if(rv == 0)
                {
                    assertNotNull(event.getAssignmentObservation());
                    assertEquals(WORKING, event.getAssignmentObservation().workingId());
                    var enriched = assertInstanceOf(APCO25FullyQualifiedRadioIdentifier.class,
                        laterCall.getFromIdentifier());
                    assertEquals(radio.getRadio(), enriched.getRadio());
                    assertEquals(HOME_WACN, enriched.getWacn());
                    assertEquals(HOME_SYSTEM, enriched.getSystem());
                }
                else
                {
                    assertNull(event.getAssignmentObservation());
                    assertInstanceOf(APCO25RadioIdentifier.class, laterCall.getFromIdentifier());
                }
            }
        }
    }

    @Test
    void workingAddressBoundariesDoNotInventAssignmentsOrLoseEqualValuedEvidence()
    {
        for(int blocks: new int[]{1, 2})
        {
            for(int working: new int[]{0, 1, SUBSCRIBER, 0xFFFFFC, 0xFFFFFD, 0xFFFFFE, 0xFFFFFF})
            {
                AMBTCUnitRegistrationResponse response = admitted(sequence(blocks, 0, working, SUBSCRIBER));
                APCO25FullyQualifiedRadioIdentifier radio = assertRadio(response, working, SUBSCRIBER);
                boolean assignable = working >= 1 && working <= 0xFFFFFC;
                assertTrue(radio.hasExplicitWorkingAddress());
                assertEquals(assignable ? Integer.valueOf(working) : null, radio.getWorkingAddress());
                Projection projection = project(response);
                P25AffiliationEvent event = assertInstanceOf(P25AffiliationEvent.class,
                    projection.events().getFirst());
                assertEquals(assignable ? Integer.valueOf(working) : null, event.getRadioId());
                assertEquals(assignable ? 1 : 0, projection.resolver().snapshot(TIMESTAMP + 1).assignments().size());
                assertEquals(assignable, event.getAssignmentObservation() != null);
            }
        }
    }

    @Test
    void damagedAndIncompletePacketsRemainRejectedBeforeSubtypeParsing()
    {
        PDUSequence damaged = sequence(1, 0, WORKING, SUBSCRIBER);
        damaged.getDataBlock(0).getMessage().flip(20);
        assertRejected(damaged);

        PDUSequence badHeader = sequence(1, 0, WORKING, SUBSCRIBER);
        CorrectedBinaryMessage header = badHeader.getHeader().getMessage();
        header.flip(80);
        header.flip(81);
        assertEquals(2, CRCP25.correctCCITT80(header, 0, 80));
        PDUSequence invalidHeader = new PDUSequence(new AMBTCHeader(header, false), TIMESTAMP, NAC);
        invalidHeader.addDataBlock(badHeader.getDataBlock(0));
        assertRejected(invalidHeader);

        PDUSequence truncatedLegacy = sequence(2, 0, WORKING, SUBSCRIBER);
        truncatedLegacy.getDataBlocks().removeLast();
        assertEquals(2, truncatedLegacy.getHeader().getBlocksToFollowCount());
        assertRejected(truncatedLegacy);
        assertNoFields(new AMBTCUnitRegistrationResponse(truncatedLegacy, NAC, TIMESTAMP));

        PDUSequence missing = sequence(1, 0, WORKING, SUBSCRIBER);
        missing.getDataBlocks().clear();
        assertRejected(missing);
        assertNoFields(new AMBTCUnitRegistrationResponse(missing, NAC, TIMESTAMP));
    }

    @Test
    void unsupportedCompleteCountsDoNotBecomeLegacyRegistrations()
    {
        for(int blocks: new int[]{0, 3})
        {
            PDUSequence sequence = sequence(blocks, 0, WORKING, SUBSCRIBER);
            assertTrue(sequence.isValid(), "Generic packet integrity is independent of supported message layouts");
            var response = assertInstanceOf(AMBTCUnitRegistrationResponse.class,
                PDUMessageFactory.create(sequence, NAC, TIMESTAMP));
            assertFalse(response.isValid());
            assertNoFields(response);
            Projection projection = project(response);
            assertTrue(projection.events().isEmpty());
            assertTrue(projection.resolver().snapshot(TIMESTAMP + 1).assignments().isEmpty());
        }
    }

    private static void assertNoFields(AMBTCUnitRegistrationResponse response)
    {
        assertNull(response.getResponse());
        assertNull(response.getRegistrationAddress());
        assertTrue(response.getIdentifiers().isEmpty());
    }

    private static void assertRejected(PDUSequence sequence)
    {
        assertFalse(sequence.isValid());
        var message = PDUMessageFactory.create(sequence, NAC, TIMESTAMP);
        assertInstanceOf(PDUSequenceMessage.class, message);
        assertFalse(message.isValid());
    }

    private static AMBTCUnitRegistrationResponse admitted(PDUSequence sequence)
    {
        assertEquals(0, CRCP25.correctCCITT80(
            CorrectedBinaryMessage.loadHex(sequence.getHeader().getMessage().toHexString()), 0, 80));
        assertTrue(sequence.isComplete());
        assertTrue(sequence.isValid());
        var response = assertInstanceOf(AMBTCUnitRegistrationResponse.class,
            PDUMessageFactory.create(sequence, NAC, TIMESTAMP));
        assertTrue(response.isValid());
        return response;
    }

    private static APCO25FullyQualifiedRadioIdentifier assertRadio(AMBTCUnitRegistrationResponse response,
                                                                  int working, int subscriber)
    {
        var radio = assertInstanceOf(APCO25FullyQualifiedRadioIdentifier.class, response.getRegistrationAddress());
        assertEquals(working, radio.getValue());
        assertEquals(HOME_WACN, radio.getWacn());
        assertEquals(HOME_SYSTEM, radio.getSystem());
        assertEquals(subscriber, radio.getRadio());
        assertEquals(Role.TO, radio.getRole());
        assertEquals(List.of(radio), response.getIdentifiers());
        return radio;
    }

    private record Projection(List<IDecodeEvent> events, P25IdentityResolver resolver) {}

    private static Projection project(AMBTCUnitRegistrationResponse response)
    {
        Channel channel = new Channel("Constructed registration", Channel.ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        channel.setP25SiteIdentity(SITE);
        P25IdentityResolver resolver = new P25IdentityResolver();
        P25TrafficChannelManager manager = new P25TrafficChannelManager(channel,
            P25BandplanOverrideRegistry.empty(), resolver);
        P25P1DecoderState state = new P25P1DecoderState(channel, manager);
        List<IDecodeEvent> events = new ArrayList<>();
        state.addDecodeEventListener(events::add);
        state.receive(response);
        return new Projection(events, resolver);
    }

    private static PDUSequence newerSequence(int response, int checksumValue)
    {
        for(int subscriber = 0x234500; subscriber < 0x234600; subscriber++)
        {
            PDUSequence sequence = sequence(1, response, WORKING, subscriber);
            if(sequence.getDataBlock(0).getMessage().getInt(IntField.length2(70)) == checksumValue)
            {
                return sequence;
            }
        }
        return fail("No constructed subscriber produced the requested checksum pattern");
    }

    private static PDUSequence sequence(int blocks, int response, int working, int subscriber)
    {
        CorrectedBinaryMessage header = CorrectedBinaryMessage.loadHex("37FD00000000802C00000000");
        header.load(24, 24, blocks == 1 ? working : 0xFFFFFE);
        header.load(49, 7, blocks);
        header.load(64, 16, HOME_WACN >>> 4);
        seal(header, 16, 0x11021L, 0xFFFFL);
        PDUSequence sequence = new PDUSequence(new AMBTCHeader(header, true), TIMESTAMP, NAC);
        if(blocks > 0)
        {
            CorrectedBinaryMessage payload = new CorrectedBinaryMessage(blocks * 96);
            payload.load(0, 4, HOME_WACN & 15);
            payload.load(4, 12, HOME_SYSTEM);
            payload.load(16, 24, subscriber);
            if(blocks == 1)
            {
                payload.load(46, 2, response);
            }
            else
            {
                payload.load(40, 24, working);
                payload.load(70, 2, response);
            }
            seal(payload, 32, 0x104C11DB7L, 0xFFFFFFFFL);
            for(int block = 0; block < blocks; block++)
            {
                sequence.addDataBlock(new TestUnconfirmedDataBlock(
                    payload.getSubMessage(block * 96, (block + 1) * 96)));
            }
        }
        return sequence;
    }

    /** Independent polynomial division generates checksums; production checks them using a shift register. */
    private static void seal(BinaryMessage message, int width, long polynomial, long inversion)
    {
        int dataBits = message.size() - width;
        boolean[] dividend = new boolean[message.size()];
        for(int bit = 0; bit < dataBits; bit++) dividend[bit] = message.get(bit);
        for(int bit = 0; bit < dataBits; bit++)
        {
            if(dividend[bit])
            {
                for(int shift = 0; shift <= width; shift++)
                {
                    if(((polynomial >>> (width - shift)) & 1L) != 0) dividend[bit + shift] ^= true;
                }
            }
        }
        long crc = 0;
        for(int bit = dataBits; bit < dividend.length; bit++) crc = (crc << 1) | (dividend[bit] ? 1 : 0);
        message.load(dataBits, width, crc ^ inversion);
    }

    private static class TestUnconfirmedDataBlock extends UnconfirmedDataBlock
    {
        private final CorrectedBinaryMessage mMessage;

        private TestUnconfirmedDataBlock(CorrectedBinaryMessage message)
        {
            super(new SymbolMessage(98));
            mMessage = message;
        }

        @Override
        public CorrectedBinaryMessage getMessage()
        {
            return mMessage;
        }
    }
}
