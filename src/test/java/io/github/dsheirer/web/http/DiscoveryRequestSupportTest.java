/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.channel.ChannelDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiscoveryRequestSupportTest
{
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void acceptsExplicitMapWithOptionalUplinkAndKeepsTheEmptyMapOptional() throws Exception
    {
        assertEquals(List.of(), DiscoveryRequestSupport.frequencyMap(mapper.readTree("{}")));
        assertEquals(List.of(new ChannelDefinition.FrequencyMapEntry(3, 452387500, 0),
            new ChannelDefinition.FrequencyMapEntry(4, 452400000, 457400000)),
            DiscoveryRequestSupport.frequencyMap(mapper.readTree("{\"frequency_map\":[" +
                "{\"number\":3,\"downlink_hz\":452387500}," +
                "{\"number\":4,\"downlink_hz\":452400000,\"uplink_hz\":457400000}]}")));
    }

    @Test void rejectsDuplicateUnknownNonIntegralAndUnboundedMapEntries() throws Exception
    {
        String[] invalid = {
            "{}", "[null]", "[{}]", "[{\"number\":0,\"downlink_hz\":452387500}]",
            "[{\"number\":1.0,\"downlink_hz\":452387500}]",
            "[{\"number\":2147483648,\"downlink_hz\":452387500}]",
            "[{\"number\":1,\"downlink_hz\":\"452387500\"}]",
            "[{\"number\":1,\"downlink_hz\":100000000001}]",
            "[{\"number\":1,\"downlink_hz\":452387500,\"uplink_hz\":-1}]",
            "[{\"number\":1,\"downlink_hz\":452387500,\"on_air\":true}]",
            "[{\"number\":1,\"downlink_hz\":452387500},{\"number\":1,\"downlink_hz\":452400000}]"
        };
        for(String map: invalid)
            assertThrows(IllegalArgumentException.class, () -> DiscoveryRequestSupport.frequencyMap(
                mapper.readTree("{\"frequency_map\":" + map + "}")), map);
        String entry = "{\"number\":1,\"downlink_hz\":452387500}";
        assertThrows(IllegalArgumentException.class, () -> DiscoveryRequestSupport.frequencyMap(mapper.readTree(
            "{\"frequency_map\":[" + String.join(",", java.util.Collections.nCopies(4097, entry)) + "]}")));
    }
}
