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

    @Test void allDigitalProtocolsUseOneCallWideGainWithoutFlatteningOrMutatingNativeSamples()
    {
        for(DecoderType decoder : List.of(DecoderType.P25_PHASE1, DecoderType.P25_CONVENTIONAL,
            DecoderType.P25_PHASE2, DecoderType.DMR, DecoderType.NXDN))
        {
            List<float[]> quiet = List.of(new float[]{.01f, -.02f}, new float[]{.03f, -.1f});
            CompletedAudioCall quietCall = call(decoder, quiet);
            assertTrue(JmbeAudioOutput.isDigital(quietCall), decoder.toString());
            var quietOutput = JmbeAudioOutput.applyGain(quietCall, JmbeAudioOutput.nativeAudio(quietCall));
            assertArrayEquals(new float[]{.05f, -.1f}, quietOutput.buffers().get(0), .000001f, decoder.toString());
            assertArrayEquals(new float[]{.15f, -.5f}, quietOutput.buffers().get(1), .000001f, decoder.toString());
            assertNotSame(quiet.getFirst(), quietOutput.buffers().getFirst());
            assertArrayEquals(new float[]{.01f, -.02f}, quiet.get(0));
            assertArrayEquals(new float[]{.03f, -.1f}, quiet.get(1));
            var independentOutput = JmbeAudioOutput.applyGain(quietCall, JmbeAudioOutput.nativeAudio(quietCall));
            assertArrayEquals(quietOutput.buffers().getFirst(), independentOutput.buffers().getFirst(),
                "Independent outputs must not compound gain");

            List<float[]> loud = List.of(new float[]{.1f, -.2f}, new float[]{.4f, -.8f});
            CompletedAudioCall loudCall = call(decoder, loud);
            var loudOutput = JmbeAudioOutput.applyGain(loudCall, JmbeAudioOutput.nativeAudio(loudCall));
            assertArrayEquals(new float[]{.11875f, -.2375f}, loudOutput.buffers().get(0), .000001f,
                "Every buffer must use the peak of the whole call: " + decoder);
            assertArrayEquals(new float[]{.475f, -.95f}, loudOutput.buffers().get(1), .000001f,
                "Loud peaks must preserve their relative levels: " + decoder);
            assertTrue(Math.abs(loudOutput.buffers().get(1)[1]) <= .95f);
            assertArrayEquals(new float[]{.1f, -.2f}, loud.get(0));
            assertArrayEquals(new float[]{.4f, -.8f}, loud.get(1));
            assertEquals(8000, loudOutput.sampleRate());
        }
    }

    @Test void analogAndUnidentifiedAudioRetainTheirExistingLevels()
    {
        for(DecoderType decoder : new DecoderType[]{DecoderType.AM, DecoderType.NBFM, null})
        {
            float[] samples = {.01f, -.2f, .4f};
            CompletedAudioCall call = call(decoder, List.of(samples));
            assertFalse(JmbeAudioOutput.isDigital(call));
            var output = JmbeAudioOutput.applyGain(call, JmbeAudioOutput.nativeAudio(call));
            assertSame(samples, output.buffers().getFirst());
            assertArrayEquals(new float[]{.01f, -.2f, .4f}, samples);
        }
    }

    @Test void suppliedFormatsAndProviderFallbackReceiveTheSameDigitalGain()
    {
        float[] samples = {.01f, -.02f, .03f, -.1f};
        CompletedAudioCall call = call(DecoderType.NXDN, List.of(samples));
        var supplied = JmbeAudioOutput.renderWithProcessor(call, new JmbeAudioOutputProcessor(new Resampled()));
        float[] suppliedBefore = supplied.buffers().getFirst().clone();
        var output = JmbeAudioOutput.applyGain(call, supplied);
        assertEquals(16000, output.sampleRate());
        assertArrayEquals(new float[]{.05f, .05f, -.1f, -.1f, .15f, .15f, -.5f, -.5f},
            output.buffers().getFirst(), .000001f);
        assertArrayEquals(suppliedBefore, supplied.buffers().getFirst());
        var fallback = JmbeAudioOutput.renderWithProcessor(call,
            new JmbeAudioOutputProcessor(new Invalid("throws")));
        var fallbackOutput = JmbeAudioOutput.applyGain(call, fallback);
        assertEquals(8000, fallbackOutput.sampleRate());
        assertArrayEquals(new float[]{.05f, -.1f, .15f, -.5f}, fallbackOutput.buffers().getFirst(), .000001f);
        assertArrayEquals(new float[]{.01f, -.02f, .03f, -.1f}, samples);
    }

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
    public static class Resampled
    {
        public Map<String,Object> process(float[] input, int rate, String codec)
        {
            assertEquals("AMBE_PLUS_2", codec);
            float[] output = new float[input.length * 2];
            for(int i = 0; i < output.length; i++) output[i] = input[i / 2];
            return Map.of("samples", output, "sample_rate", 16000);
        }
    }
    private static long count(JmbeAudioOutput.RenderedAudio audio)
    {
        return audio.buffers().stream().mapToLong(buffer -> buffer.length).sum();
    }
    static CompletedAudioCall call()
    {
        float[] samples = new float[8000];
        for(int i=0;i<samples.length;i++) samples[i] = (float)(.1 * Math.sin(2*Math.PI*700*i/8000));
        return call(DecoderType.P25_PHASE1, List.of(samples));
    }

    private static CompletedAudioCall call(DecoderType decoder, List<float[]> samples)
    {
        var id = new AudioCallId(1L, 1L, 0);
        var source = new CallLegSource(decoder, null, null, null, 0, null, null, null, false);
        var snapshot = new AudioCallSnapshot(id, null, null, null, Set.of(), 1000, 2000,
            1, 1, 1000, 2000, false, true, CallEncryptionState.CLEAR, true, null,
            VoiceCallQuality.EMPTY, CallLegId.from(id), source, null);
        return new CompletedAudioCall(snapshot, samples);
    }
}
