/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.web.settings.WebUserPreferences;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;

/**
 * Carries the formerly shared Alpha browser presentation values across migration until setup creates their first
 * explicit owner. The format-4-to-5 step deliberately leaves only these already-existing legacy fields in place
 * when no web account exists; this class consumes them after the primary administrator is persisted.
 */
final class InitialAdminPreferenceHandoff
{
    private static final String DISPLAY_KEY = "web.display.v1";
    private static final String PORTABLE_PREFERENCES_KEY = "portable_java_preferences_v1";
    private static final String NOW_PLAYING_NODE = "user/io/github/dsheirer/preference/nowplaying";
    private static final String SHOW_CONTROL_KEY = "show.control.decode.quality";
    private static final String SHOW_VOICE_KEY = "show.voice.decode.quality";
    private static final String DISPLAY_MODE_KEY = "decode.quality.display.mode";
    private static final String ROW_LIMIT_KEY = "live.detail.matching.row.limit";
    private static final Set<String> DISPLAY_FIELDS = Set.of("format_version", "show_encryption_details");
    private static final Set<String> MOVED_KEYS =
        Set.of(SHOW_CONTROL_KEY, SHOW_VOICE_KEY, DISPLAY_MODE_KEY, ROW_LIMIT_KEY);
    private static final int MAXIMUM_PORTABLE_PREFERENCES_BYTES = 4_194_304;
    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

    private InitialAdminPreferenceHandoff()
    {
    }

