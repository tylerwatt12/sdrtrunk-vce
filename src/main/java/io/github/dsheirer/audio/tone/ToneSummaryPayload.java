/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.audio.tone;

import io.github.dsheirer.module.decode.DecoderType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One explicit primitive wire contract shared by transient and persisted tone evidence. */
public final class ToneSummaryPayload
{
    private ToneSummaryPayload() {}
    public static Map<String,Object> summary(CompletedToneSequence sequence)
    {
        DecoderType decoder = sequence.callLegSource().decoderType();
        Integer phase = decoder == DecoderType.P25_PHASE1 || decoder == DecoderType.P25_CONVENTIONAL ? Integer.valueOf(1) :
            decoder == DecoderType.P25_PHASE2 ? Integer.valueOf(2) : null;
        return summary(sequence.source().code(), sequence.method().code(), phase, sequence.callStartTimestamp(),
            sequence.firstVoiceSampleTimestamp() > 0 ? sequence.firstVoiceSampleTimestamp() : null,
            sequence.completedTimestamp(), sequence.tones());
    }

    public static Map<String,Object> summary(int sourceCode, int methodCode, Integer phase,
        long callStartMs, Long firstVoiceSampleMs, long completedMs, List<CompletedToneSequence.Element> tones)
    {
        if(!(sourceCode == 1 && (methodCode == 1 || methodCode == 3) || sourceCode == 2 && methodCode == 2) ||
            phase != null && phase != 1 && phase != 2 ||
            tones.size() > CompletedToneSequence.MAXIMUM_TONES)
            throw new IllegalArgumentException("Invalid tone summary context");
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("source", sourceCode == 1 ? "decoded_audio" : "encoded");
        result.put("method", switch(methodCode)
        {
            case 1 -> "p25_pcm_tone_v1";
            case 2 -> "ambe_tone_v1";
            case 3 -> "p25_pcm_ambe_tone_v2";
            default -> throw new IllegalArgumentException("Unknown tone method");
        });
        if(phase != null) result.put("phase", phase);
        result.put("call_started_at_ms", callStartMs);
        if(firstVoiceSampleMs != null) result.put("first_voice_sample_at_ms", firstVoiceSampleMs);
        result.put("completed_at_ms", completedMs);
        List<Map<String,Object>> values = new ArrayList<>(tones.size());
        for(CompletedToneSequence.Element tone: tones)
        {
            if(methodCode == 3) CompletedToneSequence.requireNominalPcmTone(tone);
            Map<String,Object> value = new LinkedHashMap<>();
            value.put("frequencies_hz", tone.frequenciesMilliHertz().stream().map(frequency -> frequency / 1000.).toList());
            if(tone.codecSymbol() != null) value.put("code", tone.codecSymbol());
            if(tone.offsetMilliseconds() != null) value.put("offset_milliseconds", tone.offsetMilliseconds());
            value.put("duration_milliseconds", tone.durationMilliseconds());
            //Persisted integer precision is shared by both readouts; this remains
            //an uncalibrated evidence score, not a probability.
            if(tone.confidence() != null) value.put("confidence", Math.round(tone.confidence() * 1_000_000) / 1_000_000.);
            values.add(Collections.unmodifiableMap(value));
        }
        result.put("tones", List.copyOf(values));
        return Collections.unmodifiableMap(result);
    }
}
