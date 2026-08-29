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
                    throw new SQLException("Web user preferences changed after format-6-to-7 preflight: user " +
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
                    throw new SQLException("Portable preferences changed after format-6-to-7 preflight");
                }
            }
        }
    }

    private static MigrationInput inspect(Connection connection) throws SQLException
    {
        requireSourceFormat(connection);
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
                    throw new SQLException("Refusing format-6-to-7 migration because web user " + id +
                        " has an exhausted preference revision", exception);
                }

                try
                {
                    users.add(new UserPreferenceUpdate(id, sourceJson, sourceRevision,
                        Format7WebUserPreferencesCodec.migrateFromFormat6(sourceJson), targetRevision));
                }
                catch(Format7WebUserPreferencesCodec.SelectedScanListLimitException exception)
                {
                    throw new SQLException("Refusing format-6-to-7 migration because web user " + id +
                        " selected " + exception.selected() + " scan lists; format 7 supports at most " +
                        exception.maximum() + ". Reduce that user's selections in the previous build before " +
                        "migrating.", exception);
                }
                catch(IOException exception)
                {
                    throw new SQLException("Refusing format-6-to-7 migration because web user " + id +
                        " does not have an exact version-1 preference document", exception);
                }
            }
        }

        return new MigrationInput(List.copyOf(users), inspectPortablePreferences(connection));
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
                    return new PortablePreferenceUpdate(null, null, 0);
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
                    List<String> emptiedNodes = new ArrayList<>();
                    Iterator<Map.Entry<String,JsonNode>> nodes = root.fields();
                    while(nodes.hasNext())
                    {
                        Map.Entry<String,JsonNode> entry = nodes.next();
                        if(!(entry.getValue() instanceof ObjectNode preferences))
                        {
                            throw new IOException("Portable preference node must be an object: " + entry.getKey());
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
                    String targetJson = removed == 0 ? sourceJson : STRICT_MAPPER.writeValueAsString(root);
                    return new PortablePreferenceUpdate(sourceJson, targetJson, removed);
                }
                catch(IOException exception)
                {
                    throw new SQLException("Refusing format-6-to-7 migration because portable preferences are invalid",
                        exception);
                }
            }
        }
    }

    private static List<DatabaseMigrationEffect> effects(long userCount, long retiredSettingCount)
    {
        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "per-user browser preference documents", userCount,
                "Upgrade exact version-1 documents to version 2 with conversation grouping enabled and a " +
                    "four-call burst limit, incrementing each preference revision; refuse user-owned selections " +
                    "that exceed the version-2 limit of 16 scan lists"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "retired global browser-audio settings", retiredSettingCount,
                "Remove the five obsolete capacity keys from portable Java preferences while preserving every " +
                    "unrelated node and value"));
    }

    private static void requireSourceFormat(Connection connection) throws SQLException
    {
        DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);
        if(detected.version() != 6)
        {
            throw new SQLException("Migration step format-6-to-7 requires exact source format 6; found " +
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

    private record PortablePreferenceUpdate(String sourceJson, String targetJson, int removedSettings)
    {
    }
}
