/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.nxdn.layer1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.nxdn.NXDNMessage;
import io.github.dsheirer.module.decode.nxdn.layer2.LICHTracker;
import io.github.dsheirer.module.decode.nxdn.layer3.NXDNLayer3Message;
import io.github.dsheirer.module.decode.nxdn.layer3.NXDNMessageType;
import io.github.dsheirer.module.decode.nxdn.layer3.call.VoiceCallInitializationVector;
import io.github.dsheirer.module.decode.nxdn.layer3.type.TransmissionMode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression tests using transmitted-frame vectors published in NXDN TS 2-E.
 */
class NXDNOfficialVectorTest
{
    @Test
    void decodesConventionalIndividualVoiceCallOpening()
    {
        Frame frame = decodeStandard("DD7D63C2B42E2A8A2A1CA2420281A288AA28A49A2BED81C20836908482C02A2B" +
            "020A22AA261A2B4DA9E00ABEB22");

        List<NXDNLayer3Message> voiceCalls = frame.getMessages().stream()
            .filter(NXDNLayer3Message.class::isInstance)
            .map(NXDNLayer3Message.class::cast)
            .filter(message -> message.getMessageType() == NXDNMessageType.TRAFFIC_OUT_01_CC_VOICE_CALL ||
                message.getMessageType() == NXDNMessageType.TRAFFIC_IN_01_CC_VOICE_CALL)
            .toList();

        assertEquals(2, voiceCalls.size());
        assertTrue(voiceCalls.stream().allMatch(NXDNLayer3Message::isValid));
    }

    @Test
    void decodesConventionalUdchHeader()
    {
        Frame frame = decodeStandard("DFDF4088A019608828E8662200CA420ACE3AA080008AA0824922824C03A02D72328821BBE" +
            "202EC2688A60F6AA03");
        assertHasValidKnownLayer3Message(frame);
    }

    @Test
    void decodesStandardTrunkingInboundLongCac()
    {
        Frame frame = decodeStandard("5F7D08A0AB0AAA0420A2056264A1A002A22D220262E4A1C048880601E4102127628");
        assertHasValidKnownLayer3Message(frame);
    }

    @Test
    void decodesStandardTrunkingOutboundNormalCac()
    {
        Frame frame = decodeStandard("5D774088A0AE8A8868EE20462E8894182A38A28235EB008E88B280D030A3893603DC0" +
            "EAAC254283");
        assertHasValidKnownLayer3Message(frame);
    }

    @Test
    void decodesTypeDOutboundFacch3CallConnectionResponse()
    {
        Frame frame = decodeTypeD("FF75434CE7AE03D22A1" +
            "D8B462AC1EAB9F8942C46297881678A7FD0A402A42A23020822AAA2122A0D8BA18AEAF00");

        NXDNLayer3Message message = getLayer3(frame, NXDNMessageType.TYPE_D_OUT_06_CC_CALL_CONNECTION_RESPONSE);
        assertTrue(message.isValid());
    }

    @Test
    void decodesTypeDOutboundEncryptedVoiceCallAndInitializationVector()
    {
        Frame frame = decodeTypeD("FD77434CE7AE03D22A1" +
            "DA2422885AC09A908A44A2B7982E388BFD02" +
            "40920EA2342286A86B41F220A09A0DA62F40");

        NXDNLayer3Message voiceCall = getLayer3(frame, NXDNMessageType.TYPE_D_OUT_01_CC_VOICE_CALL);
        VoiceCallInitializationVector initializationVector = (VoiceCallInitializationVector)getLayer3(frame,
            NXDNMessageType.TYPE_D_OUT_03_CC_VOICE_CALL_INITIALIZATION_VECTOR);

        assertTrue(voiceCall.isValid());
        assertTrue(initializationVector.isValid());
        assertEquals("123456", initializationVector.getInitializationVector());
    }

    private static Frame decodeTypeD(String transmittedFrame)
    {
        return new Frame(CorrectedBinaryMessage.loadHex(transmittedFrame), 0,
            new LICHTracker(TransmissionMode.TYPE_D));
    }

    private static Frame decodeStandard(String transmittedFrame)
    {
        return new Frame(CorrectedBinaryMessage.loadHex(transmittedFrame), 0,
            new LICHTracker(TransmissionMode.M4800));
    }

    private static void assertHasValidKnownLayer3Message(Frame frame)
    {
        assertTrue(frame.getMessages().stream()
            .filter(NXDNLayer3Message.class::isInstance)
            .map(NXDNLayer3Message.class::cast)
            .anyMatch(message -> message.isValid() && message.getMessageType() != NXDNMessageType.UNKNOWN));
    }

    private static NXDNLayer3Message getLayer3(Frame frame, NXDNMessageType expectedType)
    {
        List<NXDNMessage> messages = frame.getMessages();

        return messages.stream()
            .filter(NXDNLayer3Message.class::isInstance)
            .map(NXDNLayer3Message.class::cast)
            .filter(message -> message.getMessageType() == expectedType)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing " + expectedType + " in " + messages));
    }
}
