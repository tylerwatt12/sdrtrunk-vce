/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.audio.enhance;

import io.github.dsheirer.protocol.Protocol;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AudioEnhancementModelBundleTest
{
    @Test void validatesAndPreservesSilenceAndCallLength() throws Exception
    {
        AudioEnhancementModelBundle bundle = AudioEnhancementModelBundle.load(ModelBundleFixtures.bundle("fixture-v1"));
        assertEquals("fixture-v1", bundle.modelId());
        assertTrue(bundle.supports(Protocol.APCO25));
        assertTrue(bundle.supports(Protocol.APCO25_PHASE2));
        assertFalse(bundle.supports(Protocol.DMR));
        assertEquals(Codec.IMBE, AudioEnhancementModelBundle.codecFor(Protocol.APCO25));
        assertThrows(UnsupportedOperationException.class, () -> bundle.metadata().codecs().clear());
        for(int size: new int[]{1,127,128,129,513})
        {
            float[] source = new float[size];
            float[] result = bundle.enhance(source, Codec.IMBE);
            assertEquals(size * 2, result.length);
            for(float sample: result) assertEquals(0, sample);
            assertArrayEquals(new float[size], source);
        }
    }

    @Test void rejectsUntrustedLayoutsAndWeights() throws Exception
    {
        assertThrows(IOException.class, () -> AudioEnhancementModelBundle.load(ModelBundleFixtures.bundle("model",2,false,false)));
        assertThrows(IOException.class, () -> AudioEnhancementModelBundle.load(ModelBundleFixtures.bundle("model",1,true,false)));
        assertThrows(IOException.class, () -> AudioEnhancementModelBundle.load(ModelBundleFixtures.bundle("model",1,false,true)));
        assertThrows(IOException.class, () -> AudioEnhancementModelBundle.load(new byte[AudioEnhancementModelBundle.MAX_BUNDLE_BYTES + 1]));
        // Repackage changed payload with a valid ZIP CRC, while retaining its old tensor digest.
        ByteArrayOutputStream changed = new ByteArrayOutputStream();
        try(ZipInputStream source = new ZipInputStream(new ByteArrayInputStream(ModelBundleFixtures.bundle("model")));
            ZipOutputStream destination = new ZipOutputStream(changed))
        {
            ZipEntry next;
            while((next = source.getNextEntry()) != null)
            {
                byte[] payload = source.readAllBytes();
                if(next.getName().equals("weights/imbe.f32le")) payload[0] ^= 1;
                destination.putNextEntry(new ZipEntry(next.getName()));
                destination.write(payload);
                destination.closeEntry();
            }
        }
        assertThrows(IOException.class, () -> AudioEnhancementModelBundle.load(changed.toByteArray()));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try(ZipOutputStream zip = new ZipOutputStream(bytes))
        {
            zip.putNextEntry(new ZipEntry("../manifest.json"));
            zip.write(new byte[]{1});
            zip.closeEntry();
        }
        assertThrows(IOException.class, () -> AudioEnhancementModelBundle.load(bytes.toByteArray()));
    }

    @Test void independentCallsAndConcurrentCallsHaveIdenticalResults() throws Exception
    {
        AudioEnhancementModelBundle bundle = AudioEnhancementModelBundle.load(ModelBundleFixtures.bundle("fixture"));
        float[] audio = new float[1600];
        for(int i = 0; i < audio.length; i++) audio[i] = (float)(Math.sin(i * .3) * .2);
        float[] expected = bundle.enhance(audio, Codec.AMBE_PLUS_2);
        assertArrayEquals(expected, bundle.enhance(audio, Codec.AMBE_PLUS_2));
        try(var executor = Executors.newFixedThreadPool(2))
        {
            var a = executor.submit(() -> bundle.enhance(audio, Codec.AMBE_PLUS_2));
            var b = executor.submit(() -> bundle.enhance(audio, Codec.AMBE_PLUS_2));
            assertArrayEquals(expected, a.get());
            assertArrayEquals(expected, b.get());
        }
        for(float value: expected) assertTrue(Float.isFinite(value));
        assertThrows(IllegalArgumentException.class, () -> bundle.enhance(new float[0],Codec.IMBE));
        assertThrows(IllegalArgumentException.class, () -> bundle.enhance(new float[]{Float.NaN},Codec.IMBE));
        assertThrows(IllegalArgumentException.class, () -> bundle.enhance(new float[]{0},null));
        assertThrows(IllegalArgumentException.class, () -> bundle.enhance(new float[AudioEnhancementModelBundle.MAX_INPUT_SAMPLES + 1],Codec.IMBE));
    }

    @Test void identityPostfilterAndNearSilenceRemainLocal() throws Exception
    {
        // All-zero network weights are an independent spectral identity control.
        var data = SyntheticModel.model();
        float[] input = new float[511];
        for(int i = 200; i < 300; i++) input[i] = (float)(.1 * Math.sin(i));
        float[] actual = WholeCallEnhancer.enhance8k(input, data);
        for(int i = 0; i < input.length; i++) assertEquals(input[i], actual[i], 2e-7);
        float[] quiet = new float[513];
        Arrays.fill(quiet, 1e-8f);
        assertArrayEquals(quiet, WholeCallEnhancer.enhance8k(quiet, data));
    }

    /** Reuse loader-validated public fixture tensors without exposing model internals in the API. */
    private static class SyntheticModel
    {
        static WholeCallEnhancer.Model model() throws Exception
        {
            var field = AudioEnhancementModelBundle.class.getDeclaredField("mModels");
            field.setAccessible(true);
            var bundle = AudioEnhancementModelBundle.load(ModelBundleFixtures.bundle("identity"));
            @SuppressWarnings("unchecked") var models = (java.util.Map<Codec,WholeCallEnhancer.Model>)field.get(bundle);
            return models.get(Codec.IMBE);
        }
    }
}
