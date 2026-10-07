/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Release regression for the actual published Nightly's format through this candidate's format. */
class NightlyFormat20ToCurrentMigrationTest
{
    // Independently verified in published build 34930803451, commit 09bfa0417dc2a597eb32da42e9e8a2d56780865f.
    private static final String PUBLISHED_FORMAT_20_FINGERPRINT =
        "190b00e68d988236732ad25a10de187e54944a6dd6f7d1cd7a7fd3e60956722a";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir Path mTemporaryFolder;

    @Test
    void updatesThePopulatedPublishedFormatWithEitherBackupChoiceAndPreservesItsData() throws Exception
    {
        for(boolean withBackup: List.of(true, false))
        {
            Path database = populatedPublishedDatabase("owned-" + withBackup + ".sqlite");
            Snapshot before = snapshot(database);
            Path backup = mTemporaryFolder.resolve("recovery-" + withBackup + ".sqlite");
            AtomicInteger backups = new AtomicInteger();
            List<String> progress = new ArrayList<>();
            var result = ApplicationDatabaseMigrator.migrateInPlace(database, progress::add,
                withBackup ? () -> {
                    backups.incrementAndGet();
                    assertPublishedSource(database);
                    SqliteDatabaseSnapshot.create(database, backup);
                } : null);

            assertEquals(withBackup ? 1 : 0, backups.get());
            assertEquals(20, result.sourcePlan().source().version());
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION - 20, result.sourcePlan().steps().size());
            assertEquals("format-20-to-21", result.sourcePlan().steps().getFirst().id());
            assertEquals(CurrentFormatTestDatabase.lastMigrationStepId(), result.sourcePlan().steps().getLast().id());
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION - 20, progress.stream().filter(message -> message.startsWith("Step ")).count());
            assertTrue(progress.contains("Database update committed"));
            assertPreservedAndCurrent(database, before);
            if(withBackup)
            {
                assertPublishedSource(backup);
                assertEquals(before, snapshot(backup), "The recovery snapshot must precede every conversion");
            }

