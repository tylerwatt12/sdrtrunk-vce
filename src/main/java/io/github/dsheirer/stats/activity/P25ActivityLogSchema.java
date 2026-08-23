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
 * The v26 shape is summary-first. Trunked P25, DMR and NXDN share one protocol-neutral identity projection while
 * receiver contexts own site observations. Detailed event rows are optional, while compact identity and hourly
 * summaries are always updated when stats logging is enabled.
 */
public class P25ActivityLogSchema
{
    public static final int SCHEMA_VERSION = 26;
    private static final String SCHEMA_VERSION_KEY = "p25_activity_schema_version";
    public static final String CALL_OUTPUT_METRICS_STARTED_AT_KEY = "p25_call_output_metrics_started_at_ms";
    public static final String ALL_MODE_CALL_OUTPUT_METRICS_STARTED_AT_KEY =
        "all_mode_call_output_metrics_started_at_ms";
    public static final String TRUNKED_IDENTITY_METRICS_STARTED_AT_KEY =
        "trunked_identity_metrics_started_at_ms";
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

    private static final int CONTEXT_TRUNKED_SITE = 1;
    private static final int CONTEXT_CONVENTIONAL_P25 = 2;
    private static final int CONTEXT_CONVENTIONAL_DMR = 3;
    private static final int CONTEXT_CONVENTIONAL_NXDN = 4;
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
    private static final int P25_EVERYONE_TALKGROUP = 0xFFFF;
    private static final int P25_FIRST_SPECIAL_RADIO = 0xFFFFFC;

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
            statement.executeUpdate(createActivityEventTalkgroupMemberSql());
            createP25SummaryTables(statement);
            createTrunkedIdentityTables(statement);
            createConventionalTables(statement);
            createCallIdentityTable(statement);
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
        SdrTrunkDatabaseStartup.setMetadata(connection, ALL_MODE_CALL_OUTPUT_METRICS_STARTED_AT_KEY,
            Long.toString(System.currentTimeMillis()));
        SdrTrunkDatabaseStartup.setMetadata(connection, TRUNKED_IDENTITY_METRICS_STARTED_AT_KEY,
            Long.toString(System.currentTimeMillis()));
    }

    public static void validate(Connection connection) throws SQLException
    {
        SqliteSchemaValidator.validate(connection, TABLES, INDEXES, VIEWS,
            List.of(new SqliteSchemaValidator.Metadata(SCHEMA_VERSION_KEY, Integer.toString(SCHEMA_VERSION))));
        TrunkedIdentitySchema.validate(connection);
        List<SqliteSchemaValidator.Definition> exactDefinitions =
            new ArrayList<>(TrunkedIdentitySchema.definitions());
        exactDefinitions.addAll(List.of(
            new SqliteSchemaValidator.Definition("table", "activity_event_talkgroup_member",
                createActivityEventTalkgroupMemberSql()),
            new SqliteSchemaValidator.Definition("table", "call_identity_bucket",
                createCallIdentityBucketSql()),
            new SqliteSchemaValidator.Definition("view", "p25_activity_event_resolved", createResolvedViewSql())));
        SqliteSchemaValidator.validateDefinitions(connection, exactDefinitions);
        validatePositiveMetadataTimestamp(connection, CALL_OUTPUT_METRICS_STARTED_AT_KEY);
        validatePositiveMetadataTimestamp(connection, ALL_MODE_CALL_OUTPUT_METRICS_STARTED_AT_KEY);
        validatePositiveMetadataTimestamp(connection, TRUNKED_IDENTITY_METRICS_STARTED_AT_KEY);
        validateIndexColumns(connection, "idx_p25_control_quality_retention",
            List.of("observed_at_ms", "guid", "frequency_hz", "bucket_start_ms"));
        validateIndexColumns(connection, "idx_conventional_bucket_dashboard_time",
            List.of("bucket_start_ms", "context_id"));
        validateIndexColumns(connection, "idx_call_identity_bucket_dashboard_time",
            List.of("bucket_start_ms", "identity_role_code", "identity_kind_code", "context_id", "identity_id"));
    }

    /**
     * Creates the protocol-neutral trunked identity objects. This is a startup-schema and staged-migrator entry point;
     * normal runtime services must remain validation-only.
     */
    public static void createTrunkedIdentityTables(Statement statement) throws SQLException
    {
        TrunkedIdentitySchema.create(statement);
    }

    /**
     * Creates the patch-member Activity association for a new database or backed-up staged migration.
     */
    public static void createActivityEventTalkgroupMemberObjects(Statement statement) throws SQLException
    {
        statement.executeUpdate(createActivityEventTalkgroupMemberSql());
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_activity_event_member_talkgroup_event
            ON activity_event_talkgroup_member(talkgroup_id, event_id)
            """);
    }

    /**
     * Rebuilds the resolved activity view after a staged schema migration. Normal runtime services remain
     * validation-only and must not call this method.
     */
    public static void recreateResolvedActivityView(Statement statement) throws SQLException
    {
        statement.executeUpdate("DROP VIEW IF EXISTS p25_activity_event_resolved");
        statement.executeUpdate(createResolvedViewSql());
    }

    static Long recordActivity(Connection connection, P25ActivityLogRecords.ActivityEvent activity,
                               boolean detailedEventHistoryEnabled) throws SQLException
    {
        Long activityId = null;
        Integer systemKey = activity.contextKind() == P25ActivityLogRecords.ContextKind.TRUNKED_SITE ?
            resolveP25SystemKey(connection, activity) : null;
        int contextId = upsertReceiverContext(connection, ReceiverContextMetadata.from(activity, systemKey));
        ReceiverContextIdentity context = selectContextIdentity(connection, activity.contextKey(), activity.guid());
        int activityProtocol = TrunkedIdentityPolicy.protocolFamilyCode(activity.protocol());

        if(!matchesContext(context, contextKindCode(activity.contextKind()), activityProtocol))
        {
            return null;
        }

        if(activityProtocol == TrunkedIdentityPolicy.PROTOCOL_P25 &&
            !matchesEstablishedP25Generation(context, activity))
        {
            return null;
        }

        if(activity.contextKind() == P25ActivityLogRecords.ContextKind.TRUNKED_SITE)
        {
            TrunkedIdentitySchema.Scope scope =
                TrunkedIdentitySchema.recordActivity(connection, activity, contextId);

            if(scope == null && (activityProtocol == TrunkedIdentityPolicy.PROTOCOL_DMR ||
                activityProtocol == TrunkedIdentityPolicy.PROTOCOL_NXDN ||
                (activityProtocol == TrunkedIdentityPolicy.PROTOCOL_P25 && context.systemKey() != null)))
            {
                return null;
            }

            if(detailedEventHistoryEnabled && activity.action() != P25ActivityLogRecords.Action.CONTINUE)
            {
                activityId = insertP25ActivityEvent(connection, activity, contextId);
            }

            upsertTrunkedSiteMetrics(connection, activity, contextId);

        }
        else if(isConventional(activity.contextKind()))
        {
            if(detailedEventHistoryEnabled && activity.action() != P25ActivityLogRecords.Action.CONTINUE)
            {
                activityId = insertP25ActivityEvent(connection, activity, contextId);
            }

            upsertConventionalSummary(connection, activity, contextId);
        }

        if(activity.countedCall())
        {
            upsertCallIdentityBuckets(connection, activity, contextId);
        }

        return activityId;
    }

    static boolean applyCompletedCallOutput(Connection connection,
                                            P25ActivityLogRecords.CompletedCallOutput completedCallOutput)
        throws SQLException
    {
        if(completedCallOutput == null || completedCallOutput.callStartEpochMilliseconds() <= 0 ||
            completedCallOutput.output() == null)
        {
            return false;
        }

        ReceiverContextIdentity context = selectContextIdentity(connection, completedCallOutput);

        if(context == null ||
            completedCallOutput.callStartEpochMilliseconds() < context.firstSeenEpochMilliseconds())
        {
            return false;
        }

        int recorded = completedCallOutput.output() == P25ActivityLogRecords.CallOutput.RECORDED ? 1 : 0;
        int streamed = completedCallOutput.output() == P25ActivityLogRecords.CallOutput.STREAMED ? 1 : 0;
        long callStart = completedCallOutput.callStartEpochMilliseconds();
        long bucket = bucketStart(callStart);

        if(context.kindCode() != CONTEXT_TRUNKED_SITE)
        {
            long frequency = completedCallOutput.frequencyHertz() != null &&
                completedCallOutput.frequencyHertz() > 0 ? completedCallOutput.frequencyHertz() :
                context.primaryFrequencyHertz() != null ? context.primaryFrequencyHertz() : 0;

            if(frequency <= 0)
            {
                return false;
            }

            int timeslot = summaryTimeslot(completedCallOutput.timeslot());

            try(PreparedStatement summary = connection.prepareStatement("""
                    INSERT INTO conventional_activity_summary (
                        context_id, frequency_hz, timeslot, first_seen_ms, last_seen_ms,
                        recorded_count, streamed_count
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(context_id, frequency_hz, timeslot) DO UPDATE SET
                        first_seen_ms = min(conventional_activity_summary.first_seen_ms, excluded.first_seen_ms),
                        last_seen_ms = max(conventional_activity_summary.last_seen_ms, excluded.last_seen_ms),
                        recorded_count = conventional_activity_summary.recorded_count + excluded.recorded_count,
                        streamed_count = conventional_activity_summary.streamed_count + excluded.streamed_count
                    """);
                PreparedStatement hourly = connection.prepareStatement("""
                    INSERT INTO conventional_activity_bucket (
                        context_id, frequency_hz, timeslot, bucket_start_ms, recorded_count, streamed_count
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(context_id, frequency_hz, timeslot, bucket_start_ms) DO UPDATE SET
                        recorded_count = conventional_activity_bucket.recorded_count + excluded.recorded_count,
                        streamed_count = conventional_activity_bucket.streamed_count + excluded.streamed_count
                    """))
            {
                summary.setInt(1, context.contextId());
                summary.setLong(2, frequency);
                summary.setInt(3, timeslot);
                summary.setLong(4, callStart);
                summary.setLong(5, callStart);
                summary.setInt(6, recorded);
                summary.setInt(7, streamed);
                summary.executeUpdate();

                hourly.setInt(1, context.contextId());
                hourly.setLong(2, frequency);
                hourly.setInt(3, timeslot);
                hourly.setLong(4, bucket);
                hourly.setInt(5, recorded);
                hourly.setInt(6, streamed);
                hourly.executeUpdate();
            }

            upsertCompletedCallOutputIdentityBuckets(connection, completedCallOutput, context.contextId(),
                TrunkedIdentityPolicy.protocolFamilyCode(context.protocolCode()), recorded, streamed);
            return true;
        }

        int protocol = TrunkedIdentityPolicy.protocolFamilyCode(context.protocolCode());
        TrunkedIdentitySchema.Scope scope = TrunkedIdentitySchema.ensureScope(
            connection, context.contextId(), callStart, completedCallOutput.identityDomain(), false);

        if((scope != null && protocol != TrunkedIdentityPolicy.PROTOCOL_P25 &&
            callStart < scope.firstSeenEpochMilliseconds()) ||
            (scope == null && (protocol == TrunkedIdentityPolicy.PROTOCOL_DMR ||
                protocol == TrunkedIdentityPolicy.PROTOCOL_NXDN)))
        {
            return false;
        }

        List<TalkgroupTarget> targets = talkgroupTargets(completedCallOutput.destinationId(),
            completedCallOutput.targetKind(), completedCallOutput.patchMemberTalkgroupIds(), protocol,
            completedCallOutput.identityDomain());

        try(PreparedStatement talkgroup = connection.prepareStatement("""
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
            for(TalkgroupTarget target: targets)
            {
                talkgroup.setInt(1, context.contextId());
                talkgroup.setInt(2, target.talkgroupId());
                talkgroup.setLong(3, bucket);
                talkgroup.setInt(4, recorded);
                talkgroup.setInt(5, streamed);
                talkgroup.executeUpdate();
            }

            site.setInt(1, context.contextId());
            site.setLong(2, bucket);
            site.setInt(3, recorded);
            site.setInt(4, streamed);
            site.executeUpdate();
        }

        TrunkedIdentitySchema.applyCompletedCallOutput(connection, context.contextId(), completedCallOutput,
            recorded, streamed);
        upsertCompletedCallOutputIdentityBuckets(connection, completedCallOutput, context.contextId(),
            protocol, recorded, streamed);
        return true;
    }

    /**
     * Atomically enriches an already-counted trunked call without changing physical or action counts.
     */
    static boolean applyTrunkedCallAttribution(
        Connection connection, P25ActivityLogRecords.TrunkedCallAttribution attribution) throws SQLException
    {
        if(attribution == null || attribution.callStartEpochMilliseconds() <= 0 ||
            (!attribution.destinationBecameKnown() && !attribution.sourceBecameKnown() &&
                !attribution.encryptionBecameKnown() && !attribution.hasEncryptionDetails() &&
                !attribution.hasP25TargetIdentity()))
        {
            return false;
        }

        ReceiverContextIdentity context = selectContextIdentity(connection, attribution.contextKey(),
            attribution.guid());

        if(context == null || context.kindCode() != CONTEXT_TRUNKED_SITE ||
            attribution.callStartEpochMilliseconds() < context.firstSeenEpochMilliseconds())
        {
            return false;
        }

        int protocol = TrunkedIdentityPolicy.protocolFamilyCode(context.protocolCode());

        if(!TrunkedIdentityPolicy.isSupportedProtocol(protocol))
        {
            return false;
        }

        TrunkedIdentitySchema.Scope scope = TrunkedIdentitySchema.ensureScope(connection, context.contextId(),
            attribution.callStartEpochMilliseconds(), attribution.identityDomain(), false);

        if((scope != null && protocol != TrunkedIdentityPolicy.PROTOCOL_P25 &&
            attribution.callStartEpochMilliseconds() < scope.firstSeenEpochMilliseconds()) ||
            (scope == null && (protocol == TrunkedIdentityPolicy.PROTOCOL_DMR ||
                protocol == TrunkedIdentityPolicy.PROTOCOL_NXDN)))
        {
            return false;
        }

        long bucket = bucketStart(attribution.callStartEpochMilliseconds());
        List<CallIdentity> destinations = destinationIdentities(
            attribution.destinationId() > 0 ? Integer.toString(attribution.destinationId()) : null,
            attribution.destinationKind(), attribution.patchMemberTalkgroupIds(), protocol,
            attribution.identityDomain());
        List<TalkgroupTarget> talkgroups = talkgroupTargets(attribution.destinationId(),
            attribution.destinationKind(), attribution.patchMemberTalkgroupIds(), protocol,
            attribution.identityDomain());
        P25ActivityLogRecords.ActivityEvent legacyActivity = attributionActivity(attribution, protocol);

        if(attribution.destinationBecameKnown())
        {
            int priorEncrypted = attribution.encryptedBeforeObservation() ? 1 : 0;

            try(PreparedStatement statement = connection.prepareStatement("""
                UPDATE call_identity_bucket
                SET call_count = call_count - 1,
                    encrypted_count = encrypted_count - ?
                WHERE context_id = ? AND bucket_start_ms = ?
                  AND identity_role_code = ? AND identity_kind_code = ? AND identity_id = 0
                  AND call_count > 0 AND encrypted_count >= ?
                """))
            {
                statement.setInt(1, priorEncrypted);
                statement.setInt(2, context.contextId());
                statement.setLong(3, bucket);
                statement.setInt(4, IDENTITY_ROLE_DESTINATION);
                statement.setInt(5, IDENTITY_KIND_CHANNEL_OR_UNKNOWN);
                statement.setInt(6, priorEncrypted);

                if(statement.executeUpdate() != 1)
                {
                    return false;
                }
            }

            try(PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM call_identity_bucket
                WHERE context_id = ? AND bucket_start_ms = ?
                  AND identity_role_code = ? AND identity_kind_code = ? AND identity_id = 0
                  AND call_count = 0 AND encrypted_count = 0
                  AND recorded_count = 0 AND streamed_count = 0
                """))
            {
                statement.setInt(1, context.contextId());
                statement.setLong(2, bucket);
                statement.setInt(3, IDENTITY_ROLE_DESTINATION);
                statement.setInt(4, IDENTITY_KIND_CHANNEL_OR_UNKNOWN);
                statement.executeUpdate();
            }

            for(CallIdentity destination: destinations)
            {
                upsertCallIdentityBucket(connection, context.contextId(), bucket, IDENTITY_ROLE_DESTINATION,
                    destination.kindCode(), destination.identityId(), 1, priorEncrypted, 0, 0);
            }

            for(TalkgroupTarget target: talkgroups)
            {
                upsertP25TalkgroupBucket(connection, legacyActivity, context.contextId(), target.talkgroupId());
            }
        }

        if(attribution.sourceBecameKnown() &&
            TrunkedIdentityPolicy.isDirectoryRadio(protocol, attribution.identityDomain(),
                attribution.sourceRadioId()))
        {
            upsertCallIdentityBucket(connection, context.contextId(), bucket, IDENTITY_ROLE_SOURCE,
                IDENTITY_KIND_RADIO, attribution.sourceRadioId(), 1,
                attribution.encryptedBeforeObservation() ? 1 : 0, 0, 0);
        }

        if(attribution.encryptionBecameKnown())
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO p25_site_activity_bucket (context_id, bucket_start_ms, encrypted_count)
                VALUES (?, ?, 1)
                ON CONFLICT(context_id, bucket_start_ms) DO UPDATE SET
                    encrypted_count = p25_site_activity_bucket.encrypted_count + 1
                """))
            {
                statement.setInt(1, context.contextId());
                statement.setLong(2, bucket);
                statement.executeUpdate();
            }

            for(CallIdentity destination: destinations)
            {
                upsertCallIdentityBucket(connection, context.contextId(), bucket, IDENTITY_ROLE_DESTINATION,
                    destination.kindCode(), destination.identityId(), 0, 1, 0, 0);
            }

            if(TrunkedIdentityPolicy.isDirectoryRadio(protocol, attribution.identityDomain(),
                attribution.sourceRadioId()))
            {
                upsertCallIdentityBucket(connection, context.contextId(), bucket, IDENTITY_ROLE_SOURCE,
                    IDENTITY_KIND_RADIO, attribution.sourceRadioId(), 0, 1, 0, 0);
            }

            incrementSiteAttributionEncryption(connection, context, attribution, talkgroups, bucket);
        }

        if(attribution.hasEncryptionDetails())
        {
            updateSiteAttributionEncryptionDetails(connection, context, attribution);
        }

        TrunkedIdentitySchema.applyAttribution(connection, context.contextId(), attribution);
        enrichDetailedTrunkedCall(connection, context.contextId(), protocol, attribution);
        return true;
    }

    /**
     * Updates the optional detailed row for the already-counted call.  The physical call and every compact summary
     * remain unchanged; this only fills facts that were not present on the first grant.
     */
    private static void enrichDetailedTrunkedCall(Connection connection, int contextId, int protocol,
                                                   P25ActivityLogRecords.TrunkedCallAttribution attribution)
        throws SQLException
    {
        Long activityId = findDetailedTrunkedCallId(connection, contextId, attribution);

        if(activityId == null)
        {
            return;
        }

        boolean sourceKnown = attribution.sourceBecameKnown() && attribution.sourceRadioId() != null &&
            attribution.sourceRadioId() > 0;
        boolean destinationKnown = attribution.destinationBecameKnown() && attribution.destinationId() > 0;
        boolean encryptionKnown = attribution.encryptionBecameKnown() || attribution.hasEncryptionDetails();

        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE p25_activity_event
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
    static Long findDetailedTrunkedCallId(Connection connection,
                                          P25ActivityLogRecords.TrunkedCallAttribution attribution)
        throws SQLException
    {
        if(attribution == null)
        {
            return null;
        }

        ReceiverContextIdentity context = selectContextIdentity(connection, attribution.contextKey(),
            attribution.guid());
        return context != null && context.kindCode() == CONTEXT_TRUNKED_SITE ?
            findDetailedTrunkedCallId(connection, context.contextId(), attribution) : null;
    }

    private static Long findDetailedTrunkedCallId(Connection connection, int contextId,
                                                   P25ActivityLogRecords.TrunkedCallAttribution attribution)
        throws SQLException
    {
        if(attribution.frequencyHertz() != null && attribution.frequencyHertz() > 0)
        {
            Long exactMatch = findDetailedTrunkedCallId(connection, contextId, attribution, """
                frequency_hz = ?
                """, attribution.frequencyHertz(), 1, false);

            if(exactMatch != null)
            {
                return exactMatch;
            }

            return findDetailedTrunkedCallId(connection, contextId, attribution, """
                (frequency_hz IS NULL OR frequency_hz <= 0)
                """, null, 0, true);
        }

        return findDetailedTrunkedCallId(connection, contextId, attribution, """
            (? IS NULL OR frequency_hz = ?)
            """, attribution.frequencyHertz(), 2, false);
    }

    private static Long findDetailedTrunkedCallId(
        Connection connection, int contextId, P25ActivityLogRecords.TrunkedCallAttribution attribution,
        String frequencyPredicate, Long frequency, int frequencyParameterCount, boolean requireUnique)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id
            FROM p25_activity_event
            WHERE context_id = ? AND observed_at_ms = ? AND action_code = ?
              AND %s
              AND ((? IS NULL AND timeslot IS NULL) OR timeslot = ?)
            ORDER BY id DESC
            LIMIT %d
            """.formatted(frequencyPredicate.strip(), requireUnique ? 2 : 1)))
        {
            int index = 1;
            statement.setInt(index++, contextId);
            statement.setLong(index++, attribution.callStartEpochMilliseconds());
            statement.setInt(index++, actionCode(P25ActivityLogRecords.Action.CALL));

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

    private static P25ActivityLogRecords.ActivityEvent attributionActivity(
        P25ActivityLogRecords.TrunkedCallAttribution attribution, int protocol)
    {
        return new P25ActivityLogRecords.ActivityEvent(attribution.callStartEpochMilliseconds(),
            attribution.contextKey(), attribution.guid(), P25ActivityLogRecords.ContextKind.TRUNKED_SITE,
            protocol == TrunkedIdentityPolicy.PROTOCOL_DMR ? "DMR" :
                protocol == TrunkedIdentityPolicy.PROTOCOL_NXDN ? "NXDN" : "APCO25",
            P25ActivityLogRecords.Action.CALL, "CALL",
            attribution.sourceRadioId() != null ? attribution.sourceRadioId().toString() : null,
            attribution.destinationId() > 0 ? Integer.toString(attribution.destinationId()) : null,
            attribution.destinationKind(), attribution.patchMemberTalkgroupIds(), attribution.frequencyHertz(),
            null, attribution.timeslot(), attribution.encryptedBeforeObservation(), null, null,
            null, null, null, null, null, null, null, null, true, null, null,
            attribution.identityDomain(), attribution.p25TargetIdentity(),
            attribution.p25PatchMemberIdentities());
    }

    private static List<TalkgroupTarget> talkgroupTargets(int destinationId, String destinationKind,
                                                          List<Integer> patchMembers, int protocol,
                                                          P25ActivityLogRecords.IdentityDomain identityDomain)
    {
        Integer kind = TrunkedIdentityPolicy.identityKindCode(destinationKind);

        if(kind == null || (kind != IDENTITY_KIND_TALKGROUP && kind != IDENTITY_KIND_PATCH_GROUP) ||
            !TrunkedIdentityPolicy.isDirectoryIdentity(protocol, identityDomain, kind, destinationId))
        {
            return List.of();
        }

        List<TalkgroupTarget> targets = new ArrayList<>();
        targets.add(new TalkgroupTarget(destinationId, destinationKind));

        if(protocol == TrunkedIdentityPolicy.PROTOCOL_P25 &&
            Form.PATCH_GROUP.name().equals(destinationKind) && patchMembers != null)
        {
            patchMembers.stream()
                .filter(member -> TrunkedIdentityPolicy.isDirectoryTalkgroup(protocol, identityDomain, member))
                .filter(member -> member != destinationId)
                .distinct()
                .sorted()
                .map(member -> new TalkgroupTarget(member, Form.TALKGROUP.name()))
                .forEach(targets::add);
        }

        return targets;
    }

    private static void incrementSiteAttributionEncryption(
        Connection connection, ReceiverContextIdentity context,
        P25ActivityLogRecords.TrunkedCallAttribution attribution,
        List<TalkgroupTarget> talkgroups, long bucket) throws SQLException
    {
        try(PreparedStatement siteTalkgroup = connection.prepareStatement("""
                UPDATE p25_site_talkgroup_bucket
                SET encrypted_count = encrypted_count + 1
                WHERE context_id = ? AND talkgroup_id = ? AND bucket_start_ms = ?
                """))
        {
            for(TalkgroupTarget target: talkgroups)
            {
                siteTalkgroup.setInt(1, context.contextId());
                siteTalkgroup.setInt(2, target.talkgroupId());
                siteTalkgroup.setLong(3, bucket);
                siteTalkgroup.executeUpdate();
            }
        }

        if(attribution.frequencyHertz() != null && attribution.frequencyHertz() > 0)
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                UPDATE p25_site_frequency_summary
                SET encrypted_count = encrypted_count + 1
                WHERE context_id = ? AND frequency_hz = ? AND timeslot = ?
                """))
            {
                statement.setInt(1, context.contextId());
                statement.setLong(2, attribution.frequencyHertz());
                statement.setInt(3, summaryTimeslot(attribution.timeslot()));
                statement.executeUpdate();
            }
        }
    }

    /**
     * Fills encryption facts learned after call start without changing call or encrypted counters.
     */
    private static void updateSiteAttributionEncryptionDetails(
        Connection connection, ReceiverContextIdentity context,
        P25ActivityLogRecords.TrunkedCallAttribution attribution) throws SQLException
    {
        if(attribution.frequencyHertz() == null || attribution.frequencyHertz() <= 0)
        {
            return;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE p25_site_frequency_summary
            SET last_encryption_algorithm_id = coalesce(last_encryption_algorithm_id, ?),
                last_encryption_key_id = coalesce(last_encryption_key_id, ?)
            WHERE context_id = ? AND frequency_hz = ? AND timeslot = ?
            """))
        {
            setInteger(statement, 1, attribution.encryptionAlgorithmId());
            setInteger(statement, 2, attribution.encryptionKeyId());
            statement.setInt(3, context.contextId());
            statement.setLong(4, attribution.frequencyHertz());
            statement.setInt(5, summaryTimeslot(attribution.timeslot()));
            statement.executeUpdate();
        }
    }

    /**
     * Aggregates one exactly-once completed DMR conventional call without retaining optional detailed event history.
     */
    static Long recordDmrConventionalCall(Connection connection, P25ActivityLogRecords.DmrConventionalCall call)
        throws SQLException
    {
        return recordDmrConventionalCall(connection, call, false);
    }

    /**
     * Aggregates one exactly-once completed DMR conventional call into the shared conventional channel totals and
     * compact DMR identity summaries, and optionally retains one detailed event row. The event row is inserted
     * directly so the summaries are not counted a second time through {@link #recordActivity}.
     */
    static Long recordDmrConventionalCall(Connection connection, P25ActivityLogRecords.DmrConventionalCall call,
                                          boolean detailedEventHistoryEnabled) throws SQLException
    {
        if(call == null)
        {
            return null;
        }

        DmrActivitySchema.validateCompletedCall(call);
        int contextId = upsertReceiverContext(connection, ReceiverContextMetadata.from(call));

        if(!matchesContext(selectContextIdentity(connection, call.contextKey(), call.guid()),
            CONTEXT_CONVENTIONAL_DMR, TrunkedIdentityPolicy.PROTOCOL_DMR))
        {
            return null;
        }

        String targetId = call.targetKind() == P25ActivityLogRecords.DmrTargetKind.GROUP &&
            call.talkgroupId() != null ? call.talkgroupId().toString() :
            call.targetKind() == P25ActivityLogRecords.DmrTargetKind.PRIVATE &&
                call.targetRadioId() != null ? call.targetRadioId().toString() : null;
        String targetKind = call.targetKind() == P25ActivityLogRecords.DmrTargetKind.GROUP ? Form.TALKGROUP.name() :
            call.targetKind() == P25ActivityLogRecords.DmrTargetKind.PRIVATE ? Form.RADIO.name() : null;
        String eventType = call.targetKind() == P25ActivityLogRecords.DmrTargetKind.GROUP ?
            (call.encrypted() ? DecodeEventType.CALL_GROUP_ENCRYPTED.name() : DecodeEventType.CALL_GROUP.name()) :
            call.targetKind() == P25ActivityLogRecords.DmrTargetKind.PRIVATE ?
                (call.encrypted() ? DecodeEventType.CALL_UNIT_TO_UNIT_ENCRYPTED.name() :
                    DecodeEventType.CALL_UNIT_TO_UNIT.name()) :
                (call.encrypted() ? DecodeEventType.CALL_ENCRYPTED.name() : DecodeEventType.CALL.name());
        P25ActivityLogRecords.ActivityEvent activity = new P25ActivityLogRecords.ActivityEvent(
            call.callStartEpochMilliseconds(), call.contextKey(), call.guid(),
            P25ActivityLogRecords.ContextKind.CONVENTIONAL_DMR, "DMR", P25ActivityLogRecords.Action.CALL,
            eventType, call.sourceRadioId() != null ? call.sourceRadioId().toString() : null, targetId, targetKind,
            call.frequencyHertz(), null, call.timeslot(), call.encrypted(), null, null, null, null, null, null, null,
            call.channelName(), "DMR", null, true, null, null);
        upsertConventionalSummary(connection, activity, contextId);
        upsertCallIdentityBuckets(connection, activity, contextId);
        DmrActivitySchema.recordCompletedCall(connection, contextId, call);
        return detailedEventHistoryEnabled ? insertP25ActivityEvent(connection, activity, contextId) : null;
    }

    static Long recordNxdnConventionalCall(Connection connection,
                                           P25ActivityLogRecords.NxdnConventionalCall call,
                                           boolean detailedEventHistoryEnabled) throws SQLException
    {
        if(call == null)
        {
            return null;
        }

        validateNxdnConventionalCall(call);
        int contextId = upsertReceiverContext(connection, ReceiverContextMetadata.from(call));

        if(!matchesContext(selectContextIdentity(connection, call.contextKey(), call.guid()),
            CONTEXT_CONVENTIONAL_NXDN, TrunkedIdentityPolicy.PROTOCOL_NXDN))
        {
            return null;
        }

        String targetId = call.targetKind() == P25ActivityLogRecords.NxdnTargetKind.GROUP ?
            value(call.talkgroupId()) :
            call.targetKind() == P25ActivityLogRecords.NxdnTargetKind.PRIVATE ?
                value(call.targetRadioId()) : null;
        String targetKind = call.targetKind() == P25ActivityLogRecords.NxdnTargetKind.GROUP ?
            Form.TALKGROUP.name() :
            call.targetKind() == P25ActivityLogRecords.NxdnTargetKind.PRIVATE ? Form.RADIO.name() : null;
        String eventType = call.targetKind() == P25ActivityLogRecords.NxdnTargetKind.GROUP ?
            (call.encrypted() ? DecodeEventType.CALL_GROUP_ENCRYPTED.name() : DecodeEventType.CALL_GROUP.name()) :
            call.targetKind() == P25ActivityLogRecords.NxdnTargetKind.PRIVATE ?
                (call.encrypted() ? DecodeEventType.CALL_UNIT_TO_UNIT_ENCRYPTED.name() :
                    DecodeEventType.CALL_UNIT_TO_UNIT.name()) :
                (call.encrypted() ? DecodeEventType.CALL_ENCRYPTED.name() : DecodeEventType.CALL.name());
        P25ActivityLogRecords.ActivityEvent activity = new P25ActivityLogRecords.ActivityEvent(
            call.callStartEpochMilliseconds(), call.contextKey(), call.guid(),
            P25ActivityLogRecords.ContextKind.CONVENTIONAL_NXDN, "NXDN", P25ActivityLogRecords.Action.CALL,
            eventType, value(call.sourceRadioId()), targetId, targetKind, call.frequencyHertz(), null, null,
            call.encrypted(), null, null, null, null, null, null, null, call.channelName(), "NXDN", null,
            true, null, null);
        upsertConventionalSummary(connection, activity, contextId);
        upsertCallIdentityBuckets(connection, activity, contextId);
        return detailedEventHistoryEnabled ? insertP25ActivityEvent(connection, activity, contextId) : null;
    }

    private static void validateNxdnConventionalCall(P25ActivityLogRecords.NxdnConventionalCall call)
        throws SQLException
    {
        if(call.callStartEpochMilliseconds() <= 0 ||
            call.callEndEpochMilliseconds() < call.callStartEpochMilliseconds() ||
            call.contextKey() == null || call.contextKey().isBlank() || call.frequencyHertz() <= 0 ||
            call.targetKind() == null || !validNxdnId(call.sourceRadioId()) ||
            !validNxdnId(call.talkgroupId()) || !validNxdnId(call.targetRadioId()))
        {
            throw new SQLException("Invalid completed conventional NXDN call");
        }

        if(call.targetKind() == P25ActivityLogRecords.NxdnTargetKind.GROUP && call.targetRadioId() != null ||
            call.targetKind() == P25ActivityLogRecords.NxdnTargetKind.PRIVATE && call.talkgroupId() != null ||
            call.targetKind() == P25ActivityLogRecords.NxdnTargetKind.UNKNOWN &&
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

    static void updateTalkerAlias(Connection connection, P25ActivityLogRecords.TalkerAliasUpdate update)
        throws SQLException
    {
        ReceiverContextIdentity context = selectContextIdentity(connection, update.contextKey(), update.guid());

        if(context != null && context.kindCode() == CONTEXT_TRUNKED_SITE &&
            update.observedAtEpochMilliseconds() >= context.firstSeenEpochMilliseconds())
        {
            TrunkedIdentitySchema.updateTalkerAlias(connection, context.contextId(), update.radioId(),
                update.talkerAlias(), update.observedAtEpochMilliseconds(), update.identityDomain());
        }
    }

    static void insertSite(Connection connection, P25ActivityLogRecords.SiteSnapshot snapshot) throws SQLException
    {
        SiteSnapshotState previous = siteSnapshotState(connection, snapshot.guid());
        ReceiverContextState previousContext =
            receiverContextState(connection, ReceiverContextKey.guid(snapshot.guid()));

        if((previous != null &&
            snapshot.observedAtEpochMilliseconds() < previous.lastSeenEpochMilliseconds()) ||
            (previousContext != null &&
                snapshot.observedAtEpochMilliseconds() < previousContext.lastSeenEpochMilliseconds()))
        {
            return;
        }

        Map<String,SiteChannelEvidence> channels = mergeSiteChannels(snapshot);
        Integer systemKey = upsertP25System(connection, snapshot.wacn(), snapshot.systemId(),
            snapshot.observedAtEpochMilliseconds());
        boolean generationChanged = (previous != null &&
            !java.util.Objects.equals(previous.systemKey(), systemKey)) ||
            (previousContext != null &&
                (previousContext.kindCode() != CONTEXT_TRUNKED_SITE ||
                    TrunkedIdentityPolicy.protocolFamilyCode(previousContext.protocolCode()) !=
                        TrunkedIdentityPolicy.PROTOCOL_P25));
        boolean changed = previous == null || generationChanged ||
            !java.util.Objects.equals(snapshot.snapshotHash(), previous.snapshotHash());
        int contextId = upsertReceiverContext(connection, ReceiverContextMetadata.from(snapshot, systemKey));

        if(generationChanged)
        {
            clearP25SiteProjection(connection, snapshot.guid());
        }

        //The receiver context is authoritative for which protocol-specific site projection is visible.
        TrunkedSiteSchema.clearSiteStats(connection, snapshot.guid());
        upsertSiteSnapshot(connection, snapshot, systemKey);
        TrunkedIdentitySchema.ensureScope(connection, contextId, snapshot.observedAtEpochMilliseconds(),
            P25ActivityLogRecords.IdentityDomain.STANDARD);

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

    /**
     * Rejects a delayed DMR/NXDN site snapshot when a newer observation already established the receiver's current
     * classification.
     */
    static boolean isAuthoritativeTrunkedSiteSnapshot(Connection connection, TrunkedSiteSchema.Snapshot snapshot)
        throws SQLException
    {
        if(snapshot == null || snapshot.guid() == null || snapshot.guid().isBlank())
        {
            return false;
        }

        ReceiverContextState previous = receiverContextState(connection, ReceiverContextKey.guid(snapshot.guid()));
        return previous == null ||
            snapshot.observedAtEpochMilliseconds() >= previous.lastSeenEpochMilliseconds();
    }

    /**
     * Establishes the receiver-owned identity scope for a decoded DMR/NXDN site even before its first call.
     */
    static void ensureTrunkedSiteIdentityScope(Connection connection, TrunkedSiteSchema.Snapshot snapshot)
        throws SQLException
    {
        if(snapshot == null || snapshot.guid() == null || snapshot.guid().isBlank() ||
            (snapshot.protocolCode() != TrunkedSiteSchema.PROTOCOL_DMR &&
                snapshot.protocolCode() != TrunkedSiteSchema.PROTOCOL_NXDN))
        {
            return;
        }

        String contextKey = ReceiverContextKey.guid(snapshot.guid());

        if(!isAuthoritativeTrunkedSiteSnapshot(connection, snapshot))
        {
            return;
        }

        int contextId = upsertReceiverContext(connection, ReceiverContextMetadata.from(snapshot));

        ReceiverContextIdentity context = selectContextIdentity(connection, contextKey, snapshot.guid());

        if(!matchesContext(context, CONTEXT_TRUNKED_SITE,
            TrunkedIdentityPolicy.protocolFamilyCode(snapshot.protocolCode())))
        {
            return;
        }

        //DMR/NXDN now owns this receiver GUID, so no P25 site row may remain available to web routing.
        clearP25SiteProjection(connection, snapshot.guid());

        P25ActivityLogRecords.IdentityDomain identityDomain =
            snapshot.protocolCode() == TrunkedSiteSchema.PROTOCOL_NXDN &&
                (snapshot.variantCode() == 2 || snapshot.identityDomainCode() == 4) ?
                P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D :
                snapshot.protocolCode() == TrunkedSiteSchema.PROTOCOL_NXDN ?
                    P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_C :
                    P25ActivityLogRecords.IdentityDomain.STANDARD;
        TrunkedIdentitySchema.ensureScope(connection, contextId, snapshot.observedAtEpochMilliseconds(),
            identityDomain);
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
        deleted += deleteByTime(connection, "call_identity_bucket", "bucket_start_ms", cutoffEpochMilliseconds);
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
        deleted += TrunkedIdentitySchema.deleteOlderThan(connection, cutoffEpochMilliseconds);
        return deleted;
    }

    /**
     * Removes statistics-owned trunked receiver contexts only after their configured channel and every retained fact
     * are gone. Configured quiet/zero-call receivers remain visible. A shared P25 scope may release one removed site
     * while retaining the other sites and system-owned identity history.
     */
    static int pruneInactiveTrunkedContexts(Connection connection) throws SQLException
    {
        String sql = """
            DELETE FROM receiver_context
            WHERE id IN (
                SELECT context.id
                FROM receiver_context context
                JOIN trunked_identity_scope_context ownership ON ownership.context_id = context.id
                JOIN trunked_identity_scope scope ON scope.scope_id = ownership.scope_id
                WHERE context.guid IS NOT NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM configuration_channel configured
                      WHERE configured.radres_guid = context.guid
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_activity_event fact WHERE fact.context_id = context.id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_site_frequency_summary fact WHERE fact.context_id = context.id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_site_talkgroup_bucket fact WHERE fact.context_id = context.id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_site_activity_bucket fact WHERE fact.context_id = context.id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM conventional_activity_summary fact WHERE fact.context_id = context.id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM conventional_activity_bucket fact WHERE fact.context_id = context.id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM call_identity_bucket fact WHERE fact.context_id = context.id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM dmr_conventional_talkgroup_summary fact WHERE fact.context_id = context.id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM dmr_conventional_radio_summary fact WHERE fact.context_id = context.id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_site_snapshot fact WHERE fact.guid = context.guid
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_site_channel fact WHERE fact.guid = context.guid
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_site_channel_summary fact WHERE fact.guid = context.guid
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_site_channel_tag fact WHERE fact.guid = context.guid
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_site_channel_tag_summary fact WHERE fact.guid = context.guid
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_site_frequency_band fact WHERE fact.guid = context.guid
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_site_frequency_band_summary fact WHERE fact.guid = context.guid
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_foreign_system_band fact WHERE fact.guid = context.guid
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_foreign_system_band_summary fact WHERE fact.guid = context.guid
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_site_neighbor fact WHERE fact.guid = context.guid
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_site_neighbor_summary fact WHERE fact.guid = context.guid
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_site_patch_group fact WHERE fact.guid = context.guid
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_site_patch_group_summary fact WHERE fact.guid = context.guid
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_site_patch_group_talkgroup fact WHERE fact.guid = context.guid
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_site_patch_group_talkgroup_summary fact WHERE fact.guid = context.guid
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_site_patch_group_radio fact WHERE fact.guid = context.guid
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_site_patch_group_radio_summary fact WHERE fact.guid = context.guid
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_control_channel_quality fact WHERE fact.guid = context.guid
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM trunked_site_snapshot fact WHERE fact.guid = context.guid
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM trunked_site_channel_summary fact WHERE fact.guid = context.guid
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM trunked_site_neighbor_summary fact WHERE fact.guid = context.guid
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM trunked_radio_site_presence presence
                      WHERE presence.context_id = context.id
                  )
                  AND (
                      EXISTS (
                          SELECT 1 FROM trunked_identity_scope_context other
                          WHERE other.scope_id = ownership.scope_id
                            AND other.context_id < context.id
                      )
                      OR (
                          NOT EXISTS (
                              SELECT 1 FROM trunked_identity_summary identity
                              WHERE identity.scope_id = ownership.scope_id
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM p25_zero_local_fq_talkgroup_summary identity
                              WHERE identity.scope_id = ownership.scope_id
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM trunked_radio_talkgroup_summary relationship
                              WHERE relationship.scope_id = ownership.scope_id
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM trunked_radio_affiliation affiliation
                              WHERE affiliation.scope_id = ownership.scope_id
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM trunked_radio_presence_lifecycle lifecycle
                              WHERE lifecycle.scope_id = ownership.scope_id
                          )
                      )
                  )
                LIMIT %d
            )
            """.formatted(RETENTION_DELETE_BATCH_SIZE);
        int deleted = 0;
        int batch;

        do
        {
            try(Statement statement = connection.createStatement())
            {
                batch = statement.executeUpdate(sql);
                deleted = Math.addExact(deleted, batch);
            }
        }
        while(batch > 0);

        try(Statement statement = connection.createStatement())
        {
            deleted = Math.addExact(deleted, statement.executeUpdate("""
                DELETE FROM trunked_identity_scope
                WHERE NOT EXISTS (
                    SELECT 1 FROM trunked_identity_scope_context ownership
                    WHERE ownership.scope_id = trunked_identity_scope.scope_id
                )
                """));
        }

        return deleted;
    }

    static int resetStats(Connection connection) throws SQLException
    {
        int deleted = 0;
        deleted += deleteAll(connection, "p25_activity_event");
        deleted += deleteAll(connection, "p25_site_talkgroup_bucket");
        deleted += deleteAll(connection, "p25_site_activity_bucket");
        deleted += deleteAll(connection, "call_identity_bucket");
        deleted += TrunkedIdentitySchema.reset(connection);
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

        for(Integer contextId: selectContextIdsByGuid(connection, guid))
        {
            deleted += TrunkedIdentitySchema.clearContext(connection, contextId);
        }

        deleted += deleteByContextGuid(connection, "p25_activity_event", guid);
        deleted += deleteByContextGuid(connection, "p25_site_talkgroup_bucket", guid);
        deleted += deleteByContextGuid(connection, "p25_site_activity_bucket", guid);
        deleted += deleteByContextGuid(connection, "call_identity_bucket", guid);
        deleted += deleteByContextGuid(connection, "p25_site_frequency_summary", guid);
        deleted += deleteByContextGuid(connection, "conventional_activity_bucket", guid);
        deleted += deleteByContextGuid(connection, "conventional_activity_summary", guid);
        deleted += clearP25SiteProjection(connection, guid);
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
                encrypted_count INTEGER NOT NULL DEFAULT 0,
                recorded_count INTEGER NOT NULL DEFAULT 0,
                streamed_count INTEGER NOT NULL DEFAULT 0,
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
                encrypted_count INTEGER NOT NULL DEFAULT 0,
                recorded_count INTEGER NOT NULL DEFAULT 0,
                streamed_count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(context_id, frequency_hz, timeslot, bucket_start_ms)
            )
            """.formatted(ACTION_COUNT_DEFINITIONS));
    }

    /**
     * Creates the compact, protocol-neutral identity projection used by the website's bounded top-destination and
     * top-source queries, grouped by protocol and trunked/conventional topology. A physical call is represented only
     * by counter increments: one destination (or channel fallback), an optional source, and additional destination
     * rows for patch members. Row growth is one upserted row per distinct context/hour/identity combination rather
     * than one row per call, and {@link #deleteOlderThan(Connection, long)} applies the configured activity
     * retention. Existing site and conventional hourly buckets cannot serve this query because they contain physical
     * totals but no protocol-neutral source identity, destination kind, or patch-member dimensions.
     */
    private static void createCallIdentityTable(Statement statement) throws SQLException
    {
        statement.executeUpdate(createCallIdentityBucketSql());
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
                event_id INTEGER NOT NULL REFERENCES p25_activity_event(id) ON DELETE CASCADE,
                talkgroup_id INTEGER NOT NULL CHECK(talkgroup_id > 0),
                PRIMARY KEY(event_id, talkgroup_id)
            ) WITHOUT ROWID
            """;
    }

    private static String createCallIdentityBucketSql()
    {
        return """
            CREATE TABLE IF NOT EXISTS call_identity_bucket (
                context_id INTEGER NOT NULL REFERENCES receiver_context(id) ON DELETE CASCADE,
                bucket_start_ms INTEGER NOT NULL,
                identity_role_code INTEGER NOT NULL CHECK(identity_role_code IN (1, 2)),
                identity_kind_code INTEGER NOT NULL CHECK(identity_kind_code IN (0, 1, 2, 3)),
                identity_id INTEGER NOT NULL CHECK(identity_id >= 0),
                call_count INTEGER NOT NULL DEFAULT 0 CHECK(call_count >= 0),
                encrypted_count INTEGER NOT NULL DEFAULT 0 CHECK(encrypted_count >= 0),
                recorded_count INTEGER NOT NULL DEFAULT 0 CHECK(recorded_count >= 0),
                streamed_count INTEGER NOT NULL DEFAULT 0 CHECK(streamed_count >= 0),
                PRIMARY KEY (
                    context_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id
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

    /** Creates the current foreign-system band tables as part of fresh current-schema creation. */
    private static void createForeignSystemBandTables(Statement statement) throws SQLException
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

    /** Creates the current retention-first shared control-channel-quality index. */
    private static void createControlChannelQualityRetentionIndex(Statement statement) throws SQLException
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
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_activity_event_member_talkgroup_event
            ON activity_event_talkgroup_member(talkgroup_id, event_id)
            """);
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_talkgroup_bucket_time ON p25_site_talkgroup_bucket(context_id, bucket_start_ms)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_talkgroup_bucket_talkgroup_time ON p25_site_talkgroup_bucket(talkgroup_id, bucket_start_ms)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_p25_site_activity_bucket_time ON p25_site_activity_bucket(bucket_start_ms)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_conventional_bucket_time ON conventional_activity_bucket(context_id, bucket_start_ms)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_conventional_bucket_dashboard_time ON conventional_activity_bucket(bucket_start_ms, context_id)");
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_call_identity_bucket_dashboard_time
            ON call_identity_bucket(
                bucket_start_ms, identity_role_code, identity_kind_code, context_id, identity_id
            )
            """);
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

    private static final List<SqliteSchemaValidator.Table> TABLES = java.util.stream.Stream.concat(List.of(
        table("p25_system", "system_key", "wacn", "system_id", "first_seen_ms", "last_seen_ms"),
        table("receiver_context", "id", "context_key", "guid", "kind_code", "protocol_code", "channel_name",
            "alias_list_name", "decoder", "first_seen_ms", "last_seen_ms", "system_key", "nac", "rfss",
            "site", "primary_frequency_hz", "current_control_hz"),
        table("p25_activity_event", "id", "context_id", "observed_at_ms", "action_code", "event_type_code",
            "source_radio_id", "target_id", "target_kind_code", "frequency_hz", "lcn_band", "lcn_number",
            "timeslot", "encrypted", "encryption_algorithm_id", "encryption_key_id"),
        table("activity_event_talkgroup_member", "event_id", "talkgroup_id"),
        tableWithActions("p25_site_frequency_summary", "context_id", "frequency_hz", "timeslot", "lcn_band",
            "lcn_number", "first_seen_ms", "last_seen_ms", "encrypted_count", "last_source_radio_id",
            "last_target_id", "last_encryption_algorithm_id", "last_encryption_key_id"),
        tableWithActions("p25_site_talkgroup_bucket", "context_id", "talkgroup_id", "bucket_start_ms",
            "encrypted_count", "recorded_count", "streamed_count"),
        tableWithActions("p25_site_activity_bucket", "context_id", "bucket_start_ms", "encrypted_count",
            "recorded_count", "streamed_count"),
        tableWithActionsBeforeLastEvent("conventional_activity_summary", "context_id", "frequency_hz", "timeslot",
            "first_seen_ms", "last_seen_ms", "last_event_type_code", "encrypted_count", "recorded_count",
            "streamed_count"),
        tableWithActions("conventional_activity_bucket", "context_id", "frequency_hz", "timeslot",
            "bucket_start_ms", "encrypted_count", "recorded_count", "streamed_count"),
        table("call_identity_bucket", "context_id", "bucket_start_ms", "identity_role_code",
            "identity_kind_code", "identity_id", "call_count", "encrypted_count", "recorded_count",
            "streamed_count"),
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
    ).stream(), TrunkedIdentitySchema.tables().stream()).toList();
    private static final List<String> INDEXES = java.util.stream.Stream.concat(List.of(
        "idx_receiver_context_guid",
        "idx_p25_activity_event_context_time",
        "idx_p25_activity_event_target_time",
        "idx_p25_activity_event_source_time",
        "idx_p25_activity_event_frequency_time",
        "idx_p25_activity_event_encryption",
        "idx_activity_event_member_talkgroup_event",
        "idx_p25_site_talkgroup_bucket_time",
        "idx_p25_site_talkgroup_bucket_talkgroup_time",
        "idx_p25_site_activity_bucket_time",
        "idx_conventional_bucket_time",
        "idx_conventional_bucket_dashboard_time",
        "idx_call_identity_bucket_dashboard_time",
        "idx_p25_site_snapshot_identity",
        "idx_p25_site_channel_guid_frequency",
        "idx_p25_site_channel_tag_summary_guid_tag",
        "idx_p25_site_neighbor_guid_site",
        "idx_p25_site_patch_talkgroup",
        "idx_p25_site_patch_radio",
        "idx_p25_site_channel_summary_guid_frequency",
        "idx_p25_site_neighbor_summary_guid_site",
        "idx_p25_control_quality_guid_time",
        "idx_p25_control_quality_retention"
    ).stream(), TrunkedIdentitySchema.indexes().stream()).toList();

    private static final List<String> VIEWS = List.of("p25_activity_event_resolved");

    private static void upsertTrunkedSiteMetrics(Connection connection,
                                                 P25ActivityLogRecords.ActivityEvent activity,
                                                 int contextId) throws SQLException
    {
        Integer sourceRadio = parseInteger(activity.sourceRadioId());
        Integer target = parseInteger(activity.targetId());
        int protocol = TrunkedIdentityPolicy.protocolFamilyCode(activity.protocol());
        Integer targetKind = TrunkedIdentityPolicy.identityKindCode(activity.targetKind());

        if(targetKind != null && (targetKind == IDENTITY_KIND_TALKGROUP ||
            targetKind == IDENTITY_KIND_PATCH_GROUP) &&
            TrunkedIdentityPolicy.isDirectoryIdentity(protocol, activity.identityDomain(), targetKind, target))
        {
            upsertP25TalkgroupBucket(connection, activity, contextId, target);

            for(Integer member: patchMemberTalkgroups(activity).stream()
                .filter(candidate -> TrunkedIdentityPolicy.isDirectoryTalkgroup(protocol,
                    activity.identityDomain(), candidate)).toList())
            {
                upsertP25TalkgroupBucket(connection, activity, contextId, member);
            }
        }

        upsertP25SiteActivityBucket(connection, activity, contextId);

        if(activity.frequencyHertz() != null && activity.frequencyHertz() > 0)
        {
            upsertP25FrequencySummary(connection, activity, contextId, sourceRadio, target);
        }
    }

    private static void upsertCallIdentityBuckets(Connection connection,
                                                  P25ActivityLogRecords.ActivityEvent activity,
                                                  int contextId) throws SQLException
    {
        long bucket = bucketStart(activity.observedAtEpochMilliseconds());
        int encrypted = activity.encrypted() ? 1 : 0;
        int protocol = TrunkedIdentityPolicy.protocolFamilyCode(activity.protocol());

        for(CallIdentity destination: destinationIdentities(activity.targetId(), activity.targetKind(),
            activity.patchMemberTalkgroupIds(), protocol, activity.identityDomain()))
        {
            upsertCallIdentityBucket(connection, contextId, bucket, IDENTITY_ROLE_DESTINATION,
                destination.kindCode(), destination.identityId(), 1, encrypted, 0, 0);
        }

        Integer source = positiveInteger(activity.sourceRadioId());

        if(source != null && (activity.contextKind() != P25ActivityLogRecords.ContextKind.TRUNKED_SITE ||
            TrunkedIdentityPolicy.isDirectoryRadio(protocol, activity.identityDomain(), source)))
        {
            upsertCallIdentityBucket(connection, contextId, bucket, IDENTITY_ROLE_SOURCE, IDENTITY_KIND_RADIO,
                source, 1, encrypted, 0, 0);
        }
    }

    private static void upsertCompletedCallOutputIdentityBuckets(
        Connection connection, P25ActivityLogRecords.CompletedCallOutput output, int contextId,
        int protocol, int recorded, int streamed) throws SQLException
    {
        long bucket = bucketStart(output.callStartEpochMilliseconds());

        for(CallIdentity destination: destinationIdentities(
            output.destinationId() > 0 ? Integer.toString(output.destinationId()) : null,
            output.targetKind(), output.patchMemberTalkgroupIds(), protocol, output.identityDomain()))
        {
            upsertCallIdentityBucket(connection, contextId, bucket, IDENTITY_ROLE_DESTINATION,
                destination.kindCode(), destination.identityId(), 0, 0, recorded, streamed);
        }

        if(output.sourceRadioId() != null && output.sourceRadioId() > 0 &&
            TrunkedIdentityPolicy.isDirectoryRadio(protocol, output.identityDomain(), output.sourceRadioId()))
        {
            upsertCallIdentityBucket(connection, contextId, bucket, IDENTITY_ROLE_SOURCE, IDENTITY_KIND_RADIO,
                output.sourceRadioId(), 0, 0, recorded, streamed);
        }
    }

    private static void upsertCallIdentityBucket(Connection connection, int contextId, long bucketStart,
                                                 int roleCode, int kindCode, int identityId, int calls,
                                                 int encrypted, int recorded, int streamed) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO call_identity_bucket (
                context_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id,
                call_count, encrypted_count, recorded_count, streamed_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(
                context_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id
            ) DO UPDATE SET
                call_count = call_identity_bucket.call_count + excluded.call_count,
                encrypted_count = call_identity_bucket.encrypted_count + excluded.encrypted_count,
                recorded_count = call_identity_bucket.recorded_count + excluded.recorded_count,
                streamed_count = call_identity_bucket.streamed_count + excluded.streamed_count
            """))
        {
            statement.setInt(1, contextId);
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
                                                             P25ActivityLogRecords.IdentityDomain identityDomain)
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

    private static long insertP25ActivityEvent(Connection connection, P25ActivityLogRecords.ActivityEvent activity,
                                               int contextId) throws SQLException
    {
        Lcn lcn = Lcn.parse(activity.lcn());
        Long activityId = null;

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
                                                            P25ActivityLogRecords.ActivityEvent activity)
        throws SQLException
    {
        int protocol = TrunkedIdentityPolicy.protocolFamilyCode(activity.protocol());
        insertActivityEventTalkgroupMembers(connection, activityId, activity.targetKind(),
            activity.patchMemberTalkgroupIds(), protocol, activity.identityDomain());
    }

    private static void insertActivityEventTalkgroupMembers(Connection connection, long activityId,
                                                            String targetKind, List<Integer> patchMemberTalkgroupIds,
                                                            int protocol,
                                                            P25ActivityLogRecords.IdentityDomain identityDomain)
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
        long frequencyHertz = activity.frequencyHertz() != null && activity.frequencyHertz() > 0 ?
            activity.frequencyHertz() : 0;
        int timeslot = summaryTimeslot(activity.timeslot());

        // A decoder event can identify its configured conventional context before it can project a frequency.
        // Keep that event out of the lifetime per-frequency directory, but count it in the compact hourly bucket so
        // dashboard action totals stay complete. Frequency zero is reserved for this aggregate-only fallback.
        if(frequencyHertz > 0)
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO conventional_activity_summary (
                    context_id, frequency_hz, timeslot, first_seen_ms, last_seen_ms, %s, encrypted_count,
                    last_event_type_code
                ) VALUES (?, ?, ?, ?, ?, %s, ?, ?)
                ON CONFLICT(context_id, frequency_hz, timeslot) DO UPDATE SET
                    last_seen_ms = max(conventional_activity_summary.last_seen_ms, excluded.last_seen_ms),
                    %s,
                    encrypted_count = conventional_activity_summary.encrypted_count + excluded.encrypted_count,
                    last_event_type_code = coalesce(excluded.last_event_type_code, conventional_activity_summary.last_event_type_code)
                """.formatted(ACTION_INSERT_COLUMNS, ACTION_INSERT_PLACEHOLDERS,
                actionUpdateSql("conventional_activity_summary"))))
            {
                int index = 1;
                statement.setInt(index++, contextId);
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
                context_id, frequency_hz, timeslot, bucket_start_ms, %s, encrypted_count
            ) VALUES (?, ?, ?, ?, %s, ?)
            ON CONFLICT(context_id, frequency_hz, timeslot, bucket_start_ms) DO UPDATE SET
                %s,
                encrypted_count = conventional_activity_bucket.encrypted_count + excluded.encrypted_count
            """.formatted(ACTION_INSERT_COLUMNS, ACTION_INSERT_PLACEHOLDERS,
            actionUpdateSql("conventional_activity_bucket"))))
        {
            int index = 1;
            statement.setInt(index++, contextId);
            statement.setLong(index++, frequencyHertz);
            statement.setInt(index++, timeslot);
            statement.setLong(index++, bucketStart(activity.observedAtEpochMilliseconds()));
            index = setActionCounts(statement, index, activity);
            statement.setInt(index, activity.encrypted() ? 1 : 0);
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

    private static int upsertReceiverContext(Connection connection, ReceiverContextMetadata metadata)
        throws SQLException
    {
        boolean trunkedSite = metadata.contextKind() == P25ActivityLogRecords.ContextKind.TRUNKED_SITE;
        ReceiverContextState previous = receiverContextState(connection, metadata.contextKey());
        boolean authoritative = previous == null ||
            metadata.lastSeenEpochMilliseconds() >= previous.lastSeenEpochMilliseconds();

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO receiver_context (
                context_key, guid, kind_code, protocol_code, channel_name, alias_list_name, decoder,
                first_seen_ms, last_seen_ms, system_key, nac, rfss, site, primary_frequency_hz, current_control_hz
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(context_key) DO UPDATE SET
                first_seen_ms = CASE
                    WHEN receiver_context.kind_code != excluded.kind_code
                      OR coalesce(receiver_context.protocol_code, 0) != coalesce(excluded.protocol_code, 0)
                      OR (receiver_context.system_key IS NOT NULL
                          AND excluded.system_key IS NOT NULL
                          AND receiver_context.system_key != excluded.system_key)
                    THEN excluded.first_seen_ms
                    ELSE min(receiver_context.first_seen_ms, excluded.first_seen_ms)
                END,
                guid = coalesce(excluded.guid, receiver_context.guid),
                kind_code = excluded.kind_code,
                protocol_code = coalesce(excluded.protocol_code, receiver_context.protocol_code),
                channel_name = coalesce(excluded.channel_name, receiver_context.channel_name),
                alias_list_name = CASE WHEN ? THEN excluded.alias_list_name
                    ELSE receiver_context.alias_list_name END,
                decoder = coalesce(excluded.decoder, receiver_context.decoder),
                last_seen_ms = max(receiver_context.last_seen_ms, excluded.last_seen_ms),
                system_key = CASE WHEN excluded.kind_code != 1 OR excluded.protocol_code NOT IN (1, 2) THEN NULL
                    ELSE coalesce(excluded.system_key, receiver_context.system_key) END,
                nac = CASE WHEN excluded.protocol_code NOT IN (1, 2) THEN NULL
                    WHEN excluded.kind_code != 1 THEN excluded.nac
                    ELSE coalesce(excluded.nac, receiver_context.nac) END,
                rfss = CASE WHEN excluded.protocol_code NOT IN (1, 2) THEN NULL
                    WHEN excluded.kind_code != 1 THEN excluded.rfss
                    ELSE coalesce(excluded.rfss, receiver_context.rfss) END,
                site = CASE WHEN excluded.protocol_code NOT IN (1, 2) THEN NULL
                    WHEN excluded.kind_code != 1 THEN excluded.site
                    ELSE coalesce(excluded.site, receiver_context.site) END,
                primary_frequency_hz = coalesce(
                    excluded.primary_frequency_hz, receiver_context.primary_frequency_hz),
                current_control_hz = CASE WHEN excluded.kind_code != 1
                    THEN NULL ELSE coalesce(excluded.current_control_hz, receiver_context.current_control_hz) END
            WHERE excluded.last_seen_ms >= receiver_context.last_seen_ms
            """))
        {
            statement.setString(1, metadata.contextKey());
            statement.setString(2, metadata.guid());
            statement.setInt(3, contextKindCode(metadata.contextKind()));
            setInteger(statement, 4, protocolCode(metadata.protocol()));
            statement.setString(5, metadata.channelName());
            statement.setString(6, metadata.aliasListName());
            statement.setString(7, metadata.decoder());
            statement.setLong(8, metadata.firstSeenEpochMilliseconds());
            statement.setLong(9, metadata.lastSeenEpochMilliseconds());
            setInteger(statement, 10, metadata.systemKey());
            setInteger(statement, 11, metadata.nac());
            setInteger(statement, 12, metadata.rfss());
            setInteger(statement, 13, metadata.site());
            setLong(statement, 14, metadata.primaryFrequencyHertz());
            setLong(statement, 15, metadata.currentControlHertz());
            statement.setBoolean(16, metadata.configuredMetadataObserved());
            statement.executeUpdate();
        }

        int contextId = selectContextId(connection, metadata.contextKey());

        if(authoritative && previous != null && previous.kindCode() == CONTEXT_TRUNKED_SITE &&
            !trunkedSite)
        {
            clearFormerTrunkedOwnership(connection, contextId,
                metadata.guid() != null ? metadata.guid() : previous.guid());
        }

        return contextId;
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
                first_seen_ms = CASE
                    WHEN coalesce(excluded.system_key, -1) != coalesce(p25_site_snapshot.system_key, -1)
                    THEN excluded.first_seen_ms
                    ELSE p25_site_snapshot.first_seen_ms
                END,
                last_seen_ms = excluded.last_seen_ms,
                observation_count = CASE
                    WHEN coalesce(excluded.system_key, -1) != coalesce(p25_site_snapshot.system_key, -1)
                    THEN 1
                    ELSE p25_site_snapshot.observation_count + 1
                END,
                protocol = coalesce(excluded.protocol, p25_site_snapshot.protocol),
                channel_name = coalesce(excluded.channel_name, p25_site_snapshot.channel_name),
                alias_list_name = excluded.alias_list_name,
                decoder = coalesce(excluded.decoder, p25_site_snapshot.decoder),
                system_key = coalesce(excluded.system_key, p25_site_snapshot.system_key),
                nac = excluded.nac,
                rfss = excluded.rfss,
                site = excluded.site,
                lra = coalesce(excluded.lra, p25_site_snapshot.lra),
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
    static void upsertGrantedChannelSummary(Connection connection,
                                            P25ActivityLogRecords.ChannelFact fact) throws SQLException
    {
        Lcn lcn = Lcn.parse(fact.lcn());
        ChannelTag serviceTag = fact.serviceTag();

        if(fact.guid() == null || fact.guid().isBlank() || fact.frequencyHertz() <= 0 ||
            lcn.band() == null || lcn.number() == null || serviceTag == null)
        {
            return;
        }

        if(!isCurrentP25SiteGeneration(connection, fact))
        {
            return;
        }

        String channelKey = lcn.channelKey();
        boolean tdma = fact.tdma();

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
            statement.setString(1, fact.guid());
            statement.setString(2, channelKey);
            statement.setString(3, channelKey);
            statement.setLong(4, fact.frequencyHertz());
            statement.setInt(5, tdma ? 1 : 0);
            statement.setInt(6, Math.max(1, fact.timeslots()));
            statement.setLong(7, fact.observedAtEpochMilliseconds());
            statement.setLong(8, fact.observedAtEpochMilliseconds());
            statement.executeUpdate();
        }

        upsertChannelTagSummary(connection, fact.guid(), channelKey, serviceTag,
            fact.observedAtEpochMilliseconds(), 1);
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

    private static SiteSnapshotState siteSnapshotState(Connection connection, String guid)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT snapshot_hash, last_seen_ms, system_key FROM p25_site_snapshot WHERE guid = ?"))
        {
            statement.setString(1, guid);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ?
                    new SiteSnapshotState(resultSet.getString(1), resultSet.getLong(2),
                        nullableInteger(resultSet, "system_key")) : null;
            }
        }
    }

    private static boolean isCurrentP25SiteGeneration(Connection connection,
                                                       P25ActivityLogRecords.ChannelFact fact)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1
            FROM receiver_context context
            WHERE context.guid=?
              AND context.kind_code=?
              AND context.protocol_code IN (?,?)
              AND ? >= context.first_seen_ms
            LIMIT 1
            """))
        {
            statement.setString(1, fact.guid());
            statement.setInt(2, CONTEXT_TRUNKED_SITE);
            statement.setInt(3, PROTOCOL_APCO25);
            statement.setInt(4, PROTOCOL_APCO25_PHASE2);
            statement.setLong(5, fact.observedAtEpochMilliseconds());

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
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

    private static List<Integer> selectContextIdsByGuid(Connection connection, String guid) throws SQLException
    {
        List<Integer> contextIds = new ArrayList<>();

        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT id FROM receiver_context WHERE guid = ?"))
        {
            statement.setString(1, guid);

            try(ResultSet resultSet = statement.executeQuery())
            {
                while(resultSet.next())
                {
                    contextIds.add(resultSet.getInt(1));
                }
            }
        }

        return contextIds;
    }

    private static ReceiverContextState receiverContextState(Connection connection, String contextKey)
        throws SQLException
    {
        if(contextKey == null || contextKey.isBlank())
        {
            return null;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT guid, kind_code, protocol_code, system_key, first_seen_ms, last_seen_ms
            FROM receiver_context
            WHERE context_key = ?
            """))
        {
            statement.setString(1, contextKey);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ?
                    new ReceiverContextState(resultSet.getString("guid"), resultSet.getInt("kind_code"),
                        resultSet.getInt("protocol_code"), nullableInteger(resultSet, "system_key"),
                        resultSet.getLong("first_seen_ms"), resultSet.getLong("last_seen_ms")) : null;
            }
        }
    }

    /**
     * A receiver GUID can be reassigned from a trunked channel to a conventional channel. Once that newer
     * conventional observation wins, remove the old receiver-owned site projection before recording conventional
     * facts so those facts cannot be displayed under the new channel classification.
     */
    private static void clearFormerTrunkedOwnership(Connection connection, int contextId, String guid)
        throws SQLException
    {
        TrunkedIdentitySchema.clearContext(connection, contextId);

        for(String table: List.of("p25_activity_event", "p25_site_talkgroup_bucket",
            "p25_site_activity_bucket", "call_identity_bucket", "p25_site_frequency_summary"))
        {
            try(PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE context_id = ?"))
            {
                statement.setInt(1, contextId);
                statement.executeUpdate();
            }
        }

        if(guid == null || guid.isBlank())
        {
            return;
        }

        clearP25SiteProjection(connection, guid);
        deleteByGuid(connection, "p25_control_channel_quality", guid);

        for(String table: List.of("trunked_site_channel_summary", "trunked_site_neighbor_summary",
            "trunked_site_snapshot"))
        {
            deleteByGuid(connection, table, guid);
        }
    }

    private static int clearP25SiteProjection(Connection connection, String guid) throws SQLException
    {
        int deleted = 0;

        for(String table: List.of("p25_site_patch_group_radio_summary",
            "p25_site_patch_group_talkgroup_summary", "p25_site_patch_group_summary",
            "p25_site_neighbor_summary", "p25_site_frequency_band_summary",
            "p25_foreign_system_band_summary", "p25_site_channel_tag_summary",
            "p25_site_channel_summary", "p25_site_patch_group_radio",
            "p25_site_patch_group_talkgroup", "p25_site_patch_group", "p25_site_neighbor",
            "p25_site_frequency_band", "p25_foreign_system_band", "p25_site_channel_tag",
            "p25_site_channel", "p25_site_snapshot"))
        {
            deleted += deleteByGuid(connection, table, guid);
        }

        return deleted;
    }

    private static ReceiverContextIdentity selectContextIdentity(
        Connection connection, P25ActivityLogRecords.CompletedCallOutput output)
        throws SQLException
    {
        return selectContextIdentity(connection, output.contextKey(), output.guid());
    }

    private static ReceiverContextIdentity selectContextIdentity(Connection connection, String contextKey,
                                                                  String guid)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT context.id, context.system_key, context.kind_code, context.protocol_code,
                   context.primary_frequency_hz, context.first_seen_ms, system.wacn, system.system_id
            FROM receiver_context context
            LEFT JOIN p25_system system ON system.system_key=context.system_key
            WHERE (? IS NOT NULL AND context.context_key = ?)
               OR (? IS NOT NULL AND context.guid = ?)
            ORDER BY CASE WHEN context.context_key = ? THEN 0 ELSE 1 END, context.last_seen_ms DESC
            LIMIT 1
            """))
        {
            statement.setString(1, contextKey);
            statement.setString(2, contextKey);
            statement.setString(3, guid);
            statement.setString(4, guid);
            statement.setString(5, contextKey);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    int systemKey = resultSet.getInt("system_key");
                    Integer nullableSystemKey = resultSet.wasNull() ? null : systemKey;
                    int protocolCode = resultSet.getInt("protocol_code");
                    Integer nullableProtocolCode = resultSet.wasNull() ? null : protocolCode;
                    long primaryFrequency = resultSet.getLong("primary_frequency_hz");
                    Long nullablePrimaryFrequency = resultSet.wasNull() ? null : primaryFrequency;
                    return new ReceiverContextIdentity(resultSet.getInt("id"),
                        nullableSystemKey, resultSet.getInt("kind_code"), nullableProtocolCode != null ?
                            nullableProtocolCode : PROTOCOL_UNKNOWN,
                        nullablePrimaryFrequency, resultSet.getLong("first_seen_ms"),
                        nullableInteger(resultSet, "wacn"), nullableInteger(resultSet, "system_id"));
                }

                return null;
            }
        }
    }

    private static boolean matchesContext(ReceiverContextIdentity context, int kindCode, int protocolFamilyCode)
    {
        return context != null && context.kindCode() == kindCode &&
            TrunkedIdentityPolicy.protocolFamilyCode(context.protocolCode()) == protocolFamilyCode;
    }

    private static boolean matchesEstablishedP25Generation(
        ReceiverContextIdentity context, P25ActivityLogRecords.ActivityEvent activity)
    {
        if(context == null || context.systemKey() == null)
        {
            return true;
        }

        if((activity.wacn() != null && !activity.wacn().equals(context.wacn())) ||
            (activity.systemId() != null && !activity.systemId().equals(context.systemId())))
        {
            return false;
        }

        boolean exactIdentity = activity.wacn() != null && activity.wacn().equals(context.wacn()) &&
            activity.systemId() != null && activity.systemId().equals(context.systemId());
        return exactIdentity ||
            activity.observedAtEpochMilliseconds() >= context.firstSeenEpochMilliseconds();
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

    private static boolean isConventional(P25ActivityLogRecords.ContextKind contextKind)
    {
        return contextKind == P25ActivityLogRecords.ContextKind.CONVENTIONAL_P25 ||
            contextKind == P25ActivityLogRecords.ContextKind.CONVENTIONAL_DMR ||
            contextKind == P25ActivityLogRecords.ContextKind.CONVENTIONAL_NXDN ||
            contextKind == P25ActivityLogRecords.ContextKind.CONVENTIONAL_ANALOG;
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

    private static List<Integer> patchMemberTalkgroups(P25ActivityLogRecords.ActivityEvent activity)
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

        if(contextKind == P25ActivityLogRecords.ContextKind.CONVENTIONAL_DMR)
        {
            return CONTEXT_CONVENTIONAL_DMR;
        }

        if(contextKind == P25ActivityLogRecords.ContextKind.CONVENTIONAL_NXDN)
        {
            return CONTEXT_CONVENTIONAL_NXDN;
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
                    List<String> conflicts = existing.conflictsWith(incoming);
                    merged.put(key, existing.merge(incoming));

                    if(!conflicts.isEmpty())
                    {
                        warnSiteChannelConflict(snapshot.guid(), key, conflicts);
                    }
                }
            }
        }

        return merged;
    }

    private record ReceiverContextIdentity(int contextId, Integer systemKey, int kindCode, int protocolCode,
                                           Long primaryFrequencyHertz, long firstSeenEpochMilliseconds,
                                           Integer wacn, Integer systemId)
    {
    }

    private record ReceiverContextState(String guid, int kindCode, int protocolCode, Integer systemKey,
                                        long firstSeenEpochMilliseconds, long lastSeenEpochMilliseconds)
    {
    }

    private record SiteSnapshotState(String snapshotHash, long lastSeenEpochMilliseconds, Integer systemKey)
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
        org.slf4j.LoggerFactory.getLogger(P25ActivityLogSchema.class);
    private static final java.util.concurrent.ConcurrentMap<String,Long> mSiteChannelConflictWarnings =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static void warnSiteChannelConflict(String guid, String key, List<String> conflicts)
    {
        String warningKey = guid + ':' + key;
        long now = System.currentTimeMillis();
        Long previous = mSiteChannelConflictWarnings.get(warningKey);

        if(previous == null || now - previous >= 300_000L)
        {
            mSiteChannelConflictWarnings.put(warningKey, now);
            mLog.warn("Merging conflicting P25 site channel [{}] for site [{}]: {}",
                key, guid, String.join(", ", conflicts));
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
            CONTEXT_CONVENTIONAL_P25 + " THEN 'CONVENTIONAL_P25' WHEN " + CONTEXT_CONVENTIONAL_DMR +
            " THEN 'CONVENTIONAL_DMR' WHEN " + CONTEXT_CONVENTIONAL_NXDN +
            " THEN 'CONVENTIONAL_NXDN' WHEN " + CONTEXT_CONVENTIONAL_ANALOG +
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

    private record TalkgroupTarget(int talkgroupId, String targetKind)
    {
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
