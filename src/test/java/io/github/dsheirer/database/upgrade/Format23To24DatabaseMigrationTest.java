/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format23To24DatabaseMigrationTest
{
    private static final Set<String> FILTER_INDEXES = Set.of(
        "idx_receiver_activity_event_system_action_time",
        "idx_receiver_activity_event_channel_action_time",
        "idx_receiver_activity_event_system_event_type_time",
        "idx_receiver_activity_event_channel_event_type_time",
        "idx_receiver_activity_event_channel_source_id_time",
        "idx_receiver_activity_event_channel_target_id_time");

    @TempDir
    Path mTemporaryFolder;

    @Test
    void addsOnlyActivityFilterIndexesAndPreservesEveryRow() throws Exception
    {
        Path database = Format23TestDatabase.create(mTemporaryFolder.resolve("format-23.sqlite"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            populateRepresentativeActivity(statement);
            Map<String,Long> rowsBefore = tableRowCounts(statement);
            List<List<Object>> activityBefore = activityRows(statement);
            Set<String> objectsBefore = schemaObjects(statement);

            DatabaseMigrationChain.MigrationReport report = DatabaseMigrationChain.migrate(connection);

            assertEquals(6, report.steps().size());
            assertEquals("format-23-to-24", report.steps().getFirst().id());
            assertEquals("format-28-to-29", report.steps().getLast().id());
            assertEquals(activityBefore.size(), report.steps().getFirst().effects().getFirst().affectedRows());
            assertEquals(rowsBefore, tableRowCounts(statement));
            assertEquals(activityBefore, activityRows(statement));

            Set<String> addedObjects = schemaObjects(statement);
            addedObjects.removeAll(objectsBefore);
            assertEquals(FILTER_INDEXES, addedObjects);
            assertEquals(DatabaseFormatCatalog.current().fingerprint(),
                SqliteSchemaValidator.fingerprint(connection));
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                DatabaseFormatCatalog.requireCurrent(connection).version());
            assertEquals("ok", scalarText(statement, "PRAGMA integrity_check"));
            assertFalse(statement.executeQuery("PRAGMA foreign_key_check").next());

            assertExactIndexSql(statement);
            statement.execute("ANALYZE");
            assertActivityFilterPlans(connection);
        }
    }

    @Test
    void refusesAnySourceOtherThanExactFormat23() throws Exception
    {
        Path database = Format22TestDatabase.create(mTemporaryFolder.resolve("format-22.sqlite"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);
            SQLException exception = assertThrows(SQLException.class,
                () -> new Format23To24DatabaseMigration().migrate(connection));
            assertTrue(exception.getMessage().contains("Expected format 23"), exception::getMessage);
            assertEquals(22, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(fingerprint, SqliteSchemaValidator.fingerprint(connection));
        }
    }

    private static void populateRepresentativeActivity(Statement statement) throws Exception
    {
        String configurationId = scalarText(statement,
            "SELECT configuration_id FROM configuration_channel ORDER BY id LIMIT 1");
        statement.executeUpdate("""
            INSERT INTO radio_system(
                id, system_key, protocol_code, address_domain_code, p25_wacn, p25_system_id,
                first_seen_ms, last_seen_ms)
            VALUES (900001, 'p25:abcde:321', 1, 0, 0xABCDE, 0x321, 1000, 20000)
            """);
        try(PreparedStatement insertChannel = statement.getConnection().prepareStatement("""
            INSERT INTO receiver_channel(
                id, configuration_id, first_seen_ms, last_seen_ms,
                radio_system_id, radio_system_assigned_at_ms)
            VALUES (900001, ?, 1000, 20000, 900001, 1000)
            """))
        {
            insertChannel.setString(1, configurationId);
            insertChannel.executeUpdate();
        }
        statement.executeUpdate("""
            WITH RECURSIVE sequence(value) AS (
                VALUES (1)
                UNION ALL
                SELECT value + 1 FROM sequence WHERE value < 20000
            )
            INSERT INTO receiver_activity_event(
                channel_id, radio_system_id, observed_at_ms, action_code, event_type_code,
                source_observed_local_id, target_observed_local_id)
            SELECT 900001, 900001, 1000 + value,
                   1 + (value % 23), 1 + (value % 57),
                   1000 + (value % 100), 2000 + (value % 100)
            FROM sequence
            """);
    }

    private static void assertExactIndexSql(Statement statement) throws Exception
    {
        assertEquals("CREATE INDEX idx_receiver_activity_event_system_action_time " +
                "ON receiver_activity_event(radio_system_id, action_code, observed_at_ms DESC, id DESC) " +
                "WHERE radio_system_id IS NOT NULL",
            indexSql(statement, "idx_receiver_activity_event_system_action_time"));
        assertEquals("CREATE INDEX idx_receiver_activity_event_channel_action_time " +
                "ON receiver_activity_event(channel_id, action_code, observed_at_ms DESC, id DESC)",
            indexSql(statement, "idx_receiver_activity_event_channel_action_time"));
        assertEquals("CREATE INDEX idx_receiver_activity_event_system_event_type_time " +
                "ON receiver_activity_event(radio_system_id, event_type_code, observed_at_ms DESC, id DESC) " +
                "WHERE radio_system_id IS NOT NULL AND event_type_code IS NOT NULL",
            indexSql(statement, "idx_receiver_activity_event_system_event_type_time"));
        assertEquals("CREATE INDEX idx_receiver_activity_event_channel_event_type_time " +
                "ON receiver_activity_event(channel_id, event_type_code, observed_at_ms DESC, id DESC) " +
                "WHERE event_type_code IS NOT NULL",
            indexSql(statement, "idx_receiver_activity_event_channel_event_type_time"));
        assertEquals("CREATE INDEX idx_receiver_activity_event_channel_source_id_time " +
                "ON receiver_activity_event(channel_id, source_observed_local_id, observed_at_ms DESC, id DESC) " +
                "WHERE source_observed_local_id IS NOT NULL",
            indexSql(statement, "idx_receiver_activity_event_channel_source_id_time"));
        assertEquals("CREATE INDEX idx_receiver_activity_event_channel_target_id_time " +
                "ON receiver_activity_event(channel_id, target_observed_local_id, observed_at_ms DESC, id DESC) " +
                "WHERE target_observed_local_id IS NOT NULL",
            indexSql(statement, "idx_receiver_activity_event_channel_target_id_time"));
    }

    private static void assertActivityFilterPlans(Connection connection) throws Exception
    {
        assertPlanUses(connection, "idx_receiver_activity_event_system_action_time", """
            SELECT id FROM receiver_activity_event
            WHERE radio_system_id=? AND action_code=?
            ORDER BY observed_at_ms DESC, id DESC LIMIT ?
            """, 900001, 7, 201);
        assertPlanUses(connection, "idx_receiver_activity_event_channel_action_time", """
            SELECT id FROM receiver_activity_event
            WHERE channel_id=? AND action_code=?
            ORDER BY observed_at_ms DESC, id DESC LIMIT ?
            """, 900001, 7, 201);
        assertPlanUses(connection, "idx_receiver_activity_event_system_event_type_time", """
            SELECT id FROM receiver_activity_event
            WHERE radio_system_id=? AND event_type_code=?
            ORDER BY observed_at_ms DESC, id DESC LIMIT ?
            """, 900001, 19, 201);
        assertPlanUses(connection, "idx_receiver_activity_event_channel_event_type_time", """
            SELECT id FROM receiver_activity_event
            WHERE channel_id=? AND event_type_code=?
            ORDER BY observed_at_ms DESC, id DESC LIMIT ?
            """, 900001, 19, 201);
        assertPlanUses(connection, "idx_receiver_activity_event_channel_source_id_time", """
            SELECT id FROM receiver_activity_event
            WHERE channel_id=? AND source_observed_local_id=?
            ORDER BY observed_at_ms DESC, id DESC LIMIT ?
            """, 900001, 1007, 201);
        assertPlanUses(connection, "idx_receiver_activity_event_channel_target_id_time", """
            SELECT id FROM receiver_activity_event
            WHERE channel_id=? AND target_observed_local_id=?
            ORDER BY observed_at_ms DESC, id DESC LIMIT ?
            """, 900001, 2007, 201);
    }

    private static void assertPlanUses(Connection connection, String index, String sql, Object... parameters)
        throws Exception
    {
        List<String> plan = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement("EXPLAIN QUERY PLAN " + sql))
        {
            for(int position = 0; position < parameters.length; position++)
            {
                statement.setObject(position + 1, parameters[position]);
            }
            try(ResultSet rows = statement.executeQuery())
            {
                while(rows.next())
                {
                    plan.add(rows.getString("detail"));
                }
            }
        }
        assertTrue(plan.stream().anyMatch(detail -> detail.contains(index)),
            () -> "Expected " + index + " in query plan: " + plan);
        assertTrue(plan.stream().noneMatch(detail -> detail.contains("USE TEMP B-TREE")),
            () -> "Expected index-ordered Activity paging: " + plan);
    }

    private static Map<String,Long> tableRowCounts(Statement statement) throws Exception
    {
        Map<String,Long> counts = new LinkedHashMap<>();
        try(ResultSet tables = statement.executeQuery("""
            SELECT name FROM sqlite_schema
            WHERE type='table' AND name NOT LIKE 'sqlite_%'
            ORDER BY name
            """))
        {
            List<String> names = new ArrayList<>();
            while(tables.next())
            {
                names.add(tables.getString(1));
            }
            for(String name: names)
            {
                try(ResultSet rows = statement.executeQuery("SELECT count(*) FROM \"" + name + "\""))
                {
                    rows.next();
                    counts.put(name, rows.getLong(1));
                }
            }
        }
        return counts;
    }

    private static Set<String> schemaObjects(Statement statement) throws Exception
    {
        Set<String> names = new TreeSet<>();
        try(ResultSet rows = statement.executeQuery("""
            SELECT name FROM sqlite_schema
            WHERE name NOT LIKE 'sqlite_%'
            ORDER BY name
            """))
        {
            while(rows.next())
            {
                names.add(rows.getString(1));
            }
        }
        return names;
    }

    private static List<List<Object>> activityRows(Statement statement) throws Exception
    {
        List<List<Object>> snapshot = new ArrayList<>();
        try(ResultSet rows = statement.executeQuery("SELECT * FROM receiver_activity_event ORDER BY id"))
        {
            ResultSetMetaData metadata = rows.getMetaData();
            while(rows.next())
            {
                List<Object> row = new ArrayList<>(metadata.getColumnCount());
                for(int column = 1; column <= metadata.getColumnCount(); column++)
                {
                    row.add(rows.getObject(column));
                }
                snapshot.add(Collections.unmodifiableList(row));
            }
        }
        return List.copyOf(snapshot);
    }

    private static String indexSql(Statement statement, String index) throws Exception
    {
        try(PreparedStatement query = statement.getConnection().prepareStatement(
            "SELECT sql FROM sqlite_schema WHERE type='index' AND name=?"))
        {
            query.setString(1, index);
            try(ResultSet rows = query.executeQuery())
            {
                rows.next();
                return rows.getString(1).replaceAll("\\s+", " ").trim();
            }
        }
    }

    private static String scalarText(Statement statement, String sql) throws Exception
    {
        try(ResultSet rows = statement.executeQuery(sql))
        {
            return rows.next() ? rows.getString(1) : null;
        }
    }
}
