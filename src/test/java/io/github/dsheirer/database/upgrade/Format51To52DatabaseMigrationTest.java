/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.stats.activity.ToneActivitySchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format51To52DatabaseMigrationTest
{
    @TempDir Path directory;

    @Test void applicationMigrationServicePreservesPopulatedFiftyOneWithEitherBackupChoice() throws Exception
    {
        for(boolean backup: List.of(false, true))
        {
            Path dataRoot = directory.resolve("service-" + backup);
            Path database = populatedPrior(SdrTrunkDatabasePath.getDatabasePath(dataRoot));
            Map<String,List<String>> before;
            List<String> indexes;
            try(Connection connection = open(database))
            {
                before = contents(connection);
                indexes = indexDefinitions(connection);
            }
            List<String> progress = new ArrayList<>();
            var result = new ApplicationMigrationService().migrateCurrent(dataRoot, backup, progress::add);
            assertEquals(51, result.sourceFormat().version());
            assertEquals("Database update committed", progress.getLast());
            try(Connection connection = open(database))
            {
                assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.requireCurrent(connection).version());
                assertEquals(before, contents(connection), "Retain every value, tone fact, statistic and allocator");
                assertEquals(indexes, indexDefinitions(connection));
                integrity(connection);
            }
            if(backup)
            {
                assertNotNull(result.safetyBackup());
                try(Connection connection = open(result.safetyBackup()))
                {
                    assertEquals(51, DatabaseFormatCatalog.inspect(connection).version());
                    assertEquals(before, contents(connection));
                    integrity(connection);
                }
            }
            else
            {
                assertNull(result.safetyBackup());
                assertFalse(Files.exists(database.getParent().resolve("backups")));
            }
        }
    }

    @Test void applicationMigrationServiceRollsBackBeforeCommitAndRetainsSourceAndBackupForRetry() throws Exception
    {
        for(boolean backup: List.of(false, true))
        {
            Path dataRoot = directory.resolve("service-rollback-" + backup);
            Path database = populatedPrior(SdrTrunkDatabasePath.getDatabasePath(dataRoot));
            Map<String,List<String>> before;
            try(Connection connection = open(database)) { before = contents(connection); }
            assertThrows(CancellationException.class, () -> new ApplicationMigrationService().migrateCurrent(
                dataRoot, backup, message ->
                {
                    if(message.equals("Committing the database update"))
                        throw new CancellationException("Injected pre-commit cancellation");
                }));
            try(Connection connection = open(database))
            {
                assertEquals(51, DatabaseFormatCatalog.inspect(connection).version());
                assertEquals(before, contents(connection));
                integrity(connection);
            }
            Path backups = database.getParent().resolve("backups");
            if(backup)
            {
                try(var paths = Files.list(backups))
                {
                    List<Path> retained = paths.toList();
                    assertEquals(1, retained.size());
                    try(Connection connection = open(retained.getFirst()))
                    {
                        assertEquals(51, DatabaseFormatCatalog.inspect(connection).version());
                        assertEquals(before, contents(connection));
                        integrity(connection);
                    }
                }
            }
            else assertFalse(Files.exists(backups));
            new ApplicationMigrationService().migrateCurrent(dataRoot, false, null);
            try(Connection connection = open(database))
            {
                assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.requireCurrent(connection).version());
                assertEquals(before, contents(connection));
                integrity(connection);
            }
        }
    }

    @Test void frozenPriorFormatsAndPopulatedUpgradePreserveEveryValueStatisticAndAllocator() throws Exception
    {
        Path fifty = Format50TestDatabase.create(directory.resolve("fifty.sqlite"));
        try(Connection connection = open(fifty))
        {
            assertEquals("647986ceed39f192239e2a7d74a8032543e1f3832a5e78701658edfcfef49cfc",
                SqliteSchemaValidator.fingerprint(connection));
            assertEquals(50, DatabaseFormatCatalog.inspect(connection).version());
        }
        Path database = Format51TestDatabase.create(directory.resolve("prior.sqlite"));
        try(Connection connection = open(database); var sql = connection.createStatement())
        {
            assertEquals("b4b1c36f41af9139c4cd62fdfb092ed7b50cf29974fffae845d6084cc295a4d7",
                SqliteSchemaValidator.fingerprint(connection));
            assertEquals(51, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(2, scalar(connection,"SELECT count(*) FROM receiver_activity_tone_sequence"));
            sql.executeUpdate("UPDATE sqlite_sequence SET seq=seq+5000 WHERE name='receiver_activity_event'");
            sql.execute("ANALYZE receiver_activity_tone_sequence");
            var before = contents(connection); var indexes = indexDefinitions(connection);
            connection.setAutoCommit(false);
            var report = DatabaseMigrationChain.migrate(connection); connection.commit();
            assertEquals(List.of("format-51-to-52", "format-52-to-53", "format-53-to-54", "format-54-to-55"),report.steps().stream().map(DatabaseMigrationChain.StepReport::id).toList());
            assertEquals(before,contents(connection)); assertEquals(indexes,indexDefinitions(connection));
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,DatabaseFormatCatalog.requireCurrent(connection).version());
            assertEquals(DatabaseFormatCatalog.current().fingerprint(),SqliteSchemaValidator.fingerprint(connection));
            assertEquals(1,scalar(connection,"SELECT count(*) FROM receiver_activity_tone_sequence s JOIN receiver_activity_tone t USING(event_id) WHERE s.source_code=1 AND s.method_code=1 AND t.frequency_1_millihz=343300"));
            assertEquals(1,scalar(connection,"SELECT count(*) FROM receiver_activity_tone_sequence s JOIN receiver_activity_tone t USING(event_id) WHERE s.source_code=2 AND s.method_code=2 AND t.codec_symbol='HZ_812_50' AND t.frequency_1_millihz=812500"));
            assertTrue(DatabaseMigrationChain.migrate(connection).steps().isEmpty()); integrity(connection);
        }
        Path fresh = directory.resolve("fresh.sqlite"); SdrTrunkDatabaseStartup.createGlobalDatabase(fresh);
        try(Connection connection = open(fresh))
        {
            assertEquals(DatabaseFormatCatalog.current().fingerprint(),SqliteSchemaValidator.fingerprint(connection));
            integrity(connection);
        }
    }

    @Test void rollbackPreservesTheUnchangedPriorFileAndRetrySucceeds() throws Exception
    {
        Path database = Format51TestDatabase.create(directory.resolve("retry.sqlite"));
        byte[] original = Files.readAllBytes(database);
        try(Connection connection = open(database))
        {
            connection.setAutoCommit(false); new Format51To52DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection,52); connection.rollback();
            assertEquals(51,DatabaseFormatCatalog.inspect(connection).version());
        }
        assertArrayEquals(original,Files.readAllBytes(database));
        try(Connection connection = open(database))
        {
            connection.setAutoCommit(false); DatabaseMigrationChain.migrate(connection); connection.commit();
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,DatabaseFormatCatalog.requireCurrent(connection).version()); integrity(connection);
        }
    }

    @Test void startupAndUnsafeForeignKeyBoundaryRefuseWithoutMutation() throws Exception
    {
        Path database = Format51TestDatabase.create(directory.resolve("refuse.sqlite"));
        byte[] original = Files.readAllBytes(database);
        assertThrows(SQLException.class,()->SdrTrunkDatabaseStartup.validateGlobalDatabase(database));
        assertArrayEquals(original,Files.readAllBytes(database));
        try(Connection connection = open(database); var sql = connection.createStatement())
        {
            assertThrows(SQLException.class,()->ToneActivitySchema.upgradeFrom51(connection));
            sql.execute("PRAGMA foreign_keys=ON"); connection.setAutoCommit(false);
            var before = contents(connection);
            assertThrows(SQLException.class,()->ToneActivitySchema.upgradeFrom51(connection));
            assertEquals(before,contents(connection)); connection.rollback();
            assertEquals(51,DatabaseFormatCatalog.inspect(connection).version());
        }
    }

    @Test void currentConstraintAcceptsOnlyDeclaredPairsAndReadsNewMethodWithoutRelabelingLegacy() throws Exception
    {
        Path database = Format52TestDatabase.create(directory.resolve("pairs.sqlite"));
        try(Connection connection = open(database); var sql = connection.createStatement())
        {
            sql.execute("PRAGMA foreign_keys=ON");
            sql.executeUpdate("INSERT INTO receiver_activity_event(channel_id,radio_system_id,observed_at_ms,action_code,event_type_code) " +
                "SELECT channel_id,radio_system_id,3000,24,62 FROM receiver_activity_event ORDER BY id LIMIT 1");
            long id=scalar(connection,"SELECT last_insert_rowid()");
            sql.executeUpdate("INSERT INTO receiver_activity_tone_sequence VALUES("+id+",1,3,1,3000,3000,4000,1)");
            sql.executeUpdate("INSERT INTO receiver_activity_tone VALUES("+id+",0,562500,NULL,NULL,100,500,900000)");
            for(int[] pair: new int[][]{{1,2},{2,1},{2,3},{1,4},{3,3}})
                assertThrows(SQLException.class,()->sql.executeUpdate("UPDATE receiver_activity_tone_sequence SET source_code="+pair[0]+",method_code="+pair[1]+" WHERE event_id="+id));
            var row=new LinkedHashMap<String,Object>(); row.put("id",id); row.put("event_type","TONE");
            List<Map<String,Object>> page=new ArrayList<>(List.of(row)); ToneActivitySchema.enrichPage(connection,page);
            Map<?,?> summary=(Map<?,?>)row.get("paging_tone_summary");
            assertEquals("p25_pcm_ambe_tone_v2",summary.get("method"));
            assertFalse(summary.containsKey("frequency_mapping"));
            var tone=(Map<?,?>)((List<?>)summary.get("tones")).getFirst();
            assertEquals(List.of(562.5),tone.get("frequencies_hz"));
            assertFalse(tone.containsKey("matching_frequency_hz"));
            assertEquals(1,scalar(connection,"SELECT count(*) FROM receiver_activity_tone_sequence s JOIN receiver_activity_tone t USING(event_id) WHERE s.source_code=1 AND s.method_code=1 AND t.frequency_1_millihz=343300"));
            integrity(connection);
        }
    }

    @Test void anOldLayoutWithNewMarkerIsRefusedBeforeAnyMutation() throws Exception
    {
        Path database=Format51TestDatabase.create(directory.resolve("mixed.sqlite"));
        try(Connection connection=open(database); var sql=connection.createStatement())
        {
            // Intentionally corrupt the marker; the validated stamp API correctly refuses this old layout.
            assertEquals(1,sql.executeUpdate("UPDATE database_metadata SET value='52' WHERE key='database_format_version'"));
            var before=contents(connection);
            assertThrows(SQLException.class,()->DatabaseFormatCatalog.requireCurrent(connection));
            assertThrows(SQLException.class,()->DatabaseMigrationChain.migrate(connection));
            assertEquals(before,contents(connection));
        }
    }

    private static Map<String,List<String>> contents(Connection connection) throws Exception
    {
        List<String> tables=new ArrayList<>(); Map<String,List<String>> result=new LinkedHashMap<>();
        try(var sql=connection.createStatement();var rows=sql.executeQuery("SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name"))
        { while(rows.next()) tables.add(rows.getString(1)); }
        for(String table:tables)
        {
            List<String> values=new ArrayList<>();
            try(var sql=connection.createStatement();var rows=sql.executeQuery("SELECT * FROM \""+table.replace("\"","\"\"")+"\""+
                (table.equals("database_metadata")?" WHERE key<>'database_format_version'":
                    table.equals("application_settings")?" WHERE key NOT IN ('remote.links','audio.paging_tones','audio.voice_enhancement')":"")))
            {
                while(rows.next())
                {
                    List<String> row=new ArrayList<>();
                    for(int column=1;column<=rows.getMetaData().getColumnCount();column++)
                    {
                        Object value=rows.getObject(column);
                        row.add(value instanceof byte[] bytes?java.util.HexFormat.of().formatHex(bytes):String.valueOf(value));
                    }
                    values.add(row.toString());
                }
            }
            Collections.sort(values); result.put(table,values);
        }
        return result;
    }
    private static Path populatedPrior(Path database) throws Exception
    {
        Files.createDirectories(database.getParent());
        Format51TestDatabase.create(database);
        try(Connection connection = open(database); var sql = connection.createStatement())
        {
            sql.executeUpdate("UPDATE sqlite_sequence SET seq=seq+5000 WHERE name='receiver_activity_event'");
            sql.execute("ANALYZE receiver_activity_tone_sequence");
        }
        return database;
    }
    private static List<String> indexDefinitions(Connection connection) throws Exception
    {
        var result=new ArrayList<String>();
        try(var sql=connection.createStatement();var rows=sql.executeQuery("SELECT name||':'||sql FROM sqlite_schema WHERE type='index' AND sql IS NOT NULL ORDER BY rowid"))
        { while(rows.next()) result.add(rows.getString(1)); }
        return result;
    }
    private static long scalar(Connection connection,String sql) throws Exception
    { try(var statement=connection.createStatement();var row=statement.executeQuery(sql)) { assertTrue(row.next());return row.getLong(1); } }
    private static void integrity(Connection connection) throws Exception
    {
        try(var sql=connection.createStatement();var row=sql.executeQuery("PRAGMA foreign_key_check")) { assertFalse(row.next()); }
        try(var sql=connection.createStatement();var row=sql.executeQuery("PRAGMA quick_check")) { assertTrue(row.next());assertEquals("ok",row.getString(1)); }
    }
    private static Connection open(Path path) throws Exception { return DriverManager.getConnection("jdbc:sqlite:"+path); }
}
