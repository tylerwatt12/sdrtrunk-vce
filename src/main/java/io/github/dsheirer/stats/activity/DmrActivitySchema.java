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

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Compact, retention-bound identity summaries for conventional DMR calls.
 *
 * <p>Identity is keyed by the saved channel, RF carrier, and timeslot. This prevents the same numeric
 * talkgroup or radio on unrelated repeaters from being merged. Call observations are aggregated directly and are
 * never retained as an append-only baseline.</p>
 */
public final class DmrActivitySchema
{
    public static final int MAXIMUM_TALKGROUPS_PER_CHANNEL = 4_096;
    public static final int MAXIMUM_RADIOS_PER_CHANNEL = 32_768;
    public static final int MAXIMUM_DMR_ID = 0xFFFFFF;
    public static final String TALKGROUP_TABLE = "dmr_conventional_talkgroup_summary";
    public static final String RADIO_TABLE = "dmr_conventional_radio_summary";
    public static final String TALKGROUP_RETENTION_INDEX = "idx_dmr_conventional_talkgroup_last_seen";
    public static final String RADIO_RETENTION_INDEX = "idx_dmr_conventional_radio_last_seen";
    public static final String TALKGROUP_RECEIVER_INDEX = "idx_dmr_conventional_talkgroup_channel";
    public static final String RADIO_RECEIVER_INDEX = "idx_dmr_conventional_radio_channel";

    private static final List<SqliteSchemaValidator.Table> TABLES = List.of(
        new SqliteSchemaValidator.Table(TALKGROUP_TABLE,
            "channel_id", "frequency_hz", "timeslot", "talkgroup_id", "first_seen_ms", "last_seen_ms",
            "call_count", "encrypted_count", "last_source_radio_id"),
        new SqliteSchemaValidator.Table(RADIO_TABLE,
            "channel_id", "frequency_hz", "timeslot", "radio_id", "first_seen_ms", "last_seen_ms",
            "call_count", "source_call_count", "target_call_count", "group_call_count", "private_call_count",
            "encrypted_count", "last_talkgroup_id", "last_peer_radio_id")
    );
    private static final List<String> INDEXES = List.of(
        TALKGROUP_RETENTION_INDEX, RADIO_RETENTION_INDEX, TALKGROUP_RECEIVER_INDEX, RADIO_RECEIVER_INDEX);
    private static final List<ColumnDefinition> TALKGROUP_COLUMNS = List.of(
        column("channel_id", "INTEGER", true, null, 1),
        column("frequency_hz", "INTEGER", true, null, 2),
        column("timeslot", "INTEGER", true, null, 3),
        column("talkgroup_id", "INTEGER", true, null, 4),
        column("first_seen_ms", "INTEGER", true, null, 0),
        column("last_seen_ms", "INTEGER", true, null, 0),
        column("call_count", "INTEGER", true, "0", 0),
        column("encrypted_count", "INTEGER", true, "0", 0),
        column("last_source_radio_id", "INTEGER", false, null, 0));
    private static final List<ColumnDefinition> RADIO_COLUMNS = List.of(
        column("channel_id", "INTEGER", true, null, 1),
        column("frequency_hz", "INTEGER", true, null, 2),
        column("timeslot", "INTEGER", true, null, 3),
        column("radio_id", "INTEGER", true, null, 4),
        column("first_seen_ms", "INTEGER", true, null, 0),
        column("last_seen_ms", "INTEGER", true, null, 0),
        column("call_count", "INTEGER", true, "0", 0),
        column("source_call_count", "INTEGER", true, "0", 0),
        column("target_call_count", "INTEGER", true, "0", 0),
        column("group_call_count", "INTEGER", true, "0", 0),
        column("private_call_count", "INTEGER", true, "0", 0),
        column("encrypted_count", "INTEGER", true, "0", 0),
        column("last_talkgroup_id", "INTEGER", false, null, 0),
        column("last_peer_radio_id", "INTEGER", false, null, 0));
    private static final List<String> TALKGROUP_CHECKS = List.of(
        "check(typeof(channel_id)='integer'andchannel_id>0)",
        "check(typeof(frequency_hz)='integer'andfrequency_hz>0)",
        "check(typeof(timeslot)='integer'andtimeslotin(1,2))",
        "check(typeof(talkgroup_id)='integer'andtalkgroup_idbetween1and16777215)",
        "check(last_source_radio_idisnullor(typeof(last_source_radio_id)='integer'andlast_source_radio_idbetween1and16777215))",
        "check(typeof(first_seen_ms)='integer'andfirst_seen_ms>0)",
        "check(typeof(last_seen_ms)='integer'andlast_seen_ms>=first_seen_ms)",
        "check(typeof(call_count)='integer'andcall_count>0)",
        "check(typeof(encrypted_count)='integer'andencrypted_count>=0)",
        "check(encrypted_count<=call_count)");
    private static final List<String> RADIO_CHECKS = List.of(
        "check(typeof(channel_id)='integer'andchannel_id>0)",
        "check(typeof(frequency_hz)='integer'andfrequency_hz>0)",
        "check(typeof(timeslot)='integer'andtimeslotin(1,2))",
        "check(typeof(radio_id)='integer'andradio_idbetween1and16777215)",
        "check(last_talkgroup_idisnullor(typeof(last_talkgroup_id)='integer'andlast_talkgroup_idbetween1and16777215))",
        "check(last_peer_radio_idisnullor(typeof(last_peer_radio_id)='integer'andlast_peer_radio_idbetween1and16777215))",
        "check(typeof(first_seen_ms)='integer'andfirst_seen_ms>0)",
        "check(typeof(last_seen_ms)='integer'andlast_seen_ms>=first_seen_ms)",
        "check(typeof(call_count)='integer'andcall_count>0)",
        "check(typeof(source_call_count)='integer'andsource_call_count>=0)",
        "check(typeof(target_call_count)='integer'andtarget_call_count>=0)",
        "check(typeof(group_call_count)='integer'andgroup_call_count>=0)",
        "check(typeof(private_call_count)='integer'andprivate_call_count>=0)",
        "check(typeof(encrypted_count)='integer'andencrypted_count>=0)",
        "check(source_call_count<=call_count)",
        "check(target_call_count<=call_count)",
        "check(group_call_count+private_call_count<=call_count)",
        "check(encrypted_count<=call_count)");

