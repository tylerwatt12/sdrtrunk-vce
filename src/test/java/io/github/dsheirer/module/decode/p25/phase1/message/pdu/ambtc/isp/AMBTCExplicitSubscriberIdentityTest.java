/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.isp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.message.SymbolMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequence;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.AMBTCHeader;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.block.UnconfirmedDataBlock;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Extended ISP identity layouts from TIA-102.AABC-B sections 4.1.2.2, 4.1.3.2, 5.1.1.2, 6.1.1.2,
 * 6.1.2.2-6.1.4.2, 6.1.11.2, 6.1.13.2-6.1.15.2, 6.1.18.2, and 6.1.20.2.
 */
class AMBTCExplicitSubscriberIdentityTest
{
    private static final int WACN = 0xBEE00;
    private static final int SYSTEM = 0x348;
    private static final int SUBSCRIBER = 2_115_288;
    private static final int SOURCE_ADDRESS = 765_432;

    @Test
    void splitWacnTargetLayoutsUseTheDataBlockTargetAndHeaderSource()
    {
        CorrectedBinaryMessage header = header(SOURCE_ADDRESS);
        header.setInt(WACN >>> 4, IntField.length16(64));
        CorrectedBinaryMessage block = new CorrectedBinaryMessage(96);
        block.setInt(WACN & 0xF, IntField.length4(0));
        block.setInt(SYSTEM, IntField.length12(4));
        block.setInt(SUBSCRIBER, IntField.length24(16));

        AMBTCAuthenticationQuery authentication = new AMBTCAuthenticationQuery(sequence(header, block), SYSTEM, 1_000L);
        AMBTCCallAlertRequest callAlert = new AMBTCCallAlertRequest(sequence(header, block), SYSTEM, 1_000L);
        AMBTCRoamingAddressRequest roaming = new AMBTCRoamingAddressRequest(sequence(header, block), SYSTEM, 1_000L);
        AMBTCStatusQueryRequest status = new AMBTCStatusQueryRequest(sequence(header, block), SYSTEM, 1_000L);

        assertTarget(authentication.getTargetId());
        assertTarget(callAlert.getTargetId());
        assertTarget(roaming.getTargetId());
        assertTarget(status.getTargetId());
        assertEquals(SOURCE_ADDRESS, callAlert.getSourceAddress().getValue());
        assertEquals(SOURCE_ADDRESS, roaming.getSourceAddress().getValue());
        assertEquals(SOURCE_ADDRESS, status.getSourceAddress().getValue());
        assertOnlySourceAndTarget(callAlert.getIdentifiers(), callAlert.getSourceAddress(), callAlert.getTargetId());
    }

    @Test
    void fullDataBlockTargetLayoutsPublishOneCanonicalTarget()
    {
        CorrectedBinaryMessage header = header(SOURCE_ADDRESS);
        CorrectedBinaryMessage block = new CorrectedBinaryMessage(96);
        block.setInt(WACN, IntField.length20(0));
        block.setInt(SYSTEM, IntField.length12(20));
        block.setInt(SUBSCRIBER, IntField.length24(32));

        AMBTCIndividualDataServiceRequest data = new AMBTCIndividualDataServiceRequest(sequence(header, block),
            SYSTEM, 1_000L);
        AMBTCMessageUpdateRequest message = new AMBTCMessageUpdateRequest(sequence(header, block), SYSTEM, 1_000L);
        AMBTCStatusQueryResponse query = new AMBTCStatusQueryResponse(sequence(header, block), SYSTEM, 1_000L);
        AMBTCStatusUpdateRequest update = new AMBTCStatusUpdateRequest(sequence(header, block), SYSTEM, 1_000L);

        assertTarget(data.getTargetId());
        assertTarget(message.getTargetId());
        assertTarget(query.getTargetId());
        assertTarget(update.getTargetId());
        assertOnlySourceAndTarget(update.getIdentifiers(), update.getSourceAddress(), update.getTargetId());
    }

    @Test
    void unitToUnitLayoutsQualifyTheDataBlockTarget()
    {
        CorrectedBinaryMessage header = header(SOURCE_ADDRESS);
        CorrectedBinaryMessage block = new CorrectedBinaryMessage(96);
        block.setInt(WACN, IntField.length20(8));
        block.setInt(SYSTEM, IntField.length12(28));
        block.setInt(SUBSCRIBER, IntField.length24(40));

        AMBTCUnitToUnitAnswerResponse answer = new AMBTCUnitToUnitAnswerResponse(sequence(header, block),
            SYSTEM, 1_000L);
        AMBTCUnitToUnitVoiceServiceRequest request = new AMBTCUnitToUnitVoiceServiceRequest(sequence(header, block),
            SYSTEM, 1_000L);

        assertTarget(answer.getTargetId());
        assertTarget(request.getTargetId());
        assertOnlySourceAndTarget(request.getIdentifiers(), request.getSourceAddress(), request.getTargetId());
    }

