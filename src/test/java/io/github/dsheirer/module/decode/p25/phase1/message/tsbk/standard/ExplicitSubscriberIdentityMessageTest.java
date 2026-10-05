/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.isp.ProtectionParameterRequest;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.isp.UnitDeRegistrationRequest;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.AcknowledgeResponse;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.AuthenticationCommand;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.UnitDeRegistrationAcknowledge;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Explicit SUID layouts from TIA-102.AABC-B Figures 6.1.12-1, 6.1.17-1, 6.2.1-2, 6.2.3-1, and 6.2.22-1.
 */
class ExplicitSubscriberIdentityMessageTest
{
    private static final int WACN = 0xBEE00;
    private static final int SYSTEM = 0x348;
    private static final int SUBSCRIBER = 2_115_288;

    @Test
    void inboundRequestsPublishOneCompleteSourceSubscriber()
    {
        CorrectedBinaryMessage bits = explicitSubscriberBits();
        ProtectionParameterRequest protection = new ProtectionParameterRequest(
            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, bits, SYSTEM, 1_000L);
        UnitDeRegistrationRequest deregistration = new UnitDeRegistrationRequest(
            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, bits, SYSTEM, 1_000L);

        assertSourceSubscriber(protection.getIdentifiers(), protection.getSourceAddress());
        assertSourceSubscriber(deregistration.getIdentifiers(), deregistration.getSourceAddress());
        assertEquals(WACN, protection.getWACN().getValue());
        assertEquals(SYSTEM, deregistration.getSystem().getValue());
    }

    @Test
    void outboundCommandsPublishOneCompleteTargetSubscriber()
    {
        CorrectedBinaryMessage bits = explicitSubscriberBits();
        AuthenticationCommand authentication = new AuthenticationCommand(
            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, bits, SYSTEM, 1_000L);
        UnitDeRegistrationAcknowledge deregistration = new UnitDeRegistrationAcknowledge(
            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, bits, SYSTEM, 1_000L);

        assertTargetSubscriber(authentication.getIdentifiers(), authentication.getTargetId());
        assertTargetSubscriber(deregistration.getIdentifiers(), deregistration.getTargetAddress());
        assertEquals(WACN, authentication.getWACN().getValue());
        assertEquals(SYSTEM, deregistration.getSystem().getValue());
    }

    @Test
    void acknowledgeExtendedInformationQualifiesOnlyTheTargetSubscriber()
    {
        CorrectedBinaryMessage bits = explicitSubscriberBits();
        bits.set(16); //AIV
        bits.set(17); //EX
        AcknowledgeResponse response = new AcknowledgeResponse(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1,
            bits, SYSTEM, 1_000L);

        assertFalse(response.hasSourceAddress());
        assertTargetSubscriber(response.getIdentifiers(), response.getTargetAddress());
        assertEquals(WACN, response.getWACN().getValue());
        assertEquals(SYSTEM, response.getSystemId().getValue());
    }

    @Test
    void acknowledgeNonExtendedInformationKeepsSourceAndLocalTargetSeparate()
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
        bits.set(16); //AIV without EX means octets 3-6 contain a Source Address, not WACN/System.
        bits.setInt(765_432, IntField.length24(32));
        bits.setInt(SUBSCRIBER, IntField.length24(56));
        AcknowledgeResponse response = new AcknowledgeResponse(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1,
            bits, SYSTEM, 1_000L);

        assertFalse(response.hasWACN());
        assertFalse(response.hasSystem());
        assertFalse(response.getTargetAddress() instanceof APCO25FullyQualifiedRadioIdentifier);
        assertEquals(765_432, response.getSourceAddress().getValue());
        assertEquals(List.of(response.getTargetAddress(), response.getSourceAddress()), response.getIdentifiers());
    }

    private static CorrectedBinaryMessage explicitSubscriberBits()
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
        bits.setInt(WACN, IntField.length20(24));
        bits.setInt(SYSTEM, IntField.length12(44));
        bits.setInt(SUBSCRIBER, IntField.length24(56));
        return bits;
    }

    private static void assertSourceSubscriber(List<Identifier> identifiers, Identifier accessor)
    {
        APCO25FullyQualifiedRadioIdentifier subscriber = assertOneSubscriber(identifiers, accessor);
        assertEquals(io.github.dsheirer.identifier.Role.FROM, subscriber.getRole());
    }

    private static void assertTargetSubscriber(List<Identifier> identifiers, Identifier accessor)
    {
        APCO25FullyQualifiedRadioIdentifier subscriber = assertOneSubscriber(identifiers, accessor);
        assertEquals(io.github.dsheirer.identifier.Role.TO, subscriber.getRole());
    }

    private static APCO25FullyQualifiedRadioIdentifier assertOneSubscriber(List<Identifier> identifiers,
                                                                            Identifier accessor)
    {
        assertEquals(1, identifiers.size());
        APCO25FullyQualifiedRadioIdentifier subscriber = assertInstanceOf(
            APCO25FullyQualifiedRadioIdentifier.class, identifiers.getFirst());
        assertSame(accessor, subscriber);
        assertEquals(SUBSCRIBER, subscriber.getValue());
        assertEquals(WACN, subscriber.getWacn());
        assertEquals(SYSTEM, subscriber.getSystem());
        assertEquals(SUBSCRIBER, subscriber.getRadio());
        return subscriber;
    }
}
