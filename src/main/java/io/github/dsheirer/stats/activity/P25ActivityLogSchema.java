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

package io.github.dsheirer.stats.activity;

import io.github.dsheirer.channel.metadata.activity.ChannelTag;
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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SQLite schema and writes for SDRTrunk receiver activity history.
 *
 * The v21 shape is summary-first. P25 systems own radios and talkgroups, while receiver contexts own site observations.
 * Detailed event rows are optional, while lifetime and hourly summaries are always updated when stats logging is
 * enabled. Table names are split by protocol family so DMR/NXDN can be added without folding unrelated records into
 * the P25 tables.
 */
public class P25ActivityLogSchema
{
    private static final int SCHEMA_VERSION = 21;
    private static final String SCHEMA_VERSION_KEY = "p25_activity_schema_version";
    public static final String CALL_OUTPUT_METRICS_STARTED_AT_KEY = "p25_call_output_metrics_started_at_ms";
    private static final long HOUR_MILLISECONDS = 3_600_000L;
    private static final long QUALITY_BUCKET_MILLISECONDS = 10_000L;
    static final int RETENTION_DELETE_BATCH_SIZE = 1_000;
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

    public static void create(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS p25_system (
                    system_key INTEGER PRIMARY KEY,
                    wacn INTEGER NOT NULL,
                    system_id INTEGER NOT NULL,
                    first_seen_ms INTEGER NOT NULL,
                    last_seen_ms INTEGER NOT NULL,
                    UNIQUE(wacn, system_id)
                )
                """);
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
                    system_key INTEGER,
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
            createControlChannelQualityTable(statement);
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
        SdrTrunkDatabaseStartup.setMetadata(connection, CALL_OUTPUT_METRICS_STARTED_AT_KEY,
            Long.toString(System.currentTimeMillis()));
    }

    public static void validate(Connection connection) throws SQLException
    {
        SqliteSchemaValidator.validate(connection, TABLES, INDEXES, VIEWS,
            List.of(new SqliteSchemaValidator.Metadata(SCHEMA_VERSION_KEY, Integer.toString(SCHEMA_VERSION))));
        validateIndexColumns(connection, "idx_p25_control_quality_retention",
            List.of("observed_at_ms", "guid", "frequency_hz", "bucket_start_ms"));
    }

    /**
     * Validates the public v20 shape for an explicit external upgrade. Normal application startup validates only the
     * current schema through {@link #validate(Connection)}.
     */
    public static void validateV20ForUpgrade(Connection connection) throws SQLException
    {
        SqliteSchemaValidator.validate(connection, TABLES, V20_INDEXES, VIEWS,
            List.of(new SqliteSchemaValidator.Metadata(SCHEMA_VERSION_KEY, "20")));
    }

    static Long recordActivity(Connection connection, P25ActivityLogRecords.ActivityEvent activity,
                               boolean detailedEventHistoryEnabled) throws SQLException
    {
        Long activityId = null;
        Integer systemKey = activity.contextKind() == P25ActivityLogRecords.ContextKind.TRUNKED_SITE ?
            resolveP25SystemKey(connection, activity) : null;
        int contextId = upsertReceiverContext(connection, activity, systemKey);

        if(activity.contextKind() == P25ActivityLogRecords.ContextKind.TRUNKED_SITE)
        {
            if(detailedEventHistoryEnabled && activity.action() != P25ActivityLogRecords.Action.CONTINUE)
            {
                activityId = insertP25ActivityEvent(connection, activity, contextId);
            }

            upsertP25SiteMetrics(connection, activity, contextId);

            if(isServiceGrant(activity))
            {
                upsertGrantedChannelSummary(connection, activity);
            }

            if(systemKey != null)
            {
                upsertP25SystemSummaries(connection, activity, systemKey);
                updateRadioAffiliation(connection, activity, systemKey);
            }
        }
        else if(isConventional(activity.contextKind()))
        {
            if(activity.contextKind() == P25ActivityLogRecords.ContextKind.CONVENTIONAL_P25 &&
                detailedEventHistoryEnabled && activity.action() != P25ActivityLogRecords.Action.CONTINUE)
            {
                activityId = insertP25ActivityEvent(connection, activity, contextId);
            }

            upsertConventionalSummary(connection, activity, contextId);
        }

        return activityId;
    }

    static boolean applyCompletedCallOutput(Connection connection,
                                            P25ActivityLogRecords.CompletedCallOutput completedCallOutput)
        throws SQLException
    {
        if(completedCallOutput == null || completedCallOutput.guid() == null ||
            completedCallOutput.guid().isBlank() || completedCallOutput.talkgroupId() <= 0 ||
            completedCallOutput.output() == null)
        {
            return false;
        }

        ReceiverContextIdentity context = selectContextIdentityByGuid(connection, completedCallOutput.guid());

        if(context == null || context.kindCode() != CONTEXT_TRUNKED_SITE || context.systemKey() == null)
        {
            return false;
        }

        int recorded = completedCallOutput.output() == P25ActivityLogRecords.CallOutput.RECORDED ? 1 : 0;
        int streamed = completedCallOutput.output() == P25ActivityLogRecords.CallOutput.STREAMED ? 1 : 0;
        long callStart = completedCallOutput.callStartEpochMilliseconds();
        long bucket = bucketStart(callStart);

        try(PreparedStatement summary = connection.prepareStatement("""
                INSERT INTO p25_talkgroup_summary (
                    system_key, talkgroup_id, first_seen_ms, last_seen_ms, recorded_count, streamed_count
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(system_key, talkgroup_id) DO UPDATE SET
                    first_seen_ms = min(p25_talkgroup_summary.first_seen_ms, excluded.first_seen_ms),
                    last_seen_ms = max(p25_talkgroup_summary.last_seen_ms, excluded.last_seen_ms),
                    recorded_count = p25_talkgroup_summary.recorded_count + excluded.recorded_count,
                    streamed_count = p25_talkgroup_summary.streamed_count + excluded.streamed_count
                """);
            PreparedStatement talkgroup = connection.prepareStatement("""
                INSERT INTO p25_site_talkgroup_bucket (
                    context_id, talkgroup_id, bucket_start_ms, recorded_count, streamed_count
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(context_id, talkgroup_id, bucket_start_ms) DO UPDATE SET
                    recorded_count = p25_site_talkgroup_bucket.recorded_count + excluded.recorded_count,
                    streamed_count = p25_site_talkgroup_bucket.streamed_count + excluded.streamed_count
                """);
            PreparedStatement site = connection.prepareStatement("""
                INSERT INTO p25_site_activity_bucket (
                    context_id, bucket_start_ms, recorded_count, streamed_count
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT(context_id, bucket_start_ms) DO UPDATE SET
                    recorded_count = p25_site_activity_bucket.recorded_count + excluded.recorded_count,
                    streamed_count = p25_site_activity_bucket.streamed_count + excluded.streamed_count
                """))
        {
            summary.setInt(1, context.systemKey());
            summary.setInt(2, completedCallOutput.talkgroupId());
            summary.setLong(3, callStart);
            summary.setLong(4, callStart);
            summary.setInt(5, recorded);
            summary.setInt(6, streamed);
            summary.executeUpdate();

            talkgroup.setInt(1, context.contextId());
            talkgroup.setInt(2, completedCallOutput.talkgroupId());
            talkgroup.setLong(3, bucket);
            talkgroup.setInt(4, recorded);
            talkgroup.setInt(5, streamed);
            talkgroup.executeUpdate();

            site.setInt(1, context.contextId());
            site.setLong(2, bucket);
            site.setInt(3, recorded);
            site.setInt(4, streamed);
            site.executeUpdate();
        }

        return true;
    }

    static void updateTalkerAlias(Connection connection, P25ActivityLogRecords.TalkerAliasUpdate update)
        throws SQLException
    {
        Integer systemKey = resolveEstablishedP25SystemKey(connection, update.observedAtEpochMilliseconds(),
            update.contextKey(), update.guid());

        if(systemKey != null)
        {
            upsertP25TalkerAlias(connection, systemKey, update.radioId(), update.talkerAlias(),
                update.observedAtEpochMilliseconds());
        }
    }

    static void insertSite(Connection connection, P25ActivityLogRecords.SiteSnapshot snapshot) throws SQLException
    {
        boolean changed = siteSnapshotChanged(connection, snapshot);
        Map<String,SiteChannelEvidence> channels = mergeSiteChannels(snapshot);
        Integer systemKey = upsertP25System(connection, snapshot.wacn(), snapshot.systemId(),
            snapshot.observedAtEpochMilliseconds());
        upsertReceiverContext(connection, snapshot, systemKey);
        upsertSiteSnapshot(connection, snapshot, systemKey);
        upsertSiteTalkerAliases(connection, snapshot, systemKey);

        if(changed)
        {
            upsertSiteChannelSummaries(connection, snapshot, channels);
            upsertSiteFrequencyBandSummaries(connection, snapshot);
            upsertForeignSystemBandSummaries(connection, snapshot);
            upsertSiteNeighborSummaries(connection, snapshot);
            upsertSitePatchSummaries(connection, snapshot);
            replaceCurrentSiteFacts(connection, snapshot, channels);
        }
        else
        {
            confirmCurrentSiteFacts(connection, snapshot);
        }
    }

    static void insertControlChannelQuality(Connection connection,
                                            P25ActivityLogRecords.ControlChannelQuality quality) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_control_channel_quality (
                guid, frequency_hz, bucket_start_ms, observed_at_ms, signal_dbfs, average_signal_dbfs,
                minimum_signal_dbfs, maximum_signal_dbfs, decode_health_pct, valid_frames, invalid_frames,
                corrected_bits, sync_loss_bits, dropped_bits, last_valid_decode_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(guid, frequency_hz, bucket_start_ms) DO UPDATE SET
                observed_at_ms = excluded.observed_at_ms,
                signal_dbfs = excluded.signal_dbfs,
                average_signal_dbfs = excluded.average_signal_dbfs,
                minimum_signal_dbfs = excluded.minimum_signal_dbfs,
                maximum_signal_dbfs = excluded.maximum_signal_dbfs,
                decode_health_pct = excluded.decode_health_pct,
                valid_frames = excluded.valid_frames,
                invalid_frames = excluded.invalid_frames,
                corrected_bits = excluded.corrected_bits,
                sync_loss_bits = excluded.sync_loss_bits,
                dropped_bits = excluded.dropped_bits,
                last_valid_decode_ms = excluded.last_valid_decode_ms
            WHERE excluded.observed_at_ms >= p25_control_channel_quality.observed_at_ms
            """))
        {
            statement.setString(1, quality.guid());
            statement.setLong(2, quality.frequencyHertz());
            statement.setLong(3, qualityBucketStart(quality.observedAtEpochMilliseconds()));
            statement.setLong(4, quality.observedAtEpochMilliseconds());
            setDouble(statement, 5, quality.signalDbfs());
            setDouble(statement, 6, quality.averageSignalDbfs());
            setDouble(statement, 7, quality.minimumSignalDbfs());
            setDouble(statement, 8, quality.maximumSignalDbfs());
            setDouble(statement, 9, quality.decodeHealthPercent());
            statement.setLong(10, quality.validFrames());
            statement.setLong(11, quality.invalidFrames());
            statement.setLong(12, quality.correctedBits());
            statement.setLong(13, quality.syncLossBits());
            statement.setLong(14, quality.droppedBits());
            statement.setLong(15, quality.lastValidDecodeMs());
            statement.executeUpdate();
        }
    }

    static int deleteOlderThan(Connection connection, long cutoffEpochMilliseconds) throws SQLException
    {
        int deleted = 0;
        deleted += deleteByTime(connection, "p25_activity_event", "observed_at_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_talkgroup_bucket", "bucket_start_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_activity_bucket", "bucket_start_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_radio_affiliation", "updated_at_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "conventional_activity_bucket", "bucket_start_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_channel", "confirmed_at_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_channel_tag", "confirmed_at_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_frequency_band", "confirmed_at_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_foreign_system_band", "confirmed_at_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_neighbor", "confirmed_at_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_patch_group_talkgroup", "confirmed_at_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_patch_group_radio", "confirmed_at_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_patch_group", "confirmed_at_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_channel_summary", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_channel_tag_summary", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_frequency_band_summary", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_foreign_system_band_summary", "last_seen_ms",
            cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_neighbor_summary", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_patch_group_talkgroup_summary", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_patch_group_radio_summary", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_patch_group_summary", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "p25_site_snapshot", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteExpiredControlChannelQuality(connection, cutoffEpochMilliseconds);
        return deleted;
    }

    static int resetStats(Connection connection) throws SQLException
    {
        int deleted = 0;
        deleted += deleteAll(connection, "p25_activity_event");
        deleted += deleteAll(connection, "p25_site_talkgroup_bucket");
        deleted += deleteAll(connection, "p25_site_activity_bucket");
        deleted += deleteAll(connection, "p25_talkgroup_summary");
        deleted += deleteAll(connection, "p25_radio_summary");
        deleted += deleteAll(connection, "p25_radio_talkgroup_summary");
        deleted += deleteAll(connection, "p25_radio_affiliation");
        deleted += deleteAll(connection, "p25_site_frequency_summary");
        deleted += deleteAll(connection, "conventional_activity_bucket");
        deleted += deleteAll(connection, "conventional_activity_summary");
        deleted += deleteAll(connection, "p25_site_patch_group_radio_summary");
        deleted += deleteAll(connection, "p25_site_patch_group_talkgroup_summary");
        deleted += deleteAll(connection, "p25_site_patch_group_summary");
        deleted += deleteAll(connection, "p25_site_neighbor_summary");
        deleted += deleteAll(connection, "p25_site_frequency_band_summary");
        deleted += deleteAll(connection, "p25_foreign_system_band_summary");
        deleted += deleteAll(connection, "p25_site_channel_tag_summary");
        deleted += deleteAll(connection, "p25_site_channel_summary");
        deleted += deleteAll(connection, "p25_site_patch_group_radio");
        deleted += deleteAll(connection, "p25_site_patch_group_talkgroup");
        deleted += deleteAll(connection, "p25_site_patch_group");
        deleted += deleteAll(connection, "p25_site_neighbor");
        deleted += deleteAll(connection, "p25_site_frequency_band");
        deleted += deleteAll(connection, "p25_foreign_system_band");
        deleted += deleteAll(connection, "p25_site_channel_tag");
        deleted += deleteAll(connection, "p25_site_channel");
        deleted += deleteAll(connection, "p25_site_snapshot");
        deleted += deleteAll(connection, "p25_control_channel_quality");
        deleted += deleteAll(connection, "receiver_context");
        deleted += deleteAll(connection, "p25_system");
        deleted += deleteAll(connection, "logger_status");
        return deleted;
    }

    /**
     * Clears receiver/site-scoped statistics for a single configured site GUID. System-wide talkgroup, radio, and
     * affiliation summaries are intentionally retained because they can contain observations from multiple sites.
     */
    static int clearSiteStats(Connection connection, String guid) throws SQLException
    {
        if(guid == null || guid.isBlank())
        {
            throw new IllegalArgumentException("Site GUID is required");
        }

        int deleted = 0;
        deleted += deleteByContextGuid(connection, "p25_activity_event", guid);
        deleted += deleteByContextGuid(connection, "p25_site_talkgroup_bucket", guid);
        deleted += deleteByContextGuid(connection, "p25_site_activity_bucket", guid);
        deleted += deleteByContextGuid(connection, "p25_site_frequency_summary", guid);
        deleted += deleteByContextGuid(connection, "conventional_activity_bucket", guid);
        deleted += deleteByContextGuid(connection, "conventional_activity_summary", guid);
        deleted += deleteByGuid(connection, "p25_site_patch_group_radio_summary", guid);
        deleted += deleteByGuid(connection, "p25_site_patch_group_talkgroup_summary", guid);
        deleted += deleteByGuid(connection, "p25_site_patch_group_summary", guid);
        deleted += deleteByGuid(connection, "p25_site_neighbor_summary", guid);
        deleted += deleteByGuid(connection, "p25_site_frequency_band_summary", guid);
        deleted += deleteByGuid(connection, "p25_foreign_system_band_summary", guid);
        deleted += deleteByGuid(connection, "p25_site_channel_tag_summary", guid);
        deleted += deleteByGuid(connection, "p25_site_channel_summary", guid);
        deleted += deleteByGuid(connection, "p25_site_patch_group_radio", guid);
        deleted += deleteByGuid(connection, "p25_site_patch_group_talkgroup", guid);
        deleted += deleteByGuid(connection, "p25_site_patch_group", guid);
        deleted += deleteByGuid(connection, "p25_site_neighbor", guid);
        deleted += deleteByGuid(connection, "p25_site_frequency_band", guid);
        deleted += deleteByGuid(connection, "p25_foreign_system_band", guid);
        deleted += deleteByGuid(connection, "p25_site_channel_tag", guid);
        deleted += deleteByGuid(connection, "p25_site_channel", guid);
        deleted += deleteByGuid(connection, "p25_site_snapshot", guid);
        deleted += deleteByGuid(connection, "p25_control_channel_quality", guid);
        deleted += deleteByGuid(connection, "receiver_context", guid);
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

    static long readStatusLong(Connection connection, String key) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT value FROM logger_status WHERE key = ?"))
        {
            statement.setString(1, key);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    try
                    {
                        return Long.parseLong(resultSet.getString(1));
                    }
                    catch(NumberFormatException e)
                    {
                        return 0;
                    }
                }
            }
        }

        return 0;
    }

