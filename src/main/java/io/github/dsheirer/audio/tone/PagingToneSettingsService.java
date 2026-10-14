/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.audio.tone;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.database.settings.ReceiverSettingsSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Receiver-wide, portable paging-analysis preference; persistence never runs on an audio producer. */
public final class PagingToneSettingsService
{
    private static final Logger LOG = LoggerFactory.getLogger(PagingToneSettingsService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ApplicationSettingsStore mSettingsStore;
    private final Consumer<Boolean> mApply;
    private State mState = new State(false, true);

    public PagingToneSettingsService(ApplicationSettingsStore settingsStore, Consumer<Boolean> apply)
    {
        mSettingsStore = Objects.requireNonNull(settingsStore);
        mApply = Objects.requireNonNull(apply);
        try
        {
            String json = mSettingsStore.loadJson(ReceiverSettingsSchema.PAGING_TONES_KEY, 256)
                .orElseThrow(() -> new IOException("Paging-tone setting is missing"));
            ReceiverSettingsSchema.validateJson(ReceiverSettingsSchema.PAGING_TONES_KEY, json);
            mState = new State(JSON.readValue(json, Settings.class).enabled(), false);
        }
        catch(IOException | SQLException | RuntimeException exception)
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
            try
            {
                mSettingsStore.save(ReceiverSettingsSchema.PAGING_TONES_KEY, new Settings(enabled));
            }
            catch(IOException | SQLException | RuntimeException exception)
            {
                throw new IOException("Paging-tone setting could not be saved");
            }
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
    public record Settings(boolean enabled) {}
    public record Snapshot(String revision, boolean enabled, String status) {}
    public record UpdateResult(boolean updated, Snapshot snapshot) {}
}
