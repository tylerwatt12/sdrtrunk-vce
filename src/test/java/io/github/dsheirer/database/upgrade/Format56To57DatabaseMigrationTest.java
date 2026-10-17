/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.alerts.TopicAlertSchema;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format56To57DatabaseMigrationTest
{
    @TempDir Path directory;

    @Test void freshSchemaMatchesRegisteredSignature() throws Exception
    {
        try(var connection = DriverManager.getConnection("jdbc:sqlite::memory:"))
        {
            SdrTrunkDatabaseSchema.create(connection); ReceiverActivitySchema.create(connection);
            DmrActivitySchema.create(connection); TrunkedSiteSchema.create(connection); TopicAlertSchema.create(connection);
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
        }
    }

    @Test void populatedPriorMigrationPreservesEveryFrequencyAndRowThenRetriesWithoutRenormalization() throws Exception
    {
        Path path = Format56TestDatabase.create(directory.resolve("prior.sqlite"));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = connection.createStatement())
        {
            assertEquals(56, DatabaseFormatCatalog.inspect(connection).version());
            sql.execute("ANALYZE");
            insertAnalogTone(connection, 562500);
            Map<String,List<String>> before = contents(connection);
            connection.setAutoCommit(false);
            DatabaseMigrationChain.migrate(connection);
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            assertEquals(before, contents(connection));
            connection.rollback();
            assertEquals(56, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(before, contents(connection));
            var report = DatabaseMigrationChain.migrate(connection); connection.commit();
            assertEquals(List.of("format-56-to-57"), report.steps().stream().map(DatabaseMigrationChain.StepReport::id).toList());
            assertTrue(report.steps().getFirst().effects().stream().allMatch(effect -> effect.kind() == DatabaseMigrationEffect.Kind.PRESERVE));
            assertEquals(57, DatabaseFormatCatalog.requireCurrent(connection).version());
            assertEquals(before, contents(connection));
            try(var rows = sql.executeQuery("PRAGMA foreign_key_check")) { assertFalse(rows.next()); }
            try(var rows = sql.executeQuery("PRAGMA integrity_check")) { assertTrue(rows.next()); assertEquals("ok", rows.getString(1)); }
            assertTrue(DatabaseMigrationChain.migrate(connection).steps().isEmpty());
        }
    }

    @Test void startupRefusesThePriorSemanticMarkerWithoutChangingItsBytes() throws Exception
    {
        Path database = Format56TestDatabase.create(directory.resolve("old-startup.sqlite"));
        byte[] before = java.nio.file.Files.readAllBytes(database);
        assertThrows(java.sql.SQLException.class,
            () -> io.github.dsheirer.database.SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database));
        assertArrayEquals(before, java.nio.file.Files.readAllBytes(database));
    }

    @Test void newAnalogNominalFrequencyRoundTripsThroughTheCurrentActivityReader() throws Exception
    {
        Path database = Format56TestDatabase.create(directory.resolve("new-tone.sqlite"));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false); DatabaseMigrationChain.migrate(connection); connection.commit();
            long id = insertAnalogTone(connection, 382300);
            connection.commit();
            var row = new LinkedHashMap<String,Object>(); row.put("id", id);
            io.github.dsheirer.stats.activity.ToneActivitySchema.enrichPage(connection, List.of(row));
            var summary = (Map<?,?>)row.get("paging_tone_summary");
            assertEquals("analog_pcm_tone_v1", summary.get("method"));
            var tone = (Map<?,?>)((List<?>)summary.get("tones")).getFirst();
            assertEquals(List.of(382.3), tone.get("frequencies_hz"));
        }
    }

    private static long insertAnalogTone(Connection connection, int frequency) throws Exception
    {
        try(var sql = connection.createStatement())
        {
            sql.executeUpdate("INSERT INTO receiver_activity_event(channel_id,radio_system_id,observed_at_ms,action_code,event_type_code) " +
                "SELECT channel_id,radio_system_id,4000,4," +
                io.github.dsheirer.stats.activity.ReceiverActivityFilterCatalog.eventTypeCode("CALL_GROUP") +
                " FROM receiver_activity_event ORDER BY id LIMIT 1");
            long id;
            try(var row = sql.executeQuery("SELECT last_insert_rowid()")) { assertTrue(row.next()); id = row.getLong(1); }
            sql.executeUpdate("INSERT INTO receiver_activity_tone_sequence VALUES(" + id + ",1,4,NULL,3000,3000,4000,1)");
            sql.executeUpdate("INSERT INTO receiver_activity_tone VALUES(" + id + ",0," + frequency + ",NULL,NULL,100,200,990000)");
            return id;
        }
    }

    private static Map<String,List<String>> contents(Connection connection) throws Exception
    {
        Map<String,List<String>> result = new LinkedHashMap<>();
        List<String> tables = new ArrayList<>();
        try(var sql = connection.createStatement(); var rows = sql.executeQuery(
            "SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name"))
        { while(rows.next()) tables.add(rows.getString(1)); }
        for(String table: tables)
        {
            List<String> values = new ArrayList<>();
            try(var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT * FROM \"" + table + "\""))
            {
                while(rows.next())
                {
                    if(table.equals("database_metadata") && "database_format_version".equals(rows.getString(1))) continue;
                    List<String> row = new ArrayList<>();
                    for(int i = 1; i <= rows.getMetaData().getColumnCount(); i++)
                    {
                        Object value = rows.getObject(i);
                        row.add(value instanceof byte[] bytes ? java.util.HexFormat.of().formatHex(bytes) : String.valueOf(value));
                    }
                    values.add(row.toString());
                }
            }
            values.sort(String::compareTo); result.put(table, values);
        }
        return result;
    }
}
