/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** Exact populated format-44 fixture produced by the adjacent Alias List name-bound migration. */
public final class Format44TestDatabase
{
    private Format44TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format43TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format43To44DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 44);
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
            if(DatabaseFormatCatalog.inspect(connection).version() != 44 ||
                !DatabaseFormatCatalog.requireVersion(44).fingerprint().equals(SqliteSchemaValidator.fingerprint(connection)))
            {
                throw new IllegalStateException("Global format 44 fixture signature mismatch");
            }
        }
        return database;
    }

    /** Retained split-owner evidence from format 44, including colliding child keys and foreign ownership. */
    public static Path createWithHomeRadioSplits(Path database) throws Exception
    {
        create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("""
                WITH new_channels(id,configuration_id) AS (
                    VALUES(5000000100,'00000000-0000-0000-0000-000000000001'),
                          (5000000101,'00000000-0000-0000-0000-000000000002'),
                          (5000000102,'00000000-0000-0000-0000-000000000003')
                )
                INSERT INTO configuration_channel(id,configuration_id,channel_kind,sort_order,system_name,site_name,
                    name,alias_list_id,decoder_type,address_domain_code,primary_frequency_hz,config_json)
                SELECT new_channels.id,new_channels.configuration_id,template.channel_kind,template.sort_order+100,
                    template.system_name,template.site_name,template.name,template.alias_list_id,template.decoder_type,
                    template.address_domain_code,template.primary_frequency_hz,
                    json_remove(template.config_json,'$.p25SiteIdentity')
                FROM new_channels CROSS JOIN configuration_channel template
                WHERE template.id=(SELECT id FROM configuration_channel
                    WHERE decoder_type='P25_PHASE1' AND channel_kind='TRUNKED' ORDER BY id LIMIT 1)
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system(id,system_key,protocol_code,address_domain_code,p25_wacn,p25_system_id,
                    first_seen_ms,last_seen_ms)
                VALUES(5000000000,'p25:bee00:3a9',1,0,0xBEE00,0x3A9,1000,9000),
                      (5000000001,'p25:bee01:3a9',1,0,0xBEE01,0x3A9,1000,9000)
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system(id,system_key,protocol_code,address_domain_code,dmr_model_code,dmr_network_id,
                    first_seen_ms,last_seen_ms)
                VALUES(5000000002,'dmr:tier3:tiny:1',3,0,1,1,1000,9000)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms,
                    radio_system_id,radio_system_assigned_at_ms)
                VALUES(5000000000,'00000000-0000-0000-0000-000000000001',1000,9000,5000000000,1000),
                      (5000000001,'00000000-0000-0000-0000-000000000002',1000,9000,5000000000,1000),
                      (5000000002,'00000000-0000-0000-0000-000000000003',1000,9000,5000000001,1000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_subscriber_identity(id,home_wacn,home_system_id,subscriber_id)
                VALUES(5000000000,0xBEE00,0x3A9,10900077),
                      (5000000001,0xABCDE,0x123,12345)
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,home_wacn,home_system_id,
                    identity_id,first_seen_ms,last_seen_ms,logical_call_count,source_logical_call_count,
                    target_logical_call_count,encrypted_logical_call_count,recorded_output_count,streamed_output_count,
                    register_count,last_encryption_algorithm_id,last_encryption_key_id,last_talker_alias,
                    last_talker_alias_seen_ms,p25_subscriber_identity_id)
                VALUES(5000000010,5000000000,2,-1,-1,10900077,1000,8000,3,2,1,1,2,3,2,132,91,'Earlier name',6000,NULL),
                      (5000000011,5000000000,2,0xBEE00,0x3A9,10900077,2000,9000,1,1,0,1,1,2,1,133,92,'Current name',7000,5000000000)
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,home_wacn,home_system_id,
                    identity_id,first_seen_ms,last_seen_ms,p25_subscriber_identity_id)
                VALUES(5000000012,5000000000,2,-1,-1,10900078,1000,9000,NULL),
                      (5000000013,5000000000,2,0xABCDE,0x123,12345,1000,9000,5000000001),
                      (5000000014,5000000000,2,-1,-1,10900079,1000,9000,NULL),
                      (5000000015,5000000000,2,0xBEE00,0x3A9,10900079,1000,9000,NULL),
                      (5000000020,5000000000,1,0xBEE00,0x3A9,10003,1000,9000,NULL),
                      (5000000030,5000000001,2,-1,-1,10900077,1000,9000,NULL),
                      (5000000040,5000000002,2,-1,-1,10900077,1000,9000,NULL)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_wuid_assignment_observation_summary(radio_system_id,working_id,p25_subscriber_identity_id,
                    first_observed_ms,last_observed_ms,last_registration_ms,registration_count,last_evidence_code,last_channel_id)
                VALUES(5000000000,10900079,5000000001,1000,9000,9000,1,1,5000000000)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,action_code,
                    source_identity_summary_id,source_observed_local_id,target_identity_summary_id,target_observed_local_id,
                    target_kind_code)
                VALUES(5000000000,5000000000,5000000000,3000,1,5000000010,10900077,5000000020,10003,1),
                      (5000000001,5000000000,5000000000,4000,1,5000000011,10900077,5000000010,10900077,2),
                      (5000000002,5000000000,5000000000,5000,20,5000000014,10900079,5000000020,10003,1)
                """);
            statement.executeUpdate("""
                INSERT INTO activity_event_identity_member(event_id,radio_system_id,identity_summary_id,
                    identity_kind_code,observed_local_id,channel_id)
                VALUES(5000000000,5000000000,5000000020,1,10003,5000000000),
                      (5000000001,5000000000,5000000020,1,10003,5000000000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_logical_call_bucket(radio_system_id,bucket_start_ms,logical_call_count,
                    encrypted_logical_call_count,recorded_output_count,streamed_output_count)
                VALUES(5000000000,0,4,2,3,5)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_logical_call_identity_bucket(radio_system_id,bucket_start_ms,identity_role_code,
                    identity_kind_code,identity_summary_id,logical_call_count,encrypted_logical_call_count,
                    recorded_output_count,streamed_output_count)
                VALUES(5000000000,0,2,2,5000000010,3,1,2,3),
                      (5000000000,0,2,2,5000000011,1,1,1,2)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_learned_site(learned_site_id,radio_system_id,rfss,site,first_seen_ms,last_seen_ms)
                VALUES(5000000000,5000000000,1,2,1000,9000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_call_bucket(radio_system_id,learned_site_id,bucket_start_ms,
                    observed_call_count,encrypted_observed_call_count)
                VALUES(5000000000,5000000000,0,4,2)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_call_identity_bucket(radio_system_id,learned_site_id,channel_id,bucket_start_ms,
                    identity_role_code,identity_kind_code,identity_summary_id,observed_local_id,observed_working_id,
                    last_observed_at_ms,observed_call_count,encrypted_observed_call_count)
                VALUES(5000000000,5000000000,5000000000,0,2,2,5000000010,10900077,NULL,8000,3,1),
                      (5000000000,5000000000,5000000000,0,2,2,5000000011,10900077,10900077,9000,1,1)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_group_summary(radio_system_id,radio_identity_id,group_identity_id,group_kind_code,
                    first_seen_ms,last_seen_ms,logical_call_count,encrypted_logical_call_count,
                    recorded_output_count,streamed_output_count,register_count,last_encryption_algorithm_id,last_encryption_key_id)
                VALUES(5000000000,5000000010,5000000020,1,1000,8000,3,1,2,3,2,132,91),
                      (5000000000,5000000011,5000000020,1,2000,9000,1,1,1,2,1,133,92)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_affiliation(radio_system_id,radio_identity_id,talkgroup_identity_id,
                    channel_id,radio_observed_local_id,talkgroup_observed_local_id,confirmed_at_ms)
                VALUES(5000000000,5000000010,5000000020,5000000000,10900077,10003,8000),
                      (5000000000,5000000011,5000000020,5000000001,10900077,10003,7000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_channel_presence(radio_system_id,radio_identity_id,channel_id,
                    observed_local_id,evidence_code,confirmed_at_ms)
                VALUES(5000000000,5000000010,5000000000,10900077,1,8000),
                      (5000000000,5000000011,5000000001,10900077,2,7000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_channel_presence_clear(radio_system_id,radio_identity_id,channel_id,
                    observed_local_id,cleared_at_ms)
                VALUES(5000000000,5000000010,5000000001,10900077,8500),
                      (5000000000,5000000011,5000000001,10900077,7500)
                """);
            statement.executeUpdate("UPDATE sqlite_sequence SET seq=seq+10000");
        }
        return database;
    }
}
