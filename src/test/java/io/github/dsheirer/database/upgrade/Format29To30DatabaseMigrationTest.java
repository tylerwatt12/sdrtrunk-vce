/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format29To30DatabaseMigrationTest
{
    private static final String SYSTEM_INDEX = "idx_receiver_activity_event_system_encrypted_time";
    private static final String CHANNEL_INDEX = "idx_receiver_activity_event_channel_encrypted_time";

    @TempDir
    Path mTemporaryFolder;

    @Test
    void addsOnlySparseEncryptedIndexesAndPreservesEveryEvent() throws Exception
    {
        Path database = Format29TestDatabase.create(mTemporaryFolder.resolve("format-29.sqlite"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            populateRepresentativeActivity(statement);
            long eventsBefore = scalarLong(statement, "SELECT count(*) FROM receiver_activity_event");
            long encryptedBefore = scalarLong(statement,
                "SELECT count(*) FROM receiver_activity_event WHERE encrypted=1");
            assertEquals(200, encryptedBefore);
            assertFalse(indexExists(statement, SYSTEM_INDEX));
            assertFalse(indexExists(statement, CHANNEL_INDEX));

            DatabaseMigrationChain.MigrationReport report = DatabaseMigrationChain.migrate(connection);

            assertEquals(1, report.steps().size());
            assertEquals("format-29-to-30", report.steps().getFirst().id());
            assertEquals(encryptedBefore, report.steps().getFirst().effects().getFirst().affectedRows());
            assertEquals(eventsBefore, scalarLong(statement, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(encryptedBefore,
                scalarLong(statement, "SELECT count(*) FROM receiver_activity_event WHERE encrypted=1"));
            assertEquals(DatabaseFormatCatalog.current().fingerprint(),
                SqliteSchemaValidator.fingerprint(connection));
            assertEquals(30, DatabaseFormatCatalog.requireCurrent(connection).version());
            assertEquals("ok", scalarText(statement, "PRAGMA integrity_check"));
            assertFalse(statement.executeQuery("PRAGMA foreign_key_check").next());

            assertEquals("CREATE INDEX " + SYSTEM_INDEX + " " +
                    "ON receiver_activity_event(radio_system_id, observed_at_ms DESC, id DESC) " +
                    "WHERE encrypted = 1 AND radio_system_id IS NOT NULL",
                indexSql(statement, SYSTEM_INDEX));
            assertEquals("CREATE INDEX " + CHANNEL_INDEX + " " +
                    "ON receiver_activity_event(channel_id, observed_at_ms DESC, id DESC) " +
                    "WHERE encrypted = 1",
                indexSql(statement, CHANNEL_INDEX));

            statement.execute("ANALYZE");
            assertPlanUses(connection, SYSTEM_INDEX, """
                SELECT id FROM receiver_activity_event
                WHERE radio_system_id=? AND encrypted=? AND action_code<>?
                ORDER BY observed_at_ms DESC, id DESC LIMIT ?
                """, 900001, 1, 12, 201);
            assertPlanUses(connection, CHANNEL_INDEX, """
                SELECT id FROM receiver_activity_event
                WHERE channel_id=? AND encrypted=? AND action_code<>?
                ORDER BY observed_at_ms DESC, id DESC LIMIT ?
                """, 900001, 1, 12, 201);
            assertPlanDoesNotUse(connection, SYSTEM_INDEX, """
                SELECT id FROM receiver_activity_event
                WHERE radio_system_id=? AND encrypted=?
                ORDER BY observed_at_ms DESC, id DESC LIMIT ?
                """, 900001, 0, 201);
            assertPlanDoesNotUse(connection, CHANNEL_INDEX, """
                SELECT id FROM receiver_activity_event
                WHERE channel_id=? AND encrypted=?
                ORDER BY observed_at_ms DESC, id DESC LIMIT ?
                """, 900001, 0, 201);
            ReceiverActivitySchema.validate(connection);
        }
    }

    @Test
    void directlySelectedFormat29RepairsBoundedCurrentDamageBeforeTheStrictStamp() throws Exception
    {
        Path database = Format29TestDatabase.create(mTemporaryFolder.resolve("repair-format-29.sqlite"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms)
                VALUES ('portable_java_preferences_v1', '[]', 1)
                ON CONFLICT(key) DO UPDATE SET settings_json=excluded.settings_json,
                                               updated_at_ms=excluded.updated_at_ms
                """);
            assertEquals(1, statement.executeUpdate("""
                UPDATE application_settings SET settings_json='{}' WHERE key='setup_wizard'
                """));

            assertThrows(SQLException.class, () -> DatabaseFormatCatalog.inspect(connection));
            DatabaseFormatCatalog.DetectedFormat source = DatabaseFormatCatalog.inspectForMigration(connection);
            assertEquals(29, source.version());
            assertTrue(source.markerPresent());

            List<DatabaseMigrationEffect> inspected =
                new Format29To30DatabaseMigration().validateSource(connection);
            assertEffect(inspected, DatabaseMigrationEffect.Kind.RESET,
                "unusable portable preference components", 1);
            assertEffect(inspected, DatabaseMigrationEffect.Kind.DEFAULT,
                "unusable setup progress", 1);

            DatabaseMigrationChain.MigrationReport report = DatabaseMigrationChain.migrate(connection);
            assertEquals("format-29-to-30", report.steps().getFirst().id());
            assertEffect(report.steps().getFirst().effects(), DatabaseMigrationEffect.Kind.RESET,
                "unusable portable preference components", 1);
            assertEffect(report.steps().getFirst().effects(), DatabaseMigrationEffect.Kind.DEFAULT,
                "unusable setup progress", 1);
            assertEquals("{}", scalarText(statement, """
                SELECT settings_json FROM application_settings
                WHERE key='portable_java_preferences_v1'
                """));
            assertEquals("1", scalarText(statement, """
                SELECT json_extract(settings_json, '$.imported')
                FROM application_settings WHERE key='setup_wizard'
                """));
            assertEquals(30, DatabaseFormatCatalog.requireCurrent(connection).version());
        }
    }

    @Test
    void rollbackLeavesFormat29UnchangedAndRetryWorks() throws Exception
    {
        Path database = Format29TestDatabase.create(mTemporaryFolder.resolve("retry.sqlite"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            connection.setAutoCommit(false);
            try
            {
                assertEquals(30, DatabaseMigrationChain.migrate(connection).target().version());
                connection.rollback();
            }
            finally
            {
                connection.setAutoCommit(true);
            }
            assertEquals(29, DatabaseFormatCatalog.inspect(connection).version());
            assertFalse(indexExists(statement, SYSTEM_INDEX));
            assertFalse(indexExists(statement, CHANNEL_INDEX));
            assertEquals(30, DatabaseMigrationChain.migrate(connection).target().version());
        }
    }

    @Test
    void refusesAnySourceOtherThanExactFormat29() throws Exception
    {
        Path database = Format28TestDatabase.create(mTemporaryFolder.resolve("format-28.sqlite"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);
            SQLException exception = assertThrows(SQLException.class,
                () -> new Format29To30DatabaseMigration().migrate(connection));
            assertTrue(exception.getMessage().contains("requires format 29"), exception::getMessage);
            assertEquals(28, DatabaseFormatCatalog.inspect(connection).version());
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
            VALUES (900001, 'p25:abcde:321', 1, 0, 0xABCDE, 0x321, 1000, 30000)
            """);
        try(PreparedStatement insertChannel = statement.getConnection().prepareStatement("""
            INSERT INTO receiver_channel(
                id, configuration_id, first_seen_ms, last_seen_ms,
                radio_system_id, radio_system_assigned_at_ms)
            VALUES (900001, ?, 1000, 30000, 900001, 1000)
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
                channel_id, radio_system_id, observed_at_ms, action_code, encrypted)
            SELECT 900001, 900001, 1000 + value, 23,
                   CASE WHEN value % 100 = 0 THEN 1 ELSE 0 END
            FROM sequence
            """);
    }

    private static void assertPlanUses(Connection connection, String index, String sql, Object... parameters)
        throws Exception
    {
        List<String> plan = queryPlan(connection, sql, parameters);
        assertTrue(plan.stream().anyMatch(detail -> detail.contains(index)),
            () -> "Expected " + index + " in query plan: " + plan);
        assertTrue(plan.stream().noneMatch(detail -> detail.contains("USE TEMP B-TREE")),
            () -> "Expected index-ordered Activity paging: " + plan);
    }

    private static void assertEffect(List<DatabaseMigrationEffect> effects, DatabaseMigrationEffect.Kind kind,
                                     String subject, long rows)
    {
        DatabaseMigrationEffect effect = effects.stream().filter(candidate -> candidate.kind() == kind &&
            candidate.subject().equals(subject)).findFirst().orElseThrow();
        assertEquals(rows, effect.affectedRows());
    }

    private static void assertPlanDoesNotUse(Connection connection, String index, String sql, Object... parameters)
        throws Exception
    {
        List<String> plan = queryPlan(connection, sql, parameters);
        assertTrue(plan.stream().noneMatch(detail -> detail.contains(index)),
            () -> "Clear Activity must not use encrypted-only index " + index + ": " + plan);
    }

    private static List<String> queryPlan(Connection connection, String sql, Object... parameters) throws Exception
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
        return plan;
    }

    private static boolean indexExists(Statement statement, String index) throws Exception
    {
        try(PreparedStatement query = statement.getConnection().prepareStatement(
            "SELECT 1 FROM sqlite_schema WHERE type='index' AND name=?"))
        {
            query.setString(1, index);
            try(ResultSet rows = query.executeQuery())
            {
                return rows.next();
            }
        }
    }

    private static String indexSql(Statement statement, String index) throws Exception
    {
        try(PreparedStatement query = statement.getConnection().prepareStatement(
            "SELECT sql FROM sqlite_schema WHERE type='index' AND name=?"))
        {
            query.setString(1, index);
            try(ResultSet rows = query.executeQuery())
            {
                assertTrue(rows.next());
                return rows.getString(1).replaceAll("\\s+", " ").trim();
            }
        }
    }

    private static long scalarLong(Statement statement, String sql) throws Exception
    {
        try(ResultSet rows = statement.executeQuery(sql))
        {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static String scalarText(Statement statement, String sql) throws Exception
    {
        try(ResultSet rows = statement.executeQuery(sql))
        {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
}
