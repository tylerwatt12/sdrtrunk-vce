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

/** Adds the disabled-by-default idle FFT marker preference without changing receiver configuration. */
final class Format11To12DatabaseMigration implements DatabaseMigrationStep
{
    @Override
    public String id()
    {
        return "format-11-to-12";
    }

    @Override
    public String description()
    {
        return "Add the per-user idle FFT channel marker preference";
    }

    @Override
    public int sourceVersion()
    {
        return 11;
    }

    @Override
    public int targetVersion()
    {
        return 12;
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
        List<UserUpdate> updates = inspect(connection);
        long updatedAt = System.currentTimeMillis();
        try(var statement = connection.prepareStatement("""
            UPDATE web_user SET preferences_json=?, preferences_revision=?, updated_at_ms=?
            WHERE id=? AND preferences_json=? AND preferences_revision=?
            """))
        {
            for(UserUpdate update: updates)
            {
                statement.setString(1, update.targetJson());
                statement.setLong(2, update.revision() + 1);
                statement.setLong(3, updatedAt);
                statement.setLong(4, update.id());
                statement.setString(5, update.sourceJson());
                statement.setLong(6, update.revision());
                if(statement.executeUpdate() != 1)
                {
                    throw new SQLException("Web user preferences changed after format-11-to-12 preflight: user " +
                        update.id());
                }
            }
        }
    }

    private static List<UserUpdate> inspect(Connection connection) throws SQLException
    {
        if(DatabaseFormatCatalog.inspect(connection).version() != 11)
        {
            throw new SQLException("Migration step format-11-to-12 requires exact source format 11");
        }
        List<UserUpdate> updates = new ArrayList<>();
        try(var query = connection.createStatement(); ResultSet rows = query.executeQuery(
            "SELECT id, preferences_json, preferences_revision FROM web_user ORDER BY id"))
        {
            while(rows.next())
            {
                long id = rows.getLong("id");
                long revision = rows.getLong("preferences_revision");
                if(revision == Long.MAX_VALUE)
                {
                    throw new SQLException("Refusing format-11-to-12 migration: exhausted preference revision for user " + id);
                }
                String source = rows.getString("preferences_json");
                try
                {
                    updates.add(new UserUpdate(id, source, Format12WebUserPreferencesCodec.migrateFromFormat11(source),
                        revision));
                }
                catch(IOException e)
                {
                    throw new SQLException("Refusing format-11-to-12 migration: user " + id +
                        " does not have an exact version-4 preference document", e);
                }
            }
        }
        return List.copyOf(updates);
    }

    private static List<DatabaseMigrationEffect> effects(long count)
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
            "per-user idle FFT channel markers", count,
            "Upgrade exact version-4 browser preferences to version 5 with idle FFT markers off, preserving " +
                "all existing settings and incrementing each preference revision; receiver configuration is unchanged"));
    }

    private record UserUpdate(long id, String sourceJson, String targetJson, long revision) {}
}
