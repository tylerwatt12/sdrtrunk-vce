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

package io.github.dsheirer.module.decode.p25;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class P25ObservationEventTest
{
    private static final String CONFIGURATION_ID = "00000000-0000-0000-0000-000000000401";

    @Test
    void channelConstructorsCaptureReceiverGeneration() throws Exception
    {
        Channel channel = new Channel("Control");
        channel.setConfigurationId(CONFIGURATION_ID);
        activate(channel, 41L);
        assertEquals(2L, channel.advanceSiteEvidenceTuningGeneration());

        P25GrantObservationEvent grant = new P25GrantObservationEvent(channel, null, null,
            DecodeEventType.CALL_GROUP, 1_000L, false, true);
        P25TrafficChannelConfirmationEvent confirmation =
            new P25TrafficChannelConfirmationEvent(channel, 851_012_500L, 1, 1_001L);

        activate(channel, 42L);
        assertEquals(1L, channel.getSiteEvidenceTuningGeneration());

        assertEquals(41L, grant.processingIncarnation());
        assertEquals(2L, grant.siteEvidenceTuningGeneration());
        assertEquals(41L, confirmation.processingIncarnation());
        assertEquals(2L, confirmation.siteEvidenceTuningGeneration());
    }

    private static void activate(Channel channel, long incarnation) throws Exception
    {
        Method activate = Channel.class.getDeclaredMethod("activateProcessingIncarnation", long.class);
        activate.setAccessible(true);
        activate.invoke(channel, incarnation);
    }
}
