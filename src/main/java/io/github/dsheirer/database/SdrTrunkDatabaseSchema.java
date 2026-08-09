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
    public static final int ALIAS_SCHEMA_VERSION = 5;
    public static final int CONFIGURATION_SCHEMA_VERSION = 2;
    public static final int SETTINGS_SCHEMA_VERSION = 2;
    public static final int ICON_SCHEMA_VERSION = 2;

    private static final String ALIAS_LIST_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS alias_list (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL COLLATE NOCASE,
            family TEXT NOT NULL CHECK(family IN (
                'P25', 'DMR', 'NXDN', 'NBFM'
            )),
            unmatched_talkgroup_priority INTEGER NOT NULL DEFAULT 100 CHECK(
                unmatched_talkgroup_priority = -1 OR
                unmatched_talkgroup_priority BETWEEN 1 AND 100
            ),
            unmatched_talkgroup_record_enabled INTEGER NOT NULL DEFAULT 0 CHECK(
                unmatched_talkgroup_record_enabled IN (0, 1)
            ),
            UNIQUE(name)
        )
        """;
    private static final String ALIAS_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS alias (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            alias_list_id INTEGER NOT NULL REFERENCES alias_list(id) ON DELETE RESTRICT,
            name TEXT,
            description TEXT,
            group_name TEXT,
            color INTEGER NOT NULL DEFAULT 0,
            icon_name TEXT,
            stream_as_talkgroup INTEGER,
            record_enabled INTEGER NOT NULL DEFAULT 0,
            priority INTEGER,
            matcher_type TEXT NOT NULL CHECK(matcher_type IN (
                'TALKGROUP',
                'TALKGROUP_RANGE',
                'RADIO_ID',
                'RADIO_ID_RANGE',
                'STATUS',
                'UNIT_STATUS',
                'TONES',
                'DCS',
                'ESN'
            )),
            protocol TEXT,
            value INTEGER,
            min_value INTEGER,
            max_value INTEGER,
            text_value TEXT,
            numeric_value INTEGER,
            tone_sequence TEXT
        )
        """;
    private static final String ALIAS_BROADCAST_CHANNEL_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS alias_broadcast_channel (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            alias_id INTEGER NOT NULL REFERENCES alias(id) ON DELETE CASCADE,
            channel_name TEXT NOT NULL CHECK(length(trim(channel_name)) > 0),
            UNIQUE(alias_id, channel_name)
        )
        """;
    private static final String ALIAS_LIST_UNMATCHED_TALKGROUP_STREAM_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS alias_list_unmatched_talkgroup_stream (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            alias_list_id INTEGER NOT NULL REFERENCES alias_list(id) ON DELETE CASCADE,
            channel_name TEXT NOT NULL CHECK(length(trim(channel_name)) > 0),
            UNIQUE(alias_list_id, channel_name)
        )
        """;
    private static final List<SqliteSchemaValidator.Definition> EXACT_ALIAS_OBJECTS = List.of(
        new SqliteSchemaValidator.Definition("table", "alias_list", ALIAS_LIST_TABLE_SQL),
        new SqliteSchemaValidator.Definition("table", "alias", ALIAS_TABLE_SQL),
        new SqliteSchemaValidator.Definition("table", "alias_broadcast_channel",
            ALIAS_BROADCAST_CHANNEL_TABLE_SQL),
        new SqliteSchemaValidator.Definition("table", "alias_list_unmatched_talkgroup_stream",
            ALIAS_LIST_UNMATCHED_TALKGROUP_STREAM_TABLE_SQL),
        new SqliteSchemaValidator.Definition("index", "idx_alias_talkgroup_value", """
            CREATE INDEX IF NOT EXISTS idx_alias_talkgroup_value
            ON alias(protocol, value, alias_list_id, id)
            WHERE matcher_type = 'TALKGROUP'
            """),
        new SqliteSchemaValidator.Definition("index", "idx_alias_talkgroup_range", """
            CREATE INDEX IF NOT EXISTS idx_alias_talkgroup_range
            ON alias(protocol, min_value, max_value, alias_list_id, id)
            WHERE matcher_type = 'TALKGROUP_RANGE'
            """),
        new SqliteSchemaValidator.Definition("index", "idx_alias_radio_value", """
            CREATE INDEX IF NOT EXISTS idx_alias_radio_value
            ON alias(protocol, value, alias_list_id, id)
            WHERE matcher_type = 'RADIO_ID'
            """),
        new SqliteSchemaValidator.Definition("index", "idx_alias_radio_range", """
            CREATE INDEX IF NOT EXISTS idx_alias_radio_range
            ON alias(protocol, min_value, max_value, alias_list_id, id)
            WHERE matcher_type = 'RADIO_ID_RANGE'
            """),
        new SqliteSchemaValidator.Definition("index", "idx_alias_broadcast_channel_name",
            "CREATE INDEX IF NOT EXISTS idx_alias_broadcast_channel_name " +
                "ON alias_broadcast_channel(channel_name)"),
        new SqliteSchemaValidator.Definition("view", "alias_talkgroup", """
            CREATE VIEW IF NOT EXISTS alias_talkgroup AS
            SELECT alias.id AS alias_id,
                   alias.protocol,
                   alias.value,
                   alias.min_value,
                   alias.max_value,
                   CASE WHEN alias.matcher_type = 'TALKGROUP_RANGE' THEN 1 ELSE 0 END AS ranged,
                   alias_list.name AS alias_list_name
            FROM alias
            JOIN alias_list ON alias_list.id = alias.alias_list_id
            WHERE alias.matcher_type IN (
                  'TALKGROUP',
                  'TALKGROUP_RANGE'
              )
            """),
        new SqliteSchemaValidator.Definition("view", "alias_radio", """
            CREATE VIEW IF NOT EXISTS alias_radio AS
            SELECT alias.id AS alias_id,
                   alias.protocol,
                   alias.value,
                   alias.min_value,
                   alias.max_value,
                   CASE WHEN alias.matcher_type = 'RADIO_ID_RANGE' THEN 1 ELSE 0 END AS ranged,
                   alias_list.name AS alias_list_name
            FROM alias
            JOIN alias_list ON alias_list.id = alias.alias_list_id
            WHERE alias.matcher_type IN (
                  'RADIO_ID',
                  'RADIO_ID_RANGE'
              )
            """)
    );
    private static final List<SqliteSchemaValidator.Table> TABLES = tables();
    private static final List<String> INDEXES = List.of(
        "idx_alias_talkgroup_value",
        "idx_alias_talkgroup_range",
        "idx_alias_radio_value",
        "idx_alias_radio_range",
        "idx_alias_broadcast_channel_name",
        "idx_configuration_channel_sort",
        "idx_configuration_channel_alias_list",
        "idx_configuration_channel_decoder",
        "idx_configuration_channel_frequency",
        "idx_configuration_channel_map_sort",
        "idx_configuration_broadcast_sort",
        "idx_configuration_broadcast_type"
    );
    private static final List<String> VIEWS = List.of("alias_talkgroup", "alias_radio");
    private static final List<SqliteSchemaValidator.Metadata> METADATA = metadata();

    private SdrTrunkDatabaseSchema()
    {
    }

    private static List<SqliteSchemaValidator.Table> tables()
    {
        return List.of(
            new SqliteSchemaValidator.Table("database_metadata", "key", "value", "updated_at_ms"),
            new SqliteSchemaValidator.Table("alias_list", "id", "name", "family",
                "unmatched_talkgroup_priority", "unmatched_talkgroup_record_enabled"),
            new SqliteSchemaValidator.Table("alias", "id", "alias_list_id", "name", "description",
                "group_name", "color", "icon_name", "stream_as_talkgroup", "record_enabled",
                "priority", "matcher_type", "protocol", "value", "min_value",
                "max_value", "text_value", "numeric_value", "tone_sequence"),
            new SqliteSchemaValidator.Table("alias_broadcast_channel", "id", "alias_id", "channel_name"),
            new SqliteSchemaValidator.Table("alias_list_unmatched_talkgroup_stream", "id", "alias_list_id",
                "channel_name"),
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
            for(SqliteSchemaValidator.Definition definition: EXACT_ALIAS_OBJECTS)
            {
                statement.executeUpdate(definition.sql());
            }
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
        SqliteSchemaValidator.validate(connection, TABLES, INDEXES, VIEWS, METADATA);
        SqliteSchemaValidator.validateDefinitions(connection, EXACT_ALIAS_OBJECTS);
    }

}
