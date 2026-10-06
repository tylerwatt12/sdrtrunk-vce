/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format41To42DatabaseMigrationTest
{
    private static final String TARGET_INDEX = "idx_receiver_activity_event_target_time";
    private static final String TARGET_SQL = """
        CREATE INDEX idx_receiver_activity_event_target_time
        ON receiver_activity_event(target_identity_summary_id,observed_at_ms,id,channel_id,
            target_observed_local_id,target_observed_working_id,target_kind_code,radio_system_id)
        WHERE target_identity_summary_id IS NOT NULL
        """;
    @TempDir Path mTemporaryFolder;

    @Test
    void preservesEveryRowAllocatorAndOtherDefinitionAcrossRollbackAndRetry() throws Exception
    {
        Path source = Format41TestDatabase.create(mTemporaryFolder.resolve("source.sqlite"));
        Map<String,TableContents> expected;
        Map<String,String> definitions;
        Map<String,String> plannerStatistics;
        try(Connection connection = open(source); Statement statement = connection.createStatement())
        {
            statement.execute("ANALYZE "+TARGET_INDEX);
            plannerStatistics = plannerStatistics(connection);
            assertTrue(plannerStatistics.containsKey(TARGET_INDEX));
            expected = tableContents(connection);
            definitions = schemaDefinitions(connection);
            assertTrue(expected.get("activity_event_identity_member").rows()>0);
            assertTrue(expected.get("web_user").rows()>0);
            assertTrue(expected.get("configuration_channel").rows()>0);
            assertEquals(List.of(1L),new Format41To42DatabaseMigration().validateSource(connection).stream()
                .map(DatabaseMigrationEffect::affectedRows).toList());
        }
        byte[] bytes = Files.readAllBytes(source);
        assertThrows(SQLException.class,() -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(source));
        assertArrayEquals(bytes,Files.readAllBytes(source));
        for(int attempt=0;attempt<2;attempt++)
        {
            Path candidate = Files.copy(source,mTemporaryFolder.resolve("candidate-"+attempt+".sqlite"));
            try(Connection connection = open(candidate); Statement statement = connection.createStatement())
            {
                connection.setAutoCommit(false);
                new Format41To42DatabaseMigration().migrate(connection);
                connection.rollback();
                assertEquals(41,DatabaseFormatCatalog.inspect(connection).version());
                assertEquals(definitions,schemaDefinitions(connection));
                assertEquals(plannerStatistics,plannerStatistics(connection));
                assertEquals(expected,tableContents(connection));
                List<DatabaseMigrationEffect> effects =
                    new Format41To42DatabaseMigration().migrateAndReport(connection, false);
                DatabaseFormatCatalog.stamp(connection, 42);
                connection.commit();
                assertEquals(List.of(1L),effects.stream()
                    .map(DatabaseMigrationEffect::affectedRows).toList());
                assertEquals(expected,tableContents(connection),"Every application row and allocator is preserved");
                Map<String,String> remainingStatistics = new LinkedHashMap<>(plannerStatistics);
                remainingStatistics.remove(TARGET_INDEX);
                assertEquals(remainingStatistics,plannerStatistics(connection),
                    "Only the replaced index loses its derived planner estimate");
                assertEquals(42,DatabaseFormatCatalog.inspect(connection).version());
                assertEquals(DatabaseFormatCatalog.requireVersion(42).fingerprint(),
                    SqliteSchemaValidator.fingerprint(connection));
                Map<String,String> actual = schemaDefinitions(connection);
                assertEquals(definitions.keySet(),actual.keySet(),"No table or additional index is introduced");
                definitions.forEach((name,sql) -> assertEquals(name.equals("index:"+TARGET_INDEX) ?
                    normalizeSql(TARGET_SQL) : sql,actual.get(name),name));
                assertEquals("ok",scalar(statement,"PRAGMA integrity_check"));
                assertEquals("0",scalar(statement,"SELECT count(*) FROM pragma_foreign_key_check"));
            }
            try(Connection connection = open(candidate); Statement statement = connection.createStatement())
            {
                DatabaseMigrationChain.MigrationReport report = DatabaseMigrationChain.migrate(connection);
                assertEquals(DatabaseFormatCatalog.CURRENT_VERSION - 42, report.steps().size());
                assertEquals("format-42-to-43", report.steps().getFirst().id());
                assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                    DatabaseFormatCatalog.requireCurrent(connection).version());
                assertEquals("0", scalar(statement, """
                    SELECT count(*) FROM web_user WHERE json_extract(preferences_json, '$.version')<>11
                        OR json_extract(preferences_json, '$.presentation.live_row_density') IS NOT 'normal'
                    """));
            }
            SdrTrunkDatabaseStartup.validateGlobalDatabase(candidate);
            assertArrayEquals(bytes,Files.readAllBytes(source));
        }
    }

    @Test
    void failedIndexRebuildRollsBackAndCanBeRetried() throws Exception
    {
        Path database = Format41TestDatabase.create(mTemporaryFolder.resolve("interrupted.sqlite"));
        Map<String,TableContents> before;
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value+1 FROM n WHERE value<10000)
                INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,action_code,
                    target_identity_summary_id,target_kind_code,target_observed_local_id)
                SELECT 910000+value,900001,900001,10000+value,4,900002,1,100 FROM n
                """);
            new Format41To42DatabaseMigration().validateSource(connection);
            before = tableContents(connection);
            Map<String,String> definitions = schemaDefinitions(connection);
            Map<String,String> statistics = plannerStatistics(connection);
            connection.setAutoCommit(false);
            java.util.concurrent.atomic.AtomicInteger callbacks = new java.util.concurrent.atomic.AtomicInteger();
            org.sqlite.ProgressHandler.setHandler(connection,1000,new org.sqlite.ProgressHandler()
            {
                @Override protected int progress()
                {
                    callbacks.incrementAndGet();
                    return 1;
                }
            });
            try
            {
                assertThrows(SQLException.class,() -> ReceiverActivitySchema.rebuildTargetIdentityForeignKeyIndex(connection));
                assertTrue(callbacks.get()>0,"The populated replacement must execute the cancellation callback");
            }
            finally
            {
                org.sqlite.ProgressHandler.clearHandler(connection);
            }
            //SQLite INTERRUPT automatically rolls back this write transaction, including the earlier DROP.
            assertEquals(definitions,schemaDefinitions(connection));
            assertEquals(statistics,plannerStatistics(connection));
            assertEquals(before,tableContents(connection));
        }
        try(Connection connection = open(database))
        {
            connection.setAutoCommit(false);
            new Format41To42DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 42);
            connection.commit();
            assertEquals(42,DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(before,tableContents(connection));
            DatabaseMigrationChain.migrate(connection);
            connection.commit();
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                DatabaseFormatCatalog.requireCurrent(connection).version());
        }
    }

    @Test
    void freshAndMigratedCurrentSchemasMatchAndCurrentMigrationIsANoOp() throws Exception
    {
        Path fresh = mTemporaryFolder.resolve("fresh.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(fresh);
        Path migrated = Format44TestDatabase.create(mTemporaryFolder.resolve("migrated.sqlite"));
        try(Connection current = open(migrated); Connection clean = open(fresh))
        {
            assertEquals(DatabaseFormatCatalog.current().fingerprint(),SqliteSchemaValidator.fingerprint(clean));
            assertEquals(SqliteSchemaValidator.fingerprint(clean),SqliteSchemaValidator.fingerprint(current));
            assertEquals(schemaDefinitions(clean).keySet(),schemaDefinitions(current).keySet());
            Map<String,TableContents> before = tableContents(current);
            assertTrue(DatabaseMigrationChain.migrate(current).steps().isEmpty());
            assertEquals(before,tableContents(current));
        }
    }

    @Test
    void refusesOlderAndMixedSourcesWithoutMutation() throws Exception
    {
        Path older = Format40TestDatabase.create(mTemporaryFolder.resolve("older.sqlite"));
        Path mixed = Format41TestDatabase.create(mTemporaryFolder.resolve("mixed.sqlite"));
        try(Connection connection = open(mixed); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP INDEX "+TARGET_INDEX);
            statement.executeUpdate(TARGET_SQL.replace("WHERE target_identity_summary_id IS NOT NULL", ""));
        }
        for(Path source: List.of(older,mixed))
        {
            byte[] bytes = Files.readAllBytes(source);
            try(Connection connection = open(source))
            {
                String fingerprint = SqliteSchemaValidator.fingerprint(connection);
                Map<String,TableContents> before = tableContents(connection);
                assertThrows(SQLException.class,() -> new Format41To42DatabaseMigration().migrate(connection));
                assertEquals(fingerprint,SqliteSchemaValidator.fingerprint(connection));
                assertEquals(before,tableContents(connection));
            }
            assertArrayEquals(bytes,Files.readAllBytes(source));
        }
    }

    /** Hashes every fixture field without exposing credentials or preference contents. */
    private static Map<String,TableContents> tableContents(Connection connection) throws Exception
    {
        List<String> tables = new ArrayList<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
            "SELECT name FROM sqlite_schema WHERE type='table' AND name NOT GLOB 'sqlite_stat*' ORDER BY name"))
        {
            while(rows.next()) tables.add(rows.getString(1));
        }
        Map<String,TableContents> contents = new LinkedHashMap<>();
        for(String table: tables)
        {
            String escaped = table.replace("\"","\"\"");
            String projection = "*";
            int columns;
            try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT "+projection+" FROM \""+escaped+"\" LIMIT 0"))
            {
                columns = rows.getMetaData().getColumnCount();
            }
            String ordering = java.util.stream.IntStream.rangeClosed(1,columns).mapToObj(Integer::toString)
                .collect(java.util.stream.Collectors.joining(","));
            String filter = table.equals("database_metadata") ? " WHERE key<>'database_format_version'" : "";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT "+projection+" FROM \""+escaped+"\""+filter+" ORDER BY "+ordering))
            {
                while(rows.next())
                {
                    count++;
                    for(int column=1;column<=columns;column++)
                    {
                        byte[] value = rows.getBytes(column);
                        digest.update((byte)(value==null ? 0 : 1));
                        if(value!=null)
                        {
                            digest.update(Integer.toString(value.length).getBytes(StandardCharsets.UTF_8));
                            digest.update((byte)0);digest.update(value);
                        }
                    }
                }
            }
            contents.put(table,new TableContents(count,HexFormat.of().formatHex(digest.digest())));
        }
        return contents;
    }

    private static Map<String,String> plannerStatistics(Connection connection) throws SQLException
    {
        Map<String,String> statistics = new LinkedHashMap<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
            "SELECT tbl,idx,stat FROM sqlite_stat1 ORDER BY tbl,idx"))
        {
            while(rows.next()) statistics.put(rows.getString(2)==null ? rows.getString(1) : rows.getString(2),
                rows.getString(3));
        }
        return statistics;
    }

    private static Map<String,String> schemaDefinitions(Connection connection) throws Exception
    {
        Map<String,String> indexes = new LinkedHashMap<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
            "SELECT type,name,sql FROM sqlite_schema WHERE sql IS NOT NULL ORDER BY type,name"))
        {
            while(rows.next()) indexes.put(rows.getString(1)+":"+rows.getString(2),normalizeSql(rows.getString(3)));
        }
        return indexes;
    }

    private static String normalizeSql(String sql)
    {
        return sql.replaceAll("\\s+"," ").trim().replace("IF NOT EXISTS ","").replaceAll("\\s*,\\s*",",");
    }

    private static Connection open(Path database) throws SQLException
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:"+database);
        // The complete offline chain rebuilds parent tables with the same FK-off boundary as the migrator.
        try(Statement statement = connection.createStatement()) { statement.execute("PRAGMA foreign_keys=OFF"); }
        return connection;
    }

    private static String scalar(Statement statement, String sql) throws SQLException
    {
        try(ResultSet rows = statement.executeQuery(sql)) { return rows.next() ? rows.getString(1) : null; }
    }

    private record TableContents(long rows, String digest) {}
}
