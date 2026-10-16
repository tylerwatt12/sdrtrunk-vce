/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.settings;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.remote.RemoteLinkSettingsStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Active format-56 receiver settings stored as distinct typed application_settings rows. */
public final class ReceiverSettingsSchema
{
    public static final String REMOTE_LINKS_KEY = "remote.links";
    public static final String PAGING_TONES_KEY = "audio.paging_tones";
    public static final List<String> KEYS = List.of(REMOTE_LINKS_KEY, PAGING_TONES_KEY);
    private static final int MAXIMUM_REMOTE_BYTES = 256_000;
    private static final int MAXIMUM_AUDIO_BYTES = 256;
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private ReceiverSettingsSchema() {}

    /** Fresh creation and the bundled adjacent migrator are the only callers permitted to install defaults. */
    public static int initializeMissing(Connection connection) throws SQLException
    {
        int missing = countMissing(connection);
        try(PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO application_settings(key, settings_json, updated_at_ms)
            SELECT ?, ?, ? WHERE NOT EXISTS(SELECT 1 FROM application_settings WHERE key=?)
            """))
        {
            for(String key: KEYS)
            {
                insert.setString(1, key);
                insert.setString(2, defaultJson(key));
                insert.setLong(3, System.currentTimeMillis());
                insert.setString(4, key);
                insert.executeUpdate();
            }
        }
        validate(connection);
        return missing;
    }

    /** Validates every existing value before the migrator adds any absent rows. Never repairs saved choices. */
    public static int countMissing(Connection connection) throws SQLException
    {
        int missing = 0;
        for(String key: KEYS)
        {
            if(read(connection, key) == null) missing++;
        }
        return missing;
    }

    /** Strict startup admission: existing databases must already contain both valid settings. */
    public static void validate(Connection connection) throws SQLException
    {
        for(String key: KEYS)
        {
            if(read(connection, key) == null) throw invalid(key);
        }
    }

    public static String defaultJson(String key) throws SQLException
    {
        try
        {
            return switch(key)
            {
                case REMOTE_LINKS_KEY -> JSON.writeValueAsString(RemoteLinkSettingsStore.Settings.defaults());
                case PAGING_TONES_KEY -> "{\"enabled\":true}";
                default -> throw new IllegalArgumentException("Unknown receiver settings key");
            };
        }
        catch(IOException | RuntimeException failure)
        {
            throw invalid(key);
        }
    }

    /** Value-free validation shared by database admission and the owning settings services. */
    public static void validateJson(String key, String json) throws SQLException
    {
        try
        {
            if(json == null || json.getBytes(StandardCharsets.UTF_8).length > maximumBytes(key)) throw invalid(key);
            JsonNode value = JSON.readTree(json);
            if(REMOTE_LINKS_KEY.equals(key))
            {
                fields(value, "revision", "listenerEnabled", "bindAddress", "listenPort", "trustedSenders", "outbound");
                integer(value, "revision");
                bool(value, "listenerEnabled");
                text(value, "bindAddress", false);
                integer(value, "listenPort");
                if(!value.path("trustedSenders").isArray()) throw invalid(key);
                for(JsonNode sender: value.path("trustedSenders"))
                {
                    fields(sender, "senderId", "displayName", "secret", "autoAdopt", "defaultAliasListId", "pairedAtMs", "revoked");
                    text(sender, "senderId", false);
                    text(sender, "displayName", false);
                    text(sender, "secret", true);
                    bool(sender, "autoAdopt");
                    if(!sender.path("defaultAliasListId").isNull()) integer(sender, "defaultAliasListId");
                    integer(sender, "pairedAtMs");
                    bool(sender, "revoked");
                }
                JsonNode outbound = value.path("outbound");
                fields(outbound, "enabled", "host", "port", "senderId", "secret", "exportedChannelIds");
                bool(outbound, "enabled");
                text(outbound, "host", false);
                integer(outbound, "port");
                text(outbound, "senderId", true);
                text(outbound, "secret", true);
                if(!outbound.path("exportedChannelIds").isArray()) throw invalid(key);
                for(JsonNode channel: outbound.path("exportedChannelIds"))
                {
                    if(!channel.isTextual()) throw invalid(key);
                }
                RemoteLinkSettingsStore.validate(JSON.treeToValue(value, RemoteLinkSettingsStore.Settings.class));
            }
            else if(PAGING_TONES_KEY.equals(key))
            {
                fields(value, "enabled");
                bool(value, "enabled");
            }
            else throw invalid(key);
        }
        catch(IOException | RuntimeException | SQLException failure)
        {
            //Parser and mapping errors may quote credentials; never attach their cause or message.
            throw invalid(key);
        }
    }

    private static String read(Connection connection, String key) throws SQLException
    {
        try(PreparedStatement query = connection.prepareStatement("""
            SELECT CASE WHEN typeof(settings_json)='text' AND length(CAST(settings_json AS BLOB))<=?
                        THEN settings_json END AS settings_json,
                   typeof(updated_at_ms) AS timestamp_type, updated_at_ms
            FROM application_settings WHERE key=?
            """))
        {
            query.setInt(1, maximumBytes(key));
            query.setString(2, key);
            try(ResultSet row = query.executeQuery())
            {
                if(!row.next()) return null;
                if(!"integer".equals(row.getString("timestamp_type")) || row.getLong("updated_at_ms") <= 0)
                    throw invalid(key);
                String json = row.getString("settings_json");
                validateJson(key, json);
                return json;
            }
        }
    }

    private static int maximumBytes(String key)
    {
        return REMOTE_LINKS_KEY.equals(key) ? MAXIMUM_REMOTE_BYTES : MAXIMUM_AUDIO_BYTES;
    }

    private static void fields(JsonNode value, String... names) throws SQLException
    {
        if(value == null || !value.isObject()) throw new SQLException("Invalid receiver settings object");
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if(!actual.equals(Set.of(names))) throw new SQLException("Invalid receiver settings fields");
    }

    private static void integer(JsonNode value, String name) throws SQLException
    {
        if(!value.path(name).isIntegralNumber() || !value.path(name).canConvertToLong())
            throw new SQLException("Invalid receiver settings number");
    }

    private static void bool(JsonNode value, String name) throws SQLException
    {
        if(!value.path(name).isBoolean()) throw new SQLException("Invalid receiver settings boolean");
    }

    private static void text(JsonNode value, String name, boolean nullable) throws SQLException
    {
        if(!value.path(name).isTextual() && !(nullable && value.path(name).isNull()))
            throw new SQLException("Invalid receiver settings text");
    }

    private static SQLException invalid(String key)
    {
        String label = switch(key)
        {
            case REMOTE_LINKS_KEY -> "Remote Links";
            case PAGING_TONES_KEY -> "Paging-tone";
            default -> "Receiver";
        };
        return new SQLException(label + " settings are missing or invalid; restore a valid database before starting");
    }
}
