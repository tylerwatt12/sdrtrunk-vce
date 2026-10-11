/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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
import org.sqlite.ProgressHandler;

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
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION - 44, report.steps().size());
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
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.requireCurrent(connection).version());
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
    void theNewViewDistinguishesFormat45AndDiscoverySemanticsRequireTheirMarker() throws Exception
    {
        Path database = Format45TestDatabase.create(mTemporaryFolder.resolve("format45.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            assertNotEquals(DatabaseFormatCatalog.requireVersion(44).fingerprint(),
                DatabaseFormatCatalog.requireVersion(45).fingerprint());
            assertEquals(45, DatabaseFormatCatalog.inspect(connection).version());
            statement.executeUpdate("DELETE FROM database_metadata WHERE key='database_format_version'");
            assertThrows(SQLException.class, () -> DatabaseFormatCatalog.inspect(connection));
            assertThrows(SQLException.class, () -> DatabaseMigrationChain.migrate(connection));
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

    @Test
    void consolidationSeeksRadioChildrenWithoutRevisitingUnrelatedSystemsForEachMergedOwner() throws Exception
    {
        int small = consolidationWork("small-radio-children44.sqlite", 8, Integer.MAX_VALUE);
        // Allow three times the fixed migration work and 500 VM instructions per added directory row. This leaves
        // room for the one-time directory passes, but not ten unrelated-child scans for each of 64 merging owners.
        int budget = small * 3 + (4_096 - 8) / 2;
        int large = consolidationWork("large-radio-children44.sqlite", 4_096, budget);
        assertTrue(large <= budget, "Consolidation revisited unrelated radio children: " + large + " > " + budget);
    }

    @Test
    void sourceEvidenceScansSequentiallyAndOffSystemSourceAndTargetProofStillRetainLegacyOwners() throws Exception
    {
        Path database = Format44TestDatabase.createWithHomeRadioSplits(mTemporaryFolder.resolve("activity-evidence44.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            // Historical derived references can disagree with their event's system. The migration must still use
            // the event system when deciding which local lifetime is ambiguous, without repairing that old evidence.
            statement.execute("PRAGMA foreign_keys=OFF");
            statement.execute("PRAGMA automatic_index=OFF");
            connection.setAutoCommit(false);
            statement.executeUpdate("""
                WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value+1 FROM n WHERE value<32)
                INSERT INTO p25_subscriber_identity(id,home_wacn,home_system_id,subscriber_id)
                SELECT 8000000000+value,0xBEE01,0x3A9,900000+value FROM n
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,home_wacn,
                    home_system_id,identity_id,first_seen_ms,last_seen_ms,p25_subscriber_identity_id)
                SELECT id,5000000001,2,home_wacn,home_system_id,subscriber_id,1000,9000,id
                FROM p25_subscriber_identity WHERE id>8000000000
                """);
            statement.executeUpdate("""
                WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value+1 FROM n WHERE value<32768)
                INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,action_code,
                    source_identity_summary_id,source_observed_local_id,target_identity_summary_id,
                    target_kind_code,target_observed_local_id)
                SELECT 9000000000+value,5000000002,5000000001,10000+value,1,
                    8000000001+(value-1)%32,900001+(value-1)%32,
                    8000000001+(value-1)%32,2,900001+(value-1)%32 FROM n
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,action_code,
                    source_identity_summary_id,source_observed_working_id)
                VALUES(8100000001,5000000000,5000000000,9000,1,8000000001,10900077)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,action_code,
                    target_identity_summary_id,target_kind_code,target_observed_local_id,target_observed_working_id)
                VALUES(8100000002,5000000000,5000000000,9000,1,8000000002,2,900002,10900078)
                """);
            connection.commit();
            Map<String,TableContents> before = contents(connection);
            TableContents owners = tableContents(connection, "radio_system_identity_summary", " WHERE id>8000000000");
            assertEquals("2", scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
            List<String> evidenceQueries = new ArrayList<>();
            Connection observed = captureActivityEvidenceQueries(connection, evidenceQueries);
            AtomicInteger callbacks = new AtomicInteger();
            connection.setAutoCommit(false);
            // Source and target each need at most one linear visit to retained evidence. A generous 10-million
            // instruction limit catches repeated history work without measuring disk speed or wall-clock time.
            ProgressHandler.setHandler(connection, 1_000, new ProgressHandler()
            {
                @Override protected int progress() { return callbacks.incrementAndGet() > 10_000 ? 1 : 0; }
            });
            List<DatabaseMigrationEffect> effects;
            try
            {
                effects = assertDoesNotThrow(() -> new Format44To45DatabaseMigration().migrateAndReport(observed),
                    () -> "Activity evidence exceeded its linear VM-work budget: " + callbacks.get());
            }
            finally
            {
                ProgressHandler.clearHandler(connection);
            }
            DatabaseFormatCatalog.stamp(connection, 45);
            connection.commit();
            assertEquals(List.of(2L, 0L, 3L, 1L), effects.stream().map(DatabaseMigrationEffect::affectedRows).toList());
            assertEquals("-1|-1", scalar(connection, "SELECT home_wacn||'|'||home_system_id FROM " +
                "radio_system_identity_summary WHERE id=5000000010"));
            assertEquals("-1|-1", scalar(connection, "SELECT home_wacn||'|'||home_system_id FROM " +
                "radio_system_identity_summary WHERE id=5000000012"));
            assertEquals("3|1", scalar(connection, "SELECT local.logical_call_count||'|'||qualified.logical_call_count " +
                "FROM radio_system_identity_summary local CROSS JOIN radio_system_identity_summary qualified " +
                "WHERE local.id=5000000010 AND qualified.id=5000000011"));
            Map<String,TableContents> after = contents(connection);
            before.forEach((table, rows) -> {
                if(!table.equals("radio_system_identity_summary")) assertEquals(rows, after.get(table), table);
            });
            assertEquals(owners, tableContents(connection, "radio_system_identity_summary", " WHERE id>8000000000"));
            assertEquals(2, evidenceQueries.size(), "Capture the actual source and target statements executed by migration");
            String source = evidenceQueries.stream().filter(sql -> sql.contains("evidence.source_identity_summary_id"))
                .findFirst().orElseThrow();
            List<String> plan = new ArrayList<>();
            try(ResultSet rows = statement.executeQuery("EXPLAIN QUERY PLAN " + source))
            {
                while(rows.next()) plan.add(rows.getString("detail"));
            }
            assertTrue(plan.getFirst().startsWith("SCAN evidence") && !plan.getFirst().contains("INDEX"), plan.toString());
            assertTrue(plan.stream().anyMatch(detail -> detail.contains("SEARCH owner USING INTEGER PRIMARY KEY")),
                "Each sequential evidence row must probe its exact owner: " + plan);
            assertEquals("ok", scalar(connection, "PRAGMA integrity_check"));
            assertEquals("2", scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"),
                "Keep the two explicitly seeded historical scope mismatches unchanged");
            assertEquals(45, DatabaseFormatCatalog.inspect(connection).version());
        }
    }

    private static Connection captureActivityEvidenceQueries(Connection connection, List<String> queries)
    {
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, arguments) -> {
                try
                {
                    Object result = method.invoke(connection, arguments);
                    if(method.getName().equals("createStatement") && result instanceof Statement statement)
                    {
                        return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                            (statementProxy, statementMethod, statementArguments) -> {
                                if(statementMethod.getName().equals("executeQuery") && statementArguments != null &&
                                    statementArguments[0] instanceof String sql &&
                                    sql.startsWith("SELECT DISTINCT evidence.radio_system_id") &&
                                    sql.contains("receiver_activity_event")) queries.add(sql);
                                try { return statementMethod.invoke(statement, statementArguments); }
                                catch(InvocationTargetException error) { throw error.getCause(); }
                            });
                    }
                    return result;
                }
                catch(InvocationTargetException error) { throw error.getCause(); }
            });
    }

    @Test
    void mergingPreservesOffSystemHistoricalRadioChildrenAndIdentityBuckets() throws Exception
    {
        Path database = Format44TestDatabase.createWithHomeRadioSplits(mTemporaryFolder.resolve("off-system-children44.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=OFF");
            connection.setAutoCommit(false);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,home_wacn,
                    home_system_id,identity_id,first_seen_ms,last_seen_ms)
                VALUES(8200000000,5000000001,1,0xBEE01,0x3A9,22000,1000,9000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_group_summary(radio_system_id,radio_identity_id,group_identity_id,
                    group_kind_code,first_seen_ms,last_seen_ms,logical_call_count,register_count)
                VALUES(5000000001,5000000010,8200000000,1,1000,9000,6,3)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_affiliation(radio_system_id,radio_identity_id,talkgroup_identity_id,
                    channel_id,radio_observed_local_id,talkgroup_observed_local_id,confirmed_at_ms)
                VALUES(5000000001,5000000010,8200000000,5000000002,10900077,22000,9000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_channel_presence(radio_system_id,radio_identity_id,channel_id,
                    observed_local_id,evidence_code,confirmed_at_ms)
                VALUES(5000000001,5000000010,5000000002,10900077,1,9000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_channel_presence_clear(radio_system_id,radio_identity_id,channel_id,
                    observed_local_id,cleared_at_ms)
                VALUES(5000000001,5000000010,5000000002,10900077,8000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_logical_call_bucket(radio_system_id,bucket_start_ms,logical_call_count)
                VALUES(5000000001,0,6)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_logical_call_identity_bucket(radio_system_id,bucket_start_ms,identity_role_code,
                    identity_kind_code,identity_summary_id,logical_call_count)
                VALUES(5000000001,0,2,2,5000000010,6)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_learned_site(learned_site_id,radio_system_id,rfss,site,first_seen_ms,last_seen_ms)
                VALUES(8200000000,5000000001,2,3,1000,9000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_call_bucket(radio_system_id,learned_site_id,bucket_start_ms,observed_call_count)
                VALUES(5000000001,8200000000,0,6)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_call_identity_bucket(radio_system_id,learned_site_id,channel_id,bucket_start_ms,
                    identity_role_code,identity_kind_code,identity_summary_id,observed_local_id,last_observed_at_ms,
                    observed_call_count)
                VALUES(5000000001,8200000000,5000000002,0,2,2,5000000010,10900077,9000,6)
                """);
            connection.commit();
            Map<String,TableContents> before = offSystemOwnerContents(connection, 5000000010L);
            assertTrue(before.values().stream().allMatch(rows -> rows.rows() == 1));
            TableContents totals = tableContents(connection, "trunked_logical_call_bucket", " WHERE radio_system_id=5000000001");
            TableContents siteTotals = tableContents(connection, "p25_site_call_bucket", " WHERE radio_system_id=5000000001");
            connection.setAutoCommit(false);
            List<DatabaseMigrationEffect> effects = new Format44To45DatabaseMigration().migrateAndReport(connection);
            DatabaseFormatCatalog.stamp(connection, 45);
            connection.commit();
            assertCounts(effects);
            assertEquals(before, offSystemOwnerContents(connection, 5000000011L),
                "Only the historical owner changes; every off-system child and bucket field survives");
            assertTrue(offSystemOwnerContents(connection, 5000000010L).values().stream().allMatch(rows -> rows.rows() == 0));
            assertEquals(totals, tableContents(connection, "trunked_logical_call_bucket", " WHERE radio_system_id=5000000001"));
            assertEquals(siteTotals, tableContents(connection, "p25_site_call_bucket", " WHERE radio_system_id=5000000001"));
            assertEquals("0", scalar(connection, "SELECT count(*) FROM radio_system_identity_summary WHERE id=5000000010"));
            assertEquals("4", scalar(connection, "SELECT logical_call_count FROM radio_system_identity_summary WHERE id=5000000011"));
            assertEquals("ok", scalar(connection, "PRAGMA integrity_check"));
            assertEquals("6", scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"),
                "The six explicitly seeded historical child scope mismatches remain, with the surviving owner");
            assertEquals(45, DatabaseFormatCatalog.inspect(connection).version());
        }
    }

    private static Map<String,TableContents> offSystemOwnerContents(Connection connection, long owner) throws Exception
    {
        Map<String,TableContents> contents = new LinkedHashMap<>();
        for(String table: List.of("trunked_radio_group_summary", "trunked_radio_affiliation",
            "trunked_radio_channel_presence", "trunked_radio_channel_presence_clear",
            "trunked_logical_call_identity_bucket", "p25_site_call_identity_bucket"))
        {
            String column = table.endsWith("identity_bucket") ? "identity_summary_id" : "radio_identity_id";
            // Normalize only the expected remapped owner for comparison; hash every other original field unchanged.
            contents.put(table, tableContents(connection, table,
                " WHERE radio_system_id=5000000001 AND " + column + "=" + owner, column));
        }
        return contents;
    }

    private int consolidationWork(String filename, int unrelatedRadios, int maximumCallbacks) throws Exception
    {
        Path database = Format44TestDatabase.createWithHomeRadioSplits(mTemporaryFolder.resolve(filename));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            // Default planner estimates must remain sufficient; ANALYZE or automatic indexes must not conceal a
            // missing system prefix in the migration's explicit radio-child lookups.
            statement.execute("PRAGMA automatic_index=OFF");
            connection.setAutoCommit(false);
            statement.executeUpdate("""
                WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value+1 FROM n WHERE value<64),
                    qualified(value) AS (VALUES(0),(1))
                INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,home_wacn,
                    home_system_id,identity_id,first_seen_ms,last_seen_ms,logical_call_count)
                SELECT 6000000000+n.value*2+qualified.value,5000000000,2,
                    CASE qualified.value WHEN 0 THEN -1 ELSE 0xBEE00 END,
                    CASE qualified.value WHEN 0 THEN -1 ELSE 0x3A9 END,
                    200000+n.value,1000,9000,1+qualified.value FROM n CROSS JOIN qualified
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_group_summary(radio_system_id,radio_identity_id,group_identity_id,
                    group_kind_code,first_seen_ms,last_seen_ms,logical_call_count)
                SELECT radio_system_id,id,5000000020,1,1000,9000,logical_call_count
                FROM radio_system_identity_summary WHERE id BETWEEN 6000000002 AND 6000000129
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_affiliation(radio_system_id,radio_identity_id,talkgroup_identity_id,
                    channel_id,radio_observed_local_id,talkgroup_observed_local_id,confirmed_at_ms)
                SELECT radio_system_id,id,5000000020,5000000000,identity_id,10003,9000
                FROM radio_system_identity_summary WHERE id BETWEEN 6000000002 AND 6000000129
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_channel_presence(radio_system_id,radio_identity_id,channel_id,
                    observed_local_id,evidence_code,confirmed_at_ms)
                SELECT radio_system_id,id,5000000000,identity_id,1,9000
                FROM radio_system_identity_summary WHERE id BETWEEN 6000000002 AND 6000000129
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_channel_presence_clear(radio_system_id,radio_identity_id,channel_id,
                    observed_local_id,cleared_at_ms)
                SELECT radio_system_id,id,5000000000,identity_id,10000
                FROM radio_system_identity_summary WHERE id BETWEEN 6000000002 AND 6000000129
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,home_wacn,
                    home_system_id,identity_id,first_seen_ms,last_seen_ms)
                VALUES(7000000000,5000000001,1,0xBEE01,0x3A9,12000,1000,9000)
                """);
            statement.executeUpdate("""
                WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value+1 FROM n WHERE value<%d)
                INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,home_wacn,
                    home_system_id,identity_id,first_seen_ms,last_seen_ms,logical_call_count)
                SELECT 7000000000+value,5000000001,2,0xBEE01,0x3A9,300000+value,1000,9000,7 FROM n
                """.formatted(unrelatedRadios));
            statement.executeUpdate("""
                INSERT INTO trunked_radio_group_summary(radio_system_id,radio_identity_id,group_identity_id,
                    group_kind_code,first_seen_ms,last_seen_ms,logical_call_count)
                SELECT radio_system_id,id,7000000000,1,1000,9000,7
                FROM radio_system_identity_summary WHERE id>7000000000
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_affiliation(radio_system_id,radio_identity_id,talkgroup_identity_id,
                    channel_id,radio_observed_local_id,talkgroup_observed_local_id,confirmed_at_ms)
                SELECT radio_system_id,id,7000000000,5000000002,identity_id,12000,9000
                FROM radio_system_identity_summary WHERE id>7000000000
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_channel_presence(radio_system_id,radio_identity_id,channel_id,
                    observed_local_id,evidence_code,confirmed_at_ms)
                SELECT radio_system_id,id,5000000002,identity_id,1,9000
                FROM radio_system_identity_summary WHERE id>7000000000
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_channel_presence_clear(radio_system_id,radio_identity_id,channel_id,
                    observed_local_id,cleared_at_ms)
                SELECT radio_system_id,id,5000000002,identity_id,10000
                FROM radio_system_identity_summary WHERE id>7000000000
                """);
            connection.commit();
            Map<String,TableContents> unrelated = unrelatedRadioContents(connection);
            assertEquals(unrelatedRadios + 1, unrelated.get("radio_system_identity_summary").rows());
            AtomicInteger callbacks = new AtomicInteger();
            connection.setAutoCommit(false);
            ProgressHandler.setHandler(connection, 1_000, new ProgressHandler()
            {
                @Override protected int progress()
                {
                    return callbacks.incrementAndGet() > maximumCallbacks ? 1 : 0;
                }
            });
            List<DatabaseMigrationEffect> effects;
            try
            {
                effects = assertDoesNotThrow(() -> new Format44To45DatabaseMigration().migrateAndReport(connection),
                    () -> "Consolidation exceeded its indexed VM-work budget: " + callbacks.get());
            }
            finally
            {
                ProgressHandler.clearHandler(connection);
            }
            DatabaseFormatCatalog.stamp(connection, 45);
            connection.commit();
            assertEquals(List.of(3L, 65L, 1L, 1L), effects.stream().map(DatabaseMigrationEffect::affectedRows).toList());
            assertEquals(unrelated, unrelatedRadioContents(connection), "Every unrelated summary and child field survives");
            assertConsolidated(connection);
            assertEquals("64|192", scalar(connection, "SELECT count(*)||'|'||sum(logical_call_count) FROM " +
                "radio_system_identity_summary WHERE id BETWEEN 6000000002 AND 6000000129"));
            assertEquals("64|192", scalar(connection, "SELECT count(*)||'|'||sum(logical_call_count) FROM " +
                "trunked_radio_group_summary WHERE radio_identity_id BETWEEN 6000000002 AND 6000000129"));
            for(String table: List.of("trunked_radio_affiliation", "trunked_radio_channel_presence"))
            {
                assertEquals("0", scalar(connection, "SELECT count(*) FROM " + table +
                    " WHERE radio_identity_id BETWEEN 6000000002 AND 6000000129"), table);
            }
            assertEquals("64", scalar(connection, "SELECT count(*) FROM trunked_radio_channel_presence_clear " +
                "WHERE radio_identity_id BETWEEN 6000000002 AND 6000000129 AND cleared_at_ms=10000"));
            assertEquals(45, DatabaseFormatCatalog.inspect(connection).version());
            assertHealthy(connection);
            return callbacks.get();
        }
    }

    private static Map<String,TableContents> unrelatedRadioContents(Connection connection) throws Exception
    {
        Map<String,TableContents> contents = new LinkedHashMap<>();
        for(String table: List.of("radio_system_identity_summary", "trunked_radio_group_summary",
            "trunked_radio_affiliation", "trunked_radio_channel_presence", "trunked_radio_channel_presence_clear"))
        {
            String filter = table.equals("radio_system_identity_summary") ? " WHERE id>=7000000000" :
                " WHERE radio_system_id=5000000001";
            contents.put(table, tableContents(connection, table, filter));
        }
        return contents;
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
            String filter = table.equals("database_metadata") ? " WHERE key<>'database_format_version'" : "";
            contents.put(table, tableContents(connection, table, filter));
        }
        return contents;
    }

    private static TableContents tableContents(Connection connection, String table, String filter) throws Exception
    {
        return tableContents(connection, table, filter, null);
    }

    private static TableContents tableContents(Connection connection, String table, String filter,
                                              String normalizedOwnerColumn) throws Exception
    {
        int columns;
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT * FROM \"" + table + "\" LIMIT 0"))
        {
            columns = rows.getMetaData().getColumnCount();
        }
        String order = java.util.stream.IntStream.rangeClosed(1, columns).mapToObj(Integer::toString)
            .collect(java.util.stream.Collectors.joining(","));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0;
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT * FROM \"" + table + "\"" + filter + " ORDER BY " + order))
        {
            while(rows.next())
            {
                count++;
                for(int column = 1; column <= columns; column++)
                {
                    byte[] value = rows.getMetaData().getColumnName(column).equals(normalizedOwnerColumn) ?
                        new byte[]{0} : rows.getBytes(column);
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
        return new TableContents(count, HexFormat.of().formatHex(digest.digest()));
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
