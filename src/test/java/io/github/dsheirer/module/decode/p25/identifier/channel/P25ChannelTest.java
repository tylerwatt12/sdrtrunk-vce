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
import org.junit.jupiter.api.Test;

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
