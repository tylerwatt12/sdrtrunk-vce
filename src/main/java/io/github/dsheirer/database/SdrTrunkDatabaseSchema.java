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

import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.database.upgrade.Format5SchemaSql;
import io.github.dsheirer.database.upgrade.Format5WebStateValidator;
import io.github.dsheirer.database.upgrade.Format6ReceiverContextValidator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Expected global SDRTrunk SQLite schema.
 */
public final class SdrTrunkDatabaseSchema
{
    public static final int ALIAS_SCHEMA_VERSION = 6;
    public static final int CONFIGURATION_SCHEMA_VERSION = 3;
    public static final int SETTINGS_SCHEMA_VERSION = 3;
    public static final int ICON_SCHEMA_VERSION = 2;

    private static final String ALIAS_LIST_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS alias_list (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL COLLATE NOCASE,
            family TEXT NOT NULL CHECK(family IN (
                'P25', 'DMR', 'NXDN', 'NBFM'
            )),
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
    private static final String SCAN_LIST_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS scan_list (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            sort_order INTEGER NOT NULL DEFAULT 0 CHECK(sort_order >= 0),
            name TEXT NOT NULL COLLATE NOCASE CHECK(
                length(trim(name)) BETWEEN 1 AND 100
            ),
            description TEXT CHECK(
                description IS NULL OR length(trim(description)) BETWEEN 1 AND 1000
            ),
            published INTEGER NOT NULL DEFAULT 1 CHECK(published IN (0, 1)),
            is_default INTEGER NOT NULL DEFAULT 0 CHECK(is_default IN (0, 1)),
            CHECK(is_default = 0 OR published = 1),
            UNIQUE(name)
        )
        """;
    private static final String ALIAS_SCAN_LIST_MEMBERSHIP_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS alias_scan_list_membership (
            alias_id INTEGER NOT NULL REFERENCES alias(id) ON DELETE CASCADE,
            scan_list_id INTEGER NOT NULL REFERENCES scan_list(id) ON DELETE CASCADE,
            PRIMARY KEY(alias_id, scan_list_id)
        ) WITHOUT ROWID
        """;
    private static final String ALIAS_LIST_UNMATCHED_TALKGROUP_SCAN_LIST_MEMBERSHIP_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS alias_list_unmatched_talkgroup_scan_list_membership (
            alias_list_id INTEGER NOT NULL REFERENCES alias_list(id) ON DELETE CASCADE,
            scan_list_id INTEGER NOT NULL REFERENCES scan_list(id) ON DELETE CASCADE,
            PRIMARY KEY(alias_list_id, scan_list_id)
        ) WITHOUT ROWID
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
        new SqliteSchemaValidator.Definition("table", "scan_list", SCAN_LIST_TABLE_SQL),
        new SqliteSchemaValidator.Definition("table", "alias_scan_list_membership",
            ALIAS_SCAN_LIST_MEMBERSHIP_TABLE_SQL),
        new SqliteSchemaValidator.Definition("table", "alias_list_unmatched_talkgroup_scan_list_membership",
            ALIAS_LIST_UNMATCHED_TALKGROUP_SCAN_LIST_MEMBERSHIP_TABLE_SQL),
        new SqliteSchemaValidator.Definition("index", "idx_scan_list_one_default", """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_scan_list_one_default
            ON scan_list(is_default)
            WHERE is_default = 1
            """),
        new SqliteSchemaValidator.Definition("index", "idx_alias_scan_list_by_list", """
            CREATE INDEX IF NOT EXISTS idx_alias_scan_list_by_list
            ON alias_scan_list_membership(scan_list_id, alias_id)
            """),
        new SqliteSchemaValidator.Definition("index", "idx_alias_list_unmatched_talkgroup_scan_list_by_list", """
            CREATE INDEX IF NOT EXISTS idx_alias_list_unmatched_talkgroup_scan_list_by_list
            ON alias_list_unmatched_talkgroup_scan_list_membership(scan_list_id, alias_list_id)
            """),
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
    private static final List<SqliteSchemaValidator.Definition> EXACT_WEB_SETTINGS_OBJECTS = List.of(
        new SqliteSchemaValidator.Definition("table", "web_user", Format5SchemaSql.WEB_USER_TABLE_SQL),
        new SqliteSchemaValidator.Definition("table", "web_access_policy",
            Format5SchemaSql.WEB_ACCESS_POLICY_TABLE_SQL),
        new SqliteSchemaValidator.Definition("index", "idx_web_user_one_primary_admin",
            Format5SchemaSql.WEB_USER_PRIMARY_INDEX_SQL));
    private static final List<SqliteSchemaValidator.Table> TABLES = tables();
    private static final List<String> INDEXES = List.of(
        "idx_alias_talkgroup_value",
        "idx_alias_talkgroup_range",
        "idx_alias_radio_value",
        "idx_alias_radio_range",
        "idx_alias_broadcast_channel_name",
        "idx_scan_list_one_default",
        "idx_alias_scan_list_by_list",
        "idx_alias_list_unmatched_talkgroup_scan_list_by_list",
        "idx_configuration_channel_sort",
        "idx_configuration_channel_alias_list",
        "idx_configuration_channel_decoder",
        "idx_configuration_channel_frequency",
        "idx_configuration_channel_unique_radres_guid",
        "idx_configuration_channel_map_sort",
        "idx_configuration_broadcast_sort",
        "idx_configuration_broadcast_type",
        "idx_web_user_one_primary_admin"
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
                "unmatched_talkgroup_record_enabled"),
            new SqliteSchemaValidator.Table("alias", "id", "alias_list_id", "name", "description",
                "group_name", "color", "icon_name", "stream_as_talkgroup", "record_enabled",
                "matcher_type", "protocol", "value", "min_value",
                "max_value", "text_value", "numeric_value", "tone_sequence"),
            new SqliteSchemaValidator.Table("alias_broadcast_channel", "id", "alias_id", "channel_name"),
            new SqliteSchemaValidator.Table("alias_list_unmatched_talkgroup_stream", "id", "alias_list_id",
                "channel_name"),
            new SqliteSchemaValidator.Table("scan_list", "id", "sort_order", "name", "description", "published",
                "is_default"),
            new SqliteSchemaValidator.Table("alias_scan_list_membership", "alias_id", "scan_list_id"),
            new SqliteSchemaValidator.Table("alias_list_unmatched_talkgroup_scan_list_membership", "alias_list_id",
                "scan_list_id"),
            new SqliteSchemaValidator.Table("configuration_channel", "id", "configuration_id", "channel_kind",
                "sort_order", "system_name", "site_name",
                "name", "alias_list_name", "radres_guid", "auto_start", "auto_start_order", "decoder_type",
                "source_type", "primary_frequency_hz", "frequency_count", "recording_enabled",
                "event_logging_enabled", "config_json"),
            new SqliteSchemaValidator.Table("configuration_channel_map", "id", "sort_order", "name", "config_json"),
            new SqliteSchemaValidator.Table("configuration_broadcast_stream", "id", "sort_order", "name",
                "server_type", "enabled", "host", "port", "delay_ms", "maximum_recording_age_ms", "config_json"),
            new SqliteSchemaValidator.Table("application_settings", "key", "settings_json", "updated_at_ms"),
            new SqliteSchemaValidator.Table("application_icons", "key", "icons_json", "updated_at_ms"),
            new SqliteSchemaValidator.Table("web_user", "id", "username", "tier", "primary_admin",
                "credential_version", "password_algorithm", "password_iterations", "password_derived_key_bits",
                "password_salt", "password_hash", "password_changed_at_ms", "auth_revision", "preferences_json",
                "preferences_revision", "created_at_ms", "updated_at_ms"),
            new SqliteSchemaValidator.Table("web_access_policy", "capability_id", "required_tier", "updated_at_ms")
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
                INSERT INTO scan_list (sort_order, name, description, published, is_default)
                SELECT 0, 'Default', NULL, 1, 1
                WHERE NOT EXISTS (SELECT 1 FROM scan_list)
                """);
            Format5SchemaSql.createConfigurationChannel(statement);
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
            Format5SchemaSql.createWebSettings(statement);
            Format5SchemaSql.createConfigurationIndexes(statement);
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

    /**
     * Seeds the visible factory Alias List for each supported protocol family and routes each list's unmatched
     * talkgroups to the current Default scan list. This is invoked only for a new database and by the staged
     * release migrator; normal startup remains validation-only.
     *
     * <p>An existing canonical name is reused only when it belongs to the expected family. A wrong-family collision
     * is rejected instead of silently assigning channels to an incompatible list.</p>
     */
    public static void seedDefaultAliasLists(Connection connection) throws SQLException
    {
        long defaultScanListId = requireDefaultScanListId(connection);
        Map<AliasListFamily,Long> existingIds = new EnumMap<>(AliasListFamily.class);

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, family
            FROM alias_list
            WHERE name = ? COLLATE NOCASE
            """))
        {
            for(AliasListFamily family: AliasListFamily.values())
            {
                statement.setString(1, family.getDefaultAliasListName());

                try(ResultSet resultSet = statement.executeQuery())
                {
                    if(resultSet.next())
                    {
                        String persistedFamily = resultSet.getString("family");
                        if(!family.name().equals(persistedFamily))
                        {
                            throw new SQLException("Default Alias List name [" +
                                family.getDefaultAliasListName() + "] belongs to family [" + persistedFamily +
                                "]; expected [" + family.name() + "]");
                        }
                        existingIds.put(family, resultSet.getLong("id"));
                    }
                }
            }
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO alias_list (name, family, unmatched_talkgroup_record_enabled)
            VALUES (?, ?, 0)
            """))
        {
            for(AliasListFamily family: AliasListFamily.values())
            {
                if(!existingIds.containsKey(family))
                {
                    statement.setString(1, family.getDefaultAliasListName());
                    statement.setString(2, family.name());
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }

        try(PreparedStatement lookup = connection.prepareStatement("""
                SELECT id
                FROM alias_list
                WHERE name = ? COLLATE NOCASE AND family = ?
                """);
            PreparedStatement membership = connection.prepareStatement("""
                INSERT OR IGNORE INTO alias_list_unmatched_talkgroup_scan_list_membership (
                    alias_list_id, scan_list_id
                ) VALUES (?, ?)
                """))
        {
            for(AliasListFamily family: AliasListFamily.values())
            {
                long aliasListId;
                Long existingId = existingIds.get(family);

                if(existingId != null)
                {
                    aliasListId = existingId;
                }
                else
                {
                    lookup.setString(1, family.getDefaultAliasListName());
                    lookup.setString(2, family.name());
                    try(ResultSet resultSet = lookup.executeQuery())
                    {
                        if(!resultSet.next())
                        {
                            throw new SQLException("Unable to resolve seeded Alias List [" +
                                family.getDefaultAliasListName() + "]");
                        }
                        aliasListId = resultSet.getLong("id");
                    }
                }

                membership.setLong(1, aliasListId);
                membership.setLong(2, defaultScanListId);
                membership.addBatch();
            }
            membership.executeBatch();
        }
    }

    private static long requireDefaultScanListId(Connection connection) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT id FROM scan_list WHERE is_default = 1");
            ResultSet resultSet = statement.executeQuery())
        {
            if(!resultSet.next())
            {
                throw new SQLException("Default Alias Lists require one Default scan list");
            }

            long id = resultSet.getLong("id");
            if(resultSet.next())
            {
                throw new SQLException("Default Alias Lists require exactly one Default scan list");
            }
            return id;
        }
    }

    public static void validate(Connection connection) throws SQLException
    {
        SqliteSchemaValidator.validate(connection, TABLES, INDEXES, VIEWS, METADATA);
        SqliteSchemaValidator.validateDefinitions(connection, EXACT_ALIAS_OBJECTS);
        SqliteSchemaValidator.validateDefinitions(connection, EXACT_WEB_SETTINGS_OBJECTS);
        Format5WebStateValidator.validate(connection);
        Format6ReceiverContextValidator.validate(connection);
    }

}
