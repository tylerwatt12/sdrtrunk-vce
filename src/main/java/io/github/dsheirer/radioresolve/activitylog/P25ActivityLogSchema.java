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
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * SQLite schema and writes for P25 activity history.
 */
public class P25ActivityLogSchema
{
    private static final int SCHEMA_VERSION = 9;
    private static final String SCHEMA_VERSION_KEY = "p25_activity_schema_version";
    private static final int NULL_TIMESLOT = -1;

    private P25ActivityLogSchema()
    {
    }

    public static void create(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS radio_context (
                    guid TEXT PRIMARY KEY,
                    kind TEXT NOT NULL,
                    protocol TEXT,
                    channel_name TEXT,
                    alias_list_name TEXT,
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
                CREATE TABLE IF NOT EXISTS activity_event (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    guid TEXT NOT NULL,
                    channel_kind TEXT NOT NULL,
                    observed_at_ms INTEGER NOT NULL,
                    protocol TEXT,
                    action TEXT NOT NULL,
                    event_type TEXT,
                    source_radio_id TEXT,
                    target_id TEXT,
                    target_kind TEXT,
                    frequency_hz INTEGER,
                    lcn TEXT,
                    timeslot INTEGER,
                    encrypted INTEGER NOT NULL DEFAULT 0,
                    encryption_algorithm_id INTEGER,
                    encryption_key_id INTEGER,
                    talker_alias TEXT
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS talkgroup_summary (
                    guid TEXT NOT NULL,
                    talkgroup_id TEXT NOT NULL,
                    target_kind TEXT,
                    first_seen_ms INTEGER NOT NULL,
                    last_seen_ms INTEGER NOT NULL,
                    hits INTEGER NOT NULL DEFAULT 0,
                    grant_count INTEGER NOT NULL DEFAULT 0,
                    continue_count INTEGER NOT NULL DEFAULT 0,
                    encrypted_count INTEGER NOT NULL DEFAULT 0,
                    denial_count INTEGER NOT NULL DEFAULT 0,
                    busy_count INTEGER NOT NULL DEFAULT 0,
                    queued_count INTEGER NOT NULL DEFAULT 0,
                    patch_count INTEGER NOT NULL DEFAULT 0,
                    last_source_radio_id TEXT,
                    last_encryption_algorithm_id INTEGER,
                    last_encryption_key_id INTEGER,
                    PRIMARY KEY(guid, talkgroup_id)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS radio_user_summary (
                    guid TEXT NOT NULL,
                    radio_id TEXT NOT NULL,
                    first_seen_ms INTEGER NOT NULL,
                    last_seen_ms INTEGER NOT NULL,
                    hits INTEGER NOT NULL DEFAULT 0,
                    join_count INTEGER NOT NULL DEFAULT 0,
                    logout_count INTEGER NOT NULL DEFAULT 0,
                    register_count INTEGER NOT NULL DEFAULT 0,
                    grant_count INTEGER NOT NULL DEFAULT 0,
                    encrypted_count INTEGER NOT NULL DEFAULT 0,
                    last_talkgroup_id TEXT,
                    last_encryption_algorithm_id INTEGER,
                    last_encryption_key_id INTEGER,
                    talker_alias TEXT,
                    PRIMARY KEY(guid, radio_id)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS frequency_summary (
                    guid TEXT NOT NULL,
                    frequency_hz INTEGER NOT NULL,
                    timeslot INTEGER NOT NULL DEFAULT -1,
                    lcn TEXT,
                    first_seen_ms INTEGER NOT NULL,
                    last_seen_ms INTEGER NOT NULL,
                    hits INTEGER NOT NULL DEFAULT 0,
                    grant_count INTEGER NOT NULL DEFAULT 0,
                    continue_count INTEGER NOT NULL DEFAULT 0,
                    data_count INTEGER NOT NULL DEFAULT 0,
                    encrypted_count INTEGER NOT NULL DEFAULT 0,
                    last_target_id TEXT,
                    last_source_radio_id TEXT,
                    PRIMARY KEY(guid, frequency_hz, timeslot)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS site_snapshot (
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
                CREATE TABLE IF NOT EXISTS site_channel (
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
                CREATE TABLE IF NOT EXISTS site_frequency_band (
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
                CREATE TABLE IF NOT EXISTS site_neighbor (
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
                CREATE TABLE IF NOT EXISTS site_patch_group (
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
                CREATE TABLE IF NOT EXISTS site_patch_group_talkgroup (
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
                CREATE TABLE IF NOT EXISTS site_patch_group_radio (
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
                CREATE TABLE IF NOT EXISTS site_talker_alias (
                    guid TEXT NOT NULL,
                    radio_id INTEGER NOT NULL,
                    alias TEXT,
                    first_seen_ms INTEGER NOT NULL,
                    last_seen_ms INTEGER NOT NULL,
                    seen_count INTEGER NOT NULL DEFAULT 1,
                    PRIMARY KEY(guid, radio_id)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS logger_status (
                    key TEXT PRIMARY KEY,
                    value TEXT,
                    updated_at_ms INTEGER NOT NULL
                )
                """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_activity_event_guid_time ON activity_event(guid, observed_at_ms)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_activity_event_target_time ON activity_event(target_id, observed_at_ms)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_activity_event_source_time ON activity_event(source_radio_id, observed_at_ms)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_activity_event_frequency_time ON activity_event(frequency_hz, observed_at_ms)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_activity_event_encryption ON activity_event(encrypted, encryption_algorithm_id, encryption_key_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_site_snapshot_identity ON site_snapshot(wacn, system_id, rfss, site)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_site_channel_guid_frequency ON site_channel(guid, downlink_hz)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_site_neighbor_guid_site ON site_neighbor(guid, system_id, rfss, site)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_site_patch_talkgroup ON site_patch_group_talkgroup(talkgroup_id, guid)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_site_patch_radio ON site_patch_group_radio(radio_id, guid)");
            statement.executeUpdate("""
                CREATE VIEW IF NOT EXISTS activity_event_resolved AS
                SELECT
                    a.*,
                    rc.kind AS resolved_context_kind,
                    rc.channel_name AS resolved_channel_name,
                    rc.alias_list_name AS resolved_alias_list_name,
                    rc.wacn AS resolved_wacn,
                    rc.system_id AS resolved_system_id,
                    rc.nac AS resolved_nac,
                    rc.rfss AS resolved_rfss,
                    rc.site AS resolved_site,
                    rc.current_control_hz AS resolved_current_control_hz
                FROM activity_event a
                LEFT JOIN radio_context rc ON rc.guid = a.guid
                """);
        }

        SdrTrunkDatabaseStartup.setMetadata(connection, SCHEMA_VERSION_KEY, Integer.toString(SCHEMA_VERSION));
    }

    public static void validate(Connection connection) throws SQLException
    {
        SqliteSchemaValidator.validate(connection, TABLES, INDEXES, VIEWS,
            List.of(new SqliteSchemaValidator.Metadata(SCHEMA_VERSION_KEY, Integer.toString(SCHEMA_VERSION))));
    }

    private static final List<SqliteSchemaValidator.Table> TABLES = List.of(
        new SqliteSchemaValidator.Table("radio_context", "guid", "kind", "protocol", "channel_name",
            "alias_list_name", "first_seen_ms", "last_seen_ms", "wacn", "system_id", "nac", "rfss", "site",
            "primary_frequency_hz", "current_control_hz"),
        new SqliteSchemaValidator.Table("activity_event", "id", "guid", "channel_kind", "observed_at_ms",
            "protocol", "action", "event_type", "source_radio_id", "target_id", "target_kind", "frequency_hz",
            "lcn", "timeslot", "encrypted", "encryption_algorithm_id", "encryption_key_id", "talker_alias"),
        new SqliteSchemaValidator.Table("talkgroup_summary", "guid", "talkgroup_id", "target_kind",
            "first_seen_ms", "last_seen_ms", "hits", "grant_count", "continue_count", "encrypted_count",
            "denial_count", "busy_count", "queued_count", "patch_count", "last_source_radio_id",
            "last_encryption_algorithm_id", "last_encryption_key_id"),
        new SqliteSchemaValidator.Table("radio_user_summary", "guid", "radio_id", "first_seen_ms",
            "last_seen_ms", "hits", "join_count", "logout_count", "register_count", "grant_count",
            "encrypted_count", "last_talkgroup_id", "last_encryption_algorithm_id", "last_encryption_key_id",
            "talker_alias"),
        new SqliteSchemaValidator.Table("frequency_summary", "guid", "frequency_hz", "timeslot", "lcn",
            "first_seen_ms", "last_seen_ms", "hits", "grant_count", "continue_count", "data_count",
            "encrypted_count", "last_target_id", "last_source_radio_id"),
        new SqliteSchemaValidator.Table("site_snapshot", "guid", "snapshot_hash", "first_seen_ms",
            "last_seen_ms", "seen_count", "protocol", "channel_name", "alias_list_name", "decoder", "wacn",
            "system_id", "nac", "rfss", "site", "primary_frequency_hz", "current_control_hz"),
        new SqliteSchemaValidator.Table("site_channel", "guid", "channel_key", "descriptor", "role",
            "downlink_hz", "uplink_hz", "tdma", "timeslots", "first_seen_ms", "last_seen_ms", "seen_count",
            "primary_control_seen", "alternate_control_seen", "traffic_seen"),
        new SqliteSchemaValidator.Table("site_frequency_band", "guid", "band", "first_seen_ms", "last_seen_ms",
            "seen_count", "tdma", "base_hz", "bandwidth", "spacing_hz", "transmit_offset_hz", "timeslots"),
        new SqliteSchemaValidator.Table("site_neighbor", "guid", "neighbor_key", "system_id", "rfss", "site",
            "lra", "channel_descriptor", "downlink_hz", "uplink_hz", "status", "first_seen_ms", "last_seen_ms",
            "seen_count"),
        new SqliteSchemaValidator.Table("site_patch_group", "guid", "patch_group", "version", "first_seen_ms",
            "last_seen_ms", "seen_count"),
        new SqliteSchemaValidator.Table("site_patch_group_talkgroup", "guid", "patch_group", "talkgroup_id",
            "first_seen_ms", "last_seen_ms", "seen_count"),
        new SqliteSchemaValidator.Table("site_patch_group_radio", "guid", "patch_group", "radio_id",
            "first_seen_ms", "last_seen_ms", "seen_count"),
        new SqliteSchemaValidator.Table("site_talker_alias", "guid", "radio_id", "alias", "first_seen_ms",
            "last_seen_ms", "seen_count"),
        new SqliteSchemaValidator.Table("logger_status", "key", "value", "updated_at_ms")
    );

    private static final List<String> INDEXES = List.of(
        "idx_activity_event_guid_time",
        "idx_activity_event_target_time",
        "idx_activity_event_source_time",
        "idx_activity_event_frequency_time",
        "idx_activity_event_encryption",
        "idx_site_snapshot_identity",
        "idx_site_channel_guid_frequency",
        "idx_site_neighbor_guid_site",
        "idx_site_patch_talkgroup",
        "idx_site_patch_radio"
    );

    private static final List<String> VIEWS = List.of("activity_event_resolved");

    static void insertActivity(Connection connection, P25ActivityLogRecords.ActivityEvent activity) throws SQLException
    {
        insertRadioContext(connection, activity);

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO activity_event (
                guid, channel_kind, observed_at_ms, protocol, action, event_type, source_radio_id, target_id, target_kind,
                frequency_hz, lcn, timeslot, encrypted, encryption_algorithm_id, encryption_key_id, talker_alias
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """))
        {
            setActivityFields(statement, activity);
            statement.executeUpdate();
        }

        upsertTalkgroupSummary(connection, activity);
        upsertRadioUserSummary(connection, activity);
        upsertFrequencySummary(connection, activity);
    }

    static void insertSite(Connection connection, P25ActivityLogRecords.SiteSnapshot snapshot) throws SQLException
    {
        insertRadioContext(connection, snapshot);
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
        deleted += deleteByTime(connection, "activity_event", "observed_at_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "talkgroup_summary", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "radio_user_summary", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "frequency_summary", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "site_channel", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "site_frequency_band", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "site_neighbor", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "site_patch_group_talkgroup", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "site_patch_group_radio", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "site_patch_group", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "site_talker_alias", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "site_snapshot", "last_seen_ms", cutoffEpochMilliseconds);
        deleted += deleteByTime(connection, "radio_context", "last_seen_ms", cutoffEpochMilliseconds);
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

    private static void insertRadioContext(Connection connection, P25ActivityLogRecords.ActivityEvent activity)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO radio_context (
                guid, kind, protocol, channel_name, first_seen_ms, last_seen_ms, wacn, system_id, nac, rfss, site,
                primary_frequency_hz, current_control_hz
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
            ON CONFLICT(guid) DO UPDATE SET
                kind = CASE
                    WHEN radio_context.kind = 'TRUNKED_SITE' THEN radio_context.kind
                    WHEN excluded.kind = 'TRUNKED_SITE' THEN excluded.kind
                    ELSE radio_context.kind
                END,
                protocol = coalesce(excluded.protocol, radio_context.protocol),
                channel_name = coalesce(radio_context.channel_name, excluded.channel_name),
                last_seen_ms = max(radio_context.last_seen_ms, excluded.last_seen_ms),
                wacn = coalesce(excluded.wacn, radio_context.wacn),
                system_id = coalesce(excluded.system_id, radio_context.system_id),
                nac = coalesce(excluded.nac, radio_context.nac),
                rfss = coalesce(excluded.rfss, radio_context.rfss),
                site = coalesce(excluded.site, radio_context.site),
                primary_frequency_hz = coalesce(excluded.primary_frequency_hz, radio_context.primary_frequency_hz)
            """))
        {
            statement.setString(1, activity.guid());
            statement.setString(2, activity.contextKind().name());
            statement.setString(3, activity.protocol());
            statement.setString(4, activity.channelName());
            statement.setLong(5, activity.observedAtEpochMilliseconds());
            statement.setLong(6, activity.observedAtEpochMilliseconds());
            setInteger(statement, 7, activity.wacn());
            setInteger(statement, 8, activity.systemId());
            setInteger(statement, 9, activity.nac());
            setInteger(statement, 10, activity.rfss());
            setInteger(statement, 11, activity.site());
            setLong(statement, 12, activity.contextKind() == P25ActivityLogRecords.ContextKind.CONVENTIONAL_P25 ?
                activity.frequencyHertz() : null);
            statement.executeUpdate();
        }
    }

    private static void insertRadioContext(Connection connection, P25ActivityLogRecords.SiteSnapshot snapshot)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO radio_context (
                guid, kind, protocol, channel_name, alias_list_name, first_seen_ms, last_seen_ms, wacn, system_id,
                nac, rfss, site, primary_frequency_hz, current_control_hz
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(guid) DO UPDATE SET
                kind = excluded.kind,
                protocol = coalesce(excluded.protocol, radio_context.protocol),
                channel_name = coalesce(excluded.channel_name, radio_context.channel_name),
                alias_list_name = coalesce(excluded.alias_list_name, radio_context.alias_list_name),
                last_seen_ms = max(radio_context.last_seen_ms, excluded.last_seen_ms),
                wacn = coalesce(excluded.wacn, radio_context.wacn),
                system_id = coalesce(excluded.system_id, radio_context.system_id),
                nac = coalesce(excluded.nac, radio_context.nac),
                rfss = coalesce(excluded.rfss, radio_context.rfss),
                site = coalesce(excluded.site, radio_context.site),
                primary_frequency_hz = coalesce(excluded.primary_frequency_hz, radio_context.primary_frequency_hz),
                current_control_hz = coalesce(excluded.current_control_hz, radio_context.current_control_hz)
            """))
        {
            statement.setString(1, snapshot.guid());
            statement.setString(2, snapshot.contextKind().name());
            statement.setString(3, snapshot.protocol());
            statement.setString(4, snapshot.channelName());
            statement.setString(5, snapshot.aliasListName());
            statement.setLong(6, snapshot.observedAtEpochMilliseconds());
            statement.setLong(7, snapshot.observedAtEpochMilliseconds());
            setInteger(statement, 8, snapshot.wacn());
            setInteger(statement, 9, snapshot.systemId());
            setInteger(statement, 10, snapshot.nac());
            setInteger(statement, 11, snapshot.rfss());
            setInteger(statement, 12, snapshot.site());
            setLong(statement, 13, snapshot.primaryFrequencyHertz());
            setLong(statement, 14, snapshot.currentControlHertz());
            statement.executeUpdate();
        }
    }

    private static void upsertSiteSnapshot(Connection connection, P25ActivityLogRecords.SiteSnapshot snapshot)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO site_snapshot (
                guid, snapshot_hash, first_seen_ms, last_seen_ms, seen_count, protocol, channel_name,
                alias_list_name, decoder, wacn, system_id, nac, rfss, site, primary_frequency_hz,
                current_control_hz
            ) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(guid) DO UPDATE SET
                snapshot_hash = coalesce(excluded.snapshot_hash, site_snapshot.snapshot_hash),
                last_seen_ms = excluded.last_seen_ms,
                seen_count = site_snapshot.seen_count + 1,
                protocol = coalesce(excluded.protocol, site_snapshot.protocol),
                channel_name = coalesce(excluded.channel_name, site_snapshot.channel_name),
                alias_list_name = coalesce(excluded.alias_list_name, site_snapshot.alias_list_name),
                decoder = coalesce(excluded.decoder, site_snapshot.decoder),
                wacn = coalesce(excluded.wacn, site_snapshot.wacn),
                system_id = coalesce(excluded.system_id, site_snapshot.system_id),
                nac = coalesce(excluded.nac, site_snapshot.nac),
                rfss = coalesce(excluded.rfss, site_snapshot.rfss),
                site = coalesce(excluded.site, site_snapshot.site),
                primary_frequency_hz = coalesce(excluded.primary_frequency_hz, site_snapshot.primary_frequency_hz),
                current_control_hz = coalesce(excluded.current_control_hz, site_snapshot.current_control_hz)
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
                INSERT INTO site_channel (
                    guid, channel_key, descriptor, role, downlink_hz, uplink_hz, tdma, timeslots,
                    first_seen_ms, last_seen_ms, seen_count, primary_control_seen, alternate_control_seen, traffic_seen
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)
                ON CONFLICT(guid, channel_key) DO UPDATE SET
                    descriptor = coalesce(excluded.descriptor, site_channel.descriptor),
                    role = coalesce(excluded.role, site_channel.role),
                    downlink_hz = coalesce(excluded.downlink_hz, site_channel.downlink_hz),
                    uplink_hz = coalesce(excluded.uplink_hz, site_channel.uplink_hz),
                    tdma = coalesce(excluded.tdma, site_channel.tdma),
                    timeslots = coalesce(excluded.timeslots, site_channel.timeslots),
                    last_seen_ms = excluded.last_seen_ms,
                    seen_count = site_channel.seen_count + 1,
                    primary_control_seen = site_channel.primary_control_seen + excluded.primary_control_seen,
                    alternate_control_seen = site_channel.alternate_control_seen + excluded.alternate_control_seen,
                    traffic_seen = site_channel.traffic_seen + excluded.traffic_seen
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
                INSERT INTO site_frequency_band (
                    guid, band, first_seen_ms, last_seen_ms, seen_count, tdma, base_hz, bandwidth,
                    spacing_hz, transmit_offset_hz, timeslots
                ) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(guid, band) DO UPDATE SET
                    last_seen_ms = excluded.last_seen_ms,
                    seen_count = site_frequency_band.seen_count + 1,
                    tdma = coalesce(excluded.tdma, site_frequency_band.tdma),
                    base_hz = coalesce(excluded.base_hz, site_frequency_band.base_hz),
                    bandwidth = coalesce(excluded.bandwidth, site_frequency_band.bandwidth),
                    spacing_hz = coalesce(excluded.spacing_hz, site_frequency_band.spacing_hz),
                    transmit_offset_hz = coalesce(excluded.transmit_offset_hz, site_frequency_band.transmit_offset_hz),
                    timeslots = coalesce(excluded.timeslots, site_frequency_band.timeslots)
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
                INSERT INTO site_neighbor (
                    guid, neighbor_key, system_id, rfss, site, lra, channel_descriptor, downlink_hz,
                    uplink_hz, status, first_seen_ms, last_seen_ms, seen_count
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT(guid, neighbor_key) DO UPDATE SET
                    system_id = coalesce(excluded.system_id, site_neighbor.system_id),
                    rfss = coalesce(excluded.rfss, site_neighbor.rfss),
                    site = coalesce(excluded.site, site_neighbor.site),
                    lra = coalesce(excluded.lra, site_neighbor.lra),
                    channel_descriptor = coalesce(excluded.channel_descriptor, site_neighbor.channel_descriptor),
                    downlink_hz = coalesce(excluded.downlink_hz, site_neighbor.downlink_hz),
                    uplink_hz = coalesce(excluded.uplink_hz, site_neighbor.uplink_hz),
                    status = coalesce(excluded.status, site_neighbor.status),
                    last_seen_ms = excluded.last_seen_ms,
                    seen_count = site_neighbor.seen_count + 1
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
                INSERT INTO site_patch_group (
                    guid, patch_group, version, first_seen_ms, last_seen_ms, seen_count
                ) VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT(guid, patch_group) DO UPDATE SET
                    version = coalesce(excluded.version, site_patch_group.version),
                    last_seen_ms = excluded.last_seen_ms,
                    seen_count = site_patch_group.seen_count + 1
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
                INSERT INTO site_patch_group_talkgroup (
                    guid, patch_group, talkgroup_id, first_seen_ms, last_seen_ms, seen_count
                ) VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT(guid, patch_group, talkgroup_id) DO UPDATE SET
                    last_seen_ms = excluded.last_seen_ms,
                    seen_count = site_patch_group_talkgroup.seen_count + 1
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
                INSERT INTO site_patch_group_radio (
                    guid, patch_group, radio_id, first_seen_ms, last_seen_ms, seen_count
                ) VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT(guid, patch_group, radio_id) DO UPDATE SET
                    last_seen_ms = excluded.last_seen_ms,
                    seen_count = site_patch_group_radio.seen_count + 1
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

        for(P25NetworkConfigurationSnapshot.TalkerAlias talkerAlias: snapshot.talkerAliases())
        {
            if(talkerAlias == null || talkerAlias.radio() == null)
            {
                continue;
            }

            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO site_talker_alias (
                    guid, radio_id, alias, first_seen_ms, last_seen_ms, seen_count
                ) VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT(guid, radio_id) DO UPDATE SET
                    alias = coalesce(excluded.alias, site_talker_alias.alias),
                    last_seen_ms = excluded.last_seen_ms,
                    seen_count = site_talker_alias.seen_count + 1
                """))
            {
                statement.setString(1, snapshot.guid());
                statement.setInt(2, talkerAlias.radio());
                statement.setString(3, talkerAlias.alias());
                statement.setLong(4, snapshot.observedAtEpochMilliseconds());
                statement.setLong(5, snapshot.observedAtEpochMilliseconds());
                statement.executeUpdate();
            }
        }
    }

    private static void upsertTalkgroupSummary(Connection connection, P25ActivityLogRecords.ActivityEvent activity)
        throws SQLException
    {
        if(!isTalkgroup(activity.targetKind()) || activity.targetId() == null)
        {
            return;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO talkgroup_summary (
                guid, talkgroup_id, target_kind, first_seen_ms, last_seen_ms, hits, grant_count, continue_count,
                encrypted_count, denial_count, busy_count, queued_count, patch_count, last_source_radio_id,
                last_encryption_algorithm_id, last_encryption_key_id
            ) VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(guid, talkgroup_id) DO UPDATE SET
                target_kind = excluded.target_kind,
                last_seen_ms = excluded.last_seen_ms,
                hits = talkgroup_summary.hits + 1,
                grant_count = talkgroup_summary.grant_count + excluded.grant_count,
                continue_count = talkgroup_summary.continue_count + excluded.continue_count,
                encrypted_count = talkgroup_summary.encrypted_count + excluded.encrypted_count,
                denial_count = talkgroup_summary.denial_count + excluded.denial_count,
                busy_count = talkgroup_summary.busy_count + excluded.busy_count,
                queued_count = talkgroup_summary.queued_count + excluded.queued_count,
                patch_count = talkgroup_summary.patch_count + excluded.patch_count,
                last_source_radio_id = coalesce(excluded.last_source_radio_id, talkgroup_summary.last_source_radio_id),
                last_encryption_algorithm_id = coalesce(excluded.last_encryption_algorithm_id,
                    talkgroup_summary.last_encryption_algorithm_id),
                last_encryption_key_id = coalesce(excluded.last_encryption_key_id,
                    talkgroup_summary.last_encryption_key_id)
            """))
        {
            statement.setString(1, activity.guid());
            statement.setString(2, activity.targetId());
            statement.setString(3, activity.targetKind());
            statement.setLong(4, activity.observedAtEpochMilliseconds());
            statement.setLong(5, activity.observedAtEpochMilliseconds());
            statement.setInt(6, count(activity, P25ActivityLogRecords.Action.GRANT));
            statement.setInt(7, count(activity, P25ActivityLogRecords.Action.CONTINUE));
            statement.setInt(8, activity.encrypted() ? 1 : 0);
            statement.setInt(9, count(activity, P25ActivityLogRecords.Action.DENIAL));
            statement.setInt(10, count(activity, P25ActivityLogRecords.Action.BUSY));
            statement.setInt(11, count(activity, P25ActivityLogRecords.Action.QUEUED));
            statement.setInt(12, count(activity, P25ActivityLogRecords.Action.PATCH,
                P25ActivityLogRecords.Action.PATCH_CREATE, P25ActivityLogRecords.Action.PATCH_CANCEL));
            statement.setString(13, activity.sourceRadioId());
            setInteger(statement, 14, activity.encryptionAlgorithmId());
            setInteger(statement, 15, activity.encryptionKeyId());
            statement.executeUpdate();
        }
    }

    private static void upsertRadioUserSummary(Connection connection, P25ActivityLogRecords.ActivityEvent activity)
        throws SQLException
    {
        if(activity.sourceRadioId() == null)
        {
            return;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO radio_user_summary (
                guid, radio_id, first_seen_ms, last_seen_ms, hits, join_count, logout_count, register_count,
                grant_count, encrypted_count, last_talkgroup_id, last_encryption_algorithm_id,
                last_encryption_key_id, talker_alias
            ) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(guid, radio_id) DO UPDATE SET
                last_seen_ms = excluded.last_seen_ms,
                hits = radio_user_summary.hits + 1,
                join_count = radio_user_summary.join_count + excluded.join_count,
                logout_count = radio_user_summary.logout_count + excluded.logout_count,
                register_count = radio_user_summary.register_count + excluded.register_count,
                grant_count = radio_user_summary.grant_count + excluded.grant_count,
                encrypted_count = radio_user_summary.encrypted_count + excluded.encrypted_count,
                last_talkgroup_id = coalesce(excluded.last_talkgroup_id, radio_user_summary.last_talkgroup_id),
                last_encryption_algorithm_id = coalesce(excluded.last_encryption_algorithm_id,
                    radio_user_summary.last_encryption_algorithm_id),
                last_encryption_key_id = coalesce(excluded.last_encryption_key_id,
                    radio_user_summary.last_encryption_key_id),
                talker_alias = coalesce(excluded.talker_alias, radio_user_summary.talker_alias)
            """))
        {
            statement.setString(1, activity.guid());
            statement.setString(2, activity.sourceRadioId());
            statement.setLong(3, activity.observedAtEpochMilliseconds());
            statement.setLong(4, activity.observedAtEpochMilliseconds());
            statement.setInt(5, count(activity, P25ActivityLogRecords.Action.JOIN));
            statement.setInt(6, count(activity, P25ActivityLogRecords.Action.LOGOUT));
            statement.setInt(7, count(activity, P25ActivityLogRecords.Action.REGISTER));
            statement.setInt(8, count(activity, P25ActivityLogRecords.Action.GRANT));
            statement.setInt(9, activity.encrypted() ? 1 : 0);
            statement.setString(10, isTalkgroup(activity.targetKind()) ? activity.targetId() : null);
            setInteger(statement, 11, activity.encryptionAlgorithmId());
            setInteger(statement, 12, activity.encryptionKeyId());
            statement.setString(13, activity.talkerAlias());
            statement.executeUpdate();
        }
    }

    private static void upsertFrequencySummary(Connection connection, P25ActivityLogRecords.ActivityEvent activity)
        throws SQLException
    {
        if(activity.frequencyHertz() == null)
        {
            return;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO frequency_summary (
                guid, frequency_hz, timeslot, lcn, first_seen_ms, last_seen_ms, hits, grant_count, continue_count,
                data_count, encrypted_count, last_target_id, last_source_radio_id
            ) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(guid, frequency_hz, timeslot) DO UPDATE SET
                lcn = coalesce(excluded.lcn, frequency_summary.lcn),
                last_seen_ms = excluded.last_seen_ms,
                hits = frequency_summary.hits + 1,
                grant_count = frequency_summary.grant_count + excluded.grant_count,
                continue_count = frequency_summary.continue_count + excluded.continue_count,
                data_count = frequency_summary.data_count + excluded.data_count,
                encrypted_count = frequency_summary.encrypted_count + excluded.encrypted_count,
                last_target_id = coalesce(excluded.last_target_id, frequency_summary.last_target_id),
                last_source_radio_id = coalesce(excluded.last_source_radio_id, frequency_summary.last_source_radio_id)
            """))
        {
            statement.setString(1, activity.guid());
            statement.setLong(2, activity.frequencyHertz());
            statement.setInt(3, summaryTimeslot(activity.timeslot()));
            statement.setString(4, activity.lcn());
            statement.setLong(5, activity.observedAtEpochMilliseconds());
            statement.setLong(6, activity.observedAtEpochMilliseconds());
            statement.setInt(7, count(activity, P25ActivityLogRecords.Action.GRANT));
            statement.setInt(8, count(activity, P25ActivityLogRecords.Action.CONTINUE));
            statement.setInt(9, count(activity, P25ActivityLogRecords.Action.DATA, P25ActivityLogRecords.Action.GPS));
            statement.setInt(10, activity.encrypted() ? 1 : 0);
            statement.setString(11, activity.targetId());
            statement.setString(12, activity.sourceRadioId());
            statement.executeUpdate();
        }
    }

    private static void setActivityFields(PreparedStatement statement, P25ActivityLogRecords.ActivityEvent activity)
        throws SQLException
    {
        statement.setString(1, activity.guid());
        statement.setString(2, activity.contextKind().name());
        statement.setLong(3, activity.observedAtEpochMilliseconds());
        statement.setString(4, activity.protocol());
        statement.setString(5, activity.action().name());
        statement.setString(6, activity.eventType());
        statement.setString(7, activity.sourceRadioId());
        statement.setString(8, activity.targetId());
        statement.setString(9, activity.targetKind());
        setLong(statement, 10, activity.frequencyHertz());
        statement.setString(11, activity.lcn());
        setInteger(statement, 12, activity.timeslot());
        statement.setInt(13, activity.encrypted() ? 1 : 0);
        setInteger(statement, 14, activity.encryptionAlgorithmId());
        setInteger(statement, 15, activity.encryptionKeyId());
        statement.setString(16, activity.talkerAlias());
    }

    private static int deleteByTime(Connection connection, String table, String column, long cutoffEpochMilliseconds)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE " + column + " < ?"))
        {
            statement.setLong(1, cutoffEpochMilliseconds);
            return statement.executeUpdate();
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

    private static int count(P25ActivityLogRecords.ActivityEvent activity, P25ActivityLogRecords.Action... actions)
    {
        for(P25ActivityLogRecords.Action action: actions)
        {
            if(activity.action() == action)
            {
                return 1;
            }
        }

        return 0;
    }

    private static boolean isTalkgroup(String targetKind)
    {
        return "TALKGROUP".equals(targetKind) || "PATCH_GROUP".equals(targetKind);
    }

    private static int summaryTimeslot(Integer timeslot)
    {
        return timeslot != null ? timeslot : NULL_TIMESLOT;
    }

    private static String safe(Object value)
    {
        return value != null ? value.toString() : "";
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
