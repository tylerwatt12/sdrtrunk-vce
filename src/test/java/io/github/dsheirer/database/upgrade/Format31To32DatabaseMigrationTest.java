/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exact predecessor coverage for canonical P25 subscriber storage. */
class Format31To32DatabaseMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void preservesFormat31RowsWithoutInferringAssignmentsOrConvertingRadioAliases() throws Exception
    {
        Path database = Format31TestDatabase.create(mTemporaryFolder.resolve("format-31.sqlite"));
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("""
                INSERT INTO radio_system(
                    id,system_key,protocol_code,address_domain_code,p25_wacn,p25_system_id,
                    first_seen_ms,last_seen_ms)
                VALUES (9901,'p25:bee00:49f',1,0,0xBEE00,0x49F,1000,3000)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_channel(
                    id,configuration_id,first_seen_ms,last_seen_ms,radio_system_id,
                    radio_system_assigned_at_ms)
                VALUES (9901,'11111111-2222-4333-8444-555555555555',1000,3000,9901,1000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_learned_site(
                    learned_site_id,radio_system_id,rfss,site,first_seen_ms,last_seen_ms)
                VALUES (9901,9901,1,1,1000,3000)
                """);
            statement.executeUpdate("""
                INSERT INTO alias(alias_list_id,name,matcher_type,protocol,value)
                SELECT id,'Local radio remains local','RADIO_ID','APCO25',123
                FROM alias_list WHERE family='P25' ORDER BY id LIMIT 1
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(
                    radio_system_id,identity_kind_code,home_wacn,home_system_id,identity_id,
                    first_seen_ms,last_seen_ms)
                SELECT id,2,0xABCDE,0x321,9001,1000,2000
                FROM radio_system WHERE protocol_code=1 ORDER BY id LIMIT 1
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(
                    radio_system_id,identity_kind_code,home_wacn,home_system_id,identity_id,
                    first_seen_ms,last_seen_ms)
                VALUES (9901,1,0xBEE00,0x49F,101,1000,2000)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event(
                    channel_id,radio_system_id,observed_at_ms,action_code,
                    source_observed_local_id,target_observed_local_id,target_kind_code,
                    source_identity_summary_id,target_identity_summary_id)
                SELECT 9901,9901,2000,12,9001,101,1,radio.id,talkgroup.id
                FROM radio_system_identity_summary radio, radio_system_identity_summary talkgroup
                WHERE radio.radio_system_id=9901 AND radio.identity_kind_code=2 AND radio.identity_id=9001
                  AND talkgroup.radio_system_id=9901 AND talkgroup.identity_kind_code=1
                  AND talkgroup.identity_id=101
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_call_identity_bucket(
                    radio_system_id,learned_site_id,channel_id,bucket_start_ms,identity_role_code,
                    identity_kind_code,identity_summary_id,observed_local_id,last_observed_at_ms,
                    observed_call_count,encrypted_observed_call_count)
                SELECT 9901,9901,9901,0,2,2,id,9001,2000,1,0
                FROM radio_system_identity_summary
                WHERE radio_system_id=9901 AND identity_kind_code=2 AND identity_id=9001
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_affiliation(
                    radio_system_id,radio_identity_id,talkgroup_identity_id,channel_id,
                    radio_observed_local_id,talkgroup_observed_local_id,confirmed_at_ms)
                SELECT 9901,radio.id,talkgroup.id,9901,9001,101,2000
                FROM radio_system_identity_summary radio, radio_system_identity_summary talkgroup
                WHERE radio.radio_system_id=9901 AND radio.identity_kind_code=2 AND radio.identity_id=9001
                  AND talkgroup.radio_system_id=9901 AND talkgroup.identity_kind_code=1
                  AND talkgroup.identity_id=101
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_channel_presence(
                    radio_system_id,radio_identity_id,channel_id,observed_local_id,evidence_code,
                    confirmed_at_ms)
                SELECT 9901,id,9901,9001,1,2000
                FROM radio_system_identity_summary
                WHERE radio_system_id=9901 AND identity_kind_code=2 AND identity_id=9001
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_channel_presence_clear(
                    radio_system_id,radio_identity_id,channel_id,observed_local_id,cleared_at_ms)
                SELECT 9901,id,9901,9001,2100
                FROM radio_system_identity_summary
                WHERE radio_system_id=9901 AND identity_kind_code=2 AND identity_id=9001
                """);
            statement.executeUpdate("""
                INSERT INTO alias_scan_list_membership(alias_id,scan_list_id)
                SELECT alias.id,scan.id FROM alias,scan_list scan
                WHERE alias.name='Local radio remains local' AND scan.is_default=1
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_logical_call_identity_bucket(
                    radio_system_id,bucket_start_ms,identity_role_code,identity_kind_code,identity_summary_id)
                SELECT radio_system_id,987654321,1,identity_kind_code,id
                FROM radio_system_identity_summary
                WHERE home_wacn=0xABCDE AND home_system_id=0x321 AND identity_id=9001
                """);

            statement.executeUpdate("UPDATE alias_list SET new_alias_record_enabled=1, " +
                "unmatched_talkgroup_record_enabled=0 WHERE family='P25'");
            long independentDefaults = scalar(statement, "SELECT count(*) FROM alias_list " +
                "WHERE family='P25' AND new_alias_record_enabled=1 AND unmatched_talkgroup_record_enabled=0");
            long newAliasStreams = scalar(statement, "SELECT count(*) FROM alias_list_new_alias_stream");
            long newAliasScanLists = scalar(statement, "SELECT count(*) FROM alias_list_new_alias_scan_list_membership");
            long activityIndexes = scalar(statement, "SELECT count(*) FROM sqlite_schema WHERE type='index' " +
                "AND name LIKE 'idx_receiver_activity_event_%'");
            String preferencesBefore = text(statement, "SELECT group_concat(preferences_json) FROM web_user ORDER BY id");
            long aliases = scalar(statement, "SELECT count(*) FROM alias");
            long identities = scalar(statement, "SELECT count(*) FROM radio_system_identity_summary");
            long memberships = scalar(statement, "SELECT count(*) FROM alias_scan_list_membership");
            long identityFacts = scalar(statement, "SELECT count(*) FROM trunked_logical_call_identity_bucket");
            DatabaseMigrationChain.PreflightReport preflight = DatabaseMigrationChain.validateSource(connection,
                DatabaseFormatCatalog.inspectForMigration(connection));
            assertEquals("format-35-to-36", preflight.steps().getLast().id());
            assertEquals(aliases, new Format31To32DatabaseMigration().validateSource(connection)
                .getFirst().affectedRows());

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

            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, report.target().version());
            assertEquals(independentDefaults, scalar(statement, "SELECT count(*) FROM alias_list " +
                "WHERE family='P25' AND new_alias_record_enabled=1 AND unmatched_talkgroup_record_enabled=0"));
            assertEquals(newAliasStreams, scalar(statement, "SELECT count(*) FROM alias_list_new_alias_stream"));
            assertEquals(newAliasScanLists, scalar(statement, "SELECT count(*) FROM alias_list_new_alias_scan_list_membership"));
            assertEquals(activityIndexes + 1, scalar(statement, "SELECT count(*) FROM sqlite_schema WHERE type='index' " +
                "AND name LIKE 'idx_receiver_activity_event_%'"));
            assertEquals(preferencesBefore, text(statement, """
                SELECT group_concat(json_remove(json_set(preferences_json, '$.version', 8),
                    '$.presentation.source_name_display', '$.presentation.live_channel_sort')) FROM web_user ORDER BY id
                """), "The downstream personal preference upgrades preserve all previous personal settings");
            assertEquals(aliases, scalar(statement, "SELECT count(*) FROM alias"));
            assertEquals(identities, scalar(statement, "SELECT count(*) FROM radio_system_identity_summary"));
            assertEquals(memberships, scalar(statement, "SELECT count(*) FROM alias_scan_list_membership"));
            assertEquals(identityFacts,
                scalar(statement, "SELECT count(*) FROM trunked_logical_call_identity_bucket"));
            assertEquals(1, scalar(statement, """
                SELECT count(*) FROM alias
                WHERE name='Local radio remains local' AND matcher_type='RADIO_ID'
                  AND protocol='APCO25' AND value=123
                """));
            assertEquals(0, scalar(statement,
                "SELECT count(*) FROM alias WHERE matcher_type='P25_SUBSCRIBER_IDENTITY'"));
            assertEquals(0, scalar(statement, "SELECT count(*) FROM alias_p25_subscriber_identity"));
            assertEquals(0, scalar(statement, "SELECT count(*) FROM p25_subscriber_identity"));
            assertEquals(0, scalar(statement,
                "SELECT count(*) FROM p25_wuid_assignment_observation_summary"));
            assertEquals(0, scalar(statement, """
                SELECT count(*) FROM sqlite_sequence
                WHERE name='p25_subscriber_identity'
                """), "format 32 adds no mixed-ownership or derived AUTOINCREMENT allocator");
            assertEquals(0, scalar(statement, """
                SELECT count(*) FROM sqlite_schema
                WHERE name IN ('p25_wuid_assignment_current','p25_wuid_assignment_summary',
                    'p25_wuid_assignment_tombstone')
                """), "format 32 must not persist live WUID authority or clear watermarks");

            statement.executeUpdate("""
                INSERT INTO p25_subscriber_identity(home_wacn,home_system_id,subscriber_id)
                VALUES(0xABCDE,0x321,9001)
                """);
            long reusableIdentityId = scalar(statement, """
                SELECT id FROM p25_subscriber_identity
                WHERE home_wacn=0xABCDE AND home_system_id=0x321 AND subscriber_id=9001
                """);
            statement.executeUpdate("DELETE FROM p25_subscriber_identity WHERE id=" + reusableIdentityId);
            statement.executeUpdate("""
                INSERT INTO p25_subscriber_identity(home_wacn,home_system_id,subscriber_id)
                VALUES(0xABCDE,0x322,9002)
                """);
            assertEquals(reusableIdentityId, scalar(statement, """
                SELECT id FROM p25_subscriber_identity
                WHERE home_wacn=0xABCDE AND home_system_id=0x322 AND subscriber_id=9002
                """), "a deleted unreferenced surrogate can be safely reused by a different natural tuple");
            statement.executeUpdate("DELETE FROM p25_subscriber_identity WHERE id=" + reusableIdentityId);
            assertEquals(1, scalar(statement, """
                SELECT count(*) FROM receiver_activity_event
                WHERE source_observed_local_id=9001 AND source_observed_working_id IS NULL
                  AND target_observed_working_id IS NULL
                """));
            assertEquals(1, scalar(statement, """
                SELECT count(*) FROM p25_site_call_identity_bucket
                WHERE observed_local_id=9001 AND observed_working_id IS NULL
                """));
            assertEquals(1, scalar(statement, """
                SELECT count(*) FROM trunked_radio_affiliation
                WHERE radio_observed_local_id=9001 AND radio_observed_working_id IS NULL
                """));
            assertEquals(1, scalar(statement, """
                SELECT count(*) FROM trunked_radio_channel_presence
                WHERE observed_local_id=9001 AND observed_working_id IS NULL
                """));
            assertEquals(1, scalar(statement, """
                SELECT count(*) FROM trunked_radio_channel_presence_clear
                WHERE observed_local_id=9001 AND observed_working_id IS NULL
                """));
            assertEquals(0, scalar(statement, """
                SELECT count(*) FROM radio_system_identity_summary
                WHERE p25_subscriber_identity_id IS NOT NULL
                """));
            assertFalse(text(statement, "SELECT sql FROM sqlite_schema WHERE name='alias'")
                .contains("format20_alias"));
            assertEquals("ok", text(statement, "PRAGMA integrity_check"));
            assertEquals(0, scalar(statement, "SELECT count(*) FROM pragma_foreign_key_check"));
            assertEquals(DatabaseFormatCatalog.current().fingerprint(),
                SqliteSchemaValidator.fingerprint(connection));
            SdrTrunkDatabaseSchema.validate(connection);
            ReceiverActivitySchema.validate(connection);
            assertTrue(report.steps().getFirst().effects().stream()
                .anyMatch(effect -> effect.kind() == DatabaseMigrationEffect.Kind.DEFAULT));
        }
    }

    private static long scalar(Statement statement, String sql) throws Exception
    {
        try(ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getLong(1) : 0;
        }
    }

    private static String text(Statement statement, String sql) throws Exception
    {
        try(ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }
}
