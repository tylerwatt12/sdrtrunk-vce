/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Moves the two remaining shared Live display choices into every user's presentation preferences. */
final class Format8To9DatabaseMigration implements DatabaseMigrationStep
{
    private static final String PORTABLE_PREFERENCES_KEY = "portable_java_preferences_v1";
    private static final String NOW_PLAYING_NODE = "user/io/github/dsheirer/preference/nowplaying";
    private static final String RETAIN_IDLE_CALL_DETAILS_KEY = "retain.idle.call.details";
    private static final String CLEAR_VOICE_QUALITY_KEY = "clear.voice.decode.quality.on.call.end";
    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Override
    public String id()
    {
        return "format-8-to-9";
    }

    @Override
    public String description()
    {
        return "Move shared Live display choices into per-user presentation settings";
    }

    @Override
    public int sourceVersion()
    {
        return 8;
    }

    @Override
    public int targetVersion()
    {
        return 9;
    }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return effects(DatabaseMigrationEffect.UNKNOWN_COUNT, DatabaseMigrationEffect.UNKNOWN_COUNT);
    }

    @Override
    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        MigrationInput input = inspect(connection);
        return effects(input.users().size(), input.portablePreferences().removedSettings());
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        MigrationInput input = inspect(connection);
        long updatedAt = System.currentTimeMillis();

        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE web_user
            SET preferences_json = ?, preferences_revision = ?, updated_at_ms = ?
            WHERE id = ? AND preferences_json = ? AND preferences_revision = ?
            """))
        {
            for(UserPreferenceUpdate user: input.users())
            {
                statement.setString(1, user.targetJson());
                statement.setLong(2, user.targetRevision());
                statement.setLong(3, updatedAt);
                statement.setLong(4, user.id());
                statement.setString(5, user.sourceJson());
                statement.setLong(6, user.sourceRevision());

                if(statement.executeUpdate() != 1)
                {
                    throw new SQLException("Web user preferences changed after format-8-to-9 preflight: user " +
                        user.id());
                }
            }
        }

        PortablePreferenceUpdate portable = input.portablePreferences();
        if(portable.removedSettings() > 0)
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                UPDATE application_settings
                SET settings_json = ?, updated_at_ms = ?
                WHERE key = ? AND settings_json = ?
                """))
            {
                statement.setString(1, portable.targetJson());
                statement.setLong(2, updatedAt);
                statement.setString(3, PORTABLE_PREFERENCES_KEY);
                statement.setString(4, portable.sourceJson());

                if(statement.executeUpdate() != 1)
                {
                    throw new SQLException("Portable preferences changed after format-8-to-9 preflight");
                }
            }
        }
    }

    private static MigrationInput inspect(Connection connection) throws SQLException
    {
        requireSourceFormat(connection);
        PortablePreferenceUpdate portable = inspectPortablePreferences(connection);
        List<UserPreferenceUpdate> users = new ArrayList<>();

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, preferences_json, preferences_revision
            FROM web_user
            ORDER BY id
            """); ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                long id = resultSet.getLong("id");
                String sourceJson = resultSet.getString("preferences_json");
                long sourceRevision = resultSet.getLong("preferences_revision");
                long targetRevision;

                try
                {
                    targetRevision = Math.incrementExact(sourceRevision);
                }
                catch(ArithmeticException exception)
                {
                    throw new SQLException("Refusing format-8-to-9 migration because web user " + id +
                        " has an exhausted preference revision", exception);
                }

                try
                {
                    users.add(new UserPreferenceUpdate(id, sourceJson, sourceRevision,
                        Format9WebUserPreferencesCodec.migrateFromFormat8(sourceJson,
                            portable.retainLastCallOnIdleRows(), portable.clearVoiceQualityWhenIdle()),
                        targetRevision));
                }
                catch(IOException exception)
                {
                    throw new SQLException("Refusing format-8-to-9 migration because web user " + id +
                        " does not have an exact version-3 preference document", exception);
                }
            }
        }

        return new MigrationInput(List.copyOf(users), portable);
    }

    private static PortablePreferenceUpdate inspectPortablePreferences(Connection connection) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT settings_json
            FROM application_settings
            WHERE key = ?
            """))
        {
            statement.setString(1, PORTABLE_PREFERENCES_KEY);
            try(ResultSet resultSet = statement.executeQuery())
            {
                if(!resultSet.next())
                {
                    return new PortablePreferenceUpdate(null, null, false, false, 0);
                }

                String sourceJson = resultSet.getString("settings_json");
                try
                {
                    JsonNode parsed = STRICT_MAPPER.readTree(sourceJson);
                    if(!(parsed instanceof ObjectNode root))
                    {
                        throw new IOException("Portable preferences must be an object");
                    }

                    JsonNode node = root.get(NOW_PLAYING_NODE);
                    if(node != null && !(node instanceof ObjectNode))
                    {
                        throw new IOException("Now-playing portable preferences must be an object");
                    }

                    ObjectNode nowPlaying = (ObjectNode)node;
                    boolean retain = readBoolean(nowPlaying, RETAIN_IDLE_CALL_DETAILS_KEY);
                    boolean clear = readBoolean(nowPlaying, CLEAR_VOICE_QUALITY_KEY);
                    int removed = 0;
                    if(nowPlaying != null)
                    {
                        if(nowPlaying.remove(RETAIN_IDLE_CALL_DETAILS_KEY) != null)
                        {
                            removed++;
                        }
                        if(nowPlaying.remove(CLEAR_VOICE_QUALITY_KEY) != null)
                        {
                            removed++;
                        }
                    }

                    String targetJson = removed == 0 ? sourceJson : STRICT_MAPPER.writeValueAsString(root);
                    return new PortablePreferenceUpdate(sourceJson, targetJson, retain, clear, removed);
                }
                catch(IOException exception)
                {
                    throw new SQLException("Refusing format-8-to-9 migration because portable preferences are invalid",
                        exception);
                }
            }
        }
    }

    private static boolean readBoolean(ObjectNode object, String key) throws IOException
    {
        if(object == null || !object.has(key))
        {
            return false;
        }

        String value = object.get(key).textValue();
        if("true".equals(value))
        {
            return true;
        }
        if("false".equals(value))
        {
            return false;
        }
        throw new IOException("Portable preference is not a canonical boolean: " + key);
    }

    private static List<DatabaseMigrationEffect> effects(long userCount, long removedSettingCount)
    {
        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "per-user Live presentation settings", userCount,
                "Upgrade each exact version-3 browser preference document to version 4, default active-channel " +
                    "filtering off, copy the two former shared Live display choices to every account, and increment " +
                    "each preference revision"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "obsolete shared Live presentation settings", removedSettingCount,
                "Remove retain-idle-call-details and clear-voice-quality settings from portable Java preferences " +
                    "while preserving traffic-grant age-out, the site-settings revision, and every unrelated value"));
    }

    private static void requireSourceFormat(Connection connection) throws SQLException
    {
        DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);
        if(detected.version() != 8)
        {
            throw new SQLException("Migration step format-8-to-9 requires exact source format 8; found " +
                detected.version() + " [" + detected.id() + "]");
        }
    }

    private record MigrationInput(List<UserPreferenceUpdate> users,
                                  PortablePreferenceUpdate portablePreferences)
    {
    }

    private record UserPreferenceUpdate(long id, String sourceJson, long sourceRevision,
                                        String targetJson, long targetRevision)
    {
    }

    private record PortablePreferenceUpdate(String sourceJson, String targetJson,
                                            boolean retainLastCallOnIdleRows, boolean clearVoiceQualityWhenIdle,
                                            int removedSettings)
    {
    }
}
