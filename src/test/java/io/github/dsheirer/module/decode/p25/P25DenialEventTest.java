/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.DenyResponse;
import io.github.dsheirer.module.decode.p25.reference.DenyReason;
import io.github.dsheirer.protocol.Protocol;
import org.junit.jupiter.api.Test;

class P25DenialEventTest
{
    @Test
    void retainsExactReasonAndSelectionWithoutCollapsingUserDefinedCodes()
    {
        for(int code: new int[]{0, 1, 0x9A, 0xFF})
        {
            for(boolean vendorSpecific: new boolean[]{false, true})
            {
                P25DenialEvent event = new P25DenialEvent(1_000L, code, vendorSpecific);
                assertEquals(DecodeEventType.DENIAL, event.getEventType());
                assertEquals(Protocol.APCO25, event.getProtocol());
                assertEquals(code, event.getDenyReasonCode());
                assertEquals(vendorSpecific, event.isVendorSpecificReason());
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new P25DenialEvent(1_000L, -1, false));
        assertThrows(IllegalArgumentException.class, () -> new P25DenialEvent(1_000L, 256, false));
    }

    @Test
    void phase1StandardDenialKeepsRawCodeWithoutTreatingAivAsVendorSelection()
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
        bits.set(16); // Standard AIV, not vendor-reason selection.
        bits.setInt(0x9A, IntField.length8(24));
        DenyResponse response = new DenyResponse(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, bits, 0x293, 1_000L);
        assertEquals(0x9A, response.getDenyReasonCode());
        assertEquals(DenyReason.USER_OR_SYSTEM_DEFINED, response.getDenyReason());
        assertFalse(response.isVendorSpecificReason());
    }

    @Test
    void phase1MotorolaDenialSelectsStandardOrVendorReasonMeaning()
    {
        for(boolean vendorSpecific: new boolean[]{false, true})
        {
            for(int code: new int[]{0, 1, 0x31, 0x9A})
            {
                CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
                if(vendorSpecific) bits.set(16);
                bits.setInt(code, IntField.length8(24));
                var response = new io.github.dsheirer.module.decode.p25.phase1.message.tsbk.motorola.osp.MotorolaDenyResponse(
                    P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, bits, 0x293, 1_000L);
                assertEquals(code, response.getDenyReasonCode());
                assertEquals(vendorSpecific, response.isVendorSpecificReason());
                assertEquals(expectedReason(code, vendorSpecific), response.getDenyReason());
            }
        }
    }

    @Test
    void phase2StandardDenialUsesStandardReasonsAtNonzeroStructureOffsets()
    {
        for(int offset: new int[]{0, 13})
        {
            CorrectedBinaryMessage bits = new CorrectedBinaryMessage(160);
            bits.set(offset + 8); // Standard AIV.
            bits.setInt(0x31, IntField.length8(offset + 16));
            var response = new io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.DenyResponse(bits, offset);
            assertEquals(0x31, response.getDenyReasonCode());
            assertFalse(response.isVendorSpecificReason());
            assertEquals(DenyReason.TARGET_GROUP_NOT_AUTHORIZED_FOR_SERVICE, response.getDenyReason());
        }
    }

    @Test
    void phase2MotorolaRcSelectsReasonMeaningAtNonzeroStructureOffsets()
    {
        // TIA-102.BBAD-A (2019), 3.97/Figure 192 and 4.17; TIA-102.AABH (2014), 4.4.7/Annex D.
        for(int offset: new int[]{0, 13})
        {
            for(boolean vendorSpecific: new boolean[]{false, true})
            {
                for(int code: new int[]{0, 1, 0x31, 0x9A})
                {
                    CorrectedBinaryMessage bits = new CorrectedBinaryMessage(160);
                    if(vendorSpecific) bits.set(offset + 24);
                    bits.setInt(code, IntField.length8(offset + 32));
                    var response = new io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.motorola.MotorolaDenyResponse(
                        bits, offset);
                    assertEquals(code, response.getDenyReasonCode());
                    assertEquals(vendorSpecific, response.isVendorSpecificReason());
                    assertEquals(expectedReason(code, vendorSpecific), response.getDenyReason());
                }
            }
        }
    }

    @Test
    void phase2MotorolaAdditionalInformationDoesNotDependOnRc()
    {
        // TIA-102.AABH (2014), 4.4.7: invalid Additional Information is zero-filled.
        for(int offset: new int[]{0, 13})
        {
            CorrectedBinaryMessage standardReason = new CorrectedBinaryMessage(160);
            standardReason.setInt(0x123456, IntField.length24(offset + 40));
            var withInfo = new io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.motorola.MotorolaDenyResponse(
                standardReason, offset);
            assertFalse(withInfo.isVendorSpecificReason());
            assertTrue(withInfo.hasAdditionalInformation());
            assertEquals("123456", withInfo.getAdditionalInfo());

            CorrectedBinaryMessage vendorReason = new CorrectedBinaryMessage(160);
            vendorReason.set(offset + 24);
            var withoutInfo = new io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.motorola.MotorolaDenyResponse(
                vendorReason, offset);
            assertTrue(withoutInfo.isVendorSpecificReason());
            assertFalse(withoutInfo.hasAdditionalInformation());
        }
    }

    @Test
    void standardReasonBoundaryDistinguishesUnsupportedServiceFromUserDefinedReasons()
    {
        assertEquals(DenyReason.USER_OR_SYSTEM_DEFINED, DenyReason.fromCode(0xEF));
        assertEquals(DenyReason.CIRCUIT_OR_PACKET_MODE_OPTION_NOT_VALID, DenyReason.fromCode(0xF3));
        assertEquals(DenyReason.USER_OR_SYSTEM_DEFINED, DenyReason.fromCode(0xF4));
        assertEquals(DenyReason.USER_OR_SYSTEM_DEFINED, DenyReason.fromCode(0xFE));
        assertEquals(DenyReason.SYSTEM_DOES_NOT_SUPPORT_SERVICE, DenyReason.fromCode(0xFF));
    }

    private static DenyReason expectedReason(int code, boolean vendorSpecific)
    {
        return switch(code)
        {
            case 0 -> vendorSpecific ? DenyReason.SECURE_REQUEST_ON_CLEAR_SUPERGROUP : DenyReason.RESERVED;
            case 1 -> vendorSpecific ? DenyReason.CLEAR_REQUEST_ON_SECURE_SUPERGROUP : DenyReason.RESERVED;
            case 0x31 -> DenyReason.TARGET_GROUP_NOT_AUTHORIZED_FOR_SERVICE;
            case 0x9A -> DenyReason.USER_OR_SYSTEM_DEFINED;
            default -> throw new AssertionError("Unexpected test reason");
        };
    }
}
