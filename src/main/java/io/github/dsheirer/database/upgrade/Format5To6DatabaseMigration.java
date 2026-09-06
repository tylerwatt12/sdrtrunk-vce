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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/** Resets format-5 receiver activity instead of translating its obsolete conventional owner identities. */
final class Format5To6DatabaseMigration implements DatabaseMigrationStep
{
    private static final String P25_SCHEMA_VERSION_KEY = "p25_activity_schema_version";
    private static final String SOURCE_P25_SCHEMA_VERSION = "28";
    private static final String TARGET_P25_SCHEMA_VERSION = "29";

    @Override
    public String id()
    {
        return "format-5-to-6";
    }

    @Override
    public String description()
    {
        return "Start configured conventional activity with canonical identities at a clean boundary";
    }

    @Override
    public int sourceVersion()
    {
        return 5;
    }

    @Override
    public int targetVersion()
    {
        return 6;
    }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(effect(DatabaseMigrationEffect.UNKNOWN_COUNT));
    }

    @Override
    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        requireSourceFormat(connection);
        return List.of(effect(LegacyActivityReset.count(connection, LegacyActivityReset.LOGICAL_CALL_TABLES)));
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        requireSourceFormat(connection);
        LegacyActivityReset.clear(connection, LegacyActivityReset.LOGICAL_CALL_TABLES);

        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE database_metadata
            SET value = ?, updated_at_ms = ?
            WHERE key = ? AND value = ?
            """))
        {
            statement.setString(1, TARGET_P25_SCHEMA_VERSION);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, P25_SCHEMA_VERSION_KEY);
            statement.setString(4, SOURCE_P25_SCHEMA_VERSION);

            if(statement.executeUpdate() != 1)
            {
                throw new SQLException("Required format-5 metadata changed after preflight: " +
                    P25_SCHEMA_VERSION_KEY);
            }
        }
    }

    private static DatabaseMigrationEffect effect(long affectedRows)
    {
        return new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
            "receiver-derived activity and counters", affectedRows,
            "Restart calls, signaling, identities, site observations, and quality history from zero; live traffic " +
                "uses canonical configured-channel identities");
    }

    private static void requireSourceFormat(Connection connection) throws SQLException
    {
        DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);

        if(detected.version() != 5)
        {
            throw new SQLException("Migration step format-5-to-6 requires exact source format 5; found " +
                detected.version() + " [" + detected.id() + "]");
        }
    }
}
