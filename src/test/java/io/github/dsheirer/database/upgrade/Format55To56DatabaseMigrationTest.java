/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.database.settings.ReceiverSettingsSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format55To56DatabaseMigrationTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VOICE_KEY = "audio.voice_enhancement";
    private static final String PORTABLE_KEY = "portable_java_preferences_v1";
    private static final String AUDIO_NODE = "user/io/github/dsheirer/preference/audio";
    private static final String AGC_KEY = "audio.output.continuous.agc.enabled";
    @TempDir Path directory;

    @Test void freshTargetContainsOnlySupportedRequiredChoicesAndMigrationIsANoOp() throws Exception
    {
        Path database = directory.resolve("fresh.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = open(database))
        {
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.requireCurrent(connection).version());
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            assertEquals(List.of("remote.links", "audio.paging_tones"), ReceiverSettingsSchema.KEYS);
            ReceiverSettingsSchema.validate(connection);
            assertRetiredStateAbsent(connection);
            Map<String,List<String>> before = contents(connection, false);
            assertTrue(DatabaseMigrationChain.migrate(connection).steps().isEmpty());
            assertEquals(before, contents(connection, false));
            integrity(connection);
        }
    }

    @Test void populatedMigrationDropsOnlyTheTwoExactRetiredItemsAndPreservesEveryOtherRow() throws Exception
    {
        Path database = Format55TestDatabase.create(directory.resolve("populated.sqlite"));
        try(Connection connection = open(database); var sql = connection.createStatement())
        {
            save(connection, VOICE_KEY, "{\"enabled\":true}", 901);
            save(connection, PORTABLE_KEY, populatedPortablePreferences(), 902);
            save(connection, VOICE_KEY + ".backup", "{\"enabled\":true}", 903);
            sql.execute("ANALYZE");
            assertEquals(55, DatabaseFormatCatalog.inspect(connection).version());
            Map<String,List<String>> expected = contents(connection, true);
            var preflight = DatabaseMigrationChain.validateSource(connection, DatabaseFormatCatalog.inspect(connection));
            assertEquals(CurrentFormatTestDatabase.migrationStepIdsFrom(55), preflight.steps().stream().map(DatabaseMigrationChain.StepPreflight::id).toList());
            assertEquals(2, preflight.steps().getFirst().effects().stream()
                .filter(effect -> effect.kind() == DatabaseMigrationEffect.Kind.DROP).count());

            connection.setAutoCommit(false);
            var report = CurrentFormatTestDatabase.migrateToVersion(connection, 62);
            connection.commit();

            assertEquals(CurrentFormatTestDatabase.migrationStepIdsBetween(55, 62), report.steps().stream().map(DatabaseMigrationChain.StepReport::id).toList());
            List<DatabaseMigrationEffect> drops = report.steps().getFirst().effects().stream()
                .filter(effect -> effect.kind() == DatabaseMigrationEffect.Kind.DROP).toList();
            assertEquals(2, drops.size());
            assertTrue(drops.stream().allMatch(effect -> effect.affectedRows() == 1));
            assertEquals(2, dropCount(report));
            assertEquals(expected, contents(connection, false));
            assertRetiredStateAbsent(connection);
            assertEquals(62, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(DatabaseFormatCatalog.requireVersion(62).fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            integrity(connection);
        }
        CurrentFormatTestDatabase.validateCurrentPair(database);
    }

    @Test void anEmptiedAudioNodeIsRemovedWithoutRemovingThePortableDocumentOrOtherNodes() throws Exception
    {
        Path database = Format55TestDatabase.create(directory.resolve("empty-node.sqlite"));
        try(Connection connection = open(database))
        {
            save(connection, PORTABLE_KEY, JSON.writeValueAsString(Map.of(
                AUDIO_NODE, Map.of(AGC_KEY, "false"),
                "unrelated/node", Map.of(AGC_KEY, "keep this unrelated key", "keep", "yes"))), 904);
            Map<String,List<String>> expected = contents(connection, true);
            connection.setAutoCommit(false);
            var report = CurrentFormatTestDatabase.migrateToVersion(connection, 62);
            connection.commit();
            assertEquals(2, dropCount(report));
            assertEquals(expected, contents(connection, false));
            ObjectNode portable = readPortable(connection);
            assertFalse(portable.has(AUDIO_NODE));
            assertEquals("keep this unrelated key", portable.path("unrelated/node").path(AGC_KEY).asText());
            assertEquals(62, DatabaseFormatCatalog.inspect(connection).version());
        }
        CurrentFormatTestDatabase.validateCurrentPair(database);
    }

    @Test void absentContinuousGainDoesNotCreateDefaultsOrReportAPhantomPreferenceDrop() throws Exception
    {
        Path database = Format55TestDatabase.create(directory.resolve("already-absent.sqlite"));
        try(Connection connection = open(database))
        {
            save(connection, PORTABLE_KEY, "{\"unrelated/node\":{\"keep\":\"yes\"}}", 905);
            assertEquals(55, DatabaseFormatCatalog.inspect(connection).version());
            Map<String,List<String>> expected = contents(connection, true);
            connection.setAutoCommit(false);
            var report = CurrentFormatTestDatabase.migrateToVersion(connection, 62);
            connection.commit();
            assertEquals(1, dropCount(report));
            assertEquals(List.of(1L, 0L), report.steps().getFirst().effects().stream()
                .filter(effect -> effect.kind() == DatabaseMigrationEffect.Kind.DROP)
                .map(DatabaseMigrationEffect::affectedRows).toList());
            assertEquals(expected, contents(connection, false));
            assertRetiredStateAbsent(connection);
            assertEquals(62, DatabaseFormatCatalog.inspect(connection).version());
        }
        CurrentFormatTestDatabase.validateCurrentPair(database);
    }

    @Test void transactionRollbackRestoresExactSourceAndTheSameSourceCanBeRetried() throws Exception
    {
        Path database = Format55TestDatabase.create(directory.resolve("retry.sqlite"));
        try(Connection connection = open(database))
        {
            save(connection, PORTABLE_KEY, populatedPortablePreferences(), 906);
        }
        byte[] original = Files.readAllBytes(database);
        Map<String,List<String>> expected;
        try(Connection connection = open(database))
        {
            Map<String,List<String>> before = contents(connection, false);
            expected = contents(connection, true);
            connection.setAutoCommit(false);
            assertEquals(2, dropCount(CurrentFormatTestDatabase.migrateToVersion(connection, 62)));
            assertRetiredStateAbsent(connection);
            connection.rollback();
            assertEquals(55, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(before, contents(connection, false));
        }
        assertArrayEquals(original, Files.readAllBytes(database));
        try(Connection connection = open(database))
        {
            connection.setAutoCommit(false);
            assertEquals(2, dropCount(CurrentFormatTestDatabase.migrateToVersion(connection, 62)));
            connection.commit();
            assertEquals(62, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(expected, contents(connection, false));
            assertTrue(CurrentFormatTestDatabase.migrateToVersion(connection, 62).steps().isEmpty());
            integrity(connection);
        }
        CurrentFormatTestDatabase.validateCurrentPair(database);
    }

    @Test void ownedProfilePreCommitFailureRollsBackAndRetryUsesTheSameAdjacentMigration() throws Exception
    {
        Path root = directory.resolve("owned-profile");
        Path database = Format55TestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(root));
        try(Connection connection = open(database))
        {
            save(connection, PORTABLE_KEY, populatedPortablePreferences(), 907);
        }
        Map<String,List<String>> before;
        Map<String,List<String>> expected;
        try(Connection connection = open(database))
        {
            before = contents(connection, false);
            expected = contents(connection, true, true);
        }
        assertThrows(CancellationException.class, () -> new ApplicationMigrationService().migrateCurrent(root,
            false, message -> {
                if(message.equals("Committing the database update")) throw new CancellationException("Synthetic cancellation");
            }));
        try(Connection connection = open(database))
        {
            assertEquals(55, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(before, contents(connection, false));
        }
        new ApplicationMigrationService().migrateCurrent(root, false, null);
        try(Connection connection = open(database))
        {
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.requireCurrent(connection).version());
            assertEquals(expected, currentContents(connection));
            assertRetiredStateAbsent(connection);
            integrity(connection);
        }
    }

    @Test void normalStartupRefusesFormat55WithoutMigratingOrChangingItsFiles() throws Exception
    {
        Path database = Format55TestDatabase.create(directory.resolve("old-startup.sqlite"));
        byte[] original = Files.readAllBytes(database);
        assertThrows(java.io.IOException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database));
        assertArrayEquals(original, Files.readAllBytes(database));
        try(Connection connection = open(database))
        {
            assertEquals(55, DatabaseFormatCatalog.inspect(connection).version());
        }
    }

    @Test void ownedProfileRetiresGainBeforeCanonicalRepairOfHistoricallyPermissiveJson() throws Exception
    {
        List<String> inputs = List.of(
            "{\"" + AUDIO_NODE + "\":{\"" + AGC_KEY + "\":\"true\"},\"unrelated/node\":{\"keep\":\"first\",\"keep\":\"last\"}}",
            "{\"" + AUDIO_NODE + "\":{\"" + AGC_KEY + "\":\"false\"},\"unrelated/node\":{\"keep\":\"kept\"}} {}");
        List<String> kept = List.of("last", "kept");
        for(int index = 0; index < inputs.size(); index++)
        {
            Path root = directory.resolve("permissive-json-" + index);
            Path database = Format55TestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(root));
            Map<String,List<String>> expected;
            try(Connection connection = open(database))
            {
                // Seed the previously tolerated damaged row; ordinary writes correctly reject trailing JSON.
                try(var sql = connection.createStatement())
                {
                    sql.execute("PRAGMA ignore_check_constraints=ON");
                    try { save(connection, PORTABLE_KEY, inputs.get(index), 909); }
                    finally { sql.execute("PRAGMA ignore_check_constraints=OFF"); }
                }
                expected = contents(connection, true, true);
            }
            var result = new ApplicationMigrationService().migrateCurrent(root, false, null);
            assertEquals(55, result.sourceFormat().version());
            assertTrue(result.helperOutput().contains("DROP retired voice-enhancement setting: 1 row(s)"),
                result::helperOutput);
            assertTrue(result.helperOutput().contains("DROP retired continuous-gain preference: 1 row(s)"),
                result::helperOutput);
            try(Connection connection = open(database))
            {
                assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.requireCurrent(connection).version());
                assertEquals(kept.get(index), readPortable(connection).path("unrelated/node").path("keep").asText());
                assertRetiredStateAbsent(connection);
                assertEquals(expected, currentContents(connection));
                integrity(connection);
            }
        }
    }

    @Test void currentValidationAndStartupRefuseRetiredTypedOrPortableSettingsWithoutRepair() throws Exception
    {
        for(boolean portable: List.of(false, true))
        {
            Path database = directory.resolve("retired-current-" + portable + ".sqlite");
            SdrTrunkDatabaseStartup.createGlobalDatabase(database);
            try(Connection connection = open(database))
            {
                if(portable) save(connection, PORTABLE_KEY,
                    JSON.writeValueAsString(Map.of(AUDIO_NODE, Map.of(AGC_KEY, "false"))), 908);
                else save(connection, VOICE_KEY, "{\"enabled\":false}", 908);
                assertThrows(SQLException.class, () -> DatabaseFormatCatalog.requireCurrent(connection));
            }
            byte[] original = Files.readAllBytes(database);
            assertThrows(SQLException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database));
            assertArrayEquals(original, Files.readAllBytes(database));
        }
    }

    private static String populatedPortablePreferences() throws Exception
    {
        return JSON.writeValueAsString(Map.of(
            AUDIO_NODE, Map.of(AGC_KEY, "true", AGC_KEY + ".extra", "keep suffix",
                "audio.enhancement.enabled", "true", "audio.output.device", "default"),
            AUDIO_NODE + "/child", Map.of(AGC_KEY, "keep child node"),
            "unrelated/node", Map.of(AGC_KEY, "keep other node", "sentinel", "keep")));
    }

    private static void assertRetiredStateAbsent(Connection connection) throws Exception
    {
        try(var sql = connection.createStatement(); var row = sql.executeQuery(
            "SELECT COUNT(*) FROM application_settings WHERE key='audio.voice_enhancement'"))
        {
            assertTrue(row.next());
            assertEquals(0, row.getInt(1));
        }
        ObjectNode portable = readPortable(connection);
        if(portable != null) assertFalse(portable.path(AUDIO_NODE).has(AGC_KEY));
    }

    private static long dropCount(DatabaseMigrationChain.MigrationReport report)
    {
        return report.steps().stream().flatMap(step -> step.effects().stream())
            .filter(effect -> effect.kind() == DatabaseMigrationEffect.Kind.DROP)
            .mapToLong(DatabaseMigrationEffect::affectedRows).sum();
    }

    private static ObjectNode readPortable(Connection connection) throws Exception
    {
        try(var query = connection.prepareStatement("SELECT settings_json FROM application_settings WHERE key=?"))
        {
            query.setString(1, PORTABLE_KEY);
            try(var row = query.executeQuery())
            {
                return row.next() ? (ObjectNode)JSON.readTree(row.getString(1)) : null;
            }
        }
    }

    /** Whole-file row comparison excludes only the changing global marker and the two declared retirements. */
    private static Map<String,List<String>> contents(Connection connection, boolean applyRetirement) throws Exception
    {
        return contents(connection, applyRetirement, false);
    }

    private static Map<String,List<String>> contents(Connection connection, boolean applyRetirement,
                                                    boolean splitProjection) throws Exception
    {
        return contents(connection, applyRetirement, splitProjection, false);
    }

    private static Map<String,List<String>> currentContents(Connection connection) throws Exception
    {
        // The new nickname default is separate from the existing settings whose values must survive.
        try(var sql = connection.createStatement(); var row = sql.executeQuery(
            "SELECT settings_json,updated_at_ms FROM application_settings WHERE key='receiver.nicknames'"))
        {
            assertTrue(row.next());
            assertEquals("{}", row.getString(1));
            assertTrue(row.getLong(2) > 0);
            assertFalse(row.next());
        }
        return contents(connection, false, true, true);
    }

    private static Map<String,List<String>> contents(Connection connection, boolean applyRetirement,
                                                    boolean splitProjection, boolean excludeNicknameDefault) throws Exception
    {
        Map<String,List<String>> result = new LinkedHashMap<>();
        List<String> tables = CurrentFormatTestDatabase.retainedMonolithTables(connection);
        for(String table: tables)
        {
            if(CurrentFormatTestDatabase.addedCurrentTable(connection, table)) continue;
            List<String> values = new ArrayList<>();
            try(var sql = connection.createStatement(); var rows = sql.executeQuery(
                "SELECT * FROM " + CurrentFormatTestDatabase.retainedMonolithQuery(connection, table, splitProjection)))
            {
                while(rows.next())
                {
                    String key = rows.getString(1);
                    if(table.equals("database_metadata") && DatabaseFormatCatalog.FORMAT_VERSION_KEY.equals(key)) continue;
                    if(applyRetirement && table.equals("application_settings") && VOICE_KEY.equals(key)) continue;
                    if(excludeNicknameDefault && table.equals("application_settings") && "receiver.nicknames".equals(key)) continue;
                    List<String> row = new ArrayList<>();
                    for(int column = 1; column <= rows.getMetaData().getColumnCount(); column++)
                    {
                        if("source_call_uuid".equals(rows.getMetaData().getColumnName(column)) ||
                            table.equals("receiver_activity_event") && "duration_ms".equals(rows.getMetaData().getColumnName(column)))
                        { assertNull(rows.getObject(column)); continue; }
                        if(CurrentFormatTestDatabase.addedMessageCounter(rows, column)) continue;
                        Object value = CurrentFormatTestDatabase.retainedValue(table, rows, column);
                        if(table.equals("application_settings") && PORTABLE_KEY.equals(key) && column == 2)
                        {
                            ObjectNode portable = (ObjectNode)JSON.readTree((String)value);
                            if(applyRetirement && portable.get(AUDIO_NODE) instanceof ObjectNode audio)
                            {
                                if(audio.remove(AGC_KEY) != null && audio.isEmpty()) portable.remove(AUDIO_NODE);
                            }
                            value = portable.toString();
                        }
                        row.add(value instanceof byte[] bytes ? java.util.HexFormat.of().formatHex(bytes) : String.valueOf(value));
                    }
                    values.add(row.toString());
                }
            }
            values.sort(String::compareTo);
            result.put(table, values);
        }
        return result;
    }

    private static void save(Connection connection, String key, String json, long timestamp) throws SQLException
    {
        try(var save = connection.prepareStatement("""
            INSERT INTO application_settings(key,settings_json,updated_at_ms) VALUES(?,?,?)
            ON CONFLICT(key) DO UPDATE SET settings_json=excluded.settings_json,updated_at_ms=excluded.updated_at_ms
            """))
        {
            save.setString(1, key);
            save.setString(2, json);
            save.setLong(3, timestamp);
            save.executeUpdate();
        }
    }

    private static void integrity(Connection connection) throws SQLException
    {
        CurrentFormatTestDatabase.retainedPairIntegrity(connection);
    }

    private static Connection open(Path database) throws SQLException
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }
}
