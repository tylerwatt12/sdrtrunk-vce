/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format24To25DatabaseMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void preservesEveryRowAndDefaultsAbsentEncryptedCallSuppressionOff() throws Exception
    {
        Path database = Format24TestDatabase.create(mTemporaryFolder.resolve("format-24.sqlite"));

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            addNXDNPreservationSentinel(statement);
            assertEquals(1, statement.executeUpdate("""
                UPDATE database_metadata
                SET value=CASE WHEN EXISTS (SELECT 1 FROM web_user WHERE primary_admin=1)
                               THEN 'complete' ELSE 'required' END,
                    updated_at_ms=1
                WHERE key='initial_admin_setup'
                """));
            String configurationBefore = configurationDigest(connection);
            String fingerprintBefore = SqliteSchemaValidator.fingerprint(connection);
            Map<String,Long> rowsBefore = tableRowCounts(statement);
            List<DatabaseMigrationEffect> sourceEffects =
                new Format24To25DatabaseMigration().validateSource(connection);
            assertTrue(sourceEffects.subList(2, sourceEffects.size()).stream()
                .allMatch(effect -> effect.affectedRows() == 0), sourceEffects::toString);
            String changesBefore = scalar(connection, "SELECT total_changes()");
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM configuration_channel
                WHERE decoder_type IN ('P25_PHASE1', 'P25_PHASE2', 'DMR')
                  AND json_type(config_json, '$.decodeConfiguration.ignoreEncryptedCalls') IS NOT NULL
                """));
            assertEquals("1:true", scalar(connection, """
                SELECT COUNT(*) || ':' || json_type(config_json,
                    '$.decodeConfiguration.ignoreEncryptedCalls')
                FROM configuration_channel WHERE decoder_type='NXDN'
                """));

            new Format24To25DatabaseMigration().migrate(connection);
            assertEquals(changesBefore, scalar(connection, "SELECT total_changes()"));
            assertEquals(fingerprintBefore, SqliteSchemaValidator.fingerprint(connection));

            DatabaseMigrationChain.PreflightReport preflight = DatabaseMigrationChain.validateSource(connection,
                DatabaseFormatCatalog.inspect(connection));
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION - 24, preflight.steps().size());
            assertEquals("format-24-to-25", preflight.steps().getFirst().id());
            assertEquals("format-25-to-26", preflight.steps().get(1).id());
            assertEquals("format-30-to-31", preflight.steps().getLast().id());
            assertEffect(preflight.steps().getFirst().effects(), DatabaseMigrationEffect.Kind.PRESERVE,
                "saved channel configurations", DatabaseMigrationEffect.UNKNOWN_COUNT);
            assertEffect(preflight.steps().getFirst().effects(), DatabaseMigrationEffect.Kind.DEFAULT,
                "encrypted traffic-channel suppression", 0);

            DatabaseMigrationChain.MigrationReport report = DatabaseMigrationChain.migrate(connection);

            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION - 24, report.steps().size());
            assertEquals("format-24-to-25", report.steps().getFirst().id());
            assertEquals("format-25-to-26", report.steps().get(1).id());
            assertEquals("format-30-to-31", report.steps().getLast().id());
            assertEquals(3, report.steps().get(1).effects().size());
            assertEquals(rowsBefore.get("configuration_channel"),
                report.steps().getFirst().effects().getFirst().affectedRows());
            assertEquals(rowsBefore, tableRowCounts(statement));
            assertEquals(configurationBefore, configurationDigest(connection));
            assertEquals(DatabaseFormatCatalog.current().fingerprint(),
                SqliteSchemaValidator.fingerprint(connection));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM configuration_channel
                WHERE decoder_type IN ('P25_PHASE1', 'P25_PHASE2', 'DMR')
                  AND json_type(config_json, '$.decodeConfiguration.ignoreEncryptedCalls') IS NOT NULL
                """));
            assertEquals("1:true", scalar(connection, """
                SELECT COUNT(*) || ':' || json_type(config_json,
                    '$.decodeConfiguration.ignoreEncryptedCalls')
                FROM configuration_channel WHERE decoder_type='NXDN'
                """));
            assertEquals(Integer.toString(DatabaseFormatCatalog.CURRENT_VERSION),
                metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                DatabaseFormatCatalog.requireCurrent(connection).version());
        }
    }

    @Test
    void callerRollbackRestoresExactFormat24AndAllowsRetry() throws Exception
    {
        Path database = Format24TestDatabase.create(mTemporaryFolder.resolve("rollback.sqlite"));

        try(Connection connection = open(database))
        {
            String configurationBefore = configurationDigest(connection);
            connection.setAutoCommit(false);
            try
            {
                assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                    DatabaseMigrationChain.migrate(connection).target().version());
                connection.rollback();
            }
            finally
            {
                connection.setAutoCommit(true);
            }

            assertEquals(configurationBefore, configurationDigest(connection));
            assertEquals(24, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals("24", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                DatabaseMigrationChain.migrate(connection).target().version());
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                DatabaseFormatCatalog.requireCurrent(connection).version());
        }
    }

    @Test
    void repairsRecoverableFormat24StateBeforeStrictStampAndRollsBackForRetry() throws Exception
    {
        Path database = Format24TestDatabase.create(mTemporaryFolder.resolve("repair-and-retry.sqlite"));

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            assertEquals(1, statement.executeUpdate("""
                UPDATE application_settings SET settings_json='{}' WHERE key='setup_wizard'
                """));
            statement.executeUpdate("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms)
                VALUES ('portable_java_preferences_v1', '[]', 1)
                ON CONFLICT(key) DO UPDATE SET settings_json=excluded.settings_json,
                                               updated_at_ms=excluded.updated_at_ms
                """);
            String configurationBefore = configurationDigest(connection);

            DatabaseFormatCatalog.DetectedFormat source = DatabaseFormatCatalog.inspectForMigration(connection);
            assertEquals(24, source.version());
            String changesBeforePreflight = scalar(connection, "SELECT total_changes()");
            List<DatabaseMigrationEffect> inspectedEffects =
                new Format24To25DatabaseMigration().validateSource(connection);
            assertEffect(inspectedEffects, DatabaseMigrationEffect.Kind.RESET,
                "unusable portable preference components", 1);
            assertEffect(inspectedEffects, DatabaseMigrationEffect.Kind.DEFAULT,
                "unusable setup progress", 1);
            DatabaseMigrationChain.PreflightReport preflight =
                DatabaseMigrationChain.validateSource(connection, source);
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION - 24, preflight.steps().size());
            assertEquals("format-24-to-25", preflight.steps().getFirst().id());
            assertEquals("format-25-to-26", preflight.steps().get(1).id());
            assertEquals("format-30-to-31", preflight.steps().getLast().id());
            assertEffect(preflight.steps().getFirst().effects(), DatabaseMigrationEffect.Kind.RESET,
                "unusable portable preference components", DatabaseMigrationEffect.UNKNOWN_COUNT);
            assertEffect(preflight.steps().getFirst().effects(), DatabaseMigrationEffect.Kind.DEFAULT,
                "unusable setup progress", DatabaseMigrationEffect.UNKNOWN_COUNT);
            assertEquals(changesBeforePreflight, scalar(connection, "SELECT total_changes()"));
            assertEquals("{}", scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='setup_wizard'
                """));
            assertEquals("[]", scalar(connection, """
                SELECT settings_json FROM application_settings
                WHERE key='portable_java_preferences_v1'
                """));
            assertEquals("24", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));

            connection.setAutoCommit(false);
            try
            {
                DatabaseMigrationChain.MigrationReport first = DatabaseMigrationChain.migrate(connection);
                assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, first.target().version());
                assertEffect(first.steps().getFirst().effects(), DatabaseMigrationEffect.Kind.RESET,
                    "unusable portable preference components", 1);
                assertEffect(first.steps().getFirst().effects(), DatabaseMigrationEffect.Kind.DEFAULT,
                    "unusable setup progress", 1);
                assertEquals("{}", scalar(connection, """
                    SELECT settings_json FROM application_settings
                    WHERE key='portable_java_preferences_v1'
                    """));
                assertEquals("1", scalar(connection, """
                    SELECT json_extract(settings_json, '$.imported') FROM application_settings
                    WHERE key='setup_wizard'
                    """));
                assertEquals(configurationBefore, configurationDigest(connection));
                connection.rollback();
            }
            finally
            {
                connection.setAutoCommit(true);
            }

            assertEquals(24, DatabaseFormatCatalog.inspectForMigration(connection).version());
            assertEquals("24", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            assertEquals("{}", scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='setup_wizard'
                """));
            assertEquals("[]", scalar(connection, """
                SELECT settings_json FROM application_settings
                WHERE key='portable_java_preferences_v1'
                """));

            connection.setAutoCommit(false);
            try
            {
                DatabaseMigrationChain.MigrationReport retry = DatabaseMigrationChain.migrate(connection);
                assertEffect(retry.steps().getFirst().effects(), DatabaseMigrationEffect.Kind.RESET,
                    "unusable portable preference components", 1);
                assertEffect(retry.steps().getFirst().effects(), DatabaseMigrationEffect.Kind.DEFAULT,
                    "unusable setup progress", 1);
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

            assertEquals(configurationBefore, configurationDigest(connection));
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                DatabaseFormatCatalog.requireCurrent(connection).version());
        }
    }

    @Test
    void refusesAnySourceOtherThanExactFormat24() throws Exception
    {
        Path database = Format23TestDatabase.create(mTemporaryFolder.resolve("format-23.sqlite"));

        try(Connection connection = open(database))
        {
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);
            SQLException exception = assertThrows(SQLException.class,
                () -> new Format24To25DatabaseMigration().migrate(connection));
            assertTrue(exception.getMessage().contains("requires exact source format 24"), exception::getMessage);
            assertEquals(23, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(fingerprint, SqliteSchemaValidator.fingerprint(connection));
        }
    }

    private static void assertEffect(List<DatabaseMigrationEffect> effects, DatabaseMigrationEffect.Kind kind,
                                     String subject, long rows)
    {
        DatabaseMigrationEffect effect = effects.stream().filter(candidate -> candidate.kind() == kind &&
            candidate.subject().equals(subject)).findFirst().orElseThrow();
        assertEquals(rows, effect.affectedRows());
    }

    private static void addNXDNPreservationSentinel(Statement statement) throws SQLException
    {
        assertEquals(1, statement.executeUpdate("""
            INSERT INTO configuration_channel(
                configuration_id, channel_kind, sort_order, system_name, site_name, name, alias_list_id,
                radioresolve_id, auto_start, auto_start_order, decoder_type, address_domain_code,
                primary_frequency_hz, config_json)
            SELECT '25252525-2525-4525-8525-252525252525', 'TRUNKED',
                   (SELECT COALESCE(MAX(sort_order), -1) + 1 FROM configuration_channel),
                   'Migration Fixture', 'NXDN Site', 'NXDN Encrypted Skip',
                   (SELECT id FROM alias_list WHERE family='NXDN' ORDER BY id LIMIT 1),
                   NULL, 0, NULL, 'NXDN', 1, primary_frequency_hz,
                   json_set(config_json,
                       '$.decodeConfiguration', json_object(
                           'type', 'decodeConfigNXDN',
                           'channelMode', 'TRUNKED',
                           'ignoreEncryptedCalls', json('true')))
            FROM configuration_channel WHERE decoder_type='P25_PHASE1' ORDER BY id LIMIT 1
            """));
    }

    private static String configurationDigest(Connection connection) throws SQLException
    {
        return scalar(connection, """
            SELECT group_concat(value, '|') FROM (
                SELECT id || ':' || configuration_id || ':' || config_json AS value
                FROM configuration_channel ORDER BY id)
            """);
    }

    private static Map<String,Long> tableRowCounts(Statement statement) throws SQLException
    {
        Map<String,Long> counts = new LinkedHashMap<>();
        try(ResultSet tables = statement.executeQuery("""
            SELECT name FROM sqlite_schema
            WHERE type='table' AND name NOT LIKE 'sqlite_%'
            ORDER BY name
            """))
        {
            List<String> names = new java.util.ArrayList<>();
            while(tables.next())
            {
                names.add(tables.getString(1));
            }
            for(String name: names)
            {
                try(ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM \"" + name + "\""))
                {
                    rows.next();
                    counts.put(name, rows.getLong(1));
                }
            }
        }
        return counts;
    }

    private static String metadata(Connection connection, String key) throws SQLException
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

    private static String scalar(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private static Connection open(Path database) throws SQLException
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }
}
