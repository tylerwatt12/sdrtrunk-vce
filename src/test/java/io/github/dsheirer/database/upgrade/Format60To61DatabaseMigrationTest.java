/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format60To61DatabaseMigrationTest
{
    @TempDir Path directory;

    @Test void freshAndMigratedDefinitionsMatchTheirRegisteredSignatures() throws Exception
    {
        Path database = Format60TestDatabase.create(directory.resolve("prior.sqlite"));
        Path fresh = directory.resolve("fresh.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(fresh);
        ConfigurationHistoryDatabase.validatePair(fresh);
        try(var migrated = open(database))
        {
            assertEquals(DatabaseFormatCatalog.requireVersion(60).fingerprint(), SqliteSchemaValidator.fingerprint(migrated));
            migrated.setAutoCommit(false); new Format60To61DatabaseMigration().migrate(migrated);
            assertEquals(DatabaseFormatCatalog.requireVersion(61).fingerprint(), SqliteSchemaValidator.fingerprint(migrated));
        }
    }

    @Test void populatedPriorRowsSurviveRollbackRetryAndValidationOnlyStartup() throws Exception
    {
        Path database = Format60TestDatabase.create(directory.resolve("upgrade.sqlite"));
        byte[] original = Files.readAllBytes(database);
        assertThrows(java.io.IOException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database));
        assertArrayEquals(original, Files.readAllBytes(database));
        try(var connection = open(database))
        {
            var before = snapshot(connection, null);
            assertTrue(before.get("web_user").size() >= 2, "The frozen prior fixture contains usable distinct accounts.");
            connection.setAutoCommit(false);
            migrateAdjacent(connection);
            assertEquals(before, snapshot(connection, before.keySet()));
            assertEquals(0, count(connection, "SELECT count(*) FROM web_user_tour_dismissal"));
            connection.rollback();
            assertEquals(60, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(before, snapshot(connection, null));
            migrateAdjacent(connection); connection.commit();
            assertEquals(before, snapshot(connection, before.keySet()));
            assertEquals(61, DatabaseFormatCatalog.inspect(connection).version());
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA foreign_key_check"))
            { assertFalse(rows.next()); }
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA integrity_check"))
            { assertTrue(rows.next()); assertEquals("ok", rows.getString(1)); }
        }
        ApplicationDatabaseMigrator.migrateInPlace(database, null);
        ConfigurationHistoryDatabase.validatePair(database);
        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
        byte[] upgraded = Files.readAllBytes(database);
        SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database);
        assertArrayEquals(upgraded, Files.readAllBytes(database));
        Path populated = Format64TestDatabase.create(directory.resolve("current.sqlite"));
        try(var connection = open(populated))
        {
            assertTrue(count(connection, "SELECT count(*) FROM web_user_tour_dismissal") >= 3);
            DatabaseFormatCatalog.requireCurrent(connection);
        }
    }

    @Test void offlineAccountRepairRemovesOnlyTheDeletedAccountsTourHistory() throws Exception
    {
        Path database = Format64TestDatabase.create(directory.resolve("account-repair.sqlite"));
        try(var connection = open(database); var statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=OFF");
            long primaryHistory = count(connection, "SELECT count(*) FROM web_user_tour_dismissal WHERE user_id=" +
                "(SELECT id FROM web_user WHERE primary_admin=1)");
            assertTrue(count(connection, "SELECT count(*) FROM web_user_tour_dismissal WHERE user_id IN " +
                "(SELECT id FROM web_user WHERE primary_admin=0)") > 0);
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("UPDATE web_user SET password_hash=X'00' WHERE primary_admin=0");
            statement.execute("PRAGMA ignore_check_constraints=OFF");
            connection.setAutoCommit(false);
            assertTrue(CurrentDatabaseAdministrativeRepair.inspect(connection).droppedSecondaryWebAccounts() > 0);
            CurrentDatabaseAdministrativeRepair.repair(connection);
            assertEquals(primaryHistory, count(connection, "SELECT count(*) FROM web_user_tour_dismissal"));
            DatabaseFormatCatalog.requireCurrent(connection);
            try(var rows = statement.executeQuery("PRAGMA foreign_key_check")) { assertFalse(rows.next()); }
            connection.rollback();
        }
    }

    private static void migrateAdjacent(Connection connection) throws Exception
    {
        new Format60To61DatabaseMigration().migrate(connection);
        DatabaseFormatCatalog.stamp(connection, 61);
    }

    private static Connection open(Path path) throws Exception
    { return DriverManager.getConnection("jdbc:sqlite:" + path); }
    private static long count(Connection connection, String sql) throws Exception
    { try(var statement = connection.createStatement(); var row = statement.executeQuery(sql)) { assertTrue(row.next()); return row.getLong(1); } }
    private static Map<String,List<String>> snapshot(Connection connection, java.util.Set<String> selected) throws Exception
    {
        Map<String,List<String>> result = new LinkedHashMap<>();
        try(var statement = connection.createStatement(); var tables = statement.executeQuery("SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name"))
        {
            while(tables.next())
            {
                String table = tables.getString(1);
                if(selected != null && !selected.contains(table)) continue;
                List<String> values = new ArrayList<>();
                try(var read = connection.createStatement(); var rows = read.executeQuery("SELECT * FROM \"" + table + "\""))
                {
                    while(rows.next())
                    {
                        if(table.equals("database_metadata") && DatabaseFormatCatalog.FORMAT_VERSION_KEY.equals(rows.getString(1))) continue;
                        List<String> fields = new ArrayList<>();
                        for(int i=1; i<=rows.getMetaData().getColumnCount(); i++)
                        {
                            Object value = rows.getObject(i);
                            fields.add(value instanceof byte[] bytes ? java.util.HexFormat.of().formatHex(bytes) : String.valueOf(value));
                        }
                        values.add(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(fields.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                    }
                }
                values.sort(String::compareTo); result.put(table, values);
            }
        }
        return result;
    }
}
