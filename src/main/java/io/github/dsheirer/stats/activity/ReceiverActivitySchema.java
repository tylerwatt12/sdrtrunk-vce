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
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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
 * The v30 semantics use canonical saved-channel identities. Trunked P25, DMR and NXDN share one protocol-neutral
 * identity projection while
 * saved receiver channels own site observations. Detailed event rows are optional, while compact identity and hourly
 * summaries are always updated when stats logging is enabled.
 */
public class ReceiverActivitySchema
{
    public static final String CONVENTIONAL_CALL_OUTPUT_METRICS_STARTED_AT_KEY =
        "conventional_call_output_metrics_started_at_ms";
    public static final String RADIO_SYSTEM_METRICS_STARTED_AT_KEY =
        "radio_system_metrics_started_at_ms";
    public static final String TRUNKED_LOGICAL_CALL_METRICS_STARTED_AT_KEY =
        "trunked_logical_call_metrics_started_at_ms";
    public static final int IDENTITY_ROLE_DESTINATION = 1;
    public static final int IDENTITY_ROLE_SOURCE = 2;
    public static final int IDENTITY_KIND_CHANNEL_OR_UNKNOWN = 0;
    public static final int IDENTITY_KIND_TALKGROUP = 1;
    public static final int IDENTITY_KIND_RADIO = 2;
    public static final int IDENTITY_KIND_PATCH_GROUP = 3;
    private static final long HOUR_MILLISECONDS = 3_600_000L;
    private static final long QUALITY_BUCKET_MILLISECONDS = 10_000L;
    static final int RETENTION_DELETE_BATCH_SIZE = 1_000;
    private static final int NULL_TIMESLOT = -1;

    private static final int RECEIVER_TRUNKED_SITE = 1;
    private static final int RECEIVER_CONVENTIONAL_P25 = 2;
    private static final int RECEIVER_CONVENTIONAL_DMR = 3;
    private static final int RECEIVER_CONVENTIONAL_NXDN = 4;
    private static final int RECEIVER_CONVENTIONAL_ANALOG = 10;

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
    private static final int P25_EVERYONE_TALKGROUP = 0xFFFF;
    private static final int P25_FIRST_SPECIAL_RADIO = 0xFFFFFC;

    private static final List<ReceiverActivityRecords.Action> ACTIONS = ReceiverActivityCodes.actionCodes();
    private static final String ACTION_CODES = ACTIONS.stream()
        .map(action -> Integer.toString(action.code()))
        .collect(Collectors.joining(", "));
    private static final String EVENT_TYPE_CODES = ReceiverActivityCodes.eventTypeCodes().stream()
        .map(eventType -> Integer.toString(eventType.code()))
        .collect(Collectors.joining(", "));
    private static final List<ReceiverActivityRecords.Action> TRUNKED_SIGNALING_ACTIONS = ACTIONS.stream()
        .filter(action -> action != ReceiverActivityRecords.Action.CALL).toList();
    private static final List<String> ACTION_COUNT_COLUMNS = ACTIONS.stream()
        .map(action -> action.name().toLowerCase(Locale.ROOT) + "_count")
        .toList();
    private static final String ACTION_COUNT_DEFINITIONS = ACTION_COUNT_COLUMNS.stream()
        .map(column -> column + " INTEGER NOT NULL DEFAULT 0 CHECK(" + column + " >= 0)")
        .collect(Collectors.joining(",\n                    "));
    private static final String ACTION_INSERT_COLUMNS = String.join(", ", ACTION_COUNT_COLUMNS);
    private static final String ACTION_INSERT_PLACEHOLDERS = ACTION_COUNT_COLUMNS.stream()
        .map(column -> "?")
        .collect(Collectors.joining(", "));
    private static final List<String> TRUNKED_SIGNALING_ACTION_COUNT_COLUMNS = TRUNKED_SIGNALING_ACTIONS.stream()
        .map(action -> action.name().toLowerCase(Locale.ROOT) + "_count")
        .toList();
    private static final String TRUNKED_SIGNALING_ACTION_COUNT_DEFINITIONS =
        TRUNKED_SIGNALING_ACTION_COUNT_COLUMNS.stream()
            .map(column -> column + " INTEGER NOT NULL DEFAULT 0 CHECK(" + column + " >= 0)")
            .collect(Collectors.joining(",\n                    "));
    private static final String TRUNKED_SIGNALING_ACTION_INSERT_COLUMNS =
        String.join(", ", TRUNKED_SIGNALING_ACTION_COUNT_COLUMNS);
    private static final String TRUNKED_SIGNALING_ACTION_INSERT_PLACEHOLDERS =
        TRUNKED_SIGNALING_ACTION_COUNT_COLUMNS.stream().map(column -> "?").collect(Collectors.joining(", "));

    private ReceiverActivitySchema()
    {
    }