    private static void createP25SummaryTables(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_talkgroup_summary (
                system_key INTEGER NOT NULL,
                talkgroup_id INTEGER NOT NULL,
                target_kind_code INTEGER,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                %s,
                encrypted_count INTEGER NOT NULL DEFAULT 0,
                recorded_count INTEGER NOT NULL DEFAULT 0,
                streamed_count INTEGER NOT NULL DEFAULT 0,
                last_source_radio_id INTEGER,
                last_encryption_algorithm_id INTEGER,
                last_encryption_key_id INTEGER,
                PRIMARY KEY(system_key, talkgroup_id)
            )
            """.formatted(ACTION_COUNT_DEFINITIONS));
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_radio_summary (
                system_key INTEGER NOT NULL,
                radio_id INTEGER NOT NULL,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                %s,
                encrypted_count INTEGER NOT NULL DEFAULT 0,
                last_talkgroup_id INTEGER,
                last_talker_alias TEXT,
                last_talker_alias_seen_ms INTEGER,
                last_encryption_algorithm_id INTEGER,
                last_encryption_key_id INTEGER,
                PRIMARY KEY(system_key, radio_id)
            )
            """.formatted(ACTION_COUNT_DEFINITIONS));
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_radio_affiliation (
                system_key INTEGER NOT NULL,
                radio_id INTEGER NOT NULL,
                talkgroup_id INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                PRIMARY KEY(system_key, radio_id)
            ) WITHOUT ROWID
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_radio_talkgroup_summary (
                system_key INTEGER NOT NULL,
                radio_id INTEGER NOT NULL,
                talkgroup_id INTEGER NOT NULL,
                target_kind_code INTEGER,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                %s,
                encrypted_count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(system_key, radio_id, talkgroup_id)
            ) WITHOUT ROWID
            """.formatted(ACTION_COUNT_DEFINITIONS));
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_frequency_summary (
                context_id INTEGER NOT NULL,
                frequency_hz INTEGER NOT NULL,
                timeslot INTEGER NOT NULL DEFAULT -1,
                lcn_band INTEGER,
                lcn_number INTEGER,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
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
            CREATE TABLE IF NOT EXISTS p25_site_talkgroup_bucket (
                context_id INTEGER NOT NULL,
                talkgroup_id INTEGER NOT NULL,
                bucket_start_ms INTEGER NOT NULL,
                %s,
                encrypted_count INTEGER NOT NULL DEFAULT 0,
                recorded_count INTEGER NOT NULL DEFAULT 0,
                streamed_count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(context_id, talkgroup_id, bucket_start_ms)
            )
            """.formatted(ACTION_COUNT_DEFINITIONS));
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_activity_bucket (
                context_id INTEGER NOT NULL,
                bucket_start_ms INTEGER NOT NULL,
                %s,
                encrypted_count INTEGER NOT NULL DEFAULT 0,
                recorded_count INTEGER NOT NULL DEFAULT 0,
                streamed_count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(context_id, bucket_start_ms)
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
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_channel (
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
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_channel_summary (
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
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_channel_tag (
                guid TEXT NOT NULL,
                channel_key TEXT NOT NULL,
                tag TEXT NOT NULL,
                confirmed_at_ms INTEGER NOT NULL,
                PRIMARY KEY(guid, channel_key, tag)
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_channel_tag_summary (
                guid TEXT NOT NULL,
                channel_key TEXT NOT NULL,
                tag TEXT NOT NULL,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                observation_count INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(guid, channel_key, tag)
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_frequency_band (
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
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_frequency_band_summary (
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
            )
            """);
        createForeignSystemBandTables(statement);
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
                confirmed_at_ms INTEGER NOT NULL,
                PRIMARY KEY(guid, neighbor_key)
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_neighbor_summary (
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
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_patch_group (
                guid TEXT NOT NULL,
                patch_group INTEGER NOT NULL,
                version INTEGER,
                confirmed_at_ms INTEGER NOT NULL,
                PRIMARY KEY(guid, patch_group)
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_patch_group_summary (
                guid TEXT NOT NULL,
                patch_group INTEGER NOT NULL,
                version INTEGER,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                observation_count INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(guid, patch_group)
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_patch_group_talkgroup (
                guid TEXT NOT NULL,
                patch_group INTEGER NOT NULL,
                talkgroup_id INTEGER NOT NULL,
                confirmed_at_ms INTEGER NOT NULL,
                PRIMARY KEY(guid, patch_group, talkgroup_id)
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_patch_group_talkgroup_summary (
                guid TEXT NOT NULL,
                patch_group INTEGER NOT NULL,
                talkgroup_id INTEGER NOT NULL,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                observation_count INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(guid, patch_group, talkgroup_id)
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_patch_group_radio (
                guid TEXT NOT NULL,
                patch_group INTEGER NOT NULL,
                radio_id INTEGER NOT NULL,
                confirmed_at_ms INTEGER NOT NULL,
                PRIMARY KEY(guid, patch_group, radio_id)
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_patch_group_radio_summary (
                guid TEXT NOT NULL,
                patch_group INTEGER NOT NULL,
                radio_id INTEGER NOT NULL,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                observation_count INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(guid, patch_group, radio_id)
            )
            """);
    }

    private static void createControlChannelQualityTable(Statement statement) throws SQLException
    {
        /*
         * The deployed table name predates DMR/NXDN quality collection.  Its GUID-scoped bucket shape is shared by
         * every supported trunked protocol, so keep the name for schema compatibility instead of forcing a
         * data-moving migration for a cosmetic rename.
         */
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_control_channel_quality (
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
            """);
    }

    /**
     * Creates the current and retained foreign-system band tables. This is called only by the startup schema creator
     * for a new database and by the explicit external v19-to-v20 migration.
     */
    public static void createForeignSystemBandTables(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_foreign_system_band (
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
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_foreign_system_band_summary (
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
            """);
    }

    /**
     * Creates the retention-first index for the shared P25/DMR/NXDN control-channel quality buckets. This is called
     * only by the startup schema creator for a new database and by the explicit external P25 activity schema upgrade.
     */
    public static void createControlChannelQualityRetentionIndex(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_p25_control_quality_retention
            ON p25_control_channel_quality(observed_at_ms, guid, frequency_hz, bucket_start_ms)
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
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_talkgroup_bucket_time ON p25_site_talkgroup_bucket(context_id, bucket_start_ms)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_talkgroup_bucket_talkgroup_time ON p25_site_talkgroup_bucket(talkgroup_id, bucket_start_ms)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_activity_bucket_time ON p25_site_activity_bucket(bucket_start_ms)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_radio_affiliation_talkgroup ON p25_radio_affiliation(system_key, talkgroup_id, updated_at_ms DESC, radio_id)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_radio_talkgroup_talkgroup ON p25_radio_talkgroup_summary(system_key, talkgroup_id, last_seen_ms DESC, radio_id)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_conventional_bucket_time ON conventional_activity_bucket(context_id, bucket_start_ms)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_snapshot_identity ON p25_site_snapshot(system_key, rfss, site)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_channel_guid_frequency ON p25_site_channel(guid, downlink_hz)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_channel_tag_summary_guid_tag ON p25_site_channel_tag_summary(guid, tag, last_seen_ms DESC)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_neighbor_guid_site ON p25_site_neighbor(guid, system_id, rfss, site)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_patch_talkgroup ON p25_site_patch_group_talkgroup(talkgroup_id, guid)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_patch_radio ON p25_site_patch_group_radio(radio_id, guid)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_channel_summary_guid_frequency ON p25_site_channel_summary(guid, downlink_hz)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_neighbor_summary_guid_site ON p25_site_neighbor_summary(guid, system_id, rfss, site)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_control_quality_guid_time ON p25_control_channel_quality(guid, observed_at_ms DESC)");
        createControlChannelQualityRetentionIndex(statement);
        statement.executeUpdate(createResolvedViewSql());
    }

    private static final List<SqliteSchemaValidator.Table> TABLES = List.of(
        table("p25_system", "system_key", "wacn", "system_id", "first_seen_ms", "last_seen_ms"),
        table("receiver_context", "id", "context_key", "guid", "kind_code", "protocol_code", "channel_name",
            "alias_list_name", "decoder", "first_seen_ms", "last_seen_ms", "system_key", "nac", "rfss",
            "site", "primary_frequency_hz", "current_control_hz"),
        table("p25_activity_event", "id", "context_id", "observed_at_ms", "action_code", "event_type_code",
            "source_radio_id", "target_id", "target_kind_code", "frequency_hz", "lcn_band", "lcn_number",
            "timeslot", "encrypted", "encryption_algorithm_id", "encryption_key_id"),
        tableWithActions("p25_talkgroup_summary", "system_key", "talkgroup_id", "target_kind_code",
            "first_seen_ms", "last_seen_ms", "encrypted_count", "recorded_count", "streamed_count",
            "last_source_radio_id",
            "last_encryption_algorithm_id", "last_encryption_key_id"),
        tableWithActions("p25_radio_summary", "system_key", "radio_id", "first_seen_ms", "last_seen_ms",
            "encrypted_count", "last_talkgroup_id", "last_talker_alias", "last_talker_alias_seen_ms",
            "last_encryption_algorithm_id", "last_encryption_key_id"),
        table("p25_radio_affiliation", "system_key", "radio_id", "talkgroup_id", "updated_at_ms"),
        tableWithActions("p25_radio_talkgroup_summary", "system_key", "radio_id", "talkgroup_id",
            "target_kind_code", "first_seen_ms", "last_seen_ms", "encrypted_count"),
        tableWithActions("p25_site_frequency_summary", "context_id", "frequency_hz", "timeslot", "lcn_band",
            "lcn_number", "first_seen_ms", "last_seen_ms", "encrypted_count", "last_source_radio_id",
            "last_target_id", "last_encryption_algorithm_id", "last_encryption_key_id"),
        tableWithActions("p25_site_talkgroup_bucket", "context_id", "talkgroup_id", "bucket_start_ms",
            "encrypted_count", "recorded_count", "streamed_count"),
        tableWithActions("p25_site_activity_bucket", "context_id", "bucket_start_ms", "encrypted_count",
            "recorded_count", "streamed_count"),
        tableWithActions("conventional_activity_summary", "context_id", "frequency_hz", "timeslot",
            "first_seen_ms", "last_seen_ms", "last_event_type_code"),
        tableWithActions("conventional_activity_bucket", "context_id", "frequency_hz", "timeslot",
            "bucket_start_ms"),
        table("p25_site_snapshot", "guid", "snapshot_hash", "first_seen_ms", "last_seen_ms", "observation_count",
            "protocol", "channel_name", "alias_list_name", "decoder", "system_key", "nac", "rfss", "site",
            "lra", "mfid", "broadcast_clock_ms", "micro_slots", "data_service", "data_access",
            "wuid_lease_minutes", "registration_service", "tdma", "voice_service", "primary_frequency_hz",
            "current_control_hz"),
        table("p25_site_channel", "guid", "channel_key", "descriptor", "downlink_hz", "uplink_hz",
            "tdma", "timeslots", "callsign", "confirmed_at_ms"),
        table("p25_site_channel_summary", "guid", "channel_key", "descriptor", "downlink_hz", "uplink_hz",
            "tdma", "timeslots", "first_seen_ms", "last_seen_ms", "observation_count"),
        table("p25_site_channel_tag", "guid", "channel_key", "tag", "confirmed_at_ms"),
        table("p25_site_channel_tag_summary", "guid", "channel_key", "tag", "first_seen_ms", "last_seen_ms",
            "observation_count"),
        table("p25_site_frequency_band", "guid", "band", "tdma", "base_hz", "bandwidth", "spacing_hz",
            "transmit_offset_hz", "timeslots", "confirmed_at_ms"),
        table("p25_site_frequency_band_summary", "guid", "band", "tdma", "base_hz", "bandwidth",
            "spacing_hz", "transmit_offset_hz", "timeslots", "first_seen_ms", "last_seen_ms",
            "observation_count"),
        table("p25_foreign_system_band", "guid", "foreign_wacn", "foreign_system_id", "band",
            "channel_type", "base_hz", "spacing_hz", "transmit_offset_hz", "confirmed_at_ms"),
        table("p25_foreign_system_band_summary", "guid", "foreign_wacn", "foreign_system_id", "band",
            "channel_type", "base_hz", "spacing_hz", "transmit_offset_hz", "first_seen_ms", "last_seen_ms",
            "observation_count"),
        table("p25_site_neighbor", "guid", "neighbor_key", "system_id", "rfss", "site", "lra",
            "channel_descriptor", "downlink_hz", "uplink_hz", "status", "confirmed_at_ms"),
        table("p25_site_neighbor_summary", "guid", "neighbor_key", "system_id", "rfss", "site", "lra",
            "channel_descriptor", "downlink_hz", "uplink_hz", "status", "first_seen_ms", "last_seen_ms",
            "observation_count"),
        table("p25_site_patch_group", "guid", "patch_group", "version", "confirmed_at_ms"),
        table("p25_site_patch_group_summary", "guid", "patch_group", "version", "first_seen_ms", "last_seen_ms",
            "observation_count"),
        table("p25_site_patch_group_talkgroup", "guid", "patch_group", "talkgroup_id", "confirmed_at_ms"),
        table("p25_site_patch_group_talkgroup_summary", "guid", "patch_group", "talkgroup_id", "first_seen_ms",
            "last_seen_ms", "observation_count"),
        table("p25_site_patch_group_radio", "guid", "patch_group", "radio_id", "confirmed_at_ms"),
        table("p25_site_patch_group_radio_summary", "guid", "patch_group", "radio_id", "first_seen_ms",
            "last_seen_ms", "observation_count"),
        table("p25_control_channel_quality", "guid", "frequency_hz", "bucket_start_ms", "observed_at_ms",
            "signal_dbfs", "average_signal_dbfs", "minimum_signal_dbfs", "maximum_signal_dbfs",
            "decode_health_pct", "valid_frames", "invalid_frames", "corrected_bits", "sync_loss_bits",
            "dropped_bits", "last_valid_decode_ms"),
        table("logger_status", "key", "value", "updated_at_ms")
    );

    private static final List<String> V20_INDEXES = List.of(
        "idx_receiver_context_guid",
        "idx_p25_activity_event_context_time",
        "idx_p25_activity_event_target_time",
        "idx_p25_activity_event_source_time",
        "idx_p25_activity_event_frequency_time",
        "idx_p25_activity_event_encryption",
        "idx_p25_site_talkgroup_bucket_time",
        "idx_p25_site_talkgroup_bucket_talkgroup_time",
        "idx_p25_site_activity_bucket_time",
        "idx_p25_radio_affiliation_talkgroup",
        "idx_p25_radio_talkgroup_talkgroup",
        "idx_conventional_bucket_time",
        "idx_p25_site_snapshot_identity",
        "idx_p25_site_channel_guid_frequency",
        "idx_p25_site_channel_tag_summary_guid_tag",
        "idx_p25_site_neighbor_guid_site",
        "idx_p25_site_patch_talkgroup",
        "idx_p25_site_patch_radio",
        "idx_p25_site_channel_summary_guid_frequency",
        "idx_p25_site_neighbor_summary_guid_site",
        "idx_p25_control_quality_guid_time"
    );
    private static final List<String> INDEXES = currentIndexes();

    private static final List<String> VIEWS = List.of("p25_activity_event_resolved");

    private static List<String> currentIndexes()
    {
        List<String> indexes = new ArrayList<>(V20_INDEXES);
        indexes.add("idx_p25_control_quality_retention");
        return List.copyOf(indexes);
    }

    private static void upsertP25SystemSummaries(Connection connection, P25ActivityLogRecords.ActivityEvent activity,
                                                 int systemKey) throws SQLException
    {
        Integer sourceRadio = parseInteger(activity.sourceRadioId());
        Integer target = parseInteger(activity.targetId());

        if(target != null && isTalkgroup(activity.targetKind()))
        {
            upsertP25TalkgroupSummary(connection, activity, systemKey, target, sourceRadio);
        }

        if(sourceRadio != null)
        {
            upsertP25RadioSummary(connection, activity, systemKey, sourceRadio, target);

            if(target != null && isTalkgroup(activity.targetKind()))
            {
                upsertP25RadioTalkgroupSummary(connection, activity, systemKey, sourceRadio, target);
            }
        }
    }

    private static void upsertP25SiteMetrics(Connection connection, P25ActivityLogRecords.ActivityEvent activity,
                                             int contextId) throws SQLException
    {
        Integer sourceRadio = parseInteger(activity.sourceRadioId());
        Integer target = parseInteger(activity.targetId());

        if(target != null && isTalkgroup(activity.targetKind()))
        {
            upsertP25TalkgroupBucket(connection, activity, contextId, target);
        }

        upsertP25SiteActivityBucket(connection, activity, contextId);

        if(activity.frequencyHertz() != null && activity.frequencyHertz() > 0)
        {
            upsertP25FrequencySummary(connection, activity, contextId, sourceRadio, target);
        }
    }

    private static void updateRadioAffiliation(Connection connection, P25ActivityLogRecords.ActivityEvent activity,
                                               int systemKey) throws SQLException
    {
        P25ActivityLogRecords.RadioAffiliationUpdate update = activity.affiliationUpdate();

        if(update == null)
        {
            return;
        }

        if(update.talkgroupId() != null)
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO p25_radio_affiliation (
                    system_key, radio_id, talkgroup_id, updated_at_ms
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT(system_key, radio_id) DO UPDATE SET
                    talkgroup_id = excluded.talkgroup_id,
                    updated_at_ms = excluded.updated_at_ms
                WHERE excluded.updated_at_ms >= p25_radio_affiliation.updated_at_ms
                """))
            {
                statement.setInt(1, systemKey);
                statement.setInt(2, update.radioId());
                statement.setInt(3, update.talkgroupId());
                statement.setLong(4, activity.observedAtEpochMilliseconds());
                statement.executeUpdate();
            }
        }
        else
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM p25_radio_affiliation
                WHERE system_key = ? AND radio_id = ? AND updated_at_ms <= ?
                """))
            {
                statement.setInt(1, systemKey);
                statement.setInt(2, update.radioId());
                statement.setLong(3, activity.observedAtEpochMilliseconds());
                statement.executeUpdate();
            }
        }
    }

    private static long insertP25ActivityEvent(Connection connection, P25ActivityLogRecords.ActivityEvent activity,
                                               int contextId) throws SQLException
    {
        Lcn lcn = Lcn.parse(activity.lcn());

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_activity_event (
                context_id, observed_at_ms, action_code, event_type_code, source_radio_id, target_id, target_kind_code,
                frequency_hz, lcn_band, lcn_number, timeslot, encrypted, encryption_algorithm_id, encryption_key_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
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
            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    return resultSet.getLong(1);
                }
            }
        }

        throw new SQLException("SQLite did not return an activity row identifier");
    }

    private static void upsertP25TalkgroupSummary(Connection connection, P25ActivityLogRecords.ActivityEvent activity,
                                                  int systemKey, int talkgroup, Integer sourceRadio)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_talkgroup_summary (
                system_key, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms, %s,
                encrypted_count, last_source_radio_id, last_encryption_algorithm_id, last_encryption_key_id
            ) VALUES (?, ?, ?, ?, ?, %s, ?, ?, ?, ?)
            ON CONFLICT(system_key, talkgroup_id) DO UPDATE SET
                target_kind_code = coalesce(excluded.target_kind_code, p25_talkgroup_summary.target_kind_code),
                last_seen_ms = max(p25_talkgroup_summary.last_seen_ms, excluded.last_seen_ms),
                %s,
                encrypted_count = p25_talkgroup_summary.encrypted_count + excluded.encrypted_count,
                last_source_radio_id = coalesce(excluded.last_source_radio_id, p25_talkgroup_summary.last_source_radio_id),
                last_encryption_algorithm_id = coalesce(excluded.last_encryption_algorithm_id, p25_talkgroup_summary.last_encryption_algorithm_id),
                last_encryption_key_id = coalesce(excluded.last_encryption_key_id, p25_talkgroup_summary.last_encryption_key_id)
            """.formatted(ACTION_INSERT_COLUMNS, ACTION_INSERT_PLACEHOLDERS, actionUpdateSql("p25_talkgroup_summary"))))
        {
            int index = 1;
            statement.setInt(index++, systemKey);
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
            INSERT INTO p25_site_talkgroup_bucket (
                context_id, talkgroup_id, bucket_start_ms, %s, encrypted_count
            ) VALUES (?, ?, ?, %s, ?)
            ON CONFLICT(context_id, talkgroup_id, bucket_start_ms) DO UPDATE SET
                %s,
                encrypted_count = p25_site_talkgroup_bucket.encrypted_count + excluded.encrypted_count
            """.formatted(ACTION_INSERT_COLUMNS, ACTION_INSERT_PLACEHOLDERS,
            actionUpdateSql("p25_site_talkgroup_bucket"))))
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
                                              int systemKey, int radio, Integer target) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_radio_summary (
                system_key, radio_id, first_seen_ms, last_seen_ms, %s, encrypted_count, last_talkgroup_id,
                last_talker_alias, last_talker_alias_seen_ms, last_encryption_algorithm_id, last_encryption_key_id
            ) VALUES (?, ?, ?, ?, %s, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(system_key, radio_id) DO UPDATE SET
                last_seen_ms = max(p25_radio_summary.last_seen_ms, excluded.last_seen_ms),
                %s,
                encrypted_count = p25_radio_summary.encrypted_count + excluded.encrypted_count,
                last_talkgroup_id = coalesce(excluded.last_talkgroup_id, p25_radio_summary.last_talkgroup_id),
                last_talker_alias = CASE
                    WHEN excluded.last_talker_alias_seen_ms >= coalesce(p25_radio_summary.last_talker_alias_seen_ms, 0)
                    THEN coalesce(excluded.last_talker_alias, p25_radio_summary.last_talker_alias)
                    ELSE p25_radio_summary.last_talker_alias
                END,
                last_talker_alias_seen_ms = max(coalesce(p25_radio_summary.last_talker_alias_seen_ms, 0),
                    coalesce(excluded.last_talker_alias_seen_ms, 0)),
                last_encryption_algorithm_id = coalesce(excluded.last_encryption_algorithm_id, p25_radio_summary.last_encryption_algorithm_id),
                last_encryption_key_id = coalesce(excluded.last_encryption_key_id, p25_radio_summary.last_encryption_key_id)
            """.formatted(ACTION_INSERT_COLUMNS, ACTION_INSERT_PLACEHOLDERS, actionUpdateSql("p25_radio_summary"))))
        {
            int index = 1;
            statement.setInt(index++, systemKey);
            statement.setInt(index++, radio);
            statement.setLong(index++, activity.observedAtEpochMilliseconds());
            statement.setLong(index++, activity.observedAtEpochMilliseconds());
            index = setActionCounts(statement, index, activity);
            statement.setInt(index++, activity.encrypted() ? 1 : 0);
            setInteger(statement, index++, isTalkgroup(activity.targetKind()) ? target : null);
            statement.setString(index++, activity.talkerAlias());
            setLong(statement, index++, activity.talkerAlias() != null ? activity.observedAtEpochMilliseconds() : null);
            setInteger(statement, index++, activity.encryptionAlgorithmId());
            setInteger(statement, index, activity.encryptionKeyId());
            statement.executeUpdate();
        }
    }

    private static void upsertP25RadioTalkgroupSummary(Connection connection,
                                                       P25ActivityLogRecords.ActivityEvent activity, int systemKey,
                                                       int radio, int talkgroup) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_radio_talkgroup_summary (
                system_key, radio_id, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms, %s,
                encrypted_count
            ) VALUES (?, ?, ?, ?, ?, ?, %s, ?)
            ON CONFLICT(system_key, radio_id, talkgroup_id) DO UPDATE SET
                target_kind_code = coalesce(excluded.target_kind_code,
                    p25_radio_talkgroup_summary.target_kind_code),
                last_seen_ms = max(p25_radio_talkgroup_summary.last_seen_ms, excluded.last_seen_ms),
                %s,
                encrypted_count = p25_radio_talkgroup_summary.encrypted_count + excluded.encrypted_count
            """.formatted(ACTION_INSERT_COLUMNS, ACTION_INSERT_PLACEHOLDERS,
            actionUpdateSql("p25_radio_talkgroup_summary"))))
        {
            int index = 1;
            statement.setInt(index++, systemKey);
            statement.setInt(index++, radio);
            statement.setInt(index++, talkgroup);
            setInteger(statement, index++, targetKindCode(activity.targetKind()));
            statement.setLong(index++, activity.observedAtEpochMilliseconds());
            statement.setLong(index++, activity.observedAtEpochMilliseconds());
            index = setActionCounts(statement, index, activity);
            statement.setInt(index, activity.encrypted() ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private static void upsertP25SiteActivityBucket(Connection connection,
                                                    P25ActivityLogRecords.ActivityEvent activity,
                                                    int contextId) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_site_activity_bucket (
                context_id, bucket_start_ms, %s, encrypted_count
            ) VALUES (?, ?, %s, ?)
            ON CONFLICT(context_id, bucket_start_ms) DO UPDATE SET
                %s,
                encrypted_count = p25_site_activity_bucket.encrypted_count + excluded.encrypted_count
            """.formatted(ACTION_INSERT_COLUMNS, ACTION_INSERT_PLACEHOLDERS,
            actionUpdateSql("p25_site_activity_bucket"))))
        {
            int index = 1;
            statement.setInt(index++, contextId);
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
            INSERT INTO p25_site_frequency_summary (
                context_id, frequency_hz, timeslot, lcn_band, lcn_number, first_seen_ms, last_seen_ms, %s,
                encrypted_count, last_source_radio_id, last_target_id, last_encryption_algorithm_id,
                last_encryption_key_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, %s, ?, ?, ?, ?, ?)
            ON CONFLICT(context_id, frequency_hz, timeslot) DO UPDATE SET
                lcn_band = coalesce(excluded.lcn_band, p25_site_frequency_summary.lcn_band),
                lcn_number = coalesce(excluded.lcn_number, p25_site_frequency_summary.lcn_number),
                last_seen_ms = max(p25_site_frequency_summary.last_seen_ms, excluded.last_seen_ms),
                %s,
                encrypted_count = p25_site_frequency_summary.encrypted_count + excluded.encrypted_count,
                last_source_radio_id = coalesce(excluded.last_source_radio_id, p25_site_frequency_summary.last_source_radio_id),
                last_target_id = coalesce(excluded.last_target_id, p25_site_frequency_summary.last_target_id),
                last_encryption_algorithm_id = coalesce(excluded.last_encryption_algorithm_id, p25_site_frequency_summary.last_encryption_algorithm_id),
                last_encryption_key_id = coalesce(excluded.last_encryption_key_id, p25_site_frequency_summary.last_encryption_key_id)
            """.formatted(ACTION_INSERT_COLUMNS, ACTION_INSERT_PLACEHOLDERS,
            actionUpdateSql("p25_site_frequency_summary"))))
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
                context_id, frequency_hz, timeslot, first_seen_ms, last_seen_ms, %s, last_event_type_code
            ) VALUES (?, ?, ?, ?, ?, %s, ?)
            ON CONFLICT(context_id, frequency_hz, timeslot) DO UPDATE SET
                last_seen_ms = max(conventional_activity_summary.last_seen_ms, excluded.last_seen_ms),
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
                context_id, frequency_hz, timeslot, bucket_start_ms, %s
            ) VALUES (?, ?, ?, ?, %s)
            ON CONFLICT(context_id, frequency_hz, timeslot, bucket_start_ms) DO UPDATE SET
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

    private static Integer upsertP25System(Connection connection, Integer wacn, Integer systemId, long timestamp)
        throws SQLException
    {
        if(wacn == null || systemId == null)
        {
            return null;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_system (wacn, system_id, first_seen_ms, last_seen_ms)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(wacn, system_id) DO UPDATE SET
                last_seen_ms = max(p25_system.last_seen_ms, excluded.last_seen_ms)
            """))
        {
            statement.setInt(1, wacn);
            statement.setInt(2, systemId);
            statement.setLong(3, timestamp);
            statement.setLong(4, timestamp);
            statement.executeUpdate();
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT system_key FROM p25_system WHERE wacn = ? AND system_id = ?
            """))
        {
            statement.setInt(1, wacn);
            statement.setInt(2, systemId);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getInt(1) : null;
            }
        }
    }

    private static Integer resolveP25SystemKey(Connection connection, P25ActivityLogRecords.ActivityEvent activity)
        throws SQLException
    {
        return resolveEstablishedP25SystemKey(connection, activity.observedAtEpochMilliseconds(),
            activity.contextKey(), activity.guid());
    }

    /**
     * Resolves only identity previously established by a stabilized site snapshot.  Activity and talker-alias records
     * are intentionally unable to create or re-key a P25 system from message-scoped identifiers.
     */
    private static Integer resolveEstablishedP25SystemKey(Connection connection, long observedAt, String contextKey,
                                                          String guid) throws SQLException
    {
        Integer systemKey = null;

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT system_key
            FROM p25_site_snapshot
            WHERE guid = ? AND system_key IS NOT NULL
            UNION ALL
            SELECT context.system_key
            FROM receiver_context context
            JOIN p25_site_snapshot snapshot
                ON snapshot.guid = context.guid AND snapshot.system_key = context.system_key
            WHERE context.context_key = ? AND context.system_key IS NOT NULL
            LIMIT 1
            """))
        {
            statement.setString(1, guid);
            statement.setString(2, contextKey);

            try(ResultSet resultSet = statement.executeQuery())
            {
                systemKey = resultSet.next() ? resultSet.getInt(1) : null;
            }
        }

        if(systemKey != null)
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                UPDATE p25_system SET last_seen_ms = max(last_seen_ms, ?) WHERE system_key = ?
                """))
            {
                statement.setLong(1, observedAt);
                statement.setInt(2, systemKey);
                statement.executeUpdate();
            }
        }

        return systemKey;
    }

    private static void upsertP25TalkerAlias(Connection connection, int systemKey, int radio, String talkerAlias,
                                             long observedAt) throws SQLException
    {
        if(radio <= 0 || talkerAlias == null || talkerAlias.isBlank())
        {
            return;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_radio_summary (
                system_key, radio_id, first_seen_ms, last_seen_ms, last_talker_alias, last_talker_alias_seen_ms
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(system_key, radio_id) DO UPDATE SET
                last_seen_ms = max(p25_radio_summary.last_seen_ms, excluded.last_seen_ms),
                last_talker_alias = CASE
                    WHEN excluded.last_talker_alias_seen_ms >= coalesce(p25_radio_summary.last_talker_alias_seen_ms, 0)
                    THEN excluded.last_talker_alias
                    ELSE p25_radio_summary.last_talker_alias
                END,
                last_talker_alias_seen_ms = max(coalesce(p25_radio_summary.last_talker_alias_seen_ms, 0),
                    excluded.last_talker_alias_seen_ms)
            """))
        {
            statement.setInt(1, systemKey);
            statement.setInt(2, radio);
            statement.setLong(3, observedAt);
            statement.setLong(4, observedAt);
            statement.setString(5, talkerAlias.trim());
            statement.setLong(6, observedAt);
            statement.executeUpdate();
        }
    }

    private static void upsertSiteTalkerAliases(Connection connection,
                                                P25ActivityLogRecords.SiteSnapshot snapshot,
                                                Integer systemKey) throws SQLException
    {
        if(systemKey == null || snapshot.talkerAliases() == null)
        {
            return;
        }

        for(P25NetworkConfigurationSnapshot.TalkerAlias talkerAlias: snapshot.talkerAliases())
        {
            if(talkerAlias != null && talkerAlias.radio() != null)
            {
                upsertP25TalkerAlias(connection, systemKey, talkerAlias.radio(), talkerAlias.alias(),
                    snapshot.observedAtEpochMilliseconds());
            }
        }
    }

    private static int upsertReceiverContext(Connection connection, P25ActivityLogRecords.ActivityEvent activity,
                                             Integer systemKey) throws SQLException
    {
        boolean trunkedSite = activity.contextKind() == P25ActivityLogRecords.ContextKind.TRUNKED_SITE;

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO receiver_context (
                context_key, guid, kind_code, protocol_code, channel_name, decoder, first_seen_ms, last_seen_ms,
                system_key, nac, rfss, site, primary_frequency_hz, current_control_hz
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
            ON CONFLICT(context_key) DO UPDATE SET
                guid = coalesce(excluded.guid, receiver_context.guid),
                kind_code = excluded.kind_code,
                protocol_code = coalesce(excluded.protocol_code, receiver_context.protocol_code),
                channel_name = coalesce(excluded.channel_name, receiver_context.channel_name),
                decoder = coalesce(excluded.decoder, receiver_context.decoder),
                last_seen_ms = max(receiver_context.last_seen_ms, excluded.last_seen_ms),
                system_key = coalesce(excluded.system_key, receiver_context.system_key),
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
            setInteger(statement, 9, systemKey);
            setInteger(statement, 10, trunkedSite ? null : activity.nac());
            setInteger(statement, 11, trunkedSite ? null : activity.rfss());
            setInteger(statement, 12, trunkedSite ? null : activity.site());
            setLong(statement, 13, isConventional(activity.contextKind()) ? activity.frequencyHertz() : null);
            statement.executeUpdate();
        }

        return selectContextId(connection, activity.contextKey());
    }

    private static int upsertReceiverContext(Connection connection, P25ActivityLogRecords.SiteSnapshot snapshot,
                                             Integer systemKey) throws SQLException
    {
        String contextKey = guidContextKey(snapshot.guid());

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO receiver_context (
                context_key, guid, kind_code, protocol_code, channel_name, alias_list_name, decoder, first_seen_ms,
                last_seen_ms, system_key, nac, rfss, site, primary_frequency_hz, current_control_hz
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(context_key) DO UPDATE SET
                guid = coalesce(excluded.guid, receiver_context.guid),
                kind_code = excluded.kind_code,
                protocol_code = coalesce(excluded.protocol_code, receiver_context.protocol_code),
                channel_name = coalesce(excluded.channel_name, receiver_context.channel_name),
                alias_list_name = coalesce(excluded.alias_list_name, receiver_context.alias_list_name),
                decoder = coalesce(excluded.decoder, receiver_context.decoder),
                last_seen_ms = max(receiver_context.last_seen_ms, excluded.last_seen_ms),
                system_key = coalesce(excluded.system_key, receiver_context.system_key),
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
            setInteger(statement, 10, systemKey);
            setInteger(statement, 11, snapshot.nac());
            setInteger(statement, 12, snapshot.rfss());
            setInteger(statement, 13, snapshot.site());
            setLong(statement, 14, snapshot.primaryFrequencyHertz());
            setLong(statement, 15, snapshot.currentControlHertz());
            statement.executeUpdate();
        }

        return selectContextId(connection, contextKey);
    }

    private static void upsertSiteSnapshot(Connection connection, P25ActivityLogRecords.SiteSnapshot snapshot,
                                           Integer systemKey) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_site_snapshot (
                guid, snapshot_hash, first_seen_ms, last_seen_ms, observation_count, protocol, channel_name,
                alias_list_name, decoder, system_key, nac, rfss, site, lra, mfid, broadcast_clock_ms, micro_slots,
                data_service, data_access, wuid_lease_minutes, registration_service, tdma, voice_service,
                primary_frequency_hz, current_control_hz
            ) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(guid) DO UPDATE SET
                snapshot_hash = coalesce(excluded.snapshot_hash, p25_site_snapshot.snapshot_hash),
                last_seen_ms = excluded.last_seen_ms,
                observation_count = p25_site_snapshot.observation_count + 1,
                protocol = coalesce(excluded.protocol, p25_site_snapshot.protocol),
                channel_name = coalesce(excluded.channel_name, p25_site_snapshot.channel_name),
                alias_list_name = coalesce(excluded.alias_list_name, p25_site_snapshot.alias_list_name),
                decoder = coalesce(excluded.decoder, p25_site_snapshot.decoder),
                system_key = coalesce(excluded.system_key, p25_site_snapshot.system_key),
                nac = excluded.nac,
                rfss = excluded.rfss,
                site = excluded.site,
                lra = coalesce(excluded.lra, p25_site_snapshot.lra),
                mfid = coalesce(excluded.mfid, p25_site_snapshot.mfid),
                broadcast_clock_ms = coalesce(excluded.broadcast_clock_ms, p25_site_snapshot.broadcast_clock_ms),
                micro_slots = coalesce(excluded.micro_slots, p25_site_snapshot.micro_slots),
                data_service = coalesce(excluded.data_service, p25_site_snapshot.data_service),
                data_access = coalesce(excluded.data_access, p25_site_snapshot.data_access),
                wuid_lease_minutes = coalesce(excluded.wuid_lease_minutes, p25_site_snapshot.wuid_lease_minutes),
                registration_service = coalesce(excluded.registration_service,
                    p25_site_snapshot.registration_service),
                tdma = coalesce(excluded.tdma, p25_site_snapshot.tdma),
                voice_service = coalesce(excluded.voice_service, p25_site_snapshot.voice_service),
                primary_frequency_hz = coalesce(excluded.primary_frequency_hz, p25_site_snapshot.primary_frequency_hz),
                current_control_hz = excluded.current_control_hz
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
            setInteger(statement, 9, systemKey);
            setInteger(statement, 10, snapshot.nac());
            setInteger(statement, 11, snapshot.rfss());
            setInteger(statement, 12, snapshot.site());
            setInteger(statement, 13, snapshot.lra());
            P25NetworkConfigurationSnapshot.SiteStatus status = snapshot.siteStatus();
            setInteger(statement, 14, status != null ? status.mfid() : null);
            setLong(statement, 15, status != null ? status.broadcastClockEpochMilliseconds() : null);
            setInteger(statement, 16, status != null ? status.microSlots() : null);
            setBoolean(statement, 17, status != null ? status.dataService() : null);
            statement.setString(18, status != null ? status.dataAccess() : null);
            setInteger(statement, 19, status != null ? status.wuidLeaseMinutes() : null);
            setBoolean(statement, 20, status != null ? status.registrationService() : null);
            setBoolean(statement, 21, snapshot.tdma());
            setBoolean(statement, 22, status != null ? status.voiceService() : null);
            setLong(statement, 23, snapshot.primaryFrequencyHertz());
            setLong(statement, 24, snapshot.currentControlHertz());
            statement.executeUpdate();
        }
    }

    private static void upsertSiteChannelSummaries(Connection connection, P25ActivityLogRecords.SiteSnapshot snapshot,
                                                   Map<String,SiteChannelEvidence> channels)
        throws SQLException
    {
        for(Map.Entry<String,SiteChannelEvidence> entry: channels.entrySet())
        {
            String key = entry.getKey();
            SiteChannelEvidence channel = entry.getValue();

            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO p25_site_channel_summary (
                    guid, channel_key, descriptor, downlink_hz, uplink_hz, tdma, timeslots,
                    first_seen_ms, last_seen_ms, observation_count
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT(guid, channel_key) DO UPDATE SET
                    descriptor = coalesce(excluded.descriptor, p25_site_channel_summary.descriptor),
                    downlink_hz = coalesce(excluded.downlink_hz, p25_site_channel_summary.downlink_hz),
                    uplink_hz = coalesce(excluded.uplink_hz, p25_site_channel_summary.uplink_hz),
                    tdma = coalesce(excluded.tdma, p25_site_channel_summary.tdma),
                    timeslots = coalesce(excluded.timeslots, p25_site_channel_summary.timeslots),
                    last_seen_ms = max(p25_site_channel_summary.last_seen_ms, excluded.last_seen_ms),
                    observation_count = p25_site_channel_summary.observation_count + 1
                """))
            {
                statement.setString(1, snapshot.guid());
                statement.setString(2, key);
                statement.setString(3, channel.descriptor());
                setLong(statement, 4, channel.downlink());
                setLong(statement, 5, channel.uplink());
                setBoolean(statement, 6, channel.tdma());
                setInteger(statement, 7, channel.timeslots());
                statement.setLong(8, snapshot.observedAtEpochMilliseconds());
                statement.setLong(9, snapshot.observedAtEpochMilliseconds());
                statement.executeUpdate();
            }

            for(ChannelTag tag: channel.summaryTags())
            {
                upsertChannelTagSummary(connection, snapshot.guid(), key, tag,
                    snapshot.observedAtEpochMilliseconds(), 1);
            }
        }
    }

    /**
     * Adds voice and data service evidence learned from control-channel grants to the site's durable channel inventory.
     * RF/site snapshots intentionally contain only stable network facts, so grant observations are projected here
     * without feeding dynamic traffic back into the network stabilizer.
     */
    private static void upsertGrantedChannelSummary(Connection connection,
                                                    P25ActivityLogRecords.ActivityEvent activity) throws SQLException
    {
        Lcn lcn = Lcn.parse(activity.lcn());
        ChannelTag serviceTag = serviceTag(activity);

        if(activity.guid() == null || activity.guid().isBlank() || activity.frequencyHertz() == null ||
            activity.frequencyHertz() <= 0 || lcn.band() == null || lcn.number() == null || serviceTag == null)
        {
            return;
        }

        String channelKey = lcn.channelKey();
        boolean tdma = isTdma(activity);

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_site_channel_summary (
                guid, channel_key, descriptor, downlink_hz, uplink_hz, tdma, timeslots,
                first_seen_ms, last_seen_ms, observation_count
            ) VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, 1)
            ON CONFLICT(guid, channel_key) DO UPDATE SET
                descriptor = coalesce(p25_site_channel_summary.descriptor, excluded.descriptor),
                downlink_hz = coalesce(excluded.downlink_hz, p25_site_channel_summary.downlink_hz),
                tdma = max(coalesce(p25_site_channel_summary.tdma, 0), excluded.tdma),
                timeslots = max(coalesce(p25_site_channel_summary.timeslots, 1), excluded.timeslots),
                last_seen_ms = max(p25_site_channel_summary.last_seen_ms, excluded.last_seen_ms),
                observation_count = p25_site_channel_summary.observation_count + 1
            """))
        {
            statement.setString(1, activity.guid());
            statement.setString(2, channelKey);
            statement.setString(3, channelKey);
            statement.setLong(4, activity.frequencyHertz());
            statement.setInt(5, tdma ? 1 : 0);
            statement.setInt(6, tdma ? 2 : 1);
            statement.setLong(7, activity.observedAtEpochMilliseconds());
            statement.setLong(8, activity.observedAtEpochMilliseconds());
            statement.executeUpdate();
        }

        upsertChannelTagSummary(connection, activity.guid(), channelKey, serviceTag,
            activity.observedAtEpochMilliseconds(), 1);
    }

    private static void upsertChannelTagSummary(Connection connection, String guid, String channelKey,
                                                ChannelTag tag, long timestamp, int observations)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_site_channel_tag_summary
                (guid, channel_key, tag, first_seen_ms, last_seen_ms, observation_count)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(guid, channel_key, tag) DO UPDATE SET
                last_seen_ms = max(p25_site_channel_tag_summary.last_seen_ms, excluded.last_seen_ms),
                observation_count = p25_site_channel_tag_summary.observation_count + excluded.observation_count
            """))
        {
            statement.setString(1, guid);
            statement.setString(2, channelKey);
            statement.setString(3, tag.name());
            statement.setLong(4, timestamp);
            statement.setLong(5, timestamp);
            statement.setInt(6, Math.max(1, observations));
            statement.executeUpdate();
        }
    }

    private static void upsertSiteFrequencyBandSummaries(Connection connection,
                                                         P25ActivityLogRecords.SiteSnapshot snapshot)
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
                INSERT INTO p25_site_frequency_band_summary (
                    guid, band, first_seen_ms, last_seen_ms, observation_count, tdma, base_hz, bandwidth,
                    spacing_hz, transmit_offset_hz, timeslots
                ) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(guid, band) DO UPDATE SET
                    last_seen_ms = excluded.last_seen_ms,
                    observation_count = p25_site_frequency_band_summary.observation_count + 1,
                    tdma = coalesce(excluded.tdma, p25_site_frequency_band_summary.tdma),
                    base_hz = coalesce(excluded.base_hz, p25_site_frequency_band_summary.base_hz),
                    bandwidth = coalesce(excluded.bandwidth, p25_site_frequency_band_summary.bandwidth),
                    spacing_hz = coalesce(excluded.spacing_hz, p25_site_frequency_band_summary.spacing_hz),
                    transmit_offset_hz = coalesce(excluded.transmit_offset_hz, p25_site_frequency_band_summary.transmit_offset_hz),
                    timeslots = coalesce(excluded.timeslots, p25_site_frequency_band_summary.timeslots)
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

    private static void upsertSiteNeighborSummaries(Connection connection, P25ActivityLogRecords.SiteSnapshot snapshot)
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
                INSERT INTO p25_site_neighbor_summary (
                    guid, neighbor_key, system_id, rfss, site, lra, channel_descriptor, downlink_hz,
                    uplink_hz, status, first_seen_ms, last_seen_ms, observation_count
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT(guid, neighbor_key) DO UPDATE SET
                    system_id = coalesce(excluded.system_id, p25_site_neighbor_summary.system_id),
                    rfss = coalesce(excluded.rfss, p25_site_neighbor_summary.rfss),
                    site = coalesce(excluded.site, p25_site_neighbor_summary.site),
                    lra = coalesce(excluded.lra, p25_site_neighbor_summary.lra),
                    channel_descriptor = coalesce(excluded.channel_descriptor, p25_site_neighbor_summary.channel_descriptor),
                    downlink_hz = coalesce(excluded.downlink_hz, p25_site_neighbor_summary.downlink_hz),
                    uplink_hz = coalesce(excluded.uplink_hz, p25_site_neighbor_summary.uplink_hz),
                    status = coalesce(excluded.status, p25_site_neighbor_summary.status),
                    last_seen_ms = excluded.last_seen_ms,
                    observation_count = p25_site_neighbor_summary.observation_count + 1
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

    private static void upsertForeignSystemBandSummaries(Connection connection,
                                                         P25ActivityLogRecords.SiteSnapshot snapshot)
        throws SQLException
    {
        for(P25NetworkConfigurationSnapshot.ForeignSystemBand band: list(snapshot.foreignSystemBands()))
        {
            if(!isValidForeignSystemBand(band))
            {
                continue;
            }

            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO p25_foreign_system_band_summary (
                    guid, foreign_wacn, foreign_system_id, band, channel_type, base_hz, spacing_hz,
                    transmit_offset_hz, first_seen_ms, last_seen_ms, observation_count
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT(guid, foreign_wacn, foreign_system_id, band) DO UPDATE SET
                    channel_type = excluded.channel_type,
                    base_hz = coalesce(excluded.base_hz, p25_foreign_system_band_summary.base_hz),
                    spacing_hz = coalesce(excluded.spacing_hz, p25_foreign_system_band_summary.spacing_hz),
                    transmit_offset_hz = coalesce(excluded.transmit_offset_hz,
                        p25_foreign_system_band_summary.transmit_offset_hz),
                    last_seen_ms = excluded.last_seen_ms,
                    observation_count = p25_foreign_system_band_summary.observation_count + 1
                """))
            {
                setForeignSystemBand(statement, snapshot.guid(), band);
                statement.setLong(9, snapshot.observedAtEpochMilliseconds());
                statement.setLong(10, snapshot.observedAtEpochMilliseconds());
                statement.executeUpdate();
            }
        }
    }

    private static void upsertSitePatchSummaries(Connection connection, P25ActivityLogRecords.SiteSnapshot snapshot)
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
                INSERT INTO p25_site_patch_group_summary (
                    guid, patch_group, version, first_seen_ms, last_seen_ms, observation_count
                ) VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT(guid, patch_group) DO UPDATE SET
                    version = coalesce(excluded.version, p25_site_patch_group_summary.version),
                    last_seen_ms = excluded.last_seen_ms,
                    observation_count = p25_site_patch_group_summary.observation_count + 1
                """))
            {
                statement.setString(1, snapshot.guid());
                statement.setInt(2, patchGroup.patchGroup());
                setInteger(statement, 3, patchGroup.version());
                statement.setLong(4, snapshot.observedAtEpochMilliseconds());
                statement.setLong(5, snapshot.observedAtEpochMilliseconds());
                statement.executeUpdate();
            }

            upsertSitePatchTalkgroupSummaries(connection, snapshot, patchGroup);
            upsertSitePatchRadioSummaries(connection, snapshot, patchGroup);
        }
    }

    private static void upsertSitePatchTalkgroupSummaries(Connection connection,
        P25ActivityLogRecords.SiteSnapshot snapshot, P25NetworkConfigurationSnapshot.PatchGroup patchGroup)
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
                INSERT INTO p25_site_patch_group_talkgroup_summary (
                    guid, patch_group, talkgroup_id, first_seen_ms, last_seen_ms, observation_count
                ) VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT(guid, patch_group, talkgroup_id) DO UPDATE SET
                    last_seen_ms = excluded.last_seen_ms,
                    observation_count = p25_site_patch_group_talkgroup_summary.observation_count + 1
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

    private static void upsertSitePatchRadioSummaries(Connection connection,
        P25ActivityLogRecords.SiteSnapshot snapshot, P25NetworkConfigurationSnapshot.PatchGroup patchGroup)
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
                INSERT INTO p25_site_patch_group_radio_summary (
                    guid, patch_group, radio_id, first_seen_ms, last_seen_ms, observation_count
                ) VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT(guid, patch_group, radio_id) DO UPDATE SET
                    last_seen_ms = excluded.last_seen_ms,
                    observation_count = p25_site_patch_group_radio_summary.observation_count + 1
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

    private static boolean siteSnapshotChanged(Connection connection, P25ActivityLogRecords.SiteSnapshot snapshot)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT snapshot_hash FROM p25_site_snapshot WHERE guid = ?"))
        {
            statement.setString(1, snapshot.guid());

            try(ResultSet resultSet = statement.executeQuery())
            {
                return !resultSet.next() || !java.util.Objects.equals(snapshot.snapshotHash(), resultSet.getString(1));
            }
        }
    }

    private static void replaceCurrentSiteFacts(Connection connection, P25ActivityLogRecords.SiteSnapshot snapshot,
                                                Map<String,SiteChannelEvidence> channels)
        throws SQLException
    {
        clearCurrentSiteFacts(connection, snapshot.guid());
        long timestamp = snapshot.observedAtEpochMilliseconds();

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_site_channel
                (guid, channel_key, descriptor, downlink_hz, uplink_hz, tdma, timeslots, callsign, confirmed_at_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(guid, channel_key) DO UPDATE SET
                descriptor = coalesce(excluded.descriptor, p25_site_channel.descriptor),
                downlink_hz = coalesce(excluded.downlink_hz, p25_site_channel.downlink_hz),
                uplink_hz = coalesce(excluded.uplink_hz, p25_site_channel.uplink_hz),
                tdma = coalesce(excluded.tdma, p25_site_channel.tdma),
                timeslots = coalesce(excluded.timeslots, p25_site_channel.timeslots),
                callsign = coalesce(excluded.callsign, p25_site_channel.callsign),
                confirmed_at_ms = max(excluded.confirmed_at_ms, p25_site_channel.confirmed_at_ms)
            """))
        {
            for(Map.Entry<String,SiteChannelEvidence> entry: channels.entrySet())
            {
                SiteChannelEvidence channel = entry.getValue();
                statement.setString(1, snapshot.guid());
                statement.setString(2, entry.getKey());
                statement.setString(3, channel.descriptor());
                setLong(statement, 4, channel.downlink());
                setLong(statement, 5, channel.uplink());
                setBoolean(statement, 6, channel.tdma());
                setInteger(statement, 7, channel.timeslots());
                statement.setString(8, channel.callsign());
                statement.setLong(9, timestamp);
                statement.addBatch();
            }

            statement.executeBatch();
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_site_channel_tag (guid, channel_key, tag, confirmed_at_ms)
            VALUES (?, ?, ?, ?)
            """))
        {
            for(Map.Entry<String,SiteChannelEvidence> entry: channels.entrySet())
            {
                for(ChannelTag tag: entry.getValue().currentTags())
                {
                    statement.setString(1, snapshot.guid());
                    statement.setString(2, entry.getKey());
                    statement.setString(3, tag.name());
                    statement.setLong(4, timestamp);
                    statement.addBatch();
                }
            }

            statement.executeBatch();
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_site_frequency_band
                (guid, band, tdma, base_hz, bandwidth, spacing_hz, transmit_offset_hz, timeslots, confirmed_at_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """))
        {
            for(P25NetworkConfigurationSnapshot.FrequencyBand band: list(snapshot.frequencyBands()))
            {
                if(band != null && band.band() != null)
                {
                    statement.setString(1, snapshot.guid());
                    statement.setInt(2, band.band());
                    setBoolean(statement, 3, band.tdma());
                    setLong(statement, 4, band.base());
                    setInteger(statement, 5, band.bandwidth());
                    setLong(statement, 6, band.spacing());
                    setLong(statement, 7, band.transmitOffset());
                    setInteger(statement, 8, band.timeslots());
                    statement.setLong(9, timestamp);
                    statement.addBatch();
                }
            }

            statement.executeBatch();
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_site_neighbor
                (guid, neighbor_key, system_id, rfss, site, lra, channel_descriptor, downlink_hz, uplink_hz,
                 status, confirmed_at_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """))
        {
            for(P25NetworkConfigurationSnapshot.NeighborSite neighbor: list(snapshot.neighborSites()))
            {
                String key = neighborKey(neighbor);

                if(key != null)
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
                    statement.setLong(11, timestamp);
                    statement.addBatch();
                }
            }

            statement.executeBatch();
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_foreign_system_band
                (guid, foreign_wacn, foreign_system_id, band, channel_type, base_hz, spacing_hz,
                 transmit_offset_hz, confirmed_at_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """))
        {
            for(P25NetworkConfigurationSnapshot.ForeignSystemBand band: list(snapshot.foreignSystemBands()))
            {
                if(isValidForeignSystemBand(band))
                {
                    setForeignSystemBand(statement, snapshot.guid(), band);
                    statement.setLong(9, timestamp);
                    statement.addBatch();
                }
            }

            statement.executeBatch();
        }

        try(PreparedStatement group = connection.prepareStatement("""
                INSERT INTO p25_site_patch_group (guid, patch_group, version, confirmed_at_ms) VALUES (?, ?, ?, ?)
                """);
            PreparedStatement talkgroup = connection.prepareStatement("""
                INSERT INTO p25_site_patch_group_talkgroup
                    (guid, patch_group, talkgroup_id, confirmed_at_ms) VALUES (?, ?, ?, ?)
                """);
            PreparedStatement radio = connection.prepareStatement("""
                INSERT INTO p25_site_patch_group_radio
                    (guid, patch_group, radio_id, confirmed_at_ms) VALUES (?, ?, ?, ?)
                """))
        {
            for(P25NetworkConfigurationSnapshot.PatchGroup patch: list(snapshot.patchGroups()))
            {
                if(patch == null || patch.patchGroup() == null)
                {
                    continue;
                }

                group.setString(1, snapshot.guid());
                group.setInt(2, patch.patchGroup());
                setInteger(group, 3, patch.version());
                group.setLong(4, timestamp);
                group.addBatch();

                for(Integer member: list(patch.talkgroups()))
                {
                    if(member != null)
                    {
                        talkgroup.setString(1, snapshot.guid());
                        talkgroup.setInt(2, patch.patchGroup());
                        talkgroup.setInt(3, member);
                        talkgroup.setLong(4, timestamp);
                        talkgroup.addBatch();
                    }
                }

                for(Integer member: list(patch.radios()))
                {
                    if(member != null)
                    {
                        radio.setString(1, snapshot.guid());
                        radio.setInt(2, patch.patchGroup());
                        radio.setInt(3, member);
                        radio.setLong(4, timestamp);
                        radio.addBatch();
                    }
                }
            }

            group.executeBatch();
            talkgroup.executeBatch();
            radio.executeBatch();
        }
    }

    private static void clearCurrentSiteFacts(Connection connection, String guid) throws SQLException
    {
        for(String table: List.of("p25_site_patch_group_radio", "p25_site_patch_group_talkgroup",
            "p25_site_patch_group", "p25_site_neighbor", "p25_foreign_system_band", "p25_site_frequency_band",
            "p25_site_channel_tag", "p25_site_channel"))
        {
            try(PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE guid = ?"))
            {
                statement.setString(1, guid);
                statement.executeUpdate();
            }
        }
    }

    private static void confirmCurrentSiteFacts(Connection connection, P25ActivityLogRecords.SiteSnapshot snapshot)
        throws SQLException
    {
        for(String table: List.of("p25_site_patch_group_radio", "p25_site_patch_group_talkgroup",
            "p25_site_patch_group", "p25_site_neighbor", "p25_foreign_system_band", "p25_site_frequency_band",
            "p25_site_channel_tag", "p25_site_channel"))
        {
            try(PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + table + " SET confirmed_at_ms = ? WHERE guid = ?"))
            {
                statement.setLong(1, snapshot.observedAtEpochMilliseconds());
                statement.setString(2, snapshot.guid());
                statement.executeUpdate();
            }
        }
    }

    private static <T> List<T> list(List<T> values)
    {
        return values != null ? values : List.of();
    }

    private static boolean isValidForeignSystemBand(P25NetworkConfigurationSnapshot.ForeignSystemBand band)
    {
        return band != null && band.wacn() != null && band.wacn() >= 0 && band.wacn() <= 0xFFFFF &&
            band.system() != null && band.system() >= 0 && band.system() <= 0xFFF &&
            band.band() != null && band.band() >= 0 && band.band() <= 0xF &&
            band.channelType() != null && band.channelType() >= 0 && band.channelType() <= 0xF;
    }

    private static void setForeignSystemBand(PreparedStatement statement, String guid,
                                             P25NetworkConfigurationSnapshot.ForeignSystemBand band)
        throws SQLException
    {
        statement.setString(1, guid);
        statement.setInt(2, band.wacn());
        statement.setInt(3, band.system());
        statement.setInt(4, band.band());
        statement.setInt(5, band.channelType());
        setLong(statement, 6, band.base());
        setLong(statement, 7, band.spacing());
        setLong(statement, 8, band.transmitOffset());
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

    private static ReceiverContextIdentity selectContextIdentityByGuid(Connection connection, String guid)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT id, system_key, kind_code FROM receiver_context WHERE guid = ?"))
        {
            statement.setString(1, guid);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    int systemKey = resultSet.getInt("system_key");
                    return new ReceiverContextIdentity(resultSet.getInt("id"),
                        resultSet.wasNull() ? null : systemKey, resultSet.getInt("kind_code"));
                }

                return null;
            }
        }
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

    private static void validateIndexColumns(Connection connection, String index, List<String> expected)
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

    /**
     * Drains expired shared control-channel quality buckets in bounded, retention-indexed batches. The ordered
     * covering-index selection prevents a full table scan and the composite primary-key lookup keeps each delete
     * batch deterministic for the WITHOUT ROWID table.
     */
    private static int deleteExpiredControlChannelQuality(Connection connection, long cutoffEpochMilliseconds)
        throws SQLException
    {
        int total = 0;

        try(PreparedStatement statement = connection.prepareStatement("""
            DELETE FROM p25_control_channel_quality
            WHERE (guid, frequency_hz, bucket_start_ms) IN (
                SELECT guid, frequency_hz, bucket_start_ms
                FROM p25_control_channel_quality INDEXED BY idx_p25_control_quality_retention
                WHERE observed_at_ms < ?
                ORDER BY observed_at_ms, guid, frequency_hz, bucket_start_ms
                LIMIT ?
            )
            """))
        {
            int deleted;

            do
            {
                statement.setLong(1, cutoffEpochMilliseconds);
                statement.setInt(2, RETENTION_DELETE_BATCH_SIZE);
                deleted = statement.executeUpdate();
                total = Math.addExact(total, deleted);
            }
            while(deleted > 0);
        }

        return total;
    }

    private static int deleteAll(Connection connection, String table) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            return statement.executeUpdate("DELETE FROM " + table);
        }
    }

    private static int deleteByContextGuid(Connection connection, String table, String guid) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM " + table + " WHERE context_id IN (SELECT id FROM receiver_context WHERE guid = ?)"))
        {
            statement.setString(1, guid);
            return statement.executeUpdate();
        }
    }

    private static int deleteByGuid(Connection connection, String table, String guid) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE guid = ?"))
        {
            statement.setString(1, guid);
            return statement.executeUpdate();
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
            boolean counted = activity.action() == action;

            if(action == P25ActivityLogRecords.Action.CALL)
            {
                counted = activity.countedCall();
            }

            statement.setInt(index++, counted ? 1 : 0);
        }

        return index;
    }

    private static boolean isServiceGrant(P25ActivityLogRecords.ActivityEvent activity)
    {
        return activity.action() == P25ActivityLogRecords.Action.GRANT && serviceTag(activity) != null;
    }

    private static ChannelTag serviceTag(P25ActivityLogRecords.ActivityEvent activity)
    {
        if(activity == null || activity.eventType() == null)
        {
            return null;
        }

        try
        {
            DecodeEventType eventType = DecodeEventType.valueOf(activity.eventType());

            return ChannelTag.fromService(eventType);
        }
        catch(IllegalArgumentException e)
        {
            return null;
        }
    }

    private static boolean isTdma(P25ActivityLogRecords.ActivityEvent activity)
    {
        return "APCO25_PHASE2".equals(activity.protocol()) ||
            (activity.decoder() != null && activity.decoder().contains("PHASE2")) ||
            (activity.lcn() != null && activity.lcn().contains("TS"));
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

    private static long qualityBucketStart(long observedAtEpochMilliseconds)
    {
        return observedAtEpochMilliseconds - Math.floorMod(observedAtEpochMilliseconds, QUALITY_BUCKET_MILLISECONDS);
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
            Lcn lcn = Lcn.parse(channel.descriptor());
            return lcn.isValid() ? lcn.channelKey() : channel.descriptor();
        }

        if(channel.downlink() != null && channel.downlink() > 0)
        {
            return Long.toString(channel.downlink());
        }

        return null;
    }

    private static Map<String,SiteChannelEvidence> mergeSiteChannels(P25ActivityLogRecords.SiteSnapshot snapshot)
    {
        Map<String,SiteChannelEvidence> merged = new LinkedHashMap<>();

        for(P25NetworkConfigurationSnapshot.Channel channel: list(snapshot.channels()))
        {
            String key = channelKey(channel);

            if(key != null)
            {
                SiteChannelEvidence incoming = SiteChannelEvidence.from(channel);
                SiteChannelEvidence existing = merged.putIfAbsent(key, incoming);

                if(existing != null)
                {
                    merged.put(key, existing.merge(incoming));
                    warnSiteChannelCollision(snapshot.guid(), key, existing, incoming);
                }
            }
        }

        return merged;
    }

    private record ReceiverContextIdentity(int contextId, Integer systemKey, int kindCode)
    {
    }

    private record SiteChannelEvidence(String descriptor, Long downlink, Long uplink, Boolean tdma, Integer timeslots,
                                       String callsign, Set<ChannelTag> tags)
    {
        private SiteChannelEvidence
        {
            tags = tags != null && !tags.isEmpty() ? Set.copyOf(tags) : Set.of();
        }

        private static SiteChannelEvidence from(P25NetworkConfigurationSnapshot.Channel channel)
        {
            EnumSet<ChannelTag> tags = EnumSet.noneOf(ChannelTag.class);
            ChannelTag tag = ChannelTag.fromNetworkRole(channel != null ? channel.role() : null);

            if(tag != null)
            {
                tags.add(tag);
            }

            return new SiteChannelEvidence(channel != null ? channel.descriptor() : null,
                channel != null ? channel.downlink() : null, channel != null ? channel.uplink() : null,
                channel != null ? channel.tdma() : null, channel != null ? channel.timeslots() : null,
                channel != null ? channel.callsign() : null, tags);
        }

        private SiteChannelEvidence merge(SiteChannelEvidence other)
        {
            EnumSet<ChannelTag> mergedTags = EnumSet.noneOf(ChannelTag.class);
            mergedTags.addAll(tags);
            mergedTags.addAll(other.tags);
            Boolean mergedTdma = Boolean.TRUE.equals(tdma) || Boolean.TRUE.equals(other.tdma) ? Boolean.TRUE :
                firstNonNull(tdma, other.tdma);
            Integer mergedTimeslots = timeslots != null && other.timeslots != null ?
                Math.max(timeslots, other.timeslots) : firstNonNull(timeslots, other.timeslots);
            return new SiteChannelEvidence(firstNonBlank(descriptor, other.descriptor),
                firstNonNull(downlink, other.downlink), firstNonNull(uplink, other.uplink), mergedTdma,
                mergedTimeslots, firstNonBlank(callsign, other.callsign), mergedTags);
        }

        private Set<ChannelTag> currentTags()
        {
            return tags;
        }

        private Set<ChannelTag> summaryTags()
        {
            EnumSet<ChannelTag> summary = EnumSet.noneOf(ChannelTag.class);

            for(ChannelTag tag: tags)
            {
                summary.add(tag.asHistoricalEvidence());
            }

            return summary;
        }
    }

    private static <T> T firstNonNull(T preferred, T fallback)
    {
        return preferred != null ? preferred : fallback;
    }

    private static String firstNonBlank(String preferred, String fallback)
    {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private static final org.slf4j.Logger mLog =
        org.slf4j.LoggerFactory.getLogger(P25ActivityLogSchema.class);
    private static final java.util.concurrent.ConcurrentMap<String,Long> mSiteChannelCollisionWarnings =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static void warnSiteChannelCollision(String guid, String key,
                                                 SiteChannelEvidence existing, SiteChannelEvidence incoming)
    {
        String warningKey = guid + ':' + key;
        long now = System.currentTimeMillis();
        Long previous = mSiteChannelCollisionWarnings.get(warningKey);

        if(previous == null || now - previous >= 300_000L)
        {
            mSiteChannelCollisionWarnings.put(warningKey, now);
            mLog.warn("Merging duplicate P25 site channel [{}] for site [{}]: tags [{}] and [{}]",
                key, guid, existing.tags(), incoming.tags());
        }
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
        boolean isValid()
        {
            return band != null && number != null;
        }

        String channelKey()
        {
            return isValid() ? band + "-" + number : null;
        }

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

    private static void setDouble(PreparedStatement statement, int index, Double value) throws SQLException
    {
        if(value != null && Double.isFinite(value))
        {
            statement.setDouble(index, value);
        }
        else
        {
            statement.setNull(index, java.sql.Types.REAL);
        }
    }
}
