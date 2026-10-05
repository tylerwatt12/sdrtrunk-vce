/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase2.message.mac.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import org.junit.jupiter.api.Test;

class FullyQualifiedRadioFieldRegressionTest
{
    @Test
    void callAlertDecodesCompleteTargetSubscriberAndExplicitEqualWorkingAddress()
    {
        CorrectedBinaryMessage base = new CorrectedBinaryMessage(160);
        CorrectedBinaryMessage continuation = new CorrectedBinaryMessage(96);
        int target = 0xABCDEF;
        int targetWacn = 0xBEE00;
        int targetSystem = 0xA48;
        int targetSubscriber = 0xC12345;
        base.setInt(target, IntField.length24(104));
        base.setInt(targetWacn >>> 4, IntField.length16(128));
        continuation.setInt(targetWacn & 0xF, IntField.length4(16));
        continuation.setInt(targetSystem, IntField.length12(20));
        continuation.setInt(targetSubscriber, IntField.length24(32));
        CallAlertExtendedLCCH message = new CallAlertExtendedLCCH(base, 0);
        message.addContinuationMessage(new MultiFragmentContinuationMessage(continuation, 0));

        APCO25FullyQualifiedRadioIdentifier decoded =
            (APCO25FullyQualifiedRadioIdentifier)message.getTargetSUID();

        assertEquals(Role.TO, decoded.getRole());
        assertEquals(targetWacn, decoded.getWacn());
        assertEquals(targetSystem, decoded.getSystem());
        assertEquals(targetSubscriber, decoded.getRadio());
        assertEquals(target, decoded.getWorkingAddress());
        assertTrue(decoded.hasExplicitWorkingAddress());
    }

    @Test
    void voiceGrantKeepsTwentyBitWacnsAndTargetRole()
    {
        CorrectedBinaryMessage base = new CorrectedBinaryMessage(160);
        CorrectedBinaryMessage continuation = new CorrectedBinaryMessage(112);
        base.setInt(501, IntField.length24(32));
        base.setInt(0xABCDE, IntField.length20(56));
        base.setInt(0xA23, IntField.length12(76));
        base.setInt(9_001, IntField.length24(88));
        continuation.setInt(777, IntField.length24(16));
        continuation.setInt(0xBEE00, IntField.length20(40));
        continuation.setInt(0xB48, IntField.length12(60));
        continuation.setInt(9_002, IntField.length24(72));
        UnitToUnitVoiceServiceChannelGrantExtendedLCCH message =
            new UnitToUnitVoiceServiceChannelGrantExtendedLCCH(base, 0);
        message.addContinuationMessage(new MultiFragmentContinuationMessage(continuation, 0));

        APCO25FullyQualifiedRadioIdentifier source = message.getSourceAddress();
        APCO25FullyQualifiedRadioIdentifier target = message.getTargetAddress();

        assertEquals(0xABCDE, source.getWacn());
        assertEquals(0xA23, source.getSystem());
        assertEquals(501, source.getWorkingAddress());
        assertEquals(Role.FROM, source.getRole());
        assertEquals(0xBEE00, target.getWacn());
        assertEquals(0xB48, target.getSystem());
        assertEquals(777, target.getWorkingAddress());
        assertEquals(Role.TO, target.getRole());
    }

    @Test
    void voiceGrantBandFieldsDoNotConsumeTheChannelNumberHighBit()
    {
        CorrectedBinaryMessage base = new CorrectedBinaryMessage(160);
        base.setInt(0xF, IntField.length4(112));
        base.setInt(0x800, IntField.length12(116));
        base.setInt(0xF, IntField.length4(128));
        base.setInt(0xFFF, IntField.length12(132));

        UnitToUnitVoiceServiceChannelGrantExtendedLCCH message =
            new UnitToUnitVoiceServiceChannelGrantExtendedLCCH(base, 0);

        assertEquals(0xF, message.getChannel().getValue().getDownlinkBandIdentifier());
        assertEquals(0x800, message.getChannel().getValue().getDownlinkChannelNumber());
        assertEquals(0xF, message.getChannel().getValue().getUplinkBandIdentifier());
        assertEquals(0xFFF, message.getChannel().getValue().getUplinkChannelNumber());
    }

    @Test
    void abbreviatedAcknowledgeSystemFieldDoesNotConsumeAddressBit()
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
        bits.set(8);
        bits.set(9);
        bits.setInt(0xBEE00, IntField.length20(16));
        bits.setInt(0xA48, IntField.length12(36));
        bits.setInt(0x800001, IntField.length24(48));
        AcknowledgeResponseFNEAbbreviated message = new AcknowledgeResponseFNEAbbreviated(bits, 0);
        APCO25FullyQualifiedRadioIdentifier target =
            (APCO25FullyQualifiedRadioIdentifier)message.getTargetAddress();

        assertEquals(0xBEE00, target.getWacn());
        assertEquals(0xA48, target.getSystem());
        assertEquals(0x800001, target.getRadio());
        assertEquals(Role.TO, target.getRole());
    }
}
