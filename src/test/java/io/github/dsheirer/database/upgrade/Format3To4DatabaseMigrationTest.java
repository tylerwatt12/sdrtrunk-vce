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
    void populatedPreflightDeclaresEveryPreservedTransformedAndResetCategory() throws Exception
    {
        Path database = Format3TestDatabase.create(mTemporaryFolder.resolve("preflight.sqlite"));

        try(Connection connection = open(database))
        {
            DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);
            DatabaseMigrationChain.PreflightReport report = DatabaseMigrationChain.validateSource(connection,
                detected);
            assertEquals(5, report.steps().size());
            DatabaseMigrationChain.StepPreflight step = report.steps().getFirst();
            assertEquals("format-3-to-4", step.id());
            assertEffect(step.effects(), DatabaseMigrationEffect.Kind.PRESERVE,
                "administrator configuration", true);
            assertEffect(step.effects(), DatabaseMigrationEffect.Kind.PRESERVE,
                "compatible receiver history", true);
            assertEffect(step.effects(), DatabaseMigrationEffect.Kind.TRANSFORM,
                "receiver-context Alias List identities", 3);
            assertEffect(step.effects(), DatabaseMigrationEffect.Kind.TRANSFORM,
                "conventional call-identity buckets", 1);
            assertEffect(step.effects(), DatabaseMigrationEffect.Kind.TRANSFORM,
                "trunked non-CALL signaling buckets", 1);
            assertEffect(step.effects(), DatabaseMigrationEffect.Kind.RESET,
                "physical receiver-leg call projections", 4);
            assertEffect(step.effects(), DatabaseMigrationEffect.Kind.RESET,
                "trunked identity evidence", 3);
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
            assertEquals(8, report.target().version());
            assertEquals("format-7-to-8", report.steps().getLast().id());
            DatabaseFormatCatalog.DetectedFormat current = DatabaseFormatCatalog.requireCurrent(connection);
            assertEquals(8, current.version());
            assertEquals(DatabaseFormatCatalog.current().fingerprint(),
                SqliteSchemaValidator.fingerprint(connection));

            //Administrator configuration and structurally unchanged receiver history are preserved.
            assertEquals("{\"preserved\":true}", scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='format-3-preserve-sentinel'
                """));
            assertEquals("5:2:3:1", scalar(connection, """
                SELECT call_count || ':' || encrypted_count || ':' || recorded_count || ':' || streamed_count
                FROM conventional_activity_summary WHERE context_id=701
                """));
            assertEquals("6:1:22001", scalar(connection, """
                SELECT call_count || ':' || encrypted_count || ':' || last_source_radio_id
                FROM dmr_conventional_talkgroup_summary WHERE context_id=703
                """));
            assertEquals("97.5:195:5", scalar(connection, """
                SELECT decode_health_pct || ':' || valid_frames || ':' || invalid_frames
                FROM p25_control_channel_quality WHERE guid='format-3-guid'
                """));
            assertEquals("fixture:2", scalar(connection, """
                SELECT snapshot_hash || ':' || observation_count
                FROM trunked_site_snapshot WHERE guid='format-3-trunked-site'
                """));

            //Alias identities are recovered only for one exact NOCASE match; unmatched names remain safely null.
            assertEquals("Default P25", scalar(connection, """
                SELECT alias_list.name
                FROM receiver_context JOIN alias_list ON alias_list.id=receiver_context.alias_list_id
                WHERE receiver_context.id=700
                """));
            assertEquals("Default DMR", scalar(connection, """
                SELECT alias_list.name
                FROM receiver_context JOIN alias_list ON alias_list.id=receiver_context.alias_list_id
                WHERE receiver_context.id=703
                """));
            assertNull(nullableScalar(connection,
                "SELECT alias_list_id FROM receiver_context WHERE id=702"));

            //Compatible conventional identities and non-CALL signaling are copied exactly.
            assertEquals("5:2:3:1", scalar(connection, """
                SELECT call_count || ':' || encrypted_count || ':' || recorded_count || ':' || streamed_count
                FROM conventional_call_identity_bucket WHERE context_id=701 AND identity_id=4101
                """));
            assertEquals("0", scalar(connection,
                "SELECT COUNT(*) FROM conventional_call_identity_bucket WHERE context_id=700"));
            assertEquals("3:2", scalar(connection, """
                SELECT grant_count || ':' || page_count
                FROM trunked_signaling_activity_bucket
                WHERE context_id=700 AND bucket_start_ms=1699999200000
                """));

            //Physical CALL and old identity projections are reset; new logical/site projections start empty.
            for(String retired: List.of("call_identity_bucket", "p25_site_frequency_summary",
                "p25_site_talkgroup_bucket", "p25_site_activity_bucket"))
            {
                assertFalse(tableExists(connection, retired), "Retired table remains: " + retired);
            }
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM trunked_identity_scope"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM trunked_logical_call_bucket"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM p25_site_call_bucket"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM p25_learned_site"));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM pragma_table_info('trunked_signaling_activity_bucket') WHERE name='call_count'
                """));

            assertEquals("29", metadata(connection, "p25_activity_schema_version"));
            assertEquals("200", metadata(connection, "conventional_call_output_metrics_started_at_ms"));
            assertTrue(Long.parseLong(metadata(connection,
                "trunked_logical_call_metrics_started_at_ms")) > 200);
            assertTrue(Long.parseLong(metadata(connection,
                "trunked_identity_metrics_started_at_ms")) > 200);
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
