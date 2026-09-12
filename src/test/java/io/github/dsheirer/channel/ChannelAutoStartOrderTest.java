/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dsheirer.controller.channel.Channel;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChannelAutoStartOrderTest
{
    @Test
    void orderIsDenseAndNullableForDisabledChannels()
    {
        Channel first = channel("00000000-0000-0000-0000-000000000001", true, 8);
        Channel second = channel("00000000-0000-0000-0000-000000000002", false, null);
        Channel third = channel("00000000-0000-0000-0000-000000000003", true, 2);
        List<Channel> channels = List.of(first, second, third);

        assertEquals(List.of(third.getConfigurationId(), first.getConfigurationId()),
            ChannelAdministrationService.effectiveAutoStartIds(channels));
        ChannelAdministrationService.applyAutoStartOrder(channels,
            List.of(first.getConfigurationId(), second.getConfigurationId()));

        assertEquals(1, first.getAutoStartOrder());
        assertEquals(2, second.getAutoStartOrder());
        assertFalse(third.isAutoStart());
        assertNull(third.getAutoStartOrder());
    }

    private static Channel channel(String id, boolean enabled, Integer order)
    {
        Channel channel = new Channel();
        channel.setConfigurationId(id);
        channel.setAutoStart(enabled);
        channel.setAutoStartOrder(order);
        return channel;
    }
}
