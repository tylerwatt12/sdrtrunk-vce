/*
 * *****************************************************************************
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.identifier.channel;

import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class P25ChannelTest
{
    @Test
    void toStringUsesDownlinkLogicalChannelOnly()
    {
        assertEquals("0-501", new P25Channel(0, 501).toString());
        assertEquals("0-501", new P25ExplicitChannel(0, 501, 0, 4095).toString());
        assertEquals("0-501", new P25ExplicitChannel(0, 501, 1, 1001).toString());
    }

    @Test
    void toStringKeepsTimeslotWithoutUplinkLogicalChannel()
    {
        P25ExplicitChannel channel = new P25ExplicitChannel(0, 501, 1, 1001);
        channel.setFrequencyBand(new TestFrequencyBand(0, true, 2));

        assertEquals("0-500 TS2", channel.toString());
    }

    @Test
    void absentExplicitUplinkUsesImplicitFDMABandOffset()
    {
        P25ExplicitChannel channel = new P25ExplicitChannel(3, 76, 15, 4095);
        channel.setFrequencyBand(new P25FrequencyBand(3, 420_012_500L, 6_000_000L,
            6_250L, 12_500, 1));

        assertArrayEquals(new int[]{3}, channel.getFrequencyBandIdentifiers());
        assertEquals(420_487_500L, channel.getDownlinkFrequency());
        assertEquals(426_487_500L, channel.getUplinkFrequency());
    }

    @Test
    void absentExplicitUplinkUsesImplicitTDMABandOffset()
    {
        P25P2ExplicitChannel channel = new P25P2ExplicitChannel(9, 16, 15, 4095);
        channel.setFrequencyBand(new P25FrequencyBand(9, 425_262_500L, 4_000_000L,
            6_250L, 12_500, 2));

        assertArrayEquals(new int[]{9}, channel.getFrequencyBandIdentifiers());
        assertEquals(425_312_500L, channel.getDownlinkFrequency());
        assertEquals(429_312_500L, channel.getUplinkFrequency());
        assertEquals(1, channel.getTimeslot());
    }

    @Test
    void genuineExplicitUplinkUsesItsOwnBandAndChannel()
    {
        P25ExplicitChannel channel = new P25ExplicitChannel(3, 76, 8, 62);
        channel.setFrequencyBand(new P25FrequencyBand(3, 420_012_500L, 6_000_000L,
            6_250L, 12_500, 1));
        channel.setFrequencyBand(new P25FrequencyBand(8, 467_512_500L, -10_000_000L,
            6_250L, 12_500, 1));

        assertArrayEquals(new int[]{3, 8}, channel.getFrequencyBandIdentifiers());
        assertEquals(420_487_500L, channel.getDownlinkFrequency());
        assertEquals(467_900_000L, channel.getUplinkFrequency());
    }

    @Test
    void absentExplicitUplinkRemainsUnresolvedUntilDownlinkBandIsKnown()
    {
        P25ExplicitChannel channel = new P25ExplicitChannel(3, 76, 15, 4095);

        assertEquals(0L, channel.getUplinkFrequency());
    }

    private record TestFrequencyBand(int identifier, boolean tdma, int timeslotCount) implements IFrequencyBand
    {
        @Override
        public int getIdentifier()
        {
            return identifier;
        }

        @Override
        public long getChannelSpacing()
        {
            return 6250;
        }

        @Override
        public long getBaseFrequency()
        {
            return 851_006_250;
        }

        @Override
        public int getBandwidth()
        {
            return 12_500;
        }

        @Override
        public long getTransmitOffset()
        {
            return -45_000_000;
        }

        @Override
        public long getDownlinkFrequency(int channelNumber)
        {
            return getBaseFrequency() + channelNumber * getChannelSpacing();
        }

        @Override
        public long getUplinkFrequency(int channelNumber)
        {
            return getDownlinkFrequency(channelNumber) + getTransmitOffset();
        }

        @Override
        public boolean isTDMA()
        {
            return tdma;
        }

        @Override
        public int getTimeslotCount()
        {
            return timeslotCount;
        }
    }
}
