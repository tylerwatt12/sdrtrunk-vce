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

import java.sql.SQLException;
import java.sql.Statement;

/** Frozen DDL introduced by whole-file database format 5. */
public final class Format5SchemaSql
{
    public static final String CONFIGURATION_CHANNEL_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS configuration_channel (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            configuration_id TEXT NOT NULL UNIQUE CHECK(
                length(configuration_id) = 36 AND configuration_id = lower(configuration_id)
                AND substr(configuration_id, 9, 1) = '-'
                AND substr(configuration_id, 14, 1) = '-'
                AND substr(configuration_id, 19, 1) = '-'
                AND substr(configuration_id, 24, 1) = '-'
                AND length(replace(configuration_id, '-', '')) = 32
                AND replace(configuration_id, '-', '') NOT GLOB '*[^0-9a-f]*'
            ),
            channel_kind TEXT NOT NULL CHECK(channel_kind IN ('TRUNKED', 'CONVENTIONAL')),
            sort_order INTEGER NOT NULL,
            system_name TEXT,
            site_name TEXT,
            name TEXT,
            alias_list_name TEXT,
            radres_guid TEXT CHECK(
                radres_guid IS NULL OR length(trim(radres_guid)) = 0 OR (
                    radres_guid = trim(radres_guid)
                    AND length(radres_guid) = 36
                    AND radres_guid = lower(radres_guid)
                    AND substr(radres_guid, 9, 1) = '-'
                    AND substr(radres_guid, 14, 1) = '-'
                    AND substr(radres_guid, 19, 1) = '-'
                    AND substr(radres_guid, 24, 1) = '-'
                    AND length(replace(radres_guid, '-', '')) = 32
                    AND replace(radres_guid, '-', '') NOT GLOB '*[^0-9a-f]*'
                )
            ),
            auto_start INTEGER NOT NULL DEFAULT 0
                CHECK(typeof(auto_start) = 'integer' AND auto_start IN (0, 1)),
            auto_start_order INTEGER CHECK(
                auto_start_order IS NULL OR (
                    typeof(auto_start_order) = 'integer'
                    AND auto_start_order BETWEEN -2147483648 AND 2147483647
                )
            ),
            decoder_type TEXT,
            source_type TEXT,
            primary_frequency_hz INTEGER,
            frequency_count INTEGER NOT NULL DEFAULT 0,
            recording_enabled INTEGER NOT NULL DEFAULT 0,
            event_logging_enabled INTEGER NOT NULL DEFAULT 0,
            config_json TEXT NOT NULL,
            CHECK(
                channel_kind = 'CONVENTIONAL' OR (
                    radres_guid IS NOT NULL AND length(trim(radres_guid)) > 0
                )
            )
        )
        """;
    public static final String WEB_USER_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS web_user (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT NOT NULL COLLATE NOCASE UNIQUE
                CHECK(length(username) BETWEEN 1 AND 64 AND username = lower(username)),
            tier TEXT NOT NULL CHECK(tier IN ('USER', 'ADMIN')),
            primary_admin INTEGER NOT NULL DEFAULT 0 CHECK(primary_admin IN (0, 1)),
            credential_version INTEGER NOT NULL CHECK(credential_version = 1),
            password_algorithm TEXT NOT NULL CHECK(password_algorithm = 'PBKDF2WithHmacSHA256'),
            password_iterations INTEGER NOT NULL CHECK(password_iterations BETWEEN 600000 AND 5000000),
            password_derived_key_bits INTEGER NOT NULL CHECK(password_derived_key_bits = 256),
            password_salt BLOB NOT NULL CHECK(length(password_salt) BETWEEN 16 AND 64),
            password_hash BLOB NOT NULL CHECK(length(password_hash) = 32),
            password_changed_at_ms INTEGER NOT NULL CHECK(password_changed_at_ms > 0),
            auth_revision INTEGER NOT NULL DEFAULT 1 CHECK(auth_revision > 0),
            preferences_json TEXT NOT NULL CHECK(
                length(preferences_json) <= 131072 AND json_valid(preferences_json)
            ),
            preferences_revision INTEGER NOT NULL DEFAULT 1 CHECK(preferences_revision > 0),
            created_at_ms INTEGER NOT NULL CHECK(created_at_ms > 0),
            updated_at_ms INTEGER NOT NULL CHECK(updated_at_ms > 0),
            CHECK(
                (primary_admin = 1 AND username = 'admin' AND tier = 'ADMIN')
                OR (primary_admin = 0 AND username <> 'admin')
            )
        )
        """;
    public static final String WEB_ACCESS_POLICY_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS web_access_policy (
            capability_id TEXT PRIMARY KEY CHECK(
                length(capability_id) BETWEEN 1 AND 64 AND capability_id = lower(capability_id)
            ),
            required_tier TEXT NOT NULL CHECK(required_tier IN ('PUBLIC', 'USER', 'ADMIN')),
            updated_at_ms INTEGER NOT NULL CHECK(updated_at_ms > 0)
        ) WITHOUT ROWID
        """;
    public static final String WEB_USER_PRIMARY_INDEX_SQL = """
        CREATE UNIQUE INDEX IF NOT EXISTS idx_web_user_one_primary_admin
        ON web_user(primary_admin)
        WHERE primary_admin = 1
        """;
    public static final String CONFIGURATION_RADRES_GUID_INDEX_SQL = """
        CREATE UNIQUE INDEX IF NOT EXISTS idx_configuration_channel_unique_radres_guid
        ON configuration_channel(lower(radres_guid))
        WHERE radres_guid IS NOT NULL AND length(trim(radres_guid)) > 0
        """;

    private Format5SchemaSql()
    {
    }

    public static void createConfigurationChannel(Statement statement) throws SQLException
    {
        statement.executeUpdate(CONFIGURATION_CHANNEL_TABLE_SQL);
    }

    public static void createWebSettings(Statement statement) throws SQLException
    {
        statement.executeUpdate(WEB_USER_TABLE_SQL);
        statement.executeUpdate(WEB_ACCESS_POLICY_TABLE_SQL);
        statement.executeUpdate(WEB_USER_PRIMARY_INDEX_SQL);
    }

    public static void createConfigurationIndexes(Statement statement) throws SQLException
    {
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_configuration_channel_sort " +
            "ON configuration_channel(sort_order, id)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_configuration_channel_alias_list " +
            "ON configuration_channel(alias_list_name)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_configuration_channel_decoder " +
            "ON configuration_channel(decoder_type)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_configuration_channel_frequency " +
            "ON configuration_channel(primary_frequency_hz)");
        statement.executeUpdate(CONFIGURATION_RADRES_GUID_INDEX_SQL);
    }
}