    public static void create(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate(receiverChannelSql());
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS receiver_activity_event (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    channel_id INTEGER NOT NULL REFERENCES receiver_channel(id) ON DELETE CASCADE,
                    observed_at_ms INTEGER NOT NULL CHECK(observed_at_ms > 0),
                    action_code INTEGER NOT NULL CHECK(action_code IN (%s)),
                    event_type_code INTEGER CHECK(event_type_code IS NULL OR event_type_code IN (%s)),
                    source_radio_id INTEGER CHECK(source_radio_id IS NULL OR source_radio_id >= 0),
                    target_id INTEGER CHECK(target_id IS NULL OR target_id >= 0),
                    target_kind_code INTEGER CHECK(target_kind_code IS NULL OR target_kind_code IN (1, 2, 3)),
                    frequency_hz INTEGER CHECK(frequency_hz IS NULL OR frequency_hz > 0),
                    lcn_band INTEGER CHECK(lcn_band IS NULL OR lcn_band >= 0),
                    lcn_number INTEGER CHECK(lcn_number IS NULL OR lcn_number >= 0),
                    timeslot INTEGER CHECK(timeslot IS NULL OR timeslot >= 0),
                    encrypted INTEGER NOT NULL DEFAULT 0 CHECK(encrypted IN (0, 1)),
                    encryption_algorithm_id INTEGER,
                    encryption_key_id INTEGER,
                    CHECK((lcn_band IS NULL) = (lcn_number IS NULL)),
                    CHECK(target_id IS NOT NULL OR target_kind_code IS NULL)
                )
                """.formatted(ACTION_CODES, EVENT_TYPE_CODES));
            statement.executeUpdate(createActivityEventTalkgroupMemberSql());
            createTrunkedCallTables(statement);
            RadioSystemSchema.create(statement);
            createConventionalTables(statement);
            createConventionalCallIdentityTable(statement);
            createP25SiteTables(statement);
            createControlChannelQualityTable(statement);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS statistics_status (
                    key TEXT PRIMARY KEY CHECK(length(trim(key)) > 0),
                    value TEXT,
                    updated_at_ms INTEGER NOT NULL CHECK(updated_at_ms > 0)
                )
                """);
            createIndexesAndViews(statement);
        }

        SdrTrunkDatabaseStartup.setMetadata(connection, CONVENTIONAL_CALL_OUTPUT_METRICS_STARTED_AT_KEY,
            Long.toString(System.currentTimeMillis()));
        SdrTrunkDatabaseStartup.setMetadata(connection, RADIO_SYSTEM_METRICS_STARTED_AT_KEY,
            Long.toString(System.currentTimeMillis()));
        SdrTrunkDatabaseStartup.setMetadata(connection, TRUNKED_LOGICAL_CALL_METRICS_STARTED_AT_KEY,
            Long.toString(System.currentTimeMillis()));
    }

    public static void validate(Connection connection) throws SQLException
    {
        SqliteSchemaValidator.validate(connection, TABLES, INDEXES, VIEWS, List.of());
        RadioSystemSchema.validate(connection);
        List<SqliteSchemaValidator.Definition> exactDefinitions =
            new ArrayList<>(RadioSystemSchema.definitions());
        exactDefinitions.addAll(List.of(
            new SqliteSchemaValidator.Definition("table", "receiver_channel", receiverChannelSql()),
            new SqliteSchemaValidator.Definition("table", "activity_event_talkgroup_member",
                createActivityEventTalkgroupMemberSql()),
            new SqliteSchemaValidator.Definition("table", "conventional_call_identity_bucket",
                createConventionalCallIdentityBucketSql()),
            new SqliteSchemaValidator.Definition("table", "trunked_logical_call_bucket",
                createTrunkedLogicalCallBucketSql()),
            new SqliteSchemaValidator.Definition("table", "trunked_logical_call_identity_bucket",
                createTrunkedLogicalCallIdentityBucketSql()),
            new SqliteSchemaValidator.Definition("table", "p25_site_call_bucket",
                createP25SiteCallBucketSql()),
            new SqliteSchemaValidator.Definition("table", "p25_site_call_identity_bucket",
                createP25SiteCallIdentityBucketSql()),
            new SqliteSchemaValidator.Definition("view", "receiver_activity_event_resolved", createResolvedViewSql())));
        SqliteSchemaValidator.validateDefinitions(connection, exactDefinitions);
        validateRequiredForeignKeys(connection);
        validatePositiveMetadataTimestamp(connection, CONVENTIONAL_CALL_OUTPUT_METRICS_STARTED_AT_KEY);
        validatePositiveMetadataTimestamp(connection, RADIO_SYSTEM_METRICS_STARTED_AT_KEY);
        validatePositiveMetadataTimestamp(connection, TRUNKED_LOGICAL_CALL_METRICS_STARTED_AT_KEY);
        validateIndexColumns(connection, "idx_trunked_control_quality_retention",
            List.of("observed_at_ms", "channel_id", "frequency_hz", "bucket_start_ms"));
        validateIndexColumns(connection, "idx_conventional_bucket_dashboard_time",
            List.of("bucket_start_ms", "channel_id"));
        validateIndexColumns(connection, "idx_conventional_call_identity_dashboard_time",
            List.of("bucket_start_ms", "identity_role_code", "identity_kind_code", "channel_id", "identity_id"));
        validateIndexColumns(connection, "idx_trunked_logical_call_bucket_time",
            List.of("bucket_start_ms", "radio_system_id"));
        validateIndexColumns(connection, "idx_trunked_logical_identity_dashboard_time",
            List.of("bucket_start_ms", "identity_role_code", "identity_kind_code", "radio_system_id", "identity_id"));
        validateIndexColumns(connection, "idx_p25_site_call_bucket_time",
            List.of("bucket_start_ms", "radio_system_id", "learned_site_id"));
        validateIndexColumns(connection, "idx_p25_site_call_identity_time",
            List.of("learned_site_id", "radio_system_id", "bucket_start_ms", "identity_role_code",
                "identity_kind_code", "identity_id"));
        validateIndexColumns(connection, "idx_p25_site_call_identity_retention",
            List.of("bucket_start_ms", "radio_system_id", "learned_site_id", "identity_role_code",
                "identity_kind_code", "identity_id"));
    }

    static Long recordActivity(Connection connection, ReceiverActivityRecords.ActivityEvent activity,
                               boolean detailedEventHistoryEnabled) throws SQLException
    {
        Long activityId = null;
        int channelId = upsertReceiverChannel(connection, ReceiverChannelMetadata.from(activity));
        ReceiverChannelIdentity channel = selectReceiverChannelIdentity(connection, activity.configurationId());
        int activityProtocol = TrunkedIdentityPolicy.protocolFamilyCode(activity.protocol());

        if(!matchesReceiverChannel(channel, receiverKindCode(activity.receiverKind()), activityProtocol))
        {
            return null;
        }

        if(activityProtocol == TrunkedIdentityPolicy.PROTOCOL_P25 &&
            !matchesCurrentP25Generation(channel, activity))
        {
            return null;
        }

        if(activity.receiverKind() == ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE)
        {
            RadioSystemSchema.RadioSystem radioSystem =
                RadioSystemSchema.recordActivity(connection, activity, channelId);

            if(radioSystem == null)
            {
                return null;
            }

            if(detailedEventHistoryEnabled && activity.action() != ReceiverActivityRecords.Action.CONTINUE)
            {
                activityId = insertReceiverActivityEvent(connection, activity, channelId);
            }

            upsertTrunkedSignalingMetrics(connection, activity, channelId);

        }
        else if(isConventional(activity.receiverKind()))
        {
            if(detailedEventHistoryEnabled && activity.action() != ReceiverActivityRecords.Action.CONTINUE)
            {
                activityId = insertReceiverActivityEvent(connection, activity, channelId);
            }

            upsertConventionalSummary(connection, activity, channelId);
        }

        if(activity.countedCall() && isConventional(activity.receiverKind()))
        {
            upsertCallIdentityBuckets(connection, activity, channelId);
        }

        return activityId;
    }

    private static String receiverChannelSql()
    {
        return """
            CREATE TABLE IF NOT EXISTS receiver_channel (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                configuration_id TEXT NOT NULL UNIQUE
                    REFERENCES configuration_channel(configuration_id) ON DELETE CASCADE
                    CHECK(
                        length(configuration_id) = 36 AND configuration_id = lower(configuration_id)
                        AND substr(configuration_id, 9, 1) = '-'
                        AND substr(configuration_id, 14, 1) = '-'
                        AND substr(configuration_id, 19, 1) = '-'
                        AND substr(configuration_id, 24, 1) = '-'
                        AND length(replace(configuration_id, '-', '')) = 32
                        AND replace(configuration_id, '-', '') NOT GLOB '*[^0-9a-f]*'
                    ),
                first_seen_ms INTEGER NOT NULL CHECK(first_seen_ms > 0),
                last_seen_ms INTEGER NOT NULL CHECK(last_seen_ms >= first_seen_ms),
                radio_system_id INTEGER REFERENCES radio_system(id) ON DELETE SET NULL,
                nac INTEGER CHECK(nac IS NULL OR nac BETWEEN 0 AND 4095),
                rfss INTEGER CHECK(rfss IS NULL OR rfss BETWEEN 0 AND 255),
                site INTEGER CHECK(site IS NULL OR site BETWEEN 0 AND 255),
                current_control_hz INTEGER CHECK(current_control_hz IS NULL OR current_control_hz > 0),
                UNIQUE(id, radio_system_id)
            )
            """;
    }

    static boolean applyConventionalCallOutput(Connection connection,
                                               ReceiverActivityRecords.ConventionalCallOutput conventionalOutput)
        throws SQLException
    {
        if(conventionalOutput == null || conventionalOutput.callStartEpochMilliseconds() <= 0 ||
            conventionalOutput.output() == null ||
            conventionalOutput.configurationId() == null || conventionalOutput.configurationId().isBlank())
        {
            return false;
        }

        ReceiverChannelIdentity channel = selectReceiverChannelIdentity(connection,
            conventionalOutput.configurationId());

        if(channel == null ||
            conventionalOutput.callStartEpochMilliseconds() < channel.firstSeenEpochMilliseconds())
        {
            return false;
        }

        int recorded = conventionalOutput.output() == ReceiverActivityRecords.CallOutput.RECORDED ? 1 : 0;
        int streamed = conventionalOutput.output() == ReceiverActivityRecords.CallOutput.STREAMED ? 1 : 0;
        long callStart = conventionalOutput.callStartEpochMilliseconds();
        long bucket = bucketStart(callStart);

        if(channel.kindCode() != RECEIVER_TRUNKED_SITE)
        {
            long frequency = conventionalOutput.frequencyHertz() != null &&
                conventionalOutput.frequencyHertz() > 0 ? conventionalOutput.frequencyHertz() :
                channel.primaryFrequencyHertz() != null ? channel.primaryFrequencyHertz() : 0;

            if(frequency <= 0)
            {
                return false;
            }

            int timeslot = summaryTimeslot(conventionalOutput.timeslot());

            try(PreparedStatement summary = connection.prepareStatement("""
                    INSERT INTO conventional_activity_summary (
                        channel_id, frequency_hz, timeslot, first_seen_ms, last_seen_ms,
                        recorded_count, streamed_count
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(channel_id, frequency_hz, timeslot) DO UPDATE SET
                        first_seen_ms = min(conventional_activity_summary.first_seen_ms, excluded.first_seen_ms),
                        last_seen_ms = max(conventional_activity_summary.last_seen_ms, excluded.last_seen_ms),
                        recorded_count = conventional_activity_summary.recorded_count + excluded.recorded_count,
                        streamed_count = conventional_activity_summary.streamed_count + excluded.streamed_count
                    """);
                PreparedStatement hourly = connection.prepareStatement("""
                    INSERT INTO conventional_activity_bucket (
                        channel_id, frequency_hz, timeslot, bucket_start_ms, recorded_count, streamed_count
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(channel_id, frequency_hz, timeslot, bucket_start_ms) DO UPDATE SET
                        recorded_count = conventional_activity_bucket.recorded_count + excluded.recorded_count,
                        streamed_count = conventional_activity_bucket.streamed_count + excluded.streamed_count
                    """))
            {
                summary.setInt(1, channel.channelId());
                summary.setLong(2, frequency);
                summary.setInt(3, timeslot);
                summary.setLong(4, callStart);
                summary.setLong(5, callStart);
                summary.setInt(6, recorded);
                summary.setInt(7, streamed);
                summary.executeUpdate();

                hourly.setInt(1, channel.channelId());
                hourly.setLong(2, frequency);
                hourly.setInt(3, timeslot);
                hourly.setLong(4, bucket);
                hourly.setInt(5, recorded);
                hourly.setInt(6, streamed);
                hourly.executeUpdate();
            }

            upsertConventionalCallOutputIdentityBuckets(connection, conventionalOutput, channel.channelId(),
                TrunkedIdentityPolicy.protocolFamilyCode(channel.protocolCode()), recorded, streamed);
            return true;
        }

        //Trunked outputs are owned by the resolved logical-call path. A receiver-leg completion cannot mutate them.
        return false;
    }

    /**
     * Stores one coordinator-resolved trunked call. P25 additionally records one observation for each distinct
     * learned site. The process-local logical id is intentionally not persisted.
     */
    static boolean recordResolvedLogicalCall(Connection connection, ReceiverActivityRecords.ResolvedLogicalCall call)
        throws SQLException
    {
        if(call == null)
        {
            return false;
        }

        int protocol = TrunkedIdentityPolicy.protocolFamilyCode(call.protocol());
        RadioSystemSchema.RadioSystem radioSystem = resolveLogicalCallRadioSystem(connection, call, protocol);
        if(radioSystem == null)
        {
            return false;
        }

        long bucket = bucketStart(call.callStartEpochMilliseconds());
        int encrypted = call.encrypted() ? 1 : 0;
        upsertLogicalCallBucket(connection, radioSystem.radioSystemId(), bucket, 1, encrypted, 0, 0);
        upsertLogicalCallIdentities(connection, radioSystem.radioSystemId(), bucket, call, 1, encrypted, 0, 0,
            false, null);

        if(protocol == TrunkedIdentityPolicy.PROTOCOL_P25 && call.wacn() != null && call.systemId() != null)
        {
            for(io.github.dsheirer.module.decode.p25.P25SiteIdentity site: call.learnedP25Sites())
            {
                if(site.wacn() != call.wacn() || site.system() != call.systemId())
                {
                    continue;
                }

                int learnedSiteId = upsertLearnedP25Site(connection, radioSystem.radioSystemId(), site,
                    call.callStartEpochMilliseconds());
                upsertP25SiteCallBucket(connection, radioSystem.radioSystemId(), learnedSiteId, bucket, 1, encrypted);
                upsertLogicalCallIdentities(connection, radioSystem.radioSystemId(), bucket, call, 1, encrypted, 0, 0,
                    true, learnedSiteId);
            }
        }

        RadioSystemSchema.recordResolvedLogicalCall(connection, radioSystem, call);
        return true;
    }

    static boolean applyLogicalCallOutput(Connection connection, ReceiverActivityRecords.LogicalCallOutput output)
        throws SQLException
    {
        if(output == null)
        {
            return false;
        }

        ReceiverActivityRecords.ResolvedLogicalCall call = output.call();
        int protocol = TrunkedIdentityPolicy.protocolFamilyCode(call.protocol());
        RadioSystemSchema.RadioSystem radioSystem = resolveLogicalCallRadioSystem(connection, call, protocol);
        if(radioSystem == null)
        {
            return false;
        }

        int recorded = output.output() == ReceiverActivityRecords.CallOutput.RECORDED ? 1 : 0;
        int streamed = output.output() == ReceiverActivityRecords.CallOutput.STREAMED ? 1 : 0;
        long bucket = bucketStart(call.callStartEpochMilliseconds());
        upsertLogicalCallBucket(connection, radioSystem.radioSystemId(), bucket, 0, 0, recorded, streamed);
        upsertLogicalCallIdentities(connection, radioSystem.radioSystemId(), bucket, call, 0, 0, recorded, streamed,
            false, null);
        RadioSystemSchema.applyLogicalCallOutput(connection, radioSystem, output, recorded, streamed);
        return true;
    }

    private static RadioSystemSchema.RadioSystem resolveLogicalCallRadioSystem(
        Connection connection, ReceiverActivityRecords.ResolvedLogicalCall call, int protocol) throws SQLException
    {
        if(!TrunkedIdentityPolicy.isSupportedProtocol(protocol) ||
            call.configurationId() == null || call.configurationId().isBlank())
        {
            return null;
        }

        int channelId = upsertReceiverChannel(connection, ReceiverChannelMetadata.from(call));
        return RadioSystemSchema.ensureReceiverRadioSystem(connection, channelId,
            call.callStartEpochMilliseconds(), call.identityDomain(), call.wacn(), call.systemId());
    }

    private static void upsertLogicalCallBucket(Connection connection, int radioSystemId, long bucket, int calls,
                                                int encrypted, int recorded, int streamed) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_logical_call_bucket(
                radio_system_id, bucket_start_ms, logical_call_count, encrypted_logical_call_count,
                recorded_output_count, streamed_output_count
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(radio_system_id, bucket_start_ms) DO UPDATE SET
                logical_call_count = trunked_logical_call_bucket.logical_call_count + excluded.logical_call_count,
                encrypted_logical_call_count = trunked_logical_call_bucket.encrypted_logical_call_count +
                    excluded.encrypted_logical_call_count,
                recorded_output_count = trunked_logical_call_bucket.recorded_output_count + excluded.recorded_output_count,
                streamed_output_count = trunked_logical_call_bucket.streamed_output_count + excluded.streamed_output_count
            """))
        {
            statement.setInt(1, radioSystemId);
            statement.setLong(2, bucket);
            statement.setInt(3, calls);
            statement.setInt(4, encrypted);
            statement.setInt(5, recorded);
            statement.setInt(6, streamed);
            statement.executeUpdate();
        }
    }

    private static void upsertLogicalCallIdentities(Connection connection, int radioSystemId, long bucket,
                                                     ReceiverActivityRecords.ResolvedLogicalCall call, int calls,
                                                     int encrypted, int recorded, int streamed, boolean site,
                                                     Integer learnedSiteId) throws SQLException
    {
        int protocol = TrunkedIdentityPolicy.protocolFamilyCode(call.protocol());
        for(CallIdentity destination: destinationIdentities(
            call.destinationId() > 0 ? Integer.toString(call.destinationId()) : null, call.destinationKind(),
            call.patchMemberTalkgroupIds(), protocol, call.identityDomain()))
        {
            upsertLogicalCallIdentity(connection, radioSystemId, learnedSiteId, bucket, IDENTITY_ROLE_DESTINATION,
                destination.kindCode(), destination.identityId(), calls, encrypted, recorded, streamed, site);
        }

        if(TrunkedIdentityPolicy.isDirectoryRadio(protocol, call.identityDomain(), call.sourceRadioId()))
        {
            upsertLogicalCallIdentity(connection, radioSystemId, learnedSiteId, bucket, IDENTITY_ROLE_SOURCE,
                IDENTITY_KIND_RADIO, call.sourceRadioId(), calls, encrypted, recorded, streamed, site);
        }
    }

    private static void upsertLogicalCallIdentity(Connection connection, int radioSystemId, Integer learnedSiteId,
                                                   long bucket, int role, int kind, int identityId, int calls,
                                                   int encrypted, int recorded, int streamed, boolean site)
        throws SQLException
    {
        String sql = site ? """
            INSERT INTO p25_site_call_identity_bucket(
                radio_system_id, learned_site_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id,
                observed_call_count, encrypted_observed_call_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(radio_system_id, learned_site_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id)
            DO UPDATE SET
                observed_call_count = p25_site_call_identity_bucket.observed_call_count + excluded.observed_call_count,
                encrypted_observed_call_count = p25_site_call_identity_bucket.encrypted_observed_call_count +
                    excluded.encrypted_observed_call_count
            """ : """
            INSERT INTO trunked_logical_call_identity_bucket(
                radio_system_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id,
                logical_call_count, encrypted_logical_call_count, recorded_output_count, streamed_output_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(radio_system_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id)
            DO UPDATE SET
                logical_call_count = trunked_logical_call_identity_bucket.logical_call_count + excluded.logical_call_count,
                encrypted_logical_call_count = trunked_logical_call_identity_bucket.encrypted_logical_call_count +
                    excluded.encrypted_logical_call_count,
                recorded_output_count = trunked_logical_call_identity_bucket.recorded_output_count + excluded.recorded_output_count,
                streamed_output_count = trunked_logical_call_identity_bucket.streamed_output_count + excluded.streamed_output_count
            """;
        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            int index = 1;
            statement.setInt(index++, radioSystemId);
            if(site)
            {
                statement.setInt(index++, learnedSiteId);
            }
            statement.setLong(index++, bucket);
            statement.setInt(index++, role);
            statement.setInt(index++, kind);
            statement.setInt(index++, identityId);
            statement.setInt(index++, calls);
            statement.setInt(index++, encrypted);
            if(!site)
            {
                statement.setInt(index++, recorded);
                statement.setInt(index, streamed);
            }
            statement.executeUpdate();
        }
    }

    private static int upsertLearnedP25Site(Connection connection, int radioSystemId,
                                             io.github.dsheirer.module.decode.p25.P25SiteIdentity site,
                                             long observedAt) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_learned_site(radio_system_id, rfss, site, first_seen_ms, last_seen_ms)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(radio_system_id, rfss, site) DO UPDATE SET
                first_seen_ms = min(p25_learned_site.first_seen_ms, excluded.first_seen_ms),
                last_seen_ms = max(p25_learned_site.last_seen_ms, excluded.last_seen_ms)
            RETURNING learned_site_id
            """))
        {
            statement.setInt(1, radioSystemId);
            statement.setInt(2, site.rfss());
            statement.setInt(3, site.site());
            statement.setLong(4, observedAt);
            statement.setLong(5, observedAt);
            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    return resultSet.getInt(1);
                }
            }
        }
        throw new SQLException("SQLite did not return a learned P25 site id");
    }

    private static void upsertP25SiteCallBucket(Connection connection, int radioSystemId, int learnedSiteId, long bucket,
                                                 int calls, int encrypted) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_site_call_bucket(
                radio_system_id, learned_site_id, bucket_start_ms, observed_call_count, encrypted_observed_call_count
            ) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(radio_system_id, learned_site_id, bucket_start_ms) DO UPDATE SET
                observed_call_count = p25_site_call_bucket.observed_call_count + excluded.observed_call_count,
                encrypted_observed_call_count = p25_site_call_bucket.encrypted_observed_call_count +
                    excluded.encrypted_observed_call_count
            """))
        {
            statement.setInt(1, radioSystemId);
            statement.setInt(2, learnedSiteId);
            statement.setLong(3, bucket);
            statement.setInt(4, calls);
            statement.setInt(5, encrypted);
            statement.executeUpdate();
        }
    }

    /**
     * Atomically enriches an already-counted trunked call without changing physical or action counts.
     */
    static boolean applyTrunkedCallAttribution(
        Connection connection, ReceiverActivityRecords.TrunkedCallAttribution attribution) throws SQLException
    {
        if(attribution == null || attribution.callStartEpochMilliseconds() <= 0 ||
            (!attribution.destinationBecameKnown() && !attribution.sourceBecameKnown() &&
                !attribution.encryptionBecameKnown() && !attribution.hasEncryptionDetails() &&
                !attribution.hasP25TargetIdentity()))
        {
            return false;
        }

        ReceiverChannelIdentity channel = selectReceiverChannelIdentity(connection, attribution.configurationId());

        if(channel == null || channel.kindCode() != RECEIVER_TRUNKED_SITE ||
            attribution.callStartEpochMilliseconds() < channel.firstSeenEpochMilliseconds())
        {
            return false;
        }

        int protocol = TrunkedIdentityPolicy.protocolFamilyCode(channel.protocolCode());

        if(!TrunkedIdentityPolicy.isSupportedProtocol(protocol))
        {
            return false;
        }

        //Late receiver-leg attribution may enrich retained detail and directory metadata, but the final global
        //logical call owns every call, encryption and output counter.
        if(!RadioSystemSchema.isAttributionCompatible(connection, channel.channelId(), attribution))
        {
            return false;
        }

        RadioSystemSchema.applyAttribution(connection, channel.channelId(), attribution);
        enrichDetailedTrunkedCall(connection, channel.channelId(), protocol, attribution);
        return true;
    }

    /**
     * Updates the optional detailed row for the already-counted call.  The physical call and every compact summary
     * remain unchanged; this only fills facts that were not present on the first grant.
     */
    private static void enrichDetailedTrunkedCall(Connection connection, int channelId, int protocol,
                                                   ReceiverActivityRecords.TrunkedCallAttribution attribution)
        throws SQLException
    {
        Long activityId = findDetailedTrunkedCallId(connection, channelId, attribution);

        if(activityId == null)
        {
            return;
        }

        boolean sourceKnown = attribution.sourceBecameKnown() && attribution.sourceRadioId() != null &&
            attribution.sourceRadioId() > 0;
        boolean destinationKnown = attribution.destinationBecameKnown() && attribution.destinationId() > 0;
        boolean encryptionKnown = attribution.encryptionBecameKnown() || attribution.hasEncryptionDetails();

        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE receiver_activity_event
            SET source_radio_id = CASE
                    WHEN ? = 1 AND (source_radio_id IS NULL OR source_radio_id <= 0) THEN ?
                    ELSE source_radio_id
                END,
                target_id = CASE
                    WHEN ? = 1 AND (target_id IS NULL OR target_id <= 0) THEN ?
                    ELSE target_id
                END,
                target_kind_code = CASE
                    WHEN ? = 1 AND target_kind_code IS NULL THEN ?
                    ELSE target_kind_code
                END,
                encrypted = CASE WHEN ? = 1 THEN 1 ELSE encrypted END,
                encryption_algorithm_id = coalesce(encryption_algorithm_id, ?),
                encryption_key_id = coalesce(encryption_key_id, ?)
            WHERE id = ?
            """))
        {
            statement.setInt(1, sourceKnown ? 1 : 0);
            setInteger(statement, 2, sourceKnown ? attribution.sourceRadioId() : null);
            statement.setInt(3, destinationKnown ? 1 : 0);
            setInteger(statement, 4, destinationKnown ? attribution.destinationId() : null);
            statement.setInt(5, destinationKnown ? 1 : 0);
            setInteger(statement, 6, destinationKnown ? targetKindCode(attribution.destinationKind()) : null);
            statement.setInt(7, encryptionKnown ? 1 : 0);
            setInteger(statement, 8, attribution.encryptionAlgorithmId());
            setInteger(statement, 9, attribution.encryptionKeyId());
            statement.setLong(10, activityId);
            statement.executeUpdate();
        }

        if(destinationKnown)
        {
            insertActivityEventTalkgroupMembers(connection, activityId, attribution.destinationKind(),
                attribution.patchMemberTalkgroupIds(), protocol, attribution.identityDomain());
        }
    }

    /**
     * Finds the retained detail row for a one-time trunked call observation.  Frequency is used when the attribution
     * contains it, and timeslot is matched null-safely so simultaneous DMR slots cannot update each other.
     */
    private static Long findDetailedTrunkedCallId(Connection connection, int channelId,
                                                   ReceiverActivityRecords.TrunkedCallAttribution attribution)
        throws SQLException
    {
        if(attribution.frequencyHertz() != null && attribution.frequencyHertz() > 0)
        {
            Long exactMatch = findDetailedTrunkedCallId(connection, channelId, attribution, """
                frequency_hz = ?
                """, attribution.frequencyHertz(), 1, false);

            if(exactMatch != null)
            {
                return exactMatch;
            }

            return findDetailedTrunkedCallId(connection, channelId, attribution, """
                (frequency_hz IS NULL OR frequency_hz <= 0)
                """, null, 0, true);
        }

        return findDetailedTrunkedCallId(connection, channelId, attribution, """
            (? IS NULL OR frequency_hz = ?)
            """, attribution.frequencyHertz(), 2, false);
    }

    private static Long findDetailedTrunkedCallId(
        Connection connection, int channelId, ReceiverActivityRecords.TrunkedCallAttribution attribution,
        String frequencyPredicate, Long frequency, int frequencyParameterCount, boolean requireUnique)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id
            FROM receiver_activity_event
            WHERE channel_id = ? AND observed_at_ms = ? AND action_code = ?
              AND %s
              AND ((? IS NULL AND timeslot IS NULL) OR timeslot = ?)
            ORDER BY id DESC
            LIMIT %d
            """.formatted(frequencyPredicate.strip(), requireUnique ? 2 : 1)))
        {
            int index = 1;
            statement.setInt(index++, channelId);
            statement.setLong(index++, attribution.callStartEpochMilliseconds());
            statement.setInt(index++, actionCode(ReceiverActivityRecords.Action.CALL));

            for(int x = 0; x < frequencyParameterCount; x++)
            {
                setLong(statement, index++, frequency);
            }

            setInteger(statement, index++, attribution.timeslot());
            setInteger(statement, index, attribution.timeslot());

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(!resultSet.next())
                {
                    return null;
                }

                long activityId = resultSet.getLong(1);
                return requireUnique && resultSet.next() ? null : activityId;
            }
        }
    }

    /**
     * Aggregates one exactly-once completed DMR conventional call without retaining optional detailed event history.
     */
    static Long recordDmrConventionalCall(Connection connection, ReceiverActivityRecords.DmrConventionalCall call)
        throws SQLException
    {
        return recordDmrConventionalCall(connection, call, false);
    }

    /**
     * Aggregates one exactly-once completed DMR conventional call into the shared conventional channel totals and
     * compact DMR identity summaries, and optionally retains one detailed event row. The event row is inserted
     * directly so the summaries are not counted a second time through {@link #recordActivity}.
     */
    static Long recordDmrConventionalCall(Connection connection, ReceiverActivityRecords.DmrConventionalCall call,
                                          boolean detailedEventHistoryEnabled) throws SQLException
    {
        if(call == null)
        {
            return null;
        }

        DmrActivitySchema.validateCompletedCall(call);
        int channelId = upsertReceiverChannel(connection, ReceiverChannelMetadata.from(call));

        if(!matchesReceiverChannel(selectReceiverChannelIdentity(connection, call.configurationId()),
            RECEIVER_CONVENTIONAL_DMR, TrunkedIdentityPolicy.PROTOCOL_DMR))
        {
            return null;
        }

        String targetId = call.targetKind() == ReceiverActivityRecords.DmrTargetKind.GROUP &&
            call.talkgroupId() != null ? call.talkgroupId().toString() :
            call.targetKind() == ReceiverActivityRecords.DmrTargetKind.PRIVATE &&
                call.targetRadioId() != null ? call.targetRadioId().toString() : null;
        String targetKind = call.targetKind() == ReceiverActivityRecords.DmrTargetKind.GROUP ? Form.TALKGROUP.name() :
            call.targetKind() == ReceiverActivityRecords.DmrTargetKind.PRIVATE ? Form.RADIO.name() : null;
        String eventType = call.targetKind() == ReceiverActivityRecords.DmrTargetKind.GROUP ?
            (call.encrypted() ? DecodeEventType.CALL_GROUP_ENCRYPTED.name() : DecodeEventType.CALL_GROUP.name()) :
            call.targetKind() == ReceiverActivityRecords.DmrTargetKind.PRIVATE ?
                (call.encrypted() ? DecodeEventType.CALL_UNIT_TO_UNIT_ENCRYPTED.name() :
                    DecodeEventType.CALL_UNIT_TO_UNIT.name()) :
                (call.encrypted() ? DecodeEventType.CALL_ENCRYPTED.name() : DecodeEventType.CALL.name());
        ReceiverActivityRecords.ActivityEvent activity = new ReceiverActivityRecords.ActivityEvent(
            call.callStartEpochMilliseconds(), call.configurationId(),
            ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_DMR, "DMR", ReceiverActivityRecords.Action.CALL,
            eventType, call.sourceRadioId() != null ? call.sourceRadioId().toString() : null, targetId, targetKind,
            List.of(), call.frequencyHertz(), null, call.timeslot(), call.encrypted(), null, null, null, null, null,
            null, null, null, true, null, null, ReceiverActivityRecords.IdentityDomain.STANDARD,
            ReceiverActivityRecords.P25TargetIdentity.UNKNOWN, List.of());
        upsertConventionalSummary(connection, activity, channelId);
        upsertCallIdentityBuckets(connection, activity, channelId);
        DmrActivitySchema.recordCompletedCall(connection, channelId, call);
        return detailedEventHistoryEnabled ? insertReceiverActivityEvent(connection, activity, channelId) : null;
    }

    static Long recordNxdnConventionalCall(Connection connection,
                                           ReceiverActivityRecords.NxdnConventionalCall call,
                                           boolean detailedEventHistoryEnabled) throws SQLException
    {
        if(call == null)
        {
            return null;
        }

        validateNxdnConventionalCall(call);
        int channelId = upsertReceiverChannel(connection, ReceiverChannelMetadata.from(call));

        if(!matchesReceiverChannel(selectReceiverChannelIdentity(connection, call.configurationId()),
            RECEIVER_CONVENTIONAL_NXDN, TrunkedIdentityPolicy.PROTOCOL_NXDN))
        {
            return null;
        }

        String targetId = call.targetKind() == ReceiverActivityRecords.NxdnTargetKind.GROUP ?
            value(call.talkgroupId()) :
            call.targetKind() == ReceiverActivityRecords.NxdnTargetKind.PRIVATE ?
                value(call.targetRadioId()) : null;
        String targetKind = call.targetKind() == ReceiverActivityRecords.NxdnTargetKind.GROUP ?
            Form.TALKGROUP.name() :
            call.targetKind() == ReceiverActivityRecords.NxdnTargetKind.PRIVATE ? Form.RADIO.name() : null;
        String eventType = call.targetKind() == ReceiverActivityRecords.NxdnTargetKind.GROUP ?
            (call.encrypted() ? DecodeEventType.CALL_GROUP_ENCRYPTED.name() : DecodeEventType.CALL_GROUP.name()) :
            call.targetKind() == ReceiverActivityRecords.NxdnTargetKind.PRIVATE ?
                (call.encrypted() ? DecodeEventType.CALL_UNIT_TO_UNIT_ENCRYPTED.name() :
                    DecodeEventType.CALL_UNIT_TO_UNIT.name()) :
                (call.encrypted() ? DecodeEventType.CALL_ENCRYPTED.name() : DecodeEventType.CALL.name());
        ReceiverActivityRecords.ActivityEvent activity = new ReceiverActivityRecords.ActivityEvent(
            call.callStartEpochMilliseconds(), call.configurationId(),
            ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_NXDN, "NXDN", ReceiverActivityRecords.Action.CALL,
            eventType, value(call.sourceRadioId()), targetId, targetKind, List.of(), call.frequencyHertz(), null, null,
            call.encrypted(), null, null, null, null, null, null, null, null, true, null, null,
            ReceiverActivityRecords.IdentityDomain.STANDARD, ReceiverActivityRecords.P25TargetIdentity.UNKNOWN,
            List.of());
        upsertConventionalSummary(connection, activity, channelId);
        upsertCallIdentityBuckets(connection, activity, channelId);
        return detailedEventHistoryEnabled ? insertReceiverActivityEvent(connection, activity, channelId) : null;
    }

    private static void validateNxdnConventionalCall(ReceiverActivityRecords.NxdnConventionalCall call)
        throws SQLException
    {
        if(call.callStartEpochMilliseconds() <= 0 ||
            call.callEndEpochMilliseconds() < call.callStartEpochMilliseconds() ||
            call.configurationId() == null || call.configurationId().isBlank() || call.frequencyHertz() <= 0 ||
            call.targetKind() == null || !validNxdnId(call.sourceRadioId()) ||
            !validNxdnId(call.talkgroupId()) || !validNxdnId(call.targetRadioId()))
        {
            throw new SQLException("Invalid completed conventional NXDN call");
        }

        if(call.targetKind() == ReceiverActivityRecords.NxdnTargetKind.GROUP && call.targetRadioId() != null ||
            call.targetKind() == ReceiverActivityRecords.NxdnTargetKind.PRIVATE && call.talkgroupId() != null ||
            call.targetKind() == ReceiverActivityRecords.NxdnTargetKind.UNKNOWN &&
                (call.talkgroupId() != null || call.targetRadioId() != null))
        {
            throw new SQLException("NXDN target identity does not match the call type");
        }
    }

    private static boolean validNxdnId(Integer identifier)
    {
        return identifier == null || identifier > 0 && identifier <= 0xFFFF;
    }

    private static String value(Integer identifier)
    {
        return identifier != null ? identifier.toString() : null;
    }

    static void updateTalkerAlias(Connection connection, ReceiverActivityRecords.TalkerAliasUpdate update)
        throws SQLException
    {
        ReceiverChannelIdentity channel = selectReceiverChannelIdentity(connection, update.configurationId());

        if(channel != null && channel.kindCode() == RECEIVER_TRUNKED_SITE &&
            update.observedAtEpochMilliseconds() >= channel.firstSeenEpochMilliseconds())
        {
            RadioSystemSchema.updateTalkerAlias(connection, channel.channelId(), update.radioId(),
                update.talkerAlias(), update.observedAtEpochMilliseconds(), update.identityDomain());
        }
    }

    static void insertSite(Connection connection, ReceiverActivityRecords.SiteSnapshot snapshot) throws SQLException
    {
        if(snapshot == null || snapshot.configurationId() == null || snapshot.configurationId().isBlank())
        {
            return;
        }

        ReceiverChannelState previousChannel = receiverChannelState(connection, snapshot.configurationId());
        if(previousChannel != null &&
            snapshot.observedAtEpochMilliseconds() < previousChannel.lastSeenEpochMilliseconds())
        {
            return;
        }

        boolean generationChanged = previousChannel != null &&
            (previousChannel.kindCode() != RECEIVER_TRUNKED_SITE ||
             TrunkedIdentityPolicy.protocolFamilyCode(previousChannel.protocolCode()) !=
                 TrunkedIdentityPolicy.PROTOCOL_P25 ||
             previousChannel.p25Wacn() != null && snapshot.wacn() != null &&
                 (!previousChannel.p25Wacn().equals(snapshot.wacn()) ||
                  !previousChannel.p25SystemId().equals(snapshot.systemId())));
        int channelId = upsertReceiverChannel(connection, ReceiverChannelMetadata.from(snapshot));
        SiteSnapshotState previous = siteSnapshotState(connection, channelId);

        if(generationChanged)
        {
            clearP25SiteProjection(connection, channelId);
            previous = null;
        }

        Map<String,SiteChannelEvidence> channels = mergeSiteChannels(snapshot);
        boolean changed = previous == null || generationChanged ||
            !java.util.Objects.equals(snapshot.snapshotHash(), previous.snapshotHash());

        TrunkedSiteSchema.clearSiteStats(connection, snapshot.configurationId());
        upsertSiteSnapshot(connection, snapshot, channelId);
        RadioSystemSchema.ensureRadioSystem(connection, channelId, snapshot.observedAtEpochMilliseconds(),
            ReceiverActivityRecords.IdentityDomain.STANDARD, snapshot.wacn(), snapshot.systemId());

        if(changed)
        {
            upsertSiteChannelSummaries(connection, snapshot, channelId, channels);
            upsertSiteFrequencyBandSummaries(connection, snapshot, channelId);
            upsertForeignSystemBandSummaries(connection, snapshot, channelId);
            upsertSiteNeighborSummaries(connection, snapshot, channelId);
            upsertSitePatchSummaries(connection, snapshot, channelId);
            replaceCurrentSiteFacts(connection, snapshot, channelId, channels);
        }
        else
        {
            confirmCurrentSiteFacts(connection, snapshot, channelId);
        }
    }

    /**
     * Rejects a delayed DMR/NXDN site snapshot when a newer observation already established the receiver's current
     * classification.
     */
    static boolean isAuthoritativeTrunkedSiteSnapshot(Connection connection, TrunkedSiteSchema.Snapshot snapshot)
        throws SQLException
    {
        if(snapshot == null || snapshot.configurationId() == null || snapshot.configurationId().isBlank())
        {
            return false;
        }

        ReceiverChannelState previous = receiverChannelState(connection, snapshot.configurationId());
        return previous == null ||
            snapshot.observedAtEpochMilliseconds() >= previous.lastSeenEpochMilliseconds();
    }

    /**
     * Establishes the receiver-owned radio system for a decoded DMR/NXDN site even before its first call.
     */
    static void ensureTrunkedSiteRadioSystem(Connection connection, TrunkedSiteSchema.Snapshot snapshot)
        throws SQLException
    {
        if(snapshot == null || snapshot.configurationId() == null || snapshot.configurationId().isBlank() ||
            (snapshot.protocolCode() != TrunkedSiteSchema.PROTOCOL_DMR &&
                snapshot.protocolCode() != TrunkedSiteSchema.PROTOCOL_NXDN))
        {
            return;
        }

        if(!isAuthoritativeTrunkedSiteSnapshot(connection, snapshot))
        {
            return;
        }

        int channelId = upsertReceiverChannel(connection, ReceiverChannelMetadata.from(snapshot));

        ReceiverChannelIdentity channel = selectReceiverChannelIdentity(connection, snapshot.configurationId());

        if(!matchesReceiverChannel(channel, RECEIVER_TRUNKED_SITE,
            TrunkedIdentityPolicy.protocolFamilyCode(snapshot.protocolCode())))
        {
            return;
        }

        clearP25SiteProjection(connection, channelId);

        ReceiverActivityRecords.IdentityDomain identityDomain =
            snapshot.protocolCode() == TrunkedSiteSchema.PROTOCOL_NXDN &&
                (snapshot.variantCode() == 2 || snapshot.identityDomainCode() == 4) ?
                ReceiverActivityRecords.IdentityDomain.NXDN_TYPE_D :
                snapshot.protocolCode() == TrunkedSiteSchema.PROTOCOL_NXDN ?
                    ReceiverActivityRecords.IdentityDomain.NXDN_TYPE_C :
                    ReceiverActivityRecords.IdentityDomain.STANDARD;
        RadioSystemSchema.ensureRadioSystem(connection, channelId, snapshot.observedAtEpochMilliseconds(),
            identityDomain);
    }

    static void insertControlChannelQuality(Connection connection,
                                            ReceiverActivityRecords.ControlChannelQuality quality) throws SQLException
    {
        int channelId = ensureControlChannelQualityChannel(connection, quality);

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_control_channel_quality (
                channel_id, frequency_hz, bucket_start_ms, observed_at_ms, signal_dbfs, average_signal_dbfs,
                minimum_signal_dbfs, maximum_signal_dbfs, decode_health_pct, valid_frames, invalid_frames,
                corrected_bits, sync_loss_bits, dropped_bits, last_valid_decode_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(channel_id, frequency_hz, bucket_start_ms) DO UPDATE SET
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
            WHERE excluded.observed_at_ms >= trunked_control_channel_quality.observed_at_ms
            """))
        {
            statement.setInt(1, channelId);
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

    /**
     * Quality samples can arrive before the first decoded site snapshot.  Establish a minimal shared receiver owner
     * so the quality table can use a real cascading foreign key without changing a known protocol classification.
     */
    private static int ensureControlChannelQualityChannel(Connection connection,
                                                          ReceiverActivityRecords.ControlChannelQuality quality)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO receiver_channel(
                configuration_id, first_seen_ms, last_seen_ms, current_control_hz
            )
            VALUES (?, ?, ?, ?)
            ON CONFLICT(configuration_id) DO UPDATE SET
                first_seen_ms = min(receiver_channel.first_seen_ms, excluded.first_seen_ms),
                current_control_hz = CASE WHEN excluded.last_seen_ms >= receiver_channel.last_seen_ms
                    THEN excluded.current_control_hz ELSE receiver_channel.current_control_hz END,
                last_seen_ms = max(receiver_channel.last_seen_ms, excluded.last_seen_ms)
            """))
        {
            statement.setString(1, quality.configurationId());
            statement.setLong(2, quality.observedAtEpochMilliseconds());
            statement.setLong(3, quality.observedAtEpochMilliseconds());
            statement.setLong(4, quality.frequencyHertz());
            statement.executeUpdate();
        }
        return selectReceiverChannelId(connection, quality.configurationId());
    }

    static int deleteOlderThan(Connection connection, long cutoffEpochMilliseconds) throws SQLException
    {
        int deleted = 0;
        //Hourly rows can contain observations on both sides of an arbitrary retention instant. Preserve that
        //overlapping hour and delete only buckets that end at or before the cutoff.
        long hourlyBucketCutoff = bucketStart(cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "receiver_activity_event", "observed_at_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "trunked_logical_call_identity_bucket", "bucket_start_ms",
            hourlyBucketCutoff);
        deleted += deleteByTime(connection, "trunked_logical_call_bucket", "bucket_start_ms",
            hourlyBucketCutoff);
        deleted += deleteByTime(connection, "p25_site_call_identity_bucket", "bucket_start_ms",
            hourlyBucketCutoff);
        deleted += deleteByTime(connection, "p25_site_call_bucket", "bucket_start_ms", hourlyBucketCutoff);
        deleted += deleteByTime(connection, "trunked_signaling_activity_bucket", "bucket_start_ms",
            hourlyBucketCutoff);
        deleted += deleteByTime(connection, "conventional_call_identity_bucket", "bucket_start_ms",
            hourlyBucketCutoff);
        deleted += deleteByTime(connection, "conventional_activity_bucket", "bucket_start_ms", hourlyBucketCutoff);
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
        deleted += RadioSystemSchema.deleteOlderThan(connection, cutoffEpochMilliseconds);
        try(PreparedStatement statement = connection.prepareStatement("""
            DELETE FROM p25_learned_site
            WHERE last_seen_ms < ?
              AND NOT EXISTS (
                  SELECT 1 FROM p25_site_call_bucket fact
                  WHERE fact.learned_site_id = p25_learned_site.learned_site_id
              )
              AND NOT EXISTS (
                  SELECT 1 FROM p25_site_call_identity_bucket fact
                  WHERE fact.learned_site_id = p25_learned_site.learned_site_id
              )
            """))
        {
            statement.setLong(1, cutoffEpochMilliseconds);
            deleted += statement.executeUpdate();
        }
        deleted += RadioSystemSchema.pruneUnusedRadioSystems(connection);
        return deleted;
    }

    /** Receiver-channel rows are owned by configuration_channel and are removed by its cascading foreign key. */
    static int pruneUnusedRadioSystems(Connection connection) throws SQLException
    {
        return RadioSystemSchema.pruneUnusedRadioSystems(connection);
    }

    static int resetStats(Connection connection) throws SQLException
    {
        int deleted = 0;
        deleted += deleteAll(connection, "receiver_activity_event");
        deleted += deleteAll(connection, "p25_site_call_identity_bucket");
        deleted += deleteAll(connection, "p25_site_call_bucket");
        deleted += deleteAll(connection, "trunked_logical_call_identity_bucket");
        deleted += deleteAll(connection, "trunked_logical_call_bucket");
        deleted += deleteAll(connection, "trunked_signaling_activity_bucket");
        deleted += deleteAll(connection, "conventional_call_identity_bucket");
        deleted += RadioSystemSchema.reset(connection);
        deleted += deleteAll(connection, "p25_learned_site");
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
        deleted += deleteAll(connection, "trunked_control_channel_quality");
        deleted += deleteAll(connection, "receiver_channel");
        deleted += deleteAll(connection, "statistics_status");
        SdrTrunkDatabaseStartup.setMetadata(connection, TRUNKED_LOGICAL_CALL_METRICS_STARTED_AT_KEY,
            Long.toString(System.currentTimeMillis()));
        SdrTrunkDatabaseStartup.setMetadata(connection, CONVENTIONAL_CALL_OUTPUT_METRICS_STARTED_AT_KEY,
            Long.toString(System.currentTimeMillis()));
        return deleted;
    }

    /** Clears activity owned by one saved channel while preserving shared radio-system summaries. */
    static int clearSiteStats(Connection connection, String configurationId) throws SQLException
    {
        if(configurationId == null || configurationId.isBlank())
        {
            throw new IllegalArgumentException("Channel configuration ID is required");
        }

        try(PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM receiver_channel WHERE configuration_id = ?"))
        {
            statement.setString(1, configurationId);
            return statement.executeUpdate();
        }
    }

    static void updateStatus(Connection connection, String key, String value) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO statistics_status (key, value, updated_at_ms)
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
            "SELECT value FROM statistics_status WHERE key = ?"))
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

    private static void createTrunkedCallTables(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_learned_site (
                learned_site_id INTEGER PRIMARY KEY CHECK(learned_site_id > 0),
                radio_system_id INTEGER NOT NULL REFERENCES radio_system(id) ON DELETE CASCADE,
                rfss INTEGER NOT NULL CHECK(rfss BETWEEN 0 AND 255),
                site INTEGER NOT NULL CHECK(site BETWEEN 0 AND 255),
                first_seen_ms INTEGER NOT NULL CHECK(first_seen_ms > 0),
                last_seen_ms INTEGER NOT NULL CHECK(last_seen_ms >= first_seen_ms),
                UNIQUE(radio_system_id, rfss, site),
                UNIQUE(learned_site_id, radio_system_id)
            )
            """);
        statement.executeUpdate(createTrunkedLogicalCallBucketSql());
        statement.executeUpdate(createTrunkedLogicalCallIdentityBucketSql());
        statement.executeUpdate(createP25SiteCallBucketSql());
        statement.executeUpdate(createP25SiteCallIdentityBucketSql());
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS trunked_signaling_activity_bucket (
                channel_id INTEGER NOT NULL REFERENCES receiver_channel(id) ON DELETE CASCADE,
                bucket_start_ms INTEGER NOT NULL CHECK(bucket_start_ms >= 0),
                %s,
                PRIMARY KEY(channel_id, bucket_start_ms)
            ) WITHOUT ROWID
            """.formatted(TRUNKED_SIGNALING_ACTION_COUNT_DEFINITIONS));
    }

    private static String createTrunkedLogicalCallBucketSql()
    {
        return """
            CREATE TABLE IF NOT EXISTS trunked_logical_call_bucket (
                radio_system_id INTEGER NOT NULL REFERENCES radio_system(id) ON DELETE CASCADE,
                bucket_start_ms INTEGER NOT NULL CHECK(bucket_start_ms >= 0),
                logical_call_count INTEGER NOT NULL DEFAULT 0 CHECK(logical_call_count >= 0),
                encrypted_logical_call_count INTEGER NOT NULL DEFAULT 0
                    CHECK(encrypted_logical_call_count >= 0),
                recorded_output_count INTEGER NOT NULL DEFAULT 0 CHECK(recorded_output_count >= 0),
                streamed_output_count INTEGER NOT NULL DEFAULT 0 CHECK(streamed_output_count >= 0),
                PRIMARY KEY(radio_system_id, bucket_start_ms)
            ) WITHOUT ROWID
            """;
    }

    private static String createTrunkedLogicalCallIdentityBucketSql()
    {
        return """
            CREATE TABLE IF NOT EXISTS trunked_logical_call_identity_bucket (
                radio_system_id INTEGER NOT NULL REFERENCES radio_system(id) ON DELETE CASCADE,
                bucket_start_ms INTEGER NOT NULL CHECK(bucket_start_ms >= 0),
                identity_role_code INTEGER NOT NULL CHECK(identity_role_code IN (1, 2)),
                identity_kind_code INTEGER NOT NULL CHECK(identity_kind_code IN (0, 1, 2, 3)),
                identity_id INTEGER NOT NULL CHECK(identity_id >= 0),
                logical_call_count INTEGER NOT NULL DEFAULT 0 CHECK(logical_call_count >= 0),
                encrypted_logical_call_count INTEGER NOT NULL DEFAULT 0
                    CHECK(encrypted_logical_call_count >= 0),
                recorded_output_count INTEGER NOT NULL DEFAULT 0 CHECK(recorded_output_count >= 0),
                streamed_output_count INTEGER NOT NULL DEFAULT 0 CHECK(streamed_output_count >= 0),
                PRIMARY KEY(
                    radio_system_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id
                ),
                CHECK(
                    (identity_kind_code = 0 AND identity_id = 0)
                    OR (identity_kind_code IN (1, 2, 3) AND identity_id > 0)
                ),
                CHECK(
                    identity_role_code = 1
                    OR (identity_role_code = 2 AND identity_kind_code = 2 AND identity_id > 0)
                )
            ) WITHOUT ROWID
            """;
    }

    private static String createP25SiteCallBucketSql()
    {
        return """
            CREATE TABLE IF NOT EXISTS p25_site_call_bucket (
                radio_system_id INTEGER NOT NULL,
                learned_site_id INTEGER NOT NULL,
                bucket_start_ms INTEGER NOT NULL CHECK(bucket_start_ms >= 0),
                observed_call_count INTEGER NOT NULL DEFAULT 0 CHECK(observed_call_count >= 0),
                encrypted_observed_call_count INTEGER NOT NULL DEFAULT 0
                    CHECK(encrypted_observed_call_count >= 0),
                PRIMARY KEY(radio_system_id, learned_site_id, bucket_start_ms),
                FOREIGN KEY(learned_site_id, radio_system_id)
                    REFERENCES p25_learned_site(learned_site_id, radio_system_id) ON DELETE CASCADE
            ) WITHOUT ROWID
            """;
    }

    private static String createP25SiteCallIdentityBucketSql()
    {
        return """
            CREATE TABLE IF NOT EXISTS p25_site_call_identity_bucket (
                radio_system_id INTEGER NOT NULL,
                learned_site_id INTEGER NOT NULL,
                bucket_start_ms INTEGER NOT NULL CHECK(bucket_start_ms >= 0),
                identity_role_code INTEGER NOT NULL CHECK(identity_role_code IN (1, 2)),
                identity_kind_code INTEGER NOT NULL CHECK(identity_kind_code IN (0, 1, 2, 3)),
                identity_id INTEGER NOT NULL CHECK(identity_id >= 0),
                observed_call_count INTEGER NOT NULL DEFAULT 0 CHECK(observed_call_count >= 0),
                encrypted_observed_call_count INTEGER NOT NULL DEFAULT 0
                    CHECK(encrypted_observed_call_count >= 0),
                PRIMARY KEY(
                    radio_system_id, learned_site_id, bucket_start_ms,
                    identity_role_code, identity_kind_code, identity_id
                ),
                CHECK(
                    (identity_kind_code = 0 AND identity_id = 0)
                    OR (identity_kind_code IN (1, 2, 3) AND identity_id > 0)
                ),
                CHECK(
                    identity_role_code = 1
                    OR (identity_role_code = 2 AND identity_kind_code = 2 AND identity_id > 0)
                ),
                FOREIGN KEY(learned_site_id, radio_system_id)
                    REFERENCES p25_learned_site(learned_site_id, radio_system_id) ON DELETE CASCADE
            ) WITHOUT ROWID
            """;
    }

    private static void createConventionalTables(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS conventional_activity_summary (
                channel_id INTEGER NOT NULL REFERENCES receiver_channel(id) ON DELETE CASCADE,
                frequency_hz INTEGER NOT NULL CHECK(frequency_hz > 0),
                timeslot INTEGER NOT NULL DEFAULT -1 CHECK(timeslot >= -1),
                first_seen_ms INTEGER NOT NULL CHECK(first_seen_ms > 0),
                last_seen_ms INTEGER NOT NULL CHECK(last_seen_ms >= first_seen_ms),
                %s,
                last_event_type_code INTEGER CHECK(last_event_type_code IS NULL OR last_event_type_code IN (%s)),
                encrypted_count INTEGER NOT NULL DEFAULT 0 CHECK(encrypted_count >= 0),
                recorded_count INTEGER NOT NULL DEFAULT 0 CHECK(recorded_count >= 0),
                streamed_count INTEGER NOT NULL DEFAULT 0 CHECK(streamed_count >= 0),
                PRIMARY KEY(channel_id, frequency_hz, timeslot)
            )
            """.formatted(ACTION_COUNT_DEFINITIONS, EVENT_TYPE_CODES));
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS conventional_activity_bucket (
                channel_id INTEGER NOT NULL REFERENCES receiver_channel(id) ON DELETE CASCADE,
                frequency_hz INTEGER NOT NULL CHECK(frequency_hz >= 0),
                timeslot INTEGER NOT NULL DEFAULT -1 CHECK(timeslot >= -1),
                bucket_start_ms INTEGER NOT NULL CHECK(bucket_start_ms >= 0),
                %s,
                encrypted_count INTEGER NOT NULL DEFAULT 0 CHECK(encrypted_count >= 0),
                recorded_count INTEGER NOT NULL DEFAULT 0 CHECK(recorded_count >= 0),
                streamed_count INTEGER NOT NULL DEFAULT 0 CHECK(streamed_count >= 0),
                PRIMARY KEY(channel_id, frequency_hz, timeslot, bucket_start_ms)
            )
            """.formatted(ACTION_COUNT_DEFINITIONS));
    }

    /**
     * Creates the compact, protocol-neutral identity projection used by the website's bounded top-destination and
     * top-source queries, grouped by protocol and trunked/conventional topology. A physical call is represented only
     * by counter increments: one destination (or channel fallback), an optional source, and additional destination
     * rows for patch members. Row growth is one upserted row per distinct channel/hour/identity combination rather
     * than one row per call, and {@link #deleteOlderThan(Connection, long)} applies the configured activity
     * retention. Existing site and conventional hourly buckets cannot serve this query because they contain physical
     * totals but no protocol-neutral source identity, destination kind, or patch-member dimensions.
     */
    private static void createConventionalCallIdentityTable(Statement statement) throws SQLException
    {
        statement.executeUpdate(createConventionalCallIdentityBucketSql());
    }

    /**
     * Keeps the optional detailed Activity row physically singular while allowing each valid member talkgroup of a
     * patch call to retrieve that same event. Rows exist only when detailed event history is enabled and are removed
     * by the parent event's retention/clear cascade.
     */
    private static String createActivityEventTalkgroupMemberSql()
    {
        return """
            CREATE TABLE IF NOT EXISTS activity_event_talkgroup_member (
                event_id INTEGER NOT NULL REFERENCES receiver_activity_event(id) ON DELETE CASCADE,
                talkgroup_id INTEGER NOT NULL CHECK(talkgroup_id > 0),
                PRIMARY KEY(event_id, talkgroup_id)
            ) WITHOUT ROWID
            """;
    }

    private static String createConventionalCallIdentityBucketSql()
    {
        return """
            CREATE TABLE IF NOT EXISTS conventional_call_identity_bucket (
                channel_id INTEGER NOT NULL REFERENCES receiver_channel(id) ON DELETE CASCADE,
                bucket_start_ms INTEGER NOT NULL CHECK(bucket_start_ms >= 0),
                identity_role_code INTEGER NOT NULL CHECK(identity_role_code IN (1, 2)),
                identity_kind_code INTEGER NOT NULL CHECK(identity_kind_code IN (0, 1, 2, 3)),
                identity_id INTEGER NOT NULL CHECK(identity_id >= 0),
                call_count INTEGER NOT NULL DEFAULT 0 CHECK(call_count >= 0),
                encrypted_count INTEGER NOT NULL DEFAULT 0 CHECK(encrypted_count >= 0),
                recorded_count INTEGER NOT NULL DEFAULT 0 CHECK(recorded_count >= 0),
                streamed_count INTEGER NOT NULL DEFAULT 0 CHECK(streamed_count >= 0),
                PRIMARY KEY (
                    channel_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id
                ),
                CHECK (
                    (identity_kind_code = 0 AND identity_id = 0)
                    OR (identity_kind_code IN (1, 2, 3) AND identity_id > 0)
                ),
                CHECK (
                    identity_role_code = 1
                    OR (identity_role_code = 2 AND identity_kind_code = 2 AND identity_id > 0)
                )
            ) WITHOUT ROWID
            """;
    }

    private static void createP25SiteTables(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_snapshot (
                channel_id INTEGER PRIMARY KEY REFERENCES receiver_channel(id) ON DELETE CASCADE,
                snapshot_hash TEXT,
                first_seen_ms INTEGER NOT NULL CHECK(first_seen_ms > 0),
                last_seen_ms INTEGER NOT NULL CHECK(last_seen_ms >= first_seen_ms),
                observation_count INTEGER NOT NULL DEFAULT 1 CHECK(observation_count > 0),
                protocol TEXT CHECK(protocol IS NULL OR protocol IN ('APCO25', 'APCO25_PHASE2')),
                nac INTEGER CHECK(nac IS NULL OR nac BETWEEN 0 AND 4095),
                rfss INTEGER CHECK(rfss IS NULL OR rfss BETWEEN 0 AND 255),
                site INTEGER CHECK(site IS NULL OR site BETWEEN 0 AND 255),
                lra INTEGER CHECK(lra IS NULL OR lra BETWEEN 0 AND 255),
                mfid INTEGER CHECK(mfid IS NULL OR mfid BETWEEN 0 AND 255),
                broadcast_clock_ms INTEGER CHECK(broadcast_clock_ms IS NULL OR broadcast_clock_ms >= 0),
                micro_slots INTEGER CHECK(micro_slots IS NULL OR micro_slots >= 0),
                data_service INTEGER CHECK(data_service IS NULL OR data_service IN (0, 1)),
                data_access TEXT,
                wuid_lease_minutes INTEGER CHECK(wuid_lease_minutes IS NULL OR wuid_lease_minutes >= 0),
                registration_service INTEGER CHECK(registration_service IS NULL OR registration_service IN (0, 1)),
                tdma INTEGER CHECK(tdma IS NULL OR tdma IN (0, 1)),
                voice_service INTEGER CHECK(voice_service IS NULL OR voice_service IN (0, 1)),
                primary_frequency_hz INTEGER CHECK(primary_frequency_hz IS NULL OR primary_frequency_hz > 0),
                current_control_hz INTEGER CHECK(current_control_hz IS NULL OR current_control_hz > 0),
                active_rfss_network_connection INTEGER
                    CHECK(active_rfss_network_connection IS NULL OR active_rfss_network_connection IN (0, 1))
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_channel (
                channel_id INTEGER NOT NULL,
                channel_key TEXT NOT NULL CHECK(length(trim(channel_key)) > 0),
                descriptor TEXT,
                downlink_hz INTEGER CHECK(downlink_hz IS NULL OR downlink_hz > 0),
                uplink_hz INTEGER CHECK(uplink_hz IS NULL OR uplink_hz > 0),
                tdma INTEGER CHECK(tdma IS NULL OR tdma IN (0, 1)),
                timeslots INTEGER CHECK(timeslots IS NULL OR timeslots > 0),
                callsign TEXT,
                confirmed_at_ms INTEGER NOT NULL CHECK(confirmed_at_ms > 0),
                PRIMARY KEY(channel_id, channel_key),
                FOREIGN KEY(channel_id) REFERENCES p25_site_snapshot(channel_id) ON DELETE CASCADE
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_channel_summary (
                channel_id INTEGER NOT NULL,
                channel_key TEXT NOT NULL CHECK(length(trim(channel_key)) > 0),
                descriptor TEXT,
                downlink_hz INTEGER CHECK(downlink_hz IS NULL OR downlink_hz > 0),
                uplink_hz INTEGER CHECK(uplink_hz IS NULL OR uplink_hz > 0),
                tdma INTEGER CHECK(tdma IS NULL OR tdma IN (0, 1)),
                timeslots INTEGER CHECK(timeslots IS NULL OR timeslots > 0),
                first_seen_ms INTEGER NOT NULL CHECK(first_seen_ms > 0),
                last_seen_ms INTEGER NOT NULL CHECK(last_seen_ms >= first_seen_ms),
                observation_count INTEGER NOT NULL DEFAULT 1 CHECK(observation_count > 0),
                callsign TEXT,
                PRIMARY KEY(channel_id, channel_key),
                FOREIGN KEY(channel_id) REFERENCES p25_site_snapshot(channel_id) ON DELETE CASCADE
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_channel_tag (
                channel_id INTEGER NOT NULL,
                channel_key TEXT NOT NULL,
                tag TEXT NOT NULL CHECK(length(trim(tag)) > 0),
                confirmed_at_ms INTEGER NOT NULL CHECK(confirmed_at_ms > 0),
                PRIMARY KEY(channel_id, channel_key, tag),
                FOREIGN KEY(channel_id, channel_key) REFERENCES p25_site_channel(channel_id, channel_key) ON DELETE CASCADE
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_channel_tag_summary (
                channel_id INTEGER NOT NULL,
                channel_key TEXT NOT NULL,
                tag TEXT NOT NULL CHECK(length(trim(tag)) > 0),
                first_seen_ms INTEGER NOT NULL CHECK(first_seen_ms > 0),
                last_seen_ms INTEGER NOT NULL CHECK(last_seen_ms >= first_seen_ms),
                observation_count INTEGER NOT NULL DEFAULT 1 CHECK(observation_count > 0),
                PRIMARY KEY(channel_id, channel_key, tag),
                FOREIGN KEY(channel_id, channel_key) REFERENCES p25_site_channel_summary(channel_id, channel_key)
                    ON DELETE CASCADE
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_frequency_band (
                channel_id INTEGER NOT NULL,
                band INTEGER NOT NULL CHECK(band BETWEEN 0 AND 15),
                tdma INTEGER CHECK(tdma IS NULL OR tdma IN (0, 1)),
                base_hz INTEGER CHECK(base_hz IS NULL OR base_hz > 0),
                bandwidth INTEGER CHECK(bandwidth IS NULL OR bandwidth > 0),
                spacing_hz INTEGER CHECK(spacing_hz IS NULL OR spacing_hz > 0),
                transmit_offset_hz INTEGER,
                timeslots INTEGER CHECK(timeslots IS NULL OR timeslots > 0),
                confirmed_at_ms INTEGER NOT NULL CHECK(confirmed_at_ms > 0),
                PRIMARY KEY(channel_id, band),
                FOREIGN KEY(channel_id) REFERENCES p25_site_snapshot(channel_id) ON DELETE CASCADE
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_frequency_band_summary (
                channel_id INTEGER NOT NULL,
                band INTEGER NOT NULL CHECK(band BETWEEN 0 AND 15),
                tdma INTEGER CHECK(tdma IS NULL OR tdma IN (0, 1)),
                base_hz INTEGER CHECK(base_hz IS NULL OR base_hz > 0),
                bandwidth INTEGER CHECK(bandwidth IS NULL OR bandwidth > 0),
                spacing_hz INTEGER CHECK(spacing_hz IS NULL OR spacing_hz > 0),
                transmit_offset_hz INTEGER,
                timeslots INTEGER CHECK(timeslots IS NULL OR timeslots > 0),
                first_seen_ms INTEGER NOT NULL CHECK(first_seen_ms > 0),
                last_seen_ms INTEGER NOT NULL CHECK(last_seen_ms >= first_seen_ms),
                observation_count INTEGER NOT NULL DEFAULT 1 CHECK(observation_count > 0),
                PRIMARY KEY(channel_id, band),
                FOREIGN KEY(channel_id) REFERENCES p25_site_snapshot(channel_id) ON DELETE CASCADE
            )
            """);
        createForeignSystemBandTables(statement);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_neighbor (
                channel_id INTEGER NOT NULL,
                neighbor_key TEXT NOT NULL CHECK(length(trim(neighbor_key)) > 0),
                system_id INTEGER CHECK(system_id IS NULL OR system_id BETWEEN 0 AND 4095),
                rfss INTEGER CHECK(rfss IS NULL OR rfss BETWEEN 0 AND 255),
                site INTEGER CHECK(site IS NULL OR site BETWEEN 0 AND 255),
                lra INTEGER CHECK(lra IS NULL OR lra BETWEEN 0 AND 255),
                channel_descriptor TEXT,
                downlink_hz INTEGER CHECK(downlink_hz IS NULL OR downlink_hz > 0),
                uplink_hz INTEGER CHECK(uplink_hz IS NULL OR uplink_hz > 0),
                status TEXT,
                confirmed_at_ms INTEGER NOT NULL CHECK(confirmed_at_ms > 0),
                PRIMARY KEY(channel_id, neighbor_key),
                FOREIGN KEY(channel_id) REFERENCES p25_site_snapshot(channel_id) ON DELETE CASCADE
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_neighbor_summary (
                channel_id INTEGER NOT NULL,
                neighbor_key TEXT NOT NULL CHECK(length(trim(neighbor_key)) > 0),
                system_id INTEGER CHECK(system_id IS NULL OR system_id BETWEEN 0 AND 4095),
                rfss INTEGER CHECK(rfss IS NULL OR rfss BETWEEN 0 AND 255),
                site INTEGER CHECK(site IS NULL OR site BETWEEN 0 AND 255),
                lra INTEGER CHECK(lra IS NULL OR lra BETWEEN 0 AND 255),
                channel_descriptor TEXT,
                downlink_hz INTEGER CHECK(downlink_hz IS NULL OR downlink_hz > 0),
                uplink_hz INTEGER CHECK(uplink_hz IS NULL OR uplink_hz > 0),
                status TEXT,
                first_seen_ms INTEGER NOT NULL CHECK(first_seen_ms > 0),
                last_seen_ms INTEGER NOT NULL CHECK(last_seen_ms >= first_seen_ms),
                observation_count INTEGER NOT NULL DEFAULT 1 CHECK(observation_count > 0),
                PRIMARY KEY(channel_id, neighbor_key),
                FOREIGN KEY(channel_id) REFERENCES p25_site_snapshot(channel_id) ON DELETE CASCADE
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_patch_group (
                channel_id INTEGER NOT NULL,
                patch_group INTEGER NOT NULL CHECK(patch_group > 0),
                version INTEGER CHECK(version IS NULL OR version >= 0),
                confirmed_at_ms INTEGER NOT NULL CHECK(confirmed_at_ms > 0),
                PRIMARY KEY(channel_id, patch_group),
                FOREIGN KEY(channel_id) REFERENCES p25_site_snapshot(channel_id) ON DELETE CASCADE
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_patch_group_summary (
                channel_id INTEGER NOT NULL,
                patch_group INTEGER NOT NULL CHECK(patch_group > 0),
                version INTEGER CHECK(version IS NULL OR version >= 0),
                first_seen_ms INTEGER NOT NULL CHECK(first_seen_ms > 0),
                last_seen_ms INTEGER NOT NULL CHECK(last_seen_ms >= first_seen_ms),
                observation_count INTEGER NOT NULL DEFAULT 1 CHECK(observation_count > 0),
                PRIMARY KEY(channel_id, patch_group),
                FOREIGN KEY(channel_id) REFERENCES p25_site_snapshot(channel_id) ON DELETE CASCADE
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_patch_group_talkgroup (
                channel_id INTEGER NOT NULL,
                patch_group INTEGER NOT NULL CHECK(patch_group > 0),
                talkgroup_id INTEGER NOT NULL CHECK(talkgroup_id > 0),
                confirmed_at_ms INTEGER NOT NULL CHECK(confirmed_at_ms > 0),
                PRIMARY KEY(channel_id, patch_group, talkgroup_id),
                FOREIGN KEY(channel_id, patch_group) REFERENCES p25_site_patch_group(channel_id, patch_group)
                    ON DELETE CASCADE
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_patch_group_talkgroup_summary (
                channel_id INTEGER NOT NULL,
                patch_group INTEGER NOT NULL CHECK(patch_group > 0),
                talkgroup_id INTEGER NOT NULL CHECK(talkgroup_id > 0),
                first_seen_ms INTEGER NOT NULL CHECK(first_seen_ms > 0),
                last_seen_ms INTEGER NOT NULL CHECK(last_seen_ms >= first_seen_ms),
                observation_count INTEGER NOT NULL DEFAULT 1 CHECK(observation_count > 0),
                PRIMARY KEY(channel_id, patch_group, talkgroup_id),
                FOREIGN KEY(channel_id, patch_group)
                    REFERENCES p25_site_patch_group_summary(channel_id, patch_group) ON DELETE CASCADE
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_patch_group_radio (
                channel_id INTEGER NOT NULL,
                patch_group INTEGER NOT NULL CHECK(patch_group > 0),
                radio_id INTEGER NOT NULL CHECK(radio_id > 0),
                confirmed_at_ms INTEGER NOT NULL CHECK(confirmed_at_ms > 0),
                PRIMARY KEY(channel_id, patch_group, radio_id),
                FOREIGN KEY(channel_id, patch_group) REFERENCES p25_site_patch_group(channel_id, patch_group)
                    ON DELETE CASCADE
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_patch_group_radio_summary (
                channel_id INTEGER NOT NULL,
                patch_group INTEGER NOT NULL CHECK(patch_group > 0),
                radio_id INTEGER NOT NULL CHECK(radio_id > 0),
                first_seen_ms INTEGER NOT NULL CHECK(first_seen_ms > 0),
                last_seen_ms INTEGER NOT NULL CHECK(last_seen_ms >= first_seen_ms),
                observation_count INTEGER NOT NULL DEFAULT 1 CHECK(observation_count > 0),
                PRIMARY KEY(channel_id, patch_group, radio_id),
                FOREIGN KEY(channel_id, patch_group)
                    REFERENCES p25_site_patch_group_summary(channel_id, patch_group) ON DELETE CASCADE
            )
            """);
    }

    private static void createControlChannelQualityTable(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS trunked_control_channel_quality (
                channel_id INTEGER NOT NULL REFERENCES receiver_channel(id) ON DELETE CASCADE,
                frequency_hz INTEGER NOT NULL CHECK(frequency_hz > 0),
                bucket_start_ms INTEGER NOT NULL CHECK(bucket_start_ms >= 0),
                observed_at_ms INTEGER NOT NULL CHECK(
                    observed_at_ms >= bucket_start_ms AND observed_at_ms < bucket_start_ms + 10000
                ),
                signal_dbfs REAL,
                average_signal_dbfs REAL,
                minimum_signal_dbfs REAL,
                maximum_signal_dbfs REAL,
                decode_health_pct REAL CHECK(
                    decode_health_pct IS NULL OR decode_health_pct BETWEEN 0.0 AND 100.0
                ),
                valid_frames INTEGER NOT NULL DEFAULT 0 CHECK(valid_frames >= 0),
                invalid_frames INTEGER NOT NULL DEFAULT 0 CHECK(invalid_frames >= 0),
                corrected_bits INTEGER NOT NULL DEFAULT 0 CHECK(corrected_bits >= 0),
                sync_loss_bits INTEGER NOT NULL DEFAULT 0 CHECK(sync_loss_bits >= 0),
                dropped_bits INTEGER NOT NULL DEFAULT 0 CHECK(dropped_bits >= 0),
                last_valid_decode_ms INTEGER NOT NULL DEFAULT 0 CHECK(last_valid_decode_ms >= 0),
                PRIMARY KEY(channel_id, frequency_hz, bucket_start_ms)
            ) WITHOUT ROWID
            """);
    }

    /** Creates the current foreign-system band tables as part of fresh current-schema creation. */
    private static void createForeignSystemBandTables(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_foreign_system_band (
                channel_id INTEGER NOT NULL,
                foreign_wacn INTEGER NOT NULL CHECK(foreign_wacn BETWEEN 0 AND 1048575),
                foreign_system_id INTEGER NOT NULL CHECK(foreign_system_id BETWEEN 0 AND 4095),
                band INTEGER NOT NULL CHECK(band BETWEEN 0 AND 15),
                channel_type INTEGER NOT NULL CHECK(channel_type >= 0),
                base_hz INTEGER CHECK(base_hz IS NULL OR base_hz > 0),
                spacing_hz INTEGER CHECK(spacing_hz IS NULL OR spacing_hz > 0),
                transmit_offset_hz INTEGER,
                confirmed_at_ms INTEGER NOT NULL CHECK(confirmed_at_ms > 0),
                PRIMARY KEY(channel_id, foreign_wacn, foreign_system_id, band),
                FOREIGN KEY(channel_id) REFERENCES p25_site_snapshot(channel_id) ON DELETE CASCADE
            ) WITHOUT ROWID
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_foreign_system_band_summary (
                channel_id INTEGER NOT NULL,
                foreign_wacn INTEGER NOT NULL CHECK(foreign_wacn BETWEEN 0 AND 1048575),
                foreign_system_id INTEGER NOT NULL CHECK(foreign_system_id BETWEEN 0 AND 4095),
                band INTEGER NOT NULL CHECK(band BETWEEN 0 AND 15),
                channel_type INTEGER NOT NULL CHECK(channel_type >= 0),
                base_hz INTEGER CHECK(base_hz IS NULL OR base_hz > 0),
                spacing_hz INTEGER CHECK(spacing_hz IS NULL OR spacing_hz > 0),
                transmit_offset_hz INTEGER,
                first_seen_ms INTEGER NOT NULL CHECK(first_seen_ms > 0),
                last_seen_ms INTEGER NOT NULL CHECK(last_seen_ms >= first_seen_ms),
                observation_count INTEGER NOT NULL DEFAULT 1 CHECK(observation_count > 0),
                PRIMARY KEY(channel_id, foreign_wacn, foreign_system_id, band),
                FOREIGN KEY(channel_id) REFERENCES p25_site_snapshot(channel_id) ON DELETE CASCADE
            ) WITHOUT ROWID
            """);
    }

    /** Creates the current retention-first shared control-channel-quality index. */
    private static void createControlChannelQualityRetentionIndex(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_control_quality_retention
            ON trunked_control_channel_quality(observed_at_ms, channel_id, frequency_hz, bucket_start_ms)
            """);
    }

    private static void createIndexesAndViews(Statement statement) throws SQLException
    {
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_receiver_channel_radio_system ON receiver_channel(radio_system_id, id)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_receiver_activity_event_channel_time ON receiver_activity_event(channel_id, observed_at_ms)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_receiver_activity_event_target_time ON receiver_activity_event(target_id, observed_at_ms) WHERE target_id IS NOT NULL");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_receiver_activity_event_source_time ON receiver_activity_event(source_radio_id, observed_at_ms) WHERE source_radio_id IS NOT NULL");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_receiver_activity_event_frequency_time ON receiver_activity_event(frequency_hz, observed_at_ms) WHERE frequency_hz IS NOT NULL");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_receiver_activity_event_encryption ON receiver_activity_event(encryption_algorithm_id, encryption_key_id, observed_at_ms) WHERE encrypted = 1");
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_activity_event_member_talkgroup_event
            ON activity_event_talkgroup_member(talkgroup_id, event_id)
            """);
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_trunked_signaling_activity_time ON trunked_signaling_activity_bucket(bucket_start_ms, channel_id)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_trunked_logical_call_bucket_time ON trunked_logical_call_bucket(bucket_start_ms, radio_system_id)");
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_logical_identity_dashboard_time
            ON trunked_logical_call_identity_bucket(
                bucket_start_ms, identity_role_code, identity_kind_code, radio_system_id, identity_id
            )
            """);
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_call_bucket_time ON p25_site_call_bucket(bucket_start_ms, radio_system_id, learned_site_id)");
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_p25_site_call_identity_time
            ON p25_site_call_identity_bucket(
                learned_site_id, radio_system_id, bucket_start_ms,
                identity_role_code, identity_kind_code, identity_id
            )
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_p25_site_call_identity_retention
            ON p25_site_call_identity_bucket(
                bucket_start_ms, radio_system_id, learned_site_id,
                identity_role_code, identity_kind_code, identity_id
            )
            """);
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_conventional_bucket_time ON conventional_activity_bucket(channel_id, bucket_start_ms)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_conventional_bucket_dashboard_time ON conventional_activity_bucket(bucket_start_ms, channel_id)");
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_conventional_call_identity_dashboard_time
            ON conventional_call_identity_bucket(
                bucket_start_ms, identity_role_code, identity_kind_code, channel_id, identity_id
            )
            """);
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_snapshot_identity ON p25_site_snapshot(rfss, site, channel_id)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_channel_frequency ON p25_site_channel(channel_id, downlink_hz)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_channel_tag_summary_channel_tag ON p25_site_channel_tag_summary(channel_id, tag, last_seen_ms DESC)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_neighbor_channel_site ON p25_site_neighbor(channel_id, system_id, rfss, site)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_patch_talkgroup ON p25_site_patch_group_talkgroup(talkgroup_id, channel_id)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_patch_radio ON p25_site_patch_group_radio(radio_id, channel_id)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_channel_summary_frequency ON p25_site_channel_summary(channel_id, downlink_hz)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_neighbor_summary_channel_site ON p25_site_neighbor_summary(channel_id, system_id, rfss, site)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_trunked_control_quality_channel_time ON trunked_control_channel_quality(channel_id, observed_at_ms DESC)");
        createControlChannelQualityRetentionIndex(statement);
        statement.executeUpdate(createResolvedViewSql());
    }

    private static final List<SqliteSchemaValidator.Table> TABLES = java.util.stream.Stream.concat(List.of(
        table("receiver_channel", "id", "configuration_id", "first_seen_ms",
            "last_seen_ms", "radio_system_id", "nac", "rfss", "site",
            "current_control_hz"),
        table("receiver_activity_event", "id", "channel_id", "observed_at_ms", "action_code", "event_type_code",
            "source_radio_id", "target_id", "target_kind_code", "frequency_hz", "lcn_band", "lcn_number",
            "timeslot", "encrypted", "encryption_algorithm_id", "encryption_key_id"),
        table("activity_event_talkgroup_member", "event_id", "talkgroup_id"),
        table("p25_learned_site", "learned_site_id", "radio_system_id", "rfss", "site", "first_seen_ms",
            "last_seen_ms"),
        table("trunked_logical_call_bucket", "radio_system_id", "bucket_start_ms", "logical_call_count",
            "encrypted_logical_call_count", "recorded_output_count", "streamed_output_count"),
        table("trunked_logical_call_identity_bucket", "radio_system_id", "bucket_start_ms", "identity_role_code",
            "identity_kind_code", "identity_id", "logical_call_count", "encrypted_logical_call_count",
            "recorded_output_count", "streamed_output_count"),
        table("p25_site_call_bucket", "radio_system_id", "learned_site_id", "bucket_start_ms",
            "observed_call_count", "encrypted_observed_call_count"),
        table("p25_site_call_identity_bucket", "radio_system_id", "learned_site_id", "bucket_start_ms",
            "identity_role_code", "identity_kind_code", "identity_id", "observed_call_count",
            "encrypted_observed_call_count"),
        tableWithTrunkedSignalingActions("trunked_signaling_activity_bucket", "channel_id", "bucket_start_ms"),
        tableWithActionsBeforeLastEvent("conventional_activity_summary", "channel_id", "frequency_hz", "timeslot",
            "first_seen_ms", "last_seen_ms", "last_event_type_code", "encrypted_count", "recorded_count",
            "streamed_count"),
        tableWithActions("conventional_activity_bucket", "channel_id", "frequency_hz", "timeslot",
            "bucket_start_ms", "encrypted_count", "recorded_count", "streamed_count"),
        table("conventional_call_identity_bucket", "channel_id", "bucket_start_ms", "identity_role_code",
            "identity_kind_code", "identity_id", "call_count", "encrypted_count", "recorded_count",
            "streamed_count"),
        table("p25_site_snapshot", "channel_id", "snapshot_hash", "first_seen_ms", "last_seen_ms", "observation_count",
            "protocol", "nac", "rfss", "site",
            "lra", "mfid", "broadcast_clock_ms", "micro_slots", "data_service", "data_access",
            "wuid_lease_minutes", "registration_service", "tdma", "voice_service", "primary_frequency_hz",
            "current_control_hz", "active_rfss_network_connection"),
        table("p25_site_channel", "channel_id", "channel_key", "descriptor", "downlink_hz", "uplink_hz",
            "tdma", "timeslots", "callsign", "confirmed_at_ms"),
        table("p25_site_channel_summary", "channel_id", "channel_key", "descriptor", "downlink_hz", "uplink_hz",
            "tdma", "timeslots", "first_seen_ms", "last_seen_ms", "observation_count", "callsign"),
        table("p25_site_channel_tag", "channel_id", "channel_key", "tag", "confirmed_at_ms"),
        table("p25_site_channel_tag_summary", "channel_id", "channel_key", "tag", "first_seen_ms", "last_seen_ms",
            "observation_count"),
        table("p25_site_frequency_band", "channel_id", "band", "tdma", "base_hz", "bandwidth", "spacing_hz",
            "transmit_offset_hz", "timeslots", "confirmed_at_ms"),
        table("p25_site_frequency_band_summary", "channel_id", "band", "tdma", "base_hz", "bandwidth",
            "spacing_hz", "transmit_offset_hz", "timeslots", "first_seen_ms", "last_seen_ms",
            "observation_count"),
        table("p25_foreign_system_band", "channel_id", "foreign_wacn", "foreign_system_id", "band",
            "channel_type", "base_hz", "spacing_hz", "transmit_offset_hz", "confirmed_at_ms"),
        table("p25_foreign_system_band_summary", "channel_id", "foreign_wacn", "foreign_system_id", "band",
            "channel_type", "base_hz", "spacing_hz", "transmit_offset_hz", "first_seen_ms", "last_seen_ms",
            "observation_count"),
        table("p25_site_neighbor", "channel_id", "neighbor_key", "system_id", "rfss", "site", "lra",
            "channel_descriptor", "downlink_hz", "uplink_hz", "status", "confirmed_at_ms"),
        table("p25_site_neighbor_summary", "channel_id", "neighbor_key", "system_id", "rfss", "site", "lra",
            "channel_descriptor", "downlink_hz", "uplink_hz", "status", "first_seen_ms", "last_seen_ms",
            "observation_count"),
        table("p25_site_patch_group", "channel_id", "patch_group", "version", "confirmed_at_ms"),
        table("p25_site_patch_group_summary", "channel_id", "patch_group", "version", "first_seen_ms", "last_seen_ms",
            "observation_count"),
        table("p25_site_patch_group_talkgroup", "channel_id", "patch_group", "talkgroup_id", "confirmed_at_ms"),
        table("p25_site_patch_group_talkgroup_summary", "channel_id", "patch_group", "talkgroup_id", "first_seen_ms",
            "last_seen_ms", "observation_count"),
        table("p25_site_patch_group_radio", "channel_id", "patch_group", "radio_id", "confirmed_at_ms"),
        table("p25_site_patch_group_radio_summary", "channel_id", "patch_group", "radio_id", "first_seen_ms",
            "last_seen_ms", "observation_count"),
        table("trunked_control_channel_quality", "channel_id", "frequency_hz", "bucket_start_ms", "observed_at_ms",
            "signal_dbfs", "average_signal_dbfs", "minimum_signal_dbfs", "maximum_signal_dbfs",
            "decode_health_pct", "valid_frames", "invalid_frames", "corrected_bits", "sync_loss_bits",
            "dropped_bits", "last_valid_decode_ms"),
        table("statistics_status", "key", "value", "updated_at_ms")
    ).stream(), RadioSystemSchema.tables().stream()).toList();
    private static final List<String> INDEXES = java.util.stream.Stream.concat(List.of(
        "idx_receiver_channel_radio_system",
        "idx_receiver_activity_event_channel_time",
        "idx_receiver_activity_event_target_time",
        "idx_receiver_activity_event_source_time",
        "idx_receiver_activity_event_frequency_time",
        "idx_receiver_activity_event_encryption",
        "idx_activity_event_member_talkgroup_event",
        "idx_trunked_signaling_activity_time",
        "idx_trunked_logical_call_bucket_time",
        "idx_trunked_logical_identity_dashboard_time",
        "idx_p25_site_call_bucket_time",
        "idx_p25_site_call_identity_time",
        "idx_p25_site_call_identity_retention",
        "idx_conventional_bucket_time",
        "idx_conventional_bucket_dashboard_time",
        "idx_conventional_call_identity_dashboard_time",
        "idx_p25_site_snapshot_identity",
        "idx_p25_site_channel_frequency",
        "idx_p25_site_channel_tag_summary_channel_tag",
        "idx_p25_site_neighbor_channel_site",
        "idx_p25_site_patch_talkgroup",
        "idx_p25_site_patch_radio",
        "idx_p25_site_channel_summary_frequency",
        "idx_p25_site_neighbor_summary_channel_site",
        "idx_trunked_control_quality_channel_time",
        "idx_trunked_control_quality_retention"
    ).stream(), RadioSystemSchema.indexes().stream()).toList();

    private static final List<String> VIEWS = List.of("receiver_activity_event_resolved");
    private static final List<ForeignKeyDefinition> REQUIRED_FOREIGN_KEYS = List.of(
        foreignKey("receiver_channel", List.of("configuration_id"), "configuration_channel",
            List.of("configuration_id"), "CASCADE"),
        foreignKey("receiver_channel", List.of("radio_system_id"), "radio_system", List.of("id"), "SET NULL"),
        foreignKey("receiver_activity_event", List.of("channel_id"), "receiver_channel", List.of("id"), "CASCADE"),
        foreignKey("activity_event_talkgroup_member", List.of("event_id"), "receiver_activity_event",
            List.of("id"), "CASCADE"),
        foreignKey("conventional_activity_summary", List.of("channel_id"), "receiver_channel", List.of("id"),
            "CASCADE"),
        foreignKey("conventional_activity_bucket", List.of("channel_id"), "receiver_channel", List.of("id"),
            "CASCADE"),
        foreignKey("p25_learned_site", List.of("radio_system_id"), "radio_system", List.of("id"), "CASCADE"),
        foreignKey("p25_site_call_bucket", List.of("learned_site_id", "radio_system_id"), "p25_learned_site",
            List.of("learned_site_id", "radio_system_id"), "CASCADE"),
        foreignKey("p25_site_call_identity_bucket", List.of("learned_site_id", "radio_system_id"),
            "p25_learned_site", List.of("learned_site_id", "radio_system_id"), "CASCADE"),
        foreignKey("p25_site_snapshot", List.of("channel_id"), "receiver_channel", List.of("id"), "CASCADE"),
        foreignKey("p25_site_channel", List.of("channel_id"), "p25_site_snapshot", List.of("channel_id"), "CASCADE"),
        foreignKey("p25_site_channel_summary", List.of("channel_id"), "p25_site_snapshot", List.of("channel_id"), "CASCADE"),
        foreignKey("p25_site_channel_tag", List.of("channel_id", "channel_key"), "p25_site_channel",
            List.of("channel_id", "channel_key"), "CASCADE"),
        foreignKey("p25_site_channel_tag_summary", List.of("channel_id", "channel_key"), "p25_site_channel_summary",
            List.of("channel_id", "channel_key"), "CASCADE"),
        foreignKey("p25_site_frequency_band", List.of("channel_id"), "p25_site_snapshot", List.of("channel_id"), "CASCADE"),
        foreignKey("p25_site_frequency_band_summary", List.of("channel_id"), "p25_site_snapshot", List.of("channel_id"),
            "CASCADE"),
        foreignKey("p25_foreign_system_band", List.of("channel_id"), "p25_site_snapshot", List.of("channel_id"), "CASCADE"),
        foreignKey("p25_foreign_system_band_summary", List.of("channel_id"), "p25_site_snapshot", List.of("channel_id"),
            "CASCADE"),
        foreignKey("p25_site_neighbor", List.of("channel_id"), "p25_site_snapshot", List.of("channel_id"), "CASCADE"),
        foreignKey("p25_site_neighbor_summary", List.of("channel_id"), "p25_site_snapshot", List.of("channel_id"), "CASCADE"),
        foreignKey("p25_site_patch_group", List.of("channel_id"), "p25_site_snapshot", List.of("channel_id"), "CASCADE"),
        foreignKey("p25_site_patch_group_summary", List.of("channel_id"), "p25_site_snapshot", List.of("channel_id"),
            "CASCADE"),
        foreignKey("p25_site_patch_group_talkgroup", List.of("channel_id", "patch_group"), "p25_site_patch_group",
            List.of("channel_id", "patch_group"), "CASCADE"),
        foreignKey("p25_site_patch_group_talkgroup_summary", List.of("channel_id", "patch_group"),
            "p25_site_patch_group_summary", List.of("channel_id", "patch_group"), "CASCADE"),
        foreignKey("p25_site_patch_group_radio", List.of("channel_id", "patch_group"), "p25_site_patch_group",
            List.of("channel_id", "patch_group"), "CASCADE"),
        foreignKey("p25_site_patch_group_radio_summary", List.of("channel_id", "patch_group"),
            "p25_site_patch_group_summary", List.of("channel_id", "patch_group"), "CASCADE"),
        foreignKey("trunked_control_channel_quality", List.of("channel_id"), "receiver_channel", List.of("id"),
            "CASCADE")
    );

    private static void upsertTrunkedSignalingMetrics(Connection connection,
                                                      ReceiverActivityRecords.ActivityEvent activity,
                                                      int channelId) throws SQLException
    {
        if(activity.action() == null || activity.action() == ReceiverActivityRecords.Action.UNKNOWN ||
            activity.action() == ReceiverActivityRecords.Action.CALL ||
            activity.action() == ReceiverActivityRecords.Action.CONTINUE)
        {
            return;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_signaling_activity_bucket(
                channel_id, bucket_start_ms, %s
            ) VALUES (?, ?, %s)
            ON CONFLICT(channel_id, bucket_start_ms) DO UPDATE SET
                %s
            """.formatted(TRUNKED_SIGNALING_ACTION_INSERT_COLUMNS,
            TRUNKED_SIGNALING_ACTION_INSERT_PLACEHOLDERS,
            actionUpdateSql("trunked_signaling_activity_bucket", TRUNKED_SIGNALING_ACTION_COUNT_COLUMNS))))
        {
            int index = 1;
            statement.setInt(index++, channelId);
            statement.setLong(index++, bucketStart(activity.observedAtEpochMilliseconds()));
            for(ReceiverActivityRecords.Action action: TRUNKED_SIGNALING_ACTIONS)
            {
                statement.setInt(index++, activity.action() == action ? 1 : 0);
            }
            statement.executeUpdate();
        }
    }

    private static void upsertCallIdentityBuckets(Connection connection,
                                                  ReceiverActivityRecords.ActivityEvent activity,
                                                  int channelId) throws SQLException
    {
        long bucket = bucketStart(activity.observedAtEpochMilliseconds());
        int encrypted = activity.encrypted() ? 1 : 0;
        int protocol = TrunkedIdentityPolicy.protocolFamilyCode(activity.protocol());

        for(CallIdentity destination: destinationIdentities(activity.targetId(), activity.targetKind(),
            activity.patchMemberTalkgroupIds(), protocol, activity.identityDomain()))
        {
            upsertCallIdentityBucket(connection, channelId, bucket, IDENTITY_ROLE_DESTINATION,
                destination.kindCode(), destination.identityId(), 1, encrypted, 0, 0);
        }

        Integer source = positiveInteger(activity.sourceRadioId());

        if(source != null && (activity.receiverKind() != ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE ||
            TrunkedIdentityPolicy.isDirectoryRadio(protocol, activity.identityDomain(), source)))
        {
            upsertCallIdentityBucket(connection, channelId, bucket, IDENTITY_ROLE_SOURCE, IDENTITY_KIND_RADIO,
                source, 1, encrypted, 0, 0);
        }
    }

    private static void upsertConventionalCallOutputIdentityBuckets(
        Connection connection, ReceiverActivityRecords.ConventionalCallOutput output, int channelId,
        int protocol, int recorded, int streamed) throws SQLException
    {
        long bucket = bucketStart(output.callStartEpochMilliseconds());

        for(CallIdentity destination: destinationIdentities(
            output.destinationId() > 0 ? Integer.toString(output.destinationId()) : null,
            output.targetKind(), output.patchMemberTalkgroupIds(), protocol, output.identityDomain()))
        {
            upsertCallIdentityBucket(connection, channelId, bucket, IDENTITY_ROLE_DESTINATION,
                destination.kindCode(), destination.identityId(), 0, 0, recorded, streamed);
        }

        if(output.sourceRadioId() != null && output.sourceRadioId() > 0 &&
            TrunkedIdentityPolicy.isDirectoryRadio(protocol, output.identityDomain(), output.sourceRadioId()))
        {
            upsertCallIdentityBucket(connection, channelId, bucket, IDENTITY_ROLE_SOURCE, IDENTITY_KIND_RADIO,
                output.sourceRadioId(), 0, 0, recorded, streamed);
        }
    }

    private static void upsertCallIdentityBucket(Connection connection, int channelId, long bucketStart,
                                                 int roleCode, int kindCode, int identityId, int calls,
                                                 int encrypted, int recorded, int streamed) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO conventional_call_identity_bucket (
                channel_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id,
                call_count, encrypted_count, recorded_count, streamed_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(
                channel_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id
            ) DO UPDATE SET
                call_count = conventional_call_identity_bucket.call_count + excluded.call_count,
                encrypted_count = conventional_call_identity_bucket.encrypted_count + excluded.encrypted_count,
                recorded_count = conventional_call_identity_bucket.recorded_count + excluded.recorded_count,
                streamed_count = conventional_call_identity_bucket.streamed_count + excluded.streamed_count
            """))
        {
            statement.setInt(1, channelId);
            statement.setLong(2, bucketStart);
            statement.setInt(3, roleCode);
            statement.setInt(4, kindCode);
            statement.setInt(5, identityId);
            statement.setInt(6, calls);
            statement.setInt(7, encrypted);
            statement.setInt(8, recorded);
            statement.setInt(9, streamed);
            statement.executeUpdate();
        }
    }

    private static List<CallIdentity> destinationIdentities(String targetId, String targetKind,
                                                             List<Integer> patchMembers, int protocol,
                                                             ReceiverActivityRecords.IdentityDomain identityDomain)
    {
        Integer target = positiveInteger(targetId);
        List<CallIdentity> identities = new ArrayList<>();
        Integer kind = TrunkedIdentityPolicy.identityKindCode(targetKind);
        boolean validTarget = target != null && kind != null &&
            TrunkedIdentityPolicy.isDirectoryIdentity(protocol, identityDomain, kind, target);

        if(validTarget && kind == IDENTITY_KIND_PATCH_GROUP)
        {
            identities.add(new CallIdentity(IDENTITY_KIND_PATCH_GROUP, target));
        }
        else if(validTarget && kind == IDENTITY_KIND_TALKGROUP)
        {
            identities.add(new CallIdentity(IDENTITY_KIND_TALKGROUP, target));
        }
        else if(validTarget && kind == IDENTITY_KIND_RADIO)
        {
            identities.add(new CallIdentity(IDENTITY_KIND_RADIO, target));
        }
        else
        {
            identities.add(new CallIdentity(IDENTITY_KIND_CHANNEL_OR_UNKNOWN, 0));
        }

        if(protocol == TrunkedIdentityPolicy.PROTOCOL_P25 &&
            Form.PATCH_GROUP.name().equals(targetKind) && patchMembers != null)
        {
            patchMembers.stream()
                .filter(member -> member != null && member > 0)
                .filter(member -> TrunkedIdentityPolicy.isDirectoryTalkgroup(protocol, identityDomain, member))
                .filter(member -> target == null || !member.equals(target))
                .distinct()
                .sorted()
                .map(member -> new CallIdentity(IDENTITY_KIND_TALKGROUP, member))
                .forEach(identities::add);
        }

        return identities;
    }

    private static long insertReceiverActivityEvent(Connection connection,
                                                    ReceiverActivityRecords.ActivityEvent activity,
                                                    int channelId) throws SQLException
    {
        Lcn lcn = Lcn.parse(activity.lcn());
        Long activityId = null;

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO receiver_activity_event (
                channel_id, observed_at_ms, action_code, event_type_code, source_radio_id, target_id, target_kind_code,
                frequency_hz, lcn_band, lcn_number, timeslot, encrypted, encryption_algorithm_id, encryption_key_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """))
        {
            statement.setInt(1, channelId);
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
                    activityId = resultSet.getLong(1);
                }
            }
        }

        if(activityId == null)
        {
            throw new SQLException("SQLite did not return an activity row identifier");
        }

        insertActivityEventTalkgroupMembers(connection, activityId, activity);
        return activityId;
    }

    private static void insertActivityEventTalkgroupMembers(Connection connection, long activityId,
                                                            ReceiverActivityRecords.ActivityEvent activity)
        throws SQLException
    {
        int protocol = TrunkedIdentityPolicy.protocolFamilyCode(activity.protocol());
        insertActivityEventTalkgroupMembers(connection, activityId, activity.targetKind(),
            activity.patchMemberTalkgroupIds(), protocol, activity.identityDomain());
    }

    private static void insertActivityEventTalkgroupMembers(Connection connection, long activityId,
                                                            String targetKind, List<Integer> patchMemberTalkgroupIds,
                                                            int protocol,
                                                            ReceiverActivityRecords.IdentityDomain identityDomain)
        throws SQLException
    {
        List<Integer> members = ("PATCH_GROUP".equals(targetKind) ?
            patchMemberTalkgroupIds : List.<Integer>of()).stream()
            .filter(member -> TrunkedIdentityPolicy.isDirectoryTalkgroup(
                protocol, identityDomain, member))
            .toList();

        if(members.isEmpty())
        {
            return;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT OR IGNORE INTO activity_event_talkgroup_member(event_id, talkgroup_id)
            VALUES (?, ?)
            """))
        {
            for(Integer member: members)
            {
                statement.setLong(1, activityId);
                statement.setInt(2, member);
                statement.addBatch();
            }

            statement.executeBatch();
        }
    }

    private static void upsertConventionalSummary(Connection connection, ReceiverActivityRecords.ActivityEvent activity,
                                                  int channelId) throws SQLException
    {
        long frequencyHertz = activity.frequencyHertz() != null && activity.frequencyHertz() > 0 ?
            activity.frequencyHertz() : 0;
        int timeslot = summaryTimeslot(activity.timeslot());

        // A decoder event can identify its saved conventional channel before it can project a frequency.
        // Keep that event out of the lifetime per-frequency directory, but count it in the compact hourly bucket so
        // dashboard action totals stay complete. Frequency zero is reserved for this aggregate-only fallback.
        if(frequencyHertz > 0)
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO conventional_activity_summary (
                    channel_id, frequency_hz, timeslot, first_seen_ms, last_seen_ms, %s, encrypted_count,
                    last_event_type_code
                ) VALUES (?, ?, ?, ?, ?, %s, ?, ?)
                ON CONFLICT(channel_id, frequency_hz, timeslot) DO UPDATE SET
                    last_seen_ms = max(conventional_activity_summary.last_seen_ms, excluded.last_seen_ms),
                    %s,
                    encrypted_count = conventional_activity_summary.encrypted_count + excluded.encrypted_count,
                    last_event_type_code = coalesce(excluded.last_event_type_code, conventional_activity_summary.last_event_type_code)
                """.formatted(ACTION_INSERT_COLUMNS, ACTION_INSERT_PLACEHOLDERS,
                actionUpdateSql("conventional_activity_summary"))))
            {
                int index = 1;
                statement.setInt(index++, channelId);
                statement.setLong(index++, frequencyHertz);
                statement.setInt(index++, timeslot);
                statement.setLong(index++, activity.observedAtEpochMilliseconds());
                statement.setLong(index++, activity.observedAtEpochMilliseconds());
                index = setActionCounts(statement, index, activity);
                statement.setInt(index++, activity.encrypted() ? 1 : 0);
                setInteger(statement, index, eventTypeCode(activity.eventType()));
                statement.executeUpdate();
            }
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO conventional_activity_bucket (
                channel_id, frequency_hz, timeslot, bucket_start_ms, %s, encrypted_count
            ) VALUES (?, ?, ?, ?, %s, ?)
            ON CONFLICT(channel_id, frequency_hz, timeslot, bucket_start_ms) DO UPDATE SET
                %s,
                encrypted_count = conventional_activity_bucket.encrypted_count + excluded.encrypted_count
            """.formatted(ACTION_INSERT_COLUMNS, ACTION_INSERT_PLACEHOLDERS,
            actionUpdateSql("conventional_activity_bucket"))))
        {
            int index = 1;
            statement.setInt(index++, channelId);
            statement.setLong(index++, frequencyHertz);
            statement.setInt(index++, timeslot);
            statement.setLong(index++, bucketStart(activity.observedAtEpochMilliseconds()));
            index = setActionCounts(statement, index, activity);
            statement.setInt(index, activity.encrypted() ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private static int upsertReceiverChannel(Connection connection, ReceiverChannelMetadata metadata)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO receiver_channel (
                configuration_id, first_seen_ms, last_seen_ms, nac, rfss, site, current_control_hz
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(configuration_id) DO UPDATE SET
                first_seen_ms = min(receiver_channel.first_seen_ms, excluded.first_seen_ms),
                last_seen_ms = max(receiver_channel.last_seen_ms, excluded.last_seen_ms),
                nac = coalesce(excluded.nac, receiver_channel.nac),
                rfss = coalesce(excluded.rfss, receiver_channel.rfss),
                site = coalesce(excluded.site, receiver_channel.site),
                current_control_hz = coalesce(excluded.current_control_hz, receiver_channel.current_control_hz)
            WHERE excluded.last_seen_ms >= receiver_channel.last_seen_ms
            """))
        {
            statement.setString(1, metadata.configurationId());
            statement.setLong(2, metadata.firstSeenEpochMilliseconds());
            statement.setLong(3, metadata.lastSeenEpochMilliseconds());
            setInteger(statement, 4, metadata.nac());
            setInteger(statement, 5, metadata.rfss());
            setInteger(statement, 6, metadata.site());
            setLong(statement, 7, metadata.currentControlHertz());
            statement.executeUpdate();
        }
        synchronizeReceiverChannelWithConfiguration(connection, metadata.configurationId());
        return selectReceiverChannelId(connection, metadata.configurationId());
    }

    private static void synchronizeReceiverChannelWithConfiguration(Connection connection, String configurationId)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE receiver_channel
            SET radio_system_id = CASE WHEN EXISTS (
                    SELECT 1 FROM configuration_channel configured
                    WHERE configured.configuration_id = receiver_channel.configuration_id
                      AND configured.channel_kind = 'TRUNKED'
                ) THEN radio_system_id ELSE NULL END,
                nac = CASE WHEN EXISTS (
                    SELECT 1 FROM configuration_channel configured
                    WHERE configured.configuration_id = receiver_channel.configuration_id
                      AND configured.decoder_type LIKE 'P25_%'
                ) THEN nac ELSE NULL END,
                rfss = CASE WHEN EXISTS (
                    SELECT 1 FROM configuration_channel configured
                    WHERE configured.configuration_id = receiver_channel.configuration_id
                      AND configured.decoder_type LIKE 'P25_%'
                ) THEN rfss ELSE NULL END,
                site = CASE WHEN EXISTS (
                    SELECT 1 FROM configuration_channel configured
                    WHERE configured.configuration_id = receiver_channel.configuration_id
                      AND configured.decoder_type LIKE 'P25_%'
                ) THEN site ELSE NULL END,
                current_control_hz = CASE WHEN EXISTS (
                    SELECT 1 FROM configuration_channel configured
                    WHERE configured.configuration_id = receiver_channel.configuration_id
                      AND configured.channel_kind = 'TRUNKED'
                ) THEN current_control_hz ELSE NULL END
            WHERE configuration_id = ?
            """))
        {
            statement.setString(1, configurationId);
            statement.executeUpdate();
        }
    }

    private static void upsertSiteSnapshot(Connection connection, ReceiverActivityRecords.SiteSnapshot snapshot,
                                           int channelId) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_site_snapshot (
                channel_id, snapshot_hash, first_seen_ms, last_seen_ms, observation_count, protocol,
                nac, rfss, site, lra, active_rfss_network_connection, mfid,
                broadcast_clock_ms, micro_slots, data_service, data_access, wuid_lease_minutes, registration_service,
                tdma, voice_service, primary_frequency_hz, current_control_hz
            ) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(channel_id) DO UPDATE SET
                snapshot_hash = coalesce(excluded.snapshot_hash, p25_site_snapshot.snapshot_hash),
                first_seen_ms = p25_site_snapshot.first_seen_ms,
                last_seen_ms = excluded.last_seen_ms,
                observation_count = p25_site_snapshot.observation_count + 1,
                protocol = coalesce(excluded.protocol, p25_site_snapshot.protocol),
                nac = excluded.nac,
                rfss = excluded.rfss,
                site = excluded.site,
                lra = coalesce(excluded.lra, p25_site_snapshot.lra),
                active_rfss_network_connection = coalesce(excluded.active_rfss_network_connection,
                    p25_site_snapshot.active_rfss_network_connection),
                mfid = coalesce(excluded.mfid, p25_site_snapshot.mfid),
                broadcast_clock_ms = CASE WHEN excluded.micro_slots IS NOT NULL
                    THEN excluded.broadcast_clock_ms
                    ELSE coalesce(excluded.broadcast_clock_ms, p25_site_snapshot.broadcast_clock_ms) END,
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
            statement.setInt(1, channelId);
            statement.setString(2, snapshot.snapshotHash());
            statement.setLong(3, snapshot.observedAtEpochMilliseconds());
            statement.setLong(4, snapshot.observedAtEpochMilliseconds());
            statement.setString(5, snapshot.protocol());
            setInteger(statement, 6, snapshot.nac());
            setInteger(statement, 7, snapshot.rfss());
            setInteger(statement, 8, snapshot.site());
            setInteger(statement, 9, snapshot.lra());
            setBoolean(statement, 10, snapshot.activeRfssNetworkConnection());
            P25NetworkConfigurationSnapshot.SiteStatus status = snapshot.siteStatus();
            setInteger(statement, 11, status != null ? status.mfid() : null);
            setLong(statement, 12, status != null ? status.broadcastClockEpochMilliseconds() : null);
            setInteger(statement, 13, status != null ? status.microSlots() : null);
            setBoolean(statement, 14, status != null ? status.dataService() : null);
            statement.setString(15, status != null ? status.dataAccess() : null);
            setInteger(statement, 16, status != null ? status.wuidLeaseMinutes() : null);
            setBoolean(statement, 17, status != null ? status.registrationService() : null);
            setBoolean(statement, 18, snapshot.tdma());
            setBoolean(statement, 19, status != null ? status.voiceService() : null);
            setLong(statement, 20, snapshot.primaryFrequencyHertz());
            setLong(statement, 21, snapshot.currentControlHertz());
            statement.executeUpdate();
        }
    }

    private static void upsertSiteChannelSummaries(Connection connection, ReceiverActivityRecords.SiteSnapshot snapshot,
                                                   int channelId, Map<String,SiteChannelEvidence> channels)
        throws SQLException
    {
        for(Map.Entry<String,SiteChannelEvidence> entry: channels.entrySet())
        {
            String key = entry.getKey();
            SiteChannelEvidence channel = entry.getValue();

            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO p25_site_channel_summary (
                    channel_id, channel_key, descriptor, downlink_hz, uplink_hz, tdma, timeslots,
                    callsign, first_seen_ms, last_seen_ms, observation_count
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT(channel_id, channel_key) DO UPDATE SET
                    descriptor = coalesce(excluded.descriptor, p25_site_channel_summary.descriptor),
                    downlink_hz = coalesce(excluded.downlink_hz, p25_site_channel_summary.downlink_hz),
                    uplink_hz = coalesce(excluded.uplink_hz, p25_site_channel_summary.uplink_hz),
                    tdma = coalesce(excluded.tdma, p25_site_channel_summary.tdma),
                    timeslots = coalesce(excluded.timeslots, p25_site_channel_summary.timeslots),
                    callsign = coalesce(excluded.callsign, p25_site_channel_summary.callsign),
                    last_seen_ms = max(p25_site_channel_summary.last_seen_ms, excluded.last_seen_ms),
                    observation_count = p25_site_channel_summary.observation_count + 1
                """))
            {
                statement.setInt(1, channelId);
                statement.setString(2, key);
                statement.setString(3, channel.descriptor());
                setLong(statement, 4, channel.downlink());
                setLong(statement, 5, channel.uplink());
                setBoolean(statement, 6, channel.tdma());
                setInteger(statement, 7, channel.timeslots());
                statement.setString(8, channel.callsign());
                statement.setLong(9, snapshot.observedAtEpochMilliseconds());
                statement.setLong(10, snapshot.observedAtEpochMilliseconds());
                statement.executeUpdate();
            }

            for(ChannelTag tag: channel.summaryTags())
            {
                upsertChannelTagSummary(connection, channelId, key, tag,
                    snapshot.observedAtEpochMilliseconds(), 1);
            }
        }
    }

    /**
     * Adds voice and data service evidence learned from control-channel grants to the site's durable channel inventory.
     * RF/site snapshots intentionally contain only stable network facts, so grant observations are projected here
     * without feeding dynamic traffic back into the network stabilizer.
     */
    static void upsertGrantedChannelSummary(Connection connection,
                                            ReceiverActivityRecords.ChannelFact fact) throws SQLException
    {
        Lcn lcn = Lcn.parse(fact.lcn());
        ChannelTag serviceTag = fact.serviceTag();

        if(fact.configurationId() == null || fact.configurationId().isBlank() || fact.frequencyHertz() <= 0 ||
            lcn.band() == null || lcn.number() == null || serviceTag == null)
        {
            return;
        }

        if(!isCurrentP25SiteGeneration(connection, fact))
        {
            return;
        }

        int channelId = selectReceiverChannelId(connection, fact.configurationId());
        String channelKey = lcn.channelKey();
        boolean tdma = fact.tdma();

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_site_channel_summary (
                channel_id, channel_key, descriptor, downlink_hz, uplink_hz, tdma, timeslots,
                first_seen_ms, last_seen_ms, observation_count
            ) VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, 1)
            ON CONFLICT(channel_id, channel_key) DO UPDATE SET
                descriptor = coalesce(p25_site_channel_summary.descriptor, excluded.descriptor),
                downlink_hz = coalesce(excluded.downlink_hz, p25_site_channel_summary.downlink_hz),
                tdma = max(coalesce(p25_site_channel_summary.tdma, 0), excluded.tdma),
                timeslots = max(coalesce(p25_site_channel_summary.timeslots, 1), excluded.timeslots),
                last_seen_ms = max(p25_site_channel_summary.last_seen_ms, excluded.last_seen_ms),
                observation_count = p25_site_channel_summary.observation_count + 1
            """))
        {
            statement.setInt(1, channelId);
            statement.setString(2, channelKey);
            statement.setString(3, channelKey);
            statement.setLong(4, fact.frequencyHertz());
            statement.setInt(5, tdma ? 1 : 0);
            statement.setInt(6, Math.max(1, fact.timeslots()));
            statement.setLong(7, fact.observedAtEpochMilliseconds());
            statement.setLong(8, fact.observedAtEpochMilliseconds());
            statement.executeUpdate();
        }

        upsertChannelTagSummary(connection, channelId, channelKey, serviceTag,
            fact.observedAtEpochMilliseconds(), 1);
    }

    private static void upsertChannelTagSummary(Connection connection, int channelId, String channelKey,
                                                ChannelTag tag, long timestamp, int observations)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_site_channel_tag_summary
                (channel_id, channel_key, tag, first_seen_ms, last_seen_ms, observation_count)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(channel_id, channel_key, tag) DO UPDATE SET
                last_seen_ms = max(p25_site_channel_tag_summary.last_seen_ms, excluded.last_seen_ms),
                observation_count = p25_site_channel_tag_summary.observation_count + excluded.observation_count
            """))
        {
            statement.setInt(1, channelId);
            statement.setString(2, channelKey);
            statement.setString(3, tag.name());
            statement.setLong(4, timestamp);
            statement.setLong(5, timestamp);
            statement.setInt(6, Math.max(1, observations));
            statement.executeUpdate();
        }
    }

    private static void upsertSiteFrequencyBandSummaries(Connection connection,
                                                         ReceiverActivityRecords.SiteSnapshot snapshot,
                                                         int channelId)
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
                    channel_id, band, first_seen_ms, last_seen_ms, observation_count, tdma, base_hz, bandwidth,
                    spacing_hz, transmit_offset_hz, timeslots
                ) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(channel_id, band) DO UPDATE SET
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
                statement.setInt(1, channelId);
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

    private static void upsertSiteNeighborSummaries(Connection connection,
                                                    ReceiverActivityRecords.SiteSnapshot snapshot, int channelId)
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
                    channel_id, neighbor_key, system_id, rfss, site, lra, channel_descriptor, downlink_hz,
                    uplink_hz, status, first_seen_ms, last_seen_ms, observation_count
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT(channel_id, neighbor_key) DO UPDATE SET
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
                statement.setInt(1, channelId);
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
                                                         ReceiverActivityRecords.SiteSnapshot snapshot,
                                                         int channelId)
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
                    channel_id, foreign_wacn, foreign_system_id, band, channel_type, base_hz, spacing_hz,
                    transmit_offset_hz, first_seen_ms, last_seen_ms, observation_count
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT(channel_id, foreign_wacn, foreign_system_id, band) DO UPDATE SET
                    channel_type = excluded.channel_type,
                    base_hz = coalesce(excluded.base_hz, p25_foreign_system_band_summary.base_hz),
                    spacing_hz = coalesce(excluded.spacing_hz, p25_foreign_system_band_summary.spacing_hz),
                    transmit_offset_hz = coalesce(excluded.transmit_offset_hz,
                        p25_foreign_system_band_summary.transmit_offset_hz),
                    last_seen_ms = excluded.last_seen_ms,
                    observation_count = p25_foreign_system_band_summary.observation_count + 1
                """))
            {
                setForeignSystemBand(statement, channelId, band);
                statement.setLong(9, snapshot.observedAtEpochMilliseconds());
                statement.setLong(10, snapshot.observedAtEpochMilliseconds());
                statement.executeUpdate();
            }
        }
    }

    private static void upsertSitePatchSummaries(Connection connection,
                                                 ReceiverActivityRecords.SiteSnapshot snapshot, int channelId)
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
                    channel_id, patch_group, version, first_seen_ms, last_seen_ms, observation_count
                ) VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT(channel_id, patch_group) DO UPDATE SET
                    version = coalesce(excluded.version, p25_site_patch_group_summary.version),
                    last_seen_ms = excluded.last_seen_ms,
                    observation_count = p25_site_patch_group_summary.observation_count + 1
                """))
            {
                statement.setInt(1, channelId);
                statement.setInt(2, patchGroup.patchGroup());
                setInteger(statement, 3, patchGroup.version());
                statement.setLong(4, snapshot.observedAtEpochMilliseconds());
                statement.setLong(5, snapshot.observedAtEpochMilliseconds());
                statement.executeUpdate();
            }

            upsertSitePatchTalkgroupSummaries(connection, snapshot, channelId, patchGroup);
            upsertSitePatchRadioSummaries(connection, snapshot, channelId, patchGroup);
        }
    }

    private static void upsertSitePatchTalkgroupSummaries(Connection connection,
        ReceiverActivityRecords.SiteSnapshot snapshot, int channelId,
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
                INSERT INTO p25_site_patch_group_talkgroup_summary (
                    channel_id, patch_group, talkgroup_id, first_seen_ms, last_seen_ms, observation_count
                ) VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT(channel_id, patch_group, talkgroup_id) DO UPDATE SET
                    last_seen_ms = excluded.last_seen_ms,
                    observation_count = p25_site_patch_group_talkgroup_summary.observation_count + 1
                """))
            {
                statement.setInt(1, channelId);
                statement.setInt(2, patchGroup.patchGroup());
                statement.setInt(3, talkgroup);
                statement.setLong(4, snapshot.observedAtEpochMilliseconds());
                statement.setLong(5, snapshot.observedAtEpochMilliseconds());
                statement.executeUpdate();
            }
        }
    }

    private static void upsertSitePatchRadioSummaries(Connection connection,
        ReceiverActivityRecords.SiteSnapshot snapshot, int channelId,
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
                INSERT INTO p25_site_patch_group_radio_summary (
                    channel_id, patch_group, radio_id, first_seen_ms, last_seen_ms, observation_count
                ) VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT(channel_id, patch_group, radio_id) DO UPDATE SET
                    last_seen_ms = excluded.last_seen_ms,
                    observation_count = p25_site_patch_group_radio_summary.observation_count + 1
                """))
            {
                statement.setInt(1, channelId);
                statement.setInt(2, patchGroup.patchGroup());
                statement.setInt(3, radio);
                statement.setLong(4, snapshot.observedAtEpochMilliseconds());
                statement.setLong(5, snapshot.observedAtEpochMilliseconds());
                statement.executeUpdate();
            }
        }
    }

    private static SiteSnapshotState siteSnapshotState(Connection connection, int channelId)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            """
            SELECT site.snapshot_hash, site.last_seen_ms
            FROM p25_site_snapshot site
            WHERE site.channel_id = ?
            """))
        {
            statement.setInt(1, channelId);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? new SiteSnapshotState(resultSet.getString(1), resultSet.getLong(2)) : null;
            }
        }
    }

    private static boolean isCurrentP25SiteGeneration(Connection connection,
                                                       ReceiverActivityRecords.ChannelFact fact)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1
            FROM receiver_channel channel
            JOIN p25_site_snapshot site ON site.channel_id = channel.id
            WHERE channel.configuration_id = ?
              AND channel.kind_code = ?
              AND channel.protocol_code IN (?,?)
              AND ? >= channel.first_seen_ms
            LIMIT 1
            """))
        {
            statement.setString(1, fact.configurationId());
            statement.setInt(2, RECEIVER_TRUNKED_SITE);
            statement.setInt(3, PROTOCOL_APCO25);
            statement.setInt(4, PROTOCOL_APCO25_PHASE2);
            statement.setLong(5, fact.observedAtEpochMilliseconds());

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }

    private static void replaceCurrentSiteFacts(Connection connection, ReceiverActivityRecords.SiteSnapshot snapshot,
                                                int channelId, Map<String,SiteChannelEvidence> channels)
        throws SQLException
    {
        clearCurrentSiteFacts(connection, channelId);
        long timestamp = snapshot.observedAtEpochMilliseconds();

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_site_channel
                (channel_id, channel_key, descriptor, downlink_hz, uplink_hz, tdma, timeslots, callsign, confirmed_at_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(channel_id, channel_key) DO UPDATE SET
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
                statement.setInt(1, channelId);
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
            INSERT INTO p25_site_channel_tag (channel_id, channel_key, tag, confirmed_at_ms)
            VALUES (?, ?, ?, ?)
            """))
        {
            for(Map.Entry<String,SiteChannelEvidence> entry: channels.entrySet())
            {
                for(ChannelTag tag: entry.getValue().currentTags())
                {
                    statement.setInt(1, channelId);
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
                (channel_id, band, tdma, base_hz, bandwidth, spacing_hz, transmit_offset_hz, timeslots, confirmed_at_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """))
        {
            for(P25NetworkConfigurationSnapshot.FrequencyBand band: list(snapshot.frequencyBands()))
            {
                if(band != null && band.band() != null)
                {
                    statement.setInt(1, channelId);
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
                (channel_id, neighbor_key, system_id, rfss, site, lra, channel_descriptor, downlink_hz, uplink_hz,
                 status, confirmed_at_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """))
        {
            for(P25NetworkConfigurationSnapshot.NeighborSite neighbor: list(snapshot.neighborSites()))
            {
                String key = neighborKey(neighbor);

                if(key != null)
                {
                    statement.setInt(1, channelId);
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
                (channel_id, foreign_wacn, foreign_system_id, band, channel_type, base_hz, spacing_hz,
                 transmit_offset_hz, confirmed_at_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """))
        {
            for(P25NetworkConfigurationSnapshot.ForeignSystemBand band: list(snapshot.foreignSystemBands()))
            {
                if(isValidForeignSystemBand(band))
                {
                    setForeignSystemBand(statement, channelId, band);
                    statement.setLong(9, timestamp);
                    statement.addBatch();
                }
            }

            statement.executeBatch();
        }

        try(PreparedStatement group = connection.prepareStatement("""
                INSERT INTO p25_site_patch_group (channel_id, patch_group, version, confirmed_at_ms) VALUES (?, ?, ?, ?)
                """);
            PreparedStatement talkgroup = connection.prepareStatement("""
                INSERT INTO p25_site_patch_group_talkgroup
                    (channel_id, patch_group, talkgroup_id, confirmed_at_ms) VALUES (?, ?, ?, ?)
                """);
            PreparedStatement radio = connection.prepareStatement("""
                INSERT INTO p25_site_patch_group_radio
                    (channel_id, patch_group, radio_id, confirmed_at_ms) VALUES (?, ?, ?, ?)
                """))
        {
            for(P25NetworkConfigurationSnapshot.PatchGroup patch: list(snapshot.patchGroups()))
            {
                if(patch == null || patch.patchGroup() == null)
                {
                    continue;
                }

                group.setInt(1, channelId);
                group.setInt(2, patch.patchGroup());
                setInteger(group, 3, patch.version());
                group.setLong(4, timestamp);
                group.addBatch();

                for(Integer member: list(patch.talkgroups()))
                {
                    if(member != null)
                    {
                        talkgroup.setInt(1, channelId);
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
                        radio.setInt(1, channelId);
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

    private static void clearCurrentSiteFacts(Connection connection, int channelId) throws SQLException
    {
        for(String table: List.of("p25_site_patch_group_radio", "p25_site_patch_group_talkgroup",
            "p25_site_patch_group", "p25_site_neighbor", "p25_foreign_system_band", "p25_site_frequency_band",
            "p25_site_channel_tag", "p25_site_channel"))
        {
            try(PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE channel_id = ?"))
            {
                statement.setInt(1, channelId);
                statement.executeUpdate();
            }
        }
    }

    private static void confirmCurrentSiteFacts(Connection connection, ReceiverActivityRecords.SiteSnapshot snapshot,
                                                int channelId)
        throws SQLException
    {
        for(String table: List.of("p25_site_patch_group_radio", "p25_site_patch_group_talkgroup",
            "p25_site_patch_group", "p25_site_neighbor", "p25_foreign_system_band", "p25_site_frequency_band",
            "p25_site_channel_tag", "p25_site_channel"))
        {
            try(PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + table + " SET confirmed_at_ms = ? WHERE channel_id = ?"))
            {
                statement.setLong(1, snapshot.observedAtEpochMilliseconds());
                statement.setInt(2, channelId);
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

    private static void setForeignSystemBand(PreparedStatement statement, int channelId,
                                             P25NetworkConfigurationSnapshot.ForeignSystemBand band)
        throws SQLException
    {
        statement.setInt(1, channelId);
        statement.setInt(2, band.wacn());
        statement.setInt(3, band.system());
        statement.setInt(4, band.band());
        statement.setInt(5, band.channelType());
        setLong(statement, 6, band.base());
        setLong(statement, 7, band.spacing());
        setLong(statement, 8, band.transmitOffset());
    }

    private static int selectReceiverChannelId(Connection connection, String configurationId) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT id FROM receiver_channel WHERE configuration_id = ?"))
        {
            statement.setString(1, configurationId);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    return resultSet.getInt(1);
                }
            }
        }

        throw new SQLException("Missing receiver_channel row for saved channel [" + configurationId + "]");
    }

    private static ReceiverChannelState receiverChannelState(Connection connection, String configurationId)
        throws SQLException
    {
        if(configurationId == null || configurationId.isBlank())
        {
            return null;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT channel.id, channel.radio_system_id, system.p25_wacn, system.p25_system_id,
                channel.first_seen_ms, channel.last_seen_ms, configured.channel_kind,
                configured.decoder_type
            FROM receiver_channel channel
            JOIN configuration_channel configured
              ON configured.configuration_id = channel.configuration_id
            LEFT JOIN radio_system system ON system.id = channel.radio_system_id
            WHERE channel.configuration_id = ?
            """))
        {
            statement.setString(1, configurationId);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ?
                    new ReceiverChannelState(resultSet.getInt("id"),
                        receiverKindCode(resultSet.getString("channel_kind"),
                            resultSet.getString("decoder_type")),
                        protocolCodeForDecoder(resultSet.getString("decoder_type")),
                        nullableInteger(resultSet, "radio_system_id"),
                        nullableInteger(resultSet, "p25_wacn"), nullableInteger(resultSet, "p25_system_id"),
                        resultSet.getLong("first_seen_ms"),
                        resultSet.getLong("last_seen_ms")) : null;
            }
        }
    }

    private static int clearP25SiteProjection(Connection connection, int channelId) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM p25_site_snapshot WHERE channel_id = ?"))
        {
            statement.setInt(1, channelId);
            return statement.executeUpdate();
        }
    }

    private static ReceiverChannelIdentity selectReceiverChannelIdentity(Connection connection,
                                                                          String configurationId)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT channel.id, channel.radio_system_id, channel.first_seen_ms,
                system.p25_wacn, system.p25_system_id, configured.channel_kind,
                configured.decoder_type, configured.primary_frequency_hz
            FROM receiver_channel channel
            JOIN configuration_channel configured
              ON configured.configuration_id = channel.configuration_id
            LEFT JOIN radio_system system ON system.id = channel.radio_system_id
            WHERE channel.configuration_id = ?
            """))
        {
            statement.setString(1, configurationId);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    long primaryFrequency = resultSet.getLong("primary_frequency_hz");
                    Long nullablePrimaryFrequency = resultSet.wasNull() ? null : primaryFrequency;
                    return new ReceiverChannelIdentity(resultSet.getInt("id"),
                        nullableInteger(resultSet, "radio_system_id"),
                        receiverKindCode(resultSet.getString("channel_kind"),
                            resultSet.getString("decoder_type")),
                        protocolCodeForDecoder(resultSet.getString("decoder_type")),
                        nullablePrimaryFrequency, resultSet.getLong("first_seen_ms"),
                        nullableInteger(resultSet, "p25_wacn"), nullableInteger(resultSet, "p25_system_id"));
                }

                return null;
            }
        }
    }

    private static boolean matchesReceiverChannel(ReceiverChannelIdentity channel, int kindCode,
                                                  int protocolFamilyCode)
    {
        return channel != null && channel.kindCode() == kindCode &&
            TrunkedIdentityPolicy.protocolFamilyCode(channel.protocolCode()) == protocolFamilyCode;
    }

    private static boolean matchesCurrentP25Generation(ReceiverChannelIdentity channel,
                                                       ReceiverActivityRecords.ActivityEvent activity)
    {
        if(channel == null || channel.p25Wacn() == null)
        {
            return true;
        }

        if((activity.wacn() != null && !activity.wacn().equals(channel.p25Wacn())) ||
            (activity.systemId() != null && !activity.systemId().equals(channel.p25SystemId())))
        {
            return false;
        }

        boolean exactIdentity = activity.wacn() != null && activity.wacn().equals(channel.p25Wacn()) &&
            activity.systemId() != null && activity.systemId().equals(channel.p25SystemId());
        return exactIdentity ||
            activity.observedAtEpochMilliseconds() >= channel.firstSeenEpochMilliseconds();
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

    private static ForeignKeyDefinition foreignKey(String table, List<String> columns, String parentTable,
                                                    List<String> parentColumns, String onDelete)
    {
        return new ForeignKeyDefinition(table, columns, parentTable, parentColumns, onDelete);
    }

    private static void validateRequiredForeignKeys(Connection connection) throws SQLException
    {
        for(ForeignKeyDefinition expected: REQUIRED_FOREIGN_KEYS)
        {
            List<ObservedForeignKey> observed = observedForeignKeys(connection, expected.table());

            if(observed.stream().noneMatch(expected::matches))
            {
                throw new SQLException("SQLite schema is missing required foreign key for table [" +
                    expected.table() + "]: " + expected.columns() + " -> " + expected.parentTable() +
                    expected.parentColumns() + " ON DELETE " + expected.onDelete());
            }
        }
    }

    private static List<ObservedForeignKey> observedForeignKeys(Connection connection, String table)
        throws SQLException
    {
        Map<Integer,ObservedForeignKeyBuilder> builders = new LinkedHashMap<>();
        String escapedTable = table.replace("'", "''");

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT id, seq, "table", "from", "to", on_delete
                FROM pragma_foreign_key_list('%s')
                ORDER BY id, seq
                """.formatted(escapedTable)))
        {
            while(resultSet.next())
            {
                int id = resultSet.getInt("id");
                ObservedForeignKeyBuilder builder = builders.computeIfAbsent(id,
                    ignored -> new ObservedForeignKeyBuilder(resultSetString(resultSet, "table"),
                        resultSetString(resultSet, "on_delete")));
                builder.add(resultSet.getInt("seq"), resultSetString(resultSet, "from"),
                    resultSetString(resultSet, "to"));
            }
        }

        return builders.values().stream().map(ObservedForeignKeyBuilder::build).toList();
    }

    private static String resultSetString(ResultSet resultSet, String column)
    {
        try
        {
            return resultSet.getString(column);
        }
        catch(SQLException e)
        {
            throw new IllegalStateException("Unable to inspect SQLite foreign key column " + column, e);
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

    private static void validatePositiveMetadataTimestamp(Connection connection, String key) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT CAST(value AS INTEGER) FROM database_metadata WHERE key = ?
            """))
        {
            statement.setString(1, key);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next() && resultSet.getLong(1) > 0)
                {
                    return;
                }
            }
        }

        throw new SQLException("SQLite schema metadata [" + key + "] is missing or not a positive timestamp");
    }

    private record ForeignKeyDefinition(String table, List<String> columns, String parentTable,
                                        List<String> parentColumns, String onDelete)
    {
        private ForeignKeyDefinition
        {
            columns = List.copyOf(columns);
            parentColumns = List.copyOf(parentColumns);

            if(columns.isEmpty() || columns.size() != parentColumns.size())
            {
                throw new IllegalArgumentException("Foreign-key columns must have matching non-empty shapes");
            }
        }

        private boolean matches(ObservedForeignKey observed)
        {
            return parentTable.equals(observed.parentTable()) && onDelete.equals(observed.onDelete()) &&
                columns.equals(observed.columns()) && parentColumns.equals(observed.parentColumns());
        }
    }

    private record ObservedForeignKey(String parentTable, String onDelete, List<String> columns,
                                      List<String> parentColumns)
    {
    }

    private static final class ObservedForeignKeyBuilder
    {
        private final String mParentTable;
        private final String mOnDelete;
        private final List<String> mColumns = new ArrayList<>();
        private final List<String> mParentColumns = new ArrayList<>();

        private ObservedForeignKeyBuilder(String parentTable, String onDelete)
        {
            mParentTable = parentTable;
            mOnDelete = onDelete;
        }

        private void add(int sequence, String column, String parentColumn)
        {
            if(sequence != mColumns.size())
            {
                throw new IllegalStateException("Unexpected SQLite foreign-key column sequence " + sequence);
            }

            mColumns.add(column);
            mParentColumns.add(parentColumn);
        }

        private ObservedForeignKey build()
        {
            return new ObservedForeignKey(mParentTable, mOnDelete, List.copyOf(mColumns),
                List.copyOf(mParentColumns));
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
            DELETE FROM trunked_control_channel_quality
            WHERE (channel_id, frequency_hz, bucket_start_ms) IN (
                SELECT channel_id, frequency_hz, bucket_start_ms
                FROM trunked_control_channel_quality INDEXED BY idx_trunked_control_quality_retention
                WHERE observed_at_ms < ?
                ORDER BY observed_at_ms, channel_id, frequency_hz, bucket_start_ms
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
            insertionPoint = list.indexOf("recorded_count");
        }

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

    private static SqliteSchemaValidator.Table tableWithTrunkedSignalingActions(String name, String... columns)
    {
        List<String> list = new ArrayList<>(List.of(columns));
        list.addAll(TRUNKED_SIGNALING_ACTION_COUNT_COLUMNS);
        return new SqliteSchemaValidator.Table(name, list);
    }

    private static SqliteSchemaValidator.Table tableWithActionsBeforeLastEvent(String name, String... columns)
    {
        List<String> list = new ArrayList<>(List.of(columns));
        int insertionPoint = list.indexOf("last_event_type_code");

        if(insertionPoint < 0)
        {
            throw new IllegalArgumentException("Expected last_event_type_code column for table " + name);
        }

        list.addAll(insertionPoint, ACTION_COUNT_COLUMNS);
        return new SqliteSchemaValidator.Table(name, list);
    }

    private static String actionUpdateSql(String table)
    {
        return actionUpdateSql(table, ACTION_COUNT_COLUMNS);
    }

    private static String actionUpdateSql(String table, List<String> columns)
    {
        return columns.stream()
            .map(column -> column + " = " + table + "." + column + " + excluded." + column)
            .collect(Collectors.joining(",\n                "));
    }

    private static int setActionCounts(PreparedStatement statement, int index,
                                       ReceiverActivityRecords.ActivityEvent activity) throws SQLException
    {
        for(ReceiverActivityRecords.Action action: ACTIONS)
        {
            boolean counted = activity.action() == action;

            if(action == ReceiverActivityRecords.Action.CALL)
            {
                counted = activity.countedCall();
            }

            statement.setInt(index++, counted ? 1 : 0);
        }

        return index;
    }

    private static boolean isConventional(ReceiverActivityRecords.ReceiverKind receiverKind)
    {
        return receiverKind == ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_P25 ||
            receiverKind == ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_DMR ||
            receiverKind == ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_NXDN ||
            receiverKind == ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_ANALOG;
    }

    private static boolean isTalkgroup(String targetKind)
    {
        return "TALKGROUP".equals(targetKind) || "PATCH_GROUP".equals(targetKind);
    }

    /**
     * TIA-102.BAAC-A sections 2.4 and 2.5 reserve zero as "no one", reserve talkgroup FFFF as "everyone", and
     * reserve radio identities FFFFFC-FFFFFF for infrastructure/special purposes.  These values remain visible in
     * detailed activity, but are not projected into subscriber/talkgroup directory tables.
     */
    private static boolean isDirectoryTalkgroup(Integer talkgroup)
    {
        return talkgroup != null && talkgroup > 0 && talkgroup < P25_EVERYONE_TALKGROUP;
    }

    private static boolean isDirectoryRadio(Integer radio)
    {
        return radio != null && radio > 0 && radio < P25_FIRST_SPECIAL_RADIO;
    }

    private static List<Integer> patchMemberTalkgroups(ReceiverActivityRecords.ActivityEvent activity)
    {
        return "PATCH_GROUP".equals(activity.targetKind()) ? activity.patchMemberTalkgroupIds() : List.of();
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

    private static int actionCode(ReceiverActivityRecords.Action action)
    {
        return action != null ? action.code() : ReceiverActivityRecords.Action.UNKNOWN.code();
    }

    private static Integer eventTypeCode(String eventType)
    {
        if(eventType == null || eventType.isBlank())
        {
            return null;
        }

        try
        {
            return ReceiverActivityCodes.eventTypeCode(DecodeEventType.valueOf(eventType));
        }
        catch(IllegalArgumentException e)
        {
            return null;
        }
    }

    private static int receiverKindCode(ReceiverActivityRecords.ReceiverKind receiverKind)
    {
        if(receiverKind == ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE)
        {
            return RECEIVER_TRUNKED_SITE;
        }

        if(receiverKind == ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_ANALOG)
        {
            return RECEIVER_CONVENTIONAL_ANALOG;
        }

        if(receiverKind == ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_DMR)
        {
            return RECEIVER_CONVENTIONAL_DMR;
        }

        if(receiverKind == ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_NXDN)
        {
            return RECEIVER_CONVENTIONAL_NXDN;
        }

        return RECEIVER_CONVENTIONAL_P25;
    }

    private static int receiverKindCode(String channelKind, String decoderType)
    {
        if("TRUNKED".equals(channelKind))
        {
            return RECEIVER_TRUNKED_SITE;
        }

        return switch(decoderType != null ? decoderType : "")
        {
            case "DMR" -> RECEIVER_CONVENTIONAL_DMR;
            case "NXDN" -> RECEIVER_CONVENTIONAL_NXDN;
            case "AM", "NBFM" -> RECEIVER_CONVENTIONAL_ANALOG;
            default -> RECEIVER_CONVENTIONAL_P25;
        };
    }

    static int protocolCodeForDecoder(String decoderType)
    {
        return switch(decoderType != null ? decoderType : "")
        {
            case "P25_PHASE1", "P25_CONVENTIONAL" -> PROTOCOL_APCO25;
            case "P25_PHASE2" -> PROTOCOL_APCO25_PHASE2;
            case "DMR" -> PROTOCOL_DMR;
            case "NXDN" -> PROTOCOL_NXDN;
            case "NBFM" -> PROTOCOL_NBFM;
            case "AM" -> PROTOCOL_AM;
            default -> PROTOCOL_UNKNOWN;
        };
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

    private static Integer positiveInteger(String value)
    {
        Integer parsed = parseInteger(value);
        return parsed != null && parsed > 0 ? parsed : null;
    }

    private static Integer nullableInteger(ResultSet resultSet, String column) throws SQLException
    {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
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

    private static Map<String,SiteChannelEvidence> mergeSiteChannels(ReceiverActivityRecords.SiteSnapshot snapshot)
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
                    List<String> conflicts = existing.conflictsWith(incoming);
                    merged.put(key, existing.merge(incoming));

                    if(!conflicts.isEmpty())
                    {
                        warnSiteChannelConflict(snapshot.configurationId(), key, conflicts);
                    }
                }
            }
        }

        return merged;
    }

    private record ReceiverChannelIdentity(int channelId, Integer radioSystemId, int kindCode, int protocolCode,
                                           Long primaryFrequencyHertz, long firstSeenEpochMilliseconds,
                                           Integer p25Wacn, Integer p25SystemId)
    {
    }

    private record ReceiverChannelState(int channelId, int kindCode, int protocolCode, Integer radioSystemId,
                                        Integer p25Wacn, Integer p25SystemId,
                                        long firstSeenEpochMilliseconds, long lastSeenEpochMilliseconds)
    {
    }

    private record SiteSnapshotState(String snapshotHash, long lastSeenEpochMilliseconds)
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

        private List<String> conflictsWith(SiteChannelEvidence other)
        {
            List<String> conflicts = new ArrayList<>();

            if(downlink != null && downlink > 0 && other.downlink != null && other.downlink > 0)
            {
                addConflict(conflicts, "downlink_hz", downlink, other.downlink);
            }

            if(uplink != null && uplink > 0 && other.uplink != null && other.uplink > 0)
            {
                addConflict(conflicts, "uplink_hz", uplink, other.uplink);
            }

            addConflict(conflicts, "tdma", tdma, other.tdma);
            addConflict(conflicts, "timeslots", timeslots, other.timeslots);

            if(callsign != null && !callsign.isBlank() && other.callsign != null && !other.callsign.isBlank() &&
                !callsign.strip().equals(other.callsign.strip()))
            {
                conflicts.add("callsign [" + callsign + "] vs [" + other.callsign + "]");
            }

            return conflicts;
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

    private static void addConflict(List<String> conflicts, String field, Object existing, Object incoming)
    {
        if(existing != null && incoming != null && !existing.equals(incoming))
        {
            conflicts.add(field + " [" + existing + "] vs [" + incoming + "]");
        }
    }

    private static final org.slf4j.Logger mLog =
        org.slf4j.LoggerFactory.getLogger(ReceiverActivitySchema.class);
    private static final java.util.concurrent.ConcurrentMap<String,Long> mSiteChannelConflictWarnings =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static void warnSiteChannelConflict(String configurationId, String key, List<String> conflicts)
    {
        String warningKey = configurationId + ':' + key;
        long now = System.currentTimeMillis();
        Long previous = mSiteChannelConflictWarnings.get(warningKey);

        if(previous == null || now - previous >= 300_000L)
        {
            mSiteChannelConflictWarnings.put(warningKey, now);
            mLog.warn("Merging conflicting P25 site channel [{}] for site [{}]: {}",
                key, configurationId, String.join(", ", conflicts));
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
            CREATE VIEW IF NOT EXISTS receiver_activity_event_resolved AS
            SELECT
                a.id,
                rc.configuration_id,
                rc.id AS channel_id,
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
                %s AS channel_kind_code,
                %s AS protocol_code,
                a.action_code,
                a.event_type_code,
                a.target_kind_code,
                configured.name AS resolved_channel_name,
                configured.alias_list_name AS resolved_alias_list_name,
                configured.decoder_type AS resolved_decoder,
                system.system_key AS resolved_system_key,
                system.p25_wacn AS resolved_wacn,
                system.p25_system_id AS resolved_system_id,
                rc.nac AS resolved_nac,
                rc.rfss AS resolved_rfss,
                rc.site AS resolved_site,
                rc.current_control_hz AS resolved_current_control_hz
            FROM receiver_activity_event a
            LEFT JOIN receiver_channel rc ON rc.id = a.channel_id
            LEFT JOIN configuration_channel configured ON configured.configuration_id = rc.configuration_id
            LEFT JOIN radio_system system ON system.id = rc.radio_system_id
            """.formatted(
            receiverKindCase(receiverKindSql("configured.channel_kind", "configured.decoder_type")),
            protocolCase(protocolSql("configured.decoder_type")),
            actionCase("a.action_code"), decodeEventTypeCase("a.event_type_code"),
            targetKindCase("a.target_kind_code"),
            receiverKindSql("configured.channel_kind", "configured.decoder_type"),
            protocolSql("configured.decoder_type"));
    }

    private static String receiverKindSql(String channelKind, String decoderType)
    {
        return "CASE WHEN " + channelKind + " = 'TRUNKED' THEN " + RECEIVER_TRUNKED_SITE +
            " WHEN " + decoderType + " = 'DMR' THEN " + RECEIVER_CONVENTIONAL_DMR +
            " WHEN " + decoderType + " = 'NXDN' THEN " + RECEIVER_CONVENTIONAL_NXDN +
            " WHEN " + decoderType + " IN ('AM', 'NBFM') THEN " + RECEIVER_CONVENTIONAL_ANALOG +
            " ELSE " + RECEIVER_CONVENTIONAL_P25 + " END";
    }

    private static String protocolSql(String decoderType)
    {
        return "CASE " + decoderType +
            " WHEN 'P25_PHASE1' THEN " + PROTOCOL_APCO25 +
            " WHEN 'P25_CONVENTIONAL' THEN " + PROTOCOL_APCO25 +
            " WHEN 'P25_PHASE2' THEN " + PROTOCOL_APCO25_PHASE2 +
            " WHEN 'DMR' THEN " + PROTOCOL_DMR +
            " WHEN 'NXDN' THEN " + PROTOCOL_NXDN +
            " WHEN 'NBFM' THEN " + PROTOCOL_NBFM +
            " WHEN 'AM' THEN " + PROTOCOL_AM +
            " ELSE " + PROTOCOL_UNKNOWN + " END";
    }

    private static String receiverKindCase(String expression)
    {
        return "CASE " + expression + " WHEN " + RECEIVER_TRUNKED_SITE + " THEN 'TRUNKED_SITE' WHEN " +
            RECEIVER_CONVENTIONAL_P25 + " THEN 'CONVENTIONAL_P25' WHEN " + RECEIVER_CONVENTIONAL_DMR +
            " THEN 'CONVENTIONAL_DMR' WHEN " + RECEIVER_CONVENTIONAL_NXDN +
            " THEN 'CONVENTIONAL_NXDN' WHEN " + RECEIVER_CONVENTIONAL_ANALOG +
            " THEN 'CONVENTIONAL_ANALOG' ELSE NULL END";
    }

    private static String protocolCase(String expression)
    {
        return "CASE " + expression + " WHEN " + PROTOCOL_APCO25 + " THEN 'APCO25' WHEN " +
            PROTOCOL_APCO25_PHASE2 + " THEN 'APCO25_PHASE2' WHEN " + PROTOCOL_DMR + " THEN 'DMR' WHEN " +
            PROTOCOL_NXDN + " THEN 'NXDN' WHEN " + PROTOCOL_NBFM + " THEN 'NBFM' ELSE 'UNKNOWN' END";
    }

    private static String targetKindCase(String expression)
    {
        return "CASE " + expression + " WHEN " + TARGET_TALKGROUP + " THEN 'TALKGROUP' WHEN " +
            TARGET_RADIO + " THEN 'RADIO' WHEN " + TARGET_PATCH_GROUP + " THEN 'PATCH_GROUP' ELSE NULL END";
    }

    private static String actionCase(String expression)
    {
        StringBuilder sb = new StringBuilder("CASE ").append(expression);

        for(ReceiverActivityRecords.Action value: ACTIONS)
        {
            sb.append(" WHEN ").append(actionCode(value)).append(" THEN '").append(value.name()).append("'");
        }

        return sb.append(" ELSE 'UNKNOWN' END").toString();
    }

    private static String decodeEventTypeCase(String expression)
    {
        StringBuilder sb = new StringBuilder("CASE ").append(expression);

        for(ReceiverActivityCodes.EventTypeCode value: ReceiverActivityCodes.eventTypeCodes())
        {
            sb.append(" WHEN ").append(value.code()).append(" THEN '").append(value.eventType().name()).append("'");
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

    private record CallIdentity(int kindCode, int identityId)
    {
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
