/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25IncompleteRadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.reference.Response;
import org.junit.jupiter.api.Test;

class UnitRegistrationResponseTest
{
    /** CRC-valid abbreviated registration recovered from an offline P25 capture. */
    private static final String MESSAGE = "AC0009540CAE7EFFFD2657BA";

    @Test
    void keepsTheWorkingAddressLocalUntilTheServingWacnIsKnown()
    {
        UnitRegistrationResponse response = new UnitRegistrationResponse(
            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, CorrectedBinaryMessage.loadHex(MESSAGE), 0x3A1, 1_000L);

        assertEquals(Response.ACCEPTED, response.getResponse());
        assertEquals(0x954, response.getSourceSystemId());
        assertEquals(831_102, response.getSourceId());
        assertEquals(0xFFFD26, response.getSourceAddress());

        Identifier<?> incomplete = response.getRegisteredRadio();
        assertInstanceOf(APCO25IncompleteRadioIdentifier.class, incomplete);
        assertEquals(0xFFFD26, incomplete.getValue());

        APCO25FullyQualifiedRadioIdentifier complete = assertInstanceOf(
            APCO25FullyQualifiedRadioIdentifier.class, response.getRegisteredRadio(0xBEE00));
        assertEquals(0xFFFD26, complete.getValue());
        assertEquals(0xBEE00, complete.getWacn());
        assertEquals(0x954, complete.getSystem());
        assertEquals(831_102, complete.getRadio());
    }
}
