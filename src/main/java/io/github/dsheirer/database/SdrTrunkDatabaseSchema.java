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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Expected global SDRTrunk SQLite schema.
 */
public final class SdrTrunkDatabaseSchema
{
    public static final int ALIAS_SCHEMA_VERSION = 3;
    public static final int CONFIGURATION_SCHEMA_VERSION = 2;
    public static final int SETTINGS_SCHEMA_VERSION = 2;
    public static final int ICON_SCHEMA_VERSION = 2;

    private static final List<SqliteSchemaValidator.Table> TABLES = tables();
    private static final List<String> INDEXES = List.of(
        "idx_alias_sort",
        "idx_alias_list_name",
        "idx_alias_broadcast_channel_alias",
        "idx_alias_broadcast_channel_name",
        "idx_alias_talkgroup_alias",
        "idx_alias_talkgroup_value",
        "idx_alias_talkgroup_range",
        "idx_alias_radio_alias",
        "idx_alias_radio_value",
        "idx_alias_radio_range",
        "idx_alias_status_alias",
        "idx_alias_status_lookup",
        "idx_alias_tone_sequence_alias",
        "idx_alias_text_identifier_alias",
        "idx_alias_text_identifier_type",
        "idx_alias_action_alias",
        "idx_configuration_channel_sort",
        "idx_configuration_channel_alias_list",
        "idx_configuration_channel_decoder",
        "idx_configuration_channel_frequency",
        "idx_configuration_channel_map_sort",
        "idx_configuration_broadcast_sort",
        "idx_configuration_broadcast_type"
    );
    private static final List<SqliteSchemaValidator.Metadata> METADATA = metadata();

    private SdrTrunkDatabaseSchema()
    {
    }

    private static List<SqliteSchemaValidator.Table> tables()
    {
        return List.of(
            new SqliteSchemaValidator.Table("database_metadata", "key", "value", "updated_at_ms"),
            new SqliteSchemaValidator.Table("alias", "id", "sort_order", "name", "description", "alias_list_name",
                "group_name", "color", "icon_name", "stream_as_talkgroup", "record_enabled", "non_recordable",
                "priority"),
            new SqliteSchemaValidator.Table("alias_broadcast_channel", "id", "alias_id", "sort_order",
                "channel_name"),
            new SqliteSchemaValidator.Table("alias_talkgroup", "id", "alias_id", "sort_order", "protocol", "value",
                "min_value", "max_value", "wacn", "system_id", "fully_qualified", "ranged"),
            new SqliteSchemaValidator.Table("alias_radio", "id", "alias_id", "sort_order", "protocol", "value",
                "min_value", "max_value", "wacn", "system_id", "fully_qualified", "ranged"),
            new SqliteSchemaValidator.Table("alias_status", "id", "alias_id", "sort_order", "status_kind", "status"),
            new SqliteSchemaValidator.Table("alias_tone_sequence", "id", "alias_id", "sort_order", "tone_sequence"),
            new SqliteSchemaValidator.Table("alias_text_identifier", "id", "alias_id", "sort_order",
                "identifier_type", "text_value", "text_value_2", "numeric_value"),
            new SqliteSchemaValidator.Table("alias_action", "id", "alias_id", "sort_order", "type", "interval",
                "period", "path", "script"),
            new SqliteSchemaValidator.Table("configuration_channel", "id", "sort_order", "system_name", "site_name",
                "name", "alias_list_name", "radres_guid", "auto_start", "auto_start_order", "decoder_type",
                "source_type", "primary_frequency_hz", "frequency_count", "recording_enabled",
                "event_logging_enabled", "config_json"),
            new SqliteSchemaValidator.Table("configuration_channel_map", "id", "sort_order", "name", "config_json"),
            new SqliteSchemaValidator.Table("configuration_broadcast_stream", "id", "sort_order", "name",
                "server_type", "enabled", "host", "port", "delay_ms", "maximum_recording_age_ms", "config_json"),
            new SqliteSchemaValidator.Table("application_settings", "key", "settings_json", "updated_at_ms"),
            new SqliteSchemaValidator.Table("application_icons", "key", "icons_json", "updated_at_ms")
        );
    }

    private static List<SqliteSchemaValidator.Metadata> metadata()
    {
        return List.of(
            new SqliteSchemaValidator.Metadata("alias_schema_version", Integer.toString(ALIAS_SCHEMA_VERSION)),
            new SqliteSchemaValidator.Metadata("configuration_schema_version",
                Integer.toString(CONFIGURATION_SCHEMA_VERSION)),
            new SqliteSchemaValidator.Metadata("settings_schema_version", Integer.toString(SETTINGS_SCHEMA_VERSION)),
            new SqliteSchemaValidator.Metadata("icon_schema_version", Integer.toString(ICON_SCHEMA_VERSION))
        );
    }

    public static void create(Connection connection) throws SQLException
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
                    description TEXT,
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

        SdrTrunkDatabaseStartup.setMetadata(connection, "alias_schema_version", Integer.toString(ALIAS_SCHEMA_VERSION));
        SdrTrunkDatabaseStartup.setMetadata(connection, "configuration_schema_version",
            Integer.toString(CONFIGURATION_SCHEMA_VERSION));
        SdrTrunkDatabaseStartup.setMetadata(connection, "settings_schema_version",
            Integer.toString(SETTINGS_SCHEMA_VERSION));
        SdrTrunkDatabaseStartup.setMetadata(connection, "icon_schema_version", Integer.toString(ICON_SCHEMA_VERSION));
    }

    public static void validate(Connection connection) throws SQLException
    {
        SqliteSchemaValidator.validate(connection, TABLES, INDEXES, List.of(), METADATA);
    }

}
