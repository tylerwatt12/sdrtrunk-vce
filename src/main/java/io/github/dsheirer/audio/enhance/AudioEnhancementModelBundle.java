/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.audio.enhance;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.github.dsheirer.protocol.Protocol;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Immutable, data-only model bundle. Loading validates the entire ZIP before publication.
 * Inference is intended for a bounded completed-call worker, never a decoder callback.
 * No model code, pickle, native library or arbitrary file is loaded from the bundle.
 */
public final class AudioEnhancementModelBundle
{
    public static final int MAX_BUNDLE_BYTES = 4 * 1024 * 1024;
    public static final int MAX_INPUT_SAMPLES = 120 * 8000;
    public static final String ARCHITECTURE = "spectral48-highband48-v1";
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final String mModelId;
    private final String mSha256;
    private final Metadata mMetadata;
    private final Map<Codec, WholeCallEnhancer.Model> mModels;

    public record CodecMetadata(float inputGain, String enhancerCheckpointSha256, String bweCheckpointSha256) {}

    public record Metadata(int schemaVersion, String architecture, int inputSampleRate, int outputSampleRate,
                           int maximumInputSamples, Map<Codec, CodecMetadata> codecs, Map<String, String> sourceHashes)
    {
        public Metadata
        {
            codecs = Map.copyOf(codecs);
            sourceHashes = Map.copyOf(sourceHashes);
        }
    }

    private AudioEnhancementModelBundle(String id, String sha256, Metadata metadata,
                                       Map<Codec, WholeCallEnhancer.Model> models)
    {
        mModelId = id;
        mSha256 = sha256;
        mMetadata = metadata;
        mModels = Map.copyOf(models);
    }

    public static AudioEnhancementModelBundle load(Path path) throws IOException
    {
        long size = Files.size(path);
        if(size < 1 || size > MAX_BUNDLE_BYTES)
        {
            throw new IOException("Model bundle exceeds the permitted size");
        }
        // A bounded stream also covers a file replaced or extended after the size check.
        try(var stream = Files.newInputStream(path))
        {
            byte[] bytes = stream.readNBytes(MAX_BUNDLE_BYTES + 1);
            return load(bytes);
        }
    }

