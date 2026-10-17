/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.audio.tone;

import io.github.dsheirer.identifier.tone.AmbeTone;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PagingToneFrequencyMappingTest
{
    @Test void catalogKeepsBothPagingTablesAndEveryExistingAmbeIdentity()
    {
        var nominals = PagingToneFrequencyMapping.nominals();
        assertEquals(256,nominals.size());
        assertEquals(nominals.size(),nominals.stream().map(PagingToneFrequencyMapping.Match::frequencyMilliHertz).distinct().count());
        assertThrows(UnsupportedOperationException.class,nominals::clear);
        for(AmbeTone tone: AmbeTone.values())
        {
            if(tone.name().startsWith("HZ_"))
            {
                int frequency = new BigDecimal(tone.getValue()).movePointRight(3).intValueExact();
                var match = PagingToneFrequencyMapping.nearest(frequency).orElseThrow();
                assertEquals(frequency,match.frequencyMilliHertz());
                assertEquals(tone.name(),match.code());
            }
        }
        for(int frequency: new int[]{288500,378600,1217800,1881000,2468200,282200,382300,1232000,2575000,3487000})
            assertEquals(frequency,PagingToneFrequencyMapping.nearest(frequency).orElseThrow().frequencyMilliHertz());
        assertEquals("HZ_382_30",PagingToneFrequencyMapping.nearest(382300).orElseThrow().code());
    }

    @Test void measuredPagingPitchesUseTheUnionWithStableLowerMidpointTies()
    {
        int[][] matches = {{381934,382300},{1231878,1232000},{510600,510500},{330600,330500},
            {615700,615800},{321550,321400},{321551,321700}};
        for(int[] pair: matches)
            assertEquals(pair[1],PagingToneFrequencyMapping.nearest(pair[0]).orElseThrow().frequencyMilliHertz());
        for(int frequency: new int[]{Integer.MIN_VALUE,0,156249,3812501,Integer.MAX_VALUE})
            assertTrue(PagingToneFrequencyMapping.nearest(frequency).isEmpty());
    }

    @Test void pagingSinglesAreAcceptedOnlyByGenericAnalogMethod()
    {
        for(int frequency: new int[]{378600,382300,1217800,1232000,3487000})
        {
            var element = new CompletedToneSequence.Element(List.of(frequency),null,100L,500,.99);
            assertDoesNotThrow(() -> CompletedToneSequence.requireAnalogPcmTone(element));
            assertThrows(IllegalArgumentException.class,() -> CompletedToneSequence.requireNominalPcmTone(element));
            var summary = ToneSummaryPayload.summary(1,4,1,1000,1000L,2000,List.of(element));
            assertEquals("analog_pcm_tone_v1",summary.get("method"));
            assertThrows(IllegalArgumentException.class,() -> ToneSummaryPayload.summary(1,3,1,1000,1000L,2000,List.of(element)));
        }
        for(int frequency: new int[]{250000,254100,440000})
            assertThrows(IllegalArgumentException.class,() -> CompletedToneSequence.requireAnalogPcmTone(
                new CompletedToneSequence.Element(List.of(frequency),null,100L,500,.99)));
    }
}
