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
package io.github.dsheirer.record;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Compact, retention-bound catalog for successfully published call recordings.
 *
 * <p>The catalog deliberately owns exactly two tables. Each managed recording directory has one bucket row that
 * stores its day and repeated system/site/channel/talkgroup hierarchy. Each retained audio file has one compact call
 * row containing timestamps, size, format, flags, the bucket reference, and the source-radio filter key. The audio
 * filename is derived from the call identity, completion timestamp, and format instead of being repeated in
 * SQLite.</p>
 *
 * <p>This schema is created only for a new global database by the single database startup owner. Existing databases
 * are validation-only and require an explicit backed-up external migration before this schema can be used.</p>
 */
public final class RecordedCallCatalogSchema
{
    public static final int SCHEMA_VERSION = 1;
    public static final String SCHEMA_VERSION_KEY = "recorded_call_catalog_schema_version";

    static final String RADIO_TIME_INDEX = "idx_recorded_call_radio_time";
    static final String DURATION_TIME_INDEX = "idx_recorded_call_duration_time";
    static final String BUCKET_TIME_INDEX = "idx_recorded_call_bucket_time";
    static final String BUCKET_SYSTEM_INDEX = "idx_recorded_call_bucket_system";
    static final String BUCKET_SITE_INDEX = "idx_recorded_call_bucket_site";
    static final String BUCKET_SYSTEM_SITE_INDEX = "idx_recorded_call_bucket_system_site";
    static final String BUCKET_CHANNEL_INDEX = "idx_recorded_call_bucket_channel";
    static final String BUCKET_SITE_CHANNEL_INDEX = "idx_recorded_call_bucket_site_channel";
    static final String BUCKET_TALKGROUP_INDEX = "idx_recorded_call_bucket_talkgroup";
    static final String BUCKET_TALKGROUP_VALUE_INDEX = "idx_recorded_call_bucket_talkgroup_value";

    private static final List<SqliteSchemaValidator.Table> TABLES = List.of(
        new SqliteSchemaValidator.Table("recorded_call_bucket", "id", "day_utc", "relative_directory",
            "system_key", "system_label", "site_key", "site_label", "channel_key", "channel_label",
            "talkgroup_key", "talkgroup_label"),
        new SqliteSchemaValidator.Table("recorded_call", "producer_id", "call_sequence", "timeslot",
            "completed_at_ms", "start_at_ms", "duration_ms", "byte_size", "format_code", "flags", "bucket_id",
            "source_radio_key")
    );
    private static final List<String> INDEXES = List.of(RADIO_TIME_INDEX, DURATION_TIME_INDEX, BUCKET_TIME_INDEX,
        BUCKET_SYSTEM_INDEX, BUCKET_SITE_INDEX, BUCKET_SYSTEM_SITE_INDEX, BUCKET_CHANNEL_INDEX,
        BUCKET_SITE_CHANNEL_INDEX, BUCKET_TALKGROUP_INDEX, BUCKET_TALKGROUP_VALUE_INDEX);

    private RecordedCallCatalogSchema()
    {
    }

