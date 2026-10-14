/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.audio.tone;

import io.github.dsheirer.audio.call.*;
import io.github.dsheirer.identifier.IdentifierCollection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToneSummaryPayloadMethodTest
{
    @Test void legacyMeasuredPcmIsPreservedWithoutDerivedFields()
    {
        var element = new CompletedToneSequence.Element(List.of(343_300), null, 125L, 800, .9234567);
        var summary = summary(1, 1, List.of(element)); var tone = tones(summary).getFirst();
        assertEquals("decoded_audio", summary.get("source")); assertEquals("p25_pcm_tone_v1", summary.get("method"));
        assertEquals(List.of(343.3), tone.get("frequencies_hz")); assertEquals(125L, tone.get("offset_milliseconds"));
        assertEquals(800L, tone.get("duration_milliseconds")); assertEquals(.923457, tone.get("confidence"));
        assertFalse(tone.containsKey("code")); noDerivedFields(summary);
        assertEquals(List.of(343_300), element.frequenciesMilliHertz());
        assertThrows(UnsupportedOperationException.class, () -> tone.put("frequency", 343.75));
        assertThrows(UnsupportedOperationException.class, () -> tones(summary).clear());
    }

    @Test void newPcmMethodReportsOnlyItsNominalFrequency()
    {
        var summary = summary(1, 3, List.of(new CompletedToneSequence.Element(List.of(343_750), null, 125L, 800, .9)));
        assertEquals("decoded_audio", summary.get("source")); assertEquals("p25_pcm_ambe_tone_v2", summary.get("method"));
        assertEquals(List.of(343.75), tones(summary).getFirst().get("frequencies_hz")); noDerivedFields(summary);
    }

    @Test void encodedSymbolsAndPairsRemainUnchanged()
    {
        var summary = summary(2, 2, List.of(new CompletedToneSequence.Element(List.of(697_000,1209_000), "DTMF_1", null, 80, null),
            new CompletedToneSequence.Element(List.of(), "UNKNOWN", null, 100, null)));
        assertEquals("encoded", summary.get("source")); assertEquals("ambe_tone_v1", summary.get("method"));
        assertEquals(List.of(697.,1209.), tones(summary).getFirst().get("frequencies_hz"));
        assertEquals("DTMF_1", tones(summary).getFirst().get("code"));
        assertEquals("UNKNOWN", tones(summary).getLast().get("code")); noDerivedFields(summary);
    }

    @Test void invalidSourceMethodPairsAreRefused()
    {
        for(int[] pair: new int[][]{{1,2},{2,1},{2,3},{1,4},{0,1},{3,3}})
            assertThrows(IllegalArgumentException.class, () -> summary(pair[0],pair[1],List.of()));
    }

    @Test void methodThreeCannotRelabelMeasuredPairUnknownOrOutOfRangeEvidence()
    {
        for(var element: List.of(new CompletedToneSequence.Element(List.of(343_300),null,null,80,null),
            new CompletedToneSequence.Element(List.of(697_000,1209_000),null,null,80,null),
            new CompletedToneSequence.Element(List.of(),"UNKNOWN",null,80,null),
            new CompletedToneSequence.Element(List.of(3_812_501),null,null,80,null)))
            assertThrows(IllegalArgumentException.class, () -> summary(1,3,List.of(element)));
    }

    @Test void completedDomainAcceptsBothPcmGenerationsAndOnlyMethodTwoForEncoded()
    {
        var raw = new CompletedToneSequence.Element(List.of(343_300),null,null,80,.9);
        var nominal = new CompletedToneSequence.Element(List.of(343_750),null,null,80,.9);
        var encoded = new CompletedToneSequence.Element(List.of(812_500),"HZ_812_50",null,80,null);
        assertDoesNotThrow(() -> sequence(CompletedToneSequence.Source.DECODED_AUDIO, CompletedToneSequence.Method.P25_PCM_TONE_V1,raw));
        assertDoesNotThrow(() -> sequence(CompletedToneSequence.Source.DECODED_AUDIO, CompletedToneSequence.Method.P25_PCM_AMBE_TONE_V2,nominal));
        assertDoesNotThrow(() -> sequence(CompletedToneSequence.Source.ENCODED, CompletedToneSequence.Method.AMBE_TONE_V1,encoded));
        assertThrows(IllegalArgumentException.class, () -> sequence(CompletedToneSequence.Source.DECODED_AUDIO, CompletedToneSequence.Method.P25_PCM_AMBE_TONE_V2,raw));
        assertThrows(IllegalArgumentException.class, () -> sequence(CompletedToneSequence.Source.ENCODED, CompletedToneSequence.Method.P25_PCM_AMBE_TONE_V2,encoded));
        assertThrows(IllegalArgumentException.class, () -> sequence(CompletedToneSequence.Source.DECODED_AUDIO, CompletedToneSequence.Method.AMBE_TONE_V1,nominal));
    }

    private static CompletedToneSequence sequence(CompletedToneSequence.Source source, CompletedToneSequence.Method method,
                                                   CompletedToneSequence.Element element)
    {
        var id = new AudioCallId(99,1,0);
        var snapshot = new AudioCallSnapshot(id,null,null,new IdentifierCollection(),Set.of(),1000,2000,1,1,1000,2000,
            false,true,CallEncryptionState.CLEAR,false,null,VoiceCallQuality.EMPTY,CallLegId.from(id),CallLegSource.UNKNOWN,null);
        return new CompletedToneSequence(snapshot,1000,source,method,List.of(element));
    }
    private static Map<String,Object> summary(int source, int method, List<CompletedToneSequence.Element> elements)
    { return ToneSummaryPayload.summary(source,method,source==1?1:2,1000,1000L,2000,elements); }
    private static void noDerivedFields(Map<String,Object> summary)
    {
        assertFalse(summary.containsKey("frequency_mapping"));
        for(var tone: tones(summary)) { assertFalse(tone.containsKey("matching_frequency_hz")); assertFalse(tone.containsKey("matching_code")); }
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> tones(Map<String,Object> summary)
    { return (List<Map<String,Object>>)summary.get("tones"); }
}
