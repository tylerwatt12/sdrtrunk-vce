/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format44To45DatabaseMigrationTest
{
    private static final Set<String> CONSOLIDATED_TABLES = Set.of("radio_system_identity_summary",
        "receiver_activity_event", "trunked_logical_call_identity_bucket", "p25_site_call_identity_bucket",
        "trunked_radio_group_summary", "trunked_radio_affiliation", "trunked_radio_channel_presence",
        "trunked_radio_channel_presence_clear");
    @TempDir Path mTemporaryFolder;

    @Test
    void consolidatesEveryRadioOwnerWithoutChangingEventsAddressesSystemTotalsOrAllocators() throws Exception
    {
        Path database = Format44TestDatabase.createWithHomeRadioSplits(mTemporaryFolder.resolve("populated44.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            assertEquals(44, DatabaseFormatCatalog.inspect(connection).version());
            Map<String,TableContents> before = contents(connection);
            Map<String,String> schema = schemaDefinitions(connection);
            connection.setAutoCommit(false);
            var report = DatabaseMigrationChain.migrate(connection);
            connection.commit();
            assertEquals(1, report.steps().size());
            assertEquals("format-44-to-45", report.steps().getFirst().id());
            assertCounts(report.steps().getFirst().effects());
            Map<String,TableContents> after = contents(connection);
            before.forEach((table, rows) -> {
                if(!CONSOLIDATED_TABLES.contains(table)) assertEquals(rows, after.get(table), table);
            });
            assertEquals(before.get("receiver_activity_event").rows(), after.get("receiver_activity_event").rows());
            Map<String,String> targetSchema = schemaDefinitions(connection);
            assertEquals(schema.keySet(), targetSchema.keySet());
            schema.forEach((object, sql) -> {
                if(!object.equals("view:receiver_activity_event_resolved"))
                    assertEquals(sql, targetSchema.get(object), object);
            });
            assertNotEquals(schema.get("view:receiver_activity_event_resolved"),
                targetSchema.get("view:receiver_activity_event_resolved"));
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            assertConsolidated(connection);
            assertEquals(45, DatabaseFormatCatalog.requireCurrent(connection).version());
            assertHealthy(connection);
        }
        SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database);
        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
    }

    @Test
    void upgradeWithEitherBackupChoicePreservesTheSourceAndDoesNotRepeatConsolidation() throws Exception
    {
        for(boolean withBackup: List.of(false, true))
        {
            Path database = Format44TestDatabase.createWithHomeRadioSplits(
                mTemporaryFolder.resolve("upgrade44-" + withBackup + ".sqlite"));
            Path backup = mTemporaryFolder.resolve("backup44-" + withBackup + ".sqlite");
            AtomicInteger backups = new AtomicInteger();
            Map<String,TableContents> before;
            try(Connection connection = open(database)) { before = contents(connection); }
            var report = ApplicationDatabaseMigrator.migrateInPlace(database, ignored -> { }, withBackup ? () -> {
                backups.incrementAndGet();
                SqliteDatabaseSnapshot.create(database, backup);
            } : null);
            assertEquals(withBackup ? 1 : 0, backups.get());
            assertEquals(44, report.sourcePlan().source().version());
            assertEquals(CurrentFormatTestDatabase.lastMigrationStepId(), report.sourcePlan().steps().getLast().id());
            if(withBackup)
            {
                try(Connection connection = open(backup))
                {
                    assertEquals(44, DatabaseFormatCatalog.inspect(connection).version());
                    assertEquals(before, contents(connection));
                }
            }
            try(Connection connection = open(database)) { assertConsolidated(connection); assertHealthy(connection); }
            byte[] currentBytes = Files.readAllBytes(database);
            var noOp = ApplicationDatabaseMigrator.migrateInPlace(database, ignored -> { },
                () -> fail("A healthy current format must not repeat the migration or backup"));
            assertFalse(noOp.sourcePlan().requiresMigration());
            assertArrayEquals(currentBytes, Files.readAllBytes(database));
        }
    }

    @Test
    void anInterruptedMergeRollsBackEveryReferenceAndTheUnchangedSourceCanBeRetried() throws Exception
    {
        Path source = Format44TestDatabase.createWithHomeRadioSplits(mTemporaryFolder.resolve("source44.sqlite"));
        byte[] sourceBytes = Files.readAllBytes(source);
        Path candidate = mTemporaryFolder.resolve("candidate.sqlite");
        Files.copy(source, candidate);
        try(Connection connection = open(candidate); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            Map<String,TableContents> before = contents(connection);
            statement.execute("""
                CREATE TEMP TRIGGER fail_home_radio_merge BEFORE DELETE ON main.radio_system_identity_summary
                WHEN OLD.id=5000000010 BEGIN SELECT RAISE(ABORT,'Injected consolidation interruption'); END
                """);
            connection.setAutoCommit(false);
            SQLException failure = assertThrows(SQLException.class, () -> DatabaseMigrationChain.migrate(connection));
            assertTrue(failure.getMessage().contains("Injected consolidation interruption"));
            connection.rollback();
            assertEquals(44, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(before, contents(connection));
            assertHealthy(connection);
            statement.execute("DROP TRIGGER fail_home_radio_merge");
            var retry = DatabaseMigrationChain.migrate(connection);
            connection.commit();
            assertCounts(retry.steps().getFirst().effects());
            assertConsolidated(connection);
            assertHealthy(connection);
        }
        assertArrayEquals(sourceBytes, Files.readAllBytes(source));
        Path retry = mTemporaryFolder.resolve("second-copy.sqlite");
        Files.copy(source, retry);
        try(Connection connection = open(retry))
        {
            connection.setAutoCommit(false);
            DatabaseMigrationChain.migrate(connection);
            connection.commit();
            assertConsolidated(connection);
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
        }
    }

    @Test
    void startupAndTheAdjacentStepRefuseTheWrongFormatWithoutMutation() throws Exception
    {
        Path old = Format44TestDatabase.createWithHomeRadioSplits(mTemporaryFolder.resolve("old44.sqlite"));
        byte[] before = Files.readAllBytes(old);
        assertThrows(SQLException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(old));
        assertArrayEquals(before, Files.readAllBytes(old));
        Path wrong = Format43TestDatabase.create(mTemporaryFolder.resolve("wrong43.sqlite"));
        try(Connection connection = open(wrong))
        {
            var contents = contents(connection);
            connection.setAutoCommit(false);
            assertThrows(SQLException.class, () -> new Format44To45DatabaseMigration().migrate(connection));
            assertEquals(contents, contents(connection));
            connection.rollback();
        }
    }

    @Test
    void theNewViewDistinguishesFormat45AndTheCurrentFormatIsANoOp() throws Exception
    {
        Path database = Format45TestDatabase.create(mTemporaryFolder.resolve("current45.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            assertNotEquals(DatabaseFormatCatalog.requireVersion(44).fingerprint(),
                DatabaseFormatCatalog.requireVersion(45).fingerprint());
            var before = contents(connection);
            assertTrue(DatabaseMigrationChain.migrate(connection).steps().isEmpty());
            assertEquals(before, contents(connection));
            statement.executeUpdate("DELETE FROM database_metadata WHERE key='database_format_version'");
            assertEquals(45, DatabaseFormatCatalog.inspect(connection).version());
            assertFalse(DatabaseFormatCatalog.inspect(connection).markerPresent());
            assertThrows(SQLException.class, () -> DatabaseFormatCatalog.requireCurrent(connection));
            var adoption = DatabaseMigrationChain.migrate(connection);
            assertEquals("adopt-global-format-marker", adoption.steps().getFirst().id());
            assertEquals(before, contents(connection));
            assertEquals(45, DatabaseFormatCatalog.requireCurrent(connection).version());
        }
    }

    @Test
    void preservesHistoricalRoleCreditsWithoutGuessingAnUnrecordedLogicalCallUnion() throws Exception
    {
        Path database = Format44TestDatabase.createWithHomeRadioSplits(mTemporaryFolder.resolve("role-credits44.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            // Older writers could credit both split owners on one private call. The retained aggregates also permit
            // two disjoint calls with these same values, so migration cannot subtract a guessed overlap.
            statement.executeUpdate("UPDATE radio_system_identity_summary SET logical_call_count=1, " +
                "source_logical_call_count=1,target_logical_call_count=0 WHERE id=5000000010");
            statement.executeUpdate("UPDATE radio_system_identity_summary SET logical_call_count=1, " +
                "source_logical_call_count=0,target_logical_call_count=1 WHERE id=5000000011");
            statement.executeUpdate("DELETE FROM trunked_logical_call_identity_bucket WHERE radio_system_id=5000000000");
            statement.executeUpdate("""
                INSERT INTO trunked_logical_call_identity_bucket(radio_system_id,bucket_start_ms,identity_role_code,
                    identity_kind_code,identity_summary_id,logical_call_count)
                VALUES(5000000000,0,2,2,5000000010,1),(5000000000,0,1,2,5000000011,1)
                """);
            statement.executeUpdate("UPDATE trunked_logical_call_bucket SET logical_call_count=1 WHERE radio_system_id=5000000000");
            connection.setAutoCommit(false);
            DatabaseMigrationChain.migrate(connection);
            connection.commit();
            assertEquals("2|1|1", scalar(connection, "SELECT logical_call_count||'|'||source_logical_call_count||'|'||" +
                "target_logical_call_count FROM radio_system_identity_summary WHERE id=5000000011"));
            assertEquals("2", scalar(connection, "SELECT count(*) FROM trunked_logical_call_identity_bucket WHERE identity_summary_id=5000000011"));
            assertEquals("1", scalar(connection, "SELECT logical_call_count FROM trunked_logical_call_bucket WHERE radio_system_id=5000000000"));
            assertHealthy(connection);
        }
    }

    private static void assertCounts(List<DatabaseMigrationEffect> effects)
    {
        assertEquals(List.of(3L, 1L, 1L, 1L), effects.stream().map(DatabaseMigrationEffect::affectedRows).toList());
        assertEquals(DatabaseMigrationEffect.Kind.PRESERVE, effects.get(2).kind());
    }

    private static void assertConsolidated(Connection connection) throws SQLException
    {
        assertEquals("0", scalar(connection, "SELECT count(*) FROM radio_system_identity_summary WHERE id=5000000010"));
        assertEquals("1000|9000|4|3|1|2|3|5|3|133|92|Current name|7000|5000000000", scalar(connection, """
            SELECT first_seen_ms||'|'||last_seen_ms||'|'||logical_call_count||'|'||source_logical_call_count||'|'||
                target_logical_call_count||'|'||encrypted_logical_call_count||'|'||recorded_output_count||'|'||
                streamed_output_count||'|'||register_count||'|'||last_encryption_algorithm_id||'|'||
                last_encryption_key_id||'|'||last_talker_alias||'|'||last_talker_alias_seen_ms||'|'||p25_subscriber_identity_id
            FROM radio_system_identity_summary WHERE id=5000000011
            """));
        assertEquals("2", scalar(connection, "SELECT count(*) FROM receiver_activity_event WHERE source_identity_summary_id=5000000011"));
        assertEquals("1", scalar(connection, "SELECT count(*) FROM receiver_activity_event WHERE target_identity_summary_id=5000000011"));
        assertEquals("2", scalar(connection, "SELECT count(*) FROM activity_event_identity_member WHERE identity_summary_id=5000000020"));
        assertEquals("4|2|3|5", scalar(connection, """
            SELECT logical_call_count||'|'||encrypted_logical_call_count||'|'||recorded_output_count||'|'||streamed_output_count
            FROM trunked_logical_call_identity_bucket WHERE identity_summary_id=5000000011
            """));
        assertEquals("4|2|10900077|10900077|9000", scalar(connection, """
            SELECT observed_call_count||'|'||encrypted_observed_call_count||'|'||observed_local_id||'|'||observed_working_id||'|'||last_observed_at_ms
            FROM p25_site_call_identity_bucket WHERE identity_summary_id=5000000011
            """));
        assertEquals("1000|9000|4|2|3|5|3|133|92", scalar(connection, """
            SELECT first_seen_ms||'|'||last_seen_ms||'|'||logical_call_count||'|'||encrypted_logical_call_count||'|'||
                recorded_output_count||'|'||streamed_output_count||'|'||register_count||'|'||last_encryption_algorithm_id||'|'||last_encryption_key_id
            FROM trunked_radio_group_summary WHERE radio_identity_id=5000000011
            """));
        assertEquals("5000000000|8000", scalar(connection, "SELECT channel_id||'|'||confirmed_at_ms FROM trunked_radio_affiliation WHERE radio_identity_id=5000000011"));
        assertEquals("5000000000|8000", scalar(connection, "SELECT channel_id||'|'||confirmed_at_ms FROM trunked_radio_channel_presence WHERE radio_identity_id=5000000011"));
        assertEquals("8500", scalar(connection, "SELECT cleared_at_ms FROM trunked_radio_channel_presence_clear WHERE radio_identity_id=5000000011"));
        assertEquals("781824|937|1", scalar(connection, "SELECT home_wacn||'|'||home_system_id||'|'||(p25_subscriber_identity_id IS NULL) FROM radio_system_identity_summary WHERE id=5000000012"));
        assertEquals("1", scalar(connection, "SELECT count(*) FROM radio_system_identity_summary WHERE id=5000000014 AND home_wacn=-1 AND home_system_id=-1"));
        assertEquals("1", scalar(connection, "SELECT count(*) FROM receiver_activity_event WHERE source_identity_summary_id=5000000014"));
        assertEquals("1", scalar(connection, "SELECT count(*) FROM radio_system_identity_summary WHERE id=5000000013 AND home_wacn=0xABCDE AND identity_id=12345"));
        assertEquals("781825|937", scalar(connection, "SELECT home_wacn||'|'||home_system_id FROM radio_system_identity_summary WHERE id=5000000030"));
        assertEquals("-1|-1", scalar(connection, "SELECT home_wacn||'|'||home_system_id FROM radio_system_identity_summary WHERE id=5000000040"));
        assertEquals("2", scalar(connection, "SELECT count(*) FROM p25_subscriber_identity WHERE id>=5000000000"), "No canonical evidence may be invented");
        assertEquals("v1-r-abcde-123-9001", scalar(connection, "SELECT source_identity_key FROM receiver_activity_event_resolved WHERE id=900001"));
        assertEquals("v1-r-abcde-123-9002", scalar(connection, "SELECT target_identity_key FROM receiver_activity_event_resolved WHERE id=900002"));
        assertEquals("v1-r-x-x-10900079", scalar(connection, "SELECT source_identity_key FROM receiver_activity_event_resolved WHERE id=5000000002"));
    }

    private static Map<String,String> schemaDefinitions(Connection connection) throws SQLException
    {
        Map<String,String> definitions = new LinkedHashMap<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
            "SELECT type,name,sql FROM sqlite_schema WHERE sql IS NOT NULL ORDER BY type,name"))
        {
            while(rows.next()) definitions.put(rows.getString(1) + ":" + rows.getString(2), rows.getString(3));
        }
        return definitions;
    }

    private static Map<String,TableContents> contents(Connection connection) throws Exception
    {
        List<String> tables = new ArrayList<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
            "SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name"))
        {
            while(rows.next()) tables.add(rows.getString(1));
        }
        Map<String,TableContents> contents = new LinkedHashMap<>();
        for(String table: tables)
        {
            int columns;
            try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT * FROM \"" + table + "\" LIMIT 0"))
            {
                columns = rows.getMetaData().getColumnCount();
            }
            String order = java.util.stream.IntStream.rangeClosed(1, columns).mapToObj(Integer::toString)
                .collect(java.util.stream.Collectors.joining(","));
            String filter = table.equals("database_metadata") ? " WHERE key<>'database_format_version'" : "";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT * FROM \"" + table + "\"" + filter + " ORDER BY " + order))
            {
                while(rows.next())
                {
                    count++;
                    for(int column = 1; column <= columns; column++)
                    {
                        byte[] value = rows.getBytes(column);
                        digest.update((byte)(value == null ? 0 : 1));
                        if(value != null)
                        {
                            digest.update(Integer.toString(value.length).getBytes(StandardCharsets.UTF_8));
                            digest.update((byte)0);
                            digest.update(value);
                        }
                    }
                }
            }
            contents.put(table, new TableContents(count, HexFormat.of().formatHex(digest.digest())));
        }
        return contents;
    }

    private static Connection open(Path database) throws SQLException
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }

    private static String scalar(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            assertTrue(rows.next(), sql);
            return rows.getString(1);
        }
    }

    private static void assertHealthy(Connection connection) throws SQLException
    {
        assertEquals("ok", scalar(connection, "PRAGMA integrity_check"));
        assertEquals("0", scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
    }

    private record TableContents(long rows, String digest) {}
}
