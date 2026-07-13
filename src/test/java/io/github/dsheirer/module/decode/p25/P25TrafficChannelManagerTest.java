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

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class P25TrafficChannelManagerTest
{
    @Test
    void clearsFrequencyBandsWhenControlFrequencyChanges() throws Exception
    {
        Channel parentChannel = new Channel("Control");
        TestP25TrafficChannelManager manager = new TestP25TrafficChannelManager(parentChannel);
        manager.processFrequencyBand(band(0, 851_006_250L, 6250L, 1));

        assertEquals(1, frequencyBands(manager).size());

        manager.changeControlFrequency(851_012_500L, 852_012_500L, parentChannel);

        assertTrue(frequencyBands(manager).isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer,IFrequencyBand> frequencyBands(P25TrafficChannelManager manager) throws Exception
    {
        Field field = P25TrafficChannelManager.class.getDeclaredField("mFrequencyBandMap");
        field.setAccessible(true);
        return (Map<Integer,IFrequencyBand>)field.get(manager);
    }

    private static IFrequencyBand band(int identifier, long base, long spacing, int timeslots)
    {
        return new P25FrequencyBand(identifier, base, -45_000_000L, spacing, 12_500, timeslots);
    }

    private static class TestP25TrafficChannelManager extends P25TrafficChannelManager
    {
        private TestP25TrafficChannelManager(Channel parentChannel)
        {
            super(parentChannel);
        }

        private void changeControlFrequency(long previous, long current, Channel parentChannel)
        {
            processControlFrequencyUpdate(previous, current, parentChannel);
        }
    }
}
