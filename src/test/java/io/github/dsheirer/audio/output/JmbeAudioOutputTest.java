/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.audio.output;

import io.github.dsheirer.audio.call.*;
import io.github.dsheirer.audio.codec.mbe.JmbeAudioOutputProcessor;
import io.github.dsheirer.audio.codec.mbe.JmbeOutputLibraryFixture;
import io.github.dsheirer.audio.codec.mbe.JmbeLibraryLoader;
import io.github.dsheirer.jmbe.JmbeLibraryFixture;
import io.github.dsheirer.module.decode.DecoderType;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JmbeAudioOutputTest
{
    @TempDir Path directory;

    @Test void absentCapabilityPreservesDefaultAndSuppliedFormatUsesNoVersionRecognition() throws Exception
    {
        CompletedAudioCall call = call();
        try
        {
            Path standard = JmbeLibraryFixture.create(directory.resolve("standard"), "1.0.17");
            var original = JmbeAudioOutput.render(call, standard);
            assertEquals(8000, original.sampleRate());
            assertSame(call.audioBuffers().getFirst(), original.buffers().getFirst());
            Path alternate = JmbeOutputLibraryFixture.create(directory.resolve("alternate"));
            var supplied = JmbeAudioOutput.render(call, alternate);
            assertEquals(16000, supplied.sampleRate());
            assertEquals(16000, count(supplied));
            assertEquals(1000, call.getDuration());
            assertEquals(8000, call.audioBuffers().getFirst().length);
            assertEquals(call.audioBuffers().getFirst()[17], supplied.buffers().getFirst()[34]);
            assertEquals(8000, JmbeAudioOutput.render(call, standard).sampleRate(), "A changed library resets capability state");
            assertEquals(8000, JmbeAudioOutput.render(call, directory.resolve("missing.jar")).sampleRate(),
                "A failed replacement must not reuse a previous provider");
        }
        finally { JmbeLibraryLoader.getInstance().close(); }
    }

    @Test void validatesActualDurationFormatAndFinitenessAndProtectsNativePcmFromFailedProvider()
    {
        CompletedAudioCall call = call();
        float[] before = call.audioBuffers().getFirst().clone();
        for(String failure : List.of("throws", "nan", "duration", "rate", "fractional-rate"))
        {
            var output = JmbeAudioOutput.renderWithProcessor(call, new JmbeAudioOutputProcessor(new Invalid(failure)));
            assertEquals(8000, output.sampleRate(), failure);
            assertSame(call.audioBuffers().getFirst(), output.buffers().getFirst());
            assertArrayEquals(before, call.audioBuffers().getFirst());
        }
        var valid = JmbeAudioOutput.renderWithProcessor(call, new JmbeAudioOutputProcessor(new OtherRate()));
        assertEquals(32000, valid.sampleRate(), "Any valid supplied rate is accepted without a model or build check");
        assertEquals(32000, count(valid));
        assertArrayEquals(before, call.audioBuffers().getFirst());
    }

    @Test void inputBoundBypassesProviderWithoutLargeInferenceAllocation()
    {
        CompletedAudioCall template = call();
        CompletedAudioCall oversized = new CompletedAudioCall(template.snapshot(),
            List.of(new float[JmbeAudioOutputProcessor.MAXIMUM_SAMPLES + 1]));
        AtomicInteger calls = new AtomicInteger();
        var result = JmbeAudioOutput.renderWithProcessor(oversized, new JmbeAudioOutputProcessor(new Counting(calls)));
        assertEquals(8000, result.sampleRate());
        assertEquals(0, calls.get());
    }

    public static class Invalid
    {
        private final String failure;
        Invalid(String failure) { this.failure = failure; }
        public Map<String,Object> process(float[] input, int rate, String codec)
        {
            Arrays.fill(input, .75f);
            if(failure.equals("throws")) throw new IllegalStateException("optional output failed");
            float[] output = new float[input.length * 2];
            if(failure.equals("nan")) output[1] = Float.NaN;
            if(failure.equals("duration")) output = new float[output.length - 1];
            return Map.of("samples", output, "sample_rate", failure.equals("rate") ? -1 :
                failure.equals("fractional-rate") ? (Object)16000.5 : (Object)16000);
        }
    }
    public static class OtherRate
    {
        public Map<String,Object> process(float[] input, int rate, String codec)
        {
            assertEquals("IMBE", codec);
            return Map.of("samples", new float[input.length * 4], "sample_rate", 32000);
        }
    }
    public static class Counting
    {
        private final AtomicInteger calls;
        Counting(AtomicInteger calls) { this.calls = calls; }
        public Map<String,Object> process(float[] input, int rate, String codec) { calls.incrementAndGet(); return Map.of(); }
    }
    private static long count(JmbeAudioOutput.RenderedAudio audio)
    {
        return audio.buffers().stream().mapToLong(buffer -> buffer.length).sum();
    }
    static CompletedAudioCall call()
    {
        var id = new AudioCallId(1L, 1L, 0);
        var source = new CallLegSource(DecoderType.P25_PHASE1, null, null, null, 0, null, null, null, false);
        var snapshot = new AudioCallSnapshot(id, null, null, null, Set.of(), 1000, 2000,
            1, 1, 1000, 2000, false, true, CallEncryptionState.CLEAR, true, null,
            VoiceCallQuality.EMPTY, CallLegId.from(id), source, null);
        float[] samples = new float[8000];
        for(int i=0;i<samples.length;i++) samples[i] = (float)(.1 * Math.sin(2*Math.PI*700*i/8000));
        return new CompletedAudioCall(snapshot, List.of(samples));
    }
}
