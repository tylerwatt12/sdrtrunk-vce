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

    @Test
    void creationDefaultsMatchRuntimeBehaviorWithRequestedCqpskPreference()
    {
        ChannelProtocolRegistry registry = new ChannelProtocolRegistry();

        assertEquals("CQPSK", registry.require("p25-conventional").defaultSettings().get("modulation"));
        assertEquals("CQPSK", registry.require("p25-phase1").defaultSettings().get("modulation"));
        assertEquals(true, registry.require("p25-phase1").defaultSettings()
            .get("learn_announced_control_channels"));
        assertEquals(true, registry.require("p25-phase2").defaultSettings()
            .get("learn_announced_control_channels"));
        assertEquals(false, registry.require("p25-phase2").defaultSettings()
            .get("auto_detect_scramble_parameters"));
        assertEquals("BW_15_0", registry.require("am").defaultSettings().get("bandwidth"));
        assertEquals("BW_12_5", registry.require("nbfm").defaultSettings().get("bandwidth"));
        assertEquals(true, registry.require("dmr").defaultSettings().get("ignore_data_calls"));
        assertEquals("TRUNKED", registry.require("nxdn").defaultSettings().get("channel_mode"));

        String catalog = registry.catalog().toString();
        assertTrue(catalog.contains("\"visible_when\":{\"path\":\"settings.low_pass_enabled\""));
        assertTrue(catalog.contains("\"number_maximum\":2048"));
        assertTrue(catalog.contains("\"options_source\":\"alias_lists\""));
    }
}
