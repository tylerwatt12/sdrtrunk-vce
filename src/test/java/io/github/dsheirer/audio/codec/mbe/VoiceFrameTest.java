/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.audio.codec.mbe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

public class VoiceFrameTest
{
    private final ObjectMapper mMapper = new ObjectMapper();

    @Test
    void serializesOptionalEncryptionFeatureIdentifier() throws Exception
    {
        VoiceFrame frame = new VoiceFrame(1234L, "001122334455667788", 0x24, 0x10, 2, "AABBCCDD");

        JsonNode json = mMapper.readTree(mMapper.writeValueAsString(frame));

        assertEquals(0x24, json.get("encryption_algorithm").intValue());
        assertEquals(0x10, json.get("encryption_fid").intValue());
        assertEquals(2, json.get("encryption_key_id").intValue());
        assertEquals("AABBCCDD", json.get("encryption_mi").textValue());
    }

    @Test
    void omitsFeatureIdentifierWhenSignalingDoesNotProvideIt() throws Exception
    {
        VoiceFrame frame = new VoiceFrame(1234L, "001122334455667788", 0x24, 2, "AABBCCDD");

        JsonNode json = mMapper.readTree(mMapper.writeValueAsString(frame));

        assertFalse(json.has("encryption_fid"));
    }

    @Test
    void readsLegacyFrameWithoutFeatureIdentifier() throws Exception
    {
        String json = "{\"encryption_algorithm\":36,\"encryption_key_id\":2,\"encryption_mi\":\"AABBCCDD\"," +
            "\"time\":1234,\"hex\":\"001122334455667788\"}";

        VoiceFrame frame = mMapper.readValue(json, VoiceFrame.class);

        assertNull(frame.getFeatureIdentifier());
        assertEquals(0x24, frame.getAlgorithm());
        assertEquals(2, frame.getKeyId());
    }

    @Test
    void callSequenceCarriesFeatureIdentifierOnContextMarker()
    {
        MBECallSequence callSequence = new MBECallSequence("DMR");

        callSequence.addEncryptedVoiceFrame(1234L, "001122334455667788", 0x24, 0x10, 2, "AABBCCDD");

        VoiceFrame frame = callSequence.getVoiceFrames().getFirst();
        assertEquals(0x10, frame.getFeatureIdentifier());
        assertEquals(0x24, frame.getAlgorithm());
        assertEquals(2, frame.getKeyId());
        assertEquals("AABBCCDD", frame.getMessageIndicator());
    }
}
