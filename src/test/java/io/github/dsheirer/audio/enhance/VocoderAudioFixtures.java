/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.audio.enhance;

import io.github.dsheirer.audio.codec.mbe.JmbeVocoderAudio;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Small reflected JMBE capability for VCE integration tests; contains no model inference or weights. */
public final class VocoderAudioFixtures
{
    private VocoderAudioFixtures() {}

    public static Provider provider(String modelId) { return new Provider(modelId); }

    public static final class Provider
    {
        private final String mModelId;
        private final String mHash;
        public volatile int processCount;
        public volatile int modelInfoCount;
        public volatile boolean failModelInfo;
        public volatile boolean fail;
        public volatile boolean failEnhancement;
        public volatile boolean mutateInput;
        public volatile String lastCodec;
        public volatile boolean lastEnhance;
        public volatile boolean lastAgc;
        public volatile long[] lastToneOffsets;
        public volatile long[] lastToneDurations;

        public Provider(String modelId)
        {
            mModelId = modelId;
            try
            {
                mHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(modelId.getBytes(StandardCharsets.UTF_8)));
            }
            catch(java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
        }

        public JmbeVocoderAudio bridge() { return new JmbeVocoderAudio(this); }

        public Map<String,Object> getModelInfo()
        {
            modelInfoCount++;
            if(failModelInfo) throw new IllegalStateException("Fixture model unavailable");
            return Map.of("id", mModelId, "sha256", mHash, "input_sample_rate", 8000,
                "output_sample_rate", 16000, "maximum_input_samples", 960000,
                "codecs", List.of("IMBE", "AMBE_PLUS_2"));
        }

        public float[] process(float[] raw, String codec, boolean enhance, boolean agc,
                               long[] toneOffsets, long[] toneDurations)
        {
            processCount++;
            lastCodec = codec;
            lastEnhance = enhance;
            lastAgc = agc;
            lastToneOffsets = toneOffsets.clone();
            lastToneDurations = toneDurations.clone();
            if(fail || enhance && failEnhancement)
            {
                if(mutateInput) java.util.Arrays.fill(raw, 0);
                throw new IllegalStateException("Fixture processing failure");
            }
            int multiplier = enhance ? 2 : 1;
            float[] result = new float[raw.length * multiplier];
            for(int i = 0; i < result.length; i++) result[i] = raw[i / multiplier] * (agc ? 2 : 1);
            if(mutateInput) java.util.Arrays.fill(raw, 0);
            return result;
        }
    }
}
