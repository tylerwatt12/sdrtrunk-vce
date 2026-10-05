/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.isp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnitRegistrationRequestTest
{
    @Test
    void exposesOneStructuredSubscriberWithoutPublishingHomeNetworkAsServingNetwork()
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
        bits.setInt(0xBEE00, IntField.length20(24));
        bits.setInt(0x4A2, IntField.length12(44));
        bits.setInt(2_115_288, IntField.length24(56));
        UnitRegistrationRequest request = new UnitRegistrationRequest(
            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, bits, 0x348, 1_000L);

        assertEquals(0xBEE00, request.getWacnValue());
        assertEquals(0x4A2, request.getSystemValue());
        assertEquals(0xBEE00, request.getWACN().getValue());
        assertEquals(0x4A2, request.getSystem().getValue());

        List<Identifier> identifiers = request.getIdentifiers();
        assertEquals(1, identifiers.size(),
            "home WACN and System ID stay inside the subscriber tuple instead of replacing serving identity");
        APCO25FullyQualifiedRadioIdentifier subscriber = assertInstanceOf(
            APCO25FullyQualifiedRadioIdentifier.class, identifiers.getFirst());
        assertSame(request.getSourceAddress(), subscriber);
        assertEquals(2_115_288, subscriber.getValue());
        assertEquals(0xBEE00, subscriber.getWacn());
        assertEquals(0x4A2, subscriber.getSystem());
        assertEquals(2_115_288, subscriber.getRadio());
    }
}
