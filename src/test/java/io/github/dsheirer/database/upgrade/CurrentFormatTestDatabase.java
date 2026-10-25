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
            io.github.dsheirer.stats.activity.ActivityMessageCatalog.validate(connection);
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
