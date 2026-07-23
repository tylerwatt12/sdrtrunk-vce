/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.nxdn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import org.junit.jupiter.api.Test;

class NXDNTrafficChannelManagerTest
{
    @Test
    void rateLimitsProgressAtTheDefaultCadence()
    {
        Channel parent = new Channel("NXDN", Channel.ChannelType.STANDARD);
        parent.setDecodeConfiguration(new DecodeConfigNXDN());
        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(parent);
        long frequency = 452_012_500L;

        assertTrue(manager.shouldPublishActivityProgress(frequency, 1_000L));
        assertFalse(manager.shouldPublishActivityProgress(frequency, 1_200L));
        assertFalse(manager.shouldPublishActivityProgress(frequency, 1_499L));
        assertTrue(manager.shouldPublishActivityProgress(frequency, 1_500L));
        assertTrue(manager.shouldPublishActivityProgress(frequency, 1_400L));
    }

    @Test
    void derivesProgressCadenceFromShortTrafficGrantAgeOut()
    {
        Channel parent = new Channel("NXDN", Channel.ChannelType.STANDARD);
        parent.setDecodeConfiguration(new DecodeConfigNXDN());
        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(parent);
        NowPlayingPreference preference = new NowPlayingPreference(type -> {})
        {
            @Override
            public int getTrafficGrantAgeOutMilliseconds()
            {
                return NowPlayingPreference.MIN_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS;
            }
        };
        manager.setChannelActivityModel(new ChannelActivityModel(new AliasModel(), preference));
        long frequency = 452_012_500L;

        assertEquals(50L, manager.getActivityProgressIntervalMilliseconds());
        assertTrue(manager.shouldPublishActivityProgress(frequency, 1_000L));
        assertFalse(manager.shouldPublishActivityProgress(frequency, 1_049L));
        assertTrue(manager.shouldPublishActivityProgress(frequency, 1_050L));
    }
}
