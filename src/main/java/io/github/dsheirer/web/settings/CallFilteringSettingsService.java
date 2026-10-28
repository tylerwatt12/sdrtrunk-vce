/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.settings;

import io.github.dsheirer.preference.call.CallFilteringSettings;
import io.github.dsheirer.preference.call.CallManagementPreference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.prefs.BackingStoreException;

/** Receiver-wide call filtering over its authoritative preference owner. */
public final class CallFilteringSettingsService
{
    private final CallManagementPreference mCalls;

    public CallFilteringSettingsService(CallManagementPreference calls)
    {
        mCalls = Objects.requireNonNull(calls, "Call management preference cannot be null");
    }

    public Snapshot snapshot()
    {
        CallFilteringSettings settings = mCalls.getCallFilteringSettings();
        return new Snapshot(revision(settings), settings.enabled(), settings.minimumUsableVoicePercent());
    }

    /** Validates and replaces both fields together when the current saved policy still matches. */
    public ReplaceResult replace(String expectedRevision, CallFilteringSettings settings) throws BackingStoreException
    {
        Objects.requireNonNull(settings, "Call filtering settings cannot be null");
        synchronized(mCalls)
        {
            Snapshot current = snapshot();
            if(!current.revision().equals(expectedRevision)) return new ReplaceResult(false, current);
            mCalls.setCallFilteringSettings(settings);
            return new ReplaceResult(true, snapshot());
        }
    }

    private static String revision(CallFilteringSettings settings)
    {
        String canonical = settings.enabled() + "\u0000" +
            (settings.minimumUsableVoicePercent() != null ? settings.minimumUsableVoicePercent().toString() : "");
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        }
        catch(NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Snapshot(String revision, boolean enabled, Integer minimumUsableVoicePercent) { }
    public record ReplaceResult(boolean updated, Snapshot snapshot) { }
}