    static Optional<WebUserPreferences> read(Path databasePath) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(databasePath))
        {
            Optional<String> displayStored = setting(connection, DISPLAY_KEY);
            Optional<String> portableStored = setting(connection, PORTABLE_PREFERENCES_KEY);
            boolean encryption = true;
            boolean control = true;
            boolean voice = true;
            String mode = "percentage";
            int rowLimit = WebUserPreferences.DEFAULT_LIVE_DETAIL_ROW_LIMIT;
            boolean present = false;

            if(displayStored.isPresent())
            {
                JsonNode display = parseObject(displayStored.get(), DISPLAY_KEY, 4096);
                if(!DISPLAY_FIELDS.equals(fieldNames(display)) || !display.path("format_version").isIntegralNumber() ||
                    display.path("format_version").intValue() != 1 ||
                    !display.path("show_encryption_details").isBoolean())
                {
                    throw new IOException("Pending initial administrator display settings are malformed");
                }
                encryption = display.path("show_encryption_details").booleanValue();
                present = true;
            }

            if(portableStored.isPresent())
            {
                JsonNode root = parseObject(portableStored.get(), PORTABLE_PREFERENCES_KEY,
                    MAXIMUM_PORTABLE_PREFERENCES_BYTES);
                JsonNode nowPlaying = root.get(NOW_PLAYING_NODE);
                if(nowPlaying != null)
                {
                    if(!nowPlaying.isObject())
                    {
                        throw new IOException("Pending initial administrator preference node is malformed");
                    }
                    if(nowPlaying.has(SHOW_CONTROL_KEY))
                    {
                        control = parseBoolean(nowPlaying.get(SHOW_CONTROL_KEY), SHOW_CONTROL_KEY);
                        present = true;
                    }
                    if(nowPlaying.has(SHOW_VOICE_KEY))
                    {
                        voice = parseBoolean(nowPlaying.get(SHOW_VOICE_KEY), SHOW_VOICE_KEY);
                        present = true;
                    }
                    if(nowPlaying.has(DISPLAY_MODE_KEY))
                    {
                        mode = switch(requireText(nowPlaying.get(DISPLAY_MODE_KEY), DISPLAY_MODE_KEY))
                        {
                            case "PERCENTAGE" -> "percentage";
                            case "DETAILED" -> "detailed";
                            default -> throw new IOException(
                                "Pending initial administrator display mode is invalid");
                        };
                        present = true;
                    }
                    if(nowPlaying.has(ROW_LIMIT_KEY))
                    {
                        rowLimit = parseCanonicalInteger(requireText(nowPlaying.get(ROW_LIMIT_KEY), ROW_LIMIT_KEY),
                            ROW_LIMIT_KEY);
                        if(rowLimit < WebUserPreferences.MINIMUM_LIVE_DETAIL_ROW_LIMIT ||
                            rowLimit > WebUserPreferences.MAXIMUM_LIVE_DETAIL_ROW_LIMIT)
                        {
                            throw new IOException("Pending initial administrator row limit is outside its range");
                        }
                        present = true;
                    }
                }
            }

            return present ? Optional.of(WebUserPreferences.defaults(encryption, control, voice, mode, rowLimit)) :
                Optional.empty();
        }
    }

    /** Removes the legacy carrier only after the administrator row has safely received the converted preferences. */
    static void clear(Path databasePath) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(databasePath))
        {
            connection.setAutoCommit(false);
            try
            {
                Optional<String> portableStored = setting(connection, PORTABLE_PREFERENCES_KEY);
                if(portableStored.isPresent())
                {
                    ObjectNode root = (ObjectNode)parseObject(portableStored.get(), PORTABLE_PREFERENCES_KEY,
                        MAXIMUM_PORTABLE_PREFERENCES_BYTES);
                    JsonNode nowPlaying = root.get(NOW_PLAYING_NODE);
                    boolean changed = false;
                    if(nowPlaying != null)
                    {
                        if(!nowPlaying.isObject())
                        {
                            throw new IOException("Pending initial administrator preference node is malformed");
                        }
                        ObjectNode values = (ObjectNode)nowPlaying;
                        for(String key: MOVED_KEYS)
                        {
                            changed |= values.remove(key) != null;
                        }
                    }
                    if(changed)
                    {
                        try(PreparedStatement statement = connection.prepareStatement("""
                            UPDATE application_settings SET settings_json=?, updated_at_ms=? WHERE key=?
                            """))
                        {
                            statement.setString(1, STRICT_MAPPER.writeValueAsString(root));
                            statement.setLong(2, System.currentTimeMillis());
                            statement.setString(3, PORTABLE_PREFERENCES_KEY);
                            if(statement.executeUpdate() != 1)
                            {
                                throw new SQLException("Pending initial administrator preferences changed concurrently");
                            }
                        }
                    }
                }
                try(PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM application_settings WHERE key=?"))
                {
                    statement.setString(1, DISPLAY_KEY);
                    statement.executeUpdate();
                }
                connection.commit();
            }
            catch(IOException | SQLException | RuntimeException exception)
            {
                try
                {
                    connection.rollback();
                }
                catch(SQLException rollbackFailure)
                {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
            finally
            {
                connection.setAutoCommit(true);
            }
        }
    }

    private static Optional<String> setting(Connection connection, String key) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT settings_json FROM application_settings WHERE key=?"))
        {
            statement.setString(1, key);
            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? Optional.ofNullable(resultSet.getString(1)) : Optional.empty();
            }
        }
    }

    private static JsonNode parseObject(String json, String label, int maximumBytes) throws IOException
    {
        if(json == null || json.getBytes(StandardCharsets.UTF_8).length > maximumBytes)
        {
            throw new IOException(label + " is missing or exceeds its handoff bound");
        }
        JsonNode root = STRICT_MAPPER.readTree(json);
        if(root == null || !root.isObject())
        {
            throw new IOException(label + " must be a JSON object");
        }
        return root;
    }

    private static Set<String> fieldNames(JsonNode object)
    {
        Set<String> names = new java.util.HashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }

    private static boolean parseBoolean(JsonNode node, String label) throws IOException
    {
        return switch(requireText(node, label))
        {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IOException("Pending initial administrator boolean is invalid: " + label);
        };
    }

    private static String requireText(JsonNode node, String label) throws IOException
    {
        if(node == null || !node.isTextual())
        {
            throw new IOException("Pending initial administrator preference must be text: " + label);
        }
        return node.textValue();
    }

    private static int parseCanonicalInteger(String value, String label) throws IOException
    {
        try
        {
            int parsed = Integer.parseInt(value);
            if(!Integer.toString(parsed).equals(value))
            {
                throw new IOException("Pending initial administrator preference must be a canonical integer: " +
                    label);
            }
            return parsed;
        }
        catch(NumberFormatException exception)
        {
            throw new IOException("Pending initial administrator preference is not an integer: " + label, exception);
        }
    }
}
