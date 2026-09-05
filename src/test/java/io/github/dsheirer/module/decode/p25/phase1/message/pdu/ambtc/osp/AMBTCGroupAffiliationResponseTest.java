/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.osp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.message.SymbolMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequence;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.AMBTCHeader;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.block.UnconfirmedDataBlock;
import io.github.dsheirer.module.decode.p25.reference.Response;
import java.util.List;
import org.junit.jupiter.api.Test;

class AMBTCGroupAffiliationResponseTest
{
    /** CRC-valid message recovered from an offline P25 capture. */
    private static final String HEADER = "37FD00FFFAB28228BEE003AD";
    private static final String BLOCK_0 = "03A93715FFA6FFC200000000";
    private static final String BLOCK_1 = "00000000000000009FE2724E";

    @Test
    void decodesTheSeparateRadioAndGroupIdentitiesWithoutOverlappingThem()
    {
        PDUSequence sequence = new PDUSequence(new AMBTCHeader(CorrectedBinaryMessage.loadHex(HEADER), true),
            1_000L, 0x951);
        sequence.addDataBlock(new TestUnconfirmedDataBlock(CorrectedBinaryMessage.loadHex(BLOCK_0)));
        sequence.addDataBlock(new TestUnconfirmedDataBlock(CorrectedBinaryMessage.loadHex(BLOCK_1)));

        assertTrue(sequence.isComplete());
        assertTrue(sequence.passesPacketCRC());

        AMBTCGroupAffiliationResponse response = new AMBTCGroupAffiliationResponse(sequence, 0x951, 1_000L);
        APCO25FullyQualifiedRadioIdentifier radio =
            (APCO25FullyQualifiedRadioIdentifier)response.getTargetAddress();

        assertEquals(0xBEE00, radio.getWacn());
        assertEquals(0x3A9, radio.getSystem());
        assertEquals(0x3715FF, radio.getRadio());
        assertEquals(0xFFFAB2, radio.getValue());
        assertEquals(0xA6FFC, response.getGroupWacn());
        assertEquals(0x200, response.getGroupSystem());
        assertEquals(0, response.getGroupId());
        assertEquals(0, response.getAnnouncementGroupId());
        assertNull(response.getGroupAddress());
        assertNull(response.getAnnouncementGroup());
        assertEquals(List.of(radio), response.getIdentifiers());
        assertEquals(Response.ACCEPTED, response.getAffiliationResponse());
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
