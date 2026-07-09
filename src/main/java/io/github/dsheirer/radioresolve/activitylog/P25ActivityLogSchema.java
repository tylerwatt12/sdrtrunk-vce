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

package io.github.dsheirer.radioresolve.activitylog;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * SQLite schema and writes for SDRTrunk receiver activity history.
 *
 * The v11 shape is summary-first. Detailed event rows are optional, while lifetime and hourly summaries are always
 * updated when stats logging is enabled. Table names are split by protocol family so DMR/NXDN can be added without
 * folding unrelated records into the P25 tables.
 */
public class P25ActivityLogSchema
{
    private static final int SCHEMA_VERSION = 11;
    private static final String SCHEMA_VERSION_KEY = "p25_activity_schema_version";
    private static final long HOUR_MILLISECONDS = 3_600_000L;
    private static final int NULL_TIMESLOT = -1;

    private static final int CONTEXT_TRUNKED_SITE = 1;
    private static final int CONTEXT_CONVENTIONAL_P25 = 2;
    private static final int CONTEXT_CONVENTIONAL_ANALOG = 10;

    private static final int PROTOCOL_UNKNOWN = 0;
    private static final int PROTOCOL_APCO25 = 1;
    private static final int PROTOCOL_APCO25_PHASE2 = 2;
    private static final int PROTOCOL_DMR = 3;
    private static final int PROTOCOL_NXDN = 4;
    private static final int PROTOCOL_NBFM = 10;
    private static final int PROTOCOL_AM = 11;

    private static final int TARGET_TALKGROUP = 1;
    private static final int TARGET_RADIO = 2;
    private static final int TARGET_PATCH_GROUP = 3;

    private static final List<P25ActivityLogRecords.Action> ACTIONS =
        Arrays.asList(P25ActivityLogRecords.Action.values());
    private static final List<String> ACTION_COUNT_COLUMNS = ACTIONS.stream()
        .map(action -> action.name().toLowerCase(Locale.ROOT) + "_count")
        .toList();
    private static final String ACTION_COUNT_DEFINITIONS = ACTION_COUNT_COLUMNS.stream()
        .map(column -> column + " INTEGER NOT NULL DEFAULT 0")
        .collect(Collectors.joining(",\n                    "));
    private static final String ACTION_INSERT_COLUMNS = String.join(", ", ACTION_COUNT_COLUMNS);
    private static final String ACTION_INSERT_PLACEHOLDERS = ACTION_COUNT_COLUMNS.stream()
        .map(column -> "?")
        .collect(Collectors.joining(", "));

    private P25ActivityLogSchema()
    {
    }

