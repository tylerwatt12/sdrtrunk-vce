/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.util.List;

/** Populated current fixture selected by the catalog, leaving every historical factory frozen. */
public final class CurrentFormatTestDatabase
{
    private CurrentFormatTestDatabase() {}

    public static String lastMigrationStepId()
    {
        return DatabaseMigrationChain.steps().getLast().id();
    }

    public static List<String> migrationStepIdsFrom(int sourceVersion)
    {
        return DatabaseMigrationChain.steps().stream().filter(step -> step.sourceVersion() >= sourceVersion)
            .map(DatabaseMigrationChain.StepDescriptor::id).toList();
    }

    /** Use the migrator's offline transaction boundary; preserve a transaction already owned by the test. */
    static DatabaseMigrationChain.MigrationReport migrateToCurrent(Connection connection) throws SQLException
    {
        if(!connection.getAutoCommit()) return DatabaseMigrationChain.migrate(connection);
        boolean foreignKeys;
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA foreign_keys"))
        {
            foreignKeys = rows.next() && rows.getInt(1) != 0;
        }
        try(var statement = connection.createStatement()) { statement.execute("PRAGMA foreign_keys=OFF"); }
        connection.setAutoCommit(false);
        try
        {
            var report = DatabaseMigrationChain.migrate(connection);
            connection.commit();
            return report;
        }
        catch(SQLException | RuntimeException failure)
        {
            connection.rollback();
            throw failure;
        }
        finally
        {
            connection.setAutoCommit(true);
            try(var statement = connection.createStatement())
            {
                statement.execute("PRAGMA foreign_keys=" + (foreignKeys ? "ON" : "OFF"));
            }
        }
    }

    /**
     * Exercise a frozen, single-file segment of the adjacent chain. Cross-file activation belongs to the
     * Application Migrator after the test closes this connection, rather than to a caller's rollback test.
     */
    static DatabaseMigrationChain.MigrationReport migrateToVersion(Connection connection, int targetVersion)
        throws SQLException
    {
        if(targetVersion >= 63) throw new IllegalArgumentException("Use the Application Migrator for paired targets");
        boolean ownsTransaction = connection.getAutoCommit();
        boolean foreignKeys;
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA foreign_keys"))
        {
            foreignKeys = rows.next() && rows.getInt(1) != 0;
        }
        if(ownsTransaction)
        {
            try(var statement = connection.createStatement()) { statement.execute("PRAGMA foreign_keys=OFF"); }
            connection.setAutoCommit(false);
        }
        try
        {
            var report = DatabaseMigrationChain.migrate(connection,
                DatabaseFormatCatalog.inspectForMigration(connection), true, ignored -> {}, ignored -> {}, targetVersion);
            if(ownsTransaction) connection.commit();
            return report;
        }
        catch(SQLException | RuntimeException failure)
        {
            if(ownsTransaction) connection.rollback();
            throw failure;
        }
        finally
        {
            if(ownsTransaction)
            {
                connection.setAutoCommit(true);
                try(var statement = connection.createStatement())
                {
                    statement.execute("PRAGMA foreign_keys=" + (foreignKeys ? "ON" : "OFF"));
                }
            }
        }
    }

    static List<String> migrationStepIdsBetween(int sourceVersion, int targetVersion)
    {
        return DatabaseMigrationChain.steps().stream().filter(step -> step.sourceVersion() >= sourceVersion &&
            step.targetVersion() <= targetVersion).map(DatabaseMigrationChain.StepDescriptor::id).toList();
    }

    /** Complete the actual current route with its owned activation boundary and validate both database roles. */
    static void validateCurrentPair(Path database) throws Exception
    {
        ApplicationDatabaseMigrator.migrateInPlace(database, null);
        io.github.dsheirer.database.ConfigurationHistoryDatabase.validatePair(database);
        io.github.dsheirer.database.SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
    }

