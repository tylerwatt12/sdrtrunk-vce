/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.p25;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.p25.bandplan.P25BandplanChannelType;
import io.github.dsheirer.module.decode.p25.bandplan.P25BandplanOverrideBand;
import io.github.dsheirer.module.decode.p25.bandplan.P25BandplanOverrideProfile;
import io.github.dsheirer.module.decode.p25.bandplan.P25BandplanOverrideRegistry;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25TrafficChannelManagerBandplanOverrideTest
{
    private static final P25SiteIdentity SITE = new P25SiteIdentity(0xBEE00, 0x49F, 1, 2);

    @Test
    void matchingOverrideReplacesTheWholeOtaPlanAndSurvivesReset()
    {
        Channel parent = parent(true, SITE);
        P25BandplanOverrideProfile profile = new P25BandplanOverrideProfile(SITE.wacn(), SITE.system(), null, null,
            List.of(overrideBand(0, 851_006_250L)));
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent,
            P25BandplanOverrideRegistry.of(List.of(profile)));
        manager.processFrequencyBand(otaBand(0, 762_006_250L));
        manager.processFrequencyBand(otaBand(1, 769_006_250L));

        APCO25Channel overridden = APCO25Channel.create(0, 10);
        assertTrue(manager.resolveControlChannel(overridden));
        assertEquals(851_068_750L, overridden.getDownlinkFrequency());
        APCO25Channel omitted = APCO25Channel.create(1, 10);
        omitted.setFrequencyBand(otaBand(1, 769_006_250L));
        assertFalse(manager.resolveControlChannel(omitted),
            "An override is authoritative and does not mix missing rows from OTA");
        assertEquals(0L, omitted.getDownlinkFrequency(),
            "A previously attached OTA row cannot leak into an active override");

        manager.resetControlSourceState();

        APCO25Channel afterReset = APCO25Channel.create(0, 10);
        assertTrue(manager.resolveControlChannel(afterReset));
        assertEquals(851_068_750L, afterReset.getDownlinkFrequency());
    }

    @Test
    void fallsBackToOtaWhenEnabledOverrideDoesNotMatch()
    {
        Channel parent = parent(true, SITE);
        P25BandplanOverrideProfile otherSystem = new P25BandplanOverrideProfile(0xBEE00, 0x348, null, null,
            List.of(overrideBand(0, 851_006_250L)));
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent,
            P25BandplanOverrideRegistry.of(List.of(otherSystem)));
        manager.processFrequencyBand(otaBand(0, 762_006_250L));

        APCO25Channel channel = APCO25Channel.create(0, 10);
        assertTrue(manager.resolveControlChannel(channel));
        assertEquals(762_068_750L, channel.getDownlinkFrequency());
    }

    @Test
    void disabledOverrideUsesOtaEvenWhenAProfileMatches()
    {
        Channel parent = parent(false, SITE);
        P25BandplanOverrideProfile profile = new P25BandplanOverrideProfile(SITE.wacn(), SITE.system(), null, null,
            List.of(overrideBand(0, 851_006_250L)));
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent,
            P25BandplanOverrideRegistry.of(List.of(profile)));
        manager.processFrequencyBand(otaBand(0, 762_006_250L));

        APCO25Channel channel = APCO25Channel.create(0, 10);
        assertTrue(manager.resolveControlChannel(channel));
        assertEquals(762_068_750L, channel.getDownlinkFrequency());
    }

    @Test
    void stabilizedIdentityBootstrapsOverrideWithoutBindingTheChannel()
    {
        Channel parent = parent(true, null);
        P25BandplanOverrideProfile profile = new P25BandplanOverrideProfile(SITE.wacn(), SITE.system(), SITE.rfss(),
            SITE.site(), List.of(overrideBand(0, 851_006_250L)));
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent,
            P25BandplanOverrideRegistry.of(List.of(profile)));
        manager.processNetworkConfigurationIdentity(SITE);

        APCO25Channel channel = APCO25Channel.create(0, 10);
        assertTrue(manager.resolveControlChannel(channel));
        assertEquals(851_068_750L, channel.getDownlinkFrequency());
        assertNull(parent.getP25SiteIdentity(), "The existing site learner remains the only binding owner");

        manager.resetControlSourceState();
        assertFalse(manager.resolveControlChannel(APCO25Channel.create(0, 10)));
    }

    private static Channel parent(boolean useOverride, P25SiteIdentity identity)
    {
        DecodeConfigP25Phase1 configuration = new DecodeConfigP25Phase1();
        configuration.setUseP25BandplanOverride(useOverride);
        Channel channel = new Channel("Control");
        channel.setDecodeConfiguration(configuration);
        channel.setP25SiteIdentity(identity);
        return channel;
    }

    private static P25BandplanOverrideBand overrideBand(int identifier, long base)
    {
        return new P25BandplanOverrideBand(identifier, P25BandplanChannelType.FDMA, base, 12_500, 6_250L,
            -45_000_000L);
    }

    private static P25FrequencyBand otaBand(int identifier, long base)
    {
        return new P25FrequencyBand(identifier, base, -45_000_000L, 6_250L, 12_500, 1);
    }
}
