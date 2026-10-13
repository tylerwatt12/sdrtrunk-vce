/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.audio.enhance;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.protocol.Protocol;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Portable, atomic model selection. Inference is called only by the bounded completed-call audio worker. */
public final class VoiceEnhancementService
{
    public static final int MAXIMUM_UPLOAD_BYTES = 16 * 1024 * 1024;
    public static final int MAXIMUM_INPUT_SAMPLES = 120 * 8000;
    private static final Logger LOG = LoggerFactory.getLogger(VoiceEnhancementService.class);
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Path mDirectory;
    private final AtomicBoolean mInstalling = new AtomicBoolean();
    private final AtomicLong mFailures = new AtomicLong();
    private volatile State mState = new State(false, null, null, false);

    public VoiceEnhancementService(Path directory)
    {
        mDirectory = directory.toAbsolutePath().normalize();
        loadSelection();
    }

    private void loadSelection()
    {
        Path settings = mDirectory.resolve("selection.json");
        if(!Files.exists(settings, LinkOption.NOFOLLOW_LINKS)) return;
        boolean enabled = false;
        String selected = null;
        try
        {
            if(!Files.isRegularFile(settings, LinkOption.NOFOLLOW_LINKS) || Files.size(settings) > 4096)
                throw new IOException("Invalid model selection");
            JsonNode value = JSON.readTree(Files.readAllBytes(settings));
            if(value == null || !value.isObject() || value.size() != 3 ||
                !value.path("version").isInt() || value.path("version").intValue() != 1 || !value.path("enabled").isBoolean() ||
                !value.path("bundle_sha256").isTextual()) throw new IOException("Invalid model selection");
            enabled = value.path("enabled").booleanValue();
            selected = value.path("bundle_sha256").textValue();
            if(!selected.matches("[0-9a-f]{64}")) throw new IOException("Invalid model selection");
            Path archive = mDirectory.resolve(selected + ".zip");
            if(!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS) ||
                Files.size(archive) > MAXIMUM_UPLOAD_BYTES) throw new IOException("Model is unavailable");
            byte[] bytes = Files.readAllBytes(archive);
            if(!hash(bytes).equals(selected)) throw new IOException("Model checksum mismatch");
            AudioEnhancementModelBundle model = AudioEnhancementModelBundle.load(bytes);
            warm(model);
            mState = new State(enabled, selected, model, false);
        }
        catch(IOException | RuntimeException exception)
        {
            mState = new State(enabled, selected, null, true);
            LOG.warn("Selected voice enhancement model is unavailable; using original audio");
        }
    }

    public Snapshot snapshot()
    {
        State state = mState;
        Map<String,Object> model = null;
        if(state.model() != null)
        {
            LinkedHashMap<String,Object> values = new LinkedHashMap<>();
            values.put("id", state.model().modelId());
            values.put("sha256", state.hash());
            values.put("input_sample_rate", 8000);
            values.put("output_sample_rate", 16000);
            values.put("codecs", List.of("IMBE", "AMBE_PLUS_2"));
            model = Map.copyOf(values);
        }
        return new Snapshot(revision(state), state.enabled(), state.unavailable() ? "unavailable" :
            state.model() == null ? "no_model" : "ready", model);
    }

    public synchronized UpdateResult setEnabled(String expectedRevision, boolean enabled) throws IOException
    {
        if(!revision(mState).equals(expectedRevision)) return new UpdateResult(false, snapshot());
        if(enabled && mState.model() == null)
            throw new IllegalArgumentException("Install a voice enhancement model first");
        if(enabled != mState.enabled())
        {
            State next = new State(enabled, mState.hash(), mState.model(), mState.unavailable());
            persist(next);
            mState = next;
        }
        return new UpdateResult(true, snapshot());
    }

    /** Validate and warm before replacing the pointer. In-progress inference retains its immutable model. */
    public UpdateResult install(String expectedRevision, byte[] archive) throws IOException
    {
        if(archive == null || archive.length == 0 || archive.length > MAXIMUM_UPLOAD_BYTES)
            throw new IllegalArgumentException("Choose a valid voice enhancement model ZIP");
        if(!mInstalling.compareAndSet(false, true)) throw new BusyException();
        try
        {
            archive = archive.clone();
            if(!snapshot().revision().equals(expectedRevision)) return new UpdateResult(false, snapshot());
            AudioEnhancementModelBundle model;
            try { model = AudioEnhancementModelBundle.load(archive); }
            catch(IOException exception) { throw new IllegalArgumentException("Invalid voice model bundle", exception); }
            warm(model);
            String sha = hash(archive);
            synchronized(this)
            {
                if(!revision(mState).equals(expectedRevision)) return new UpdateResult(false, snapshot());
                Files.createDirectories(mDirectory);
                Path target = mDirectory.resolve(sha + ".zip");
                if(Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                {
                    if(!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) ||
                        Files.size(target) != archive.length || !hash(Files.readAllBytes(target)).equals(sha))
                        throw new IOException("Stored model checksum mismatch");
                }
                else atomicWrite(target, archive);
                State next = new State(mState.enabled(), sha, model, false);
                persist(next);
                mState = next;
                return new UpdateResult(true, snapshot());
            }
        }
        finally { mInstalling.set(false); }
    }

    private static void warm(AudioEnhancementModelBundle model)
    {
        for(Codec codec : Codec.values())
        {
            if(!model.supports(codec)) throw new IllegalArgumentException("Both P25 voice models are required");
            float[] output = model.enhance(new float[1024], codec);
            if(output.length != 2048) throw new IllegalArgumentException("Invalid model output");
            for(float value : output)
                if(!Float.isFinite(value) || Math.abs(value) > 1e-7f)
                    throw new IllegalArgumentException("Model does not preserve silence");
        }
    }

    /** No model reads, writes, loads, or selection locks occur here. Caller is an audio worker, never a decoder. */
    public RenderedAudio render(CompletedAudioCall call)
    {
        State captured = mState;
        if(call == null || call.snapshot() == null) return null;
        Protocol protocol = call.snapshot().callLegSource().decoderType() != null ?
            call.snapshot().callLegSource().decoderType().getProtocol() : null;
        if(!captured.enabled() || captured.model() == null || !captured.model().supports(protocol)) return null;
        long length = 0;
        for(float[] buffer : call.audioBuffers())
        {
            if(buffer == null) return null;
            length += buffer.length;
            if(length > Math.min(MAXIMUM_INPUT_SAMPLES, captured.model().metadata().maximumInputSamples())) return null;
        }
        if(length == 0) return null;
        float[] raw = new float[(int)length];
        int offset = 0;
        for(float[] buffer : call.audioBuffers())
        {
            System.arraycopy(buffer, 0, raw, offset, buffer.length);
            offset += buffer.length;
        }
        try
        {
            float[] enhanced = captured.model().enhance(raw,
                AudioEnhancementModelBundle.codecFor(protocol));
            if(enhanced.length != raw.length * 2) throw new IllegalArgumentException("Invalid output length");
            for(float value : enhanced)
                if(!Float.isFinite(value)) throw new IllegalArgumentException("Invalid output sample");
            return new RenderedAudio(enhanced, 16000, captured.model().modelId());
        }
        catch(RuntimeException exception)
        {
            mFailures.incrementAndGet();
            return null;
        }
    }

    public long failures() { return mFailures.get(); }
    public boolean isEnabled() { State state = mState; return state.enabled() && state.model() != null; }

    private void persist(State state) throws IOException
    {
        Files.createDirectories(mDirectory);
        if(state.hash() == null) return;
        atomicWrite(mDirectory.resolve("selection.json"), JSON.writeValueAsBytes(Map.of(
            "version", 1, "enabled", state.enabled(), "bundle_sha256", state.hash())));
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException
    {
        Path temporary = Files.createTempFile(target.getParent(), ".voice-model-", ".tmp");
        try
        {
            Files.write(temporary, bytes);
            try(FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        finally { Files.deleteIfExists(temporary); }
    }

    private static String revision(State state)
    {
        return hash(("v1:" + state.enabled() + ":" + state.hash() + ":" + state.unavailable())
            .getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(byte[] bytes)
    {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private record State(boolean enabled, String hash, AudioEnhancementModelBundle model, boolean unavailable) {}
    public record Snapshot(String revision, boolean enabled, String status, Map<String,Object> model) {}
    public record UpdateResult(boolean updated, Snapshot snapshot) {}
    public record RenderedAudio(float[] samples, int sampleRate, String modelId) {}
    public static final class BusyException extends IllegalStateException {}
}
