/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.audio.enhance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Synthetic finite models for loader/service tests; contains no trained weights or private audio. */
public final class ModelBundleFixtures
{
    private ModelBundleFixtures() {}

    public static byte[] bundle(String id) throws IOException
    {
        return bundle(id, 1, false, false);
    }

    static byte[] bundle(String id, int schema, boolean invalidShape, boolean nonFinite) throws IOException
    {
        Map<String, int[]> shapes = AudioEnhancementModelBundle.tensorShapes();
        Map<String, Object> table = new LinkedHashMap<>();
        int count = 0;
        for(int[] shape: shapes.values())
        {
            int size = 1;
            for(int dimension: shape) size *= dimension;
            count += size;
        }
        ByteBuffer buffer = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN);
        for(var field: shapes.entrySet())
        {
            String key = field.getKey();
            int[] shape = field.getValue().clone();
            if(invalidShape && key.equals("low.input.bias")) shape[0]++;
            table.put(key, Map.of("shape", shape, "offset_bytes", buffer.position()));
            int size = 1;
            for(int dimension: field.getValue()) size *= dimension;
            float[] data = new float[size];
            if(key.startsWith("constants.hann"))
            {
                for(int i = 0; i < size; i++) data[i] = (float)(0.5 - 0.5 * Math.cos(2 * Math.PI * i / size));
            }
            if(key.equals("constants.upsample"))
            {
                data = AudioEnhancementModelBundle.firCoefficients(3900,129);
                double sum = 0;
                for(float coefficient: data) sum += coefficient;
                for(int i = 0; i < size; i++) data[i] = (float)(data[i] / sum * 2);
            }
            if(key.equals("high.bandpass"))
            {
                data = AudioEnhancementModelBundle.firCoefficients(7500,257);
                float[] low = AudioEnhancementModelBundle.firCoefficients(4250,257);
                for(int i = 0; i < size; i++) data[i] -= low[i];
            }
            if(key.equals("high.mask"))
            {
                for(int i = 136; i <= 240; i++) data[i] = 1;
            }
            if(nonFinite && key.equals("low.input.weight")) data[0] = Float.NaN;
            for(float value: data) buffer.putFloat(value);
        }
        byte[] weights = buffer.array();
        Map<String, Object> codecs = new LinkedHashMap<>();
        for(Codec codec: Codec.values())
        {
            String entry = "weights/" + codec.name().toLowerCase(java.util.Locale.ROOT) + ".f32le";
            codecs.put(codec.name(), Map.of("input_gain", .75, "enhancer_checkpoint_sha256", "0".repeat(64),
                "bwe_checkpoint_sha256", "1".repeat(64), "weights_entry", entry,
                "weights_sha256", AudioEnhancementModelBundle.sha256(weights), "weights_bytes", weights.length, "tensors", table));
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", "sdrtrunk-audio-enhancement");
        manifest.put("schema_version", schema);
        manifest.put("model_id", id);
        manifest.put("architecture", AudioEnhancementModelBundle.ARCHITECTURE);
        manifest.put("input_sample_rate", 8000);
        manifest.put("output_sample_rate", 16000);
        manifest.put("maximum_input_samples", AudioEnhancementModelBundle.MAX_INPUT_SAMPLES);
        manifest.put("source_sha256", Map.of("synthetic", "2".repeat(64)));
        manifest.put("codecs", codecs);
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try(ZipOutputStream zip = new ZipOutputStream(result))
        {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(new ObjectMapper().writeValueAsBytes(manifest));
            zip.closeEntry();
            for(Codec codec: Codec.values())
            {
                zip.putNextEntry(new ZipEntry("weights/" + codec.name().toLowerCase(java.util.Locale.ROOT) + ".f32le"));
                zip.write(weights);
                zip.closeEntry();
            }
        }
        return result.toByteArray();
    }
}
