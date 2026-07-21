/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.audio.codec.mbe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.module.decode.nxdn.audio.NXDNCallSequenceRecorder;
import io.github.dsheirer.module.decode.nxdn.layer3.type.AudioCodec;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MBECallSequenceConverterTest
{
    @Test
    void rejectsUnsupportedProtocol()
    {
        MBECallSequence sequence = new MBECallSequence("UNKNOWN");
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> MBECallSequenceConverter.convert(sequence, Path.of("unused.wav")));
        assertEquals("Unsupported MBE protocol: UNKNOWN", exception.getMessage());
    }

    @Test
    void rejectsNxdnFullRateExplicitly()
    {
        MBECallSequence sequence = new MBECallSequence(NXDNCallSequenceRecorder.PROTOCOL);
        sequence.setCodec(AudioCodec.FULL_RATE.name());
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> MBECallSequenceConverter.convert(sequence, Path.of("unused.wav")));
        assertEquals("NXDN full-rate AMBE .mbe conversion is not supported", exception.getMessage());
    }

    @Test
    void roundTripsOptionalCodecMetadata() throws Exception
    {
        MBECallSequence sequence = new MBECallSequence(NXDNCallSequenceRecorder.PROTOCOL);
        sequence.setCodec(AudioCodec.HALF_RATE.name());
        ObjectMapper mapper = new ObjectMapper();

        MBECallSequence decoded = mapper.readValue(mapper.writeValueAsBytes(sequence), MBECallSequence.class);

        assertEquals(AudioCodec.HALF_RATE.name(), decoded.getCodec());
        assertEquals(2, decoded.getVersion());
    }

    @Test
    void mapsNxdnSacchTagsForKeystreamGapAlignment()
    {
        assertEquals(0, MBECallSequenceConverter.getNxdnSACCHIndex("SACCH 1"));
        assertEquals(2, MBECallSequenceConverter.getNxdnSACCHIndex("SACCH 3"));
        assertEquals(3, MBECallSequenceConverter.getNxdnSACCHIndex("SACCH 4"));
        assertNull(MBECallSequenceConverter.getNxdnSACCHIndex("VOICE"));
        assertNull(MBECallSequenceConverter.getNxdnSACCHIndex(null));
    }
}
