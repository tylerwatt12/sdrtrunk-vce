/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.audio.enhance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import static org.junit.jupiter.api.Assertions.*;

/** Optional private, hash-pinned whole-call parity fixture; no recordings are stored in Git. */
class AudioEnhancementReferenceTest
{
    @Test void matchesFrozenWholeCallReferences() throws Exception
    {
        String manifest = System.getenv("SDRTRUNK_ML_REFERENCE_MANIFEST");
        String bundlePath = System.getenv("SDRTRUNK_ML_REFERENCE_BUNDLE");
        Assumptions.assumeTrue(manifest != null && bundlePath != null, "Private reference paths were not supplied");
        Path manifestPath = Path.of(manifest);
        JsonNode document = new ObjectMapper().readTree(Files.readAllBytes(manifestPath));
        assertEquals("complete", document.path("status").asText());
        AudioEnhancementModelBundle bundle = AudioEnhancementModelBundle.load(Path.of(bundlePath));
        int count = 0;
        var reports = new ArrayList<Map<String,Object>>();
        for(JsonNode call: document.path("calls"))
        {
            Codec codec = call.path("codec").asText().equals("IMBE") ? Codec.IMBE : Codec.AMBE_PLUS_2;
            var metadata = bundle.metadata().codecs().get(codec);
            String modelKey = codec == Codec.IMBE ? "imbe" : "ambe";
            JsonNode provenance = document.path("models").path(modelKey);
            assertEquals(provenance.path("enhancer8k").path("sha256").asText(), metadata.enhancerCheckpointSha256());
            assertEquals(provenance.path("bwe16k").path("sha256").asText(), metadata.bweCheckpointSha256());
            assertEquals(call.path("input_scale").floatValue(), metadata.inputGain());
            float[] input = readPinned(manifestPath.getParent(), call.path("source_user_raw"));
            float[] expected = readPinned(manifestPath.getParent(), call.path("views").path("bwe16k").path("raw_full16"));
            float[] actual = bundle.enhance(input, codec);
            assertEquals(expected.length, actual.length);
            double energy = 0, error = 0, maximum = 0;
            for(int i = 0; i < actual.length; i++)
            {
                assertTrue(Float.isFinite(actual[i]));
                double delta = actual[i] - expected[i];
                maximum = Math.max(maximum, Math.abs(delta));
                error += delta * delta;
                energy += (double)expected[i] * expected[i];
            }
            double relative = Math.sqrt(error / Math.max(energy, 1e-20));
            assertTrue(maximum < 1e-4, "Whole-call peak error exceeds 1e-4: " + maximum);
            assertTrue(relative < 1e-4, "Whole-call relative RMS error exceeds 1e-4: " + relative);
            assertArrayEquals(actual, bundle.enhance(input, codec), "A repeated call must reset its full state deterministically");
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id",call.path("id").asText());
            row.put("codec",codec.name());
            row.put("input_samples",input.length);
            row.put("output_samples",actual.length);
            row.put("maximum_absolute_error",maximum);
            row.put("relative_rms_error",relative);
            row.put("rms_error",Math.sqrt(error / actual.length));
            row.put("reference_to_error_db",10 * Math.log10(energy / Math.max(error,1e-30)));
            row.put("input_sha256",call.path("source_user_raw").path("sha256").asText());
            row.put("reference_sha256",call.path("views").path("bwe16k").path("raw_full16").path("sha256").asText());
            reports.add(row);
            count++;
        }
        assertEquals(5, count);
        String report = System.getenv("SDRTRUNK_ML_REFERENCE_REPORT");
        if(report != null)
        {
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("status","complete");
            result.put("model_id",bundle.modelId());
            result.put("bundle_sha256",bundle.sha256());
            result.put("java_version",System.getProperty("java.version"));
            result.put("scope","Full-call Java vs pinned Python float outputs; no crop, clipping or presentation gain; repeated calls deterministic");
            result.put("calls",reports);
            Path destination = Path.of(report);
            Files.createDirectories(destination.getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(destination.toFile(),result);
        }
    }

    private static float[] readPinned(Path root, JsonNode record) throws Exception
    {
        Path path = root.resolve(record.path("path").asText()).normalize();
        byte[] bytes = Files.readAllBytes(path);
        assertEquals(record.path("sha256").asText(), AudioEnhancementModelBundle.sha256(bytes));
        assertEquals(0, bytes.length % 4);
        float[] samples = new float[bytes.length / 4];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for(int i = 0; i < samples.length; i++) samples[i] = buffer.getFloat();
        return samples;
    }
}
