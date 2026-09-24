/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Adds sparse per-table collapsed row-group state without changing receiver configuration. */
final class Format22To23DatabaseMigration implements DatabaseMigrationStep
{
    @Override
    public String id()
    {
        return "format-22-to-23";
    }

    @Override
    public String description()
    {
        return "Add persisted table row-group disclosure preferences";
    }

    @Override
    public int sourceVersion()
    {
        return 22;
    }

    @Override
    public int targetVersion()
    {
        return 23;
    }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return effects(DatabaseMigrationEffect.UNKNOWN_COUNT, DatabaseMigrationEffect.UNKNOWN_COUNT);
    }

    @Override
    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        UserInspection inspection = inspect(connection);
        return effects(inspection.users().size(), inspection.defaultedUsers());
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        List<UserUpdate> updates = inspect(connection).users();
        long updatedAt = System.currentTimeMillis();
        try(var statement = connection.prepareStatement("""
            UPDATE web_user SET preferences_json=?, preferences_revision=?, updated_at_ms=?
            WHERE id=?
            """))
        {
            for(UserUpdate update: updates)
            {
                statement.setString(1, update.targetJson());
                statement.setLong(2, update.targetRevision());
                statement.setLong(3, updatedAt);
                statement.setLong(4, update.id());
                if(statement.executeUpdate() != 1)
                {
                    throw new SQLException("Unable to update web user preferences during format-22-to-23: user " +
                        update.id());
                }
            }
        }
    }

    private static UserInspection inspect(Connection connection) throws SQLException
    {
        if(DatabaseFormatCatalog.inspectForMigration(connection).version() != 22)
        {
            throw new SQLException("Migration step format-22-to-23 requires exact source format 22");
        }
        List<UserUpdate> updates = new ArrayList<>();
        int defaultedUsers = 0;
        try(var query = connection.createStatement(); ResultSet rows = query.executeQuery("""
            SELECT id,
                   CASE WHEN typeof(preferences_json)='text'
                              AND length(CAST(preferences_json AS BLOB)) <= 131072
                        THEN preferences_json END AS preferences_json,
                   CASE WHEN typeof(preferences_revision)='integer'
                        THEN preferences_revision END AS preferences_revision
            FROM web_user ORDER BY id
            """))
        {
            while(rows.next())
            {
                long id = rows.getLong("id");
                long revision = rows.getLong("preferences_revision");
                boolean revisionMissing = rows.wasNull();
                String source = rows.getString("preferences_json");
                boolean revisionIncrementable = !revisionMissing && revision > 0 &&
                    revision < Long.MAX_VALUE - 2;
                long targetRevision = revisionIncrementable ? revision + 1 : 1;
                String target;
                boolean defaulted = !revisionIncrementable;
                if(defaulted)
                {
                    target = defaultPreferences();
                }
                else
                {
                    try
                    {
                        target = Format22WebUserPreferencesCodec.migrateToFormat23(source);
                    }
                    catch(IOException | RuntimeException exception)
                    {
                        target = defaultPreferences();
                        defaulted = true;
                    }
                }
                if(defaulted)
                {
                    defaultedUsers++;
                }
                updates.add(new UserUpdate(id, target, targetRevision));
            }
        }
        return new UserInspection(List.copyOf(updates), defaultedUsers);
    }

    private static String defaultPreferences() throws SQLException
    {
        try
        {
            return Format22WebUserPreferencesCodec.defaults();
        }
        catch(IOException | RuntimeException exception)
        {
            throw new SQLException("Unable to create default version-7 browser preferences", exception);
        }
    }

    private static List<DatabaseMigrationEffect> effects(long count, long defaultedCount)
    {
        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "per-table collapsed row groups", count,
                "Upgrade usable version-6 browser preferences to version 7 with every table group expanded and " +
                    "increment each usable preference revision; receiver configuration is unchanged"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "unusable per-user browser preferences", defaultedCount,
                "Replace only malformed, oversized, or revision-exhausted user preference documents with " +
                    "version-7 defaults"));
    }

    private record UserUpdate(long id, String targetJson, long targetRevision) {}
    private record UserInspection(List<UserUpdate> users, int defaultedUsers) {}
}