    /**
     * Creates the current schema. Call only from the new-database startup routine or an explicit migration.
     */
    public static void create(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS recorded_call_bucket (
                    id INTEGER PRIMARY KEY,
                    day_utc INTEGER NOT NULL,
                    relative_directory TEXT NOT NULL UNIQUE,
                    system_key TEXT,
                    system_label TEXT,
                    site_key TEXT,
                    site_label TEXT,
                    channel_key TEXT,
                    channel_label TEXT,
                    talkgroup_key TEXT,
                    talkgroup_label TEXT,
                    CHECK(day_utc >= 0),
                    CHECK(length(relative_directory) BETWEEN 1 AND 192),
                    CHECK(system_key IS NULL OR length(system_key) BETWEEN 1 AND 512),
                    CHECK(system_label IS NULL OR length(system_label) <= 160),
                    CHECK(site_key IS NULL OR length(site_key) BETWEEN 1 AND 512),
                    CHECK(site_label IS NULL OR length(site_label) <= 160),
                    CHECK(channel_key IS NULL OR length(channel_key) BETWEEN 1 AND 512),
                    CHECK(channel_label IS NULL OR length(channel_label) <= 160),
                    CHECK(talkgroup_key IS NULL OR length(talkgroup_key) BETWEEN 1 AND 512),
                    CHECK(talkgroup_label IS NULL OR length(talkgroup_label) <= 160)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS recorded_call (
                    producer_id INTEGER NOT NULL,
                    call_sequence INTEGER NOT NULL,
                    timeslot INTEGER NOT NULL,
                    completed_at_ms INTEGER NOT NULL,
                    start_at_ms INTEGER NOT NULL,
                    duration_ms INTEGER NOT NULL,
                    byte_size INTEGER NOT NULL,
                    format_code INTEGER NOT NULL,
                    flags INTEGER NOT NULL DEFAULT 0,
                    bucket_id INTEGER NOT NULL REFERENCES recorded_call_bucket(id) ON DELETE RESTRICT,
                    source_radio_key TEXT,
                    PRIMARY KEY(completed_at_ms, producer_id, call_sequence, timeslot),
                    CHECK(completed_at_ms > 0),
                    CHECK(start_at_ms > 0 AND start_at_ms <= completed_at_ms),
                    CHECK(timeslot >= 0),
                    CHECK(duration_ms >= 0),
                    CHECK(byte_size > 0),
                    CHECK(format_code IN (1, 2)),
                    CHECK(flags BETWEEN 0 AND 3),
                    CHECK(source_radio_key IS NULL OR length(source_radio_key) BETWEEN 1 AND 512)
                ) WITHOUT ROWID
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_recorded_call_radio_time
                ON recorded_call(source_radio_key, completed_at_ms DESC,
                    producer_id DESC, call_sequence DESC, timeslot DESC)
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_recorded_call_duration_time
                ON recorded_call(duration_ms, completed_at_ms DESC)
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_recorded_call_bucket_time
                ON recorded_call(bucket_id, completed_at_ms DESC,
                    producer_id DESC, call_sequence DESC, timeslot DESC)
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_recorded_call_bucket_system
                ON recorded_call_bucket(system_key, day_utc, id)
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_recorded_call_bucket_site
                ON recorded_call_bucket(site_key, day_utc, id)
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_recorded_call_bucket_system_site
                ON recorded_call_bucket(system_key, site_key, id)
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_recorded_call_bucket_channel
                ON recorded_call_bucket(channel_key, day_utc, id)
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_recorded_call_bucket_site_channel
                ON recorded_call_bucket(site_key, channel_key, id)
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_recorded_call_bucket_talkgroup
                ON recorded_call_bucket(system_key, talkgroup_key, day_utc, id)
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_recorded_call_bucket_talkgroup_value
                ON recorded_call_bucket(talkgroup_key, day_utc, id)
                """);
        }

        SdrTrunkDatabaseStartup.setMetadata(connection, SCHEMA_VERSION_KEY, Integer.toString(SCHEMA_VERSION));
    }

    public static void validate(Connection connection) throws SQLException
    {
        SqliteSchemaValidator.validate(connection, TABLES, INDEXES, List.of(),
            List.of(new SqliteSchemaValidator.Metadata(SCHEMA_VERSION_KEY, Integer.toString(SCHEMA_VERSION))));
        validateCatalogObjects(connection);
        validatePrimaryKey(connection, "recorded_call",
            List.of("completed_at_ms", "producer_id", "call_sequence", "timeslot"));
        validateIndex(connection, RADIO_TIME_INDEX,
            List.of("source_radio_key", "completed_at_ms", "producer_id", "call_sequence", "timeslot"));
        validateIndex(connection, DURATION_TIME_INDEX, List.of("duration_ms", "completed_at_ms"));
        validateIndex(connection, BUCKET_TIME_INDEX,
            List.of("bucket_id", "completed_at_ms", "producer_id", "call_sequence", "timeslot"));
        validateIndex(connection, BUCKET_SYSTEM_INDEX, List.of("system_key", "day_utc", "id"));
        validateIndex(connection, BUCKET_SITE_INDEX, List.of("site_key", "day_utc", "id"));
        validateIndex(connection, BUCKET_SYSTEM_SITE_INDEX, List.of("system_key", "site_key", "id"));
        validateIndex(connection, BUCKET_CHANNEL_INDEX, List.of("channel_key", "day_utc", "id"));
        validateIndex(connection, BUCKET_SITE_CHANNEL_INDEX, List.of("site_key", "channel_key", "id"));
        validateIndex(connection, BUCKET_TALKGROUP_INDEX,
            List.of("system_key", "talkgroup_key", "day_utc", "id"));
        validateIndex(connection, BUCKET_TALKGROUP_VALUE_INDEX, List.of("talkgroup_key", "day_utc", "id"));
    }

    public static String schemaVersion(Connection connection) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT value FROM database_metadata WHERE key = ?"))
        {
            statement.setString(1, SCHEMA_VERSION_KEY);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static void validateCatalogObjects(Connection connection) throws SQLException
    {
        List<String> actual = new ArrayList<>();

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT type, name FROM sqlite_master
                WHERE name GLOB 'recorded_call*' OR name GLOB 'idx_recorded_call*'
                ORDER BY type, name
                """))
        {
            while(resultSet.next())
            {
                actual.add(resultSet.getString("type") + ':' + resultSet.getString("name"));
            }
        }

        List<String> expected = new ArrayList<>();
        INDEXES.stream().sorted().map(index -> "index:" + index).forEach(expected::add);
        expected.add("table:recorded_call");
        expected.add("table:recorded_call_bucket");

        if(!actual.equals(expected))
        {
            throw new SQLException("SQLite schema has incorrect recorded-call catalog objects: " + actual);
        }
    }

    private static void validatePrimaryKey(Connection connection, String table, List<String> expected)
        throws SQLException
    {
        TreeMap<Integer,String> ordered = new TreeMap<>();

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")"))
        {
            while(resultSet.next())
            {
                int position = resultSet.getInt("pk");

                if(position > 0)
                {
                    ordered.put(position, resultSet.getString("name"));
                }
            }
        }

        if(!new ArrayList<>(ordered.values()).equals(expected))
        {
            throw new SQLException("SQLite schema has incorrect primary key for [" + table + "]: " +
                ordered.values());
        }
    }

    private static void validateIndex(Connection connection, String index, List<String> expected)
        throws SQLException
    {
        List<String> actual = new ArrayList<>();

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA index_info(" + index + ")"))
        {
            while(resultSet.next())
            {
                actual.add(resultSet.getString("name"));
            }
        }

        if(!actual.equals(expected))
        {
            throw new SQLException("SQLite schema has incorrect columns for index [" + index + "]: " + actual);
        }
    }
}
