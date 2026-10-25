/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format57To58DatabaseMigrationTest
{
    private static final String CALL = "8a220f1a-7ddc-4182-8dd0-fb60506aeb51";
    @TempDir Path directory;

    @Test void freshSchemaMatchesRegisteredSignatureAndActivityDefinition() throws Exception
    {
        Path fresh = directory.resolve("fresh.sqlite");
        io.github.dsheirer.database.SdrTrunkDatabaseStartup.createGlobalDatabase(fresh);
        io.github.dsheirer.database.ConfigurationHistoryDatabase.validatePair(fresh);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" +
            io.github.dsheirer.database.ConfigurationHistoryDatabase.companionPath(fresh)))
        {
            assertEquals(io.github.dsheirer.database.ConfigurationHistoryDatabase.HISTORY_FINGERPRINT,
                SqliteSchemaValidator.fingerprint(connection));
        }
    }

    @Test void populatedMigrationPreservesEveryOriginalRowAndAllocatorWithUnknownReferences() throws Exception
    {
        Path database = Format57TestDatabase.create(directory.resolve("prior.sqlite"));
        try(var connection = open(database); var sql = connection.createStatement())
        {
            sql.execute("ANALYZE");
            Map<String,List<String>> before = contents(connection);
            assertTrue(count(connection, "receiver_activity_event") > 0);
            assertTrue(count(connection, "topic_alert_message") > 0);
            connection.setAutoCommit(false);
            var report = CurrentFormatTestDatabase.migrateToVersion(connection, 62);
            assertEquals(CurrentFormatTestDatabase.migrationStepIdsBetween(57, 62), report.steps().stream().map(DatabaseMigrationChain.StepReport::id).toList());
            assertEquals(DatabaseFormatCatalog.requireVersion(62).fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            assertEquals(before, contents(connection));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM receiver_activity_event WHERE source_call_uuid IS NOT NULL"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM topic_alert_message WHERE source_call_uuid IS NOT NULL"));
            connection.rollback();
            assertEquals(57, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(before, contents(connection));
            CurrentFormatTestDatabase.migrateToVersion(connection, 62); connection.commit();
            assertEquals(62, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(before, contents(connection));
            assertTrue(CurrentFormatTestDatabase.migrateToVersion(connection, 62).steps().isEmpty());
            integrity(connection);
        }
        CurrentFormatTestDatabase.validateCurrentPair(database);
    }

    @Test void interruptedAdjacentStepRollsBackBothColumnsAndCanRetryFromTheUnchangedSource() throws Exception
    {
        Path database = Format57TestDatabase.create(directory.resolve("interrupted.sqlite"));
        byte[] before = Files.readAllBytes(database);
        try(var connection = open(database))
        {
            connection.setAutoCommit(false);
            ReceiverActivitySchema.addSourceCallReference(connection);
            connection.rollback();
            assertEquals(57, DatabaseFormatCatalog.inspect(connection).version());
        }
        assertArrayEquals(before, Files.readAllBytes(database));
        try(var connection = open(database))
        {
            connection.setAutoCommit(false); CurrentFormatTestDatabase.migrateToVersion(connection, 62); connection.commit();
            assertEquals(62, DatabaseFormatCatalog.inspect(connection).version()); integrity(connection);
        }
        CurrentFormatTestDatabase.validateCurrentPair(database);
    }

    @Test void canonicalReferencesAreOptionalIndexedNonUniqueAndIndependentlyRetained() throws Exception
    {
        Path database = Format58TestDatabase.create(directory.resolve("references.sqlite"));
        try(var connection = open(database); var sql = connection.createStatement())
        {
            sql.execute("PRAGMA foreign_keys=ON");
            for(String table: List.of("receiver_activity_event", "topic_alert_message"))
            {
                try(var update = connection.prepareStatement("UPDATE " + table + " SET source_call_uuid=?"))
                {
                    update.setString(1, CALL); assertTrue(update.executeUpdate() > 0);
                    for(String invalid: List.of("", "temporary:1", CALL.toUpperCase(), CALL.replace('-', 'a'),
                        "ga220f1a-7ddc-4182-8dd0-fb60506aeb51"))
                    {
                        update.setString(1, invalid); assertThrows(SQLException.class, update::executeUpdate);
                    }
                }
                String index = table.equals("receiver_activity_event") ? "idx_receiver_activity_event_source_call" :
                    "idx_topic_alert_message_source_call";
                try(var plan = sql.executeQuery("EXPLAIN QUERY PLAN SELECT id FROM " + table +
                    " WHERE source_call_uuid='" + CALL + "'"))
                {
                    assertTrue(plan.next()); assertTrue(plan.getString("detail").contains(index));
                    assertFalse(plan.getString("detail").contains("SCAN"));
                }
                try(var definition = sql.executeQuery("SELECT sql FROM sqlite_schema WHERE name='" + index + "'"))
                {
                    assertTrue(definition.next()); assertTrue(definition.getString(1).contains("WHERE source_call_uuid IS NOT NULL"));
                }
            }
            long alerts = count(connection, "topic_alert_message");
            sql.executeUpdate("DELETE FROM receiver_activity_event");
            assertEquals(alerts, count(connection, "topic_alert_message"));
            assertEquals(alerts, scalar(connection, "SELECT count(*) FROM topic_alert_message WHERE source_call_uuid='" + CALL + "'"));
            sql.executeUpdate("UPDATE topic_alert_message SET source_call_uuid=NULL");
            integrity(connection);
        }
    }

    @Test void startupRefusesPriorFormatWithoutChangingItsBytes() throws Exception
    {
        Path database = Format57TestDatabase.create(directory.resolve("startup.sqlite"));
        byte[] before = Files.readAllBytes(database);
        assertThrows(java.io.IOException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database));
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    private static Connection open(Path path) throws SQLException
    {
        return DriverManager.getConnection("jdbc:sqlite:" + path);
    }

    private static long count(Connection connection, String table) throws SQLException
    {
        return scalar(connection, "SELECT count(*) FROM " + table);
    }

    private static long scalar(Connection connection, String query) throws SQLException
    {
        try(var statement = connection.createStatement(); var row = statement.executeQuery(query))
        { assertTrue(row.next()); return row.getLong(1); }
    }

    private static void integrity(Connection connection) throws SQLException
    {
        try(var sql = connection.createStatement(); var rows = sql.executeQuery("PRAGMA foreign_key_check")) { assertFalse(rows.next()); }
        try(var sql = connection.createStatement(); var rows = sql.executeQuery("PRAGMA integrity_check"))
        { assertTrue(rows.next()); assertEquals("ok", rows.getString(1)); }
    }

    /** Compare every old column, including statistics and sqlite_sequence, ignoring only the marker and new fields. */
    private static Map<String,List<String>> contents(Connection connection) throws Exception
    {
        Map<String,List<String>> result = new LinkedHashMap<>();
        List<String> tables = new ArrayList<>();
        try(var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name"))
        { while(rows.next()) tables.add(rows.getString(1)); }
        for(String table: tables)
        {
            if(CurrentFormatTestDatabase.addedCurrentTable(connection, table)) continue;
            List<String> values = new ArrayList<>();
            try(var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT * FROM \"" + table + "\""))
            {
                while(rows.next())
                {
                    if(table.equals("database_metadata") && DatabaseFormatCatalog.FORMAT_VERSION_KEY.equals(rows.getString(1))) continue;
                    List<String> row = new ArrayList<>();
                    for(int i = 1; i <= rows.getMetaData().getColumnCount(); i++)
                    {
                        if("source_call_uuid".equals(rows.getMetaData().getColumnName(i)) ||
                            table.equals("receiver_activity_event") && "duration_ms".equals(rows.getMetaData().getColumnName(i))) continue;
                        if(CurrentFormatTestDatabase.addedMessageCounter(rows, i)) continue;
                        Object value = CurrentFormatTestDatabase.retainedValue(table, rows, i);
                        row.add(value instanceof byte[] bytes ? java.util.HexFormat.of().formatHex(bytes) : String.valueOf(value));
                    }
                    values.add(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(row.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                }
            }
            values.sort(String::compareTo); result.put(table, values);
        }
        return result;
    }
}
