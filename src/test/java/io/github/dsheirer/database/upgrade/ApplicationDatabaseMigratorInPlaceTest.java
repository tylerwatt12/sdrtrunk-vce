/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationDatabaseMigratorInPlaceTest
{
    @TempDir Path mTemporaryFolder;

    @Test
    void ownedUpdateOmitsDerivedIdentityWalkWhileImportedUpdateRemainsStrict() throws Exception
    {
        Path database = Format30TestDatabase.create(mTemporaryFolder.resolve("source.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            addNativeSystem(statement);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(radio_system_id, identity_kind_code,
                    home_wacn, home_system_id, identity_id, first_seen_ms, last_seen_ms)
                VALUES(500, 2, 703710, 291, 16777213, 1000, 1000)
                """);
            assertThrows(SQLException.class, () -> ApplicationDatabaseMigrator.validateCurrentDatabase(connection));
        }
        Path staged = mTemporaryFolder.resolve(".sdrtrunk.sqlite.migration-" + UUID.randomUUID());
        Files.copy(database, staged);
        try(PrintStream output = new PrintStream(new ByteArrayOutputStream()))
        {
            assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED,
                ApplicationDatabaseMigrator.run(new String[]{staged.toString()}, output, output));
        }
        assertEquals("30", format(staged));
        ApplicationDatabaseMigrator.migrateInPlace(database, ignored -> { });
        assertEquals(Integer.toString(DatabaseFormatCatalog.CURRENT_VERSION), format(database));
        assertEquals("1", scalar(database,
            "SELECT count(*) FROM radio_system_identity_summary WHERE identity_id=16777213"));
    }

    @Test
    void dropsOnlyUnusableChannelAndItsOwnedHistoryAcrossLegacyAndModernSources() throws Exception
    {
        for(int version: List.of(25, 30, 31))
        {
            Path database = mTemporaryFolder.resolve("damaged-channel-" + version + ".sqlite");
            Class.forName("io.github.dsheirer.database.upgrade.Format" + version + "TestDatabase")
                .getMethod("create", Path.class).invoke(null, database);
            String credentials = scalar(database, "SELECT hex(password_hash) FROM web_user WHERE id=3");
            try(Connection connection = open(database); Statement statement = connection.createStatement())
            {
                addNativeSystem(statement);
                statement.executeUpdate("""
                    INSERT INTO receiver_channel(id, configuration_id, first_seen_ms, last_seen_ms)
                    SELECT id, configuration_id, 1000, 1000 FROM configuration_channel WHERE id IN (1,2)
                    """);
                statement.executeUpdate("""
                    INSERT INTO receiver_activity_event(id, channel_id, radio_system_id, observed_at_ms, action_code)
                    VALUES(501, 1, 500, 1000, 1), (502, 2, 500, 1000, 1)
                    """);
                statement.executeUpdate("""
                    INSERT INTO radio_system_identity_summary(id, radio_system_id, identity_kind_code,
                        home_wacn, home_system_id, identity_id, first_seen_ms, last_seen_ms)
                    VALUES(500, 500, 1, -1, -1, 123, 1000, 1000)
                    """);
                statement.executeUpdate("""
                    INSERT INTO activity_event_identity_member(event_id, radio_system_id, identity_summary_id)
                    VALUES(501,500,500),(502,500,500)
                    """);
                statement.executeUpdate("""
                    INSERT INTO radio_system(id, system_key, configuration_id, protocol_code, address_domain_code,
                        first_seen_ms, last_seen_ms)
                    SELECT 501, 'nxdn-c:channel:' || configuration_id, configuration_id, 4, 1, 1000, 1000
                    FROM configuration_channel WHERE id=1
                    """);
                statement.executeUpdate("UPDATE receiver_channel SET radio_system_id=501," +
                    " radio_system_assigned_at_ms=1000 WHERE id=2");
                statement.executeUpdate("UPDATE configuration_channel SET config_json='{}' WHERE id=1");
            }
            var result = ApplicationDatabaseMigrator.migrateInPlace(database, ignored -> { });
            assertEquals(Integer.toString(DatabaseFormatCatalog.CURRENT_VERSION), format(database));
            assertEquals("0", scalar(database, "SELECT count(*) FROM configuration_channel WHERE id=1"));
            assertEquals("1", scalar(database, "SELECT count(*) FROM configuration_channel WHERE id=2"));
            assertEquals("0", scalar(database, "SELECT count(*) FROM receiver_channel WHERE id=1"));
            assertEquals("1", scalar(database, "SELECT count(*) FROM receiver_channel WHERE id=2"));
            assertEquals("0", scalar(database, "SELECT count(*) FROM receiver_activity_event WHERE id=501"));
            assertEquals("1", scalar(database, "SELECT count(*) FROM receiver_activity_event WHERE id=502"));
            assertEquals("0", scalar(database, "SELECT count(*) FROM activity_event_identity_member WHERE event_id=501"));
            assertEquals("1", scalar(database, "SELECT count(*) FROM activity_event_identity_member WHERE event_id=502"));
            assertEquals("1", scalar(database, "SELECT count(*) FROM radio_system WHERE id=500"));
            assertEquals("0", scalar(database, "SELECT count(*) FROM radio_system WHERE id=501"));
            assertEquals("1", scalar(database,
                "SELECT count(*) FROM receiver_channel WHERE id=2 AND radio_system_id IS NULL"));
            assertEquals(credentials, scalar(database, "SELECT hex(password_hash) FROM web_user WHERE id=3"));
            assertTrue(result.helperOutput().contains("DROP unusable saved channel rows: 1 row(s)"), result.helperOutput());
            try(Connection connection = open(database); Statement statement = connection.createStatement())
            {
                assertFalse(statement.executeQuery("PRAGMA foreign_key_check").next());
            }
        }
    }

    @Test
    void defaultsMissingRequiredSettingOnOlderAndCurrentSources() throws Exception
    {
        for(int version: List.of(30, DatabaseFormatCatalog.CURRENT_VERSION))
        {
            Path database = mTemporaryFolder.resolve("missing-setting-" + version + ".sqlite");
            Class.forName("io.github.dsheirer.database.upgrade.Format" + version + "TestDatabase")
                .getMethod("create", Path.class).invoke(null, database);
            execute(database, "DELETE FROM application_settings WHERE key='spectrum_snap_country'");
            AtomicInteger backups = new AtomicInteger();
            var result = ApplicationDatabaseMigrator.migrateInPlace(database, ignored -> { }, backups::incrementAndGet);
            assertEquals(1, backups.get());
            assertTrue(result.sourcePlan().requiresMigration());
            assertEquals(Integer.toString(DatabaseFormatCatalog.CURRENT_VERSION), format(database));
            assertEquals("US", scalar(database,
                "SELECT json_extract(settings_json,'$.country_code') FROM application_settings WHERE key='spectrum_snap_country'"));
        }
    }

    @Test
    void healthyCurrentDatabaseSkipsBackupAndDoesNotChangeItsBytes() throws Exception
    {
        Path database = Format43TestDatabase.create(mTemporaryFolder.resolve("current.sqlite"));
        byte[] before = Files.readAllBytes(database);
        var result = ApplicationDatabaseMigrator.migrateInPlace(database, ignored -> { },
            () -> fail("A healthy current database must not be backed up"));
        assertFalse(result.sourcePlan().requiresMigration());
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test
    void cancellationBeforeCommitRollsBackFormatAndPreferences() throws Exception
    {
        Path database = Format30TestDatabase.create(mTemporaryFolder.resolve("cancel.sqlite"));
        String before = scalar(database, "SELECT preferences_json FROM web_user WHERE id=3");
        assertThrows(CancellationException.class, () -> ApplicationDatabaseMigrator.migrateInPlace(database,
            message -> { if(message.equals("Committing the database update")) throw new CancellationException(); }));
        assertEquals("30", format(database));
        assertEquals(before, scalar(database, "SELECT preferences_json FROM web_user WHERE id=3"));
    }

    @Test
    void completionObserverFailureCannotReportACommittedUpdateAsRetryable() throws Exception
    {
        Path database = Format30TestDatabase.create(mTemporaryFolder.resolve("completed.sqlite"));
        List<String> messages = new ArrayList<>();
        ApplicationDatabaseMigrator.migrateInPlace(database, message ->
        {
            messages.add(message);
            if(message.equals("Database update committed")) throw new IllegalStateException("observer failed");
        });
        assertEquals(Integer.toString(DatabaseFormatCatalog.CURRENT_VERSION), format(database));
        assertTrue(messages.stream().anyMatch(message -> message.contains("observer reported a warning")));
    }

    @Test
    void failedBackupRollsBackAndLeavesTheSourceUntouched() throws Exception
    {
        Path database = Format30TestDatabase.create(mTemporaryFolder.resolve("backup-failed.sqlite"));
        byte[] before = Files.readAllBytes(database);
        IOException failure = assertThrows(IOException.class, () -> ApplicationDatabaseMigrator.migrateInPlace(
            database, ignored -> { }, () -> { throw new IOException("backup refused"); }));
        assertEquals("backup refused", failure.getMessage());
        assertEquals("30", format(database));
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test
    void backupUnderTheWriteLockCapturesCommittedWalBeforeAnyConversion() throws Exception
    {
        Path database = Format30TestDatabase.create(mTemporaryFolder.resolve("wal.sqlite"));
        Path backup = mTemporaryFolder.resolve("backup.sqlite");
        try(Connection writer = open(database); Statement statement = writer.createStatement())
        {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.executeUpdate("UPDATE web_user SET preferences_json=json_set(preferences_json," +
                " '$.appearance.theme','dark') WHERE id=3");
            assertTrue(Files.size(Path.of(database + "-wal")) > 0);
            ApplicationDatabaseMigrator.migrateInPlace(database, ignored -> { },
                () -> SqliteDatabaseSnapshot.create(database, backup));
            assertEquals("30", format(backup));
            assertEquals("dark", scalar(backup,
                "SELECT json_extract(preferences_json,'$.appearance.theme') FROM web_user WHERE id=3"));
            assertEquals(Integer.toString(DatabaseFormatCatalog.CURRENT_VERSION), format(database));
        }
    }

    @Test
    void writableOwnedOpenRecoversAHotRollbackJournal() throws Exception
    {
        Path database = Format30TestDatabase.create(mTemporaryFolder.resolve("hot.sqlite"));
        Process writer = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"), HotJournalWriter.class.getName(), database.toString())
            .redirectErrorStream(true).redirectOutput(mTemporaryFolder.resolve("hot-writer.log").toFile()).start();
        assertTrue(writer.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, writer.exitValue(), Files.readString(mTemporaryFolder.resolve("hot-writer.log")));
        assertTrue(Files.size(Path.of(database + "-journal")) > 0);
        ApplicationDatabaseMigrator.migrateInPlace(database, ignored -> { });
        assertEquals(Integer.toString(DatabaseFormatCatalog.CURRENT_VERSION), format(database));
        assertEquals("0", scalar(database,
            "SELECT count(*) FROM application_settings WHERE key='uncommitted_probe'"));
    }

    @Test
    void exhaustedLegacyFactoryAllocatorIsRepairedAfterItsBackupAndBeforeAnyInsert() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("exhausted.sqlite"));
        Path backup = mTemporaryFolder.resolve("exhausted-backup.sqlite");
        execute(database, "UPDATE sqlite_sequence SET seq=9223372036854775807 WHERE name='alias_list'");
        assertEquals("9223372036854775807", scalar(database,
            "SELECT seq FROM sqlite_sequence WHERE name='alias_list'"));
        var result = ApplicationDatabaseMigrator.migrateInPlace(database, ignored -> { },
            () -> SqliteDatabaseSnapshot.create(database, backup));
        assertEquals("14", format(backup));
        assertEquals("9223372036854775807", scalar(backup,
            "SELECT seq FROM sqlite_sequence WHERE name='alias_list'"));
        assertEquals(Integer.toString(DatabaseFormatCatalog.CURRENT_VERSION), format(database));
        assertTrue(result.helperOutput().contains("DEFAULT SQLite identity high-water marks: 1 row(s)"),
            result.helperOutput());
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            assertEquals(DatabaseFormatCatalog.current(), DatabaseFormatCatalog.requireCurrent(connection).descriptor());
            assertFalse(statement.executeQuery("PRAGMA foreign_key_check").next());
            try(ResultSet rows = statement.executeQuery("EXPLAIN QUERY PLAN SELECT coalesce(max(id),0) " +
                "FROM alias_list WHERE typeof(id)='integer' AND id>0 AND id<9007199254740991"))
            {
                assertTrue(rows.next());
                assertTrue(rows.getString(4).contains("INTEGER PRIMARY KEY"), rows.getString(4));
            }
        }
    }

    @Test
    void refusesNewerMarkersAndMixedSchemasWithoutChangingThem() throws Exception
    {
        for(String change: List.of(
            "UPDATE database_metadata SET value='32' WHERE key='database_format_version'",
            "ALTER TABLE configuration_channel ADD COLUMN mixed_build_field TEXT"))
        {
            Path database = Format30TestDatabase.create(mTemporaryFolder.resolve("refuse-" + change.hashCode() + ".sqlite"));
            execute(database, change);
            byte[] before = Files.readAllBytes(database);
            assertThrows(SQLException.class, () -> ApplicationDatabaseMigrator.migrateInPlace(database, ignored -> { }));
            assertArrayEquals(before, Files.readAllBytes(database));
        }
    }

    public static class HotJournalWriter
    {
        public static void main(String[] args) throws Exception
        {
            Connection connection = open(Path.of(args[0]));
            Statement statement = connection.createStatement();
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA cache_size=1");
            statement.execute("BEGIN IMMEDIATE");
            statement.executeUpdate("INSERT INTO application_settings(key,settings_json,updated_at_ms) " +
                "VALUES('uncommitted_probe',json_object('probe',hex(zeroblob(1048576))),1000)");
            Runtime.getRuntime().halt(0);
        }
    }

    private static void addNativeSystem(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            INSERT INTO radio_system(id, system_key, protocol_code, p25_wacn, p25_system_id, first_seen_ms, last_seen_ms)
            VALUES(500, 'p25:abcde:123', 1, 703710, 291, 1000, 1000)
            """);
    }

    private static Connection open(Path database) throws SQLException
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }

    private static void execute(Path database, String sql) throws SQLException
    {
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate(sql);
        }
    }

    private static String format(Path database) throws SQLException
    {
        return scalar(database, "SELECT value FROM database_metadata WHERE key='database_format_version'");
    }

    private static String scalar(Path database, String sql) throws SQLException
    {
        try(Connection connection = open(database); Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(sql))
        {
            return rows.next() ? rows.getString(1) : null;
        }
    }
}
