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

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Adds the bounded per-user list of disabled receiver-health alert codes. */
final class Format7To8DatabaseMigration implements DatabaseMigrationStep
{
    @Override
    public String id()
    {
        return "format-7-to-8";
    }

    @Override
    public String description()
    {
        return "Add per-user receiver-health alert visibility settings";
    }

    @Override
    public int sourceVersion()
    {
        return 7;
    }

    @Override
    public int targetVersion()
    {
        return 8;
    }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return effects(DatabaseMigrationEffect.UNKNOWN_COUNT);
    }

    @Override
    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        return effects(inspect(connection).size());
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        List<UserPreferenceUpdate> users = inspect(connection);
        long updatedAt = System.currentTimeMillis();

        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE web_user
            SET preferences_json = ?, preferences_revision = ?, updated_at_ms = ?
            WHERE id = ? AND preferences_json = ? AND preferences_revision = ?
            """))
        {
            for(UserPreferenceUpdate user: users)
            {
                statement.setString(1, user.targetJson());
                statement.setLong(2, user.targetRevision());
                statement.setLong(3, updatedAt);
                statement.setLong(4, user.id());
                statement.setString(5, user.sourceJson());
                statement.setLong(6, user.sourceRevision());

                if(statement.executeUpdate() != 1)
                {
                    throw new SQLException("Web user preferences changed after format-7-to-8 preflight: user " +
                        user.id());
                }
            }
        }
    }

    private static List<UserPreferenceUpdate> inspect(Connection connection) throws SQLException
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
                    throw new SQLException("Refusing format-7-to-8 migration because web user " + id +
                        " has an exhausted preference revision", exception);
                }

                try
                {
                    users.add(new UserPreferenceUpdate(id, sourceJson, sourceRevision,
                        Format8WebUserPreferencesCodec.migrateFromFormat7(sourceJson), targetRevision));
                }
                catch(IOException exception)
                {
                    throw new SQLException("Refusing format-7-to-8 migration because web user " + id +
                        " does not have an exact version-2 preference document", exception);
                }
            }
        }

        return List.copyOf(users);
    }

    private static List<DatabaseMigrationEffect> effects(long userCount)
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
            "per-user receiver-health alert settings", userCount,
            "Upgrade each exact version-2 browser preference document to version 3 with every receiver-health " +
                "alert enabled, preserving all existing preferences and incrementing each preference revision"));
    }

    private static void requireSourceFormat(Connection connection) throws SQLException
    {
        DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);
        if(detected.version() != 7)
        {
            throw new SQLException("Migration step format-7-to-8 requires exact source format 7; found " +
                detected.version() + " [" + detected.id() + "]");
        }
    }

    private record UserPreferenceUpdate(long id, String sourceJson, long sourceRevision,
                                        String targetJson, long targetRevision)
    {
    }
}
