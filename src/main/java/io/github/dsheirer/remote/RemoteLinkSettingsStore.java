/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.database.settings.ReceiverSettingsSchema;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Receiver-owned remote-link settings in the application database. These settings contain link credentials; never
 * serialize a Settings instance into diagnostics, an HTTP response, or a log.
 */
public final class RemoteLinkSettingsStore
{
    public static final String DEFAULT_BIND_ADDRESS = "0.0.0.0";
    public static final String SETTINGS_KEY = ReceiverSettingsSchema.REMOTE_LINKS_KEY;
    private static final int MAXIMUM_SETTINGS_BYTES = 256_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ApplicationSettingsStore mSettings;

    public RemoteLinkSettingsStore(Path databasePath)
    {
        mSettings = new ApplicationSettingsStore(databasePath);
    }

    public synchronized Settings load() throws IOException
    {
        try
        {
            String json = mSettings.loadJson(SETTINGS_KEY, MAXIMUM_SETTINGS_BYTES)
                .orElseThrow(() -> new IOException("Remote-link settings are missing"));
            ReceiverSettingsSchema.validateJson(SETTINGS_KEY, json);
            Settings settings = JSON.readValue(json, Settings.class);
            validate(settings);
            return settings;
        }
        catch(IOException | SQLException | RuntimeException exception)
        {
            // Jackson diagnostics can quote a malformed credential value. Never put its cause in receiver logs.
            throw new IOException("Remote-link settings could not be loaded; restore a valid application database");
        }
    }

    public synchronized void save(Settings settings) throws IOException
    {
        validate(settings);
        try
        {
            //Character bounds alone do not bound UTF-8 size. Never commit a value startup would refuse.
            ReceiverSettingsSchema.validateJson(SETTINGS_KEY, JSON.writeValueAsString(settings));
            //Commit credentials before the caller can replace its runtime settings snapshot.
            mSettings.save(SETTINGS_KEY, settings);
        }
        catch(IOException | SQLException | RuntimeException exception)
        {
            //Database and serialization diagnostics may contain credential material. Keep the boundary cause-free.
            throw new IOException("Remote-link settings could not be saved");
        }
    }

    public static void validate(Settings settings) throws IOException
    {
        if(settings == null || settings.revision() < 1L || !validPort(settings.listenPort()) ||
            settings.bindAddress() == null || settings.bindAddress().length() > 255 ||
            settings.trustedSenders().size() > 256)
        {
            throw new IOException("Remote-link settings are invalid");
        }
        Set<String> ids = new HashSet<>();
        for(TrustedSender sender: settings.trustedSenders())
        {
            if(sender == null || !validUuid(sender.senderId()) || !ids.add(sender.senderId()) ||
                sender.displayName() == null || sender.displayName().isBlank() ||
                sender.displayName().length() > 120 || sender.pairedAtMs() < 0L ||
                !sender.revoked() && (sender.secret() == null || sender.secret().isBlank()) ||
                sender.secret() != null && sender.secret().length() > 256 ||
                sender.defaultAliasListId() != null && sender.defaultAliasListId() <= 0L)
            {
                throw new IOException("Remote-link settings are invalid");
            }
        }
        Outbound outbound = settings.outbound();
        if(outbound == null || !validPort(outbound.port()) || outbound.host() == null ||
            outbound.host().length() > 255 ||
            outbound.exportedChannelIds().size() > RemoteWireMessages.MAXIMUM_FEEDS ||
            outbound.enabled() && (outbound.host().isBlank() || !validUuid(outbound.senderId()) ||
                outbound.secret() == null || outbound.secret().isBlank()) ||
            outbound.senderId() != null && !validUuid(outbound.senderId()) ||
            outbound.secret() != null && outbound.secret().length() > 256)
        {
            throw new IOException("Remote-link settings are invalid");
        }
        Set<String> exports = new HashSet<>();
        for(String id: outbound.exportedChannelIds())
        {
            if(!validUuid(id) || !exports.add(id))
            {
                throw new IOException("Remote-link settings are invalid");
            }
        }
    }

    private static boolean validPort(int port)
    {
        return port >= 1 && port <= 65_535;
    }

    private static boolean validUuid(String value)
    {
        if(value == null) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch(IllegalArgumentException ignored) { return false; }
    }

    /** The saved autoAdopt preference is retained; automatic channel setup is unconditional. */
    public record TrustedSender(String senderId, String displayName, String secret, boolean autoAdopt,
                                Long defaultAliasListId, long pairedAtMs, boolean revoked)
    {
        @Override
        public String toString()
        {
            return "TrustedSender[senderId=" + senderId + ", secret=REDACTED, revoked=" + revoked + "]";
        }
    }

    public record Outbound(boolean enabled, String host, int port, String senderId, String secret,
                           List<String> exportedChannelIds)
    {
        public Outbound
        {
            exportedChannelIds = exportedChannelIds != null ? List.copyOf(exportedChannelIds) : List.of();
        }

        public static Outbound disabled()
        {
            return new Outbound(false, "", 53_800, null, null, List.of());
        }

        @Override
        public String toString()
        {
            return "Outbound[enabled=" + enabled + ", senderId=" + senderId + ", secret=REDACTED]";
        }
    }

    public record Settings(long revision, boolean listenerEnabled, String bindAddress, int listenPort,
                           List<TrustedSender> trustedSenders, Outbound outbound)
    {
        public Settings
        {
            trustedSenders = trustedSenders != null ? List.copyOf(trustedSenders) : List.of();
            outbound = outbound != null ? outbound : Outbound.disabled();
        }

        public static Settings defaults()
        {
            return new Settings(1, false, DEFAULT_BIND_ADDRESS, 53_800, List.of(), Outbound.disabled());
        }

        @Override
        public String toString()
        {
            return "Settings[revision=" + revision + ", credentials=REDACTED]";
        }
    }
}
