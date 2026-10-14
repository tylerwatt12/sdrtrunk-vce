/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format50To51DatabaseMigrationTest
{
    @TempDir Path directory;

    @Test void preservesDigitalCountersAndHistoryAndStartsAnalogWithoutFabricatedMatches() throws Exception
    {
        Path database = Format50TestDatabase.create(directory.resolve("prior.sqlite"));
        try(Connection connection = open(database); var statement = connection.createStatement())
        {
            long analogList = scalar(connection, "SELECT id FROM alias_list WHERE family='NBFM' LIMIT 1");
            long digitalList = scalar(connection, "SELECT id FROM alias_list WHERE family='P25' LIMIT 1");
            statement.executeUpdate("INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value) VALUES " +
                "(900001," + analogList + ",'Analog fixture','TALKGROUP','AM',17)," +
                "(900002," + digitalList + ",'Digital fixture','TALKGROUP','APCO25',18)");
            statement.executeUpdate("INSERT INTO alias_activity_summary(alias_id,alias_list_id,protocol_code,metrics_state,logical_call_count,first_evidence_ms,last_evidence_ms,updated_at_ms) " +
                "VALUES(900001," + analogList + ",0,'unsupported',23,1000,2000,2000)");
            statement.executeUpdate("INSERT INTO alias_activity_summary(alias_id,alias_list_id,protocol_code,metrics_state,logical_call_count,first_evidence_ms,last_evidence_ms,updated_at_ms) " +
                "VALUES(900002," + digitalList + ",1,'observed',17,1000,2000,2000)");
            long events = scalar(connection, "SELECT count(*) FROM receiver_activity_event");
            long channelCount = scalar(connection, "SELECT count(*) FROM configuration_channel");
            connection.setAutoCommit(false);
            var report = DatabaseMigrationChain.migrate(connection);
            connection.commit();
            assertEquals(List.of("format-50-to-51", "format-51-to-52"), report.steps().stream().map(DatabaseMigrationChain.StepReport::id).toList());
            assertEquals(52, DatabaseFormatCatalog.requireCurrent(connection).version());
            assertEquals(17, scalar(connection, "SELECT logical_call_count FROM alias_activity_summary WHERE alias_id=900002"));
            assertEquals(2000, scalar(connection, "SELECT last_evidence_ms FROM alias_activity_summary WHERE alias_id=900002"));
            assertEquals("11:not_collected", text(connection, "SELECT protocol_code||':'||metrics_state FROM alias_activity_summary WHERE alias_id=900001"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM alias_activity_summary WHERE alias_id=900001 AND logical_call_count IS NOT NULL"));
            assertTrue(scalar(connection, "SELECT last_reset_ms FROM alias_activity_summary WHERE alias_id=900001") > 0);
            assertEquals(0, scalar(connection, "SELECT count(*) FROM alias_list_analog_activity_summary"));
            assertEquals(events, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(channelCount, scalar(connection, "SELECT count(*) FROM configuration_channel"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM configuration_channel WHERE analog_activity_reset_ms IS NOT NULL"));
            assertTrue(DatabaseMigrationChain.migrate(connection).steps().isEmpty());
            integrity(connection);
        }
        Path fresh = directory.resolve("fresh.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(fresh);
        try(Connection connection = open(fresh))
        {
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            integrity(connection);
        }
    }

    @Test void migrationRollbackLeavesFrozenFormat50AndCanRetry() throws Exception
    {
        Path database = Format50TestDatabase.create(directory.resolve("retry.sqlite"));
        assertThrows(SQLException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));
        try(Connection connection = open(database))
        {
            connection.setAutoCommit(false);
            new Format50To51DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 51);
            connection.rollback();
            assertEquals(50, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(0, scalar(connection, "SELECT count(*) FROM pragma_table_info('alias_activity_summary') WHERE name='last_reset_ms'"));
            DatabaseMigrationChain.migrate(connection);
            connection.commit();
            assertEquals(52, DatabaseFormatCatalog.requireCurrent(connection).version());
            integrity(connection);
        }
    }

    @Test void analogSummaryHasBoundedExplicitKeysAndRejectsImpossibleCounts() throws Exception
    {
        Path database = Format51TestDatabase.create(directory.resolve("constraints.sqlite"));
        try(Connection connection = open(database); var statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            long list = scalar(connection, "SELECT id FROM alias_list WHERE family='NBFM' LIMIT 1");
            String channel = text(connection, "SELECT configuration_id FROM configuration_channel LIMIT 1");
            String prefix = "INSERT INTO alias_list_analog_activity_summary(alias_list_id,channel_configuration_id,protocol_code,assigned_talkgroup,call_count,unmatched_call_count,first_seen_ms,last_seen_ms,updated_at_ms) VALUES(" + list + ",'" + channel + "',";
            statement.executeUpdate(prefix + "10,17,2,1,1000,2000,2000)");
            assertThrows(SQLException.class, () -> statement.executeUpdate(prefix + "10,18,1,2,1000,2000,2000)"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(prefix + "1,18,1,0,1000,2000,2000)"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(prefix + "11,65536,1,0,1000,2000,2000)"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM alias_list_analog_activity_summary"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE configuration_channel SET analog_activity_reset_ms=0"));
            statement.executeUpdate("UPDATE configuration_channel SET analog_activity_reset_ms=2000");
            assertEquals(2000, scalar(connection,
                "SELECT analog_activity_reset_ms FROM configuration_channel LIMIT 1"));
            try(var rows = statement.executeQuery("EXPLAIN QUERY PLAN SELECT * FROM alias_list_analog_activity_summary WHERE channel_configuration_id='" + channel + "'"))
            {
                assertTrue(rows.next()); assertTrue(rows.getString("detail").contains("idx_alias_list_analog_activity_channel"));
            }
            integrity(connection);
        }
    }

    private static Connection open(Path path) throws SQLException { return DriverManager.getConnection("jdbc:sqlite:" + path); }
    private static long scalar(Connection connection, String sql) throws SQLException
    { try(var statement=connection.createStatement();var rows=statement.executeQuery(sql)) { return rows.next()?rows.getLong(1):0; } }
    private static String text(Connection connection, String sql) throws SQLException
    { try(var statement=connection.createStatement();var rows=statement.executeQuery(sql)) { return rows.next()?rows.getString(1):null; } }
    private static void integrity(Connection connection) throws SQLException
    {
        assertEquals("ok", text(connection,"PRAGMA integrity_check"));
        try(var statement=connection.createStatement();var rows=statement.executeQuery("PRAGMA foreign_key_check")) { assertFalse(rows.next()); }
    }
}
