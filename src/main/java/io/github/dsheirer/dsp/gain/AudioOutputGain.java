/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.dsp.gain;

import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.codec.mbe.JmbeLibraryLoader;
import io.github.dsheirer.audio.codec.mbe.JmbeVocoderAudio;
import io.github.dsheirer.audio.tone.CompletedToneSequence;
import java.nio.file.Path;
import java.util.List;

/** Worker adapter to JMBE. VCE owns no vocoder gain algorithm or model weights. */
public final class AudioOutputGain
{
    private AudioOutputGain() {}

    public static CompletedAudioCall forEncoding(CompletedAudioCall call, boolean continuousAgc, Path library)
    {
        return prepare(call, continuousAgc, library).call();
    }

    public static CompletedAudioCall forEncoding(CompletedAudioCall call, JmbeVocoderAudio processor)
    {
        return prepare(call, processor).call();
    }

    /** Captures the actual outcome so encoders can suppress normalization only when automatic gain succeeded. */
    public static Prepared prepare(CompletedAudioCall call, boolean continuousAgc, Path library)
    {
        if(!continuousAgc) return fallback(call);
        return prepare(call, JmbeLibraryLoader.getInstance().getVocoderAudioProcessor(library));
    }

    public static Prepared prepare(CompletedAudioCall call, JmbeVocoderAudio processor)
    {
        if(processor == null) return fallback(call);
        try { return processed(call, processor); }
        catch(RuntimeException | LinkageError unavailable) { return fallback(call); }
    }

    private static Prepared processed(CompletedAudioCall call, JmbeVocoderAudio processor)
    {
        float[] input = flatten(call.audioBuffers());
        long[][] tones = toneIntervals(call.completedToneSequence());
        float[] output = processor.process(input, "NONE", false, true, tones[0], tones[1]);
        CompletedAudioCall prepared = new CompletedAudioCall(call.logicalCallId(), call.snapshot(), List.of(output), call.resolvedPolicy(),
            call.callLegSummaries(), call.recordingIdentifierCollection(), call.nativeAudioSnapshot(),
            call.completedToneSequence());
        return new Prepared(prepared, true);
    }

    private static Prepared fallback(CompletedAudioCall call) { return new Prepared(P25AudioGain.forEncoding(call), false); }

    public record Prepared(CompletedAudioCall call, boolean agcApplied) {}

    public static float[] flatten(List<float[]> buffers)
    {
        int length = 0;
        for(float[] buffer : buffers) length = Math.addExact(length, buffer.length);
        float[] result = new float[length];
        int offset = 0;
        for(float[] buffer : buffers)
        {
            System.arraycopy(buffer, 0, result, offset, buffer.length);
            offset += buffer.length;
        }
        return result;
    }

    public static long[][] toneIntervals(CompletedToneSequence sequence)
    {
        if(sequence == null || sequence.tones().isEmpty()) return new long[][]{new long[0],new long[0]};
        long[] offsets = new long[sequence.tones().size()], durations = new long[offsets.length];
        for(int i = 0; i < offsets.length; i++)
        {
            var tone = sequence.tones().get(i);
            offsets[i] = tone.offsetMilliseconds() != null ? tone.offsetMilliseconds() : -1;
            durations[i] = tone.durationMilliseconds();
        }
        return new long[][]{offsets, durations};
    }
}
