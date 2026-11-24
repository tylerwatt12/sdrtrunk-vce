/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.osp;

import static io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.GroupAffiliationTestMessages.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.identifier.IdentityResolutionEvidence;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUMessageFactory;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequence;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequenceMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.AMBTCHeader;
import io.github.dsheirer.module.decode.p25.reference.Response;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Current documented layout: TIA-102.AABC-E section 6.2.8.2, Figure 6.2.8-2. */
class AMBTCGroupAffiliationResponseTest
{
    @Test
    void captureIdentifiesTheHomeAndWorkingGroupWithoutInventingAPermanentRadioIdentity()
    {
        PDUSequence sequence = capture();
        AMBTCGroupAffiliationResponse response = decode(sequence);
        assertInstanceOf(APCO25RadioIdentifier.class, response.getTargetAddress());
        assertEquals(WORKING_RADIO, response.getTargetAddress().getValue());
        assertEquals(Role.TO, response.getTargetAddress().getRole());
        assertEquals(0xBEE00, response.getGroupWacn());
        assertEquals(0x3A9, response.getGroupSystem());
        assertEquals(0x3715, response.getGroupId());
        assertEquals(0xFFA6, response.getAnnouncementGroupId());
        assertEquals(0xFFC2, response.getWorkingGroupId());
        var group = response.getGroupAddress();
        assertNotNull(group);
        assertEquals(0xFFC2, group.getLocalAddress());
        assertEquals(0x3715, group.getTalkgroup());
        assertEquals(IdentityResolutionEvidence.DIRECT, group.getResolutionEvidence());
        assertEquals(Role.ANY, group.getRole());
        assertEquals(Response.ACCEPTED, response.getAffiliationResponse());
        assertEquals(List.of(response.getTargetAddress(), group, response.getAnnouncementGroup()),
            response.getIdentifiers());
    }

    @Test
    void allOutcomesComeFromTheFirstBlockRegardlessOfReservedLastBlockValues()
    {
        for(int outcome = 0; outcome < 4; outcome++)
        {
            for(int oldLocation = 0; oldLocation < 4; oldLocation++)
            {
                var payload = new CorrectedBinaryMessage(192);
                payload.load(4, 12, 0x3A9);
                payload.load(16, 16, 0x3715);
                payload.load(48, 16, 0xFFC2);
                payload.set(64); //LG is not a format/version selector.
                payload.load(70, 2, outcome);
                payload.load(96 + 14, 2, oldLocation);
                assertEquals(Response.fromValue(outcome), decode(response(payload)).getAffiliationResponse());
            }
        }
    }

    @Test
    void equalWorkingAndHomeNumbersStillCarryExplicitLocalEvidence()
    {
        var group = decode(response(0, 0x3715, 0x3715)).getGroupAddress();
        assertNotNull(group);
        assertEquals(0x3715, group.getLocalAddress());
        assertTrue(group.hasExplicitLocalAddress());
    }

    @Test
    void groupNetworkUsesBothHeaderAndPayloadAndKeepsAssignableBoundaryValues()
    {
        for(int groupId : new int[]{1, 0xFFFE})
        {
            var payload = new CorrectedBinaryMessage(192);
            payload.load(0, 4, 0xB);
            payload.load(4, 12, 0x456);
            payload.load(16, 16, groupId);
            payload.load(32, 16, 0x1234);
            payload.load(48, 16, groupId);
            var decoded = decode(response(payload));
            var group = decoded.getGroupAddress();
            assertNotNull(group);
            assertEquals(0xBEE0B, group.getWacn());
            assertEquals(0x456, group.getSystem());
            assertEquals(groupId, group.getTalkgroup());
            assertEquals(groupId, group.getLocalAddress());
            assertEquals(0x1234, decoded.getAnnouncementGroupId());
        }
    }

    @Test
    void reservedWorkingNumbersKeepOnlyTheHomeIdentity()
    {
        for(int working : new int[]{0, 0xFFFF})
        {
            var decoded = decode(response(0, 0x3715, working));
            var group = decoded.getGroupAddress();
            assertNotNull(group);
            assertEquals(working, decoded.getWorkingGroupId());
            assertEquals(0x3715, group.getTalkgroup());
            assertNull(group.getLocalAddress());
            assertFalse(group.hasExplicitLocalAddress());
        }
    }

    @Test
    void reservedHomeAndAnnouncementGroupsDoNotCreateOrdinaryGroupIdentifiers()
    {
        for(int reserved : new int[]{0, 0xFFFF})
        {
            var payload = new CorrectedBinaryMessage(192);
            payload.load(16, 16, reserved);
            payload.load(32, 16, reserved);
            payload.load(48, 16, 0xFFC2);
            var decoded = decode(response(payload));
            assertNull(decoded.getGroupAddress());
            assertNull(decoded.getAnnouncementGroup());
            assertEquals(List.of(decoded.getTargetAddress()), decoded.getIdentifiers());
        }
    }

    @Test
    void incompleteOrCorruptSequencesAreRejectedBeforeAffiliationParsing()
    {
        var original = capture();
        var missing = new PDUSequence(original.getHeader(), TIMESTAMP, NAC);
        missing.addDataBlock(original.getDataBlocks().getFirst());
        assertRejected(missing);
        var extra = capture();
        extra.addDataBlock(original.getDataBlocks().getFirst());
        assertRejected(extra);
        var corrupt = capture();
        corrupt.getDataBlocks().getFirst().getMessage().flip(70);
        assertRejected(corrupt);
        var invalidHeader = new PDUSequence(new AMBTCHeader(original.getHeader().getMessage(), false), TIMESTAMP, NAC);
        for(var block : original.getDataBlocks()) invalidHeader.addDataBlock(block);
        assertRejected(invalidHeader);
    }

    @Test
    void absentPayloadDoesNotInventGroupFields()
    {
        var full = capture();
        var sequence = new PDUSequence(full.getHeader(), TIMESTAMP, NAC);
        var decoded = new AMBTCGroupAffiliationResponse(sequence, NAC, TIMESTAMP);
        assertEquals(Response.UNKNOWN, decoded.getAffiliationResponse());
        assertNull(decoded.getGroupAddress());
        assertNull(decoded.getAnnouncementGroup());
        assertEquals(WORKING_RADIO, decoded.getTargetAddress().getValue());
    }

    private static AMBTCGroupAffiliationResponse decode(PDUSequence sequence)
    {
        assertTrue(sequence.isValid());
        return assertInstanceOf(AMBTCGroupAffiliationResponse.class,
            PDUMessageFactory.create(sequence, NAC, TIMESTAMP));
    }

    private static void assertRejected(PDUSequence sequence)
    {
        assertFalse(sequence.isValid());
        assertFalse(assertInstanceOf(PDUSequenceMessage.class,
            PDUMessageFactory.create(sequence, NAC, TIMESTAMP)).isValid());
    }
}