    public static AudioEnhancementModelBundle load(byte[] bytes) throws IOException
    {
        if(bytes == null || bytes.length == 0 || bytes.length > MAX_BUNDLE_BYTES)
        {
            throw new IOException("Model bundle exceeds the permitted size");
        }
        // Keep the hash and parsed data tied to a snapshot even if the caller mutates its buffer.
        bytes = bytes.clone();
        Map<String, byte[]> entries = readZip(bytes);
        byte[] manifestBytes = entries.remove("manifest.json");
        if(manifestBytes == null || manifestBytes.length > 65536)
        {
            throw new IOException("Missing or oversized model manifest");
        }
        JsonNode root = MAPPER.readTree(manifestBytes);
        requireObject(root, "manifest");
        if(!"sdrtrunk-audio-enhancement".equals(text(root, "format")) || integer(root, "schema_version") != 1 ||
            !ARCHITECTURE.equals(text(root, "architecture")) || integer(root, "input_sample_rate") != 8000 ||
            integer(root, "output_sample_rate") != 16000)
        {
            throw new IOException("Unsupported model bundle format or architecture");
        }
        String id = text(root, "model_id");
        if(!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}"))
        {
            throw new IOException("Invalid model identifier");
        }
        int cap = integer(root, "maximum_input_samples");
        if(cap < 1 || cap > MAX_INPUT_SAMPLES)
        {
            throw new IOException("Invalid model input limit");
        }
        Map<String, String> sources = new LinkedHashMap<>();
        JsonNode sourceNodes = root.get("source_sha256");
        requireObject(sourceNodes, "source hashes");
        if(sourceNodes.size() > 8)
        {
            throw new IOException("Too many model source records");
        }
        var sourceFields = sourceNodes.fields();
        while(sourceFields.hasNext())
        {
            var field = sourceFields.next();
            if(!field.getKey().matches("[A-Za-z0-9._-]{1,96}"))
            {
                throw new IOException("Invalid source identifier");
            }
            sources.put(field.getKey(), checkedSha(field.getValue().asText()));
        }
        JsonNode codecs = root.get("codecs");
        requireObject(codecs, "codecs");
        if(codecs.size() < 1 || codecs.size() > Codec.values().length)
        {
            throw new IOException("Invalid codec count");
        }
        Map<Codec, WholeCallEnhancer.Model> models = new EnumMap<>(Codec.class);
        Map<Codec, CodecMetadata> codecMetadata = new EnumMap<>(Codec.class);
        var fields = codecs.fields();
        while(fields.hasNext())
        {
            var field = fields.next();
            Codec codec;
            try
            {
                codec = Codec.valueOf(field.getKey());
            }
            catch(IllegalArgumentException e)
            {
                throw new IOException("Unsupported codec in bundle", e);
            }
            JsonNode node = field.getValue();
            requireObject(node, "codec");
            JsonNode gainNode = node.get("input_gain");
            if(gainNode == null || !gainNode.isNumber())
            {
                throw new IOException("Missing input calibration gain");
            }
            float gain = gainNode.floatValue();
            if(!Float.isFinite(gain) || gain < 0.05f || gain > 4.0f)
            {
                throw new IOException("Invalid input calibration gain");
            }
            String enhancerSha = checkedSha(text(node, "enhancer_checkpoint_sha256"));
            String bweSha = checkedSha(text(node, "bwe_checkpoint_sha256"));
            String entry = text(node, "weights_entry");
            if(!entry.equals("weights/" + codec.name().toLowerCase(java.util.Locale.ROOT) + ".f32le"))
            {
                throw new IOException("Invalid weights entry");
            }
            byte[] payload = entries.remove(entry);
            if(payload == null || !sha256(payload).equals(checkedSha(text(node, "weights_sha256"))))
            {
                throw new IOException("Missing or changed model weights");
            }
            if(payload.length != integer(node, "weights_bytes"))
            {
                throw new IOException("Invalid weight payload length");
            }
            Map<String, float[]> tensors = readTensors(node.get("tensors"), payload);
            WholeCallEnhancer.Model model = new WholeCallEnhancer.Model(gain, tensors);
            models.put(codec, model);
            codecMetadata.put(codec, new CodecMetadata(gain, enhancerSha, bweSha));
        }
        if(!entries.isEmpty())
        {
            throw new IOException("Undeclared files in model bundle");
        }
        return new AudioEnhancementModelBundle(id, sha256(bytes),
            new Metadata(1, ARCHITECTURE, 8000, 16000, cap, codecMetadata, sources), models);
    }

    public String modelId() { return mModelId; }
    public String sha256() { return mSha256; }
    public Metadata metadata() { return mMetadata; }
    public boolean supports(Codec codec) { return codec != null && mModels.containsKey(codec); }
    public boolean supports(Protocol protocol) { return supports(codecFor(protocol)); }

    /** Protocol mapping is deliberately limited to the two P25 modes validated for this bundle. */
    public static Codec codecFor(Protocol protocol)
    {
        if(protocol == Protocol.APCO25) return Codec.IMBE;
        if(protocol == Protocol.APCO25_PHASE2) return Codec.AMBE_PLUS_2;
        return null;
    }

    /**
     * Returns decoder-level 16 kHz mono samples. No delay removal, crop, clipping or presentation gain is applied.
     * Each invocation has independent CNN/OLA state and noise indexing from call frame zero.
     */
    public float[] enhance(float[] raw8, Codec codec)
    {
        if(codec == null) throw new IllegalArgumentException("Codec is not supported by this model");
        WholeCallEnhancer.Model model = mModels.get(codec);
        if(model == null)
        {
            throw new IllegalArgumentException("Codec is not supported by this model");
        }
        if(raw8 == null || raw8.length == 0 || raw8.length > mMetadata.maximumInputSamples())
        {
            throw new IllegalArgumentException("Audio length is outside the model limit");
        }
        for(float sample: raw8)
        {
            if(!Float.isFinite(sample) || Math.abs(sample) > 16)
            {
                throw new IllegalArgumentException("Audio contains invalid decoder-level samples");
            }
        }
        return WholeCallEnhancer.enhance(raw8, model);
    }