    /** Read both actual format-63 roles as the original format-62 table projection for preservation assertions. */
    static List<String> retainedMonolithTables(Connection connection) throws SQLException
    {
        boolean paired = attachRetainedHistory(connection);
        var result = new java.util.ArrayList<String>();
        String query = "SELECT name FROM main.sqlite_schema WHERE type='table'";
        if(paired) query += " UNION SELECT name FROM activity.sqlite_schema WHERE type='table' " +
            "AND name<>'receiver_channel_statistics_reset'";
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(query + " ORDER BY name"))
        {
            while(rows.next()) result.add(rows.getString(1));
        }
        return result;
    }

    /**
     * Preserve every pre-split field, including the explicit reset value moved to its own table. Newly captured
     * context and independent Alias identities are verified by pair validation, rather than invented in the source.
     */
    static String retainedMonolithQuery(Connection connection, String table) throws SQLException
    {
        return retainedMonolithQuery(connection, table, false);
    }

    static String retainedMonolithQuery(Connection connection, String table, boolean omitRebuiltPlannerStatistics)
        throws SQLException
    {
        boolean paired = attachRetainedHistory(connection);
        String quoted = "\"" + table.replace("\"", "\"\"") + "\"";
        String query;
        if(!paired) query = "SELECT * FROM " + quoted;
        else if(table.equals("database_metadata")) query = "SELECT * FROM (SELECT * FROM main.database_metadata " +
            "UNION SELECT * FROM activity.database_metadata) WHERE key NOT IN " +
            "('database_role','configuration_history_pair_id')";
        else if(table.equals("sqlite_sequence") || table.startsWith("sqlite_stat"))
        {
            var roles = new java.util.ArrayList<String>();
            for(String role: List.of("main", "activity"))
            {
                try(var statement = connection.prepareStatement("SELECT 1 FROM " + role +
                    ".sqlite_schema WHERE type='table' AND name=?"))
                {
                    statement.setString(1, table);
                    try(var row = statement.executeQuery())
                    {
                        if(row.next()) roles.add("SELECT * FROM " + role + "." + quoted);
                    }
                }
            }
            query = String.join(" UNION ALL ", roles);
        }
        else if(table.equals("configuration_channel")) query = "SELECT c.*, (SELECT reset_at_ms FROM " +
            "activity.receiver_channel_statistics_reset r WHERE r.configuration_id=c.configuration_id) " +
            "AS analog_activity_reset_ms FROM main.configuration_channel c";
        else
        {
            boolean configuration = io.github.dsheirer.database.ConfigurationHistorySchema.CONFIGURATION_TABLES.contains(table)
                && !table.equals("p25_subscriber_identity");
            String role = configuration ? "main" : "activity";
            java.util.Set<String> added = switch(table)
            {
                case "alias" -> java.util.Set.of("activity_identity", "activity_reset_ms");
                case "receiver_channel" -> java.util.Set.of("name", "system_name", "site_name", "decoder_type",
                    "channel_kind", "address_domain_code", "alias_list_id", "alias_list_name", "primary_frequency_hz",
                    "assigned_talkgroup");
                case "alias_activity_summary" -> java.util.Set.of("activity_identity", "alias_name", "alias_list_name");
                case "alias_list_analog_activity_summary" -> java.util.Set.of("alias_list_name");
                default -> java.util.Set.of();
            };
            var columns = new java.util.ArrayList<String>();
            try(var statement = connection.createStatement();
                var rows = statement.executeQuery("PRAGMA " + role + ".table_info(" + quoted + ")"))
            {
                while(rows.next())
                {
                    String name = rows.getString("name");
                    if(!added.contains(name)) columns.add("\"" + name.replace("\"", "\"\"") + "\"");
                }
            }
            query = "SELECT " + String.join(",", columns) + " FROM " + role + "." + quoted;
        }
        if(omitRebuiltPlannerStatistics && table.startsWith("sqlite_stat"))
        {
            var rebuilt = new java.util.TreeSet<>(io.github.dsheirer.database.ConfigurationHistorySchema.CONFIGURATION_TABLES);
            // The complete original subscriber dictionary stays in history; only its configured subset is copied.
            rebuilt.remove("p25_subscriber_identity");
            rebuilt.addAll(List.of("receiver_channel", "radio_system", "alias_activity_summary", "alias_list_analog_activity_summary"));
            query = "SELECT * FROM (" + query + ") WHERE tbl NOT IN (" + rebuilt.stream()
                .map(name -> "'" + name + "'").collect(java.util.stream.Collectors.joining(",")) + ")";
        }
        return "(" + query + ")";
    }

    private static boolean attachRetainedHistory(Connection connection) throws SQLException
    {
        if(!io.github.dsheirer.database.ConfigurationHistoryDatabase.isPairedConfiguration(connection)) return false;
        Path database = null;
        boolean attached = false;
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA database_list"))
        {
            while(rows.next())
            {
                if(rows.getString("name").equals("main")) database = Path.of(rows.getString("file"));
                if(rows.getString("name").equals("activity")) attached = true;
            }
        }
        if(!attached)
        {
            try
            {
                io.github.dsheirer.database.ConfigurationHistoryDatabase.validatePair(database);
            }
            catch(java.io.IOException failure)
            {
                throw new SQLException("The migrated preservation fixture is not a complete pair", failure);
            }
            try(var attach = connection.prepareStatement("ATTACH DATABASE ? AS activity"))
            {
                attach.setString(1, io.github.dsheirer.database.ConfigurationHistoryDatabase.companionPath(database)
                    .toUri() + "?mode=ro");
                attach.execute();
            }
        }
        return true;
    }

    static void retainedPairIntegrity(Connection connection) throws SQLException
    {
        List<String> roles = attachRetainedHistory(connection) ? List.of("main", "activity") : List.of("main");
        for(String role: roles)
        {
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA " + role + ".foreign_key_check"))
            {
                org.junit.jupiter.api.Assertions.assertFalse(rows.next(), role);
            }
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA " + role + ".integrity_check"))
            {
                org.junit.jupiter.api.Assertions.assertTrue(rows.next(), role);
                org.junit.jupiter.api.Assertions.assertEquals("ok", rows.getString(1), role);
            }
        }
    }

    /** Later declared additions are checked independently, rather than compared as historical columns. */
    static boolean addedMessageCounter(ResultSet rows, int column) throws SQLException
    {
        if(!List.of("stun_count", "revive_count", "kill_count", "radio_inhibit_count", "radio_uninhibit_count",
            "remote_monitor_count", "authenticate_count", "cancel_call_count", "radio_detach_count",
            "protect_count", "vote_now_count").contains(rows.getMetaData().getColumnName(column))) return false;
        org.junit.jupiter.api.Assertions.assertEquals(0, rows.getLong(column));
        return true;
    }

    static boolean addedCurrentTable(Connection connection, String table) throws SQLException
    {
        if(table.equals("activity_message_type"))
        {
            io.github.dsheirer.stats.activity.ActivityMessageCatalog.validate(connection,
                attachRetainedHistory(connection) ? "activity" : "main");
            return true;
        }
        if(table.equals("web_user_tour_dismissal"))
        {
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM web_user_tour_dismissal"))
            {
                org.junit.jupiter.api.Assertions.assertTrue(rows.next());
                org.junit.jupiter.api.Assertions.assertEquals(0, rows.getInt(1));
            }
            return true;
        }
        return false;
    }

    /** Compare the exact declared preference conversion while preserving every other account field. */
    static Object retainedValue(String table, ResultSet rows, int column) throws Exception
    {
        if(table.equals("web_user"))
        {
            String name = rows.getMetaData().getColumnName(column);
            String preferences = rows.getString("preferences_json");
            if(new com.fasterxml.jackson.databind.ObjectMapper().readTree(preferences).path("version").asInt() == 11)
            {
                if(name.equals("preferences_json"))
                    return Format62WebUserPreferencesCodec.migrateFromFormat61(preferences).json();
                if(name.equals("preferences_revision")) return rows.getLong(column) + 1;
            }
        }
        return rows.getObject(column);
    }

    public static Path create(Path database) throws Exception
    {
        String factory = Path.of(DatabaseFormatCatalog.current().fixtureResource()).getFileName().toString()
            .replace(".java", "");
        return (Path)Class.forName(CurrentFormatTestDatabase.class.getPackageName() + "." + factory)
            .getMethod("create", Path.class).invoke(null, database);
    }
}
