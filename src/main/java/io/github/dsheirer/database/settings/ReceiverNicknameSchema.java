/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.settings;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded administrator-owned receiver nicknames, independent of mutable hardware configuration documents. */
public final class ReceiverNicknameSchema
{
    public static final String KEY = "receiver.nicknames";
    public static final int MAXIMUM_NICKNAMES = 512;
    public static final int MAXIMUM_BYTES = 256_000;
    public static final int MAXIMUM_NICKNAME_LENGTH = 80;
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private ReceiverNicknameSchema() {}

    /** Fresh creation and the adjacent Application Migrator are the only callers which install an absent row. */
    public static int initializeMissing(Connection connection) throws SQLException
    {
        validateIfPresent(connection);
        try(PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO application_settings(key,settings_json,updated_at_ms)
            SELECT ?, '{}', ? WHERE NOT EXISTS(SELECT 1 FROM application_settings WHERE key=?)
            """))
        {
            insert.setString(1, KEY);
            insert.setLong(2, System.currentTimeMillis());
            insert.setString(3, KEY);
            return insert.executeUpdate();
        }
    }

    public static void validate(Connection connection) throws SQLException
    {
        if(!validateIfPresent(connection)) throw invalid();
    }

    private static boolean validateIfPresent(Connection connection) throws SQLException
    {
        try(PreparedStatement query = connection.prepareStatement("""
            SELECT CASE WHEN typeof(settings_json)='text' AND length(CAST(settings_json AS BLOB))<=?
                        THEN settings_json END, typeof(updated_at_ms), updated_at_ms
            FROM application_settings WHERE key=?
            """))
        {
            query.setInt(1, MAXIMUM_BYTES);
            query.setString(2, KEY);
            try(ResultSet row = query.executeQuery())
            {
                if(!row.next()) return false;
                if(!"integer".equals(row.getString(2)) || row.getLong(3) <= 0) throw invalid();
                decode(row.getString(1));
                return true;
            }
        }
    }

    public static Map<String,String> decode(String json) throws SQLException
    {
        try
        {
            if(json == null || json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_BYTES) throw invalid();
            JsonNode root = JSON.readTree(json);
            if(root == null || !root.isObject() || root.size() > MAXIMUM_NICKNAMES) throw invalid();
            Map<String,String> values = new LinkedHashMap<>();
            var fields = root.fields();
            while(fields.hasNext())
            {
                var field = fields.next();
                requireReceiverId(field.getKey());
                if(!field.getValue().isTextual()) throw invalid();
                String value = field.getValue().textValue();
                if(value.isEmpty() || !value.equals(normalize(value))) throw invalid();
                values.put(field.getKey(), value);
            }
            return Map.copyOf(values);
        }
        catch(IOException | RuntimeException failure)
        {
            // Parser messages may contain supplied names or identifiers. Admission errors never include their data.
            throw invalid();
        }
    }

    public static String normalize(String nickname)
    {
        if(nickname == null) throw new IllegalArgumentException("Receiver nickname must be text");
        String value = nickname.strip();
        if(value.codePointCount(0, value.length()) > MAXIMUM_NICKNAME_LENGTH)
            throw new IllegalArgumentException("Receiver nickname must be 80 characters or fewer");
        for(int index = 0; index < value.length();)
        {
            int codepoint = value.codePointAt(index);
            if(Character.isISOControl(codepoint) || codepoint >= 0xD800 && codepoint <= 0xDFFF)
                throw new IllegalArgumentException("Receiver nickname must not contain control characters");
            index += Character.charCount(codepoint);
        }
        return value;
    }

    public static void requireReceiverId(String receiverId)
    {
        if(receiverId == null || !receiverId.matches("tuner-[0-9a-f]{32}"))
            throw new IllegalArgumentException("Receiver identifier is invalid");
    }

    private static SQLException invalid()
    {
        return new SQLException("Receiver nicknames are missing or invalid; restore valid settings before starting");
    }
}
