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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format1To2DatabaseMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void migratesExactPopulatedFormat1AndAppliesDeclaredResetPolicy() throws Exception
    {
        Path database = Format1TestDatabase.create(mTemporaryFolder.resolve("format-1.sqlite"));
        populateFormat1(database);

        DatabaseMigrationChain.MigrationReport report;
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("BEGIN IMMEDIATE");
            try
            {
                report = DatabaseMigrationChain.migrate(connection);
                statement.execute("COMMIT");
            }
            catch(Exception e)
            {
                statement.execute("ROLLBACK");
                throw e;
            }
        }

        assertEquals(1, report.source().version());
        assertEquals(2, report.target().version());
        assertEquals(1, report.steps().size());
        assertTrue(report.steps().getFirst().effects().stream().anyMatch(effect ->
                effect.kind() == DatabaseMigrationEffect.Kind.RESET &&
                effect.subject().equals("P25 affiliation history") && effect.affectedRows() == 2));

        try(Connection connection = open(database))
        {
            DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.requireCurrent(connection);
            assertEquals(2, detected.version());
            assertEquals(DatabaseFormatCatalog.current().fingerprint(),
                SqliteSchemaValidator.fingerprint(connection));
            assertEquals("6", scalar(connection,
                "SELECT value FROM database_metadata WHERE key='alias_schema_version'"));
            assertEquals("26", scalar(connection,
                "SELECT value FROM database_metadata WHERE key='p25_activity_schema_version'"));
            assertEquals("1", scalar(connection,
                "SELECT COUNT(*) FROM scan_list WHERE name='Default' AND is_default=1"));

            assertEquals("0", scalar(connection,
                "SELECT COUNT(*) FROM alias WHERE name='Catch All'"));
            assertEquals("1", scalar(connection, """
                SELECT unmatched_talkgroup_record_enabled
                FROM alias_list WHERE name='Metro'
                """));
            assertEquals("1", scalar(connection, """
                SELECT COUNT(*)
                FROM alias_list_unmatched_talkgroup_scan_list_membership membership
                JOIN alias_list list ON list.id=membership.alias_list_id
                JOIN scan_list scan ON scan.id=membership.scan_list_id
                WHERE list.name='Metro' AND scan.is_default=1
                """));
            assertEquals("1", scalar(connection, """
                SELECT COUNT(*)
                FROM alias_list_unmatched_talkgroup_stream route
                JOIN alias_list list ON list.id=route.alias_list_id
                WHERE list.name='Metro' AND route.channel_name='Dispatch Stream'
                """));

            assertEquals("0", scalar(connection,
                "SELECT COUNT(*) FROM alias WHERE name IN ('FQ Talkgroup', 'FQ Radio')"));
            assertEquals("0", scalar(connection,
                "SELECT COUNT(*) FROM alias_broadcast_channel WHERE channel_name='Retired Route'"));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*)
                FROM alias_scan_list_membership membership
                JOIN alias owner ON owner.id=membership.alias_id
                WHERE owner.name='Muted Talkgroup'
                """));
            assertEquals("1", scalar(connection, """
                SELECT COUNT(*)
                FROM alias_scan_list_membership membership
                JOIN alias owner ON owner.id=membership.alias_id
                JOIN scan_list scan ON scan.id=membership.scan_list_id
                WHERE owner.name='Enabled Radio' AND scan.is_default=1
                """));

            assertEquals("{\"decoder\":{\"preferredControlFrequency\":851012500}}", scalar(connection,
                "SELECT config_json FROM configuration_channel WHERE name='Preserved Channel'"));
            assertFalse(objectExists(connection, "table", "p25_radio_affiliation"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM trunked_identity_scope"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM trunked_radio_affiliation"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        }
    }

    private static void populateFormat1(Path database) throws Exception
    {
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("INSERT INTO alias_list(name, family) VALUES ('Metro', 'P25')");
            statement.executeUpdate("""
                INSERT INTO alias(
                    alias_list_id, name, color, record_enabled, priority, matcher_type,
                    protocol, min_value, max_value
                )
                SELECT id, 'Catch All', 0, 1, 4, 'TALKGROUP_RANGE', 'APCO25', 0, 65535
                FROM alias_list WHERE name='Metro'
                """);
            statement.executeUpdate("""
                INSERT INTO alias_broadcast_channel(alias_id, channel_name)
                SELECT id, 'Dispatch Stream' FROM alias WHERE name='Catch All'
                """);
            statement.executeUpdate("""
                INSERT INTO alias(
                    alias_list_id, name, color, record_enabled, priority, matcher_type, protocol, value
                )
                SELECT id, 'Muted Talkgroup', 0, 0, -1, 'TALKGROUP', 'APCO25', 1200
                FROM alias_list WHERE name='Metro'
                """);
            statement.executeUpdate("""
                INSERT INTO alias(
                    alias_list_id, name, color, record_enabled, priority, matcher_type, protocol, value
                )
                SELECT id, 'Enabled Radio', 0, 0, 8, 'RADIO_ID', 'APCO25', 5001
                FROM alias_list WHERE name='Metro'
                """);
            statement.executeUpdate("""
                INSERT INTO alias(
                    alias_list_id, name, color, record_enabled, priority, matcher_type,
                    protocol, value, wacn, p25_system_id
                )
                SELECT id, 'FQ Talkgroup', 0, 0, 3, 'P25_FULLY_QUALIFIED_TALKGROUP',
                       'APCO25', 1300, 781824, 293
                FROM alias_list WHERE name='Metro'
                """);
            statement.executeUpdate("""
                INSERT INTO alias(
                    alias_list_id, name, color, record_enabled, priority, matcher_type,
                    protocol, value, wacn, p25_system_id
                )
                SELECT id, 'FQ Radio', 0, 0, 3, 'P25_FULLY_QUALIFIED_RADIO_ID',
                       'APCO25', 5002, 781824, 293
                FROM alias_list WHERE name='Metro'
                """);
            statement.executeUpdate("""
                INSERT INTO alias_broadcast_channel(alias_id, channel_name)
                SELECT id, 'Retired Route' FROM alias WHERE name='FQ Talkgroup'
                """);
            statement.executeUpdate("""
                INSERT INTO configuration_channel(
                    sort_order, name, frequency_count, recording_enabled, event_logging_enabled, config_json
                ) VALUES (
                    0, 'Preserved Channel', 1, 0, 0,
                    '{"decoder":{"preferredControlFrequency":851012500}}'
                )
                """);
            statement.executeUpdate("""
                INSERT INTO p25_system(system_key, wacn, system_id, first_seen_ms, last_seen_ms)
                VALUES (1, 781824, 293, 1000, 2000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope(
                    scope_id, scope_token, protocol_code, scope_kind_code, identity_domain_code,
                    p25_system_key, first_seen_ms, last_seen_ms
                ) VALUES (1, 'p25:781824:293', 1, 1, 0, 1, 1000, 2000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary(
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms
                ) VALUES (1, 1, 5001, 1000, 2000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_radio_affiliation(system_key, radio_id, talkgroup_id, updated_at_ms)
                VALUES (1, 5001, 1200, 2000)
                """);
        }
    }

    private static Connection open(Path database) throws Exception
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }

    private static String scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private static boolean objectExists(Connection connection, String type, String name) throws Exception
    {
        try(var statement = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type=? AND name=?"))
        {
            statement.setString(1, type);
            statement.setString(2, name);
            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }
}
