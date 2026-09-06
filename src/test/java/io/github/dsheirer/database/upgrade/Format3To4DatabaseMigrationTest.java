/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format3To4DatabaseMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void populatedPreflightDeclaresConfigurationPreservationAndCompleteActivityReset() throws Exception
    {
        Path database = Format3TestDatabase.create(mTemporaryFolder.resolve("preflight.sqlite"));

        try(Connection connection = open(database))
        {
            DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);
            DatabaseMigrationChain.PreflightReport report = DatabaseMigrationChain.validateSource(connection,
                detected);
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION - 3, report.steps().size());
            DatabaseMigrationChain.StepPreflight step = report.steps().getFirst();
            assertEquals("format-3-to-4", step.id());
            assertEffect(step.effects(), DatabaseMigrationEffect.Kind.PRESERVE,
                "administrator configuration", true);
            assertEquals(2, step.effects().size());
            assertEffect(step.effects(), DatabaseMigrationEffect.Kind.RESET,
                "receiver-derived activity and counters", true);
        }
    }

    @Test
    void migratesExactPopulatedFormat3AndCanRetryFromUnchangedSource() throws Exception
    {
        Path source = Format3TestDatabase.create(mTemporaryFolder.resolve("source.sqlite"));
        Path retry = mTemporaryFolder.resolve("retry.sqlite");
        Files.copy(source, retry);

        migrateAndAssert(source);
        migrateAndAssert(retry);
    }

    @Test
    void directStepLeavesEveryFormat4ActivityTableEmptyAtOneFreshBoundary() throws Exception
    {
        Path database = Format3TestDatabase.create(mTemporaryFolder.resolve("direct-reset.sqlite"));

        try(Connection connection = open(database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format3To4DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 4);
                connection.commit();
            }
            catch(Exception exception)
            {
                connection.rollback();
                throw exception;
            }
            finally
            {
                connection.setAutoCommit(true);
            }

            assertEquals(4, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(0, LegacyActivityReset.count(connection, LegacyActivityReset.LOGICAL_CALL_TABLES));
            String boundary = metadata(connection, "conventional_call_output_metrics_started_at_ms");
            assertEquals(boundary, metadata(connection, "trunked_logical_call_metrics_started_at_ms"));
            assertEquals(boundary, metadata(connection, "trunked_identity_metrics_started_at_ms"));
            assertTrue(Long.parseLong(boundary) > 0);
            assertEquals("{\"preserved\":true}", scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='format-3-preserve-sentinel'
                """));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
        }
    }

    private static void migrateAndAssert(Path database) throws Exception
    {
        try(Connection connection = open(database))
        {
            connection.setAutoCommit(false);
            DatabaseMigrationChain.MigrationReport report;

            try
            {
                report = DatabaseMigrationChain.migrate(connection);
                connection.commit();
            }
            catch(Exception e)
            {
                connection.rollback();
                throw e;
            }
            finally
            {
                connection.setAutoCommit(true);
            }

            assertEquals(3, report.source().version());
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, report.target().version());
            assertEquals("format-14-to-15", report.steps().getLast().id());
            DatabaseFormatCatalog.DetectedFormat current = DatabaseFormatCatalog.requireCurrent(connection);
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, current.version());
            assertEquals(DatabaseFormatCatalog.current().fingerprint(),
                SqliteSchemaValidator.fingerprint(connection));

            //Administrator configuration survives the full chain.
            assertEquals("{\"preserved\":true}", scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='format-3-preserve-sentinel'
                """));
            //Format 15 intentionally resets all old receiver/system/site observations whose identities were ambiguous.
            for(String retired: List.of("receiver_context", "call_identity_bucket", "p25_site_frequency_summary",
                "p25_site_talkgroup_bucket", "p25_site_activity_bucket", "p25_control_channel_quality",
                "trunked_radio_site_presence", "trunked_radio_talkgroup_summary"))
            {
                assertFalse(tableExists(connection, retired), "Retired table remains: " + retired);
            }
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM receiver_channel"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM radio_system"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM trunked_control_channel_quality"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM statistics_status"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM conventional_activity_summary"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM trunked_logical_call_bucket"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM p25_site_call_bucket"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM p25_learned_site"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM trunked_site_snapshot"));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM pragma_table_info('trunked_signaling_activity_bucket') WHERE name='call_count'
                """));

            assertNull(metadata(connection, "p25_activity_schema_version"));
            long boundary = Long.parseLong(metadata(connection,
                "conventional_call_output_metrics_started_at_ms"));
            assertTrue(boundary > 200);
            assertEquals(Long.toString(boundary), metadata(connection,
                "trunked_logical_call_metrics_started_at_ms"));
            assertEquals(Long.toString(boundary), metadata(connection,
                "radio_system_metrics_started_at_ms"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
            ApplicationDatabaseMigrator.validateCurrentDatabase(connection);
        }
    }

    private static Connection open(Path database) throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    private static String metadata(Connection connection, String key) throws Exception
    {
        try(var statement = connection.prepareStatement(
            "SELECT value FROM database_metadata WHERE key=?"))
        {
            statement.setString(1, key);
            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static String scalar(Connection connection, String sql) throws Exception
    {
        String value = nullableScalar(connection, sql);
        return value == null ? "" : value;
    }

    private static String nullableScalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private static boolean tableExists(Connection connection, String table) throws Exception
    {
        try(var statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM sqlite_schema WHERE type='table' AND name=?"))
        {
            statement.setString(1, table);
            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() && resultSet.getInt(1) == 1;
            }
        }
    }

    private static void assertEffect(List<DatabaseMigrationEffect> effects,
                                     DatabaseMigrationEffect.Kind kind, String subject,
                                     long expectedRows)
    {
        DatabaseMigrationEffect effect = effects.stream()
            .filter(candidate -> candidate.kind() == kind && candidate.subject().equals(subject))
            .findFirst().orElseThrow();
        assertEquals(expectedRows, effect.affectedRows());
    }

    private static void assertEffect(List<DatabaseMigrationEffect> effects,
                                     DatabaseMigrationEffect.Kind kind, String subject,
                                     boolean positive)
    {
        DatabaseMigrationEffect effect = effects.stream()
            .filter(candidate -> candidate.kind() == kind && candidate.subject().equals(subject))
            .findFirst().orElseThrow();
        assertTrue(!positive || effect.affectedRows() > 0);
    }
}
