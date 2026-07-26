/*
 * *****************************************************************************
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.p25;

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import io.github.dsheirer.protocol.Protocol;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class P25FrequencyBandConfirmationTrackerTest
{
    @Test
    void requiresTwoMatchingOverTheAirObservations()
    {
        Map<Integer,IFrequencyBand> bands = new HashMap<>();
        P25FrequencyBandConfirmationTracker tracker = new P25FrequencyBandConfirmationTracker();
        IFrequencyBand band = new RawBand(band(-45_000_000L), 1000L);

        assertTrue(tracker.observe(bands, band, false, 1000L).pending());
        assertTrue(bands.isEmpty());
        IFrequencyBand repeated = new RawBand(band(-45_000_000L), 2000L);
        assertTrue(tracker.observe(bands, repeated, false, 2000L).accepted());
        assertEquals(repeated, bands.get(0));
    }

    @Test
    void trustsPreloadedBandImmediately()
    {
        Map<Integer,IFrequencyBand> bands = new HashMap<>();
        IFrequencyBand band = band(-45_000_000L);

        assertTrue(new P25FrequencyBandConfirmationTracker().observe(bands, band, true, 1000L).accepted());
        assertEquals(band, bands.get(0));
    }

    @Test
    void treatsTransmitOffsetAsPartOfBandIdentity()
    {
        assertTrue(!P25FrequencyBandValidator.matches(band(-45_000_000L), band(-30_000_000L)));
    }

    private static IFrequencyBand band(long transmitOffset)
    {
        return new P25FrequencyBand(0, 851_006_250L, transmitOffset, 6250L, 12_500, 1);
    }

    private record RawBand(IFrequencyBand delegate, long timestamp) implements IFrequencyBand, IMessage
    {
        @Override public int getIdentifier() { return delegate.getIdentifier(); }
        @Override public long getChannelSpacing() { return delegate.getChannelSpacing(); }
        @Override public long getBaseFrequency() { return delegate.getBaseFrequency(); }
        @Override public int getBandwidth() { return delegate.getBandwidth(); }
        @Override public long getTransmitOffset() { return delegate.getTransmitOffset(); }
        @Override public long getDownlinkFrequency(int channelNumber) { return delegate.getDownlinkFrequency(channelNumber); }
        @Override public long getUplinkFrequency(int channelNumber) { return delegate.getUplinkFrequency(channelNumber); }
        @Override public boolean isTDMA() { return delegate.isTDMA(); }
        @Override public int getTimeslotCount() { return delegate.getTimeslotCount(); }
        @Override public long getTimestamp() { return timestamp; }
        @Override public boolean isValid() { return true; }
        @Override public Protocol getProtocol() { return Protocol.APCO25; }
        @Override public int getTimeslot() { return 0; }
        @Override public List<Identifier> getIdentifiers() { return List.of(); }
    }
}