    private static Map<String, byte[]> readZip(byte[] bytes) throws IOException
    {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        int total = 0;
        try(ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes)))
        {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while((entry = zip.getNextEntry()) != null)
            {
                String name = entry.getName();
                if(entry.isDirectory() || !Set.of("manifest.json", "weights/imbe.f32le", "weights/ambe_plus_2.f32le").contains(name) ||
                    entries.containsKey(name) || entries.size() >= 3)
                {
                    throw new IOException("Invalid or duplicate model ZIP entry");
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                int n;
                while((n = zip.read(buffer)) != -1)
                {
                    total += n;
                    if(total > MAX_BUNDLE_BYTES || (name.equals("manifest.json") && output.size() + n > 65536))
                    {
                        throw new IOException("Expanded model exceeds the permitted size");
                    }
                    output.write(buffer, 0, n);
                }
                entries.put(name, output.toByteArray());
                zip.closeEntry();
            }
        }
        return entries;
    }

    static Map<String, int[]> tensorShapes()
    {
        Map<String, int[]> shapes = new LinkedHashMap<>();
        for(String prefix: new String[]{"low", "high"})
        {
            shapes.put(prefix + ".input.weight", new int[]{48,129,1});
            shapes.put(prefix + ".input.bias", new int[]{48});
            for(int block = 0; block < 4; block++)
            {
                shapes.put(prefix + ".blocks." + block + ".conv.weight", new int[]{48,48,3});
                shapes.put(prefix + ".blocks." + block + ".conv.bias", new int[]{48});
                shapes.put(prefix + ".blocks." + block + ".mix.weight", new int[]{48,48,1});
                shapes.put(prefix + ".blocks." + block + ".mix.bias", new int[]{48});
            }
            int output = prefix.equals("low") ? 129 : 514;
            shapes.put(prefix + ".output.weight", new int[]{output,48,1});
            shapes.put(prefix + ".output.bias", new int[]{output});
        }
        shapes.put("high.mask", new int[]{1,257,1});
        shapes.put("high.bandpass", new int[]{257});
        shapes.put("constants.hann256", new int[]{256});
        shapes.put("constants.hann512", new int[]{512});
        shapes.put("constants.upsample", new int[]{129});
        return Collections.unmodifiableMap(shapes);
    }

    private static Map<String, float[]> readTensors(JsonNode nodes, byte[] payload) throws IOException
    {
        requireObject(nodes, "tensors");
        Map<String, int[]> expected = tensorShapes();
        if(nodes.size() != expected.size()) throw new IOException("Incorrect tensor count");
        Map<String, float[]> tensors = new LinkedHashMap<>();
        int offset = 0;
        ByteBuffer data = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        for(var entry: expected.entrySet())
        {
            JsonNode node = nodes.get(entry.getKey());
            requireObject(node, "tensor " + entry.getKey());
            JsonNode shape = node.get("shape");
            int[] dimensions = entry.getValue();
            if(shape == null || !shape.isArray() || shape.size() != dimensions.length || integer(node, "offset_bytes") != offset)
                throw new IOException("Invalid tensor shape or offset");
            int count = 1;
            for(int d = 0; d < dimensions.length; d++)
            {
                if(!shape.get(d).isIntegralNumber() || !shape.get(d).canConvertToInt() || shape.get(d).intValue() != dimensions[d])
                    throw new IOException("Unsupported tensor dimensions");
                count *= dimensions[d];
            }
            if(offset + count * 4 > payload.length) throw new IOException("Truncated tensor data");
            float[] values = new float[count];
            for(int i = 0; i < count; i++)
            {
                values[i] = data.getFloat();
                if(!Float.isFinite(values[i]) || Math.abs(values[i]) > 16)
                    throw new IOException("Non-finite or out-of-bound model tensor");
            }
            offset += count * 4;
            tensors.put(entry.getKey(), values);
        }
        if(offset != payload.length) throw new IOException("Trailing weight data");
        validateConstants(tensors);
        return tensors;
    }

    private static void validateConstants(Map<String, float[]> tensors) throws IOException
    {
        for(int size: new int[]{256,512})
        {
            float[] window = tensors.get("constants.hann" + size);
            for(int i = 0; i < size; i++)
                if(Math.abs(window[i] - (0.5 - 0.5 * Math.cos(2 * Math.PI * i / size))) > 2e-6)
                    throw new IOException("Invalid analysis window");
        }
        float[] mask = tensors.get("high.mask");
        for(int i = 0; i < mask.length; i++)
            if(mask[i] != (i >= 136 && i <= 240 ? 1.0f : 0.0f)) throw new IOException("Invalid highband frequency mask");
        for(String key: new String[]{"constants.upsample", "high.bandpass"})
        {
            float[] filter = tensors.get(key);
            float[] expected;
            if(key.equals("constants.upsample"))
            {
                expected = firCoefficients(3900, 129);
                double coefficientSum = 0;
                for(float coefficient: expected) coefficientSum += coefficient;
                for(int i = 0; i < expected.length; i++) expected[i] = (float)(expected[i] / coefficientSum * 2);
            }
            else
            {
                expected = firCoefficients(7500, 257);
                float[] lower = firCoefficients(4250, 257);
                for(int i = 0; i < expected.length; i++) expected[i] -= lower[i];
            }
            double sum = 0;
            for(int i = 0; i < filter.length; i++)
            {
                if(Math.abs(filter[i] - expected[i]) > 2e-6 || Math.abs(filter[i] - filter[filter.length - 1 - i]) > 2e-6)
                    throw new IOException("Invalid symmetric FIR filter");
                sum += filter[i];
            }
            if(key.equals("constants.upsample") && Math.abs(sum - 2) > 1e-5)
                throw new IOException("Invalid upsampling filter gain");
            if(key.equals("high.bandpass") && Math.abs(sum) > 1e-4)
                throw new IOException("Invalid highband filter gain");
        }
    }

    /** Version-one FIR definition, also used by public-data-only test fixtures. */
    static float[] firCoefficients(int cutoff, int taps)
    {
        float[] result = new float[taps];
        double denominator = besselI0(8.6);
        for(int i = 0; i < taps; i++)
        {
            double x = i - (taps - 1) / 2.0;
            double argument = 2 * Math.PI * cutoff / 16000.0 * x;
            double sinc = x == 0 ? 1 : Math.sin(argument) / argument;
            double ratio = x / ((taps - 1) / 2.0);
            double window = besselI0(8.6 * Math.sqrt(Math.max(0, 1 - ratio * ratio))) / denominator;
            result[i] = (float)(2.0 * cutoff / 16000 * sinc * window);
        }
        return result;
    }

    private static double besselI0(double x)
    {
        double term = 1;
        double sum = 1;
        for(int i = 1; i < 100; i++)
        {
            term *= x * x / (4.0 * i * i);
            sum += term;
            if(term < sum * 1e-16) break;
        }
        return sum;
    }

    private static void requireObject(JsonNode node, String name) throws IOException
    {
        if(node == null || !node.isObject()) throw new IOException("Missing or invalid " + name);
    }

    private static int integer(JsonNode node, String key) throws IOException
    {
        JsonNode value = node.get(key);
        if(value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw new IOException("Invalid " + key);
        return value.intValue();
    }

    private static String text(JsonNode node, String key) throws IOException
    {
        JsonNode value = node.get(key);
        if(value == null || !value.isTextual()) throw new IOException("Invalid " + key);
        return value.textValue();
    }

    private static String checkedSha(String value) throws IOException
    {
        if(!value.matches("[0-9a-f]{64}")) throw new IOException("Invalid SHA256");
        return value;
    }

    static String sha256(byte[] bytes)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch(NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }
}