    public static boolean exists(Connection connection) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM database_metadata WHERE key = ?
            """))
        {
            statement.setString(1, SCHEMA_VERSION_KEY);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }

    public static void create(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS receiver_context (
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
                    wacn INTEGER,
                    system_id INTEGER,
                    nac INTEGER,
                    rfss INTEGER,
                    site INTEGER,
                    primary_frequency_hz INTEGER,
                    current_control_hz INTEGER
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS p25_activity_event (
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
                )
                """);
            createP25SummaryTables(statement);
            createConventionalTables(statement);
            createP25SiteTables(statement);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS logger_status (
                    key TEXT PRIMARY KEY,
                    value TEXT,
                    updated_at_ms INTEGER NOT NULL
                )
                """);
            createIndexesAndViews(statement);
        }

        SdrTrunkDatabaseStartup.setMetadata(connection, SCHEMA_VERSION_KEY, Integer.toString(SCHEMA_VERSION));
    }

    public static void validate(Connection connection) throws SQLException
    {
        SqliteSchemaValidator.validate(connection, TABLES, INDEXES, VIEWS,
            List.of(new SqliteSchemaValidator.Metadata(SCHEMA_VERSION_KEY, Integer.toString(SCHEMA_VERSION))));
    }

    static void recordActivity(Connection connection, P25ActivityLogRecords.ActivityEvent activity,
                               boolean detailedEventHistoryEnabled) throws SQLException
    {
        int contextId = upsertReceiverContext(connection, activity);

        if(isP25(activity))
        {
            if(detailedEventHistoryEnabled)
            {
                insertP25ActivityEvent(connection, activity, contextId);
            }

            upsertP25Summaries(connection, activity, contextId);
        }
        else if(activity.contextKind() == P25ActivityLogRecords.ContextKind.CONVENTIONAL_ANALOG)
        {
            upsertConventionalSummary(connection, activity, contextId);
        }
    }

    static void insertSite(Connection connection, P25ActivityLogRecords.SiteSnapshot snapshot) throws SQLException
    {
        upsertReceiverContext(connection, snapshot);
        upsertSiteSnapshot(connection, snapshot);
        upsertSiteChannels(connection, snapshot);
        upsertSiteFrequencyBands(connection, snapshot);
        upsertSiteNeighbors(connection, snapshot);
        upsertSitePatches(connection, snapshot);
        upsertSiteTalkerAliases(connection, snapshot);
    }

    static int deleteOlderThan(Connection connection, long cutoffEpochMilliseconds) throws SQLException
    {
        int deleted = 0;
        deleted += deleteByTime(connection, "p25_activity_event", "observed_at_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_talkgroup_activity_bucket", "bucket_start_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_radio_activity_bucket", "bucket_start_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_frequency_activity_bucket", "bucket_start_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "conventional_activity_bucket", "bucket_start_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_channel", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_frequency_band", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_neighbor", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_patch_group_talkgroup", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_patch_group_radio", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_patch_group", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_talker_alias", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_snapshot", "last_seen_ms", cutoffEpochMilliseconds);
        return deleted;
    }

    static int resetStats(Connection connection) throws SQLException
    {
        int deleted = 0;
        deleted += deleteAll(connection, "p25_activity_event");
        deleted += deleteAll(connection, "p25_talkgroup_activity_bucket");
        deleted += deleteAll(connection, "p25_radio_activity_bucket");
        deleted += deleteAll(connection, "p25_frequency_activity_bucket");
        deleted += deleteAll(connection, "p25_talkgroup_summary");
        deleted += deleteAll(connection, "p25_radio_summary");
        deleted += deleteAll(connection, "p25_frequency_summary");
        deleted += deleteAll(connection, "conventional_activity_bucket");
        deleted += deleteAll(connection, "conventional_activity_summary");
        deleted += deleteAll(connection, "p25_site_talker_alias");
        deleted += deleteAll(connection, "p25_site_patch_group_radio");
        deleted += deleteAll(connection, "p25_site_patch_group_talkgroup");
        deleted += deleteAll(connection, "p25_site_patch_group");
        deleted += deleteAll(connection, "p25_site_neighbor");
        deleted += deleteAll(connection, "p25_site_frequency_band");
        deleted += deleteAll(connection, "p25_site_channel");
        deleted += deleteAll(connection, "p25_site_snapshot");
        deleted += deleteAll(connection, "receiver_context");
        deleted += deleteAll(connection, "logger_status");
        return deleted;
    }

    static void updateStatus(Connection connection, String key, String value) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO logger_status (key, value, updated_at_ms)
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

    private static void createP25SummaryTables(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_talkgroup_summary (
                context_id INTEGER NOT NULL,
                talkgroup_id INTEGER NOT NULL,
                target_kind_code INTEGER,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                hits INTEGER NOT NULL DEFAULT 0,
                %s,
                encrypted_count INTEGER NOT NULL DEFAULT 0,
                last_source_radio_id INTEGER,
                last_encryption_algorithm_id INTEGER,
                last_encryption_key_id INTEGER,
                PRIMARY KEY(context_id, talkgroup_id)
            )
            """.formatted(ACTION_COUNT_DEFINITIONS));
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_radio_summary (
                context_id INTEGER NOT NULL,
                radio_id INTEGER NOT NULL,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                hits INTEGER NOT NULL DEFAULT 0,
                %s,
                encrypted_count INTEGER NOT NULL DEFAULT 0,
                last_talkgroup_id INTEGER,
                last_talker_alias TEXT,
                last_encryption_algorithm_id INTEGER,
                last_encryption_key_id INTEGER,
                PRIMARY KEY(context_id, radio_id)
            )
            """.formatted(ACTION_COUNT_DEFINITIONS));
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_frequency_summary (
                context_id INTEGER NOT NULL,
                frequency_hz INTEGER NOT NULL,
                timeslot INTEGER NOT NULL DEFAULT -1,
                lcn_band INTEGER,
                lcn_number INTEGER,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                hits INTEGER NOT NULL DEFAULT 0,
                %s,
                encrypted_count INTEGER NOT NULL DEFAULT 0,
                last_source_radio_id INTEGER,
                last_target_id INTEGER,
                last_encryption_algorithm_id INTEGER,
                last_encryption_key_id INTEGER,
                PRIMARY KEY(context_id, frequency_hz, timeslot)
            )
            """.formatted(ACTION_COUNT_DEFINITIONS));
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_talkgroup_activity_bucket (
                context_id INTEGER NOT NULL,
                talkgroup_id INTEGER NOT NULL,
                bucket_start_ms INTEGER NOT NULL,
                hits INTEGER NOT NULL DEFAULT 0,
                %s,
                encrypted_count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(context_id, talkgroup_id, bucket_start_ms)
            )
            """.formatted(ACTION_COUNT_DEFINITIONS));
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_radio_activity_bucket (
                context_id INTEGER NOT NULL,
                radio_id INTEGER NOT NULL,
                bucket_start_ms INTEGER NOT NULL,
                hits INTEGER NOT NULL DEFAULT 0,
                %s,
                encrypted_count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(context_id, radio_id, bucket_start_ms)
            )
            """.formatted(ACTION_COUNT_DEFINITIONS));
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_frequency_activity_bucket (
                context_id INTEGER NOT NULL,
                frequency_hz INTEGER NOT NULL,
                timeslot INTEGER NOT NULL DEFAULT -1,
                bucket_start_ms INTEGER NOT NULL,
                hits INTEGER NOT NULL DEFAULT 0,
                %s,
                encrypted_count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(context_id, frequency_hz, timeslot, bucket_start_ms)
            )
            """.formatted(ACTION_COUNT_DEFINITIONS));
    }

    private static void createConventionalTables(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS conventional_activity_summary (
                context_id INTEGER NOT NULL,
                frequency_hz INTEGER NOT NULL,
                timeslot INTEGER NOT NULL DEFAULT -1,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                hits INTEGER NOT NULL DEFAULT 0,
                %s,
                last_event_type_code INTEGER,
                PRIMARY KEY(context_id, frequency_hz, timeslot)
            )
            """.formatted(ACTION_COUNT_DEFINITIONS));
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS conventional_activity_bucket (
                context_id INTEGER NOT NULL,
                frequency_hz INTEGER NOT NULL,
                timeslot INTEGER NOT NULL DEFAULT -1,
                bucket_start_ms INTEGER NOT NULL,
                hits INTEGER NOT NULL DEFAULT 0,
                %s,
                PRIMARY KEY(context_id, frequency_hz, timeslot, bucket_start_ms)
            )
            """.formatted(ACTION_COUNT_DEFINITIONS));
    }

    private static void createP25SiteTables(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_snapshot (
                guid TEXT PRIMARY KEY,
                snapshot_hash TEXT,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                seen_count INTEGER NOT NULL DEFAULT 1,
                protocol TEXT,
                channel_name TEXT,
                alias_list_name TEXT,
                decoder TEXT,
                wacn INTEGER,
                system_id INTEGER,
                nac INTEGER,
                rfss INTEGER,
                site INTEGER,
                primary_frequency_hz INTEGER,
                current_control_hz INTEGER
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_channel (
                guid TEXT NOT NULL,
                channel_key TEXT NOT NULL,
                descriptor TEXT,
                role TEXT,
                downlink_hz INTEGER,
                uplink_hz INTEGER,
                tdma INTEGER,
                timeslots INTEGER,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                seen_count INTEGER NOT NULL DEFAULT 1,
                primary_control_seen INTEGER NOT NULL DEFAULT 0,
                alternate_control_seen INTEGER NOT NULL DEFAULT 0,
                traffic_seen INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(guid, channel_key)
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_frequency_band (
                guid TEXT NOT NULL,
                band INTEGER NOT NULL,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                seen_count INTEGER NOT NULL DEFAULT 1,
                tdma INTEGER,
                base_hz INTEGER,
                bandwidth INTEGER,
                spacing_hz INTEGER,
                transmit_offset_hz INTEGER,
                timeslots INTEGER,
                PRIMARY KEY(guid, band)
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_neighbor (
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
                seen_count INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(guid, neighbor_key)
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_patch_group (
                guid TEXT NOT NULL,
                patch_group INTEGER NOT NULL,
                version INTEGER,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                seen_count INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(guid, patch_group)
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_patch_group_talkgroup (
                guid TEXT NOT NULL,
                patch_group INTEGER NOT NULL,
                talkgroup_id INTEGER NOT NULL,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                seen_count INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(guid, patch_group, talkgroup_id)
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_patch_group_radio (
                guid TEXT NOT NULL,
                patch_group INTEGER NOT NULL,
                radio_id INTEGER NOT NULL,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                seen_count INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(guid, patch_group, radio_id)
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_talker_alias (
                guid TEXT NOT NULL,
                radio_id INTEGER NOT NULL,
                alias TEXT,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                seen_count INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(guid, radio_id)
            )
            """);
    }

    private static void createIndexesAndViews(Statement statement) throws SQLException
    {
        statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_receiver_context_guid ON receiver_context(guid) WHERE guid IS NOT NULL");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_activity_event_context_time ON p25_activity_event(context_id, observed_at_ms)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_activity_event_target_time ON p25_activity_event(target_id, observed_at_ms) WHERE target_id IS NOT NULL");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_activity_event_source_time ON p25_activity_event(source_radio_id, observed_at_ms) WHERE source_radio_id IS NOT NULL");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_activity_event_frequency_time ON p25_activity_event(frequency_hz, observed_at_ms) WHERE frequency_hz IS NOT NULL");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_activity_event_encryption ON p25_activity_event(encryption_algorithm_id, encryption_key_id, observed_at_ms) WHERE encrypted = 1");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_talkgroup_bucket_time ON p25_talkgroup_activity_bucket(context_id, bucket_start_ms)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_talkgroup_bucket_talkgroup_time ON p25_talkgroup_activity_bucket(talkgroup_id, bucket_start_ms)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_radio_bucket_time ON p25_radio_activity_bucket(context_id, bucket_start_ms)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_frequency_bucket_time ON p25_frequency_activity_bucket(context_id, bucket_start_ms)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_conventional_bucket_time ON conventional_activity_bucket(context_id, bucket_start_ms)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_snapshot_identity ON p25_site_snapshot(wacn, system_id, rfss, site)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_channel_guid_frequency ON p25_site_channel(guid, downlink_hz)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_neighbor_guid_site ON p25_site_neighbor(guid, system_id, rfss, site)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_patch_talkgroup ON p25_site_patch_group_talkgroup(talkgroup_id, guid)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_patch_radio ON p25_site_patch_group_radio(radio_id, guid)");
        statement.executeUpdate(createResolvedViewSql());
    }

    private static final List<SqliteSchemaValidator.Table> TABLES = List.of(
        table("receiver_context", "id", "context_key", "guid", "kind_code", "protocol_code", "channel_name",
            "alias_list_name", "decoder", "first_seen_ms", "last_seen_ms", "wacn", "system_id", "nac", "rfss",
            "site", "primary_frequency_hz", "current_control_hz"),
        table("p25_activity_event", "id", "context_id", "observed_at_ms", "action_code", "event_type_code",
            "source_radio_id", "target_id", "target_kind_code", "frequency_hz", "lcn_band", "lcn_number",
            "timeslot", "encrypted", "encryption_algorithm_id", "encryption_key_id"),
        tableWithActions("p25_talkgroup_summary", "context_id", "talkgroup_id", "target_kind_code",
            "first_seen_ms", "last_seen_ms", "hits", "encrypted_count", "last_source_radio_id",
            "last_encryption_algorithm_id", "last_encryption_key_id"),
        tableWithActions("p25_radio_summary", "context_id", "radio_id", "first_seen_ms", "last_seen_ms", "hits",
            "encrypted_count", "last_talkgroup_id", "last_talker_alias", "last_encryption_algorithm_id",
            "last_encryption_key_id"),
        tableWithActions("p25_frequency_summary", "context_id", "frequency_hz", "timeslot", "lcn_band",
            "lcn_number", "first_seen_ms", "last_seen_ms", "hits", "encrypted_count", "last_source_radio_id",
            "last_target_id", "last_encryption_algorithm_id", "last_encryption_key_id"),
        tableWithActions("p25_talkgroup_activity_bucket", "context_id", "talkgroup_id", "bucket_start_ms", "hits",
            "encrypted_count"),
        tableWithActions("p25_radio_activity_bucket", "context_id", "radio_id", "bucket_start_ms", "hits",
            "encrypted_count"),
        tableWithActions("p25_frequency_activity_bucket", "context_id", "frequency_hz", "timeslot",
            "bucket_start_ms", "hits", "encrypted_count"),
        tableWithActions("conventional_activity_summary", "context_id", "frequency_hz", "timeslot",
            "first_seen_ms", "last_seen_ms", "hits", "last_event_type_code"),
        tableWithActions("conventional_activity_bucket", "context_id", "frequency_hz", "timeslot",
            "bucket_start_ms", "hits"),
        table("p25_site_snapshot", "guid", "snapshot_hash", "first_seen_ms", "last_seen_ms", "seen_count",
            "protocol", "channel_name", "alias_list_name", "decoder", "wacn", "system_id", "nac", "rfss", "site",
            "primary_frequency_hz", "current_control_hz"),
        table("p25_site_channel", "guid", "channel_key", "descriptor", "role", "downlink_hz", "uplink_hz",
            "tdma", "timeslots", "first_seen_ms", "last_seen_ms", "seen_count", "primary_control_seen",
            "alternate_control_seen", "traffic_seen"),
        table("p25_site_frequency_band", "guid", "band", "first_seen_ms", "last_seen_ms", "seen_count", "tdma",
            "base_hz", "bandwidth", "spacing_hz", "transmit_offset_hz", "timeslots"),
        table("p25_site_neighbor", "guid", "neighbor_key", "system_id", "rfss", "site", "lra",
            "channel_descriptor", "downlink_hz", "uplink_hz", "status", "first_seen_ms", "last_seen_ms",
            "seen_count"),
        table("p25_site_patch_group", "guid", "patch_group", "version", "first_seen_ms", "last_seen_ms",
            "seen_count"),
        table("p25_site_patch_group_talkgroup", "guid", "patch_group", "talkgroup_id", "first_seen_ms",
            "last_seen_ms", "seen_count"),
        table("p25_site_patch_group_radio", "guid", "patch_group", "radio_id", "first_seen_ms", "last_seen_ms",
            "seen_count"),
        table("p25_site_talker_alias", "guid", "radio_id", "alias", "first_seen_ms", "last_seen_ms",
            "seen_count"),
        table("logger_status", "key", "value", "updated_at_ms")
    );

    private static final List<String> INDEXES = List.of(
        "idx_receiver_context_guid",
        "idx_p25_activity_event_context_time",
        "idx_p25_activity_event_target_time",
        "idx_p25_activity_event_source_time",
        "idx_p25_activity_event_frequency_time",
        "idx_p25_activity_event_encryption",
        "idx_p25_talkgroup_bucket_time",
        "idx_p25_talkgroup_bucket_talkgroup_time",
        "idx_p25_radio_bucket_time",
        "idx_p25_frequency_bucket_time",
        "idx_conventional_bucket_time",
        "idx_p25_site_snapshot_identity",
        "idx_p25_site_channel_guid_frequency",
        "idx_p25_site_neighbor_guid_site",
        "idx_p25_site_patch_talkgroup",
        "idx_p25_site_patch_radio"
    );

    private static final List<String> VIEWS = List.of("p25_activity_event_resolved");

    private static void upsertP25Summaries(Connection connection, P25ActivityLogRecords.ActivityEvent activity,
                                           int contextId) throws SQLException
    {
        Integer sourceRadio = parseInteger(activity.sourceRadioId());
        Integer target = parseInteger(activity.targetId());

        if(target != null && isTalkgroup(activity.targetKind()))
        {
            upsertP25TalkgroupSummary(connection, activity, contextId, target, sourceRadio);
            upsertP25TalkgroupBucket(connection, activity, contextId, target);
        }

        if(sourceRadio != null)
        {
            upsertP25RadioSummary(connection, activity, contextId, sourceRadio, target);
            upsertP25RadioBucket(connection, activity, contextId, sourceRadio);
        }

        if(activity.frequencyHertz() != null && activity.frequencyHertz() > 0)
        {
            upsertP25FrequencySummary(connection, activity, contextId, sourceRadio, target);
            upsertP25FrequencyBucket(connection, activity, contextId);
        }
    }

    private static void insertP25ActivityEvent(Connection connection, P25ActivityLogRecords.ActivityEvent activity,
                                               int contextId) throws SQLException
    {
        Lcn lcn = Lcn.parse(activity.lcn());

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_activity_event (
                context_id, observed_at_ms, action_code, event_type_code, source_radio_id, target_id, target_kind_code,
                frequency_hz, lcn_band, lcn_number, timeslot, encrypted, encryption_algorithm_id, encryption_key_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """))
        {
            statement.setInt(1, contextId);
            statement.setLong(2, activity.observedAtEpochMilliseconds());
            statement.setInt(3, actionCode(activity.action()));
            setInteger(statement, 4, eventTypeCode(activity.eventType()));
            setInteger(statement, 5, parseInteger(activity.sourceRadioId()));
            setInteger(statement, 6, parseInteger(activity.targetId()));
            setInteger(statement, 7, targetKindCode(activity.targetKind()));
            setLong(statement, 8, activity.frequencyHertz());
            setInteger(statement, 9, lcn.band());
            setInteger(statement, 10, lcn.number());
            setInteger(statement, 11, activity.timeslot());
            statement.setInt(12, activity.encrypted() ? 1 : 0);
            setInteger(statement, 13, activity.encryptionAlgorithmId());
            setInteger(statement, 14, activity.encryptionKeyId());
            statement.executeUpdate();
        }
    }

    private static void upsertP25TalkgroupSummary(Connection connection, P25ActivityLogRecords.ActivityEvent activity,
                                                  int contextId, int talkgroup, Integer sourceRadio)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_talkgroup_summary (
                context_id, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms, hits, %s,
                encrypted_count, last_source_radio_id, last_encryption_algorithm_id, last_encryption_key_id
            ) VALUES (?, ?, ?, ?, ?, 1, %s, ?, ?, ?, ?)
            ON CONFLICT(context_id, talkgroup_id) DO UPDATE SET
                target_kind_code = coalesce(excluded.target_kind_code, p25_talkgroup_summary.target_kind_code),
                last_seen_ms = max(p25_talkgroup_summary.last_seen_ms, excluded.last_seen_ms),
                hits = p25_talkgroup_summary.hits + 1,
                %s,
                encrypted_count = p25_talkgroup_summary.encrypted_count + excluded.encrypted_count,
                last_source_radio_id = coalesce(excluded.last_source_radio_id, p25_talkgroup_summary.last_source_radio_id),
                last_encryption_algorithm_id = coalesce(excluded.last_encryption_algorithm_id, p25_talkgroup_summary.last_encryption_algorithm_id),
                last_encryption_key_id = coalesce(excluded.last_encryption_key_id, p25_talkgroup_summary.last_encryption_key_id)
            """.formatted(ACTION_INSERT_COLUMNS, ACTION_INSERT_PLACEHOLDERS, actionUpdateSql("p25_talkgroup_summary"))))
        {
            int index = 1;
            statement.setInt(index++, contextId);
            statement.setInt(index++, talkgroup);
            setInteger(statement, index++, targetKindCode(activity.targetKind()));
            statement.setLong(index++, activity.observedAtEpochMilliseconds());
            statement.setLong(index++, activity.observedAtEpochMilliseconds());
            index = setActionCounts(statement, index, activity);
            statement.setInt(index++, activity.encrypted() ? 1 : 0);
            setInteger(statement, index++, sourceRadio);
            setInteger(statement, index++, activity.encryptionAlgorithmId());
            setInteger(statement, index, activity.encryptionKeyId());
            statement.executeUpdate();
        }
    }

    private static void upsertP25TalkgroupBucket(Connection connection, P25ActivityLogRecords.ActivityEvent activity,
                                                 int contextId, int talkgroup) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_talkgroup_activity_bucket (
                context_id, talkgroup_id, bucket_start_ms, hits, %s, encrypted_count
            ) VALUES (?, ?, ?, 1, %s, ?)
            ON CONFLICT(context_id, talkgroup_id, bucket_start_ms) DO UPDATE SET
                hits = p25_talkgroup_activity_bucket.hits + 1,
                %s,
                encrypted_count = p25_talkgroup_activity_bucket.encrypted_count + excluded.encrypted_count
            """.formatted(ACTION_INSERT_COLUMNS, ACTION_INSERT_PLACEHOLDERS,
            actionUpdateSql("p25_talkgroup_activity_bucket"))))
        {
            int index = 1;
            statement.setInt(index++, contextId);
            statement.setInt(index++, talkgroup);
            statement.setLong(index++, bucketStart(activity.observedAtEpochMilliseconds()));
            index = setActionCounts(statement, index, activity);
            statement.setInt(index, activity.encrypted() ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private static void upsertP25RadioSummary(Connection connection, P25ActivityLogRecords.ActivityEvent activity,
                                              int contextId, int radio, Integer target) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_radio_summary (
                context_id, radio_id, first_seen_ms, last_seen_ms, hits, %s, encrypted_count, last_talkgroup_id,
                last_talker_alias, last_encryption_algorithm_id, last_encryption_key_id
            ) VALUES (?, ?, ?, ?, 1, %s, ?, ?, ?, ?, ?)
            ON CONFLICT(context_id, radio_id) DO UPDATE SET
                last_seen_ms = max(p25_radio_summary.last_seen_ms, excluded.last_seen_ms),
                hits = p25_radio_summary.hits + 1,
                %s,
                encrypted_count = p25_radio_summary.encrypted_count + excluded.encrypted_count,
                last_talkgroup_id = coalesce(excluded.last_talkgroup_id, p25_radio_summary.last_talkgroup_id),
                last_talker_alias = coalesce(excluded.last_talker_alias, p25_radio_summary.last_talker_alias),
                last_encryption_algorithm_id = coalesce(excluded.last_encryption_algorithm_id, p25_radio_summary.last_encryption_algorithm_id),
                last_encryption_key_id = coalesce(excluded.last_encryption_key_id, p25_radio_summary.last_encryption_key_id)
            """.formatted(ACTION_INSERT_COLUMNS, ACTION_INSERT_PLACEHOLDERS, actionUpdateSql("p25_radio_summary"))))
        {
            int index = 1;
            statement.setInt(index++, contextId);
            statement.setInt(index++, radio);
            statement.setLong(index++, activity.observedAtEpochMilliseconds());
            statement.setLong(index++, activity.observedAtEpochMilliseconds());
            index = setActionCounts(statement, index, activity);
            statement.setInt(index++, activity.encrypted() ? 1 : 0);
            setInteger(statement, index++, isTalkgroup(activity.targetKind()) ? target : null);
            statement.setString(index++, activity.talkerAlias());
            setInteger(statement, index++, activity.encryptionAlgorithmId());
            setInteger(statement, index, activity.encryptionKeyId());
            statement.executeUpdate();
        }
    }

    private static void upsertP25RadioBucket(Connection connection, P25ActivityLogRecords.ActivityEvent activity,
                                             int contextId, int radio) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_radio_activity_bucket (
                context_id, radio_id, bucket_start_ms, hits, %s, encrypted_count
            ) VALUES (?, ?, ?, 1, %s, ?)
            ON CONFLICT(context_id, radio_id, bucket_start_ms) DO UPDATE SET
                hits = p25_radio_activity_bucket.hits + 1,
                %s,
                encrypted_count = p25_radio_activity_bucket.encrypted_count + excluded.encrypted_count
            """.formatted(ACTION_INSERT_COLUMNS, ACTION_INSERT_PLACEHOLDERS,
            actionUpdateSql("p25_radio_activity_bucket"))))
        {
            int index = 1;
            statement.setInt(index++, contextId);
            statement.setInt(index++, radio);
            statement.setLong(index++, bucketStart(activity.observedAtEpochMilliseconds()));
            index = setActionCounts(statement, index, activity);
            statement.setInt(index, activity.encrypted() ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private static void upsertP25FrequencySummary(Connection connection, P25ActivityLogRecords.ActivityEvent activity,
                                                  int contextId, Integer sourceRadio, Integer target)
        throws SQLException
    {
        int timeslot = summaryTimeslot(activity.timeslot());
        Lcn lcn = Lcn.parse(activity.lcn());

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_frequency_summary (
                context_id, frequency_hz, timeslot, lcn_band, lcn_number, first_seen_ms, last_seen_ms, hits, %s,
                encrypted_count, last_source_radio_id, last_target_id, last_encryption_algorithm_id,
                last_encryption_key_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, %s, ?, ?, ?, ?, ?)
            ON CONFLICT(context_id, frequency_hz, timeslot) DO UPDATE SET
                lcn_band = coalesce(excluded.lcn_band, p25_frequency_summary.lcn_band),
                lcn_number = coalesce(excluded.lcn_number, p25_frequency_summary.lcn_number),
                last_seen_ms = max(p25_frequency_summary.last_seen_ms, excluded.last_seen_ms),
                hits = p25_frequency_summary.hits + 1,
                %s,
                encrypted_count = p25_frequency_summary.encrypted_count + excluded.encrypted_count,
                last_source_radio_id = coalesce(excluded.last_source_radio_id, p25_frequency_summary.last_source_radio_id),
                last_target_id = coalesce(excluded.last_target_id, p25_frequency_summary.last_target_id),
                last_encryption_algorithm_id = coalesce(excluded.last_encryption_algorithm_id, p25_frequency_summary.last_encryption_algorithm_id),
                last_encryption_key_id = coalesce(excluded.last_encryption_key_id, p25_frequency_summary.last_encryption_key_id)
            """.formatted(ACTION_INSERT_COLUMNS, ACTION_INSERT_PLACEHOLDERS, actionUpdateSql("p25_frequency_summary"))))
        {
            int index = 1;
            statement.setInt(index++, contextId);
            statement.setLong(index++, activity.frequencyHertz());
            statement.setInt(index++, timeslot);
            setInteger(statement, index++, lcn.band());
            setInteger(statement, index++, lcn.number());
            statement.setLong(index++, activity.observedAtEpochMilliseconds());
            statement.setLong(index++, activity.observedAtEpochMilliseconds());
            index = setActionCounts(statement, index, activity);
            statement.setInt(index++, activity.encrypted() ? 1 : 0);
            setInteger(statement, index++, sourceRadio);
            setInteger(statement, index++, target);
            setInteger(statement, index++, activity.encryptionAlgorithmId());
            setInteger(statement, index, activity.encryptionKeyId());
            statement.executeUpdate();
        }
    }

    private static void upsertP25FrequencyBucket(Connection connection, P25ActivityLogRecords.ActivityEvent activity,
                                                 int contextId) throws SQLException
    {
        int timeslot = summaryTimeslot(activity.timeslot());

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_frequency_activity_bucket (
                context_id, frequency_hz, timeslot, bucket_start_ms, hits, %s, encrypted_count
            ) VALUES (?, ?, ?, ?, 1, %s, ?)
            ON CONFLICT(context_id, frequency_hz, timeslot, bucket_start_ms) DO UPDATE SET
                hits = p25_frequency_activity_bucket.hits + 1,
                %s,
                encrypted_count = p25_frequency_activity_bucket.encrypted_count + excluded.encrypted_count
            """.formatted(ACTION_INSERT_COLUMNS, ACTION_INSERT_PLACEHOLDERS,
            actionUpdateSql("p25_frequency_activity_bucket"))))
        {
            int index = 1;
            statement.setInt(index++, contextId);
            statement.setLong(index++, activity.frequencyHertz());
            statement.setInt(index++, timeslot);
            statement.setLong(index++, bucketStart(activity.observedAtEpochMilliseconds()));
            index = setActionCounts(statement, index, activity);
            statement.setInt(index, activity.encrypted() ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private static void upsertConventionalSummary(Connection connection, P25ActivityLogRecords.ActivityEvent activity,
                                                  int contextId) throws SQLException
    {
        if(activity.frequencyHertz() == null || activity.frequencyHertz() <= 0)
        {
            return;
        }

        int timeslot = summaryTimeslot(activity.timeslot());

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO conventional_activity_summary (
                context_id, frequency_hz, timeslot, first_seen_ms, last_seen_ms, hits, %s, last_event_type_code
            ) VALUES (?, ?, ?, ?, ?, 1, %s, ?)
            ON CONFLICT(context_id, frequency_hz, timeslot) DO UPDATE SET
                last_seen_ms = max(conventional_activity_summary.last_seen_ms, excluded.last_seen_ms),
                hits = conventional_activity_summary.hits + 1,
                %s,
                last_event_type_code = coalesce(excluded.last_event_type_code, conventional_activity_summary.last_event_type_code)
            """.formatted(ACTION_INSERT_COLUMNS, ACTION_INSERT_PLACEHOLDERS,
            actionUpdateSql("conventional_activity_summary"))))
        {
            int index = 1;
            statement.setInt(index++, contextId);
            statement.setLong(index++, activity.frequencyHertz());
            statement.setInt(index++, timeslot);
            statement.setLong(index++, activity.observedAtEpochMilliseconds());
            statement.setLong(index++, activity.observedAtEpochMilliseconds());
            index = setActionCounts(statement, index, activity);
            setInteger(statement, index, eventTypeCode(activity.eventType()));
            statement.executeUpdate();
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO conventional_activity_bucket (
                context_id, frequency_hz, timeslot, bucket_start_ms, hits, %s
            ) VALUES (?, ?, ?, ?, 1, %s)
            ON CONFLICT(context_id, frequency_hz, timeslot, bucket_start_ms) DO UPDATE SET
                hits = conventional_activity_bucket.hits + 1,
                %s
            """.formatted(ACTION_INSERT_COLUMNS, ACTION_INSERT_PLACEHOLDERS,
            actionUpdateSql("conventional_activity_bucket"))))
        {
            int index = 1;
            statement.setInt(index++, contextId);
            statement.setLong(index++, activity.frequencyHertz());
            statement.setInt(index++, timeslot);
            statement.setLong(index++, bucketStart(activity.observedAtEpochMilliseconds()));
            setActionCounts(statement, index, activity);
            statement.executeUpdate();
        }
    }

    private static int upsertReceiverContext(Connection connection, P25ActivityLogRecords.ActivityEvent activity)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO receiver_context (
                context_key, guid, kind_code, protocol_code, channel_name, decoder, first_seen_ms, last_seen_ms, wacn,
                system_id, nac, rfss, site, primary_frequency_hz, current_control_hz
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
            ON CONFLICT(context_key) DO UPDATE SET
                guid = coalesce(excluded.guid, receiver_context.guid),
                kind_code = excluded.kind_code,
                protocol_code = coalesce(excluded.protocol_code, receiver_context.protocol_code),
                channel_name = coalesce(excluded.channel_name, receiver_context.channel_name),
                decoder = coalesce(excluded.decoder, receiver_context.decoder),
                last_seen_ms = max(receiver_context.last_seen_ms, excluded.last_seen_ms),
                wacn = coalesce(excluded.wacn, receiver_context.wacn),
                system_id = coalesce(excluded.system_id, receiver_context.system_id),
                nac = coalesce(excluded.nac, receiver_context.nac),
                rfss = coalesce(excluded.rfss, receiver_context.rfss),
                site = coalesce(excluded.site, receiver_context.site),
                primary_frequency_hz = coalesce(excluded.primary_frequency_hz, receiver_context.primary_frequency_hz)
            """))
        {
            statement.setString(1, activity.contextKey());
            statement.setString(2, activity.guid());
            statement.setInt(3, contextKindCode(activity.contextKind()));
            setInteger(statement, 4, protocolCode(activity.protocol()));
            statement.setString(5,
                activity.contextKind() != P25ActivityLogRecords.ContextKind.TRUNKED_SITE ? activity.channelName() : null);
            statement.setString(6, activity.decoder());
            statement.setLong(7, activity.observedAtEpochMilliseconds());
            statement.setLong(8, activity.observedAtEpochMilliseconds());
            setInteger(statement, 9, activity.wacn());
            setInteger(statement, 10, activity.systemId());
            setInteger(statement, 11, activity.nac());
            setInteger(statement, 12, activity.rfss());
            setInteger(statement, 13, activity.site());
            setLong(statement, 14, isConventional(activity.contextKind()) ? activity.frequencyHertz() : null);
            statement.executeUpdate();
        }

        return selectContextId(connection, activity.contextKey());
    }

    private static int upsertReceiverContext(Connection connection, P25ActivityLogRecords.SiteSnapshot snapshot)
        throws SQLException
    {
        String contextKey = guidContextKey(snapshot.guid());

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO receiver_context (
                context_key, guid, kind_code, protocol_code, channel_name, alias_list_name, decoder, first_seen_ms,
                last_seen_ms, wacn, system_id, nac, rfss, site, primary_frequency_hz, current_control_hz
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(context_key) DO UPDATE SET
                guid = coalesce(excluded.guid, receiver_context.guid),
                kind_code = excluded.kind_code,
                protocol_code = coalesce(excluded.protocol_code, receiver_context.protocol_code),
                channel_name = coalesce(excluded.channel_name, receiver_context.channel_name),
                alias_list_name = coalesce(excluded.alias_list_name, receiver_context.alias_list_name),
                decoder = coalesce(excluded.decoder, receiver_context.decoder),
                last_seen_ms = max(receiver_context.last_seen_ms, excluded.last_seen_ms),
                wacn = coalesce(excluded.wacn, receiver_context.wacn),
                system_id = coalesce(excluded.system_id, receiver_context.system_id),
                nac = coalesce(excluded.nac, receiver_context.nac),
                rfss = coalesce(excluded.rfss, receiver_context.rfss),
                site = coalesce(excluded.site, receiver_context.site),
                primary_frequency_hz = coalesce(excluded.primary_frequency_hz, receiver_context.primary_frequency_hz),
                current_control_hz = coalesce(excluded.current_control_hz, receiver_context.current_control_hz)
            """))
        {
            statement.setString(1, contextKey);
            statement.setString(2, snapshot.guid());
            statement.setInt(3, contextKindCode(snapshot.contextKind()));
            setInteger(statement, 4, protocolCode(snapshot.protocol()));
            statement.setString(5, snapshot.channelName());
            statement.setString(6, snapshot.aliasListName());
            statement.setString(7, snapshot.decoder());
            statement.setLong(8, snapshot.observedAtEpochMilliseconds());
            statement.setLong(9, snapshot.observedAtEpochMilliseconds());
            setInteger(statement, 10, snapshot.wacn());
            setInteger(statement, 11, snapshot.systemId());
            setInteger(statement, 12, snapshot.nac());
            setInteger(statement, 13, snapshot.rfss());
            setInteger(statement, 14, snapshot.site());
            setLong(statement, 15, snapshot.primaryFrequencyHertz());
            setLong(statement, 16, snapshot.currentControlHertz());
            statement.executeUpdate();
        }

        return selectContextId(connection, contextKey);
    }

    private static void upsertSiteSnapshot(Connection connection, P25ActivityLogRecords.SiteSnapshot snapshot)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_site_snapshot (
                guid, snapshot_hash, first_seen_ms, last_seen_ms, seen_count, protocol, channel_name,
                alias_list_name, decoder, wacn, system_id, nac, rfss, site, primary_frequency_hz, current_control_hz
            ) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(guid) DO UPDATE SET
                snapshot_hash = coalesce(excluded.snapshot_hash, p25_site_snapshot.snapshot_hash),
                last_seen_ms = excluded.last_seen_ms,
                seen_count = p25_site_snapshot.seen_count + 1,
                protocol = coalesce(excluded.protocol, p25_site_snapshot.protocol),
                channel_name = coalesce(excluded.channel_name, p25_site_snapshot.channel_name),
                alias_list_name = coalesce(excluded.alias_list_name, p25_site_snapshot.alias_list_name),
                decoder = coalesce(excluded.decoder, p25_site_snapshot.decoder),
                wacn = coalesce(excluded.wacn, p25_site_snapshot.wacn),
                system_id = coalesce(excluded.system_id, p25_site_snapshot.system_id),
                nac = coalesce(excluded.nac, p25_site_snapshot.nac),
                rfss = coalesce(excluded.rfss, p25_site_snapshot.rfss),
                site = coalesce(excluded.site, p25_site_snapshot.site),
                primary_frequency_hz = coalesce(excluded.primary_frequency_hz, p25_site_snapshot.primary_frequency_hz),
                current_control_hz = coalesce(excluded.current_control_hz, p25_site_snapshot.current_control_hz)
            """))
        {
            statement.setString(1, snapshot.guid());
            statement.setString(2, snapshot.snapshotHash());
            statement.setLong(3, snapshot.observedAtEpochMilliseconds());
            statement.setLong(4, snapshot.observedAtEpochMilliseconds());
            statement.setString(5, snapshot.protocol());
            statement.setString(6, snapshot.channelName());
            statement.setString(7, snapshot.aliasListName());
            statement.setString(8, snapshot.decoder());
            setInteger(statement, 9, snapshot.wacn());
            setInteger(statement, 10, snapshot.systemId());
            setInteger(statement, 11, snapshot.nac());
            setInteger(statement, 12, snapshot.rfss());
            setInteger(statement, 13, snapshot.site());
            setLong(statement, 14, snapshot.primaryFrequencyHertz());
            setLong(statement, 15, snapshot.currentControlHertz());
            statement.executeUpdate();
        }
    }

    private static void upsertSiteChannels(Connection connection, P25ActivityLogRecords.SiteSnapshot snapshot)
        throws SQLException
    {
        if(snapshot.channels() == null)
        {
            return;
        }

        for(P25NetworkConfigurationSnapshot.Channel channel: snapshot.channels())
        {
            String key = channelKey(channel);

            if(key == null)
            {
                continue;
            }

            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO p25_site_channel (
                    guid, channel_key, descriptor, role, downlink_hz, uplink_hz, tdma, timeslots,
                    first_seen_ms, last_seen_ms, seen_count, primary_control_seen, alternate_control_seen, traffic_seen
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)
                ON CONFLICT(guid, channel_key) DO UPDATE SET
                    descriptor = coalesce(excluded.descriptor, p25_site_channel.descriptor),
                    role = coalesce(excluded.role, p25_site_channel.role),
                    downlink_hz = coalesce(excluded.downlink_hz, p25_site_channel.downlink_hz),
                    uplink_hz = coalesce(excluded.uplink_hz, p25_site_channel.uplink_hz),
                    tdma = coalesce(excluded.tdma, p25_site_channel.tdma),
                    timeslots = coalesce(excluded.timeslots, p25_site_channel.timeslots),
                    last_seen_ms = excluded.last_seen_ms,
                    seen_count = p25_site_channel.seen_count + 1,
                    primary_control_seen = p25_site_channel.primary_control_seen + excluded.primary_control_seen,
                    alternate_control_seen = p25_site_channel.alternate_control_seen + excluded.alternate_control_seen,
                    traffic_seen = p25_site_channel.traffic_seen + excluded.traffic_seen
                """))
            {
                statement.setString(1, snapshot.guid());
                statement.setString(2, key);
                statement.setString(3, channel.descriptor());
                statement.setString(4, channel.role());
                setLong(statement, 5, channel.downlink());
                setLong(statement, 6, channel.uplink());
                setBoolean(statement, 7, channel.tdma());
                setInteger(statement, 8, channel.timeslots());
                statement.setLong(9, snapshot.observedAtEpochMilliseconds());
                statement.setLong(10, snapshot.observedAtEpochMilliseconds());
                statement.setInt(11, "primary_control".equals(channel.role()) ? 1 : 0);
                statement.setInt(12, isAlternateControl(channel.role()) ? 1 : 0);
                statement.setInt(13, "traffic".equals(channel.role()) ? 1 : 0);
                statement.executeUpdate();
            }
        }
    }

    private static void upsertSiteFrequencyBands(Connection connection, P25ActivityLogRecords.SiteSnapshot snapshot)
        throws SQLException
    {
        if(snapshot.frequencyBands() == null)
        {
            return;
        }

        for(P25NetworkConfigurationSnapshot.FrequencyBand band: snapshot.frequencyBands())
        {
            if(band == null || band.band() == null)
            {
                continue;
            }

            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO p25_site_frequency_band (
                    guid, band, first_seen_ms, last_seen_ms, seen_count, tdma, base_hz, bandwidth,
                    spacing_hz, transmit_offset_hz, timeslots
                ) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(guid, band) DO UPDATE SET
                    last_seen_ms = excluded.last_seen_ms,
                    seen_count = p25_site_frequency_band.seen_count + 1,
                    tdma = coalesce(excluded.tdma, p25_site_frequency_band.tdma),
                    base_hz = coalesce(excluded.base_hz, p25_site_frequency_band.base_hz),
                    bandwidth = coalesce(excluded.bandwidth, p25_site_frequency_band.bandwidth),
                    spacing_hz = coalesce(excluded.spacing_hz, p25_site_frequency_band.spacing_hz),
                    transmit_offset_hz = coalesce(excluded.transmit_offset_hz, p25_site_frequency_band.transmit_offset_hz),
                    timeslots = coalesce(excluded.timeslots, p25_site_frequency_band.timeslots)
                """))
            {
                statement.setString(1, snapshot.guid());
                statement.setInt(2, band.band());
                statement.setLong(3, snapshot.observedAtEpochMilliseconds());
                statement.setLong(4, snapshot.observedAtEpochMilliseconds());
                setBoolean(statement, 5, band.tdma());
                setLong(statement, 6, band.base());
                setInteger(statement, 7, band.bandwidth());
                setLong(statement, 8, band.spacing());
                setLong(statement, 9, band.transmitOffset());
                setInteger(statement, 10, band.timeslots());
                statement.executeUpdate();
            }
        }
    }

    private static void upsertSiteNeighbors(Connection connection, P25ActivityLogRecords.SiteSnapshot snapshot)
        throws SQLException
    {
        if(snapshot.neighborSites() == null)
        {
            return;
        }

        for(P25NetworkConfigurationSnapshot.NeighborSite neighbor: snapshot.neighborSites())
        {
            String key = neighborKey(neighbor);

            if(key == null)
            {
                continue;
            }

            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO p25_site_neighbor (
                    guid, neighbor_key, system_id, rfss, site, lra, channel_descriptor, downlink_hz,
                    uplink_hz, status, first_seen_ms, last_seen_ms, seen_count
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT(guid, neighbor_key) DO UPDATE SET
                    system_id = coalesce(excluded.system_id, p25_site_neighbor.system_id),
                    rfss = coalesce(excluded.rfss, p25_site_neighbor.rfss),
                    site = coalesce(excluded.site, p25_site_neighbor.site),
                    lra = coalesce(excluded.lra, p25_site_neighbor.lra),
                    channel_descriptor = coalesce(excluded.channel_descriptor, p25_site_neighbor.channel_descriptor),
                    downlink_hz = coalesce(excluded.downlink_hz, p25_site_neighbor.downlink_hz),
                    uplink_hz = coalesce(excluded.uplink_hz, p25_site_neighbor.uplink_hz),
                    status = coalesce(excluded.status, p25_site_neighbor.status),
                    last_seen_ms = excluded.last_seen_ms,
                    seen_count = p25_site_neighbor.seen_count + 1
                """))
            {
                statement.setString(1, snapshot.guid());
                statement.setString(2, key);
                setInteger(statement, 3, neighbor.system());
                setInteger(statement, 4, neighbor.rfss());
                setInteger(statement, 5, neighbor.site());
                setInteger(statement, 6, neighbor.lra());
                statement.setString(7, neighbor.channel());
                setLong(statement, 8, neighbor.downlink());
                setLong(statement, 9, neighbor.uplink());
                statement.setString(10, neighbor.status());
                statement.setLong(11, snapshot.observedAtEpochMilliseconds());
                statement.setLong(12, snapshot.observedAtEpochMilliseconds());
                statement.executeUpdate();
            }
        }
    }

    private static void upsertSitePatches(Connection connection, P25ActivityLogRecords.SiteSnapshot snapshot)
        throws SQLException
    {
        if(snapshot.patchGroups() == null)
        {
            return;
        }

        for(P25NetworkConfigurationSnapshot.PatchGroup patchGroup: snapshot.patchGroups())
        {
            if(patchGroup == null || patchGroup.patchGroup() == null)
            {
                continue;
            }

            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO p25_site_patch_group (
                    guid, patch_group, version, first_seen_ms, last_seen_ms, seen_count
                ) VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT(guid, patch_group) DO UPDATE SET
                    version = coalesce(excluded.version, p25_site_patch_group.version),
                    last_seen_ms = excluded.last_seen_ms,
                    seen_count = p25_site_patch_group.seen_count + 1
                """))
            {
                statement.setString(1, snapshot.guid());
                statement.setInt(2, patchGroup.patchGroup());
                setInteger(statement, 3, patchGroup.version());
                statement.setLong(4, snapshot.observedAtEpochMilliseconds());
                statement.setLong(5, snapshot.observedAtEpochMilliseconds());
                statement.executeUpdate();
            }

            upsertSitePatchTalkgroups(connection, snapshot, patchGroup);
            upsertSitePatchRadios(connection, snapshot, patchGroup);
        }
    }

    private static void upsertSitePatchTalkgroups(Connection connection, P25ActivityLogRecords.SiteSnapshot snapshot,
                                                  P25NetworkConfigurationSnapshot.PatchGroup patchGroup)
        throws SQLException
    {
        if(patchGroup.talkgroups() == null)
        {
            return;
        }

        for(Integer talkgroup: patchGroup.talkgroups())
        {
            if(talkgroup == null)
            {
                continue;
            }

            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO p25_site_patch_group_talkgroup (
                    guid, patch_group, talkgroup_id, first_seen_ms, last_seen_ms, seen_count
                ) VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT(guid, patch_group, talkgroup_id) DO UPDATE SET
                    last_seen_ms = excluded.last_seen_ms,
                    seen_count = p25_site_patch_group_talkgroup.seen_count + 1
                """))
            {
                statement.setString(1, snapshot.guid());
                statement.setInt(2, patchGroup.patchGroup());
                statement.setInt(3, talkgroup);
                statement.setLong(4, snapshot.observedAtEpochMilliseconds());
                statement.setLong(5, snapshot.observedAtEpochMilliseconds());
                statement.executeUpdate();
            }
        }
    }

    private static void upsertSitePatchRadios(Connection connection, P25ActivityLogRecords.SiteSnapshot snapshot,
                                              P25NetworkConfigurationSnapshot.PatchGroup patchGroup)
        throws SQLException
    {
        if(patchGroup.radios() == null)
        {
            return;
        }

        for(Integer radio: patchGroup.radios())
        {
            if(radio == null)
            {
                continue;
            }

            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO p25_site_patch_group_radio (
                    guid, patch_group, radio_id, first_seen_ms, last_seen_ms, seen_count
                ) VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT(guid, patch_group, radio_id) DO UPDATE SET
                    last_seen_ms = excluded.last_seen_ms,
                    seen_count = p25_site_patch_group_radio.seen_count + 1
                """))
            {
                statement.setString(1, snapshot.guid());
                statement.setInt(2, patchGroup.patchGroup());
                statement.setInt(3, radio);
                statement.setLong(4, snapshot.observedAtEpochMilliseconds());
                statement.setLong(5, snapshot.observedAtEpochMilliseconds());
                statement.executeUpdate();
            }
        }
    }

    private static void upsertSiteTalkerAliases(Connection connection, P25ActivityLogRecords.SiteSnapshot snapshot)
        throws SQLException
    {
        if(snapshot.talkerAliases() == null)
        {
            return;
        }

        for(P25NetworkConfigurationSnapshot.TalkerAlias alias: snapshot.talkerAliases())
        {
            if(alias == null || alias.radio() == null)
            {
                continue;
            }

            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO p25_site_talker_alias (
                    guid, radio_id, alias, first_seen_ms, last_seen_ms, seen_count
                ) VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT(guid, radio_id) DO UPDATE SET
                    alias = coalesce(excluded.alias, p25_site_talker_alias.alias),
                    last_seen_ms = excluded.last_seen_ms,
                    seen_count = p25_site_talker_alias.seen_count + 1
                """))
            {
                statement.setString(1, snapshot.guid());
                statement.setInt(2, alias.radio());
                statement.setString(3, alias.alias());
                statement.setLong(4, snapshot.observedAtEpochMilliseconds());
                statement.setLong(5, snapshot.observedAtEpochMilliseconds());
                statement.executeUpdate();
            }
        }
    }

    private static int selectContextId(Connection connection, String contextKey) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT id FROM receiver_context WHERE context_key = ?"))
        {
            statement.setString(1, contextKey);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    return resultSet.getInt(1);
                }
            }
        }

        throw new SQLException("Missing receiver_context row for context [" + contextKey + "]");
    }

    private static int deleteByTime(Connection connection, String table, String column, long cutoffEpochMilliseconds)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM " + table + " WHERE " + column + " < ?"))
        {
            statement.setLong(1, cutoffEpochMilliseconds);
            return statement.executeUpdate();
        }
    }

    private static int deleteAll(Connection connection, String table) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            return statement.executeUpdate("DELETE FROM " + table);
        }
    }

    private static SqliteSchemaValidator.Table table(String name, String... columns)
    {
        return new SqliteSchemaValidator.Table(name, columns);
    }

    private static SqliteSchemaValidator.Table tableWithActions(String name, String... columns)
    {
        List<String> list = new ArrayList<>(List.of(columns));
        int insertionPoint = list.indexOf("encrypted_count");

        if(insertionPoint < 0)
        {
            insertionPoint = list.indexOf("last_event_type_code");
        }

        if(insertionPoint < 0)
        {
            insertionPoint = list.size();
        }

        list.addAll(insertionPoint, ACTION_COUNT_COLUMNS);
        return new SqliteSchemaValidator.Table(name, list);
    }

    private static String actionUpdateSql(String table)
    {
        return ACTION_COUNT_COLUMNS.stream()
            .map(column -> column + " = " + table + "." + column + " + excluded." + column)
            .collect(Collectors.joining(",\n                "));
    }

    private static int setActionCounts(PreparedStatement statement, int index,
                                       P25ActivityLogRecords.ActivityEvent activity) throws SQLException
    {
        for(P25ActivityLogRecords.Action action: ACTIONS)
        {
            statement.setInt(index++, activity.action() == action ? 1 : 0);
        }

        return index;
    }

    private static boolean isP25(P25ActivityLogRecords.ActivityEvent activity)
    {
        return activity.contextKind() == P25ActivityLogRecords.ContextKind.TRUNKED_SITE ||
            activity.contextKind() == P25ActivityLogRecords.ContextKind.CONVENTIONAL_P25;
    }

    private static boolean isConventional(P25ActivityLogRecords.ContextKind contextKind)
    {
        return contextKind == P25ActivityLogRecords.ContextKind.CONVENTIONAL_P25 ||
            contextKind == P25ActivityLogRecords.ContextKind.CONVENTIONAL_ANALOG;
    }

    private static boolean isTalkgroup(String targetKind)
    {
        return "TALKGROUP".equals(targetKind) || "PATCH_GROUP".equals(targetKind);
    }

    private static int summaryTimeslot(Integer timeslot)
    {
        return timeslot != null ? timeslot : NULL_TIMESLOT;
    }

    private static long bucketStart(long observedAtEpochMilliseconds)
    {
        return observedAtEpochMilliseconds - Math.floorMod(observedAtEpochMilliseconds, HOUR_MILLISECONDS);
    }

    private static String guidContextKey(String guid)
    {
        return "GUID:" + guid;
    }

    private static int actionCode(P25ActivityLogRecords.Action action)
    {
        return action != null ? action.ordinal() + 1 : P25ActivityLogRecords.Action.UNKNOWN.ordinal() + 1;
    }

    private static Integer eventTypeCode(String eventType)
    {
        if(eventType == null || eventType.isBlank())
        {
            return null;
        }

        try
        {
            return DecodeEventType.valueOf(eventType).ordinal() + 1;
        }
        catch(IllegalArgumentException e)
        {
            return null;
        }
    }

    private static int contextKindCode(P25ActivityLogRecords.ContextKind contextKind)
    {
        if(contextKind == P25ActivityLogRecords.ContextKind.TRUNKED_SITE)
        {
            return CONTEXT_TRUNKED_SITE;
        }

        if(contextKind == P25ActivityLogRecords.ContextKind.CONVENTIONAL_ANALOG)
        {
            return CONTEXT_CONVENTIONAL_ANALOG;
        }

        return CONTEXT_CONVENTIONAL_P25;
    }

    private static Integer protocolCode(String protocol)
    {
        if(protocol == null)
        {
            return PROTOCOL_UNKNOWN;
        }

        return switch(protocol)
        {
            case "APCO25" -> PROTOCOL_APCO25;
            case "APCO25_PHASE2" -> PROTOCOL_APCO25_PHASE2;
            case "DMR" -> PROTOCOL_DMR;
            case "NXDN" -> PROTOCOL_NXDN;
            case "NBFM" -> PROTOCOL_NBFM;
            case "AM" -> PROTOCOL_AM;
            default -> PROTOCOL_UNKNOWN;
        };
    }

    private static Integer targetKindCode(String targetKind)
    {
        if("TALKGROUP".equals(targetKind))
        {
            return TARGET_TALKGROUP;
        }

        if("RADIO".equals(targetKind))
        {
            return TARGET_RADIO;
        }

        if("PATCH_GROUP".equals(targetKind))
        {
            return TARGET_PATCH_GROUP;
        }

        return null;
    }

    private static Integer parseInteger(String value)
    {
        if(value == null || value.isBlank())
        {
            return null;
        }

        String candidate = value.strip();

        try
        {
            return Integer.parseInt(candidate);
        }
        catch(NumberFormatException e)
        {
            return null;
        }
    }

    private static String channelKey(P25NetworkConfigurationSnapshot.Channel channel)
    {
        if(channel == null)
        {
            return null;
        }

        if(channel.descriptor() != null && !channel.descriptor().isBlank())
        {
            return channel.descriptor();
        }

        if(channel.downlink() != null && channel.downlink() > 0)
        {
            return Long.toString(channel.downlink());
        }

        return null;
    }

    private static String neighborKey(P25NetworkConfigurationSnapshot.NeighborSite neighbor)
    {
        if(neighbor == null)
        {
            return null;
        }

        String key = String.join(":", safe(neighbor.system()), safe(neighbor.rfss()), safe(neighbor.site()),
            safe(neighbor.channel()));

        if(!":::".equals(key))
        {
            return key;
        }

        return neighbor.downlink() != null && neighbor.downlink() > 0 ? Long.toString(neighbor.downlink()) : null;
    }

    private static boolean isAlternateControl(String role)
    {
        return role != null && (role.contains("alternate") || role.contains("secondary"));
    }

    private static String createResolvedViewSql()
    {
        return """
            CREATE VIEW IF NOT EXISTS p25_activity_event_resolved AS
            SELECT
                a.id,
                rc.context_key,
                rc.guid,
                %s AS channel_kind,
                a.observed_at_ms,
                %s AS protocol,
                %s AS action,
                %s AS event_type,
                a.source_radio_id,
                a.target_id,
                %s AS target_kind,
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
                rc.wacn AS resolved_wacn,
                rc.system_id AS resolved_system_id,
                rc.nac AS resolved_nac,
                rc.rfss AS resolved_rfss,
                rc.site AS resolved_site,
                rc.current_control_hz AS resolved_current_control_hz
            FROM p25_activity_event a
            LEFT JOIN receiver_context rc ON rc.id = a.context_id
            """.formatted(contextKindCase("rc.kind_code"), protocolCase("rc.protocol_code"),
            enumCase("a.action_code", P25ActivityLogRecords.Action.values()), decodeEventTypeCase("a.event_type_code"),
            targetKindCase("a.target_kind_code"));
    }

    private static String contextKindCase(String expression)
    {
        return "CASE " + expression + " WHEN " + CONTEXT_TRUNKED_SITE + " THEN 'TRUNKED_SITE' WHEN " +
            CONTEXT_CONVENTIONAL_P25 + " THEN 'CONVENTIONAL_P25' WHEN " + CONTEXT_CONVENTIONAL_ANALOG +
            " THEN 'CONVENTIONAL_ANALOG' ELSE NULL END";
    }

    private static String protocolCase(String expression)
    {
        return "CASE " + expression + " WHEN " + PROTOCOL_APCO25 + " THEN 'APCO25' WHEN " +
            PROTOCOL_APCO25_PHASE2 + " THEN 'APCO25_PHASE2' WHEN " + PROTOCOL_DMR + " THEN 'DMR' WHEN " +
            PROTOCOL_NXDN + " THEN 'NXDN' WHEN " + PROTOCOL_NBFM + " THEN 'NBFM' WHEN " + PROTOCOL_AM +
            " THEN 'AM' ELSE 'UNKNOWN' END";
    }

    private static String targetKindCase(String expression)
    {
        return "CASE " + expression + " WHEN " + TARGET_TALKGROUP + " THEN 'TALKGROUP' WHEN " +
            TARGET_RADIO + " THEN 'RADIO' WHEN " + TARGET_PATCH_GROUP + " THEN 'PATCH_GROUP' ELSE NULL END";
    }

    private static String enumCase(String expression, P25ActivityLogRecords.Action[] values)
    {
        StringBuilder sb = new StringBuilder("CASE ").append(expression);

        for(P25ActivityLogRecords.Action value: values)
        {
            sb.append(" WHEN ").append(actionCode(value)).append(" THEN '").append(value.name()).append("'");
        }

        return sb.append(" ELSE 'UNKNOWN' END").toString();
    }

    private static String decodeEventTypeCase(String expression)
    {
        StringBuilder sb = new StringBuilder("CASE ").append(expression);

        for(DecodeEventType value: DecodeEventType.values())
        {
            sb.append(" WHEN ").append(value.ordinal() + 1).append(" THEN '").append(value.name()).append("'");
        }

        return sb.append(" ELSE NULL END").toString();
    }

    private static String safe(Object value)
    {
        return value != null ? value.toString() : "";
    }

    private record Lcn(Integer band, Integer number)
    {
        static Lcn parse(String value)
        {
            if(value == null)
            {
                return new Lcn(null, null);
            }

            String candidate = value.strip();
            int separator = candidate.indexOf('-');

            if(separator <= 0 || separator >= candidate.length() - 1)
            {
                return new Lcn(null, null);
            }

            Integer band = parseLeadingInteger(candidate.substring(0, separator));
            Integer number = parseLeadingInteger(candidate.substring(separator + 1));
            return new Lcn(band, number);
        }

        private static Integer parseLeadingInteger(String value)
        {
            if(value == null)
            {
                return null;
            }

            String candidate = value.strip();
            int end = 0;

            while(end < candidate.length() && Character.isDigit(candidate.charAt(end)))
            {
                end++;
            }

            if(end == 0)
            {
                return null;
            }

            try
            {
                return Integer.parseInt(candidate.substring(0, end));
            }
            catch(NumberFormatException e)
            {
                return null;
            }
        }
    }

    private static void setInteger(PreparedStatement statement, int index, Integer value) throws SQLException
    {
        if(value != null)
        {
            statement.setInt(index, value);
        }
        else
        {
            statement.setNull(index, java.sql.Types.INTEGER);
        }
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException
    {
        if(value != null)
        {
            statement.setLong(index, value);
        }
        else
        {
            statement.setNull(index, java.sql.Types.INTEGER);
        }
    }

    private static void setBoolean(PreparedStatement statement, int index, Boolean value) throws SQLException
    {
        if(value != null)
        {
            statement.setInt(index, value ? 1 : 0);
        }
        else
        {
            statement.setNull(index, java.sql.Types.INTEGER);
        }
    }
}
