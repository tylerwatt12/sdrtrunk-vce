/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.metadata.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SiteControlChannelLearnerTest
{
    private static final long PRIMARY = 851_012_500L;
    private static final long ALTERNATE = 852_012_500L;
    private static final long MANUAL = 853_012_500L;
    private static final long SECOND_ALTERNATE = 854_012_500L;
    private static final int WACN = 0xBEE00;
    private static final int SYSTEM = 0x123;
    private static final int NAC = 0x456;
    private static final int RFSS = 1;
    private static final int SITE = 2;

    @Test
    void bindsCompleteIdentityAndOwnsOnlyFrequenciesItAdds()
    {
        AtomicInteger saves = new AtomicInteger();
        SiteControlChannelLearner learner = new SiteControlChannelLearner(saves::incrementAndGet);
        Channel channel = channel(List.of(PRIMARY, MANUAL));
        DecodeConfigP25Phase1 decode = decode(channel);

        learner.receiveSiteMetadata(event(channel, PRIMARY, 1_000, WACN, SYSTEM, RFSS, SITE,
            List.of(PRIMARY, ALTERNATE, MANUAL)));

        assertEquals(new P25SiteIdentity(WACN, SYSTEM, RFSS, SITE), channel.getP25SiteIdentity());
        assertEquals(List.of(PRIMARY, MANUAL, ALTERNATE), frequencies(channel));
        assertEquals(List.of(ALTERNATE), decode.getLearnedControlFrequencies());
        assertEquals(1, saves.get());
    }

    @Test
    void bindsIdentityWithoutFrequencyLearningOrFrequencyMutation()
    {
        AtomicInteger saves = new AtomicInteger();
        SiteControlChannelLearner learner = new SiteControlChannelLearner(saves::incrementAndGet);
        Channel channel = channel(List.of(PRIMARY, MANUAL), false);

        learner.receiveSiteMetadata(event(channel, PRIMARY, 1_000, WACN, SYSTEM, RFSS, SITE,
            List.of(PRIMARY, ALTERNATE)));

        assertEquals(new P25SiteIdentity(WACN, SYSTEM, RFSS, SITE), channel.getP25SiteIdentity());
        assertEquals(List.of(PRIMARY, MANUAL), frequencies(channel));
        assertTrue(decode(channel).getLearnedControlFrequencies().isEmpty());
        assertEquals(1, saves.get());
    }

    @Test
    void bindsIdentityForSingleFrequencyTunerSource()
    {
        AtomicInteger saves = new AtomicInteger();
        SiteControlChannelLearner learner = new SiteControlChannelLearner(saves::incrementAndGet);
        Channel channel = new Channel("Control");
        DecodeConfigP25Phase1 decode = new DecodeConfigP25Phase1();
        decode.setLearnAnnouncedControlChannels(true);
        channel.setDecodeConfiguration(decode);
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(PRIMARY);
        channel.setSourceConfiguration(source);

        learner.receiveSiteMetadata(event(channel, PRIMARY, 1_000, WACN, SYSTEM, RFSS, SITE,
            List.of(PRIMARY, ALTERNATE)));

        assertEquals(new P25SiteIdentity(WACN, SYSTEM, RFSS, SITE), channel.getP25SiteIdentity());
        assertEquals(PRIMARY, source.getFrequency());
        assertTrue(decode.getLearnedControlFrequencies().isEmpty());
        assertEquals(1, saves.get());
    }

    @Test
    void rejectsAndRemovesLearnedFrequencyThatDecodesAnotherSystem()
    {
        AtomicInteger saves = new AtomicInteger();
        SiteControlChannelLearner learner = new SiteControlChannelLearner(saves::incrementAndGet);
        Channel channel = channel(List.of(PRIMARY));
        DecodeConfigP25Phase1 decode = decode(channel);

        learner.receiveSiteMetadata(event(channel, PRIMARY, 1_000, WACN, SYSTEM, RFSS, SITE,
            List.of(PRIMARY, ALTERNATE)));
        assertTrue(frequencies(channel).contains(ALTERNATE));

        learner.receiveSiteMetadata(event(channel, ALTERNATE, 2_000, 0xA0001, 0x777, 4, 5,
            List.of(ALTERNATE, 854_012_500L)));

        assertEquals(new P25SiteIdentity(WACN, SYSTEM, RFSS, SITE), channel.getP25SiteIdentity());
        assertEquals(List.of(PRIMARY), frequencies(channel));
        assertFalse(decode.getLearnedControlFrequencies().contains(ALTERNATE));
        assertEquals(2, saves.get());
    }

    @Test
    void waitsForFullPostRestartObservationWindowBeforeRemovingAbsentLearnedFrequency()
    {
        Channel channel = channel(List.of(PRIMARY));
        SiteControlChannelLearner firstRun = new SiteControlChannelLearner(() -> {});
        firstRun.receiveSiteMetadata(event(channel, PRIMARY, 1_000, WACN, SYSTEM, RFSS, SITE,
            List.of(PRIMARY, ALTERNATE)));

        AtomicInteger restartSaves = new AtomicInteger();
        SiteControlChannelLearner restarted = new SiteControlChannelLearner(restartSaves::incrementAndGet);
        restarted.receiveSiteMetadata(event(channel, PRIMARY, 10_000, WACN, SYSTEM, RFSS, SITE,
            List.of(PRIMARY)));
        assertTrue(frequencies(channel).contains(ALTERNATE));

        restarted.receiveSiteMetadata(event(channel, PRIMARY,
            10_000 + SiteControlChannelLearner.ABSENT_FREQUENCY_RECONCILIATION_DELAY_MILLISECONDS - 1,
            WACN, SYSTEM, RFSS, SITE, List.of(PRIMARY)));
        assertTrue(frequencies(channel).contains(ALTERNATE));

        restarted.receiveSiteMetadata(event(channel, PRIMARY,
            10_000 + SiteControlChannelLearner.ABSENT_FREQUENCY_RECONCILIATION_DELAY_MILLISECONDS,
            WACN, SYSTEM, RFSS, SITE, List.of(PRIMARY)));
        assertEquals(List.of(PRIMARY), frequencies(channel));
        assertEquals(1, restartSaves.get());
    }

    @Test
    void neverRemovesUnownedManualFrequency()
    {
        Channel channel = channel(List.of(PRIMARY, MANUAL));
        channel.setP25SiteIdentity(new P25SiteIdentity(WACN, SYSTEM, RFSS, SITE));
        SiteControlChannelLearner learner = new SiteControlChannelLearner(() -> {});

        learner.receiveSiteMetadata(event(channel, PRIMARY, 1_000, WACN, SYSTEM, RFSS, SITE,
            List.of(PRIMARY)));
        learner.receiveSiteMetadata(event(channel, PRIMARY,
            1_000 + SiteControlChannelLearner.ABSENT_FREQUENCY_RECONCILIATION_DELAY_MILLISECONDS,
            WACN, SYSTEM, RFSS, SITE, List.of(PRIMARY)));

        assertEquals(List.of(PRIMARY, MANUAL), frequencies(channel));
    }

    @Test
    void validControlRotationStartsANewAbsenceObservationWindow()
    {
        Channel channel = channel(List.of(PRIMARY));
        SiteControlChannelLearner learner = new SiteControlChannelLearner(() -> {});
        learner.receiveSiteMetadata(event(channel, PRIMARY, 1_000, WACN, SYSTEM, RFSS, SITE,
            List.of(PRIMARY, ALTERNATE, SECOND_ALTERNATE)));

        long afterFirstWindow = 1_000 +
            SiteControlChannelLearner.ABSENT_FREQUENCY_RECONCILIATION_DELAY_MILLISECONDS;
        learner.receiveSiteMetadata(event(channel, ALTERNATE, afterFirstWindow, WACN, SYSTEM, RFSS, SITE,
            List.of(ALTERNATE)));

        assertTrue(frequencies(channel).contains(PRIMARY));
        assertTrue(frequencies(channel).contains(ALTERNATE));
        assertTrue(frequencies(channel).contains(SECOND_ALTERNATE));
    }

    @Test
    void incompleteOrInconsistentIdentityCannotBindChannel()
    {
        Channel channel = channel(List.of(PRIMARY));
        SiteControlChannelLearner learner = new SiteControlChannelLearner(() -> {});
        P25NetworkConfigurationSnapshot snapshot = new P25NetworkConfigurationSnapshot("P25_PHASE_1",
            new P25NetworkConfigurationSnapshot.Network(WACN, SYSTEM, NAC, null),
            new P25NetworkConfigurationSnapshot.CurrentSite(SYSTEM + 1, NAC, RFSS, SITE, null, null),
            controls(List.of(PRIMARY)), List.of(), List.of(), List.of(), List.of());

        learner.receiveSiteMetadata(new SiteMetadataEvent(channel, snapshot, 1_000, PRIMARY));

        assertNull(channel.getP25SiteIdentity());
        assertEquals(List.of(PRIMARY), frequencies(channel));
    }

    private static Channel channel(List<Long> frequencies)
    {
        return channel(frequencies, true);
    }

    private static Channel channel(List<Long> frequencies, boolean learnAnnouncedControlChannels)
    {
        Channel channel = new Channel("Control");
        DecodeConfigP25Phase1 decode = new DecodeConfigP25Phase1();
        decode.setLearnAnnouncedControlChannels(learnAnnouncedControlChannels);
        channel.setDecodeConfiguration(decode);
        SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
        source.setFrequencies(frequencies);
        channel.setSourceConfiguration(source);
        return channel;
    }

    private static DecodeConfigP25Phase1 decode(Channel channel)
    {
        return (DecodeConfigP25Phase1)channel.getDecodeConfiguration();
    }

    private static List<Long> frequencies(Channel channel)
    {
        return ((SourceConfigTunerMultipleFrequency)channel.getSourceConfiguration()).getFrequencies();
    }

    private static SiteMetadataEvent event(Channel channel, long sourceFrequency, long timestamp, int wacn,
                                           int system, int rfss, int site, List<Long> controls)
    {
        P25NetworkConfigurationSnapshot snapshot = new P25NetworkConfigurationSnapshot("P25_PHASE_1",
            new P25NetworkConfigurationSnapshot.Network(wacn, system, NAC, null),
            new P25NetworkConfigurationSnapshot.CurrentSite(system, NAC, rfss, site, null, null),
            controls(controls), List.of(), List.of(), List.of(), List.of());
        return new SiteMetadataEvent(channel, snapshot, timestamp, sourceFrequency);
    }

    private static List<P25NetworkConfigurationSnapshot.Channel> controls(List<Long> frequencies)
    {
        return frequencies.stream().map(frequency -> new P25NetworkConfigurationSnapshot.Channel(
            frequency.equals(frequencies.getFirst()) ? "primary_control" : "secondary_control", null, frequency, null,
            false, 1)).toList();
    }
}
