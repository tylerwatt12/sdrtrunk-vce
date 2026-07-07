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

import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class P25FrequencyBandValidatorTest
{
    @Test
    void acceptsValidBand()
    {
        Map<Integer,IFrequencyBand> bands = new HashMap<>();
        IFrequencyBand band = band(0, 851_006_250L, 6250L, 1);

        P25FrequencyBandValidator.RegistrationResult result =
            P25FrequencyBandValidator.register(bands, band);

        assertTrue(result.accepted());
        assertFalse(result.replaced());
        assertSame(band, bands.get(0));
    }

    @Test
    void rejectsImplausibleBand()
    {
        P25FrequencyBandValidator.RegistrationResult result =
            P25FrequencyBandValidator.register(new HashMap<>(), band(0, 2_013_551_840L, 6250L, 1));

        assertFalse(result.accepted());
        assertEquals(P25FrequencyBandValidator.RejectReason.BASE_OUTSIDE_RF_RANGE, result.rejectReason());
    }

    @Test
    void rejectsInvalidSpacing()
    {
        P25FrequencyBandValidator.RegistrationResult result =
            P25FrequencyBandValidator.register(new HashMap<>(), band(0, 851_006_250L, 6375L, 1));

        assertFalse(result.accepted());
        assertEquals(P25FrequencyBandValidator.RejectReason.INVALID_CHANNEL_SPACING, result.rejectReason());
    }

    @Test
    void rejectsConflictingBandThatIsNotBetterAligned()
    {
        Map<Integer,IFrequencyBand> bands = new HashMap<>();
        IFrequencyBand existing = band(0, 851_006_250L, 6250L, 1);
        P25FrequencyBandValidator.register(bands, existing);

        P25FrequencyBandValidator.RegistrationResult result =
            P25FrequencyBandValidator.register(bands, band(0, 851_006_251L, 6250L, 1));

        assertFalse(result.accepted());
        assertSame(existing, bands.get(0));
    }

    @Test
    void replacesConflictingBandWhenCandidateIsBetterAligned()
    {
        Map<Integer,IFrequencyBand> bands = new HashMap<>();
        P25FrequencyBandValidator.register(bands, band(0, 851_006_251L, 6250L, 1));
        IFrequencyBand better = band(0, 851_006_250L, 6250L, 1);

        P25FrequencyBandValidator.RegistrationResult result =
            P25FrequencyBandValidator.register(bands, better);

        assertTrue(result.accepted());
        assertTrue(result.replaced());
        assertSame(better, bands.get(0));
    }

    @Test
    void resolvesFdmaAndTdmaChannels()
    {
        APCO25Channel fdma = APCO25Channel.create(0, 501);
        fdma.setFrequencyBand(band(0, 851_006_250L, 6250L, 1));

        APCO25Channel tdma = APCO25Channel.create(1, 3);
        tdma.setFrequencyBand(band(1, 851_012_500L, 12500L, 2));

        assertTrue(P25FrequencyBandValidator.isResolvedChannel(fdma));
        assertEquals(854_137_500L, fdma.getDownlinkFrequency());
        assertTrue(P25FrequencyBandValidator.isResolvedChannel(tdma));
        assertEquals(851_025_000L, tdma.getDownlinkFrequency());
    }

    @Test
    void rejectsUnresolvedInvalidAndNoChannelDescriptors()
    {
        APCO25Channel unresolved = APCO25Channel.create(0, 501);
        APCO25Channel invalidChannel = APCO25Channel.create(0, 4096);
        invalidChannel.setFrequencyBand(band(0, 851_006_250L, 6250L, 1));
        APCO25Channel noChannel = APCO25Channel.create(15, 4095);
        noChannel.setFrequencyBand(band(15, 851_006_250L, 6250L, 1));

        assertFalse(P25FrequencyBandValidator.isResolvedChannel(unresolved));
        assertFalse(P25FrequencyBandValidator.isResolvedChannel(invalidChannel));
        assertFalse(P25FrequencyBandValidator.isResolvedChannel(noChannel));
        assertFalse(P25FrequencyBandValidator.hasChannel(15, 4095));
        assertTrue(P25FrequencyBandValidator.hasChannel(0, 4095));
    }

    private static IFrequencyBand band(int identifier, long base, long spacing, int timeslots)
    {
        return new P25FrequencyBand(identifier, base, -45_000_000L, spacing, 12_500, timeslots);
    }
}
