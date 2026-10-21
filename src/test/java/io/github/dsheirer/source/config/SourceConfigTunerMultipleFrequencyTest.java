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

package io.github.dsheirer.source.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceConfigTunerMultipleFrequencyTest
{
    private static final long PRIMARY = 851_012_500L;
    private static final long ALTERNATE = 852_012_500L;

    @Test
    void publishesImmutableSnapshotsWithoutChangingAnEarlierReaderSnapshot()
    {
        SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
        source.setFrequencies(List.of(PRIMARY));
        List<Long> original = source.getFrequencies();
        assertSame(original, source.getFrequencies(), "reads do not copy the frequency list");
        assertThrows(UnsupportedOperationException.class, () -> original.add(ALTERNATE));
        source.addFrequency(ALTERNATE);
        assertEquals(List.of(PRIMARY), original);
        assertEquals(List.of(PRIMARY, ALTERNATE), source.getFrequencies());
        List<Long> two = source.getFrequencies();
        source.addFrequency(ALTERNATE);
        assertSame(two, source.getFrequencies(), "a duplicate observation does not allocate a new snapshot");
        source.setFrequencies(List.of(ALTERNATE));
        assertEquals(List.of(PRIMARY, ALTERNATE), two);
        assertEquals(List.of(ALTERNATE), source.getFrequencies());
    }

    @Test
    void fallsBackToFirstFrequencyWhenPreferredFrequencyIsRemoved()
    {
        SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
        source.setFrequencies(List.of(851_012_500L, 852_012_500L));
        source.setPreferredFrequency(852_012_500L);

        source.setFrequencies(List.of(851_012_500L));

        assertEquals(851_012_500L, source.getPreferredFrequency());
    }

    @Test
    void roundTripsPreferredFrequencyInSourceConfigurationJson() throws Exception
    {
        ObjectMapper objectMapper = new ObjectMapper();
        SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
        source.setFrequencies(List.of(PRIMARY, ALTERNATE));
        source.setPreferredFrequency(ALTERNATE);

        String json = objectMapper.writeValueAsString(source);
        SourceConfigTunerMultipleFrequency restored =
            objectMapper.readValue(json, SourceConfigTunerMultipleFrequency.class);

        assertEquals(ALTERNATE, restored.getPreferredFrequency());
    }

    @Test
    void restoresPreferredFrequencyRegardlessOfJsonPropertyOrder() throws Exception
    {
        ObjectMapper objectMapper = new ObjectMapper();
        SourceConfigTunerMultipleFrequency restored = objectMapper.readValue("""
            {
              "type": "sourceConfigTunerMultipleFrequency",
              "preferredFrequency": 852012500,
              "frequencies": [851012500, 852012500]
            }
            """, SourceConfigTunerMultipleFrequency.class);

        assertEquals(ALTERNATE, restored.getPreferredFrequency());
    }

    @Test
    void rejectsPersistedPreferredFrequencyOutsideConfiguredList() throws Exception
    {
        ObjectMapper objectMapper = new ObjectMapper();
        SourceConfigTunerMultipleFrequency restored = objectMapper.readValue("""
            {
              "type": "sourceConfigTunerMultipleFrequency",
              "frequencies": [851012500, 852012500],
              "preferredFrequency": 853012500
            }
            """, SourceConfigTunerMultipleFrequency.class);

        assertEquals(PRIMARY, restored.getPreferredFrequency());
        assertFalse(objectMapper.writeValueAsString(restored).contains("preferredFrequency"));
    }
}
