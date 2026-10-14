/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.audio.enhance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.codec.mbe.JmbeVocoderAudio;
import io.github.dsheirer.audio.tone.CompletedToneSequence;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.database.settings.ReceiverSettingsSchema;
import io.github.dsheirer.module.decode.DecoderType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Browser output preferences and worker-only access to the selected JMBE library's embedded processing. */
public final class VoiceEnhancementService
{
    public static final int MAXIMUM_INPUT_SAMPLES = 120 * 8000;
    public static final int MAXIMUM_AGC_INPUT_SAMPLES = 16_777_216;
    private static final Logger LOG = LoggerFactory.getLogger(VoiceEnhancementService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ApplicationSettingsStore mSettingsStore;
    private final Supplier<JmbeVocoderAudio> mProviderSupplier;
    private final AtomicLong mFailures = new AtomicLong();
    private volatile boolean mEnabled;

    public VoiceEnhancementService(ApplicationSettingsStore settingsStore, Supplier<JmbeVocoderAudio> providerSupplier)
    {
        mSettingsStore = Objects.requireNonNull(settingsStore);
        mProviderSupplier = Objects.requireNonNull(providerSupplier);
        loadEnabled();
    }

    private void loadEnabled()
    {
        try
        {
            String json = mSettingsStore.loadJson(ReceiverSettingsSchema.VOICE_ENHANCEMENT_KEY, 256)
                .orElseThrow(() -> new IOException("Voice enhancement setting is missing"));
            ReceiverSettingsSchema.validateJson(ReceiverSettingsSchema.VOICE_ENHANCEMENT_KEY, json);
            mEnabled = JSON.readValue(json, Settings.class).enabled();
        }
        catch(IOException | SQLException | RuntimeException exception)
        {
            LOG.warn("Voice enhancement setting is unavailable; using original audio");
        }
    }

    /** Called by administration requests, never by real-time decoder callbacks. */
    public Snapshot snapshot()
    {
        Map<String,Object> model = null;
        try
        {
            JmbeVocoderAudio provider = mProviderSupplier.get();
            if(provider != null) model = modelInfo(provider);
        }
        catch(RuntimeException | LinkageError exception)
        {
            // An older or unavailable selected library keeps the optional processing unavailable.
        }
        String status = model != null ? "ready" : "unavailable";
        boolean enabled = mEnabled;
        return new Snapshot(revision(enabled, status, model), enabled, status, model);
    }

    public synchronized UpdateResult setEnabled(String expectedRevision, boolean enabled) throws IOException
    {
        Snapshot before = snapshot();
        if(!before.revision().equals(expectedRevision)) return new UpdateResult(false, before);
        if(enabled && before.model() == null)
            throw new IllegalArgumentException("Install a current JMBE library to use voice enhancement");
        if(enabled != mEnabled)
        {
            persist(enabled);
            mEnabled = enabled;
        }
        return new UpdateResult(true, snapshot());
    }

    public RenderedAudio render(CompletedAudioCall call)
    {
        return render(call, false);
    }

    /** Copies raw PCM before handing it to JMBE. Called only by the bounded completed-call browser worker. */
    public RenderedAudio render(CompletedAudioCall call, boolean continuousAgc)
    {
        return render(call, continuousAgc, true);
    }

    /** Under backlog, the browser worker may skip model inference while retaining lightweight automatic gain. */
    public RenderedAudio render(CompletedAudioCall call, boolean continuousAgc, boolean allowEnhancement)
    {
        boolean requestedEnhancement = mEnabled && allowEnhancement;
        if(call == null || !requestedEnhancement && !continuousAgc) return null;
        DecoderType decoder = call.nativeAudioSnapshot().callLegSource().decoderType();
        String codec = switch(decoder)
        {
            case P25_PHASE1, P25_CONVENTIONAL -> "IMBE";
            case P25_PHASE2, DMR, NXDN -> "AMBE_PLUS_2";
            case null, default -> "NONE";
        };
        boolean enhance = requestedEnhancement && (decoder == DecoderType.P25_PHASE1 ||
            decoder == DecoderType.P25_CONVENTIONAL || decoder == DecoderType.P25_PHASE2);
        if(!enhance && !continuousAgc) return null;
        try
        {
            JmbeVocoderAudio provider = mProviderSupplier.get();
            if(provider == null) return null;
            long count = 0;
            long maximum = continuousAgc ? MAXIMUM_AGC_INPUT_SAMPLES : MAXIMUM_INPUT_SAMPLES;
            for(float[] buffer : call.audioBuffers())
            {
                if(buffer == null) return null;
                count += buffer.length;
                if(count > maximum) return null;
            }
            if(count == 0) return null;
            Map<String,Object> info = null;
            if(enhance && count <= MAXIMUM_INPUT_SAMPLES)
            {
                try
                {
                    info = modelInfo(provider);
                    if(count > ((Number)info.get("maximum_input_samples")).longValue()) enhance = false;
                }
                catch(RuntimeException | LinkageError exception)
                {
                    mFailures.incrementAndGet();
                    enhance = false;
                }
            }
            else enhance = false;
            if(!enhance && !continuousAgc) return null;
            long[][] ranges = toneRanges(call, count);
            try
            {
                return processed(provider, copyAudio(call, (int)count), codec, enhance, continuousAgc, ranges,
                    enhance ? (String)info.get("id") : null);
            }
            catch(RuntimeException | LinkageError exception)
            {
                mFailures.incrementAndGet();
                if(enhance && continuousAgc)
                {
                    // Failed inference may have altered its private input. Re-copy native PCM before AGC fallback.
                    return processed(provider, copyAudio(call, (int)count), codec, false, true, ranges, null);
                }
                return null;
            }
        }
        catch(RuntimeException | LinkageError exception)
        {
            mFailures.incrementAndGet();
            return null;
        }
    }

    private static float[] copyAudio(CompletedAudioCall call, int count)
    {
        float[] raw = new float[count];
        int offset = 0;
        for(float[] buffer : call.audioBuffers())
        {
            System.arraycopy(buffer, 0, raw, offset, buffer.length);
            offset += buffer.length;
        }
        return raw;
    }

    private static RenderedAudio processed(JmbeVocoderAudio provider, float[] raw, String codec,
                                           boolean enhance, boolean agc, long[][] ranges, String modelId)
    {
        float[] output = provider.process(raw, codec, enhance, agc, ranges[0], ranges[1]);
        if(output == null || output.length != (long)raw.length * (enhance ? 2 : 1))
            throw new IllegalArgumentException("Invalid JMBE audio length");
        for(float value : output)
            if(!Float.isFinite(value)) throw new IllegalArgumentException("Invalid JMBE audio sample");
        return new RenderedAudio(output, enhance ? 16000 : 8000, modelId);
    }

    private static long[][] toneRanges(CompletedAudioCall call, long samples)
    {
        CompletedToneSequence sequence = call.completedToneSequence();
        if(sequence == null || !sequence.matches(call.nativeAudioSnapshot()) || sequence.tones().isEmpty())
            return new long[][] {new long[0], new long[0]};
        List<CompletedToneSequence.Element> tones = sequence.tones();
        long[] offsets = new long[tones.size()];
        long[] durations = new long[tones.size()];
        for(int i = 0; i < tones.size(); i++)
        {
            CompletedToneSequence.Element tone = tones.get(i);
            if(tone.offsetMilliseconds() == null)
                return new long[][] {new long[] {0}, new long[] {(samples + 7) / 8}};
            offsets[i] = tone.offsetMilliseconds();
            durations[i] = tone.durationMilliseconds();
        }
        return new long[][] {offsets, durations};
    }

    private static Map<String,Object> modelInfo(JmbeVocoderAudio provider)
    {
        Map<String,Object> info = provider.modelInfo();
        if(!(info.get("id") instanceof String id) || id.isBlank() || id.length() > 160 ||
            !(info.get("sha256") instanceof String hash) || !hash.matches("[0-9a-f]{64}") ||
            !(info.get("input_sample_rate") instanceof Number input) || input.intValue() != 8000 ||
            !(info.get("output_sample_rate") instanceof Number output) || output.intValue() != 16000 ||
            !(info.get("maximum_input_samples") instanceof Number maximum) || maximum.longValue() < 1 ||
            maximum.longValue() > MAXIMUM_INPUT_SAMPLES || !(info.get("codecs") instanceof List<?> codecs) ||
            !codecs.containsAll(List.of("IMBE", "AMBE_PLUS_2")))
            throw new IllegalStateException("Invalid JMBE model information");
        return Map.of("id", id, "sha256", hash, "input_sample_rate", 8000, "output_sample_rate", 16000,
            "maximum_input_samples", maximum.intValue(), "codecs", List.of("IMBE", "AMBE_PLUS_2"));
    }

    public long failures() { return mFailures.get(); }

    /** The saved choice only; checking selected-library capability belongs to an admin request or audio worker. */
    public boolean isEnabled() { return mEnabled; }

    private void persist(boolean enabled) throws IOException
    {
        try
        {
            mSettingsStore.save(ReceiverSettingsSchema.VOICE_ENHANCEMENT_KEY, new Settings(enabled));
        }
        catch(IOException | SQLException | RuntimeException exception)
        {
            throw new IOException("Voice enhancement setting could not be saved");
        }
    }

    private static String revision(boolean enabled, String status, Map<String,Object> model)
    {
        String canonical = "v2:" + enabled + ":" + status + ":" + (model != null ? model.get("sha256") : "");
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        }
        catch(NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public record Settings(boolean enabled) {}
    public record Snapshot(String revision, boolean enabled, String status, Map<String,Object> model) {}
    public record UpdateResult(boolean updated, Snapshot snapshot) {}
    public record RenderedAudio(float[] samples, int sampleRate, String modelId) {}
}
