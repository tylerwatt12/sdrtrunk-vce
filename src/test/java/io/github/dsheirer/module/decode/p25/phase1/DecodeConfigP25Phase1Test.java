/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DecodeConfigP25Phase1Test
{
    private final ObjectMapper mObjectMapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void loadsRetiredAutoConfigurationAsFixedC4fm() throws Exception
    {
        DecodeConfigP25Phase1 configuration = mObjectMapper.readValue(
            "{\"type\":\"decodeConfigP25Phase1\",\"modulation\":\"AUTO\"," +
                "\"autoPreferredModulation\":\"CQPSK\"}",
            DecodeConfigP25Phase1.class);

        assertEquals(Modulation.C4FM, configuration.getModulation());
        JsonNode serialized = mObjectMapper.valueToTree(configuration);
        assertEquals("C4FM", serialized.path("modulation").textValue());
        assertFalse(serialized.has("autoPreferredModulation"));
    }

    @Test
    void retainsExplicitLsmConfiguration() throws Exception
    {
        DecodeConfigP25Phase1 configuration = mObjectMapper.readValue(
            "{\"type\":\"decodeConfigP25Phase1\",\"modulation\":\"CQPSK\"}", DecodeConfigP25Phase1.class);

        assertEquals(Modulation.CQPSK, configuration.getModulation());
    }
}
