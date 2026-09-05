/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.osp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.message.SymbolMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequence;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.AMBTCHeader;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.block.UnconfirmedDataBlock;
import io.github.dsheirer.module.decode.p25.reference.Response;
import org.junit.jupiter.api.Test;

/** TIA-102.AABC-B section 6.2.21.2, Figure 6.2.21-2. */
class AMBTCUnitRegistrationResponseTest
{
    private static final String HEADER = "0000001111110000BEE00000";
    private static final String BLOCK_0 = "03A90CAE7EABCDEF02000000";

    @Test
    void decodesResponseAndNewWorkingAddressFromTheirSeparateBlockFields()
    {
        PDUSequence sequence = new PDUSequence(new AMBTCHeader(CorrectedBinaryMessage.loadHex(HEADER), true),
            1_000L, 0x3A1);
        sequence.addDataBlock(new TestUnconfirmedDataBlock(CorrectedBinaryMessage.loadHex(BLOCK_0)));
        AMBTCUnitRegistrationResponse response = new AMBTCUnitRegistrationResponse(sequence, 0x3A1, 1_000L);
        APCO25FullyQualifiedRadioIdentifier radio =
            (APCO25FullyQualifiedRadioIdentifier)response.getRegistrationAddress();

        assertEquals(Response.DENIED, response.getResponse());
        assertEquals(0xABCDEF, radio.getValue(), "The data-block Source Address is the new working address");
        assertEquals(0xBEE00, radio.getWacn());
        assertEquals(0x3A9, radio.getSystem());
        assertEquals(831_102, radio.getRadio());
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
