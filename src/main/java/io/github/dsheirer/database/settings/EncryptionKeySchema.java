/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.settings;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Voice keys are ordinary, unencrypted application configuration. */
public final class EncryptionKeySchema
{
    public static final String KEY = "voice.encryption_keys";
    public static final int MAXIMUM_KEYS = 10_000;
    public static final int MAXIMUM_BYTES = 4_194_304;
    private static final Set<String> FIELDS = Set.of("id", "label", "enabled", "protocol", "algorithm_id",
        "key_id", "key_hex", "scope");
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private EncryptionKeySchema() {}

    /** Only fresh creation and the adjacent Application Migrator install a missing setting. */
    public static int initializeMissing(Connection connection) throws SQLException
    {
        if(readIfPresent(connection) != null) return 0;
        try(var insert = connection.prepareStatement(
            "INSERT INTO application_settings(key,settings_json,updated_at_ms) VALUES (?, '[]', ?)"))
        {
            insert.setString(1, KEY);
            insert.setLong(2, System.currentTimeMillis());
            return insert.executeUpdate();
        }
    }

    public static void validate(Connection connection) throws SQLException
    {
        if(readIfPresent(connection) == null) throw invalid();
    }

    private static List<VoiceEncryptionKey> readIfPresent(Connection connection) throws SQLException
    {
        try(var query = connection.prepareStatement("""
            SELECT CASE WHEN typeof(settings_json)='text' AND length(CAST(settings_json AS BLOB))<=?
                        THEN settings_json END, typeof(updated_at_ms), updated_at_ms
            FROM application_settings WHERE key=?
            """))
        {
            query.setInt(1, MAXIMUM_BYTES);
            query.setString(2, KEY);
            try(var row = query.executeQuery())
            {
                if(!row.next()) return null;
                if(!"integer".equals(row.getString(2)) || row.getLong(3) <= 0) throw invalid();
                return decode(row.getString(1));
            }
        }
    }

    public static List<VoiceEncryptionKey> decode(String json) throws SQLException
    {
        try
        {
            if(json == null || json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_BYTES) throw invalid();
            JsonNode root = JSON.readTree(json);
            if(root == null || !root.isArray() || root.size() > MAXIMUM_KEYS) throw invalid();
            List<VoiceEncryptionKey> keys = new ArrayList<>(root.size());
            Set<String> ids = new HashSet<>();
            for(JsonNode node: root)
            {
                Set<String> fields = new HashSet<>();
                node.fieldNames().forEachRemaining(fields::add);
                if(!node.isObject() || !FIELDS.equals(fields)) throw invalid();
                String id = text(node, "id", false, 128);
                if(id.isBlank() || !ids.add(id) || !node.get("enabled").isBoolean()) throw invalid();
                VoiceEncryptionKey key = new VoiceEncryptionKey(id);
                key.setLabel(text(node, "label", true, 4096));
                key.setEnabled(node.get("enabled").booleanValue());
                key.setProtocol(VoiceEncryptionProtocol.valueOf(text(node, "protocol", false, 16)));
                key.setAlgorithmId(integer(node, "algorithm_id", 255));
                key.setKeyId(integer(node, "key_id", 65535));
                String keyHex = text(node, "key_hex", true, 4096);
                if(keyHex != null && !keyHex.isEmpty() &&
                    (!VoiceEncryptionKey.isValidHexKey(keyHex) ||
                        !keyHex.equals(VoiceEncryptionKey.normalizeKeyHex(keyHex)))) throw invalid();
                key.setKeyHex(keyHex);
                key.setScope(text(node, "scope", true, 4096));
                keys.add(key);
            }
            return List.copyOf(keys);
        }
        catch(IOException | RuntimeException failure)
        {
            // Parsing errors can include key material; do not retain their messages or causes.
            throw invalid();
        }
    }

    public static ArrayNode encode(Collection<VoiceEncryptionKey> keys) throws SQLException
    {
        if(keys == null || keys.size() > MAXIMUM_KEYS) throw invalid();
        ArrayNode array = JSON.createArrayNode();
        for(VoiceEncryptionKey key: keys)
        {
            if(key == null) throw invalid();
            var node = array.addObject();
            node.put("id", key.getId());
            node.put("label", key.getLabel());
            node.put("enabled", key.isEnabled());
            node.put("protocol", key.getProtocol().name());
            node.put("algorithm_id", key.getAlgorithmId());
            node.put("key_id", key.getKeyId());
            node.put("key_hex", key.getKeyHex());
            node.put("scope", key.getScope());
        }
        decode(array.toString());
        return array;
    }

    private static String text(JsonNode node, String field, boolean nullable, int maximum) throws SQLException
    {
        JsonNode value = node.get(field);
        if(nullable && value.isNull()) return null;
        if(!value.isTextual() || value.textValue().length() > maximum) throw invalid();
        return value.textValue();
    }

    private static int integer(JsonNode node, String field, int maximum) throws SQLException
    {
        JsonNode value = node.get(field);
        if(!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0 ||
            value.intValue() > maximum) throw invalid();
        return value.intValue();
    }

    private static SQLException invalid()
    {
        return new SQLException("Voice encryption keys are missing or invalid; restore valid settings before starting");
    }
}
