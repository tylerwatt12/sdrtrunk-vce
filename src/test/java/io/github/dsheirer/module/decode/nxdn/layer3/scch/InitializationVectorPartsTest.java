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

package io.github.dsheirer.module.decode.nxdn.layer3.scch;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.nxdn.layer2.LICH;
import io.github.dsheirer.module.decode.nxdn.layer3.NXDNMessageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class InitializationVectorPartsTest
{
    @Test
    void extractsHighElevenBitsFromInformation1()
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(26);
        message.setInt(0x5AB, new int[]{8, 9, 10, 11, 12, 18, 19, 20, 21, 22, 23});
        InitializationVectorPart1 part1 = new InitializationVectorPart1(message, 0,
            NXDNMessageType.TYPE_D_SCCH_OUT_INFO_1_INITIALIZATION_VECTOR_PART1, 0,
            LICH.RTCH_2_OUTBOUND_SUPER_VOICE_VOICE);

        assertEquals(0x5AB, part1.getIV());
    }

    @Test
    void extractsLowTwelveBitsFromInformation3()
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(26);
        message.setInt(31, new int[]{8, 9, 10, 11, 12});
        message.setInt(0xCDE, new int[]{13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24});
        InitializationVectorPart2 part2 = new InitializationVectorPart2(message, 0,
            NXDNMessageType.TYPE_D_SCCH_OUT_INFO_3_INITIALIZATION_VECTOR_PART2, 0,
            LICH.RTCH_2_OUTBOUND_SUPER_VOICE_VOICE);

        assertEquals(0xCDE, part2.getIV());
    }
}
