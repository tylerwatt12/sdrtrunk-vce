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
import java.sql.Connection;
import java.sql.PreparedStatement;
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

    /** Saves only an explicitly removed sender, inside the caller's channel-deletion transaction. */
    public static void saveSenderRemoval(Connection connection, String senderId, Settings next) throws IOException
    {
        try
        {
            validate(next);
            Settings current;
            try(PreparedStatement query = connection.prepareStatement(
                "SELECT settings_json FROM application_settings WHERE key=?"))
            {
                query.setString(1, SETTINGS_KEY);
                try(var row = query.executeQuery())
                {
                    if(!row.next()) throw new IOException("Remote-link settings are missing");
                    String json = row.getString(1);
                    ReceiverSettingsSchema.validateJson(SETTINGS_KEY, json);
                    current = JSON.readValue(json, Settings.class);
                    validate(current);
                }
            }
            List<TrustedSender> remaining = current.trustedSenders().stream()
                .filter(sender -> !sender.senderId().equals(senderId)).toList();
            Settings expected = new Settings(current.revision() + 1, current.listenerEnabled(),
                current.bindAddress(), current.listenPort(), remaining, current.outbound());
            if(remaining.size() == current.trustedSenders().size() || !expected.equals(next))
                throw new IOException("Remote-link settings changed before sender removal");
            String json = JSON.writeValueAsString(next);
            ReceiverSettingsSchema.validateJson(SETTINGS_KEY, json);
            try(PreparedStatement update = connection.prepareStatement(
                "UPDATE application_settings SET settings_json=?, updated_at_ms=? WHERE key=?"))
            {
                update.setString(1, json);
                update.setLong(2, System.currentTimeMillis());
                update.setString(3, SETTINGS_KEY);
                if(update.executeUpdate() != 1) throw new IOException("Remote-link settings are missing");
            }
        }
        catch(IOException | SQLException | RuntimeException exception)
        {
            // Stored credentials and database diagnostics must not escape this boundary.
            throw new IOException("Remote-link sender could not be removed");
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

    /**
     * The saved autoAdopt preference is retained; automatic channel setup is unconditional.
     * revoked retains its legacy persisted meaning for supported database/import formats; removal drops the sender entry.
     */
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
