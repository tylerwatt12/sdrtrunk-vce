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

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** Exact populated markerless global-format 2 fixture. */
public final class Format2TestDatabase
{
    private Format2TestDatabase()
    {
    }

    public static Path create(Path database) throws Exception
    {
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);

            //This fallback lets the migrator component test its target before the format-2 runtime schema commits
            //are integrated. The integrated Alpha creates format 2 directly and takes the branch below.
            if(detected.version() == 1)
            {
                try(Statement statement = connection.createStatement())
                {
                    statement.execute("PRAGMA foreign_keys=ON");
                    statement.execute("BEGIN IMMEDIATE");
                    try
                    {
                        DatabaseMigrationChain.migrate(connection);
                        statement.execute("COMMIT");
                    }
                    catch(Exception e)
                    {
                        statement.execute("ROLLBACK");
                        throw e;
                    }
                }
            }
            else if(detected.version() != 2)
            {
                throw new IllegalStateException("Fresh database is neither format 1 nor format 2");
            }

            try(Statement statement = connection.createStatement())
            {
                statement.executeUpdate("""
                    INSERT OR IGNORE INTO alias_list(
                        name, family, unmatched_talkgroup_record_enabled
                    ) VALUES ('Format 2 Fixture', 'P25', 0)
                    """);
                statement.executeUpdate("""
                    INSERT INTO alias(
                        alias_list_id, name, color, record_enabled, matcher_type, protocol, value
                    )
                    SELECT id, 'Fixture Dispatch', 0, 1, 'TALKGROUP', 'APCO25', 1201
                    FROM alias_list WHERE name='Format 2 Fixture'
                    """);
                statement.executeUpdate("""
                    DELETE FROM database_metadata
                    WHERE key='database_format_version'
                    """);
            }

            DatabaseFormatCatalog.DetectedFormat markerless = DatabaseFormatCatalog.inspect(connection);
            if(markerless.version() != 2 || markerless.markerPresent())
            {
                throw new IllegalStateException("Format 2 fixture was not recognized as exact markerless format 2");
            }

            String fingerprint = SqliteSchemaValidator.fingerprint(connection);
            if(!DatabaseFormatCatalog.requireVersion(2).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 2 fixture fingerprint mismatch: " + fingerprint);
            }
        }

        return database;
    }
}
