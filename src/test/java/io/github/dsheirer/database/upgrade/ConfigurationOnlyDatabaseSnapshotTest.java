/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.ConfigurationHistorySchema;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationOnlyDatabaseSnapshotTest
{
    private static final Set<String> CONFIGURATION_TABLES = configurationTables();
    @TempDir Path directory;

    @Test void extractsExactConfigurationFromFormats2Through14() throws Exception { checkFormats(2, 14); }
    @Test void extractsExactConfigurationFromFormats15Through30() throws Exception { checkFormats(15, 30); }
    @Test void extractsExactConfigurationFromFormats31Through46() throws Exception { checkFormats(31, 46); }
    @Test void extractsExactConfigurationFromFormats47Through59() throws Exception { checkFormats(47, 59); }
    @Test void extractsExactConfigurationFromFormats60ThroughCurrent() throws Exception
    {
        checkFormats(60, DatabaseFormatCatalog.CURRENT_VERSION);
    }

    private void checkFormats(int first, int last) throws Exception
    {
        for(int version = first; version <= last; version++)
        {
            Path source = fixture(version, directory.resolve("source-" + version + "/sdrtrunk.sqlite"));
            Path target = directory.resolve("target-" + version + "/sdrtrunk.sqlite");
            byte[] original = Files.readAllBytes(source);
            byte[] originalHistory = version >= 63 ?
                Files.readAllBytes(ActivityDatabasePath.forConfiguration(source)) : null;
            Map<String,List<String>> before;
            String fingerprint;
            try(Connection connection = open(source))
            {
                before = configurationRows(connection);
                fingerprint = SqliteSchemaValidator.fingerprint(connection);
            }

            assertEquals(version, ConfigurationOnlyDatabaseSnapshot.create(source, target).version());

            try(Connection connection = open(target))
            {
                assertEquals(fingerprint, SqliteSchemaValidator.fingerprint(connection), "format " + version);
                assertEquals(version, DatabaseFormatCatalog.inspectForMigration(connection).version());
                assertEquals(before, configurationRows(connection), "configuration for format " + version);
                assertEmptyHistory(connection);
            }
            if(version >= 63)
            {
                ConfigurationHistoryDatabase.validatePairIdentity(target);
                try(Connection activity = open(ActivityDatabasePath.forConfiguration(target)))
                {
                    assertEmptyHistory(activity);
                    ConfigurationHistoryDatabase.validateRole(activity, ConfigurationHistorySchema.HISTORY_ROLE);
                }
                assertArrayEquals(originalHistory, Files.readAllBytes(ActivityDatabasePath.forConfiguration(source)));
            }
            assertArrayEquals(original, Files.readAllBytes(source), "source for format " + version);
        }
    }

    @Test void extractedHistoricalLayoutsStillUseTheOrdinaryMigrationChain() throws Exception
    {
        for(int version: List.of(2, 14, 31, 46, 62, 63, DatabaseFormatCatalog.CURRENT_VERSION))
        {
            Path source = fixture(version, directory.resolve("chain-source-" + version + "/sdrtrunk.sqlite"));
            Path target = directory.resolve("chain-target-" + version + "/sdrtrunk.sqlite");
            ConfigurationOnlyDatabaseSnapshot.create(source, target);
            ApplicationDatabaseMigrator.migrateInPlace(target, ignored -> { });
            try(Connection configuration = open(target))
            {
                DatabaseFormatCatalog.requireCurrent(configuration);
                assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(configuration));
            }
            try(Connection history = open(ActivityDatabasePath.forConfiguration(target)))
            {
                assertEquals(0, count(history, "receiver_activity_event"));
                assertEquals(0, count(history, "radio_system"));
            }
        }
    }

    @Test void readsCommittedConfigurationFromWalWithoutChangingDatabaseOrWal() throws Exception
    {
        Path source = fixture(62, directory.resolve("wal-source.sqlite"));
        Path target = directory.resolve("wal-target.sqlite");
        try(Connection writer = open(source); var statement = writer.createStatement())
        {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.executeUpdate("UPDATE alias SET name='Configuration committed in WAL' WHERE id=(SELECT min(id) FROM alias)");
            Path wal = Path.of(source + "-wal");
            assertTrue(Files.size(wal) > 0);
            byte[] mainBytes = Files.readAllBytes(source);
            byte[] walBytes = Files.readAllBytes(wal);

            ConfigurationOnlyDatabaseSnapshot.create(source, target);

            try(Connection copied = open(target); var query = copied.createStatement();
                var rows = query.executeQuery("SELECT name FROM alias WHERE id=(SELECT min(id) FROM alias)"))
            {
                assertTrue(rows.next());
                assertEquals("Configuration committed in WAL", rows.getString(1));
            }
            assertArrayEquals(mainBytes, Files.readAllBytes(source));
            assertArrayEquals(walBytes, Files.readAllBytes(wal));
        }
    }

    @Test void refusesRollbackRecoveryInsteadOfWritingTheSource() throws Exception
    {
        Path source = fixture(62, directory.resolve("journal-source.sqlite"));
        Path target = directory.resolve("journal-target.sqlite");
        try(Connection writer = open(source); var statement = writer.createStatement())
        {
            statement.execute("PRAGMA journal_mode=DELETE");
            writer.setAutoCommit(false);
            statement.executeUpdate("UPDATE alias SET name='Uncommitted' WHERE id=(SELECT min(id) FROM alias)");
            Path journal = Path.of(source + "-journal");
            assertTrue(Files.size(journal) > 0);
            byte[] before = Files.readAllBytes(source), beforeJournal = Files.readAllBytes(journal);

            IOException exception = assertThrows(IOException.class,
                () -> ConfigurationOnlyDatabaseSnapshot.create(source, target));

            assertTrue(exception.getMessage().contains("rollback journal"));
            assertArrayEquals(before, Files.readAllBytes(source));
            assertArrayEquals(beforeJournal, Files.readAllBytes(journal));
            assertFalse(Files.exists(target));
            writer.rollback();
        }
    }

    @Test void neverReadsDiscardedHistoryPages() throws Exception
    {
        Path source = fixture(62, directory.resolve("damaged-history.sqlite"));
        damageHistoryPage(source);
        byte[] damaged = Files.readAllBytes(source);
        Path target = directory.resolve("configuration-without-damaged-history.sqlite");

        ConfigurationOnlyDatabaseSnapshot.create(source, target);

        try(Connection connection = open(target))
        {
            assertEquals(0, count(connection, "receiver_activity_event"));
            assertTrue(count(connection, "configuration_channel") > 0);
        }
        assertArrayEquals(damaged, Files.readAllBytes(source));
    }

    @Test void inspectsOnlyTheSeparatedHistorySchemaAndIdentity() throws Exception
    {
        Path source = fixture(64, directory.resolve("pair-source/sdrtrunk.sqlite"));
        Path history = ActivityDatabasePath.forConfiguration(source);
        damageHistoryPage(history);
        byte[] sourceBytes = Files.readAllBytes(source), historyBytes = Files.readAllBytes(history);
        Path target = directory.resolve("pair-target/sdrtrunk.sqlite");

        ConfigurationOnlyDatabaseSnapshot.create(source, target);

        ConfigurationHistoryDatabase.validatePairIdentity(target);
        try(Connection connection = open(ActivityDatabasePath.forConfiguration(target)))
        {
            assertEquals(0, count(connection, "receiver_activity_event"));
        }
        assertArrayEquals(sourceBytes, Files.readAllBytes(source));
        assertArrayEquals(historyBytes, Files.readAllBytes(history));
    }

    private static void damageHistoryPage(Path source) throws Exception
    {
        damageHistoryPage(source, "receiver_activity_event");
    }

    private static void damageHistoryPage(Path source, String table) throws Exception
    {
        long page;
        int pageSize;
        try(Connection connection = open(source); var statement = connection.createStatement())
        {
            try(var rows = statement.executeQuery("PRAGMA page_size")) { rows.next(); pageSize = rows.getInt(1); }
            try(var rows = statement.executeQuery("SELECT pageno FROM dbstat WHERE name='" + table + "' " +
                "AND pagetype='leaf' LIMIT 1"))
            {
                assertTrue(rows.next()); page = rows.getLong(1);
            }
        }
        //Damage only a discarded history b-tree. Configuration and sqlite_schema remain readable.
        try(RandomAccessFile bytes = new RandomAccessFile(source.toFile(), "rw"))
        {
            bytes.seek((page - 1) * pageSize); bytes.write(0);
        }
        try(Connection connection = open(source))
        {
            assertThrows(SQLException.class, () -> count(connection, table, " NOT INDEXED"));
        }
    }

    @Test void extractedCurrentPairRunsConfigurationOnlyRelocationAndSeedsAliasCounters() throws Exception
    {
        Path sourceRoot = Files.createDirectories(directory.resolve("relocation-source"));
        Path targetRoot = Files.createDirectories(directory.resolve("relocation-target"));
        Path source = fixture(DatabaseFormatCatalog.CURRENT_VERSION, sourceRoot.resolve("database/sdrtrunk.sqlite"));
        Path target = targetRoot.resolve("database/.sdrtrunk.sqlite.migration-" + java.util.UUID.randomUUID());
        ConfigurationOnlyDatabaseSnapshot.create(source, target);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try(var output = new java.io.PrintStream(bytes))
        {
            int result = ApplicationDatabaseMigrator.run(new String[]{target.toString(), sourceRoot.toString(),
                targetRoot.toString(), "--configuration-only"}, output, output);
            assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result, bytes::toString);
        }
        long aliases;
        try(Connection configuration = open(target))
        {
            DatabaseFormatCatalog.requireCurrent(configuration);
            aliases = count(configuration, "alias");
        }
        try(Connection activity = open(ActivityDatabasePath.forConfiguration(target)))
        {
            assertEquals(aliases, count(activity, "alias_activity_summary"));
            assertEquals(0, count(activity, "receiver_activity_event"));
            assertEquals(0, count(activity, "radio_system"));
        }
    }

    @Test void keepsOnlyAliasOwnedSubscriberIdentitiesAndPreservesConfigurationSequences() throws Exception
    {
        Path source = fixture(62, directory.resolve("identities.sqlite"));
        Path target = directory.resolve("identities-target.sqlite");
        try(Connection connection = open(source); var statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO p25_subscriber_identity(id,home_wacn,home_system_id,subscriber_id) " +
                "VALUES(9000000,1,1,1)");
            statement.executeUpdate("UPDATE sqlite_sequence SET seq=9000001 WHERE name='alias'");
        }

        ConfigurationOnlyDatabaseSnapshot.create(source, target);

        try(Connection connection = open(target); var statement = connection.createStatement())
        {
            assertEquals(0, count(connection, "p25_subscriber_identity", " WHERE id=9000000"));
            assertEquals(0, count(connection, "alias_p25_subscriber_identity", " WHERE p25_subscriber_identity_id " +
                "NOT IN (SELECT id FROM p25_subscriber_identity)"));
            try(var rows = statement.executeQuery("SELECT seq FROM sqlite_sequence WHERE name='alias'"))
            {
                assertTrue(rows.next()); assertEquals(9000001, rows.getLong(1));
            }
            assertEquals(0, count(connection, "sqlite_sequence", " WHERE name='receiver_activity_event'"));
        }
    }

    @Test void preservesTheVerifiedP25BindingRecoveredByTheSourceLoaderBeforeDiscardingHistory() throws Exception
    {
        P25SiteIdentity expected = new P25SiteIdentity(0xABCDE, 0x456, 7, 8);
        for(int version: List.of(62, DatabaseFormatCatalog.CURRENT_VERSION))
        {
            PendingBinding pending = pendingBindingSource("pending-binding-" + version, version);
            Path source = pending.source(), history = pending.history();
            String configurationId = pending.configurationId();
            assertEquals(expected, loadBinding(source, configurationId, version), "source runtime recovery");
            byte[] original = Files.readAllBytes(source), originalHistory = Files.readAllBytes(history);
            Path target = directory.resolve("pending-binding-target-" + version + "/sdrtrunk.sqlite");

            ConfigurationOnlyDatabaseSnapshot.create(source, target);

            assertEquals(expected, loadBinding(target, configurationId, version), "settings-only format " + version);
            assertArrayEquals(original, Files.readAllBytes(source));
            assertArrayEquals(originalHistory, Files.readAllBytes(history));
            try(Connection connection = open(version >= 63 ? ActivityDatabasePath.forConfiguration(target) : target))
            {
                assertEquals(0, count(connection, "receiver_channel"));
                assertEquals(0, count(connection, "p25_site_snapshot"));
            }
        }
    }

    @Test void bindingRecoveryUsesOnlyIndexedOwnerAndSiteLookups() throws Exception
    {
        for(int version: List.of(62, DatabaseFormatCatalog.CURRENT_VERSION))
        {
            PendingBinding pending = pendingBindingSource("indexed-binding-" + version, version);
            List<String> plan = new ArrayList<>();
            try(Connection connection = open(pending.history()); var query = connection.prepareStatement(
                "EXPLAIN QUERY PLAN " + ConfigurationOnlyDatabaseSnapshot.P25_BINDING_LOOKUP_SQL))
            {
                query.setString(1, pending.configurationId());
                try(var rows = query.executeQuery()) { while(rows.next()) plan.add(rows.getString(4)); }
            }
            assertEquals(3, plan.size(), plan::toString);
            assertTrue(plan.stream().allMatch(detail -> detail.startsWith("SEARCH ")), plan::toString);
        }
    }

    @Test void keepsExplicitBindingsWithoutNeedingDiscardedOwnerPages() throws Exception
    {
        PendingBinding pending = pendingBindingSource("explicit-binding", DatabaseFormatCatalog.CURRENT_VERSION);
        String saved = "{\"wacn\":703711,\"system\":1111,\"rfss\":9,\"site\":10}";
        try(Connection connection = open(pending.source()); var update = connection.prepareStatement("""
            UPDATE configuration_channel SET config_json=json_set(config_json,'$.p25SiteIdentity',json(?))
            WHERE channel_kind='TRUNKED' AND decoder_type IN ('P25_PHASE1','P25_PHASE2')
            """))
        {
            update.setString(1, saved); assertTrue(update.executeUpdate() > 0);
        }
        damageHistoryPage(pending.history(), "receiver_channel");
        Path target = directory.resolve("explicit-binding-target/sdrtrunk.sqlite");
        byte[] original = Files.readAllBytes(pending.source()), damaged = Files.readAllBytes(pending.history());

        ConfigurationOnlyDatabaseSnapshot.create(pending.source(), target);

        assertEquals(new P25SiteIdentity(703711,1111,9,10),
            loadBinding(target, pending.configurationId(), DatabaseFormatCatalog.CURRENT_VERSION));
        assertArrayEquals(original, Files.readAllBytes(pending.source()));
        assertArrayEquals(damaged, Files.readAllBytes(pending.history()));
    }

    @Test void damagedPendingBindingOwnersDoNotPreventRecoveringReadableSettings() throws Exception
    {
        for(int version: List.of(62, DatabaseFormatCatalog.CURRENT_VERSION))
        {
            PendingBinding pending = pendingBindingSource("damaged-pending-binding-" + version, version);
            damageHistoryPage(pending.history(), "receiver_channel");
            byte[] original = Files.readAllBytes(pending.source()), damaged = Files.readAllBytes(pending.history());
            Path target = directory.resolve("damaged-pending-binding-target-" + version + "/sdrtrunk.sqlite");

            ConfigurationOnlyDatabaseSnapshot.create(pending.source(), target);

            assertNull(loadBinding(target, pending.configurationId(), version));
            assertArrayEquals(original, Files.readAllBytes(pending.source()));
            assertArrayEquals(damaged, Files.readAllBytes(pending.history()));
            try(Connection connection = open(target)) { assertTrue(count(connection, "configuration_channel") > 0); }
        }
    }

    @Test void neverInfersABindingFromInconsistentOrMalformedDiscardedOwners() throws Exception
    {
        for(String damage: List.of("mismatched-key", "channel-owned", "oversized-wacn", "fractional-site"))
        {
            PendingBinding pending = pendingBindingSource(damage, DatabaseFormatCatalog.CURRENT_VERSION);
            try(Connection connection = open(pending.history()); var update = connection.createStatement())
            {
                update.execute("PRAGMA ignore_check_constraints=ON");
                switch(damage)
                {
                    case "mismatched-key" -> update.executeUpdate("UPDATE radio_system SET system_key='p25:abcde:457' WHERE system_key='p25:abcde:456'");
                    case "channel-owned" -> {
                        try(var owned = connection.prepareStatement("UPDATE radio_system SET configuration_id=? WHERE system_key='p25:abcde:456'"))
                        { owned.setString(1, pending.configurationId()); assertEquals(1, owned.executeUpdate()); }
                    }
                    case "oversized-wacn" -> update.executeUpdate("UPDATE radio_system SET p25_wacn=4295671006 WHERE system_key='p25:abcde:456'");
                    case "fractional-site" -> update.executeUpdate("UPDATE p25_site_snapshot SET rfss=7.5 WHERE rfss=7 AND site=8");
                    default -> throw new AssertionError(damage);
                }
            }
            assertNull(loadBinding(pending.source(), pending.configurationId(), DatabaseFormatCatalog.CURRENT_VERSION), damage);
            Path target = directory.resolve(damage + "-target/sdrtrunk.sqlite");

            ConfigurationOnlyDatabaseSnapshot.create(pending.source(), target);

            assertNull(loadBinding(target, pending.configurationId(), DatabaseFormatCatalog.CURRENT_VERSION), damage);
        }
    }

    @Test void neverInfersABindingWhenTheSavedDecoderPayloadDisagreesWithItsP25Projection() throws Exception
    {
        for(int version: List.of(62, DatabaseFormatCatalog.CURRENT_VERSION))
        {
            for(String damage: List.of("decoder-payload", "overflowing-address-domain"))
            {
                PendingBinding pending = pendingBindingSource(damage + "-" + version, version);
                try(Connection connection = open(pending.source()); var statement = connection.createStatement())
                {
                    statement.execute("PRAGMA ignore_check_constraints=ON");
                    String sql = damage.equals("decoder-payload") ? """
                        UPDATE configuration_channel SET config_json=json_set(config_json,
                            '$.decodeConfiguration.type','decodeConfigDMR','$.decodeConfiguration.channelMode','TRUNKED')
                        WHERE configuration_id=?
                        """ : "UPDATE configuration_channel SET address_domain_code=4294967296 WHERE configuration_id=?";
                    try(var update = connection.prepareStatement(sql))
                    {
                        update.setString(1, pending.configurationId()); assertEquals(1, update.executeUpdate());
                    }
                }
                Path target = directory.resolve(damage + "-target-" + version + "/sdrtrunk.sqlite");
                Map<String,List<String>> expected;
                try(Connection connection = open(pending.source()); var query = connection.prepareStatement(
                    "SELECT config_json FROM configuration_channel WHERE configuration_id=?"))
                {
                    expected = configurationRows(connection);
                    query.setString(1, pending.configurationId());
                    try(var rows = query.executeQuery())
                    {
                        assertTrue(rows.next());
                        var decoded = new com.fasterxml.jackson.databind.ObjectMapper()
                            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .readValue(rows.getString(1), io.github.dsheirer.controller.channel.Channel.class);
                        if(damage.equals("decoder-payload"))
                            assertEquals(io.github.dsheirer.module.decode.DecoderType.DMR,
                                decoded.getDecodeConfiguration().getDecoderType());
                        else assertTrue(decoded.getDecodeConfiguration().getDecoderType() ==
                            io.github.dsheirer.module.decode.DecoderType.P25_PHASE1 ||
                            decoded.getDecodeConfiguration().getDecoderType() ==
                            io.github.dsheirer.module.decode.DecoderType.P25_PHASE2);
                        assertEquals(io.github.dsheirer.configuration.ChannelConfigurationPolicy.ChannelKind.TRUNKED,
                            io.github.dsheirer.configuration.ChannelConfigurationPolicy.requireChannelKind(decoded));
                    }
                }

                ConfigurationOnlyDatabaseSnapshot.create(pending.source(), target);

                try(Connection connection = open(target))
                {
                    assertEquals(expected, configurationRows(connection),
                        "leave ambiguous settings untouched for ordinary migration repair");
                    assertEquals(1, count(connection,"configuration_channel", damage.equals("decoder-payload") ?
                        " WHERE decoder_type IN ('P25_PHASE1','P25_PHASE2') AND json_extract(config_json,'$.decodeConfiguration.type')='decodeConfigDMR'" :
                        " WHERE address_domain_code=4294967296"));
                }
            }
        }
    }

    private PendingBinding pendingBindingSource(String name, int version) throws Exception
    {
        Path source = fixture(version, directory.resolve(name + "-source/sdrtrunk.sqlite"));
        String configurationId;
        try(Connection connection = open(source); var statement = connection.createStatement())
        {
            try(var rows = statement.executeQuery("""
                SELECT c.configuration_id FROM configuration_channel c
                WHERE c.channel_kind='TRUNKED' AND c.decoder_type IN ('P25_PHASE1','P25_PHASE2')
                ORDER BY c.id LIMIT 1
                """))
            {
                assertTrue(rows.next()); configurationId = rows.getString(1);
            }
            try(var update = connection.prepareStatement("UPDATE configuration_channel SET config_json=" +
                "json_remove(config_json,'$.p25SiteIdentity') WHERE configuration_id=?"))
            {
                update.setString(1, configurationId); assertEquals(1, update.executeUpdate());
            }
        }
        Path history = version >= 63 ? ActivityDatabasePath.forConfiguration(source) : source;
        try(Connection connection = open(history))
        {
            try(var update = connection.prepareStatement("""
                UPDATE radio_system SET system_key='p25:abcde:456', configuration_id=NULL,
                    protocol_code=1,address_domain_code=0,p25_wacn=0xABCDE,p25_system_id=0x456,
                    dmr_model_code=NULL,dmr_network_id=NULL,nxdn_location_category_code=NULL,nxdn_system_id=NULL
                WHERE id=(SELECT radio_system_id FROM receiver_channel WHERE configuration_id=?)
                """))
            {
                update.setString(1, configurationId); assertEquals(1, update.executeUpdate());
            }
            try(var update = connection.prepareStatement("""
                INSERT INTO p25_site_snapshot(channel_id,snapshot_hash,first_seen_ms,last_seen_ms,
                    observation_count,protocol,rfss,site)
                SELECT id,printf('%064x',1),1000,1000,1,'APCO25',7,8
                FROM receiver_channel WHERE configuration_id=?
                ON CONFLICT(channel_id) DO UPDATE SET protocol='APCO25',rfss=7,site=8
                """))
            {
                update.setString(1, configurationId); assertEquals(1, update.executeUpdate());
            }
        }
        return new PendingBinding(source, history, configurationId);
    }

    private record PendingBinding(Path source, Path history, String configurationId) { }

    private static P25SiteIdentity loadBinding(Path database, String configurationId, int version) throws Exception
    {
        ConfigurationDatabaseStore store = new ConfigurationDatabaseStore(database);
        java.util.List<io.github.dsheirer.controller.channel.Channel> channels;
        try(Connection connection = open(database)) { channels = store.loadChannels(connection); }
        if(version >= 63) store.restoreInterruptedP25Bindings(channels);
        return channels.stream().filter(channel -> configurationId.equals(channel.getConfigurationId()))
            .findFirst().orElseThrow().getP25SiteIdentity();
    }

    @Test void refusesUnrecognizedSourcesAndExistingDestinations() throws Exception
    {
        Path source = fixture(62, directory.resolve("refusal-source.sqlite"));
        Path target = directory.resolve("existing.sqlite");
        Files.writeString(target, "keep existing destination");
        assertThrows(IOException.class, () -> ConfigurationOnlyDatabaseSnapshot.create(source, target));
        assertEquals("keep existing destination", Files.readString(target));
        try(Connection connection = open(source); var statement = connection.createStatement())
        {
            statement.execute("CREATE TABLE unknown_layout(id INTEGER PRIMARY KEY)");
        }
        Path refused = directory.resolve("refused.sqlite");
        assertThrows(SQLException.class, () -> ConfigurationOnlyDatabaseSnapshot.create(source, refused));
        assertFalse(Files.exists(refused));
        try(var paths = Files.list(directory))
        {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith(".configuration-extraction-")));
        }
    }

    private static Set<String> configurationTables()
    {
        Set<String> tables = new LinkedHashSet<>(ConfigurationHistorySchema.CONFIGURATION_TABLES);
        tables.addAll(List.of("configuration_channel_map", "activity_message_type"));
        tables.removeAll(List.of("topic_alert_message", "topic_alert_delivery"));
        return Set.copyOf(tables);
    }

    private static Path fixture(int version, Path path) throws Exception
    {
        Files.createDirectories(path.getParent());
        Class.forName(ConfigurationOnlyDatabaseSnapshotTest.class.getPackageName() + ".Format" + version + "TestDatabase")
            .getMethod("create", Path.class).invoke(null, path);
        return path;
    }

    private static Connection open(Path path) throws SQLException { return DriverManager.getConnection("jdbc:sqlite:" + path); }

    private static Map<String,List<String>> configurationRows(Connection connection) throws SQLException
    {
        Map<String,List<String>> result = new LinkedHashMap<>();
        for(String table: tables(connection))
        {
            if(!CONFIGURATION_TABLES.contains(table)) continue;
            String where = table.equals("p25_subscriber_identity") ?
                " WHERE id IN (SELECT p25_subscriber_identity_id FROM alias_p25_subscriber_identity)" : "";
            List<String> values = new ArrayList<>();
            try(var statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT * FROM \"" + table + "\"" + where))
            {
                while(rows.next())
                {
                    List<String> columns = new ArrayList<>();
                    for(int index = 1; index <= rows.getMetaData().getColumnCount(); index++)
                    {
                        Object value = rows.getObject(index);
                        columns.add(value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : String.valueOf(value));
                    }
                    values.add(columns.toString());
                }
            }
            values.sort(String::compareTo); result.put(table, List.copyOf(values));
        }
        return result;
    }

    private static void assertEmptyHistory(Connection connection) throws SQLException
    {
        for(String table: tables(connection))
            if(!CONFIGURATION_TABLES.contains(table) && !table.equals("sqlite_sequence"))
                assertEquals(0, count(connection, table), "discarded " + table);
        for(String table: List.of("topic_alert_message", "topic_alert_delivery"))
            if(tables(connection).contains(table)) assertEquals(0, count(connection, table), table);
    }

    private static List<String> tables(Connection connection) throws SQLException
    {
        List<String> tables = new ArrayList<>();
        try(var statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
            "SELECT name FROM sqlite_schema WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"))
        {
            while(rows.next()) tables.add(rows.getString(1));
        }
        return tables;
    }

    private static long count(Connection connection, String table) throws SQLException { return count(connection, table, ""); }
    private static long count(Connection connection, String table, String where) throws SQLException
    {
        try(var statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT count(*) FROM \"" + table + "\"" + where))
        {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }
}
