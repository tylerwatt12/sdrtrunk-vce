/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.dmr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import java.util.List;
import org.junit.jupiter.api.Test;

class DecodeConfigDMRTest
{
    private final ObjectMapper mObjectMapper = new ObjectMapper();

    @Test
    void defaultsNewAndUnmappedLegacyConfigurationsToConventional()
    {
        DecodeConfigDMR configuration = new DecodeConfigDMR();

        assertEquals(DMRChannelMode.CONVENTIONAL, configuration.getChannelMode());
        assertTrue(configuration.isConventional());
        assertFalse(configuration.isTrunked());

        TimeslotFrequency incompleteMapping = new TimeslotFrequency();
        incompleteMapping.setNumber(12);
        configuration.setTimeslotMap(List.of(incompleteMapping));
        assertEquals(DMRChannelMode.CONVENTIONAL, configuration.getChannelMode());
    }

    @Test
    void infersMappedLegacyConfigurationAsTrunked() throws Exception
    {
        DecodeConfigDMR configuration = new DecodeConfigDMR();
        configuration.setTimeslotMap(List.of(mapping(12, 451_012_500L)));
        ObjectNode legacyJson = mObjectMapper.valueToTree(configuration);
        legacyJson.remove("channelMode");

        DecodeConfigDMR restored = mObjectMapper.treeToValue(legacyJson, DecodeConfigDMR.class);

        assertEquals(DMRChannelMode.TRUNKED, restored.getChannelMode());
        assertTrue(restored.isTrunked());
    }

    @Test
    void explicitModeOverridesLegacyInferenceAndRoundTrips() throws Exception
    {
        DecodeConfigDMR configuration = new DecodeConfigDMR();
        configuration.setTimeslotMap(List.of(mapping(12, 451_012_500L)));
        configuration.setChannelMode(DMRChannelMode.CONVENTIONAL);

        String json = mObjectMapper.writeValueAsString(configuration);
        DecodeConfigDMR restored = mObjectMapper.readValue(json, DecodeConfigDMR.class);

        assertTrue(json.contains("\"channelMode\":\"CONVENTIONAL\""));
        assertEquals(DMRChannelMode.CONVENTIONAL, restored.getChannelMode());
        assertTrue(restored.isConventional());
        assertEquals(1, restored.getTimeslotMap().size());
    }

    private static TimeslotFrequency mapping(int number, long frequency)
    {
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(number);
        mapping.setDownlinkFrequency(frequency);
        return mapping;
    }
}