    @Test
    void unitAcknowledgeReassemblesItsEightPlusTwelveBitTargetWacn()
    {
        CorrectedBinaryMessage header = header(SOURCE_ADDRESS);
        header.setInt(WACN >>> 12, IntField.length8(72));
        CorrectedBinaryMessage block = new CorrectedBinaryMessage(96);
        block.setInt(WACN & 0xFFF, IntField.length12(0));
        block.setInt(SYSTEM, IntField.length12(12));
        block.setInt(SUBSCRIBER, IntField.length24(24));
        AMBTCUnitAcknowledgeResponse response = new AMBTCUnitAcknowledgeResponse(sequence(header, block),
            SYSTEM, 1_000L);

        assertTarget(response.getTargetId());
        assertOnlySourceAndTarget(response.getIdentifiers(), response.getSourceAddress(), response.getTargetId());
    }

    @Test
    void authenticationResponsePublishesTheExplicitSourceSuidWithoutInventingAWorkingId()
    {
        CorrectedBinaryMessage header = header(SOURCE_ADDRESS); //The header field is the target address here.
        header.setInt(WACN >>> 4, IntField.length16(64));
        CorrectedBinaryMessage block = new CorrectedBinaryMessage(96);
        block.setInt(WACN & 0xF, IntField.length4(0));
        block.setInt(SYSTEM, IntField.length12(4));
        block.setInt(SUBSCRIBER, IntField.length24(16));
        AMBTCAuthenticationResponse response = new AMBTCAuthenticationResponse(sequence(header, block),
            SYSTEM, 1_000L);

        APCO25FullyQualifiedRadioIdentifier source = assertSource(response.getSourceId(), SUBSCRIBER);
        assertFalse(source.isAliased());
        assertEquals(SOURCE_ADDRESS, response.getTargetAddress().getValue());
        assertOnlySourceAndTarget(response.getIdentifiers(), response.getTargetAddress(), source);
    }

    @Test
    void locationRegistrationKeepsTheHeaderWorkingAddressInsideTheExplicitSourceSuid()
    {
        CorrectedBinaryMessage header = header(SOURCE_ADDRESS);
        header.setInt(WACN >>> 4, IntField.length16(64));
        CorrectedBinaryMessage block = new CorrectedBinaryMessage(96);
        block.setInt(WACN & 0xF, IntField.length4(0));
        block.setInt(SYSTEM, IntField.length12(4));
        block.setInt(SUBSCRIBER, IntField.length24(16));
        block.setInt(0x22, IntField.length8(40));
        block.setInt(12_345, IntField.length16(48));
        AMBTCLocationRegistrationRequest request = new AMBTCLocationRegistrationRequest(sequence(header, block),
            SYSTEM, 1_000L);

        APCO25FullyQualifiedRadioIdentifier source = assertSource(request.getSourceId(), SOURCE_ADDRESS);
        assertTrue(source.isAliased());
        assertSame(source, request.getIdentifiers().getFirst());
        assertEquals(3, request.getIdentifiers().size(), "source SUID, group, and previous LRA only");
    }

    @Test
    void incompleteLocationRegistrationFallsBackToItsHeaderAddressWithoutGuessingAHomeIdentity()
    {
        AMBTCLocationRegistrationRequest request = new AMBTCLocationRegistrationRequest(
            sequence(header(SOURCE_ADDRESS), null), SYSTEM, 1_000L);

        assertEquals(SOURCE_ADDRESS, request.getSourceAddress().getValue());
        assertEquals(List.of(request.getSourceAddress()), request.getIdentifiers());
        assertFalse(request.getIdentifiers().getFirst() instanceof APCO25FullyQualifiedRadioIdentifier);
    }

    private static CorrectedBinaryMessage header(int address)
    {
        CorrectedBinaryMessage header = new CorrectedBinaryMessage(96);
        header.setInt(address, IntField.length24(24));
        return header;
    }

    private static PDUSequence sequence(CorrectedBinaryMessage header, CorrectedBinaryMessage block)
    {
        PDUSequence sequence = new PDUSequence(new AMBTCHeader(header, true), 1_000L, SYSTEM);
        if(block != null)
        {
            sequence.addDataBlock(new TestUnconfirmedDataBlock(block));
        }
        return sequence;
    }

    private static void assertTarget(Identifier identifier)
    {
        APCO25FullyQualifiedRadioIdentifier target = assertInstanceOf(
            APCO25FullyQualifiedRadioIdentifier.class, identifier);
        assertEquals(SUBSCRIBER, target.getValue());
        assertEquals(WACN, target.getWacn());
        assertEquals(SYSTEM, target.getSystem());
        assertEquals(SUBSCRIBER, target.getRadio());
    }

    private static APCO25FullyQualifiedRadioIdentifier assertSource(Identifier identifier, int expectedLocalAddress)
    {
        APCO25FullyQualifiedRadioIdentifier source = assertInstanceOf(
            APCO25FullyQualifiedRadioIdentifier.class, identifier);
        assertEquals(expectedLocalAddress, source.getValue());
        assertEquals(WACN, source.getWacn());
        assertEquals(SYSTEM, source.getSystem());
        assertEquals(SUBSCRIBER, source.getRadio());
        return source;
    }

    private static void assertOnlySourceAndTarget(List<Identifier> identifiers, Identifier first, Identifier second)
    {
        assertEquals(List.of(first, second), identifiers);
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
