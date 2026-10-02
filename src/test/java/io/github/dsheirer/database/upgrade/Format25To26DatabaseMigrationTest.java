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
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format25To26DatabaseMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void preservesDetailedAndConventionalActivityAndAllowsExactRadioControlTypes() throws Exception
    {
        Path database = Format25TestDatabase.create(mTemporaryFolder.resolve("format-25.sqlite"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            populateRepresentativeActivity(statement);
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO receiver_activity_event(channel_id, observed_at_ms, action_code, event_type_code)
                VALUES (9500, 3000, 21, 58)
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE conventional_activity_summary SET last_event_type_code=58 WHERE channel_id=9500
                """));

            List<List<Object>> eventsBefore = rows(statement,
                "SELECT * FROM receiver_activity_event ORDER BY id");
            List<List<Object>> membersBefore = rows(statement,
                "SELECT * FROM activity_event_identity_member ORDER BY event_id, identity_summary_id");
            List<List<Object>> summariesBefore = rows(statement, """
                SELECT * FROM conventional_activity_summary
                ORDER BY channel_id, frequency_hz, timeslot
                """);
            long maximumEventId = number(statement, "SELECT max(id) FROM receiver_activity_event");
            List<DatabaseMigrationEffect> expectedEffects =
                new Format25To26DatabaseMigration().validateSource(connection);
            assertEquals(List.of((long)eventsBefore.size(), (long)membersBefore.size(), (long)summariesBefore.size()),
                expectedEffects.subList(0, 3).stream().map(DatabaseMigrationEffect::affectedRows).toList());

            statement.execute("PRAGMA foreign_keys=OFF");
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
            statement.execute("PRAGMA foreign_keys=ON");

            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION - 25, report.steps().size());
            assertEquals("format-25-to-26", report.steps().getFirst().id());
            assertEquals("format-30-to-31", report.steps().getLast().id());
            assertEquals(expectedEffects, report.steps().getFirst().effects());
            assertEquals(eventsBefore, rows(statement, "SELECT * FROM receiver_activity_event ORDER BY id"));
            assertEquals(membersBefore, rows(statement,
                "SELECT * FROM activity_event_identity_member ORDER BY event_id, identity_summary_id"));
            assertEquals(summariesBefore, rows(statement, """
                SELECT * FROM conventional_activity_summary
                ORDER BY channel_id, frequency_hz, timeslot
                """));
            assertEquals(6, number(statement, """
                SELECT count(*) FROM sqlite_schema
                WHERE type='index' AND name IN (
                    'idx_receiver_activity_event_system_action_time',
                    'idx_receiver_activity_event_channel_action_time',
                    'idx_receiver_activity_event_system_event_type_time',
                    'idx_receiver_activity_event_channel_event_type_time',
                    'idx_receiver_activity_event_channel_source_id_time',
                    'idx_receiver_activity_event_channel_target_id_time'
                )
                """));

            String[] names = {"RADIO_UNINHIBIT", "RADIO_INHIBIT", "RADIO_UNINHIBIT_ACK", "RADIO_INHIBIT_ACK"};
            for(int index = 0; index < names.length; index++)
            {
                int code = 58 + index;
                statement.executeUpdate("""
                    INSERT INTO receiver_activity_event(
                        channel_id, radio_system_id, observed_at_ms, action_code, event_type_code,
                        source_observed_local_id)
                    VALUES (9500, 9500, %d, 21, %d, 2002)
                    """.formatted(4000 + index, code));
                assertEquals(names[index], text(statement, """
                    SELECT event_type FROM receiver_activity_event_resolved
                    WHERE event_type_code=%d ORDER BY id DESC LIMIT 1
                    """.formatted(code)));
            }
            assertTrue(number(statement,
                "SELECT min(id) FROM receiver_activity_event WHERE event_type_code BETWEEN 58 AND 61") >
                maximumEventId);
            statement.executeUpdate("""
                UPDATE conventional_activity_summary SET last_event_type_code=61 WHERE channel_id=9500
                """);
            assertEquals(61, number(statement, """
                SELECT last_event_type_code FROM conventional_activity_summary WHERE channel_id=9500
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO receiver_activity_event(channel_id, observed_at_ms, action_code, event_type_code)
                VALUES (9500, 5000, 21, 62)
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE conventional_activity_summary SET last_event_type_code=62 WHERE channel_id=9500
                """));

            assertEquals("ok", text(statement, "PRAGMA integrity_check"));
            assertFalse(statement.executeQuery("PRAGMA foreign_key_check").next());
            assertEquals(DatabaseFormatCatalog.current().fingerprint(),
                SqliteSchemaValidator.fingerprint(connection));
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                DatabaseFormatCatalog.requireCurrent(connection).version());
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);
            SQLException wrongSource = assertThrows(SQLException.class,
                () -> new Format25To26DatabaseMigration().migrate(connection));
            assertTrue(wrongSource.getMessage().contains("Expected format 25"), wrongSource::getMessage);
            assertEquals(fingerprint, SqliteSchemaValidator.fingerprint(connection));
        }
    }

    @Test
    void repairsDamagedDirectFormat25StateBeforeRebuildingActivityTables() throws Exception
    {
        Path database = Format25TestDatabase.create(mTemporaryFolder.resolve("damaged-format-25.sqlite"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            populateRepresentativeActivity(statement);
            assertEquals(1, statement.executeUpdate(
                "UPDATE sqlite_sequence SET seq=-1 WHERE name='receiver_activity_event'"));
            List<DatabaseMigrationEffect> validated =
                new Format25To26DatabaseMigration().validateSource(connection);
            assertEquals(List.of(0L, 0L, 0L),
                validated.subList(0, 3).stream().map(DatabaseMigrationEffect::affectedRows).toList());

            statement.execute("PRAGMA foreign_keys=OFF");
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
                statement.execute("PRAGMA foreign_keys=ON");
            }
            assertEquals(25, DatabaseFormatCatalog.inspectForMigration(connection).version());
            assertEquals(-1, number(statement,
                "SELECT seq FROM sqlite_sequence WHERE name='receiver_activity_event'"));

            DatabaseMigrationChain.MigrationReport report =
                migrateWithForeignKeysDisabled(connection, statement);

            List<DatabaseMigrationEffect> effects = report.steps().getFirst().effects();
            assertEquals(List.of(0L, 0L, 0L),
                effects.subList(0, 3).stream().map(DatabaseMigrationEffect::affectedRows).toList());
            assertTrue(effects.stream().anyMatch(effect ->
                effect.kind() == DatabaseMigrationEffect.Kind.RESET &&
                    effect.subject().equals("bounded receiver activity and statistics rows") &&
                    effect.affectedRows() > 0));
            assertEquals(0, number(statement, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(0, number(statement, "SELECT count(*) FROM activity_event_identity_member"));
            assertEquals(0, number(statement, "SELECT count(*) FROM conventional_activity_summary"));
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                DatabaseFormatCatalog.requireCurrent(connection).version());
        }
    }

    @Test
    void clearsActivityOrphanedByDirectFormat25ConfigurationRepair() throws Exception
    {
        Path database = Format25TestDatabase.create(mTemporaryFolder.resolve("orphaned-format-25.sqlite"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            populateRepresentativeActivity(statement);
            assertEquals(1, statement.executeUpdate("""
                UPDATE configuration_channel SET config_json='{}'
                WHERE configuration_id=(
                    SELECT configuration_id FROM receiver_channel WHERE id=9500
                )
                """));

            DatabaseMigrationChain.MigrationReport report =
                migrateWithForeignKeysDisabled(connection, statement);
            List<DatabaseMigrationEffect> effects = report.steps().getFirst().effects();
            assertTrue(effects.stream().anyMatch(effect ->
                effect.kind() == DatabaseMigrationEffect.Kind.DROP &&
                    effect.subject().equals("unusable saved channel rows") && effect.affectedRows() == 1));
            assertTrue(effects.stream().anyMatch(effect ->
                effect.kind() == DatabaseMigrationEffect.Kind.RESET &&
                    effect.subject().equals("bounded receiver activity and statistics rows") &&
                    effect.affectedRows() > 0));
            assertEquals(0, number(statement, "SELECT count(*) FROM receiver_channel"));
            assertEquals(0, number(statement, "SELECT count(*) FROM receiver_activity_event"));
            assertFalse(statement.executeQuery("PRAGMA foreign_key_check").next());
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                DatabaseFormatCatalog.requireCurrent(connection).version());
        }
    }

    private static void populateRepresentativeActivity(Statement statement) throws Exception
    {
        String configurationId = text(statement,
            "SELECT configuration_id FROM configuration_channel ORDER BY id LIMIT 1");
        statement.executeUpdate("""
            INSERT INTO radio_system(
                id, system_key, protocol_code, address_domain_code, p25_wacn, p25_system_id,
                first_seen_ms, last_seen_ms)
            VALUES (9500, 'p25:abcde:321', 1, 0, 0xABCDE, 0x321, 1000, 3000)
            """);
        statement.executeUpdate("""
            INSERT INTO receiver_channel(
                id, configuration_id, first_seen_ms, last_seen_ms,
                radio_system_id, radio_system_assigned_at_ms)
            VALUES (9500, '%s', 1000, 3000, 9500, 1000)
            """.formatted(configurationId));
        statement.executeUpdate("""
            INSERT INTO radio_system_identity_summary(
                id, radio_system_id, identity_kind_code, home_wacn, home_system_id,
                identity_id, first_seen_ms, last_seen_ms)
            VALUES (9501, 9500, 2, 0xABCDE, 0x321, 2002, 1000, 3000),
                   (9502, 9500, 1, 0xABCDE, 0x321, 1001, 1000, 3000),
                   (9503, 9500, 1, 0xABCDE, 0x321, 1002, 1000, 3000)
            """);
        statement.executeUpdate("""
            INSERT INTO receiver_activity_event(
                id, channel_id, radio_system_id, observed_at_ms, action_code, event_type_code,
                source_observed_local_id, target_observed_local_id, target_kind_code,
                source_identity_summary_id, source_identity_kind_code, target_identity_summary_id,
                frequency_hz, lcn_band, lcn_number, timeslot, encrypted,
                encryption_algorithm_id, encryption_key_id, observed_nac, observed_rfss, observed_site)
            VALUES (9600, 9500, 9500, 2000, 12, 57, 2002, 1001, 1,
                9501, 2, 9502, 851012500, 1, 10, 2, 1, 128, 77, 0x123, 4, 5)
            """);
        statement.executeUpdate("""
            INSERT INTO activity_event_identity_member(
                event_id, radio_system_id, identity_summary_id, identity_kind_code, observed_local_id)
            VALUES (9600, 9500, 9503, 1, 1002)
            """);
        statement.executeUpdate("""
            INSERT INTO conventional_activity_summary(
                channel_id, frequency_hz, timeslot, first_seen_ms, last_seen_ms,
                grant_count, denial_count, last_event_type_code, encrypted_count,
                recorded_count, streamed_count)
            VALUES (9500, 155000000, -1, 1000, 3000, 7, 2, 57, 3, 4, 5)
            """);
    }

    private static List<List<Object>> rows(Statement statement, String sql) throws SQLException
    {
        List<List<Object>> result = new ArrayList<>();
        try(ResultSet rows = statement.executeQuery(sql))
        {
            ResultSetMetaData metadata = rows.getMetaData();
            while(rows.next())
            {
                List<Object> row = new ArrayList<>();
                for(int column = 1; column <= metadata.getColumnCount(); column++)
                {
                    row.add(rows.getObject(column));
                }
                result.add(row);
            }
        }
        return List.copyOf(result);
    }

    private static long number(Statement statement, String sql) throws SQLException
    {
        try(ResultSet rows = statement.executeQuery(sql))
        {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    private static DatabaseMigrationChain.MigrationReport migrateWithForeignKeysDisabled(Connection connection,
                                                                                           Statement statement)
        throws Exception
    {
        statement.execute("PRAGMA foreign_keys=OFF");
        connection.setAutoCommit(false);
        try
        {
            DatabaseMigrationChain.MigrationReport report = DatabaseMigrationChain.migrate(connection);
            connection.commit();
            return report;
        }
        catch(Exception exception)
        {
            connection.rollback();
            throw exception;
        }
        finally
        {
            connection.setAutoCommit(true);
            statement.execute("PRAGMA foreign_keys=ON");
        }
    }

    private static String text(Statement statement, String sql) throws SQLException
    {
        try(ResultSet rows = statement.executeQuery(sql))
        {
            return rows.next() ? rows.getString(1) : null;
        }
    }
}