            byte[] currentBytes = Files.readAllBytes(database);
            var noOp = ApplicationDatabaseMigrator.migrateInPlace(database, ignored -> { },
                () -> fail("An already upgraded healthy profile must not create another backup"));
            assertFalse(noOp.sourcePlan().requiresMigration());
            assertArrayEquals(currentBytes, Files.readAllBytes(database));
        }
    }

    @Test
    void stagedMigratorReachesCurrentWithoutChangingTheSelectedPublishedSource() throws Exception
    {
        Path source = populatedPublishedDatabase("selected-source.sqlite");
        Snapshot before = snapshot(source);
        byte[] sourceBytes = Files.readAllBytes(source);
        for(int attempt = 0; attempt < 2; attempt++)
        {
            Path staged = mTemporaryFolder.resolve(".sdrtrunk.sqlite.migration-" + UUID.randomUUID());
            Files.copy(source, staged);
            try(ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8))
            {
                assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS,
                    ApplicationDatabaseMigrator.run(new String[]{staged.toString()}, output, output),
                    "The real staged Application Migrator entry point must accept the published source");
                assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("migration and validation complete"));
            }
            assertPreservedAndCurrent(staged, before);
            assertArrayEquals(sourceBytes, Files.readAllBytes(source));
            assertPublishedSource(source);
        }
    }

    @Test
    void aFailureAfterAllStepsRollsBackTheEntireUpgradeAndAllowsRetry() throws Exception
    {
        Path database = populatedPublishedDatabase("retry.sqlite");
        Snapshot before = snapshot(database);
        assertThrows(CancellationException.class, () -> ApplicationDatabaseMigrator.migrateInPlace(database,
            message -> {
                if(message.equals("Committing the database update")) throw new CancellationException("test rollback");
            }));
        assertPublishedSource(database);
        assertEquals(before, snapshot(database), "No earlier adjacent step may remain committed after failure");
        ApplicationDatabaseMigrator.migrateInPlace(database, ignored -> { });
        assertPreservedAndCurrent(database, before);
    }

    @Test
    void failedRecoverySnapshotAndOrdinaryStartupLeaveFormat20Unchanged() throws Exception
    {
        Path database = populatedPublishedDatabase("backup-failure.sqlite");
        byte[] before = Files.readAllBytes(database);
        assertThrows(SQLException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database));
        assertArrayEquals(before, Files.readAllBytes(database), "Normal startup must not migrate an older profile");
        assertThrows(IOException.class, () -> ApplicationDatabaseMigrator.migrateInPlace(database, ignored -> { },
            () -> { throw new IOException("Injected backup failure"); }));
        assertArrayEquals(before, Files.readAllBytes(database));
        assertPublishedSource(database);
    }

    private Path populatedPublishedDatabase(String name) throws Exception
    {
        Path database = Format20TestDatabase.create(mTemporaryFolder.resolve(name));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            // Values beyond 32 bits also exercise migration preservation of SQLite-owned IDs and parent keys.
            statement.executeUpdate("""
                INSERT INTO radio_system(id,system_key,protocol_code,p25_wacn,p25_system_id,first_seen_ms,last_seen_ms)
                VALUES(5000000000,'p25:abcde:123',1,703710,291,1000,3000)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms,
                    radio_system_id,radio_system_assigned_at_ms)
                SELECT 5000000000,configuration_id,1000,3000,5000000000,1000
                FROM configuration_channel WHERE id=1
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,home_wacn,
                    home_system_id,identity_id,first_seen_ms,last_seen_ms,logical_call_count)
                VALUES(5000000001,5000000000,2,781824,1183,12345,1000,3000,2),
                    (5000000002,5000000000,1,703710,291,123,1000,3000,2)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,action_code,
                    source_observed_local_id,target_observed_local_id,target_kind_code,
                    source_identity_summary_id,target_identity_summary_id,frequency_hz,lcn_band,lcn_number,
                    timeslot,encrypted,observed_rfss,observed_site)
                VALUES(5000000000,5000000000,5000000000,2000,1,12345,123,1,
                    5000000001,5000000002,851012500,0,123,1,0,1,1),
                    (5000000001,5000000000,5000000000,3000,1,12345,123,1,
                    5000000001,5000000002,851012500,0,123,2,1,1,1)
                """);
            statement.executeUpdate("""
                INSERT INTO activity_event_identity_member(event_id,radio_system_id,identity_summary_id,observed_local_id)
                VALUES(5000000000,5000000000,5000000002,123),
                    (5000000001,5000000000,5000000002,123)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_logical_call_bucket(radio_system_id,bucket_start_ms,logical_call_count,
                    encrypted_logical_call_count,recorded_output_count,streamed_output_count)
                VALUES(5000000000,0,2,1,1,1)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_logical_call_identity_bucket(radio_system_id,bucket_start_ms,
                    identity_role_code,identity_kind_code,identity_summary_id,logical_call_count,
                    encrypted_logical_call_count,recorded_output_count,streamed_output_count)
                VALUES(5000000000,0,1,1,5000000002,2,1,1,1),
                    (5000000000,0,2,2,5000000001,2,1,1,1)
                """);
            statement.executeUpdate("""
                UPDATE web_user SET preferences_json=json_set(preferences_json,'$.appearance.theme','dark',
                    '$.presentation.show_only_active_trunked_channels',json('true'), '$.tables.channels',
                    json('{"schema":["name","frequency"],"column_order":["frequency","name"],
                    "column_widths":{"name":240,"frequency":160},"hidden_columns":["frequency"]}'))
                WHERE id=3
                """);
            statement.executeUpdate("UPDATE sqlite_sequence SET seq=seq+10000 WHERE name IN " +
                "('alias_list','alias','configuration_channel','configuration_broadcast_stream','web_user')");
            statement.executeUpdate("UPDATE database_metadata SET value='complete' WHERE key='initial_admin_setup'");
        }
        assertPublishedSource(database);
        return database;
    }

    private static void assertPublishedSource(Path database) throws SQLException
    {
        try(Connection connection = open(database))
        {
            assertEquals(20, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(PUBLISHED_FORMAT_20_FINGERPRINT, SqliteSchemaValidator.fingerprint(connection));
            assertEquals("ok", scalar(connection, "PRAGMA integrity_check"));
            assertEquals("0", scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
        }
    }

    private static void assertPreservedAndCurrent(Path database, Snapshot before) throws Exception
    {
        try(Connection connection = open(database))
        {
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.requireCurrent(connection).version());
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            assertEquals(before.contents(), tableContents(connection, before.columns()),
                "Valid configuration, aliases, credentials, activity, relationships or allocators changed");
            assertEquals("1", scalar(connection, "SELECT count(*) FROM web_user WHERE tier='ADMIN' AND primary_admin=1"));
            assertEquals("0", scalar(connection, "SELECT count(*) FROM web_user WHERE tier='ADMIN' AND primary_admin=0"));
            assertEquals("USER", scalar(connection, "SELECT tier FROM web_user WHERE id=3"));
            for(var user: before.preferences().entrySet())
            {
                JsonNode target = MAPPER.readTree(scalar(connection,
                    "SELECT preferences_json FROM web_user WHERE id=" + user.getKey()));
                assertOriginalPreferences(user.getValue(), target, "");
                assertEquals(11, target.path("version").asInt());
                assertTrue(target.path("appearance").path("hue").isNull());
                assertEquals("talker_alias", target.path("presentation").path("source_name_display").asText());
                assertEquals("normal", target.path("presentation").path("live_row_density").asText());
            }
            // Historical full tuples remain unproven: migration must not invent ISSI mappings or Alias matches.
            assertEquals("0", scalar(connection, "SELECT count(*) FROM p25_subscriber_identity"));
            assertEquals("0", scalar(connection, "SELECT count(*) FROM p25_wuid_assignment_observation_summary"));
            assertEquals("0", scalar(connection, "SELECT count(*) FROM radio_system_identity_summary " +
                "WHERE p25_subscriber_identity_id IS NOT NULL"));
            assertEquals("2", scalar(connection, "SELECT count(*) FROM activity_event_identity_member " +
                "WHERE channel_id=5000000000"));
            assertEquals("ok", scalar(connection, "PRAGMA integrity_check"));
            assertEquals("0", scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
        }
        SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database);
        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
    }

    private static void assertOriginalPreferences(JsonNode source, JsonNode target, String path)
    {
        if(path.equals("/version")) return;
        if(source.isObject()) source.fields().forEachRemaining(field ->
            assertOriginalPreferences(field.getValue(), target.path(field.getKey()), path + "/" + field.getKey()));
        else assertEquals(source, target, "Existing personal choice changed at " + path);
    }

    private static Snapshot snapshot(Path database) throws Exception
    {
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            Map<String,List<String>> columns = new LinkedHashMap<>();
            List<String> tables = new ArrayList<>();
            try(ResultSet rows = statement.executeQuery("SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name"))
            {
                while(rows.next()) tables.add(rows.getString(1));
            }
            for(String table: tables)
            {
                List<String> names = new ArrayList<>();
                try(ResultSet rows = statement.executeQuery("PRAGMA table_info(\"" + table + "\")"))
                {
                    while(rows.next()) names.add(rows.getString("name"));
                }
                if(table.equals("web_user")) names.removeAll(List.of("tier","preferences_json",
                    "preferences_revision","updated_at_ms"));
                columns.put(table, List.copyOf(names));
            }
            Map<Long,JsonNode> preferences = new LinkedHashMap<>();
            try(ResultSet rows = statement.executeQuery("SELECT id,preferences_json FROM web_user ORDER BY id"))
            {
                while(rows.next()) preferences.put(rows.getLong(1), MAPPER.readTree(rows.getString(2)));
            }
            return new Snapshot(columns, tableContents(connection, columns), preferences);
        }
    }

    private static Map<String,TableContents> tableContents(Connection connection, Map<String,List<String>> tables)
        throws Exception
    {
        Map<String,TableContents> contents = new LinkedHashMap<>();
        for(var table: tables.entrySet())
        {
            String projection = table.getValue().stream().map(name -> "\"" + name + "\"")
                .collect(java.util.stream.Collectors.joining(","));
            String filter = switch(table.getKey()) {
                case "database_metadata" -> " WHERE key<>'database_format_version'";
                case "application_settings" -> " WHERE key<>'setup_wizard'";
                // New tables may acquire their own allocator. Every original table's high-water mark must survive.
                case "sqlite_sequence" -> " WHERE name IN (" + tables.keySet().stream()
                    .map(name -> "'" + name + "'").collect(java.util.stream.Collectors.joining(",")) + ")";
                default -> "";
            };
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT " + projection + " FROM \"" + table.getKey() + "\"" + filter + " ORDER BY " + projection))
            {
                while(rows.next())
                {
                    count++;
                    for(int column = 1; column <= table.getValue().size(); column++)
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
            contents.put(table.getKey(), new TableContents(count, HexFormat.of().formatHex(digest.digest())));
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
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private record Snapshot(Map<String,List<String>> columns, Map<String,TableContents> contents,
                            Map<Long,JsonNode> preferences) {}
    private record TableContents(long rows, String digest) {}
}
