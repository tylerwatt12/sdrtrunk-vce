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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ChannelModelTest
{
    @Test
    public void standardChannelGetsRadioResolveIdWhenAdded()
    {
        Channel channel = new Channel("Test");

        assertFalse(channel.hasRadioResolveId());

        ChannelModel model = new ChannelModel();
        model.addChannel(channel);

        assertTrue(channel.hasRadioResolveId());
    }

    @Test
    public void copiedChannelGetsNewRadioResolveIdWhenAdded()
    {
        Channel original = new Channel("Test");
        ChannelModel model = new ChannelModel();
        model.addChannel(original);

        Channel copy = original.copyOf();

        assertFalse(copy.hasRadioResolveId());

        model.addChannel(copy);

        assertTrue(copy.hasRadioResolveId());
        assertNotEquals(original.getRadioResolveId(), copy.getRadioResolveId());
    }

    @Test
    public void currentJsonUsesRadioResolveIdAndReadsTheLegacyName() throws Exception
    {
        String radioResolveId = "11111111-2222-4333-8444-555555555555";
        ObjectMapper objectMapper = new ObjectMapper();
        Channel channel = new Channel("Test");
        channel.setRadioResolveId(radioResolveId);

        JsonNode json = objectMapper.valueToTree(channel);
        assertEquals(radioResolveId, json.path("radioResolveId").textValue());
        assertFalse(json.has("radresGuid"));

        Channel legacy = objectMapper.readValue("{\"radresGuid\":\"" + radioResolveId + "\"}",
            Channel.class);
        assertEquals(radioResolveId, legacy.getRadioResolveId());
    }
}
