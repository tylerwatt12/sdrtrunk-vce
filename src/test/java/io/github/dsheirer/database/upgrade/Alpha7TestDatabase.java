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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** Exact empty database shape emitted by published v0.6.2-alpha-7, for migrator boundary tests only. */
final class Alpha7TestDatabase
{
    private static final String SCHEMA = """
        CREATE TABLE database_metadata (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL,
            updated_at_ms INTEGER NOT NULL
        );
        CREATE TABLE alias (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            sort_order INTEGER NOT NULL,
            name TEXT,
            description TEXT,
            alias_list_name TEXT,
            group_name TEXT,
            color INTEGER NOT NULL DEFAULT 0,
            icon_name TEXT,
            stream_as_talkgroup INTEGER,
            record_enabled INTEGER NOT NULL DEFAULT 0,
            non_recordable INTEGER NOT NULL DEFAULT 0,
            priority INTEGER
        );
        CREATE TABLE alias_broadcast_channel (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            alias_id INTEGER NOT NULL REFERENCES alias(id) ON DELETE CASCADE,
            sort_order INTEGER NOT NULL,
            channel_name TEXT NOT NULL
        );
        CREATE TABLE alias_talkgroup (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            alias_id INTEGER NOT NULL REFERENCES alias(id) ON DELETE CASCADE,
            sort_order INTEGER NOT NULL,
            protocol TEXT NOT NULL,
            value INTEGER,
            min_value INTEGER,
            max_value INTEGER,
            wacn INTEGER,
            system_id INTEGER,
            fully_qualified INTEGER NOT NULL DEFAULT 0,
            ranged INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE alias_radio (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            alias_id INTEGER NOT NULL REFERENCES alias(id) ON DELETE CASCADE,
            sort_order INTEGER NOT NULL,
            protocol TEXT NOT NULL,
            value INTEGER,
            min_value INTEGER,
            max_value INTEGER,
            wacn INTEGER,
            system_id INTEGER,
            fully_qualified INTEGER NOT NULL DEFAULT 0,
            ranged INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE alias_status (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            alias_id INTEGER NOT NULL REFERENCES alias(id) ON DELETE CASCADE,
            sort_order INTEGER NOT NULL,
            status_kind TEXT NOT NULL,
            status INTEGER NOT NULL
        );
        CREATE TABLE alias_tone_sequence (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            alias_id INTEGER NOT NULL REFERENCES alias(id) ON DELETE CASCADE,
            sort_order INTEGER NOT NULL,
            tone_sequence TEXT
        );
        CREATE TABLE alias_text_identifier (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            alias_id INTEGER NOT NULL REFERENCES alias(id) ON DELETE CASCADE,
            sort_order INTEGER NOT NULL,
            identifier_type TEXT NOT NULL,
            text_value TEXT,
            text_value_2 TEXT,
            numeric_value INTEGER
        );
        CREATE TABLE alias_action (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            alias_id INTEGER NOT NULL REFERENCES alias(id) ON DELETE CASCADE,
            sort_order INTEGER NOT NULL,
            type TEXT NOT NULL,
            interval TEXT,
            period INTEGER,
            path TEXT,
            script TEXT
        );
        CREATE TABLE configuration_channel (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            sort_order INTEGER NOT NULL,
            system_name TEXT,
            site_name TEXT,
            name TEXT,
            alias_list_name TEXT,
            radres_guid TEXT,
            auto_start INTEGER NOT NULL DEFAULT 0,
            auto_start_order INTEGER,
            decoder_type TEXT,
            source_type TEXT,
            primary_frequency_hz INTEGER,
            frequency_count INTEGER NOT NULL DEFAULT 0,
            recording_enabled INTEGER NOT NULL DEFAULT 0,
            event_logging_enabled INTEGER NOT NULL DEFAULT 0,
            config_json TEXT NOT NULL
        );
        CREATE TABLE configuration_channel_map (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            sort_order INTEGER NOT NULL,
            name TEXT,
            config_json TEXT NOT NULL
        );
        CREATE TABLE configuration_broadcast_stream (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            sort_order INTEGER NOT NULL,
            name TEXT,
            server_type TEXT,
            enabled INTEGER NOT NULL DEFAULT 0,
            host TEXT,
            port INTEGER,
            delay_ms INTEGER,
            maximum_recording_age_ms INTEGER,
            config_json TEXT NOT NULL
        );
        CREATE TABLE application_settings (
            key TEXT PRIMARY KEY,
            settings_json TEXT NOT NULL,
            updated_at_ms INTEGER NOT NULL
        );
        CREATE TABLE application_icons (
            key TEXT PRIMARY KEY,
            icons_json TEXT NOT NULL,
            updated_at_ms INTEGER NOT NULL
        );
        CREATE INDEX idx_alias_sort ON alias(sort_order, id);
        CREATE INDEX idx_alias_list_name ON alias(alias_list_name);
        CREATE INDEX idx_alias_broadcast_channel_alias ON alias_broadcast_channel(alias_id, sort_order, id);
        CREATE INDEX idx_alias_broadcast_channel_name ON alias_broadcast_channel(channel_name);
        CREATE INDEX idx_alias_talkgroup_alias ON alias_talkgroup(alias_id, sort_order, id);
        CREATE INDEX idx_alias_talkgroup_value
        ON alias_talkgroup(protocol, value, wacn, system_id)
        ;
        CREATE INDEX idx_alias_talkgroup_range
        ON alias_talkgroup(protocol, min_value, max_value)
        ;
        CREATE INDEX idx_alias_radio_alias ON alias_radio(alias_id, sort_order, id);
        CREATE INDEX idx_alias_radio_value
        ON alias_radio(protocol, value, wacn, system_id)
        ;
        CREATE INDEX idx_alias_radio_range
        ON alias_radio(protocol, min_value, max_value)
        ;
        CREATE INDEX idx_alias_status_alias ON alias_status(alias_id, sort_order, id);
        CREATE INDEX idx_alias_status_lookup ON alias_status(status_kind, status);
        CREATE INDEX idx_alias_tone_sequence_alias ON alias_tone_sequence(alias_id, sort_order, id);
        CREATE INDEX idx_alias_text_identifier_alias ON alias_text_identifier(alias_id, sort_order, id);
        CREATE INDEX idx_alias_text_identifier_type ON alias_text_identifier(identifier_type);
        CREATE INDEX idx_alias_action_alias ON alias_action(alias_id, sort_order, id);
        CREATE INDEX idx_configuration_channel_sort ON configuration_channel(sort_order, id);
        CREATE INDEX idx_configuration_channel_alias_list ON configuration_channel(alias_list_name);
        CREATE INDEX idx_configuration_channel_decoder ON configuration_channel(decoder_type);
        CREATE INDEX idx_configuration_channel_frequency ON configuration_channel(primary_frequency_hz);
        CREATE INDEX idx_configuration_channel_map_sort ON configuration_channel_map(sort_order, id);
        CREATE INDEX idx_configuration_broadcast_sort ON configuration_broadcast_stream(sort_order, id);
        CREATE INDEX idx_configuration_broadcast_type ON configuration_broadcast_stream(server_type, enabled);
        CREATE TABLE p25_system (
            system_key INTEGER PRIMARY KEY,
            wacn INTEGER NOT NULL,
            system_id INTEGER NOT NULL,
            first_seen_ms INTEGER NOT NULL,
            last_seen_ms INTEGER NOT NULL,
            UNIQUE(wacn, system_id)
        );
        CREATE TABLE receiver_context (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            context_key TEXT NOT NULL UNIQUE,
            guid TEXT,
            kind_code INTEGER NOT NULL,
            protocol_code INTEGER,
            channel_name TEXT,
            alias_list_name TEXT,
            decoder TEXT,
            first_seen_ms INTEGER NOT NULL,
            last_seen_ms INTEGER NOT NULL,
            system_key INTEGER,
            nac INTEGER,
            rfss INTEGER,
            site INTEGER,
            primary_frequency_hz INTEGER,
            current_control_hz INTEGER
        );
        CREATE TABLE p25_activity_event (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            context_id INTEGER NOT NULL,
            observed_at_ms INTEGER NOT NULL,
            action_code INTEGER NOT NULL,
            event_type_code INTEGER,
            source_radio_id INTEGER,
            target_id INTEGER,
            target_kind_code INTEGER,
            frequency_hz INTEGER,
            lcn_band INTEGER,
            lcn_number INTEGER,
            timeslot INTEGER,
            encrypted INTEGER NOT NULL DEFAULT 0,
            encryption_algorithm_id INTEGER,
            encryption_key_id INTEGER
        );
        CREATE TABLE p25_talkgroup_summary (
            system_key INTEGER NOT NULL,
            talkgroup_id INTEGER NOT NULL,
            target_kind_code INTEGER,
            first_seen_ms INTEGER NOT NULL,
            last_seen_ms INTEGER NOT NULL,
            acknowledge_count INTEGER NOT NULL DEFAULT 0,
                            active_count INTEGER NOT NULL DEFAULT 0,
                            busy_count INTEGER NOT NULL DEFAULT 0,
                            call_count INTEGER NOT NULL DEFAULT 0,
                            check_count INTEGER NOT NULL DEFAULT 0,
                            check_ack_count INTEGER NOT NULL DEFAULT 0,
                            continue_count INTEGER NOT NULL DEFAULT 0,
                            data_count INTEGER NOT NULL DEFAULT 0,
                            denial_count INTEGER NOT NULL DEFAULT 0,
                            emergency_count INTEGER NOT NULL DEFAULT 0,
                            gps_count INTEGER NOT NULL DEFAULT 0,
                            grant_count INTEGER NOT NULL DEFAULT 0,
                            join_count INTEGER NOT NULL DEFAULT 0,
                            logout_count INTEGER NOT NULL DEFAULT 0,
                            page_count INTEGER NOT NULL DEFAULT 0,
                            patch_count INTEGER NOT NULL DEFAULT 0,
                            patch_cancel_count INTEGER NOT NULL DEFAULT 0,
                            patch_create_count INTEGER NOT NULL DEFAULT 0,
                            queued_count INTEGER NOT NULL DEFAULT 0,
                            register_count INTEGER NOT NULL DEFAULT 0,
                            request_count INTEGER NOT NULL DEFAULT 0,
                            status_count INTEGER NOT NULL DEFAULT 0,
                            unknown_count INTEGER NOT NULL DEFAULT 0,
            encrypted_count INTEGER NOT NULL DEFAULT 0,
            recorded_count INTEGER NOT NULL DEFAULT 0,
            streamed_count INTEGER NOT NULL DEFAULT 0,
            last_source_radio_id INTEGER,
            last_encryption_algorithm_id INTEGER,
            last_encryption_key_id INTEGER,
            PRIMARY KEY(system_key, talkgroup_id)
        );
        CREATE TABLE p25_radio_summary (
            system_key INTEGER NOT NULL,
            radio_id INTEGER NOT NULL,
            first_seen_ms INTEGER NOT NULL,
            last_seen_ms INTEGER NOT NULL,
            acknowledge_count INTEGER NOT NULL DEFAULT 0,
                            active_count INTEGER NOT NULL DEFAULT 0,
                            busy_count INTEGER NOT NULL DEFAULT 0,
                            call_count INTEGER NOT NULL DEFAULT 0,
                            check_count INTEGER NOT NULL DEFAULT 0,
                            check_ack_count INTEGER NOT NULL DEFAULT 0,
                            continue_count INTEGER NOT NULL DEFAULT 0,
                            data_count INTEGER NOT NULL DEFAULT 0,
                            denial_count INTEGER NOT NULL DEFAULT 0,
                            emergency_count INTEGER NOT NULL DEFAULT 0,
                            gps_count INTEGER NOT NULL DEFAULT 0,
                            grant_count INTEGER NOT NULL DEFAULT 0,
                            join_count INTEGER NOT NULL DEFAULT 0,
                            logout_count INTEGER NOT NULL DEFAULT 0,
                            page_count INTEGER NOT NULL DEFAULT 0,
                            patch_count INTEGER NOT NULL DEFAULT 0,
                            patch_cancel_count INTEGER NOT NULL DEFAULT 0,
                            patch_create_count INTEGER NOT NULL DEFAULT 0,
                            queued_count INTEGER NOT NULL DEFAULT 0,
                            register_count INTEGER NOT NULL DEFAULT 0,
                            request_count INTEGER NOT NULL DEFAULT 0,
                            status_count INTEGER NOT NULL DEFAULT 0,
                            unknown_count INTEGER NOT NULL DEFAULT 0,
            encrypted_count INTEGER NOT NULL DEFAULT 0,
            last_talkgroup_id INTEGER,
            last_talker_alias TEXT,
            last_talker_alias_seen_ms INTEGER,
            last_encryption_algorithm_id INTEGER,
            last_encryption_key_id INTEGER,
            PRIMARY KEY(system_key, radio_id)
        );
        CREATE TABLE p25_radio_affiliation (
            system_key INTEGER NOT NULL,
            radio_id INTEGER NOT NULL,
            talkgroup_id INTEGER NOT NULL,
            updated_at_ms INTEGER NOT NULL,
            PRIMARY KEY(system_key, radio_id)
        ) WITHOUT ROWID
        ;
        CREATE TABLE p25_radio_talkgroup_summary (
            system_key INTEGER NOT NULL,
            radio_id INTEGER NOT NULL,
            talkgroup_id INTEGER NOT NULL,
            target_kind_code INTEGER,
            first_seen_ms INTEGER NOT NULL,
            last_seen_ms INTEGER NOT NULL,
            acknowledge_count INTEGER NOT NULL DEFAULT 0,
                            active_count INTEGER NOT NULL DEFAULT 0,
                            busy_count INTEGER NOT NULL DEFAULT 0,
                            call_count INTEGER NOT NULL DEFAULT 0,
                            check_count INTEGER NOT NULL DEFAULT 0,
                            check_ack_count INTEGER NOT NULL DEFAULT 0,
                            continue_count INTEGER NOT NULL DEFAULT 0,
                            data_count INTEGER NOT NULL DEFAULT 0,
                            denial_count INTEGER NOT NULL DEFAULT 0,
                            emergency_count INTEGER NOT NULL DEFAULT 0,
                            gps_count INTEGER NOT NULL DEFAULT 0,
                            grant_count INTEGER NOT NULL DEFAULT 0,
                            join_count INTEGER NOT NULL DEFAULT 0,
                            logout_count INTEGER NOT NULL DEFAULT 0,
                            page_count INTEGER NOT NULL DEFAULT 0,
                            patch_count INTEGER NOT NULL DEFAULT 0,
                            patch_cancel_count INTEGER NOT NULL DEFAULT 0,
                            patch_create_count INTEGER NOT NULL DEFAULT 0,
                            queued_count INTEGER NOT NULL DEFAULT 0,
                            register_count INTEGER NOT NULL DEFAULT 0,
                            request_count INTEGER NOT NULL DEFAULT 0,
                            status_count INTEGER NOT NULL DEFAULT 0,
                            unknown_count INTEGER NOT NULL DEFAULT 0,
            encrypted_count INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(system_key, radio_id, talkgroup_id)
        ) WITHOUT ROWID
        ;
        CREATE TABLE p25_site_frequency_summary (
            context_id INTEGER NOT NULL,
            frequency_hz INTEGER NOT NULL,
            timeslot INTEGER NOT NULL DEFAULT -1,
            lcn_band INTEGER,
            lcn_number INTEGER,
            first_seen_ms INTEGER NOT NULL,
            last_seen_ms INTEGER NOT NULL,
            acknowledge_count INTEGER NOT NULL DEFAULT 0,
                            active_count INTEGER NOT NULL DEFAULT 0,
                            busy_count INTEGER NOT NULL DEFAULT 0,
                            call_count INTEGER NOT NULL DEFAULT 0,
                            check_count INTEGER NOT NULL DEFAULT 0,
                            check_ack_count INTEGER NOT NULL DEFAULT 0,
                            continue_count INTEGER NOT NULL DEFAULT 0,
                            data_count INTEGER NOT NULL DEFAULT 0,
                            denial_count INTEGER NOT NULL DEFAULT 0,
                            emergency_count INTEGER NOT NULL DEFAULT 0,
                            gps_count INTEGER NOT NULL DEFAULT 0,
                            grant_count INTEGER NOT NULL DEFAULT 0,
                            join_count INTEGER NOT NULL DEFAULT 0,
                            logout_count INTEGER NOT NULL DEFAULT 0,
                            page_count INTEGER NOT NULL DEFAULT 0,
                            patch_count INTEGER NOT NULL DEFAULT 0,
                            patch_cancel_count INTEGER NOT NULL DEFAULT 0,
                            patch_create_count INTEGER NOT NULL DEFAULT 0,
                            queued_count INTEGER NOT NULL DEFAULT 0,
                            register_count INTEGER NOT NULL DEFAULT 0,
                            request_count INTEGER NOT NULL DEFAULT 0,
                            status_count INTEGER NOT NULL DEFAULT 0,
                            unknown_count INTEGER NOT NULL DEFAULT 0,
            encrypted_count INTEGER NOT NULL DEFAULT 0,
            last_source_radio_id INTEGER,
            last_target_id INTEGER,
            last_encryption_algorithm_id INTEGER,
            last_encryption_key_id INTEGER,
            PRIMARY KEY(context_id, frequency_hz, timeslot)
        );
        CREATE TABLE p25_site_talkgroup_bucket (
            context_id INTEGER NOT NULL,
            talkgroup_id INTEGER NOT NULL,
            bucket_start_ms INTEGER NOT NULL,
            acknowledge_count INTEGER NOT NULL DEFAULT 0,
                            active_count INTEGER NOT NULL DEFAULT 0,
                            busy_count INTEGER NOT NULL DEFAULT 0,
                            call_count INTEGER NOT NULL DEFAULT 0,
                            check_count INTEGER NOT NULL DEFAULT 0,
                            check_ack_count INTEGER NOT NULL DEFAULT 0,
                            continue_count INTEGER NOT NULL DEFAULT 0,
                            data_count INTEGER NOT NULL DEFAULT 0,
                            denial_count INTEGER NOT NULL DEFAULT 0,
                            emergency_count INTEGER NOT NULL DEFAULT 0,
                            gps_count INTEGER NOT NULL DEFAULT 0,
                            grant_count INTEGER NOT NULL DEFAULT 0,
                            join_count INTEGER NOT NULL DEFAULT 0,
                            logout_count INTEGER NOT NULL DEFAULT 0,
                            page_count INTEGER NOT NULL DEFAULT 0,
                            patch_count INTEGER NOT NULL DEFAULT 0,
                            patch_cancel_count INTEGER NOT NULL DEFAULT 0,
                            patch_create_count INTEGER NOT NULL DEFAULT 0,
                            queued_count INTEGER NOT NULL DEFAULT 0,
                            register_count INTEGER NOT NULL DEFAULT 0,
                            request_count INTEGER NOT NULL DEFAULT 0,
                            status_count INTEGER NOT NULL DEFAULT 0,
                            unknown_count INTEGER NOT NULL DEFAULT 0,
            encrypted_count INTEGER NOT NULL DEFAULT 0,
            recorded_count INTEGER NOT NULL DEFAULT 0,
            streamed_count INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(context_id, talkgroup_id, bucket_start_ms)
        );
        CREATE TABLE p25_site_activity_bucket (
            context_id INTEGER NOT NULL,
            bucket_start_ms INTEGER NOT NULL,
            acknowledge_count INTEGER NOT NULL DEFAULT 0,
                            active_count INTEGER NOT NULL DEFAULT 0,
                            busy_count INTEGER NOT NULL DEFAULT 0,
                            call_count INTEGER NOT NULL DEFAULT 0,
                            check_count INTEGER NOT NULL DEFAULT 0,
                            check_ack_count INTEGER NOT NULL DEFAULT 0,
                            continue_count INTEGER NOT NULL DEFAULT 0,
                            data_count INTEGER NOT NULL DEFAULT 0,
                            denial_count INTEGER NOT NULL DEFAULT 0,
                            emergency_count INTEGER NOT NULL DEFAULT 0,
                            gps_count INTEGER NOT NULL DEFAULT 0,
                            grant_count INTEGER NOT NULL DEFAULT 0,
                            join_count INTEGER NOT NULL DEFAULT 0,
                            logout_count INTEGER NOT NULL DEFAULT 0,
                            page_count INTEGER NOT NULL DEFAULT 0,
                            patch_count INTEGER NOT NULL DEFAULT 0,
                            patch_cancel_count INTEGER NOT NULL DEFAULT 0,
                            patch_create_count INTEGER NOT NULL DEFAULT 0,
                            queued_count INTEGER NOT NULL DEFAULT 0,
                            register_count INTEGER NOT NULL DEFAULT 0,
                            request_count INTEGER NOT NULL DEFAULT 0,
                            status_count INTEGER NOT NULL DEFAULT 0,
                            unknown_count INTEGER NOT NULL DEFAULT 0,
            encrypted_count INTEGER NOT NULL DEFAULT 0,
            recorded_count INTEGER NOT NULL DEFAULT 0,
            streamed_count INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(context_id, bucket_start_ms)
        );
        CREATE TABLE conventional_activity_summary (
            context_id INTEGER NOT NULL,
            frequency_hz INTEGER NOT NULL,
            timeslot INTEGER NOT NULL DEFAULT -1,
            first_seen_ms INTEGER NOT NULL,
            last_seen_ms INTEGER NOT NULL,
            acknowledge_count INTEGER NOT NULL DEFAULT 0,
                            active_count INTEGER NOT NULL DEFAULT 0,
                            busy_count INTEGER NOT NULL DEFAULT 0,
                            call_count INTEGER NOT NULL DEFAULT 0,
                            check_count INTEGER NOT NULL DEFAULT 0,
                            check_ack_count INTEGER NOT NULL DEFAULT 0,
                            continue_count INTEGER NOT NULL DEFAULT 0,
                            data_count INTEGER NOT NULL DEFAULT 0,
                            denial_count INTEGER NOT NULL DEFAULT 0,
                            emergency_count INTEGER NOT NULL DEFAULT 0,
                            gps_count INTEGER NOT NULL DEFAULT 0,
                            grant_count INTEGER NOT NULL DEFAULT 0,
                            join_count INTEGER NOT NULL DEFAULT 0,
                            logout_count INTEGER NOT NULL DEFAULT 0,
                            page_count INTEGER NOT NULL DEFAULT 0,
                            patch_count INTEGER NOT NULL DEFAULT 0,
                            patch_cancel_count INTEGER NOT NULL DEFAULT 0,
                            patch_create_count INTEGER NOT NULL DEFAULT 0,
                            queued_count INTEGER NOT NULL DEFAULT 0,
                            register_count INTEGER NOT NULL DEFAULT 0,
                            request_count INTEGER NOT NULL DEFAULT 0,
                            status_count INTEGER NOT NULL DEFAULT 0,
                            unknown_count INTEGER NOT NULL DEFAULT 0,
            last_event_type_code INTEGER,
            PRIMARY KEY(context_id, frequency_hz, timeslot)
        );
        CREATE TABLE conventional_activity_bucket (
            context_id INTEGER NOT NULL,
            frequency_hz INTEGER NOT NULL,
            timeslot INTEGER NOT NULL DEFAULT -1,
            bucket_start_ms INTEGER NOT NULL,
            acknowledge_count INTEGER NOT NULL DEFAULT 0,
                            active_count INTEGER NOT NULL DEFAULT 0,
                            busy_count INTEGER NOT NULL DEFAULT 0,
                            call_count INTEGER NOT NULL DEFAULT 0,
                            check_count INTEGER NOT NULL DEFAULT 0,
                            check_ack_count INTEGER NOT NULL DEFAULT 0,
                            continue_count INTEGER NOT NULL DEFAULT 0,
                            data_count INTEGER NOT NULL DEFAULT 0,
                            denial_count INTEGER NOT NULL DEFAULT 0,
                            emergency_count INTEGER NOT NULL DEFAULT 0,
                            gps_count INTEGER NOT NULL DEFAULT 0,
                            grant_count INTEGER NOT NULL DEFAULT 0,
                            join_count INTEGER NOT NULL DEFAULT 0,
                            logout_count INTEGER NOT NULL DEFAULT 0,
                            page_count INTEGER NOT NULL DEFAULT 0,
                            patch_count INTEGER NOT NULL DEFAULT 0,
                            patch_cancel_count INTEGER NOT NULL DEFAULT 0,
                            patch_create_count INTEGER NOT NULL DEFAULT 0,
                            queued_count INTEGER NOT NULL DEFAULT 0,
                            register_count INTEGER NOT NULL DEFAULT 0,
                            request_count INTEGER NOT NULL DEFAULT 0,
                            status_count INTEGER NOT NULL DEFAULT 0,
                            unknown_count INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(context_id, frequency_hz, timeslot, bucket_start_ms)
        );
        CREATE TABLE p25_site_snapshot (
            guid TEXT PRIMARY KEY,
            snapshot_hash TEXT,
            first_seen_ms INTEGER NOT NULL,
            last_seen_ms INTEGER NOT NULL,
            observation_count INTEGER NOT NULL DEFAULT 1,
            protocol TEXT,
            channel_name TEXT,
            alias_list_name TEXT,
            decoder TEXT,
            system_key INTEGER,
            nac INTEGER,
            rfss INTEGER,
            site INTEGER,
            lra INTEGER,
            mfid INTEGER,
            broadcast_clock_ms INTEGER,
            micro_slots INTEGER,
            data_service INTEGER,
            data_access TEXT,
            wuid_lease_minutes INTEGER,
            registration_service INTEGER,
            tdma INTEGER,
            voice_service INTEGER,
            primary_frequency_hz INTEGER,
            current_control_hz INTEGER
        );
        CREATE TABLE p25_site_channel (
            guid TEXT NOT NULL,
            channel_key TEXT NOT NULL,
            descriptor TEXT,
            downlink_hz INTEGER,
            uplink_hz INTEGER,
            tdma INTEGER,
            timeslots INTEGER,
            callsign TEXT,
            confirmed_at_ms INTEGER NOT NULL,
            PRIMARY KEY(guid, channel_key)
        );
        CREATE TABLE p25_site_channel_summary (
            guid TEXT NOT NULL,
            channel_key TEXT NOT NULL,
            descriptor TEXT,
            downlink_hz INTEGER,
            uplink_hz INTEGER,
            tdma INTEGER,
            timeslots INTEGER,
            first_seen_ms INTEGER NOT NULL,
            last_seen_ms INTEGER NOT NULL,
            observation_count INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(guid, channel_key)
        );
        CREATE TABLE p25_site_channel_tag (
            guid TEXT NOT NULL,
            channel_key TEXT NOT NULL,
            tag TEXT NOT NULL,
            confirmed_at_ms INTEGER NOT NULL,
            PRIMARY KEY(guid, channel_key, tag)
        );
        CREATE TABLE p25_site_channel_tag_summary (
            guid TEXT NOT NULL,
            channel_key TEXT NOT NULL,
            tag TEXT NOT NULL,
            first_seen_ms INTEGER NOT NULL,
            last_seen_ms INTEGER NOT NULL,
            observation_count INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(guid, channel_key, tag)
        );
        CREATE TABLE p25_site_frequency_band (
            guid TEXT NOT NULL,
            band INTEGER NOT NULL,
            tdma INTEGER,
            base_hz INTEGER,
            bandwidth INTEGER,
            spacing_hz INTEGER,
            transmit_offset_hz INTEGER,
            timeslots INTEGER,
            confirmed_at_ms INTEGER NOT NULL,
            PRIMARY KEY(guid, band)
        );
        CREATE TABLE p25_site_frequency_band_summary (
            guid TEXT NOT NULL,
            band INTEGER NOT NULL,
            tdma INTEGER,
            base_hz INTEGER,
            bandwidth INTEGER,
            spacing_hz INTEGER,
            transmit_offset_hz INTEGER,
            timeslots INTEGER,
            first_seen_ms INTEGER NOT NULL,
            last_seen_ms INTEGER NOT NULL,
            observation_count INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(guid, band)
        );
        CREATE TABLE p25_foreign_system_band (
            guid TEXT NOT NULL,
            foreign_wacn INTEGER NOT NULL,
            foreign_system_id INTEGER NOT NULL,
            band INTEGER NOT NULL,
            channel_type INTEGER NOT NULL,
            base_hz INTEGER,
            spacing_hz INTEGER,
            transmit_offset_hz INTEGER,
            confirmed_at_ms INTEGER NOT NULL,
            PRIMARY KEY(guid, foreign_wacn, foreign_system_id, band)
        ) WITHOUT ROWID
        ;
        CREATE TABLE p25_foreign_system_band_summary (
            guid TEXT NOT NULL,
            foreign_wacn INTEGER NOT NULL,
            foreign_system_id INTEGER NOT NULL,
            band INTEGER NOT NULL,
            channel_type INTEGER NOT NULL,
            base_hz INTEGER,
            spacing_hz INTEGER,
            transmit_offset_hz INTEGER,
            first_seen_ms INTEGER NOT NULL,
            last_seen_ms INTEGER NOT NULL,
            observation_count INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(guid, foreign_wacn, foreign_system_id, band)
        ) WITHOUT ROWID
        ;
        CREATE TABLE p25_site_neighbor (
            guid TEXT NOT NULL,
            neighbor_key TEXT NOT NULL,
            system_id INTEGER,
            rfss INTEGER,
            site INTEGER,
            lra INTEGER,
            channel_descriptor TEXT,
            downlink_hz INTEGER,
            uplink_hz INTEGER,
            status TEXT,
            confirmed_at_ms INTEGER NOT NULL,
            PRIMARY KEY(guid, neighbor_key)
        );
        CREATE TABLE p25_site_neighbor_summary (
            guid TEXT NOT NULL,
            neighbor_key TEXT NOT NULL,
            system_id INTEGER,
            rfss INTEGER,
            site INTEGER,
            lra INTEGER,
            channel_descriptor TEXT,
            downlink_hz INTEGER,
            uplink_hz INTEGER,
            status TEXT,
            first_seen_ms INTEGER NOT NULL,
            last_seen_ms INTEGER NOT NULL,
            observation_count INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(guid, neighbor_key)
        );
        CREATE TABLE p25_site_patch_group (
            guid TEXT NOT NULL,
            patch_group INTEGER NOT NULL,
            version INTEGER,
            confirmed_at_ms INTEGER NOT NULL,
            PRIMARY KEY(guid, patch_group)
        );
        CREATE TABLE p25_site_patch_group_summary (
            guid TEXT NOT NULL,
            patch_group INTEGER NOT NULL,
            version INTEGER,
            first_seen_ms INTEGER NOT NULL,
            last_seen_ms INTEGER NOT NULL,
            observation_count INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(guid, patch_group)
        );
        CREATE TABLE p25_site_patch_group_talkgroup (
            guid TEXT NOT NULL,
            patch_group INTEGER NOT NULL,
            talkgroup_id INTEGER NOT NULL,
            confirmed_at_ms INTEGER NOT NULL,
            PRIMARY KEY(guid, patch_group, talkgroup_id)
        );
        CREATE TABLE p25_site_patch_group_talkgroup_summary (
            guid TEXT NOT NULL,
            patch_group INTEGER NOT NULL,
            talkgroup_id INTEGER NOT NULL,
            first_seen_ms INTEGER NOT NULL,
            last_seen_ms INTEGER NOT NULL,
            observation_count INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(guid, patch_group, talkgroup_id)
        );
        CREATE TABLE p25_site_patch_group_radio (
            guid TEXT NOT NULL,
            patch_group INTEGER NOT NULL,
            radio_id INTEGER NOT NULL,
            confirmed_at_ms INTEGER NOT NULL,
            PRIMARY KEY(guid, patch_group, radio_id)
        );
        CREATE TABLE p25_site_patch_group_radio_summary (
            guid TEXT NOT NULL,
            patch_group INTEGER NOT NULL,
            radio_id INTEGER NOT NULL,
            first_seen_ms INTEGER NOT NULL,
            last_seen_ms INTEGER NOT NULL,
            observation_count INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(guid, patch_group, radio_id)
        );
        CREATE TABLE p25_control_channel_quality (
            guid TEXT NOT NULL,
            frequency_hz INTEGER NOT NULL,
            bucket_start_ms INTEGER NOT NULL,
            observed_at_ms INTEGER NOT NULL,
            signal_dbfs REAL,
            average_signal_dbfs REAL,
            minimum_signal_dbfs REAL,
            maximum_signal_dbfs REAL,
            decode_health_pct REAL,
            valid_frames INTEGER NOT NULL DEFAULT 0,
            invalid_frames INTEGER NOT NULL DEFAULT 0,
            corrected_bits INTEGER NOT NULL DEFAULT 0,
            sync_loss_bits INTEGER NOT NULL DEFAULT 0,
            dropped_bits INTEGER NOT NULL DEFAULT 0,
            last_valid_decode_ms INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(guid, frequency_hz, bucket_start_ms)
        ) WITHOUT ROWID
        ;
        CREATE TABLE logger_status (
            key TEXT PRIMARY KEY,
            value TEXT,
            updated_at_ms INTEGER NOT NULL
        );
        CREATE UNIQUE INDEX idx_receiver_context_guid ON receiver_context(guid) WHERE guid IS NOT NULL;
        CREATE INDEX idx_p25_activity_event_context_time ON p25_activity_event(context_id, observed_at_ms);
        CREATE INDEX idx_p25_activity_event_target_time ON p25_activity_event(target_id, observed_at_ms) WHERE target_id IS NOT NULL;
        CREATE INDEX idx_p25_activity_event_source_time ON p25_activity_event(source_radio_id, observed_at_ms) WHERE source_radio_id IS NOT NULL;
        CREATE INDEX idx_p25_activity_event_frequency_time ON p25_activity_event(frequency_hz, observed_at_ms) WHERE frequency_hz IS NOT NULL;
        CREATE INDEX idx_p25_activity_event_encryption ON p25_activity_event(encryption_algorithm_id, encryption_key_id, observed_at_ms) WHERE encrypted = 1;
        CREATE INDEX idx_p25_site_talkgroup_bucket_time ON p25_site_talkgroup_bucket(context_id, bucket_start_ms);
        CREATE INDEX idx_p25_site_talkgroup_bucket_talkgroup_time ON p25_site_talkgroup_bucket(talkgroup_id, bucket_start_ms);
        CREATE INDEX idx_p25_site_activity_bucket_time ON p25_site_activity_bucket(bucket_start_ms);
        CREATE INDEX idx_p25_radio_affiliation_talkgroup ON p25_radio_affiliation(system_key, talkgroup_id, updated_at_ms DESC, radio_id);
        CREATE INDEX idx_p25_radio_talkgroup_talkgroup ON p25_radio_talkgroup_summary(system_key, talkgroup_id, last_seen_ms DESC, radio_id);
        CREATE INDEX idx_conventional_bucket_time ON conventional_activity_bucket(context_id, bucket_start_ms);
        CREATE INDEX idx_p25_site_snapshot_identity ON p25_site_snapshot(system_key, rfss, site);
        CREATE INDEX idx_p25_site_channel_guid_frequency ON p25_site_channel(guid, downlink_hz);
        CREATE INDEX idx_p25_site_channel_tag_summary_guid_tag ON p25_site_channel_tag_summary(guid, tag, last_seen_ms DESC);
        CREATE INDEX idx_p25_site_neighbor_guid_site ON p25_site_neighbor(guid, system_id, rfss, site);
        CREATE INDEX idx_p25_site_patch_talkgroup ON p25_site_patch_group_talkgroup(talkgroup_id, guid);
        CREATE INDEX idx_p25_site_patch_radio ON p25_site_patch_group_radio(radio_id, guid);
        CREATE INDEX idx_p25_site_channel_summary_guid_frequency ON p25_site_channel_summary(guid, downlink_hz);
        CREATE INDEX idx_p25_site_neighbor_summary_guid_site ON p25_site_neighbor_summary(guid, system_id, rfss, site);
        CREATE INDEX idx_p25_control_quality_guid_time ON p25_control_channel_quality(guid, observed_at_ms DESC);
        CREATE INDEX idx_p25_control_quality_retention
        ON p25_control_channel_quality(observed_at_ms, guid, frequency_hz, bucket_start_ms)
        ;
        CREATE VIEW p25_activity_event_resolved AS
        SELECT
            a.id,
            rc.context_key,
            rc.guid,
            CASE rc.kind_code WHEN 1 THEN 'TRUNKED_SITE' WHEN 2 THEN 'CONVENTIONAL_P25' WHEN 10 THEN 'CONVENTIONAL_ANALOG' ELSE NULL END AS channel_kind,
            a.observed_at_ms,
            CASE rc.protocol_code WHEN 1 THEN 'APCO25' WHEN 2 THEN 'APCO25_PHASE2' WHEN 3 THEN 'DMR' WHEN 4 THEN 'NXDN' WHEN 10 THEN 'NBFM' WHEN 11 THEN 'AM' ELSE 'UNKNOWN' END AS protocol,
            CASE a.action_code WHEN 1 THEN 'ACKNOWLEDGE' WHEN 2 THEN 'ACTIVE' WHEN 3 THEN 'BUSY' WHEN 4 THEN 'CALL' WHEN 5 THEN 'CHECK' WHEN 6 THEN 'CHECK_ACK' WHEN 7 THEN 'CONTINUE' WHEN 8 THEN 'DATA' WHEN 9 THEN 'DENIAL' WHEN 10 THEN 'EMERGENCY' WHEN 11 THEN 'GPS' WHEN 12 THEN 'GRANT' WHEN 13 THEN 'JOIN' WHEN 14 THEN 'LOGOUT' WHEN 15 THEN 'PAGE' WHEN 16 THEN 'PATCH' WHEN 17 THEN 'PATCH_CANCEL' WHEN 18 THEN 'PATCH_CREATE' WHEN 19 THEN 'QUEUED' WHEN 20 THEN 'REGISTER' WHEN 21 THEN 'REQUEST' WHEN 22 THEN 'STATUS' WHEN 23 THEN 'UNKNOWN' ELSE 'UNKNOWN' END AS action,
            CASE a.event_type_code WHEN 1 THEN 'AFFILIATE' WHEN 2 THEN 'ANNOUNCEMENT' WHEN 3 THEN 'ACKNOWLEDGE' WHEN 4 THEN 'AUTOMATIC_REGISTRATION_SERVICE' WHEN 5 THEN 'CALL' WHEN 6 THEN 'CALL_ENCRYPTED' WHEN 7 THEN 'CALL_GROUP' WHEN 8 THEN 'CALL_GROUP_ENCRYPTED' WHEN 9 THEN 'CALL_PATCH_GROUP' WHEN 10 THEN 'CALL_PATCH_GROUP_ENCRYPTED' WHEN 11 THEN 'CALL_ALERT' WHEN 12 THEN 'CALL_DETECT' WHEN 13 THEN 'CALL_IN_PROGRESS' WHEN 14 THEN 'CALL_DO_NOT_MONITOR' WHEN 15 THEN 'CALL_END' WHEN 16 THEN 'CALL_INTERCONNECT' WHEN 17 THEN 'CALL_INTERCONNECT_ENCRYPTED' WHEN 18 THEN 'CALL_UNIQUE_ID' WHEN 19 THEN 'CALL_UNIT_TO_UNIT' WHEN 20 THEN 'CALL_UNIT_TO_UNIT_ENCRYPTED' WHEN 21 THEN 'CALL_NO_TUNER' WHEN 22 THEN 'CALL_TIMEOUT' WHEN 23 THEN 'CELLOCATOR' WHEN 24 THEN 'COMMAND' WHEN 25 THEN 'DATA_CALL' WHEN 26 THEN 'DATA_CALL_ENCRYPTED' WHEN 27 THEN 'DATA_PACKET' WHEN 28 THEN 'DEREGISTER' WHEN 29 THEN 'DYNAMIC_REGROUP' WHEN 30 THEN 'EMERGENCY' WHEN 31 THEN 'FUNCTION' WHEN 32 THEN 'GPS' WHEN 33 THEN 'ICMP_PACKET' WHEN 34 THEN 'ID_ANI' WHEN 35 THEN 'ID_UNIQUE' WHEN 36 THEN 'IP_PACKET' WHEN 37 THEN 'LRRP' WHEN 38 THEN 'NOTIFICATION' WHEN 39 THEN 'PAGE' WHEN 40 THEN 'QUERY' WHEN 41 THEN 'RADIO_CHECK' WHEN 42 THEN 'RADIO_REGISTRATION_SERVICE' WHEN 43 THEN 'REGISTER' WHEN 44 THEN 'REGISTER_ESN' WHEN 45 THEN 'REQUEST' WHEN 46 THEN 'RESPONSE' WHEN 47 THEN 'RESPONSE_PACKET' WHEN 48 THEN 'SDM' WHEN 49 THEN 'SMS' WHEN 50 THEN 'STATION_ID' WHEN 51 THEN 'STATUS' WHEN 52 THEN 'TEXT_MESSAGE' WHEN 53 THEN 'UDP_PACKET' WHEN 54 THEN 'UNKNOWN_PACKET' WHEN 55 THEN 'XCMP' WHEN 56 THEN 'UNKNOWN' ELSE NULL END AS event_type,
            a.source_radio_id,
            a.target_id,
            CASE a.target_kind_code WHEN 1 THEN 'TALKGROUP' WHEN 2 THEN 'RADIO' WHEN 3 THEN 'PATCH_GROUP' ELSE NULL END AS target_kind,
            a.frequency_hz,
            CASE
                WHEN a.lcn_band IS NOT NULL AND a.lcn_number IS NOT NULL
                THEN a.lcn_band || '-' || a.lcn_number
                ELSE NULL
            END AS lcn,
            a.timeslot,
            a.encrypted,
            a.encryption_algorithm_id,
            a.encryption_key_id,
            a.context_id,
            rc.kind_code AS channel_kind_code,
            rc.protocol_code,
            a.action_code,
            a.event_type_code,
            a.target_kind_code,
            rc.channel_name AS resolved_channel_name,
            rc.alias_list_name AS resolved_alias_list_name,
            rc.decoder AS resolved_decoder,
            rc.system_key AS resolved_system_key,
            ps.wacn AS resolved_wacn,
            ps.system_id AS resolved_system_id,
            rc.nac AS resolved_nac,
            rc.rfss AS resolved_rfss,
            rc.site AS resolved_site,
            rc.current_control_hz AS resolved_current_control_hz
        FROM p25_activity_event a
        LEFT JOIN receiver_context rc ON rc.id = a.context_id
        LEFT JOIN p25_system ps ON ps.system_key = rc.system_key
        ;
        CREATE TABLE trunked_site_snapshot (
            guid TEXT PRIMARY KEY,
            snapshot_hash TEXT NOT NULL,
            protocol_code INTEGER NOT NULL,
            variant_code INTEGER NOT NULL DEFAULT 0,
            identity_domain_code INTEGER NOT NULL DEFAULT 0,
            configured_system TEXT,
            channel_name TEXT,
            alias_list_name TEXT,
            decoder TEXT,
            network_id INTEGER,
            system_id INTEGER,
            site_id INTEGER,
            ran INTEGER,
            model_code INTEGER,
            brand_code INTEGER,
            mode_code INTEGER,
            channel_type_code INTEGER,
            color_code_ts1 INTEGER,
            color_code_ts2 INTEGER,
            current_repeater INTEGER,
            service_flags INTEGER,
            failure_code INTEGER,
            primary_frequency_hz INTEGER,
            current_control_hz INTEGER,
            first_seen_ms INTEGER NOT NULL,
            last_seen_ms INTEGER NOT NULL,
            observation_count INTEGER NOT NULL DEFAULT 1,
            CHECK(protocol_code IN (3, 4)),
            CHECK(last_seen_ms >= first_seen_ms),
            CHECK(observation_count > 0)
        );
        CREATE TABLE trunked_site_channel_summary (
            guid TEXT NOT NULL,
            channel_number INTEGER NOT NULL,
            inbound_channel_number INTEGER NOT NULL,
            timeslot INTEGER NOT NULL,
            frequency_hz INTEGER NOT NULL,
            uplink_hz INTEGER,
            role_flags INTEGER NOT NULL DEFAULT 0,
            first_seen_ms INTEGER NOT NULL,
            last_seen_ms INTEGER NOT NULL,
            observation_count INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(guid, channel_number, inbound_channel_number, timeslot, frequency_hz),
            FOREIGN KEY(guid) REFERENCES trunked_site_snapshot(guid) ON DELETE CASCADE,
            CHECK(last_seen_ms >= first_seen_ms),
            CHECK(observation_count > 0)
        ) WITHOUT ROWID
        ;
        CREATE TABLE trunked_site_neighbor_summary (
            guid TEXT NOT NULL,
            variant_code INTEGER NOT NULL,
            identity_domain_code INTEGER NOT NULL,
            network_id INTEGER NOT NULL,
            system_id INTEGER NOT NULL,
            site_id INTEGER NOT NULL,
            channel_number INTEGER NOT NULL,
            frequency_hz INTEGER NOT NULL,
            status_flags INTEGER NOT NULL DEFAULT 0,
            first_seen_ms INTEGER NOT NULL,
            last_seen_ms INTEGER NOT NULL,
            observation_count INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(guid, variant_code, identity_domain_code, network_id, system_id, site_id,
                channel_number, frequency_hz),
            FOREIGN KEY(guid) REFERENCES trunked_site_snapshot(guid) ON DELETE CASCADE,
            CHECK(last_seen_ms >= first_seen_ms),
            CHECK(observation_count > 0)
        ) WITHOUT ROWID
        ;
        CREATE INDEX idx_trunked_site_snapshot_last_seen
        ON trunked_site_snapshot(last_seen_ms, guid)
        ;
        CREATE INDEX idx_trunked_site_channel_last_seen
        ON trunked_site_channel_summary(
            last_seen_ms, guid, channel_number, inbound_channel_number, timeslot, frequency_hz)
        ;
        CREATE INDEX idx_trunked_site_neighbor_last_seen
        ON trunked_site_neighbor_summary(
            last_seen_ms, guid, variant_code, identity_domain_code, network_id, system_id, site_id,
            channel_number, frequency_hz)
        ;
        """;

    private Alpha7TestDatabase()
    {
    }

    static Path create(Path database) throws Exception
    {
        Files.createDirectories(database.toAbsolutePath().normalize().getParent());
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            for(String sql: SCHEMA.split(";"))
            {
                if(!sql.isBlank())
                {
                    statement.executeUpdate(sql);
                }
            }
            statement.executeUpdate("""
                INSERT INTO database_metadata(key, value, updated_at_ms) VALUES
                    ('alias_config_initialized', 'true', 1),
                    ('alias_schema_version', '3', 1),
                    ('configuration_schema_version', '2', 1),
                    ('configuration_state_initialized', 'true', 1),
                    ('icon_schema_version', '2', 1),
                    ('p25_activity_schema_version', '21', 1),
                    ('p25_call_output_metrics_started_at_ms', '1', 1),
                    ('settings_schema_version', '2', 1),
                    ('trunked_site_schema_version', '2', 1)
                """);
        }
        return database;
    }
}
