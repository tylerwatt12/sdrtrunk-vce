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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format5To6DatabaseMigrationTest
{
    private static final long CONTEXT_ID = 900;
    private static final String CONFIGURATION_ID = "66666666-7777-4888-8999-aaaaaaaaaaaa";
    private static final String GUID = "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff";
    private static final String LEGACY_KEY = "GUID:" + GUID;
    private static final String CANONICAL_KEY = "CONFIGURATION:" + CONFIGURATION_ID;

    @TempDir
    Path mTemporaryFolder;

    @Test
    void migratesExactLegacyKeyAndPreservesReceiverContextIdAndHistory() throws Exception
    {
        Path database = Format5TestDatabase.create(mTemporaryFolder.resolve("legacy.sqlite"));

        try(Connection connection = open(database))
        {
            String contextBefore = scalar(connection, """
                SELECT id || ':' || guid || ':' || kind_code || ':' || protocol_code || ':' ||
                       first_seen_ms || ':' || last_seen_ms
                FROM receiver_context WHERE id=900
                """);
            String historyBefore = historyDigest(connection);
            DatabaseMigrationChain.PreflightReport preflight = DatabaseMigrationChain.validateSource(connection,
                DatabaseFormatCatalog.inspect(connection));
            assertEquals(1, preflight.steps().size());
            assertEquals("format-5-to-6", preflight.steps().getFirst().id());
            assertEffect(preflight.steps().getFirst().effects(), DatabaseMigrationEffect.Kind.TRANSFORM,
                "configured conventional receiver-context identities", 1);

            connection.setAutoCommit(false);
            DatabaseMigrationChain.MigrationReport report;

            try
            {
                report = DatabaseMigrationChain.migrate(connection);
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

            assertEquals(5, report.source().version());
            assertEquals(6, report.target().version());
            assertEquals(1, report.steps().size());
            assertEquals("format-5-to-6", report.steps().getFirst().id());
            assertEquals(CANONICAL_KEY, scalar(connection,
                "SELECT context_key FROM receiver_context WHERE id=900"));
            assertEquals(contextBefore, scalar(connection, """
                SELECT id || ':' || guid || ':' || kind_code || ':' || protocol_code || ':' ||
                       first_seen_ms || ':' || last_seen_ms
                FROM receiver_context WHERE id=900
                """));
            assertEquals(historyBefore, historyDigest(connection));
            assertEquals("1", scalar(connection,
                "SELECT COUNT(*) FROM conventional_call_identity_bucket WHERE context_id=900"));
            assertEquals("29", metadata(connection, "p25_activity_schema_version"));
            assertEquals("6", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
            assertEquals(6, DatabaseFormatCatalog.requireCurrent(connection).version());
        }
    }

    @Test
    void acceptsAlreadyCanonicalContextWithoutChangingItsIdentityOrHistory() throws Exception
    {
        Path database = Format5TestDatabase.create(mTemporaryFolder.resolve("canonical.sqlite"));

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE receiver_context SET context_key='" + CANONICAL_KEY +
                "' WHERE id=" + CONTEXT_ID);
            String historyBefore = historyDigest(connection);
            Format5To6DatabaseMigration migration = new Format5To6DatabaseMigration();
            assertEffect(migration.validateSource(connection), DatabaseMigrationEffect.Kind.TRANSFORM,
                "configured conventional receiver-context identities", 0);

            connection.setAutoCommit(false);
            try
            {
                DatabaseMigrationChain.MigrationReport report = DatabaseMigrationChain.migrate(connection);
                assertEquals(6, report.target().version());
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

            assertEquals(CANONICAL_KEY, scalar(connection,
                "SELECT context_key FROM receiver_context WHERE id=900"));
            assertEquals(historyBefore, historyDigest(connection));
            assertEquals("29", metadata(connection, "p25_activity_schema_version"));
            assertEquals("6", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            assertEquals(6, DatabaseFormatCatalog.requireCurrent(connection).version());
        }
    }

    @Test
    void callerRollbackRestoresExactFormat5IdentityMetadataAndHistory() throws Exception
    {
        Path database = Format5TestDatabase.create(mTemporaryFolder.resolve("rollback.sqlite"));

        try(Connection connection = open(database))
        {
            String historyBefore = historyDigest(connection);
            connection.setAutoCommit(false);

            try
            {
                DatabaseMigrationChain.MigrationReport report = DatabaseMigrationChain.migrate(connection);
                assertEquals(6, report.target().version());
                assertEquals(CANONICAL_KEY, scalar(connection,
                    "SELECT context_key FROM receiver_context WHERE id=900"));
                assertEquals("29", metadata(connection, "p25_activity_schema_version"));
                assertEquals("6", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
                connection.rollback();
            }
            finally
            {
                connection.setAutoCommit(true);
            }

            assertEquals(LEGACY_KEY, scalar(connection,
                "SELECT context_key FROM receiver_context WHERE id=900"));
            assertEquals("28", metadata(connection, "p25_activity_schema_version"));
            assertEquals("5", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            assertEquals(historyBefore, historyDigest(connection));
            assertEquals(5, DatabaseFormatCatalog.inspect(connection).version());
        }
    }

    @Test
    void refusesCaseInsensitiveGuidAmbiguityWithoutChangingSource() throws Exception
    {
        Path database = Format5TestDatabase.create(mTemporaryFolder.resolve("ambiguous-guid.sqlite"));

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_context(
                    id, context_key, guid, kind_code, protocol_code, channel_name,
                    first_seen_ms, last_seen_ms, primary_frequency_hz
                ) VALUES (
                    901, 'GUID:BBBBBBBB-CCCC-4DDD-8EEE-FFFFFFFFFFFF',
                    'BBBBBBBB-CCCC-4DDD-8EEE-FFFFFFFFFFFF', 10, 11, 'Ambiguous Conventional',
                    100, 200, 155550000
                )
                """);
            assertRefusedWithoutChange(connection,
                "more than one receiver context matches the GUID case-insensitively");
        }
    }

    @Test
    void refusesOccupiedCanonicalTargetWithoutChangingSource() throws Exception
    {
        Path database = Format5TestDatabase.create(mTemporaryFolder.resolve("occupied-target.sqlite"));

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_context(
                    id, context_key, guid, kind_code, protocol_code, channel_name,
                    first_seen_ms, last_seen_ms, primary_frequency_hz
                ) VALUES (
                    901, 'CONFIGURATION:66666666-7777-4888-8999-aaaaaaaaaaaa', NULL,
                    10, 11, 'Conflicting Conventional', 100, 200, 155550000
                )
                """);
            assertRefusedWithoutChange(connection,
                "configured target key is already owned by another receiver context");
        }
    }

    @Test
    void refusesUnexpectedLegacyKeyWithoutChangingSource() throws Exception
    {
        Path database = Format5TestDatabase.create(mTemporaryFolder.resolve("unexpected-key.sqlite"));

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE receiver_context
                SET context_key='CONVENTIONAL_ANALOG:NBFM:155550000'
                WHERE id=900
                """);
            assertRefusedWithoutChange(connection,
                "matching receiver context has an unexpected identity key");
        }
    }

    @Test
    void refusesNonconventionalContextWithoutChangingSource() throws Exception
    {
        Path database = Format5TestDatabase.create(mTemporaryFolder.resolve("wrong-kind.sqlite"));

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE receiver_context SET kind_code=1 WHERE id=900");
            assertRefusedWithoutChange(connection,
                "matching receiver context is not a recognized conventional context");
        }
    }

    private static void assertRefusedWithoutChange(Connection connection, String expectedMessage) throws Exception
    {
        String contextBefore = receiverContextDigest(connection);
        String historyBefore = historyDigest(connection);
        String metadataBefore = metadata(connection, "p25_activity_schema_version") + ':' +
            metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY);
        SQLException exception = assertThrows(SQLException.class,
            () -> DatabaseMigrationChain.validateSource(connection, DatabaseFormatCatalog.inspect(connection)));
        assertTrue(exception.getMessage().contains(expectedMessage), exception::getMessage);
        assertEquals(contextBefore, receiverContextDigest(connection));
        assertEquals(historyBefore, historyDigest(connection));
        assertEquals(metadataBefore, metadata(connection, "p25_activity_schema_version") + ':' +
            metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
    }

    private static String receiverContextDigest(Connection connection) throws Exception
    {
        return scalar(connection, """
            SELECT group_concat(id || ':' || context_key || ':' || coalesce(guid, '') || ':' || kind_code, '|')
            FROM (SELECT * FROM receiver_context ORDER BY id)
            """);
    }

    private static String historyDigest(Connection connection) throws Exception
    {
        return scalar(connection, """
            SELECT
                (SELECT COUNT(*) || ':' || coalesce(SUM(observed_at_ms), 0)
                 FROM p25_activity_event WHERE context_id=900) || '|' ||
                (SELECT COUNT(*) || ':' || coalesce(SUM(call_count), 0) || ':' ||
                        coalesce(SUM(recorded_count), 0) || ':' || coalesce(SUM(streamed_count), 0)
                 FROM conventional_activity_summary WHERE context_id=900) || '|' ||
                (SELECT COUNT(*) || ':' || coalesce(SUM(call_count), 0) || ':' ||
                        coalesce(SUM(recorded_count), 0) || ':' || coalesce(SUM(streamed_count), 0)
                 FROM conventional_activity_bucket WHERE context_id=900) || '|' ||
                (SELECT COUNT(*) || ':' || coalesce(SUM(call_count), 0) || ':' ||
                        coalesce(SUM(recorded_count), 0) || ':' || coalesce(SUM(streamed_count), 0)
                 FROM conventional_call_identity_bucket WHERE context_id=900)
            """);
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
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private static void assertEffect(List<DatabaseMigrationEffect> effects,
                                     DatabaseMigrationEffect.Kind kind, String subject, long expectedRows)
    {
        DatabaseMigrationEffect effect = effects.stream()
            .filter(candidate -> candidate.kind() == kind && candidate.subject().equals(subject))
            .findFirst().orElseThrow();
        assertEquals(expectedRows, effect.affectedRows());
    }
}
