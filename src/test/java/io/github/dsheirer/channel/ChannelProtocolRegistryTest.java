/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.module.decode.DecoderType;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChannelProtocolRegistryTest
{
    @Test
    void catalogCoversEveryAndOnlyActivePrimaryDecoder()
    {
        ChannelProtocolRegistry registry = new ChannelProtocolRegistry();

        assertEquals(DecoderType.PRIMARY_DECODERS.size(), registry.catalog().path("profiles").size());
        DecoderType.PRIMARY_DECODERS.forEach(type -> assertEquals(type, registry.require(type).decoderType()));
        assertFalse(registry.catalog().toString().contains("MPT"));
    }

    @Test
    void submittedSettingsAreRestrictedByTheManifest()
    {
        ChannelProtocolRegistry registry = new ChannelProtocolRegistry();
        ChannelProtocolRegistry.Profile profile = registry.require("p25-phase1");
        Map<String,Object> submitted = new HashMap<>();
        submitted.put("modulation", "CQPSK");
        submitted.put("traffic_channel_pool_size", 12L);

        Map<String,Object> validated = registry.validateSettings(profile, submitted);

        assertEquals("CQPSK", validated.get("modulation"));
        assertEquals(12L, validated.get("traffic_channel_pool_size"));
        assertTrue(validated.containsKey("learn_announced_control_channels"));
        assertThrows(IllegalArgumentException.class,
            () -> registry.validateSettings(profile, Map.of("java_editor_setting", true)));
        assertThrows(IllegalArgumentException.class,
            () -> registry.validateSettings(profile, Map.of("modulation", "NOT_A_MODULATION")));
    }
}
