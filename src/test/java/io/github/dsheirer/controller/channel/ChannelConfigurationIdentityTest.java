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

package io.github.dsheirer.controller.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChannelConfigurationIdentityTest
{
    private final ObjectMapper mObjectMapper = new ObjectMapper();

    @Test
    void persistsConfigurationIdentityInChannelJson() throws Exception
    {
        Channel channel = new Channel("Control");
        String configurationId = channel.getConfigurationId();
        assertTrue(channel.isConfigurationIdPersistenceRequired());

        String json = mObjectMapper.writeValueAsString(channel);
        JsonNode tree = mObjectMapper.readTree(json);
        Channel restored = mObjectMapper.readValue(json, Channel.class);

        assertEquals(configurationId, tree.path("configurationId").asText());
        assertEquals(configurationId, restored.getConfigurationId());
        assertFalse(restored.isConfigurationIdPersistenceRequired());
    }

    @Test
    void replacesMissingBlankAndMalformedPersistedIdentities() throws Exception
    {
        Channel missing = mObjectMapper.readValue("{}", Channel.class);
        Channel blank = mObjectMapper.readValue("{\"configurationId\":\"\"}", Channel.class);
        Channel malformed = mObjectMapper.readValue("{\"configurationId\":\"not-a-uuid\"}", Channel.class);

        assertValid(missing.getConfigurationId());
        assertValid(blank.getConfigurationId());
        assertValid(malformed.getConfigurationId());
        assertTrue(missing.isConfigurationIdPersistenceRequired());
        assertTrue(blank.isConfigurationIdPersistenceRequired());
        assertTrue(malformed.isConfigurationIdPersistenceRequired());
    }

    @Test
    void normalizesPersistedIdentityAndGivesCloneANewIdentity()
    {
        Channel channel = new Channel("Control");
        String uppercase = "11111111-2222-3333-AAAA-BBBBBBBBBBBB";
        channel.setConfigurationId("  " + uppercase + "  ");

        Channel clone = channel.copyOf();

        assertEquals(uppercase.toLowerCase(), channel.getConfigurationId());
        assertFalse(channel.isConfigurationIdPersistenceRequired());
        assertNotEquals(channel.getConfigurationId(), clone.getConfigurationId());
        assertTrue(clone.isConfigurationIdPersistenceRequired());
    }

    private static void assertValid(String value)
    {
        assertEquals(value, UUID.fromString(value).toString());
    }
}
