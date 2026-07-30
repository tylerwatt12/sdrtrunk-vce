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
package io.github.dsheirer.channel.metadata.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ChannelTagTest
{
    @Test
    void mapsNetworkRolesWithoutTreatingStationIdentificationAsTraffic()
    {
        assertEquals(ChannelTag.CURRENT_CONTROL, ChannelTag.fromNetworkRole("primary_control"));
        assertEquals(ChannelTag.ALTERNATE_CONTROL, ChannelTag.fromNetworkRole("alternate_control"));
        assertEquals(ChannelTag.DATA_ANNOUNCED, ChannelTag.fromNetworkRole("fdma_data"));
        assertEquals(ChannelTag.CWID, ChannelTag.fromNetworkRole("base_station"));
        assertNull(ChannelTag.fromNetworkRole("unknown"));
    }
}
