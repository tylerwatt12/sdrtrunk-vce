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

package io.github.dsheirer.module.decode.nxdn.layer3.type;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannelDFA;
import org.junit.jupiter.api.Test;

class ChannelAccessInformationTest
{
    @Test
    void calculatesDefinedDfaBaseFrequencies()
    {
        assertEquals(100_003_125, create(1, 3).getFrequency(1));
        assertEquals(330_003_125, create(2, 3).getFrequency(1));
        assertEquals(400_003_125, create(3, 3).getFrequency(1));
        assertEquals(750_003_125, create(4, 3).getFrequency(1));
    }

    @Test
    void rejectsReservedAndSystemDefinedValuesWithoutAConfiguredDefinition()
    {
        assertEquals(0, create(0, 3).getFrequency(1));
        assertEquals(0, create(5, 3).getFrequency(1));
        assertEquals(0, create(6, 3).getFrequency(1));
        assertEquals(0, create(7, 3).getFrequency(1));
        assertEquals(0, create(1, 0).getFrequency(1));
    }

    @Test
    void retainsDfaBandwidth()
    {
        NXDNChannelDFA channel = new NXDNChannelDFA(1, 2, Bandwidth.BW_12_5);
        assertEquals(Bandwidth.BW_12_5, channel.getBandwidth());
        assertEquals(TransmissionMode.M9600, channel.getBandwidth().getTransmissionMode());
    }

    private static ChannelAccessInformation create(int base, int step)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(6);
        message.set(0);
        message.load(1, 2, step);
        message.load(3, 3, base);
        return new ChannelAccessInformation(message, 0);
    }
}
