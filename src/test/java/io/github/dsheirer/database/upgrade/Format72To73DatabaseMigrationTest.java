/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format72To73DatabaseMigrationTest
{
    @TempDir Path directory;

    @Test void rebuildsOnlyNineIndexesAndPreservesRowsAndEqualTimeOrderingAfterRetry() throws Exception
    {
        Path configuration = Format72TestDatabase.create(directory.resolve("prior.sqlite"));
        Path history = ActivityDatabasePath.forConfiguration(configuration);
        TreeMap<String,List<String>> schema;
        List<Long> originalOrder;
        List<String> statistics;
        try(var connection = open(history); var statement = connection.createStatement())
        {
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event(id,channel_id,observed_at_ms,action_code,event_type_code,encryption_state)
                SELECT id + 900000000000, channel_id, 1000000, 4, 8, 2
                FROM (SELECT id,channel_id FROM receiver_activity_event ORDER BY id LIMIT 2)
                """);
            statement.execute("ANALYZE receiver_activity_event");
            schema = schemaObjects(connection);
            statistics = unaffectedStatistics(connection);
            originalOrder = orderedIds(connection, "ASC");
            assertTrue(originalOrder.size() >= 2);
        }
        var activity = ActivityMigrationPreservation.capture(configuration);
        Map<String,List<String>> savedConfiguration;
        try(var connection = open(configuration))
        {
            savedConfiguration = Format68To69DatabaseMigrationTest.snapshot(connection);
        }
        byte[] configurationBefore = Files.readAllBytes(configuration);
        byte[] historyBefore = Files.readAllBytes(history);
        var plan = ApplicationMigrationService.readStartupPlan(configuration);
        assertEquals(List.of("activity-index-rowid-order-v1"),
            plan.steps().stream().map(DatabaseMigrationChain.StepPreflight::id).toList());
        assertEquals(2 * (Files.size(configuration) + Files.size(history)),
            ApplicationMigrationService.requiredOwnedMigrationWorkspace(configuration, MigrationContent.FULL));
        assertThrows(IllegalStateException.class, () -> ApplicationDatabaseMigrator.migrateInPlace(configuration, phase -> {
            if(phase.equals("Validating the updated schema and configuration")) throw new IllegalStateException("injected");
        }));
        assertArrayEquals(configurationBefore, Files.readAllBytes(configuration));
        assertArrayEquals(historyBefore, Files.readAllBytes(history));

        ApplicationDatabaseMigrator.migrateInPlace(configuration, null);
        SdrTrunkDatabaseStartup.validateGlobalDatabase(configuration);
        ActivityMigrationPreservation.assertCurrent(configuration, activity);
        try(var connection = open(configuration))
        {
            assertEquals(savedConfiguration, Format68To69DatabaseMigrationTest.snapshot(connection));
        }
        try(var connection = open(history))
        {
            var rebuilt = schemaObjects(connection);
            assertEquals(schema.keySet(), rebuilt.keySet());
            Set<String> changed = new TreeSet<>();
            schema.forEach((name, definition) -> {
                if(!definition.equals(rebuilt.get(name))) changed.add(name);
            });
            assertEquals(ActivityMigrationPreservation.FORMAT_73_INDEXES, changed);
            assertEquals(statistics, unaffectedStatistics(connection));
            var ascending = orderedIds(connection, "ASC");
            assertEquals(originalOrder, ascending);
            assertEquals(ascending.reversed(), orderedIds(connection, "DESC"));
        }
    }

    private static List<Long> orderedIds(Connection connection, String direction) throws Exception
    {
        String sql = "SELECT id FROM receiver_activity_event INDEXED BY idx_receiver_activity_event_retention " +
            "WHERE observed_at_ms BETWEEN 999999 AND 1000001 ORDER BY observed_at_ms " + direction + ",id " + direction;
        List<Long> ids = new ArrayList<>();
        try(var statement = connection.createStatement())
        {
            try(var plan = statement.executeQuery("EXPLAIN QUERY PLAN " + sql))
            {
                while(plan.next()) assertFalse(plan.getString(4).contains("TEMP B-TREE"));
            }
            try(var rows = statement.executeQuery(sql))
            {
                while(rows.next()) ids.add(rows.getLong(1));
            }
        }
        return ids;
    }

    private static TreeMap<String,List<String>> schemaObjects(Connection connection) throws Exception
    {
        var objects = new TreeMap<String,List<String>>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(
            "SELECT name,type,rootpage,coalesce(sql,'') FROM sqlite_schema"))
        {
            while(rows.next()) objects.put(rows.getString(1), List.of(rows.getString(2), rows.getString(3), rows.getString(4)));
        }
        return objects;
    }

    private static List<String> unaffectedStatistics(Connection connection) throws Exception
    {
        List<String> statistics = new ArrayList<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(
            "SELECT tbl,idx,stat FROM sqlite_stat1 ORDER BY tbl,idx"))
        {
            while(rows.next())
            {
                if(rows.getString(2) == null || !ActivityMigrationPreservation.FORMAT_73_INDEXES.contains(rows.getString(2)))
                    statistics.add(rows.getString(1) + "|" + rows.getString(2) + "|" + rows.getString(3));
            }
        }
        return statistics;
    }

    private static Connection open(Path database) throws Exception
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }
}
