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
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Exact markerless format 2 fixture used by published-nightly migration tests.
 *
 * <p>This factory reconstructs the historical schema independently of the production format-1-to-2 and
 * format-2-to-3 transformations, then verifies the frozen catalog fingerprint.</p>
 */
public final class Format2TestDatabase
{
    private Format2TestDatabase()
    {
    }

    public static Path create(Path database) throws Exception
    {
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            connection.setAutoCommit(false);

            try
            {
                replaceP25SiteProjectionSchema(statement);
                statement.executeUpdate(
                    "UPDATE database_metadata SET value='26' WHERE key='p25_activity_schema_version'");
                statement.executeUpdate(
                    "DELETE FROM database_metadata WHERE key='" + DatabaseFormatCatalog.FORMAT_VERSION_KEY + "'");
                statement.executeUpdate("DELETE FROM database_metadata WHERE key='initial_admin_setup'");
                connection.commit();
            }
            catch(Exception e)
            {
                connection.rollback();
                throw e;
            }
            finally
            {
                connection.setAutoCommit(true);
            }

            DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);
            if(detected.version() != 2 || detected.markerPresent())
            {
                throw new IllegalStateException("Format 2 fixture was not recognized as markerless format 2");
            }

            String fingerprint = SqliteSchemaValidator.fingerprint(connection);
            if(!DatabaseFormatCatalog.requireVersion(2).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 2 fixture fingerprint mismatch: " + fingerprint);
            }
        }

        return database;
    }

    private static void replaceP25SiteProjectionSchema(Statement statement) throws Exception
    {
        String resolvedViewSql;

        try(ResultSet resultSet = statement.executeQuery("""
            SELECT sql
            FROM sqlite_schema
            WHERE type = 'view' AND name = 'p25_activity_event_resolved'
            """))
        {
            if(!resultSet.next())
            {
                throw new IllegalStateException("Current resolved P25 activity view is missing");
            }

            resolvedViewSql = resultSet.getString(1);
        }

        String currentProjection = "coalesce(ps.system_id, p25.system_id)";
        String predecessorProjection = "ps.system_id";
        int joinStart = resolvedViewSql.indexOf("LEFT JOIN p25_site_snapshot p25");
        int joinPredicate = resolvedViewSql.indexOf(
            "ON p25.guid = rc.guid AND rc.kind_code = 1 AND rc.protocol_code IN (1, 2)", joinStart);

        if(!resolvedViewSql.contains(currentProjection) || joinStart < 0 || joinPredicate < 0)
        {
            throw new IllegalStateException("Current resolved P25 activity projection is not recognized");
        }

        int joinLineStart = resolvedViewSql.lastIndexOf('\n', joinStart) + 1;
        int joinLineEnd = resolvedViewSql.indexOf('\n', joinPredicate);
        joinLineEnd = joinLineEnd < 0 ? resolvedViewSql.length() : joinLineEnd + 1;
        String predecessorViewSql = (resolvedViewSql.substring(0, joinLineStart) +
            resolvedViewSql.substring(joinLineEnd)).replace(currentProjection, predecessorProjection);

        statement.executeUpdate("DROP VIEW p25_activity_event_resolved");
        statement.executeUpdate("ALTER TABLE p25_site_snapshot DROP COLUMN system_id");
        statement.executeUpdate("ALTER TABLE p25_site_snapshot DROP COLUMN active_rfss_network_connection");
        statement.executeUpdate("ALTER TABLE p25_site_channel_summary DROP COLUMN callsign");
        statement.executeUpdate(predecessorViewSql);
    }
}
