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
        long unknown = DatabaseMigrationEffect.UNKNOWN_COUNT;
        return effects(unknown, unknown, unknown, unknown);
    }

    @Override
    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        MigrationInput input = inspect(connection);
        return effects(input.users().size(), input.defaultedUsers(),
            input.portablePreferences().removedSettings(), input.portablePreferences().resetRows());
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        MigrationInput input = inspect(connection);
        long updatedAt = System.currentTimeMillis();

        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE web_user
            SET preferences_json = ?, preferences_revision = ?, updated_at_ms = ?
            WHERE id = ?
            """))
        {
            for(UserPreferenceUpdate user: input.users())
            {
                statement.setString(1, user.targetJson());
                statement.setLong(2, user.targetRevision());
                statement.setLong(3, updatedAt);
                statement.setLong(4, user.id());

                if(statement.executeUpdate() != 1)
                {
                    throw new SQLException("Unable to update web user preferences during format-8-to-9: user " +
                        user.id());
                }
            }
        }

        PortablePreferenceUpdate portable = input.portablePreferences();
        if(portable.targetJson() != null && !portable.targetJson().equals(portable.sourceJson()))
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                UPDATE application_settings
                SET settings_json = ?, updated_at_ms = ?
                WHERE key = ?
                """))
            {
                statement.setString(1, portable.targetJson());
                statement.setLong(2, updatedAt);
                statement.setString(3, PORTABLE_PREFERENCES_KEY);

                if(statement.executeUpdate() != 1)
                {
                    throw new SQLException("Unable to update portable preferences during format-8-to-9");
                }
            }
        }
    }

    private static MigrationInput inspect(Connection connection) throws SQLException
    {
        requireSourceFormat(connection);
        PortablePreferenceUpdate portable = inspectPortablePreferences(connection);
        List<UserPreferenceUpdate> users = new ArrayList<>();
        int defaultedUsers = 0;

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id,
                   CASE WHEN typeof(preferences_json)='text'
                              AND length(CAST(preferences_json AS BLOB)) <= 131072
                        THEN preferences_json END AS preferences_json,
                   preferences_revision
            FROM web_user
            ORDER BY id
            """); ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                long id = resultSet.getLong("id");
                String sourceJson = resultSet.getString("preferences_json");
                long sourceRevision = resultSet.getLong("preferences_revision");
                boolean revisionRecovered = sourceRevision <= 0 || sourceRevision == Long.MAX_VALUE;
                long targetRevision = revisionRecovered ? 1 : sourceRevision + 1;
                String targetJson;
                boolean defaulted = revisionRecovered;

                try
                {
                    targetJson = Format9WebUserPreferencesCodec.migrateFromFormat8(sourceJson,
                        portable.retainLastCallOnIdleRows(), portable.clearVoiceQualityWhenIdle());
                }
                catch(IOException | RuntimeException exception)
                {
                    targetJson = defaultPreferences();
                    defaulted = true;
                }
                if(defaulted)
                {
                    defaultedUsers++;
                }
                users.add(new UserPreferenceUpdate(id, targetJson, targetRevision));
            }
        }

        return new MigrationInput(List.copyOf(users), defaultedUsers, portable);
    }

    private static PortablePreferenceUpdate inspectPortablePreferences(Connection connection) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT CASE WHEN typeof(settings_json)='text'
                              AND length(CAST(settings_json AS BLOB)) <= 4194304
                        THEN settings_json END AS settings_json
            FROM application_settings
            WHERE key = ?
            """))
        {
            statement.setString(1, PORTABLE_PREFERENCES_KEY);
            try(ResultSet resultSet = statement.executeQuery())
            {
                if(!resultSet.next())
                {
                    return new PortablePreferenceUpdate(null, null, false, false, 0, 0);
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
                    int reset = 0;
                    ObjectNode nowPlaying = null;
                    if(node instanceof ObjectNode object)
                    {
                        nowPlaying = object;
                    }
                    else if(node != null)
                    {
                        root.remove(NOW_PLAYING_NODE);
                        reset++;
                    }

                    boolean retain = false;
                    boolean clear = false;
                    int removed = 0;
                    if(nowPlaying != null)
                    {
                        if(nowPlaying.has(RETAIN_IDLE_CALL_DETAILS_KEY))
                        {
                            removed++;
                            try
                            {
                                retain = readBoolean(nowPlaying, RETAIN_IDLE_CALL_DETAILS_KEY);
                            }
                            catch(IOException | RuntimeException ignored)
                            {
                                reset++;
                            }
                            nowPlaying.remove(RETAIN_IDLE_CALL_DETAILS_KEY);
                        }
                        if(nowPlaying.has(CLEAR_VOICE_QUALITY_KEY))
                        {
                            removed++;
                            try
                            {
                                clear = readBoolean(nowPlaying, CLEAR_VOICE_QUALITY_KEY);
                            }
                            catch(IOException | RuntimeException ignored)
                            {
                                reset++;
                            }
                            nowPlaying.remove(CLEAR_VOICE_QUALITY_KEY);
                        }
                    }

                    String targetJson = removed == 0 && reset == 0 ? sourceJson :
                        STRICT_MAPPER.writeValueAsString(root);
                    return new PortablePreferenceUpdate(sourceJson, targetJson, retain, clear, removed, reset);
                }
                catch(IOException | RuntimeException exception)
                {
                    return new PortablePreferenceUpdate(sourceJson, "{}", false, false, 0, 1);
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

    private static String defaultPreferences() throws SQLException
    {
        try
        {
            return Format9WebUserPreferencesCodec.defaults();
        }
        catch(IOException | RuntimeException exception)
        {
            throw new SQLException("Unable to create default version-4 browser preferences", exception);
        }
    }

    private static List<DatabaseMigrationEffect> effects(long userCount, long defaultedUserCount,
                                                          long removedSettingCount, long resetPortableRows)
    {
        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "per-user Live presentation settings", userCount,
                "Upgrade each exact version-3 browser preference document to version 4, default active-channel " +
                    "filtering off, copy the two former shared Live display choices to every account, and increment " +
                    "each preference revision"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "unusable per-user browser preferences", defaultedUserCount,
                "Replace only malformed or oversized user preference documents with version-4 defaults"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "obsolete shared Live presentation settings", removedSettingCount,
                "Remove retain-idle-call-details and clear-voice-quality settings from portable Java preferences " +
                    "while preserving traffic-grant age-out, the site-settings revision, and every unrelated value"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "unusable portable browser preferences", resetPortableRows,
                "Discard malformed shared-presentation preferences; replace only a wholly unreadable portable " +
                    "document with an empty bounded document"));
    }

    private static void requireSourceFormat(Connection connection) throws SQLException
    {
        DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspectForMigration(connection);
        if(detected.version() != 8)
        {
            throw new SQLException("Migration step format-8-to-9 requires exact source format 8; found " +
                detected.version() + " [" + detected.id() + "]");
        }
    }

    private record MigrationInput(List<UserPreferenceUpdate> users, int defaultedUsers,
                                  PortablePreferenceUpdate portablePreferences)
    {
    }

    private record UserPreferenceUpdate(long id, String targetJson, long targetRevision)
    {
    }

    private record PortablePreferenceUpdate(String sourceJson, String targetJson,
                                            boolean retainLastCallOnIdleRows, boolean clearVoiceQualityWhenIdle,
                                            int removedSettings, int resetRows)
    {
    }
}
