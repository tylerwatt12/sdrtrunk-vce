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

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Adds canonical P25 subscriber identities, WUID observation summaries, and explicit canonical Alias matchers. */
final class Format31To32DatabaseMigration implements DatabaseMigrationStep
{
    private static final String ALIAS_COLUMNS = "id,alias_list_id,name,description,group_name,color,icon_name," +
        "stream_as_talkgroup,record_enabled,matcher_type,protocol,value,min_value,max_value,text_value," +
        "numeric_value,tone_sequence";

    @Override public String id() { return "format-31-to-32"; }
    @Override public String description() { return "Store canonical P25 subscribers and WUID observations"; }
    @Override public int sourceVersion() { return 31; }
    @Override public int targetVersion() { return 32; }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return effects(DatabaseMigrationEffect.UNKNOWN_COUNT);
    }

    @Override
    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        requireSource(connection);
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT count(*) FROM alias"))
        {
            return effects(resultSet.next() ? resultSet.getLong(1) : 0);
        }
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        requireSource(connection);
        ReceiverActivitySchema.createFormat32P25SubscriberIdentityTables(connection);
        rebuildAliasTable(connection);
        ReceiverActivitySchema.addFormat32ExplicitWorkingAddressProvenance(connection);
        ReceiverActivitySchema.addFormat32P25SubscriberIdentityReference(connection);
    }

    private static void rebuildAliasTable(Connection connection) throws SQLException
    {
        long aliasSequence = sequence(connection, "alias");
        long broadcastSequence = sequence(connection, "alias_broadcast_channel");
        List<String> indexes = new ArrayList<>();
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT name FROM sqlite_schema
                WHERE type='index' AND tbl_name='alias' AND sql IS NOT NULL
                ORDER BY name
                """))
        {
            while(resultSet.next())
            {
                indexes.add(resultSet.getString(1));
            }
        }

        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP VIEW alias_talkgroup");
            statement.executeUpdate("DROP VIEW alias_radio");
            statement.executeUpdate("""
                CREATE TEMP TABLE format31_alias_activity_summary AS
                SELECT * FROM alias_activity_summary
                """);
            statement.executeUpdate("""
                CREATE TEMP TABLE format31_alias_scan_list_membership AS
                SELECT * FROM alias_scan_list_membership
                """);
            statement.executeUpdate("""
                CREATE TEMP TABLE format31_alias_broadcast_channel AS
                SELECT * FROM alias_broadcast_channel
                """);
            //Remove every referencing child before renaming the parent.  With foreign_keys enabled SQLite otherwise
            //rewrites those foreign-key targets to the staging name and leaves a structurally invalid target schema.
            statement.executeUpdate("DROP TABLE alias_activity_summary");
            statement.executeUpdate("DROP TABLE alias_scan_list_membership");
            statement.executeUpdate("DROP TABLE alias_broadcast_channel");
            for(String index: indexes)
            {
                statement.executeUpdate("DROP INDEX " + index);
            }

            statement.execute("PRAGMA legacy_alter_table=ON");
            try
            {
                statement.executeUpdate("ALTER TABLE alias RENAME TO format31_alias");
                SdrTrunkDatabaseSchema.createFormat32AliasTable(connection);
                statement.executeUpdate("INSERT INTO alias(" + ALIAS_COLUMNS + ") SELECT " + ALIAS_COLUMNS +
                    " FROM format31_alias");
                statement.executeUpdate("DROP TABLE format31_alias");
            }
            finally
            {
                statement.execute("PRAGMA legacy_alter_table=OFF");
            }
        }
        SdrTrunkDatabaseSchema.createCurrentAliasMatcherObjects(connection);
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias_activity_summary
                SELECT * FROM format31_alias_activity_summary
                """);
            statement.executeUpdate("""
                INSERT INTO alias_scan_list_membership
                SELECT * FROM format31_alias_scan_list_membership
                """);
            statement.executeUpdate("""
                INSERT INTO alias_broadcast_channel
                SELECT * FROM format31_alias_broadcast_channel
                """);
            statement.executeUpdate("DROP TABLE format31_alias_activity_summary");
            statement.executeUpdate("DROP TABLE format31_alias_scan_list_membership");
            statement.executeUpdate("DROP TABLE format31_alias_broadcast_channel");
        }
        restoreSequence(connection, "alias", aliasSequence);
        restoreSequence(connection, "alias_broadcast_channel", broadcastSequence);
    }

    private static long sequence(Connection connection, String table) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(
                "SELECT seq FROM sqlite_sequence WHERE name='" + table + "'"))
        {
            return resultSet.next() ? resultSet.getLong(1) : -1;
        }
    }

    private static void restoreSequence(Connection connection, String table, long sequence) throws SQLException
    {
        if(sequence < 0)
        {
            return;
        }
        try(Statement statement = connection.createStatement())
        {
            int updated = statement.executeUpdate("UPDATE sqlite_sequence SET seq=max(seq," + sequence +
                ") WHERE name='" + table + "'");
            if(updated == 0)
            {
                statement.executeUpdate("INSERT INTO sqlite_sequence(name,seq) VALUES ('" + table + "'," +
                    sequence + ")");
            }
        }
    }

    private static void requireSource(Connection connection) throws SQLException
    {
        if(DatabaseFormatCatalog.inspectForMigration(connection).version() != 31)
        {
            throw new SQLException("Expected format 31");
        }
    }

    private static List<DatabaseMigrationEffect> effects(long aliases)
    {
        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
                "administrator-owned aliases", aliases,
                "Preserve every existing Alias without converting local radio-ID matchers"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "canonical P25 subscriber Alias matchers", 0,
                "Start empty; administrators may add explicit WACN, System ID, and Subscriber ID matchers"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "canonical P25 subscribers and WUID observations", 0,
                "Start empty without inferring mappings from existing identities or local radio IDs"));
    }
}
