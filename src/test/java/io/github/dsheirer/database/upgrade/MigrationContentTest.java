/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.database.settings.ReceiverSettingsSchema;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationContentTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long SUBSCRIBER_ALIAS_ID = 9_100_001;
    private static final List<String> PRESERVED_TABLES = List.of(
        "configuration_channel", "configuration_broadcast_stream", "alias", "alias_list",
        "alias_p25_subscriber_identity", "p25_subscriber_identity", "scan_list", "alias_scan_list_membership",
        "alias_list_unmatched_talkgroup_scan_list_membership", "alias_list_new_alias_scan_list_membership",
        "alias_broadcast_channel", "alias_list_unmatched_talkgroup_stream", "alias_list_new_alias_stream",
        "application_settings", "application_icons", "web_user", "web_access_policy", "activity_message_type",
        "topic_alert_topic", "topic_alert_rule", "topic_alert_device", "topic_alert_membership",
        "topic_alert_enrollment", "topic_alert_enrollment_topic");
    private static final List<String> METRIC_KEYS = List.of("conventional_call_output_metrics_started_at_ms",
        "trunked_logical_call_metrics_started_at_ms", "radio_system_metrics_started_at_ms");

    @TempDir Path directory;

    @Test void contentChoicesArePureAndRetainTheReviewedSourceBinding() throws Exception
    {
        Path source = Format61TestDatabase.create(directory.resolve("reviewed.sqlite"));
        var full = ApplicationMigrationService.readMigrationApproval(source);
        try(var connection = open(source); var statement = connection.createStatement())
        {
            assertTrue(statement.executeUpdate("UPDATE alias SET name='Changed after review' WHERE id=" +
                "(SELECT min(id) FROM alias)") > 0);
        }
        var configuration = full.withContent(MigrationContent.CONFIGURATION_ONLY);
        var toggled = configuration.withContent(MigrationContent.FULL);

        assertEquals(MigrationContent.FULL, full.content());
        assertEquals(MigrationContent.FULL, full.plan().content());
        assertEquals(MigrationContent.CONFIGURATION_ONLY, configuration.content());
        assertEquals(MigrationContent.CONFIGURATION_ONLY, configuration.plan().content());
        assertEquals(full.plan().source(), configuration.plan().source());
        assertEquals(full.plan().target(), configuration.plan().target());
        assertEquals(full.plan(), toggled.plan());
        assertEquals(full.plan(), full.plan().withContent(MigrationContent.CONFIGURATION_ONLY)
            .withContent(MigrationContent.FULL));

        byte[] changedSource = Files.readAllBytes(source);
        Path target = directory.resolve("changed-source-target");
        assertThrows(IOException.class, () -> service().importPrevious(fileSelection(source), target,
            configuration, null));
        assertFalse(Files.exists(target));
        assertArrayEquals(changedSource, Files.readAllBytes(source));
    }

    @Test void changingContentKeepsTheReviewedLegacyFilesBoundToFolderApproval() throws Exception
    {
        Path sourceRoot = directory.resolve("legacy-source");
        Path source = Format52TestDatabase.create(database(sourceRoot));
        Path paging = sourceRoot.resolve("audio-analysis/paging-tones.json");
        Files.createDirectories(paging.getParent());
        Files.writeString(paging, "{\"version\":1,\"enabled\":false}");
        var selection = new PreviousBuildLocator.Selection(sourceRoot, PreviousBuildLocator.InputScope.PORTABLE_PROFILE);
        var approval = ApplicationMigrationService.readMigrationApproval(selection, directory)
            .withContent(MigrationContent.CONFIGURATION_ONLY);
        Files.writeString(paging, "{\"version\":1,\"enabled\":true}");
        byte[] sourceBytes = Files.readAllBytes(source);
        Path target = directory.resolve("changed-legacy-target");

        assertThrows(IOException.class, () -> service().importPrevious(selection, target, approval, null));

        assertFalse(Files.exists(target));
        assertArrayEquals(sourceBytes, Files.readAllBytes(source));
        assertEquals("{\"version\":1,\"enabled\":true}", Files.readString(paging));
        Files.writeString(paging, "{\"version\":1,\"enabled\":false}");
        service().importPrevious(selection, target, approval, null);
        assertEquals(Map.of("enabled", false), new ApplicationSettingsStore(database(target))
            .load(ReceiverSettingsSchema.PAGING_TONES_KEY, Map.class).orElseThrow());
        assertArrayEquals(sourceBytes, Files.readAllBytes(source));
        assertEquals("{\"version\":1,\"enabled\":false}", Files.readString(paging));
    }

    @Test void configurationOnlyImportsHealthyFormat60And61HistoryWithoutTouchingItsSourceOrRecordings() throws Exception
    {
        for(int version: List.of(60, 61))
        {
            Path sourceRoot = directory.resolve("source-" + version);
            Path source = priorDatabase(version, database(sourceRoot));
            populateAdditionalOwnershipCases(source);
            Path audioRoot = Files.createDirectories(sourceRoot.resolve("recordings-managed"));
            Path audio = audioRoot.resolve("kept.wav");
            Files.write(audio, new byte[]{1, 2, 3, 4});
            Path catalog = source.resolveSibling("managed-recordings.sqlite");
            try(var ignored = new ManagedRecordingCatalog(catalog, audioRoot)) { }
            try(var connection = open(catalog); var statement = connection.createStatement())
            {
                statement.executeUpdate("INSERT INTO recording_call(id,start_ms,end_ms,duration_ms,relative_path," +
                    "size_bytes,protocol,call_type,voice_type) VALUES(1,1000,2000,1000,'kept.wav',4,1,1,1)");
                statement.executeUpdate("INSERT INTO recording_source(call_id,source_call_uuid) " +
                    "VALUES(1,'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')");
            }
            byte[] sourceBytes = Files.readAllBytes(source);
            byte[] catalogBytes = Files.readAllBytes(catalog);
            byte[] audioBytes = Files.readAllBytes(audio);
            Map<String,List<String>> preserved;
            Map<Long,Preference> preferences;
            Map<String,List<String>> tours;
            try(var connection = open(source))
            {
                assertHealthyPopulatedHistory(connection);
                preserved = preservedRows(connection);
                preferences = preferences(connection);
                tours = version == 61 ? rows(connection, List.of("web_user_tour_dismissal"), false) : Map.of();
            }
            var approval = ApplicationMigrationService.readMigrationApproval(source)
                .withContent(MigrationContent.CONFIGURATION_ONLY);
            Path targetRoot = directory.resolve("target-" + version);

            var result = service().importPrevious(fileSelection(source), targetRoot, approval, null);

            assertEquals(MigrationContent.CONFIGURATION_ONLY, result.sourcePlan().content());
            try(var connection = open(database(targetRoot)))
            {
                assertEquals(preserved, preservedRows(connection));
                assertConvertedPreferences(preferences, preferences(connection));
                if(version == 61) assertEquals(tours, rows(connection, List.of("web_user_tour_dismissal"), false));
                else assertEquals(0, count(connection, "web_user_tour_dismissal"));
                assertEmptyCurrentHistory(connection);
            }
            assertArrayEquals(sourceBytes, Files.readAllBytes(source));
            assertArrayEquals(catalogBytes, Files.readAllBytes(catalog));
            assertArrayEquals(audioBytes, Files.readAllBytes(audio));
            assertFalse(Files.exists(database(targetRoot).resolveSibling("managed-recordings.sqlite")),
                "A SQLite-file import leaves the separate recording catalog at its existing location");
        }
    }

    @Test void theDefaultFullUpdatePreservesHealthyHistoryAndAnalogCounters() throws Exception
    {
        Path root = directory.resolve("full");
        Path source = priorDatabase(61, database(root));
        populateAdditionalOwnershipCases(source);
        Map<String,List<String>> history;
        try(var connection = open(source))
        {
            assertHealthyPopulatedHistory(connection);
            List<String> tables = new ArrayList<>(CurrentDatabaseDerivedStateRepair.REPRODUCIBLE_TABLES);
            tables.addAll(List.of("alias_list_analog_activity_summary", "topic_alert_message", "topic_alert_delivery"));
            history = rows(connection, tables, false);
        }

        var result = service().migrateCurrent(root, false, null);

        assertEquals(MigrationContent.FULL, result.sourcePlan().content());
        try(var connection = open(source))
        {
            assertEquals(history, rows(connection, new ArrayList<>(history.keySet()), false));
            DatabaseFormatCatalog.requireCurrent(connection);
        }
    }

    @Test void configurationOnlyAlsoUpdatesThePopulatedFormat46SourceThroughTheExistingChain() throws Exception
    {
        Path source = Format46TestDatabase.create(directory.resolve("published-format-46.sqlite"));
        List<String> configurationTables = List.of("configuration_channel", "configuration_broadcast_stream",
            "alias", "alias_list", "scan_list", "alias_scan_list_membership", "alias_broadcast_channel",
            "p25_subscriber_identity", "alias_p25_subscriber_identity", "web_user", "web_access_policy");
        Map<String,List<String>> configuration;
        Map<Long,Preference> preferences;
        try(var connection = open(source))
        {
            assertTrue(count(connection, "receiver_activity_event") > 0);
            configuration = rows(connection, configurationTables, true);
            preferences = preferences(connection);
        }
        byte[] original = Files.readAllBytes(source);
        var approval = ApplicationMigrationService.readMigrationApproval(source)
            .withContent(MigrationContent.CONFIGURATION_ONLY);
        Path target = directory.resolve("published-format-46-target");

        service().importPrevious(fileSelection(source), target, approval, null);

        try(var connection = open(database(target)))
        {
            DatabaseFormatCatalog.requireCurrent(connection);
            assertEquals(currentRoleFingerprint(connection), SqliteSchemaValidator.fingerprint(connection));
            assertEquals(configuration, rows(connection, configurationTables, true));
            assertConvertedPreferences(preferences, preferences(connection));
            for(String table: List.of("receiver_activity_event", "radio_system", "receiver_activity_tone",
                "alias_list_analog_activity_summary", "topic_alert_message", "topic_alert_delivery"))
                assertEquals(0, count(connection, table), table);
            assertEquals(count(connection, "alias"), count(connection, "alias_activity_summary"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM alias_activity_summary " +
                "WHERE logical_call_count IS NOT NULL OR first_evidence_ms IS NOT NULL OR last_evidence_ms IS NOT NULL"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
        }
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test void unusableCanonicalSubscriberRelationshipsRefuseWithoutDiscardingConfigurationOrHistory() throws Exception
    {
        for(String damage: List.of("missing-link", "invalid-tuple", "wrong-family", "wrong-matcher"))
        {
            Path root = directory.resolve("invalid-canonical-" + damage);
            Path source = priorDatabase(61, database(root));
            populateAdditionalOwnershipCases(source);
            try(var connection = open(source); var statement = connection.createStatement())
            {
                statement.execute("PRAGMA ignore_check_constraints=ON");
                switch(damage)
                {
                    case "missing-link" -> statement.executeUpdate("DELETE FROM alias_p25_subscriber_identity " +
                        "WHERE alias_id=" + SUBSCRIBER_ALIAS_ID);
                    case "invalid-tuple" -> statement.executeUpdate("UPDATE p25_subscriber_identity " +
                        "SET subscriber_id=16777215 WHERE id=" + SUBSCRIBER_ALIAS_ID);
                    case "wrong-family" -> statement.executeUpdate("UPDATE alias SET alias_list_id=9100002 " +
                        "WHERE id=" + SUBSCRIBER_ALIAS_ID);
                    case "wrong-matcher" -> statement.executeUpdate("UPDATE alias SET matcher_type='RADIO_ID'," +
                        "protocol='APCO25',value=42 WHERE id=" + SUBSCRIBER_ALIAS_ID);
                    default -> throw new AssertionError(damage);
                }
                statement.execute("PRAGMA ignore_check_constraints=OFF");
            }
            byte[] before = Files.readAllBytes(source);
            Map<String,List<String>> retained;
            try(var connection = open(source))
            {
                retained = rows(connection, List.of("alias", "alias_p25_subscriber_identity", "p25_subscriber_identity",
                    "alias_activity_summary", "receiver_activity_event", "alias_list_analog_activity_summary"), false);
            }

            var failure = assertThrows(SQLException.class, () -> service().migrateCurrent(root, false,
                MigrationContent.CONFIGURATION_ONLY, null), damage);

            assertTrue(failure.getMessage().contains("subscriber Alias"), damage);
            assertArrayEquals(before, Files.readAllBytes(source), damage);
            try(var connection = open(source))
            {
                assertEquals(61, DatabaseFormatCatalog.inspectForMigration(connection).version());
                assertEquals(retained, rows(connection, new ArrayList<>(retained.keySet()), false), damage);
            }
        }
    }

    @Test void skippingOnlyAnUnsafeCanonicalAliasAlsoRemovesItsOwnedLinkAndKeepsTheValidSubscriberAlias() throws Exception
    {
        Path root = directory.resolve("unsafe-canonical-alias");
        Path source = priorDatabase(61, database(root));
        populateAdditionalOwnershipCases(source);
        long unsafeAlias = 9_007_199_254_740_992L;
        Map<String,List<String>> preserved;
        long aliases;
        try(var connection = open(source); var statement = connection.createStatement())
        {
            preserved = preservedRows(connection);
            aliases = count(connection, "alias");
            statement.execute("PRAGMA foreign_keys=ON");
            assertEquals(1, statement.executeUpdate("INSERT INTO alias(id,alias_list_id,name,matcher_type) " +
                "SELECT " + unsafeAlias + ",alias_list_id,'Unsafe identity','P25_SUBSCRIBER_IDENTITY' " +
                "FROM alias WHERE id=" + SUBSCRIBER_ALIAS_ID));
            statement.executeUpdate("INSERT INTO alias_p25_subscriber_identity(alias_id,p25_subscriber_identity_id) " +
                "VALUES(" + unsafeAlias + "," + SUBSCRIBER_ALIAS_ID + ")");
            statement.executeUpdate("INSERT INTO alias_activity_summary(alias_id,alias_list_id,protocol_code," +
                "metrics_state,logical_call_count,first_evidence_ms,last_evidence_ms,updated_at_ms) SELECT " +
                "id,alias_list_id,1,'observed',3,1000,2000,2000 FROM alias WHERE id=" + unsafeAlias);
            assertEquals(aliases + 1, count(connection, "alias"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
        }

        service().migrateCurrent(root, false, MigrationContent.CONFIGURATION_ONLY, null);

        try(var connection = open(source))
        {
            assertEquals(preserved, preservedRows(connection));
            assertEquals(aliases, count(connection, "alias"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM alias WHERE id=" + unsafeAlias));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM alias_p25_subscriber_identity WHERE alias_id=" + unsafeAlias));
            assertEmptyCurrentHistory(connection);
        }
    }

    @Test void anUnusableOrMissingAliasListSkipsOnlyItsCanonicalAliasAndOwnedLink() throws Exception
    {
        for(boolean missingOwner: List.of(false, true))
        {
            Path root = directory.resolve("unusable-canonical-owner-" + missingOwner);
            Path source = priorDatabase(61, database(root));
            populateAdditionalOwnershipCases(source);
            long owner = 9_100_003, alias = 9_100_004;
            Map<String,List<String>> preserved;
            try(var connection = open(source); var statement = connection.createStatement())
            {
                preserved = preservedRows(connection);
                statement.executeUpdate("INSERT INTO alias_list(id,name,family) " +
                    "VALUES(" + owner + ",'Unusable custom owner','P25')");
                statement.executeUpdate("INSERT INTO alias(id,alias_list_id,name,matcher_type) " +
                    "VALUES(" + alias + "," + owner + ",'Alias owned by unusable list','P25_SUBSCRIBER_IDENTITY')");
                statement.executeUpdate("INSERT INTO alias_p25_subscriber_identity(alias_id,p25_subscriber_identity_id) " +
                    "VALUES(" + alias + "," + SUBSCRIBER_ALIAS_ID + ")");
                statement.executeUpdate("INSERT INTO alias_activity_summary(alias_id,alias_list_id,protocol_code," +
                    "metrics_state,logical_call_count,first_evidence_ms,last_evidence_ms,updated_at_ms) " +
                    "VALUES(" + alias + "," + owner + ",1,'observed',3,1000,2000,2000)");
                if(missingOwner)
                {
                    statement.execute("PRAGMA foreign_keys=OFF");
                    statement.executeUpdate("DELETE FROM alias_list WHERE id=" + owner);
                }
                else
                {
                    statement.execute("PRAGMA ignore_check_constraints=ON");
                    statement.executeUpdate("UPDATE alias_list SET family='UNKNOWN' WHERE id=" + owner);
                    statement.execute("PRAGMA ignore_check_constraints=OFF");
                }
            }

            service().migrateCurrent(root, false, MigrationContent.CONFIGURATION_ONLY, null);

            try(var connection = open(source))
            {
                assertEquals(preserved, preservedRows(connection));
                assertEquals(0, scalar(connection, "SELECT count(*) FROM alias_list WHERE id=" + owner));
                assertEquals(0, scalar(connection, "SELECT count(*) FROM alias WHERE id=" + alias));
                assertEquals(0, scalar(connection, "SELECT count(*) FROM alias_p25_subscriber_identity WHERE alias_id=" + alias));
                assertEmptyCurrentHistory(connection);
            }
        }
    }

    @Test void configurationOnlyIsRefusedForAnAlreadyCurrentDatabase() throws Exception
    {
        Path root = directory.resolve("already-current");
        Path source = Format63TestDatabase.create(database(root));
        byte[] before = Files.readAllBytes(source);

        var failure = assertThrows(IllegalArgumentException.class, () -> service().migrateCurrent(root, false,
            MigrationContent.CONFIGURATION_ONLY, null));
        assertTrue(failure.getMessage().contains("older database"));

        assertArrayEquals(before, Files.readAllBytes(source));
        assertFalse(Files.exists(source.getParent().resolve("backups")));
    }

    @Test void aConfigurationOnlyFailureBeforeCommitRollsBackTheDiscardAndCanBeRetried() throws Exception
    {
        Path root = directory.resolve("rollback");
        Path source = priorDatabase(61, database(root));
        populateAdditionalOwnershipCases(source);
        Map<String,List<String>> before;
        try(var connection = open(source))
        {
            List<String> tables = new ArrayList<>(PRESERVED_TABLES);
            tables.addAll(List.of("web_user_tour_dismissal", "database_metadata", "sqlite_sequence",
                "receiver_activity_event", "activity_event_identity_member", "receiver_activity_tone_sequence",
                "receiver_activity_tone", "alias_list_analog_activity_summary", "alias_activity_summary",
                "topic_alert_message", "topic_alert_delivery"));
            before = rows(connection, tables, false);
        }

        assertThrows(IllegalStateException.class, () -> service().migrateCurrent(root, false,
            MigrationContent.CONFIGURATION_ONLY, phase -> {
                if(phase.equals("Committing the database update"))
                    throw new IllegalStateException("Synthetic failure before commit");
            }));

        try(var connection = open(source))
        {
            assertEquals(61, DatabaseFormatCatalog.inspectForMigration(connection).version());
            assertEquals(before, rows(connection, new ArrayList<>(before.keySet()), false));
        }
        service().migrateCurrent(root, false, MigrationContent.CONFIGURATION_ONLY, null);
        try(var connection = open(source)) { assertEmptyCurrentHistory(connection); }
    }

    private static void populateAdditionalOwnershipCases(Path source) throws Exception
    {
        try(var connection = open(source); var statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("INSERT INTO p25_subscriber_identity(id,home_wacn,home_system_id,subscriber_id) " +
                "VALUES(" + SUBSCRIBER_ALIAS_ID + ",0xFFF01,0xFFE,9100001)");
            assertEquals(1, statement.executeUpdate("INSERT INTO alias(id,alias_list_id,name,matcher_type) " +
                "SELECT " + SUBSCRIBER_ALIAS_ID + ",id,'Configured subscriber','P25_SUBSCRIBER_IDENTITY' " +
                "FROM alias_list WHERE family='P25' ORDER BY id LIMIT 1"));
            statement.executeUpdate("INSERT INTO alias_p25_subscriber_identity(alias_id,p25_subscriber_identity_id) " +
                "VALUES(" + SUBSCRIBER_ALIAS_ID + "," + SUBSCRIBER_ALIAS_ID + ")");
            statement.executeUpdate("INSERT INTO alias_activity_summary(alias_id,alias_list_id,protocol_code," +
                "metrics_state,logical_call_count,first_evidence_ms,last_evidence_ms,updated_at_ms) SELECT " +
                "id,alias_list_id,1,'observed',7,1000,2000,2000 FROM alias WHERE id=" + SUBSCRIBER_ALIAS_ID);
            statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES(9100002,'Analog statistics','NBFM')");
            assertEquals(1, statement.executeUpdate("INSERT INTO alias_list_analog_activity_summary(alias_list_id," +
                "channel_configuration_id,protocol_code,assigned_talkgroup,call_count,unmatched_call_count," +
                "recorded_count,streamed_count,first_seen_ms,last_seen_ms,updated_at_ms) " +
                "SELECT 9100002,configuration_id,10,120,7,2,3,4,1000,2000,2000 " +
                "FROM configuration_channel ORDER BY id LIMIT 1"));
            for(String key: METRIC_KEYS)
                statement.executeUpdate("UPDATE database_metadata SET value='1',updated_at_ms=1 WHERE key='" + key + "'");
        }
    }

    private static void assertHealthyPopulatedHistory(Connection connection) throws Exception
    {
        assertTrue(count(connection, "receiver_activity_event") > 0);
        assertTrue(count(connection, "receiver_activity_tone") > 0);
        assertTrue(count(connection, "alias_list_analog_activity_summary") > 0);
        assertTrue(count(connection, "topic_alert_message") > 0);
        assertTrue(count(connection, "topic_alert_delivery") > 0);
        assertTrue(count(connection, "alias_p25_subscriber_identity") > 0);
        assertFalse(CurrentDatabaseDerivedStateRepair.inspectHistorical(connection, 61).requiresRepair(),
            "The explicit choice must discard healthy history, independently of damaged-history recovery");
    }

    private static void assertEmptyCurrentHistory(Connection connection) throws Exception
    {
        assertEquals(currentRoleFingerprint(connection), SqliteSchemaValidator.fingerprint(connection));
        DatabaseFormatCatalog.requireCurrent(connection);
        for(String table: CurrentDatabaseDerivedStateRepair.REPRODUCIBLE_TABLES)
            if(!table.equals("alias_activity_summary")) assertEquals(0, count(connection, table), table);
        assertEquals(0, count(connection, "alias_list_analog_activity_summary"));
        assertEquals(0, count(connection, "topic_alert_delivery"));
        assertEquals(0, count(connection, "topic_alert_message"));
        assertEquals(count(connection, "alias"), count(connection, "alias_activity_summary"));
        assertEquals(0, scalar(connection, "SELECT count(*) FROM alias_activity_summary WHERE " +
            "logical_call_count IS NOT NULL OR signaling_observation_count IS NOT NULL " +
            "OR first_evidence_ms IS NOT NULL OR last_evidence_ms IS NOT NULL"));
        assertEquals(1, scalar(connection, "SELECT count(*) FROM alias_p25_subscriber_identity " +
            "WHERE alias_id=" + SUBSCRIBER_ALIAS_ID + " AND p25_subscriber_identity_id=" + SUBSCRIBER_ALIAS_ID));
        for(String key: METRIC_KEYS)
            assertTrue(scalar(connection, "SELECT CAST(value AS INTEGER) FROM database_metadata WHERE key='" + key + "'") > 1);
        assertEquals(0, scalar(connection, "SELECT count(*) FROM sqlite_sequence WHERE name IN " +
            "('radio_system','radio_system_identity_summary','receiver_channel','receiver_activity_event'," +
            "'receiver_health_incident','topic_alert_delivery')"));
        assertEquals(0, scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
        try(var statement = connection.createStatement(); var row = statement.executeQuery("PRAGMA integrity_check"))
        { assertTrue(row.next()); assertEquals("ok", row.getString(1)); }
    }

    private static Map<String,List<String>> preservedRows(Connection connection) throws Exception
    { return rows(connection, PRESERVED_TABLES, true); }

    private static Map<String,List<String>> rows(Connection connection, List<String> tables, boolean convertedFields)
        throws Exception
    {
        Map<String,List<String>> result = new LinkedHashMap<>();
        for(String table: tables)
        {
            List<String> values = new ArrayList<>();
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM \"" + table + "\""))
            {
                while(rows.next())
                {
                    if(convertedFields && table.equals("application_settings") && rows.getString("key").equals("setup_wizard")) continue;
                    List<String> fields = new ArrayList<>();
                    for(int index = 1; index <= rows.getMetaData().getColumnCount(); index++)
                    {
                        String column = rows.getMetaData().getColumnName(index);
                        if(addedFormat63Column(table,column)) continue;
                        if(convertedFields && (table.equals("web_user") &&
                            List.of("preferences_json", "preferences_revision").contains(column) ||
                            table.equals("configuration_channel") && column.equals("analog_activity_reset_ms"))) continue;
                        Object value = rows.getObject(index);
                        fields.add(value instanceof byte[] bytes ? java.util.HexFormat.of().formatHex(bytes) : String.valueOf(value));
                    }
                    values.add(java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(fields.toString().getBytes(StandardCharsets.UTF_8))));
                }
            }
            values.sort(String::compareTo);
            result.put(table, List.copyOf(values));
        }
        return result;
    }

    private static Map<Long,Preference> preferences(Connection connection) throws Exception
    {
        Map<Long,Preference> result = new LinkedHashMap<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(
            "SELECT id,preferences_json,preferences_revision FROM web_user ORDER BY id"))
        { while(rows.next()) result.put(rows.getLong(1), new Preference(rows.getString(2), rows.getLong(3))); }
        return result;
    }

    private static void assertConvertedPreferences(Map<Long,Preference> before, Map<Long,Preference> after) throws Exception
    {
        assertEquals(before.keySet(), after.keySet());
        for(var entry: before.entrySet())
        {
            assertEquals(JSON.readTree(Format62WebUserPreferencesCodec.migrateFromFormat61(entry.getValue().json()).json()),
                JSON.readTree(after.get(entry.getKey()).json()));
            assertEquals(entry.getValue().revision() + 1, after.get(entry.getKey()).revision());
        }
    }

    private static Path priorDatabase(int version, Path target) throws Exception
    { return version == 60 ? Format60TestDatabase.create(target) : Format61TestDatabase.create(target); }
    private static PreviousBuildLocator.Selection fileSelection(Path source)
    { return new PreviousBuildLocator.Selection(source, PreviousBuildLocator.InputScope.DATABASE_FILE); }
    private static ApplicationMigrationService service() { return ApplicationMigrationServiceTestSupport.createInProcess(); }
    private static Path database(Path root) { return SdrTrunkDatabasePath.getDatabasePath(root); }
    private static Connection open(Path source) throws Exception
    {
        try(var inspect=DriverManager.getConnection("jdbc:sqlite:" + source);var query=inspect.createStatement();
            var rows=query.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='database_metadata'"))
        {
            if(rows.next() && io.github.dsheirer.database.ConfigurationHistoryDatabase.isPairedConfiguration(inspect))
                return io.github.dsheirer.database.SdrTrunkDatabase.openHistory(source);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + source);
    }
    private static String currentRoleFingerprint(Connection connection) throws Exception
    {
        return "activity".equals(io.github.dsheirer.database.ConfigurationHistorySchema.metadata(connection,
            io.github.dsheirer.database.ConfigurationHistorySchema.ROLE_KEY)) ?
            io.github.dsheirer.database.ConfigurationHistoryDatabase.HISTORY_FINGERPRINT : DatabaseFormatCatalog.current().fingerprint();
    }
    private static boolean addedFormat63Column(String table,String column)
    {
        return switch(table)
        {
            case "alias" -> List.of("activity_identity","activity_reset_ms").contains(column);
            case "receiver_channel" -> List.of("name","system_name","site_name","decoder_type","channel_kind",
                "address_domain_code","alias_list_id","alias_list_name","primary_frequency_hz","assigned_talkgroup").contains(column);
            case "alias_activity_summary" -> List.of("activity_identity","alias_name","alias_list_name").contains(column);
            case "alias_list_analog_activity_summary" -> column.equals("alias_list_name");
            default -> false;
        };
    }
    private static long count(Connection connection, String table) throws Exception
    { return scalar(connection, "SELECT count(*) FROM \"" + table + "\""); }
    private static long scalar(Connection connection, String sql) throws Exception
    { try(var statement = connection.createStatement(); var row = statement.executeQuery(sql)) { assertTrue(row.next()); return row.getLong(1); } }
    private record Preference(String json, long revision) {}
}
