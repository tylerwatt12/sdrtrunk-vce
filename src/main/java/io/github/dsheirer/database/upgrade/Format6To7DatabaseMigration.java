/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Upgrades every complete per-user preference document and removes the retired global browser-audio settings. */
final class Format6To7DatabaseMigration implements DatabaseMigrationStep
{
    private static final String PORTABLE_PREFERENCES_KEY = "portable_java_preferences_v1";
    static final Set<String> RETIRED_WEB_AUDIO_KEYS = Set.of(
        "stats.web.call.maximum.listeners",
        "stats.web.call.maximum.selected.scan.lists",
        "stats.web.call.maximum.browser.queue.calls",
        "stats.web.call.maximum.cached.calls",
        "stats.web.call.maximum.cached.audio.mib");
    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Override
    public String id()
    {
        return "format-6-to-7";
    }

    @Override
    public String description()
    {
        return "Add per-user conversation playback settings and retire global browser-audio tuning";
    }

    @Override
    public int sourceVersion()
    {
        return 6;
    }

    @Override
    public int targetVersion()
    {
        return 7;
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
                    throw new SQLException("Unable to update web user preferences during format-6-to-7: user " +
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
                    throw new SQLException("Unable to update portable preferences during format-6-to-7");
                }
            }
        }
    }

    private static MigrationInput inspect(Connection connection) throws SQLException
    {
        requireSourceFormat(connection);
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
                    Format7WebUserPreferencesCodec.MigrationResult migration =
                        Format7WebUserPreferencesCodec.migrateFromFormat6BestEffort(sourceJson);
                    targetJson = migration.json();
                    defaulted |= migration.defaultedSelectedScanLists();
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

        return new MigrationInput(List.copyOf(users), defaultedUsers, inspectPortablePreferences(connection));
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
                    return new PortablePreferenceUpdate(null, null, 0, 0);
                }

                String sourceJson = resultSet.getString("settings_json");
                try
                {
                    JsonNode parsed = STRICT_MAPPER.readTree(sourceJson);
                    if(!(parsed instanceof ObjectNode root))
                    {
                        throw new IOException("Portable preferences must be an object");
                    }

                    int removed = 0;
                    int reset = 0;
                    List<String> emptiedNodes = new ArrayList<>();
                    List<String> malformedNodes = new ArrayList<>();
                    Iterator<Map.Entry<String,JsonNode>> nodes = root.fields();
                    while(nodes.hasNext())
                    {
                        Map.Entry<String,JsonNode> entry = nodes.next();
                        if(!(entry.getValue() instanceof ObjectNode preferences))
                        {
                            malformedNodes.add(entry.getKey());
                            reset++;
                            continue;
                        }

                        int removedFromNode = 0;
                        for(String key: RETIRED_WEB_AUDIO_KEYS)
                        {
                            if(preferences.remove(key) != null)
                            {
                                removed++;
                                removedFromNode++;
                            }
                        }

                        if(removedFromNode > 0 && preferences.isEmpty())
                        {
                            emptiedNodes.add(entry.getKey());
                        }
                    }
                    root.remove(emptiedNodes);
                    root.remove(malformedNodes);
                    String targetJson = removed == 0 && reset == 0 ? sourceJson :
                        STRICT_MAPPER.writeValueAsString(root);
                    return new PortablePreferenceUpdate(sourceJson, targetJson, removed, reset);
                }
                catch(IOException | RuntimeException exception)
                {
                    return new PortablePreferenceUpdate(sourceJson, "{}", 0, 1);
                }
            }
        }
    }

    private static String defaultPreferences() throws SQLException
    {
        try
        {
            return Format7WebUserPreferencesCodec.defaults();
        }
        catch(IOException | RuntimeException exception)
        {
            throw new SQLException("Unable to create default version-2 browser preferences", exception);
        }
    }

    private static List<DatabaseMigrationEffect> effects(long userCount, long defaultedUserCount,
                                                          long retiredSettingCount, long resetPortableRows)
    {
        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "per-user browser preference documents", userCount,
                "Upgrade exact version-1 documents to version 2 with conversation grouping enabled and a " +
                    "four-call burst limit and increment each usable preference revision"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "unusable per-user browser preferences", defaultedUserCount,
                "Replace only malformed, oversized, or unrepresentable user preference documents with version-2 defaults"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "retired global browser-audio settings", retiredSettingCount,
                "Remove the five obsolete capacity keys from portable Java preferences while preserving every " +
                    "unrelated node and value"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "unusable portable browser preferences", resetPortableRows,
                "Discard malformed portable preference nodes; replace only a wholly unreadable document with an " +
                    "empty bounded document"));
    }

    private static void requireSourceFormat(Connection connection) throws SQLException
    {
        DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspectForMigration(connection);
        if(detected.version() != 6)
        {
            throw new SQLException("Migration step format-6-to-7 requires exact source format 6; found " +
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

    private record PortablePreferenceUpdate(String sourceJson, String targetJson, int removedSettings, int resetRows)
    {
    }
}
