/*
 * Copyright (C) 2026 Dennis Sheirer
 */
package io.github.dsheirer.stats.activity;

import io.github.dsheirer.alias.AnalogCallAttribution;
import io.github.dsheirer.stats.AliasActivitySummaryMaintenance;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;

/** Writer-only projection of frozen AM/NBFM call matches and compact assigned-number observations. */
final class AnalogAliasActivityProjection
{
    private AnalogAliasActivityProjection() {}

    static void recordCall(Connection connection, AnalogCallAttribution attribution) throws SQLException
    {
        record(connection, attribution, 1, 0, 0);
    }

    static boolean recordOutput(Connection connection, AnalogCallAttribution attribution,
                             ReceiverActivityRecords.CallOutput output) throws SQLException
    {
        return record(connection, attribution, 0, output == ReceiverActivityRecords.CallOutput.RECORDED ? 1 : 0,
            output == ReceiverActivityRecords.CallOutput.STREAMED ? 1 : 0);
    }

    private static boolean record(Connection connection, AnalogCallAttribution attribution,
                               int calls, int recorded, int streamed) throws SQLException
    {
        if(attribution == null || !attribution.hasSavedScope() || !acceptsScope(connection, attribution))
        {
            return false;
        }
        int protocol = attribution.protocol() == io.github.dsheirer.protocol.Protocol.AM ? 11 : 10;
        long updatedAt = Math.max(1, System.currentTimeMillis());
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO alias_list_analog_activity_summary(
                alias_list_id, channel_configuration_id, protocol_code, assigned_talkgroup,
                call_count, unmatched_call_count, recorded_count, streamed_count,
                first_seen_ms, last_seen_ms, updated_at_ms, alias_list_name
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,(SELECT name FROM alias_list WHERE id=?))
            ON CONFLICT(alias_list_id,channel_configuration_id,protocol_code,assigned_talkgroup) DO UPDATE SET
                call_count=call_count+excluded.call_count,
                unmatched_call_count=unmatched_call_count+excluded.unmatched_call_count,
                recorded_count=recorded_count+excluded.recorded_count,
                streamed_count=streamed_count+excluded.streamed_count,
                first_seen_ms=min(first_seen_ms,excluded.first_seen_ms),
                last_seen_ms=max(last_seen_ms,excluded.last_seen_ms),
                updated_at_ms=excluded.updated_at_ms
            """))
        {
            statement.setLong(1, attribution.aliasListId());
            statement.setString(2, attribution.channelConfigurationId());
            statement.setInt(3, protocol);
            statement.setInt(4, attribution.assignedTalkgroup());
            statement.setInt(5, calls);
            statement.setInt(6, attribution.matches().isEmpty() ? calls : 0);
            statement.setInt(7, recorded);
            statement.setInt(8, streamed);
            statement.setLong(9, attribution.startedAt());
            statement.setLong(10, attribution.startedAt());
            statement.setLong(11, updatedAt);
            statement.setLong(12, attribution.aliasListId());
            statement.executeUpdate();
        }

        // One accepted call/output credits each winning Alias once, including range aliases.
        LinkedHashSet<Long> credited = new LinkedHashSet<>();
        for(AnalogCallAttribution.Match match: attribution.matches())
        {
            if(match.aliasId() > 0 && credited.add(match.aliasId()))
            {
                creditAlias(connection, attribution, match, protocol, calls, recorded, streamed, updatedAt);
            }
        }
        return true;
    }

    private static boolean acceptsScope(Connection connection, AnalogCallAttribution attribution) throws SQLException
    {
        // Saved channel/list identities remain valid when a channel is assigned to another list. Removed scopes
        // and calls from before a statistics reset cannot be recreated by a late output notification.
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM alias_list list
            JOIN configuration_channel configuration ON configuration.configuration_id=?
            WHERE list.id=? AND list.family='NBFM'
              AND coalesce((SELECT reset_at_ms FROM receiver_channel_statistics_reset WHERE configuration_id=configuration.configuration_id),0)<=?
              AND coalesce((SELECT CAST(value AS INTEGER) FROM database_metadata WHERE key=?),0)<=?
            """))
        {
            statement.setString(1, attribution.channelConfigurationId());
            statement.setLong(2, attribution.aliasListId());
            statement.setLong(3, attribution.startedAt());
            statement.setString(4, ReceiverActivitySchema.CONVENTIONAL_CALL_OUTPUT_METRICS_STARTED_AT_KEY);
            statement.setLong(5, attribution.startedAt());
            try(ResultSet result = statement.executeQuery())
            {
                return result.next();
            }
        }
    }

    static boolean acceptsObservation(Connection connection, ReceiverActivityRecords.ActivityEvent activity)
        throws SQLException
    {
        var attribution = activity.analogCallAttribution();
        if(attribution == null)
        {
            return true;
        }
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM configuration_channel configuration WHERE configuration_id=?
                AND coalesce((SELECT reset_at_ms FROM receiver_channel_statistics_reset WHERE configuration_id=configuration.configuration_id),0)<=?
                AND coalesce((SELECT CAST(value AS INTEGER) FROM database_metadata WHERE key=?),0)<=?
            """))
        {
            statement.setString(1, attribution.channelConfigurationId());
            statement.setLong(2, attribution.startedAt());
            statement.setString(3, ReceiverActivitySchema.CONVENTIONAL_CALL_OUTPUT_METRICS_STARTED_AT_KEY);
            statement.setLong(4, attribution.startedAt());
            try(ResultSet result = statement.executeQuery())
            {
                return result.next();
            }
        }
    }

    private static void creditAlias(Connection connection, AnalogCallAttribution attribution,
                                    AnalogCallAttribution.Match match, int protocol, int calls,
                                    int recorded, int streamed, long updatedAt) throws SQLException
    {
        AliasActivitySummaryMaintenance.ensureCurrent(connection, match.aliasId(), protocol, updatedAt);
        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE alias_activity_summary SET metrics_state='observed',
                logical_call_count=coalesce(logical_call_count,0)+?,
                recorded_logical_call_count=coalesce(recorded_logical_call_count,0)+?,
                stream_submitted_logical_call_count=coalesce(stream_submitted_logical_call_count,0)+?,
                first_evidence_ms=CASE WHEN first_evidence_ms IS NULL THEN ? ELSE min(first_evidence_ms,?) END,
                last_evidence_ms=CASE WHEN last_evidence_ms IS NULL THEN ? ELSE max(last_evidence_ms,?) END,
                updated_at_ms=?
            WHERE alias_id=? AND alias_list_id=? AND coalesce(last_reset_ms,0)<=?
              AND EXISTS(SELECT 1 FROM alias configured WHERE configured.id=alias_id
                AND configured.alias_list_id=? AND configured.matcher_type=? AND configured.protocol=?
                AND ((configured.matcher_type='TALKGROUP' AND configured.value=?) OR
                     (configured.matcher_type='TALKGROUP_RANGE' AND configured.min_value=? AND configured.max_value=?)))
            """))
        {
            int parameter = 1;
            statement.setInt(parameter++, calls);
            statement.setInt(parameter++, recorded);
            statement.setInt(parameter++, streamed);
            statement.setLong(parameter++, attribution.startedAt());
            statement.setLong(parameter++, attribution.startedAt());
            statement.setLong(parameter++, attribution.startedAt());
            statement.setLong(parameter++, attribution.startedAt());
            statement.setLong(parameter++, updatedAt);
            statement.setLong(parameter++, match.aliasId());
            statement.setLong(parameter++, attribution.aliasListId());
            statement.setLong(parameter++, attribution.startedAt());
            statement.setLong(parameter++, attribution.aliasListId());
            statement.setString(parameter++, match.matcherType());
            statement.setString(parameter++, attribution.protocol().name());
            statement.setInt(parameter++, match.minimum());
            statement.setInt(parameter++, match.minimum());
            statement.setInt(parameter, match.maximum());
            statement.executeUpdate();
        }
    }
}
