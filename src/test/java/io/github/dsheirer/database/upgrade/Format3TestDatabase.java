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

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** Exact populated global-format 3 fixture with frozen predecessor-only DDL. */
public final class Format3TestDatabase
{
    private Format3TestDatabase()
    {
    }

    public static Path create(Path database) throws Exception
    {
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=OFF");
            connection.setAutoCommit(false);

            try
            {
                Format1TestDatabase.replaceLogicalCallStatisticsSchema(statement);
                Format3SchemaSql.replaceTrunkedIdentitySchema(statement);
                Format1TestDatabase.replaceLogicalCallStatisticsMetadata(statement);
                statement.executeUpdate(
                    "UPDATE database_metadata SET value='27' WHERE key='p25_activity_schema_version'");
                statement.executeUpdate("""
                    UPDATE database_metadata SET value='3' WHERE key='database_format_version'
                    """);
                statement.executeUpdate("""
                    UPDATE database_metadata SET value='100'
                    WHERE key='p25_call_output_metrics_started_at_ms'
                    """);
                statement.executeUpdate("""
                    UPDATE database_metadata SET value='200'
                    WHERE key='all_mode_call_output_metrics_started_at_ms'
                    """);
                populate(statement);
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
                statement.execute("PRAGMA foreign_keys=ON");
            }

            DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);

            if(detected.version() != 3 || !detected.markerPresent() ||
                !DatabaseFormatCatalog.requireVersion(3).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 3 fixture fingerprint mismatch: " + fingerprint);
            }
        }

        return database;
    }

    private static void populate(Statement statement) throws Exception
    {
        statement.executeUpdate("""
            INSERT OR REPLACE INTO application_settings(key, settings_json, updated_at_ms)
            VALUES ('format-3-preserve-sentinel', '{"preserved":true}', 1700000000000)
            """);
        statement.executeUpdate("""
            INSERT INTO p25_system(system_key, wacn, system_id, first_seen_ms, last_seen_ms)
            VALUES (700, 781824, 101, 1700000000000, 1700000005000)
            """);
        statement.executeUpdate("""
            INSERT INTO receiver_context(
                id, context_key, guid, kind_code, protocol_code, channel_name, alias_list_name, decoder,
                first_seen_ms, last_seen_ms, system_key, nac, rfss, site, primary_frequency_hz,
                current_control_hz
            ) VALUES
                (700, 'format-3-site', 'format-3-guid', 1, 1, 'Format 3 Site', 'default p25',
                 'P25_PHASE1', 1700000000000, 1700000005000, 700, 293, 1, 2, 851012500, 851012500),
                (701, 'format-3-conventional', NULL, 2, 1, 'Format 3 Conventional', 'Default P25',
                 'P25_CONVENTIONAL', 1700000000000, 1700000005000, NULL, NULL, NULL, NULL,
                 155550000, NULL),
                (702, 'format-3-unmatched', NULL, 2, 1, 'Format 3 Unmatched', 'Missing Alias List',
                 'P25_CONVENTIONAL', 1700000000000, 1700000005000, NULL, NULL, NULL, NULL,
                 155560000, NULL),
                (703, 'format-3-dmr', NULL, 3, 3, 'Format 3 DMR', 'Default DMR',
                 'DMR', 1700000000000, 1700000005000, NULL, NULL, NULL, NULL, 460012500, NULL)
            """);
        statement.executeUpdate("""
            INSERT INTO p25_site_frequency_summary(
                context_id, frequency_hz, timeslot, first_seen_ms, last_seen_ms, call_count,
                grant_count, encrypted_count, last_source_radio_id, last_target_id
            ) VALUES (700, 851012500, -1, 1700000000000, 1700000005000, 4, 3, 1, 12001, 3101)
            """);
        statement.executeUpdate("""
            INSERT INTO p25_site_talkgroup_bucket(
                context_id, talkgroup_id, bucket_start_ms, call_count, grant_count, encrypted_count,
                recorded_count, streamed_count
            ) VALUES (700, 3101, 1699999200000, 4, 3, 1, 2, 1)
            """);
        statement.executeUpdate("""
            INSERT INTO p25_site_activity_bucket(
                context_id, bucket_start_ms, call_count, grant_count, page_count, encrypted_count,
                recorded_count, streamed_count
            ) VALUES (700, 1699999200000, 4, 3, 2, 1, 2, 1)
            """);
        statement.executeUpdate("""
            INSERT INTO call_identity_bucket(
                context_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id,
                call_count, encrypted_count, recorded_count, streamed_count
            ) VALUES
                (700, 1699999200000, 1, 1, 3101, 4, 1, 2, 1),
                (701, 1699999200000, 1, 1, 4101, 5, 2, 3, 1)
            """);
        statement.executeUpdate("""
            INSERT INTO conventional_activity_summary(
                context_id, frequency_hz, timeslot, first_seen_ms, last_seen_ms, call_count,
                encrypted_count, recorded_count, streamed_count
            ) VALUES (701, 155550000, -1, 1700000000000, 1700000005000, 5, 2, 3, 1)
            """);
        statement.executeUpdate("""
            INSERT INTO conventional_activity_bucket(
                context_id, frequency_hz, timeslot, bucket_start_ms, call_count,
                encrypted_count, recorded_count, streamed_count
            ) VALUES (701, 155550000, -1, 1699999200000, 5, 2, 3, 1)
            """);
        statement.executeUpdate("""
            INSERT INTO dmr_conventional_talkgroup_summary(
                context_id, frequency_hz, timeslot, talkgroup_id, first_seen_ms, last_seen_ms,
                call_count, encrypted_count, last_source_radio_id
            ) VALUES (703, 460012500, 1, 5101, 1700000000000, 1700000005000, 6, 1, 22001)
            """);
        statement.executeUpdate("""
            INSERT INTO p25_control_channel_quality(
                guid, frequency_hz, bucket_start_ms, observed_at_ms, decode_health_pct,
                valid_frames, invalid_frames
            ) VALUES ('format-3-guid', 851012500, 1699999200000, 1700000005000, 97.5, 195, 5)
            """);
        statement.executeUpdate("""
            INSERT INTO trunked_site_snapshot(
                guid, snapshot_hash, protocol_code, variant_code, identity_domain_code,
                first_seen_ms, last_seen_ms, observation_count
            ) VALUES ('format-3-trunked-site', 'fixture', 3, 0, 0,
                      1700000000000, 1700000005000, 2)
            """);
        statement.executeUpdate("""
            INSERT INTO trunked_identity_scope(
                scope_id, scope_token, protocol_code, scope_kind_code, identity_domain_code,
                p25_system_key, first_seen_ms, last_seen_ms
            ) VALUES (700, 'p25:781824:101', 1, 1, 0, 700, 1700000000000, 1700000005000)
            """);
        statement.executeUpdate("""
            INSERT INTO trunked_identity_scope_context(context_id, scope_id, first_seen_ms, last_seen_ms)
            VALUES (700, 700, 1700000000000, 1700000005000)
            """);
        statement.executeUpdate("""
            INSERT INTO trunked_identity_summary(
                scope_id, identity_kind_code, identity_id, p25_identity_state_code,
                first_seen_ms, last_seen_ms, call_count, target_call_count, encrypted_count,
                recorded_count, streamed_count
            ) VALUES (700, 1, 3101, 1, 1700000000000, 1700000005000, 4, 4, 1, 2, 1)
            """);
    }
}
