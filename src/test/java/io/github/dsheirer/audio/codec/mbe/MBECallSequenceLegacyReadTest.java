/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.audio.codec.mbe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class MBECallSequenceLegacyReadTest
{
    /**
     * Synthetic values in the exact version-2 shape verified against older local P25 recordings: no codec, tag,
     * or encryption_fid, with encryption context on the first frame only. No recording contents are included.
     */
    @Test
    void readsOlderCallThroughSharedVoiceFrame() throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        MBECallSequence sequence;
        try(InputStream input = getClass().getResourceAsStream("legacy-p25-version-2.mbe"))
        {
            assertNotNull(input);
            sequence = mapper.readValue(input, MBECallSequence.class);
        }

        assertEquals("APCO25-PHASE1", sequence.getProtocol());
        assertEquals(2, sequence.getVersion());
        assertEquals("GROUP", sequence.getCallType());
        assertEquals("1001", sequence.getFromIdentifier());
        assertEquals("2001", sequence.getToIdentifier());
        assertTrue(sequence.isEncrypted());
        assertTrue(sequence.hasAudio());
        assertNull(sequence.getCodec());
        assertEquals(5, sequence.getVoiceFrames().size());

        VoiceFrame context = assertInstanceOf(VoiceFrame.class, sequence.getVoiceFrames().getFirst());
        assertEquals(132, context.getAlgorithm());
        assertEquals(1, context.getKeyId());
        assertEquals("000000000000000000", context.getMessageIndicator());
        assertNull(context.getFeatureIdentifier());
        assertNull(context.getTag());
        assertEquals(1000L, context.getTimestamp());
        assertArrayEquals(HexFormat.of().parseHex("00112233445566778899AABBCCDDEEFF0011"),
            context.getFrameBytes());

        VoiceFrame next = sequence.getVoiceFrames().get(1);
        assertEquals(1020L, next.getTimestamp());
        assertEquals("112233445566778899AABBCCDDEEFF001122", next.getFrame());
        assertNull(next.getAlgorithm());
        assertNull(next.getKeyId());
        assertNull(next.getMessageIndicator());
        assertFalse(mapper.readTree(mapper.writeValueAsBytes(sequence)).get("frames").get(1)
            .has("encryption_algorithm"));
    }
}
