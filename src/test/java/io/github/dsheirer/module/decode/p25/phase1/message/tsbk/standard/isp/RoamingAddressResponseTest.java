/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.isp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TIA-102.AABC-B Figure 6.1.21-1 abbreviated inbound roaming-address response identity layout.
 */
class RoamingAddressResponseTest
{
    @Test
    void publishesTheInboundSourceSubscriberWithCanonicalTupleAndSourceRole()
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
        bits.setInt(0xBEE00, IntField.length20(24));
        bits.setInt(0xA48, IntField.length12(44));
        bits.setInt(0xC12345, IntField.length24(56));
        RoamingAddressResponse response = new RoamingAddressResponse(
            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, bits, 0x951, 1_000L);

        APCO25FullyQualifiedRadioIdentifier subscriber =
            (APCO25FullyQualifiedRadioIdentifier)response.getRoamingAddress();

        assertEquals(Role.FROM, subscriber.getRole());
        assertEquals(0xBEE00, subscriber.getWacn());
        assertEquals(0xA48, subscriber.getSystem());
        assertEquals(0xC12345, subscriber.getRadio());
        assertEquals(0xC12345, subscriber.getValue());
        assertFalse(subscriber.hasExplicitWorkingAddress());
        assertEquals(List.of(subscriber), response.getIdentifiers());
        assertSame(subscriber, response.getIdentifiers().getFirst());
    }
}
