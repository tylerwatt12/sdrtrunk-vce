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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
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
        channel.setP25SiteIdentity(new P25SiteIdentity(0xBEE00, 0x123, 1, 2));

        Channel clone = channel.copyOf();

        assertEquals(uppercase.toLowerCase(), channel.getConfigurationId());
        assertFalse(channel.isConfigurationIdPersistenceRequired());
        assertNotEquals(channel.getConfigurationId(), clone.getConfigurationId());
        assertTrue(clone.isConfigurationIdPersistenceRequired());
        assertNull(clone.getP25SiteIdentity());
    }

    @Test
    void bindsFirstP25SiteIdentityImmutably()
    {
        Channel channel = new Channel("Control");
        P25SiteIdentity first = new P25SiteIdentity(0xBEE00, 0x123, 1, 2);
        P25SiteIdentity other = new P25SiteIdentity(0xA0001, 0x456, 3, 4);

        assertTrue(channel.bindP25SiteIdentity(first));
        assertTrue(channel.bindP25SiteIdentity(first));
        assertFalse(channel.bindP25SiteIdentity(other));
        assertEquals(first, channel.getP25SiteIdentity());
    }

    private static void assertValid(String value)
    {
        assertEquals(value, UUID.fromString(value).toString());
    }
}
