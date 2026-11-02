/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format68To69DatabaseMigrationTest
{
    @TempDir Path directory;

    @Test void keepsEventsChildrenCountersAndAllocatorAndRollsBackBothRolesBeforeRetry() throws Exception
    {
        Path configuration = Format68TestDatabase.create(directory.resolve("prior.sqlite"));
        Path history = ActivityDatabasePath.forConfiguration(configuration);
        Map<String,List<String>> before;
        long count;
        try(var connection = open(history); var statement = connection.createStatement())
        {
            assertEquals(ConfigurationHistoryDatabase.FORMAT_63_HISTORY_FINGERPRINT,
                SqliteSchemaValidator.fingerprint(connection));
            for(long id = 900000000001L; id <= 900000000003L; id++)
            {
                int event = id == 900000000001L ? 8 : 7;
                int encrypted = id == 900000000003L ? 1 : 0;
                statement.executeUpdate("INSERT INTO receiver_activity_event(id,channel_id,observed_at_ms,action_code," +
                    "event_type_code,encrypted,encryption_algorithm_id,encryption_key_id,duration_ms,source_call_uuid) " +
                    "SELECT " + id + ",channel_id,1000000+" + id + ",4," + event + "," + encrypted +
                    "," + (encrypted == 1 ? "132,5" : "NULL,NULL") + ",1234," +
                    "'9a220f1a-7ddc-4182-8dd0-fb60506aeb51' FROM receiver_activity_event LIMIT 1");
            }
            statement.executeUpdate("UPDATE sqlite_sequence SET seq=900000000100 WHERE name='receiver_activity_event'");
            count = scalar(connection, "SELECT count(*) FROM receiver_activity_event");
            before = snapshot(connection);
        }
        byte[] originalConfiguration = Files.readAllBytes(configuration);
        assertThrows(Exception.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(configuration));
        assertArrayEquals(originalConfiguration, Files.readAllBytes(configuration));
        assertThrows(IllegalStateException.class, () -> ApplicationDatabaseMigrator.migrateInPlace(configuration, phase -> {
            if(phase.equals("Validating the updated schema and configuration")) throw new IllegalStateException("injected");
        }));
        try(var connection = open(history))
        {
            assertEquals(ConfigurationHistoryDatabase.FORMAT_63_HISTORY_FINGERPRINT,
                SqliteSchemaValidator.fingerprint(connection));
            assertEquals(before, snapshot(connection));
        }
        try(var connection = open(configuration)) { assertEquals(68, DatabaseFormatCatalog.inspect(connection).version()); }
        ApplicationDatabaseMigrator.migrateInPlace(configuration, null);
        ConfigurationHistoryDatabase.validatePair(configuration);
        try(var connection = open(history))
        {
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.requireCurrent(connection).version());
            assertEquals(ConfigurationHistoryDatabase.HISTORY_FINGERPRINT, SqliteSchemaValidator.fingerprint(connection));
            assertEquals(count, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(2, scalar(connection, "SELECT encryption_state FROM receiver_activity_event WHERE id=900000000001"));
            assertEquals(0, scalar(connection, "SELECT encryption_state FROM receiver_activity_event WHERE id=900000000002"));
            assertEquals(2, scalar(connection, "SELECT encryption_state FROM receiver_activity_event WHERE id=900000000003"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM receiver_activity_event WHERE encryption_state=1"));
            assertEquals(900000000100L, scalar(connection, "SELECT seq FROM sqlite_sequence WHERE name='receiver_activity_event'"));
            assertEquals(before, snapshot(connection), "Every original event field, child, counter and allocator is retained");
            assertEquals(0, scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
            try(var statement = connection.createStatement(); var row = statement.executeQuery("PRAGMA integrity_check"))
            { assertTrue(row.next()); assertEquals("ok", row.getString(1)); }
            try(var statement = connection.createStatement(); var rows = statement.executeQuery(
                "PRAGMA table_info(receiver_activity_event)"))
            { while(rows.next()) assertNotEquals("encrypted", rows.getString("name")); }
        }
        byte[] configCurrent = Files.readAllBytes(configuration), historyCurrent = Files.readAllBytes(history);
        ApplicationDatabaseMigrator.migrateInPlace(configuration, null);
        assertArrayEquals(configCurrent, Files.readAllBytes(configuration));
        assertArrayEquals(historyCurrent, Files.readAllBytes(history));
    }

    @Test void aCurrentMarkerCannotHideAnUnconvertedHistoryCompanion() throws Exception
    {
        Path configuration = Format68TestDatabase.create(directory.resolve("mixed.sqlite"));
        Path history = ActivityDatabasePath.forConfiguration(configuration);
        byte[] original = Files.readAllBytes(history);
        try(var connection = open(configuration)) { DatabaseFormatCatalog.stamp(connection, 69); }
        assertThrows(Exception.class, () -> ConfigurationHistoryDatabase.validatePairIdentity(configuration));
        assertThrows(Exception.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(configuration));
        assertArrayEquals(original, Files.readAllBytes(history));
    }

    @Test void refusesUnmanagedOrForeignKeyEnabledLegacyCallsBeforeChangingTheExtractedConfiguration() throws Exception
    {
        Path configuration = Format68TestDatabase.create(directory.resolve("legacy-guards.sqlite"));
        Path history = ActivityDatabasePath.forConfiguration(configuration);
        Path stage = ActivityDatabasePath.configurationStage(history);
        Files.copy(configuration, stage);
        byte[] originalStage = Files.readAllBytes(stage);
        byte[] originalHistory = Files.readAllBytes(history);
        try(var connection = open(history); var statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=OFF");
            assertThrows(SQLException.class, () -> new Format68To69DatabaseMigration().migrateSource(connection));
            assertArrayEquals(originalStage, Files.readAllBytes(stage));
            statement.execute("PRAGMA foreign_keys=ON");
            connection.setAutoCommit(false);
            assertThrows(SQLException.class, () -> new Format68To69DatabaseMigration().migrateSource(connection));
            connection.rollback(); connection.setAutoCommit(true);
        }
        assertArrayEquals(originalStage, Files.readAllBytes(stage));
        assertArrayEquals(originalHistory, Files.readAllBytes(history));
    }

    @Test void freshAndMigratedHistoryUseTheSameExactDefinition() throws Exception
    {
        Path fresh = directory.resolve("fresh.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(fresh);
        Path migrated = Format69TestDatabase.create(directory.resolve("migrated.sqlite"));
        try(var first = open(ActivityDatabasePath.forConfiguration(fresh));
            var second = open(ActivityDatabasePath.forConfiguration(migrated)))
        {
            assertEquals(SqliteSchemaValidator.fingerprint(first), SqliteSchemaValidator.fingerprint(second));
            assertEquals(ConfigurationHistoryDatabase.HISTORY_FINGERPRINT, SqliteSchemaValidator.fingerprint(first));
        }
        ConfigurationHistoryDatabase.validatePair(fresh);
        ConfigurationHistoryDatabase.validatePairIdentity(migrated);
    }

    /** Compare retained data independently of the deliberately replaced encryption field and format metadata. */
    static Map<String,List<String>> snapshot(Connection connection) throws Exception
    {
        Map<String,List<String>> result = new LinkedHashMap<>();
        List<String> tables = new ArrayList<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(
            "SELECT name FROM sqlite_schema WHERE type='table' AND name<>'database_metadata' " +
                "AND (name NOT GLOB 'sqlite_*' OR name='sqlite_sequence') ORDER BY name"))
        { while(rows.next()) tables.add(rows.getString(1)); }
        for(String table: tables)
        {
            List<String> rows = new ArrayList<>();
            try(var statement = connection.createStatement(); ResultSet values = statement.executeQuery("SELECT * FROM " + table))
            {
                var metadata = values.getMetaData();
                while(values.next())
                {
                    StringBuilder row = new StringBuilder();
                    for(int index=1; index<=metadata.getColumnCount(); index++)
                    {
                        if(table.equals("receiver_activity_event") &&
                            List.of("encrypted", "encryption_state").contains(metadata.getColumnName(index))) continue;
                        Object value = values.getObject(index);
                        String text = value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : String.valueOf(value);
                        row.append(text.length()).append(':').append(text).append('|');
                    }
                    rows.add(row.toString());
                }
            }
            rows.sort(String::compareTo);
            result.put(table, rows);
        }
        return result;
    }
    private static Connection open(Path database) throws Exception
    { return DriverManager.getConnection("jdbc:sqlite:" + database); }
    private static long scalar(Connection connection, String sql) throws Exception
    {
        try(var statement=connection.createStatement();var row=statement.executeQuery(sql))
        { assertTrue(row.next()); return row.getLong(1); }
    }
}
