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
        assertEquals(false, registry.require("dmr").defaultSettings().get("ignore_encrypted_calls"));
        assertEquals("TRUNKED", registry.require("nxdn").defaultSettings().get("channel_mode"));
        assertEquals(false, registry.require("nxdn").defaultSettings().get("ignore_encrypted_calls"));
        assertEquals(false, registry.require("p25-phase1").defaultSettings().get("ignore_encrypted_calls"));
        assertEquals(false, registry.require("p25-phase2").defaultSettings().get("ignore_encrypted_calls"));

        String catalog = registry.catalog().toString();
        assertTrue(catalog.contains("\"visible_when\":{\"path\":\"settings.low_pass_enabled\""));
        assertTrue(catalog.contains("\"number_maximum\":2048"));
        assertTrue(catalog.contains("\"options_source\":\"alias_lists\""));
        assertTrue(catalog.contains("Skip encrypted traffic channels (performance)"));
        assertTrue(catalog.contains("raw encrypted recordings are unavailable"));
    }

    @Test
    void everyProfileRejectsAccessCodeSettingsItDoesNotImplement()
    {
        ChannelProtocolRegistry registry = new ChannelProtocolRegistry();
        for(DecoderType decoder: DecoderType.PRIMARY_DECODERS)
        {
            ChannelProtocolRegistry.Profile profile = registry.require(decoder);
            assertEquals(profile.defaults(), registry.validateSettings(profile, Map.of()));
            for(String unsupported: new String[]{"nac", "ran", "color_code"})
                assertThrows(IllegalArgumentException.class,
                    () -> registry.validateSettings(profile, Map.of(unsupported, 1)), profile.id());
        }
    }

    @Test
    void conventionalDigitalSettingsDiscardMalformedHiddenValuesAndValidateControllers()
    {
        ChannelProtocolRegistry registry = new ChannelProtocolRegistry();
        for(String protocol: new String[]{"dmr", "nxdn"})
        {
            ChannelProtocolRegistry.Profile profile = registry.require(protocol);
            Map<String,Object> submitted = new HashMap<>();
            submitted.put("channel_mode", "CONVENTIONAL");
            submitted.put("traffic_channel_pool_size", "stale");
            submitted.put("ignore_data_calls", null);
            submitted.put("ignore_encrypted_calls", 9);
            if(protocol.equals("dmr")) submitted.put("use_compressed_talkgroups", true);
            Map<String,Object> effective = registry.validateSettings(profile, submitted);
            assertEquals(profile.defaults().get("traffic_channel_pool_size"), effective.get("traffic_channel_pool_size"));
            assertEquals(profile.defaults().get("ignore_data_calls"), effective.get("ignore_data_calls"));
            assertEquals(false, effective.get("ignore_encrypted_calls"));
            if(protocol.equals("dmr")) assertEquals(false, effective.get("use_compressed_talkgroups"));
            assertFalse(registry.isFieldApplicable(profile, "frequency_map", effective));
            submitted.put("channel_mode", "TRUNKED");
            assertTrue(registry.isFieldApplicable(profile, "frequency_map", submitted));
            assertThrows(IllegalArgumentException.class, () -> registry.validateSettings(profile, submitted));
            submitted.put("channel_mode", "INVALID");
            assertThrows(IllegalArgumentException.class, () -> registry.validateSettings(profile, submitted));
            submitted.put("channel_mode", "CONVENTIONAL");
            submitted.put("unknown_hidden_value", true);
            assertThrows(IllegalArgumentException.class, () -> registry.validateSettings(profile, submitted));
        }
    }

    @Test
    void disabledAnalogAndAutomaticScrambleFieldsDiscardInvalidStaleValues()
    {
        ChannelProtocolRegistry registry = new ChannelProtocolRegistry();
        for(String protocol: new String[]{"am", "nbfm"})
        {
            ChannelProtocolRegistry.Profile profile = registry.require(protocol);
            Map<String,Object> submitted = new HashMap<>();
            submitted.put("low_pass_enabled", false);
            submitted.put("low_pass_cutoff_hz", "stale");
            submitted.put("squelch_trim_enabled", false);
            submitted.put("squelch_tail_ms", -20);
            submitted.put("squelch_head_ms", null);
            Map<String,Object> effective = registry.validateSettings(profile, submitted);
            for(String key: new String[]{"low_pass_cutoff_hz", "squelch_tail_ms", "squelch_head_ms"})
                assertEquals(profile.defaults().get(key), effective.get(key));
            submitted.put("low_pass_enabled", true);
            assertThrows(IllegalArgumentException.class, () -> registry.validateSettings(profile, submitted));
            submitted.put("low_pass_enabled", "INVALID");
            assertThrows(IllegalArgumentException.class, () -> registry.validateSettings(profile, submitted));
        }
        ChannelProtocolRegistry.Profile p25 = registry.require("p25-phase2");
        Map<String,Object> submitted = new HashMap<>();
        submitted.put("auto_detect_scramble_parameters", true);
        submitted.put("scramble_wacn", "stale");
        submitted.put("scramble_system", -1);
        submitted.put("scramble_nac", null);
        Map<String,Object> effective = registry.validateSettings(p25, submitted);
        for(String key: new String[]{"scramble_wacn", "scramble_system", "scramble_nac"})
            assertEquals(p25.defaults().get(key), effective.get(key));
        submitted.put("auto_detect_scramble_parameters", false);
        assertThrows(IllegalArgumentException.class, () -> registry.validateSettings(p25, submitted));
        submitted.put("auto_detect_scramble_parameters", "INVALID");
        assertThrows(IllegalArgumentException.class, () -> registry.validateSettings(p25, submitted));
    }

    @Test
    void inactiveDependenciesRetainValidTuningAndOmittedSavedValues()
    {
        ChannelProtocolRegistry registry = new ChannelProtocolRegistry();
        for(String protocol: new String[]{"am", "nbfm"})
        {
            ChannelProtocolRegistry.Profile profile = registry.require(protocol);
            Map<String,Object> dormant = Map.of("low_pass_cutoff_hz", 2800, "squelch_tail_ms", 80,
                "squelch_head_ms", 25);
            Map<String,Object> retained = registry.validateSettings(profile, dormant);
            dormant.forEach((key, value) -> assertEquals(value, retained.get(key)));
            Map<String,Object> restored = registry.validateSettings(profile, Map.of(), dormant);
            dormant.forEach((key, value) -> assertEquals(value, restored.get(key)));
        }
        ChannelProtocolRegistry.Profile dmr = registry.require("dmr");
        assertEquals(false, registry.validateSettings(dmr, Map.of("channel_mode", "CONVENTIONAL"),
            Map.of("use_compressed_talkgroups", true)).get("use_compressed_talkgroups"));
    }
}
