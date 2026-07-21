/*
 * ****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.nxdn.layer3.call;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.nxdn.layer2.LICH;
import io.github.dsheirer.module.decode.nxdn.layer3.NXDNMessageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class InitializationVectorTest
{
    @Test
    void formatsStandardInitializationVectorAsSixteenHexDigits()
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(80);
        message.load(8, 64, 0x123L);
        VoiceCallInitializationVector iv = new VoiceCallInitializationVector(message, 0,
            NXDNMessageType.TRAFFIC_OUT_03_CC_VOICE_CALL_INITIALIZATION_VECTOR, 1,
            LICH.RTCH_OUTBOUND_SINGLE_FACCH1_FACCH1);

        assertEquals("0000000000000123", iv.getInitializationVector());
    }

    @Test
    void formatsTypeDInitializationVectorAsSixHexDigits()
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(40);
        message.load(8, 23, 0x123L);
        VoiceCallInitializationVector iv = new VoiceCallInitializationVector(message, 0,
            NXDNMessageType.TYPE_D_OUT_03_CC_VOICE_CALL_INITIALIZATION_VECTOR, 0,
            LICH.RTCH_2_OUTBOUND_SINGLE_FACCH1_FACCH1);

        assertEquals("000123", iv.getInitializationVector());
    }
}
