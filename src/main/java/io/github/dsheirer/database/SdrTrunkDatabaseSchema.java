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

package io.github.dsheirer.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Global SDRTrunk SQLite schema.
 */
public final class SdrTrunkDatabaseSchema
{
    private static final int ALIAS_SCHEMA_VERSION = 2;
    private static final int CONFIGURATION_SCHEMA_VERSION = 2;
    private static final int SETTINGS_SCHEMA_VERSION = 2;
    private static final int ICON_SCHEMA_VERSION = 2;

    private SdrTrunkDatabaseSchema()
    {
    }

    static void initialize(Connection connection) throws SQLException
    {
        createTables(connection);
        updateMetadata(connection, "alias_schema_version", Integer.toString(ALIAS_SCHEMA_VERSION));
        updateMetadata(connection, "configuration_schema_version", Integer.toString(CONFIGURATION_SCHEMA_VERSION));
        updateMetadata(connection, "settings_schema_version", Integer.toString(SETTINGS_SCHEMA_VERSION));
        updateMetadata(connection, "icon_schema_version", Integer.toString(ICON_SCHEMA_VERSION));
    }

    private static void createTables(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS database_metadata (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL,
                    updated_at_ms INTEGER NOT NULL
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS alias (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    sort_order INTEGER NOT NULL,
                    name TEXT,
                    alias_list_name TEXT,
                    group_name TEXT,
                    color INTEGER NOT NULL DEFAULT 0,
                    icon_name TEXT,
                    stream_as_talkgroup INTEGER,
                    record_enabled INTEGER NOT NULL DEFAULT 0,
                    non_recordable INTEGER NOT NULL DEFAULT 0,
                    priority INTEGER
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS alias_broadcast_channel (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    alias_id INTEGER NOT NULL REFERENCES alias(id) ON DELETE CASCADE,
                    sort_order INTEGER NOT NULL,
                    channel_name TEXT NOT NULL
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS alias_talkgroup (
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
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS alias_radio (
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
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS alias_status (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    alias_id INTEGER NOT NULL REFERENCES alias(id) ON DELETE CASCADE,
                    sort_order INTEGER NOT NULL,
                    status_kind TEXT NOT NULL,
                    status INTEGER NOT NULL
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS alias_tone_sequence (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    alias_id INTEGER NOT NULL REFERENCES alias(id) ON DELETE CASCADE,
                    sort_order INTEGER NOT NULL,
                    tone_sequence TEXT
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS alias_text_identifier (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    alias_id INTEGER NOT NULL REFERENCES alias(id) ON DELETE CASCADE,
                    sort_order INTEGER NOT NULL,
                    identifier_type TEXT NOT NULL,
                    text_value TEXT,
                    text_value_2 TEXT,
                    numeric_value INTEGER
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS alias_action (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    alias_id INTEGER NOT NULL REFERENCES alias(id) ON DELETE CASCADE,
                    sort_order INTEGER NOT NULL,
                    type TEXT NOT NULL,
                    interval TEXT,
                    period INTEGER,
                    path TEXT,
                    script TEXT
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS configuration_channel (
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
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS configuration_channel_map (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    sort_order INTEGER NOT NULL,
                    name TEXT,
                    config_json TEXT NOT NULL
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS configuration_broadcast_stream (
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
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS application_settings (
                    key TEXT PRIMARY KEY,
                    settings_json TEXT NOT NULL,
                    updated_at_ms INTEGER NOT NULL
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS application_icons (
                    key TEXT PRIMARY KEY,
                    icons_json TEXT NOT NULL,
                    updated_at_ms INTEGER NOT NULL
                )
                """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_alias_sort ON alias(sort_order, id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_alias_list_name ON alias(alias_list_name)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_alias_broadcast_channel_alias ON alias_broadcast_channel(alias_id, sort_order, id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_alias_broadcast_channel_name ON alias_broadcast_channel(channel_name)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_alias_talkgroup_alias ON alias_talkgroup(alias_id, sort_order, id)");
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_alias_talkgroup_value
                ON alias_talkgroup(protocol, value, wacn, system_id)
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_alias_talkgroup_range
                ON alias_talkgroup(protocol, min_value, max_value)
                """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_alias_radio_alias ON alias_radio(alias_id, sort_order, id)");
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_alias_radio_value
                ON alias_radio(protocol, value, wacn, system_id)
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_alias_radio_range
                ON alias_radio(protocol, min_value, max_value)
                """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_alias_status_alias ON alias_status(alias_id, sort_order, id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_alias_status_lookup ON alias_status(status_kind, status)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_alias_tone_sequence_alias ON alias_tone_sequence(alias_id, sort_order, id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_alias_text_identifier_alias ON alias_text_identifier(alias_id, sort_order, id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_alias_text_identifier_type ON alias_text_identifier(identifier_type)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_alias_action_alias ON alias_action(alias_id, sort_order, id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_configuration_channel_sort ON configuration_channel(sort_order, id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_configuration_channel_alias_list ON configuration_channel(alias_list_name)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_configuration_channel_decoder ON configuration_channel(decoder_type)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_configuration_channel_frequency ON configuration_channel(primary_frequency_hz)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_configuration_channel_map_sort ON configuration_channel_map(sort_order, id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_configuration_broadcast_sort ON configuration_broadcast_stream(sort_order, id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_configuration_broadcast_type ON configuration_broadcast_stream(server_type, enabled)");
        }
    }

    private static void updateMetadata(Connection connection, String key, String value) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO database_metadata (key, value, updated_at_ms)
            VALUES (?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET
                value = excluded.value,
                updated_at_ms = excluded.updated_at_ms
            """))
        {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }
}
