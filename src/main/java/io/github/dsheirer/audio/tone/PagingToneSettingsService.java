/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.audio.tone;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Receiver-wide, portable paging-analysis preference; persistence never runs on an audio producer. */
public final class PagingToneSettingsService
{
    private static final Logger LOG = LoggerFactory.getLogger(PagingToneSettingsService.class);
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Path mFile;
    private final Consumer<Boolean> mApply;
    private State mState = new State(true, false);

    public PagingToneSettingsService(Path file, Consumer<Boolean> apply)
    {
        mFile = file.toAbsolutePath().normalize();
        mApply = Objects.requireNonNull(apply);
        try
        {
            if(!Files.notExists(mFile, LinkOption.NOFOLLOW_LINKS))
            {
                if(!Files.isRegularFile(mFile, LinkOption.NOFOLLOW_LINKS) || Files.size(mFile) > 4096)
                    throw new IOException("Invalid paging-tone settings");
                JsonNode value = JSON.readTree(Files.readAllBytes(mFile));
                if(value == null || !value.isObject() || value.size() != 2 ||
                    !value.path("version").isInt() || value.path("version").intValue() != 1 ||
                    !value.path("enabled").isBoolean()) throw new IOException("Invalid paging-tone settings");
                mState = new State(value.path("enabled").booleanValue(), false);
            }
        }
        catch(IOException | RuntimeException exception)
        {
            //Preserve a potential saved Off choice when the preference cannot be read.
            mState = new State(false, true);
            LOG.warn("Paging-tone setting is unavailable; detection is off until a setting is saved");
        }
        mApply.accept(mState.enabled());
    }

    public synchronized Snapshot snapshot()
    {
        return new Snapshot(revision(mState), mState.enabled(), mState.unavailable() ? "unavailable" : "ready");
    }

    /** A failed write preserves the current runtime selection. Saving also repairs an unavailable preference. */
    public synchronized UpdateResult setEnabled(String expectedRevision, boolean enabled) throws IOException
    {
        if(!revision(mState).equals(expectedRevision)) return new UpdateResult(false, snapshot());
        State next = new State(enabled, false);
        if(!next.equals(mState))
        {
            Files.createDirectories(mFile.getParent());
            Path temporary = Files.createTempFile(mFile.getParent(), ".paging-tones-", ".tmp");
            try
            {
                Files.write(temporary, JSON.writeValueAsBytes(Map.of("version", 1, "enabled", enabled)));
                try(FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
                Files.move(temporary, mFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            finally { Files.deleteIfExists(temporary); }
            mApply.accept(enabled);
            mState = next;
        }
        return new UpdateResult(true, snapshot());
    }

    private static String revision(State state)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                ("paging-tones-v1:" + state.enabled() + ":" + state.unavailable()).getBytes(StandardCharsets.UTF_8)));
        }
        catch(NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private record State(boolean enabled, boolean unavailable) {}
    public record Snapshot(String revision, boolean enabled, String status) {}
    public record UpdateResult(boolean updated, Snapshot snapshot) {}
}