    private DmrActivitySchema()
    {
    }

    /**
     * Creates the current schema. Only global first-database setup and the staged Application Migrator may call this.
     */
    public static void create(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS dmr_conventional_talkgroup_summary (
                    channel_id INTEGER NOT NULL CHECK(typeof(channel_id) = 'integer' AND channel_id > 0),
                    frequency_hz INTEGER NOT NULL,
                    timeslot INTEGER NOT NULL,
                    talkgroup_id INTEGER NOT NULL,
                    first_seen_ms INTEGER NOT NULL,
                    last_seen_ms INTEGER NOT NULL,
                    call_count INTEGER NOT NULL DEFAULT 0,
                    encrypted_count INTEGER NOT NULL DEFAULT 0,
                    last_source_radio_id INTEGER,
                    PRIMARY KEY(channel_id, frequency_hz, timeslot, talkgroup_id),
                    FOREIGN KEY(channel_id) REFERENCES receiver_channel(id) ON DELETE CASCADE,
                    CHECK(typeof(frequency_hz) = 'integer' AND frequency_hz > 0),
                    CHECK(typeof(timeslot) = 'integer' AND timeslot IN (1, 2)),
                    CHECK(typeof(talkgroup_id) = 'integer' AND talkgroup_id BETWEEN 1 AND 16777215),
                    CHECK(last_source_radio_id IS NULL OR
                        (typeof(last_source_radio_id) = 'integer' AND
                            last_source_radio_id BETWEEN 1 AND 16777215)),
                    CHECK(typeof(first_seen_ms) = 'integer' AND first_seen_ms > 0),
                    CHECK(typeof(last_seen_ms) = 'integer' AND last_seen_ms >= first_seen_ms),
                    CHECK(typeof(call_count) = 'integer' AND call_count > 0),
                    CHECK(typeof(encrypted_count) = 'integer' AND encrypted_count >= 0),
                    CHECK(encrypted_count <= call_count)
                ) WITHOUT ROWID
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS dmr_conventional_radio_summary (
                    channel_id INTEGER NOT NULL CHECK(typeof(channel_id) = 'integer' AND channel_id > 0),
                    frequency_hz INTEGER NOT NULL,
                    timeslot INTEGER NOT NULL,
                    radio_id INTEGER NOT NULL,
                    first_seen_ms INTEGER NOT NULL,
                    last_seen_ms INTEGER NOT NULL,
                    call_count INTEGER NOT NULL DEFAULT 0,
                    source_call_count INTEGER NOT NULL DEFAULT 0,
                    target_call_count INTEGER NOT NULL DEFAULT 0,
                    group_call_count INTEGER NOT NULL DEFAULT 0,
                    private_call_count INTEGER NOT NULL DEFAULT 0,
                    encrypted_count INTEGER NOT NULL DEFAULT 0,
                    last_talkgroup_id INTEGER,
                    last_peer_radio_id INTEGER,
                    PRIMARY KEY(channel_id, frequency_hz, timeslot, radio_id),
                    FOREIGN KEY(channel_id) REFERENCES receiver_channel(id) ON DELETE CASCADE,
                    CHECK(typeof(frequency_hz) = 'integer' AND frequency_hz > 0),
                    CHECK(typeof(timeslot) = 'integer' AND timeslot IN (1, 2)),
                    CHECK(typeof(radio_id) = 'integer' AND radio_id BETWEEN 1 AND 16777215),
                    CHECK(last_talkgroup_id IS NULL OR
                        (typeof(last_talkgroup_id) = 'integer' AND last_talkgroup_id BETWEEN 1 AND 16777215)),
                    CHECK(last_peer_radio_id IS NULL OR
                        (typeof(last_peer_radio_id) = 'integer' AND last_peer_radio_id BETWEEN 1 AND 16777215)),
                    CHECK(typeof(first_seen_ms) = 'integer' AND first_seen_ms > 0),
                    CHECK(typeof(last_seen_ms) = 'integer' AND last_seen_ms >= first_seen_ms),
                    CHECK(typeof(call_count) = 'integer' AND call_count > 0),
                    CHECK(typeof(source_call_count) = 'integer' AND source_call_count >= 0),
                    CHECK(typeof(target_call_count) = 'integer' AND target_call_count >= 0),
                    CHECK(typeof(group_call_count) = 'integer' AND group_call_count >= 0),
                    CHECK(typeof(private_call_count) = 'integer' AND private_call_count >= 0),
                    CHECK(typeof(encrypted_count) = 'integer' AND encrypted_count >= 0),
                    CHECK(source_call_count <= call_count),
                    CHECK(target_call_count <= call_count),
                    CHECK(group_call_count + private_call_count <= call_count),
                    CHECK(encrypted_count <= call_count)
                ) WITHOUT ROWID
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_dmr_conventional_talkgroup_last_seen
                ON dmr_conventional_talkgroup_summary(
                    last_seen_ms, channel_id, frequency_hz, timeslot, talkgroup_id)
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_dmr_conventional_radio_last_seen
                ON dmr_conventional_radio_summary(
                    last_seen_ms, channel_id, frequency_hz, timeslot, radio_id)
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_dmr_conventional_talkgroup_channel
                ON dmr_conventional_talkgroup_summary(
                    channel_id, last_seen_ms DESC, frequency_hz, timeslot, talkgroup_id)
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_dmr_conventional_radio_channel
                ON dmr_conventional_radio_summary(
                    channel_id, last_seen_ms DESC, frequency_hz, timeslot, radio_id)
                """);
        }

    }

    /**
     * Read-only validation for normal startup.
     */
    public static void validate(Connection connection) throws SQLException
    {
        SqliteSchemaValidator.validate(connection, TABLES, INDEXES, List.of(), List.of());
        validateTableDefinition(connection, TALKGROUP_TABLE, TALKGROUP_COLUMNS, TALKGROUP_CHECKS);
        validateTableDefinition(connection, RADIO_TABLE, RADIO_COLUMNS, RADIO_CHECKS);
        validatePrimaryKey(connection, TALKGROUP_TABLE,
            List.of("channel_id", "frequency_hz", "timeslot", "talkgroup_id"));
        validatePrimaryKey(connection, RADIO_TABLE,
            List.of("channel_id", "frequency_hz", "timeslot", "radio_id"));
        validateForeignKey(connection, TALKGROUP_TABLE);
        validateForeignKey(connection, RADIO_TABLE);
        validateIndex(connection, TALKGROUP_TABLE, TALKGROUP_RETENTION_INDEX,
            indexes("last_seen_ms", "channel_id", "frequency_hz", "timeslot", "talkgroup_id"));
        validateIndex(connection, RADIO_TABLE, RADIO_RETENTION_INDEX,
            indexes("last_seen_ms", "channel_id", "frequency_hz", "timeslot", "radio_id"));
        validateIndex(connection, TALKGROUP_TABLE, TALKGROUP_RECEIVER_INDEX, List.of(
            index("channel_id", false), index("last_seen_ms", true), index("frequency_hz", false),
            index("timeslot", false), index("talkgroup_id", false)));
        validateIndex(connection, RADIO_TABLE, RADIO_RECEIVER_INDEX, List.of(
            index("channel_id", false), index("last_seen_ms", true), index("frequency_hz", false),
            index("timeslot", false), index("radio_id", false)));
    }

    static void recordCompletedCall(Connection connection, int channelId,
                                    ReceiverActivityRecords.DmrConventionalCall call) throws SQLException
    {
        requireValid(channelId, call);
        long timestamp = call.callEndEpochMilliseconds();

        if(call.targetKind() == ReceiverActivityRecords.DmrTargetKind.GROUP && positive(call.talkgroupId()) != null)
        {
            if(canAdmitTalkgroup(connection, channelId, call))
            {
                upsertTalkgroup(connection, channelId, call, timestamp);
            }
        }

        Integer sourceRadio = positive(call.sourceRadioId());
        Integer targetRadio = call.targetKind() == ReceiverActivityRecords.DmrTargetKind.PRIVATE ?
            positive(call.targetRadioId()) : null;

        if(sourceRadio != null && sourceRadio.equals(targetRadio))
        {
            if(canAdmitRadio(connection, channelId, call, sourceRadio))
            {
                upsertRadio(connection, channelId, call, sourceRadio, true, true, timestamp);
            }
        }
        else
        {
            if(sourceRadio != null)
            {
                if(canAdmitRadio(connection, channelId, call, sourceRadio))
                {
                    upsertRadio(connection, channelId, call, sourceRadio, true, false, timestamp);
                }
            }

            if(targetRadio != null)
            {
                if(canAdmitRadio(connection, channelId, call, targetRadio))
                {
                    upsertRadio(connection, channelId, call, targetRadio, false, true, timestamp);
                }
            }
        }
    }

    static void validateCompletedCall(ReceiverActivityRecords.DmrConventionalCall call) throws SQLException
    {
        if(call == null || call.configurationId() == null || call.configurationId().isBlank() ||
            call.frequencyHertz() <= 0 ||
            (call.timeslot() != 1 && call.timeslot() != 2) || call.callStartEpochMilliseconds() <= 0 ||
            call.callEndEpochMilliseconds() < call.callStartEpochMilliseconds() || call.targetKind() == null ||
            invalidIdentity(call.talkgroupId()) || invalidIdentity(call.sourceRadioId()) ||
            invalidIdentity(call.targetRadioId()))
        {
            throw new SQLException("Invalid conventional DMR call observation");
        }
    }

    public static int resetStats(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            int deleted = statement.executeUpdate("DELETE FROM " + TALKGROUP_TABLE);
            return Math.addExact(deleted, statement.executeUpdate("DELETE FROM " + RADIO_TABLE));
        }
    }

    public static int clearChannelStats(Connection connection, String configurationId) throws SQLException
    {
        if(configurationId == null || configurationId.isBlank())
        {
            return 0;
        }

        int deleted = 0;

        for(String table: List.of(TALKGROUP_TABLE, RADIO_TABLE))
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM %s
                WHERE channel_id IN (SELECT id FROM receiver_channel WHERE configuration_id = ?)
                """.formatted(table)))
            {
                statement.setString(1, configurationId);
                deleted = Math.addExact(deleted, statement.executeUpdate());
            }
        }

        return deleted;
    }

    private static void upsertTalkgroup(Connection connection, int channelId,
                                        ReceiverActivityRecords.DmrConventionalCall call, long timestamp)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO dmr_conventional_talkgroup_summary (
                channel_id, frequency_hz, timeslot, talkgroup_id, first_seen_ms, last_seen_ms, call_count,
                encrypted_count, last_source_radio_id
            ) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)
            ON CONFLICT(channel_id, frequency_hz, timeslot, talkgroup_id) DO UPDATE SET
                first_seen_ms = min(dmr_conventional_talkgroup_summary.first_seen_ms, excluded.first_seen_ms),
                last_seen_ms = max(dmr_conventional_talkgroup_summary.last_seen_ms, excluded.last_seen_ms),
                call_count = dmr_conventional_talkgroup_summary.call_count + 1,
                encrypted_count = dmr_conventional_talkgroup_summary.encrypted_count + excluded.encrypted_count,
                last_source_radio_id = CASE WHEN excluded.last_seen_ms >= dmr_conventional_talkgroup_summary.last_seen_ms
                    THEN coalesce(excluded.last_source_radio_id,
                        dmr_conventional_talkgroup_summary.last_source_radio_id)
                    ELSE dmr_conventional_talkgroup_summary.last_source_radio_id END
            """))
        {
            statement.setInt(1, channelId);
            statement.setLong(2, call.frequencyHertz());
            statement.setInt(3, call.timeslot());
            statement.setInt(4, call.talkgroupId());
            statement.setLong(5, call.callStartEpochMilliseconds());
            statement.setLong(6, timestamp);
            statement.setInt(7, call.encrypted() ? 1 : 0);
            setInteger(statement, 8, positive(call.sourceRadioId()));
            statement.executeUpdate();
        }
    }

    private static void upsertRadio(Connection connection, int channelId,
                                    ReceiverActivityRecords.DmrConventionalCall call, int radioId,
                                    boolean source, boolean target, long timestamp) throws SQLException
    {
        boolean group = call.targetKind() == ReceiverActivityRecords.DmrTargetKind.GROUP;
        boolean privateCall = call.targetKind() == ReceiverActivityRecords.DmrTargetKind.PRIVATE;
        Integer lastTalkgroup = group ? positive(call.talkgroupId()) : null;
        Integer lastPeer = privateCall ? (source ? positive(call.targetRadioId()) :
            positive(call.sourceRadioId())) : null;

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO dmr_conventional_radio_summary (
                channel_id, frequency_hz, timeslot, radio_id, first_seen_ms, last_seen_ms, call_count,
                source_call_count, target_call_count, group_call_count, private_call_count, encrypted_count,
                last_talkgroup_id, last_peer_radio_id
            ) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(channel_id, frequency_hz, timeslot, radio_id) DO UPDATE SET
                first_seen_ms = min(dmr_conventional_radio_summary.first_seen_ms, excluded.first_seen_ms),
                last_seen_ms = max(dmr_conventional_radio_summary.last_seen_ms, excluded.last_seen_ms),
                call_count = dmr_conventional_radio_summary.call_count + 1,
                source_call_count = dmr_conventional_radio_summary.source_call_count +
                    excluded.source_call_count,
                target_call_count = dmr_conventional_radio_summary.target_call_count +
                    excluded.target_call_count,
                group_call_count = dmr_conventional_radio_summary.group_call_count +
                    excluded.group_call_count,
                private_call_count = dmr_conventional_radio_summary.private_call_count +
                    excluded.private_call_count,
                encrypted_count = dmr_conventional_radio_summary.encrypted_count + excluded.encrypted_count,
                last_talkgroup_id = CASE WHEN excluded.last_seen_ms >= dmr_conventional_radio_summary.last_seen_ms
                    THEN coalesce(excluded.last_talkgroup_id, dmr_conventional_radio_summary.last_talkgroup_id)
                    ELSE dmr_conventional_radio_summary.last_talkgroup_id END,
                last_peer_radio_id = CASE WHEN excluded.last_seen_ms >= dmr_conventional_radio_summary.last_seen_ms
                    THEN coalesce(excluded.last_peer_radio_id, dmr_conventional_radio_summary.last_peer_radio_id)
                    ELSE dmr_conventional_radio_summary.last_peer_radio_id END
            """))
        {
            statement.setInt(1, channelId);
            statement.setLong(2, call.frequencyHertz());
            statement.setInt(3, call.timeslot());
            statement.setInt(4, radioId);
            statement.setLong(5, call.callStartEpochMilliseconds());
            statement.setLong(6, timestamp);
            statement.setInt(7, source ? 1 : 0);
            statement.setInt(8, target ? 1 : 0);
            statement.setInt(9, group ? 1 : 0);
            statement.setInt(10, privateCall ? 1 : 0);
            statement.setInt(11, call.encrypted() ? 1 : 0);
            setInteger(statement, 12, lastTalkgroup);
            setInteger(statement, 13, lastPeer);
            statement.executeUpdate();
        }
    }

    private static boolean canAdmitTalkgroup(Connection connection, int channelId,
                                             ReceiverActivityRecords.DmrConventionalCall call) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM dmr_conventional_talkgroup_summary
            WHERE channel_id = ? AND frequency_hz = ? AND timeslot = ? AND talkgroup_id = ?
            """))
        {
            statement.setInt(1, channelId);
            statement.setLong(2, call.frequencyHertz());
            statement.setInt(3, call.timeslot());
            statement.setInt(4, call.talkgroupId());

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    return true;
                }
            }
        }

        return channelRowCountBelow(connection, TALKGROUP_TABLE, channelId, MAXIMUM_TALKGROUPS_PER_CHANNEL);
    }

    private static boolean canAdmitRadio(Connection connection, int channelId,
                                         ReceiverActivityRecords.DmrConventionalCall call, int radioId)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM dmr_conventional_radio_summary
            WHERE channel_id = ? AND frequency_hz = ? AND timeslot = ? AND radio_id = ?
            """))
        {
            statement.setInt(1, channelId);
            statement.setLong(2, call.frequencyHertz());
            statement.setInt(3, call.timeslot());
            statement.setInt(4, radioId);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    return true;
                }
            }
        }

        return channelRowCountBelow(connection, RADIO_TABLE, channelId, MAXIMUM_RADIOS_PER_CHANNEL);
    }

    private static boolean channelRowCountBelow(Connection connection, String table, int channelId, int maximumRows)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT count(*) FROM " + table + " WHERE channel_id = ?"))
        {
            statement.setInt(1, channelId);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() && resultSet.getInt(1) < maximumRows;
            }
        }
    }

    private static void requireValid(int channelId, ReceiverActivityRecords.DmrConventionalCall call)
        throws SQLException
    {
        validateCompletedCall(call);

        if(channelId <= 0)
        {
            throw new SQLException("Invalid conventional DMR receiver channel");
        }
    }

    private static void validateTableDefinition(Connection connection, String table,
                                                List<ColumnDefinition> expectedColumns,
                                                List<String> requiredChecks) throws SQLException
    {
        List<ColumnDefinition> actualColumns = new ArrayList<>();

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")"))
        {
            while(resultSet.next())
            {
                actualColumns.add(column(resultSet.getString("name"),
                    resultSet.getString("type").toUpperCase(Locale.ROOT), resultSet.getInt("notnull") != 0,
                    resultSet.getString("dflt_value"), resultSet.getInt("pk")));
            }
        }

        if(!actualColumns.equals(expectedColumns))
        {
            throw new SQLException("SQLite schema has incorrect column definition for [" + table + "]: " +
                actualColumns);
        }

        String tableSql = null;

        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name=?"))
        {
            statement.setString(1, table);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    tableSql = resultSet.getString(1);
                }
            }
        }

        String normalizedSql = tableSql != null ?
            tableSql.replaceAll("\\s+", "").toLowerCase(Locale.ROOT) : "";

        if(!normalizedSql.contains("withoutrowid"))
        {
            throw new SQLException("SQLite schema table [" + table + "] must use WITHOUT ROWID");
        }

        for(String requiredCheck: requiredChecks)
        {
            if(!normalizedSql.contains(requiredCheck))
            {
                throw new SQLException("SQLite schema table [" + table +
                    "] is missing required constraint [" + requiredCheck + "]");
            }
        }

        boolean withoutRowId = false;

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA table_list"))
        {
            while(resultSet.next())
            {
                if(table.equals(resultSet.getString("name")))
                {
                    withoutRowId = resultSet.getInt("wr") == 1;
                    break;
                }
            }
        }

        if(!withoutRowId)
        {
            throw new SQLException("SQLite schema table [" + table + "] is not a WITHOUT ROWID table");
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

    private static void validateForeignKey(Connection connection, String table) throws SQLException
    {
        boolean found = false;

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_list(" + table + ")"))
        {
            while(resultSet.next())
            {
                if("receiver_channel".equals(resultSet.getString("table")) &&
                    "channel_id".equals(resultSet.getString("from")) &&
                    "id".equals(resultSet.getString("to")) &&
                    "CASCADE".equalsIgnoreCase(resultSet.getString("on_delete")))
                {
                    found = true;
                }
            }
        }

        if(!found)
        {
            throw new SQLException("SQLite schema has incorrect receiver-channel foreign key for [" + table + "]");
        }
    }

    private static void validateIndex(Connection connection, String table, String index,
                                      List<IndexDefinition> expected)
        throws SQLException
    {
        boolean validIndexDefinition = false;

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA index_list(" + table + ")"))
        {
            while(resultSet.next())
            {
                if(index.equals(resultSet.getString("name")))
                {
                    validIndexDefinition = resultSet.getInt("unique") == 0 &&
                        "c".equals(resultSet.getString("origin")) && resultSet.getInt("partial") == 0;
                    break;
                }
            }
        }

        if(!validIndexDefinition)
        {
            throw new SQLException("SQLite schema has incorrect index definition for [" + index + "]");
        }

        List<IndexDefinition> actual = new ArrayList<>();

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA index_xinfo(" + index + ")"))
        {
            while(resultSet.next())
            {
                if(resultSet.getInt("key") == 1)
                {
                    if(!"BINARY".equalsIgnoreCase(resultSet.getString("coll")))
                    {
                        throw new SQLException("SQLite schema index [" + index +
                            "] has unsupported collation [" + resultSet.getString("coll") + "]");
                    }

                    actual.add(index(resultSet.getString("name"), resultSet.getInt("desc") != 0));
                }
            }
        }

        if(!actual.equals(expected))
        {
            throw new SQLException("SQLite schema has incorrect ordered columns for index [" + index + "]: " +
                actual);
        }
    }

    private static ColumnDefinition column(String name, String type, boolean notNull, String defaultValue,
                                           int primaryKeyPosition)
    {
        return new ColumnDefinition(name, type, notNull, defaultValue, primaryKeyPosition);
    }

    private static IndexDefinition index(String name, boolean descending)
    {
        return new IndexDefinition(name, descending);
    }

    private static List<IndexDefinition> indexes(String... names)
    {
        List<IndexDefinition> indexes = new ArrayList<>(names.length);

        for(String name: names)
        {
            indexes.add(index(name, false));
        }

        return List.copyOf(indexes);
    }

    private static Integer positive(Integer value)
    {
        return value != null && value > 0 && value <= MAXIMUM_DMR_ID ? value : null;
    }

    private static boolean invalidIdentity(Integer value)
    {
        return value != null && (value <= 0 || value > MAXIMUM_DMR_ID);
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

    private record ColumnDefinition(String name, String type, boolean notNull, String defaultValue,
                                    int primaryKeyPosition)
    {
    }

    private record IndexDefinition(String name, boolean descending)
    {
    }
}
