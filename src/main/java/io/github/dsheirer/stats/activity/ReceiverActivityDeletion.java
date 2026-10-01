/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.Channel;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.ConventionalIdentity;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.DeletionTarget;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.Frequency;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.Identity;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.IdentityKind;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.LearnedSite;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.SavedSite;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.System;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.ScopedData;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Writer-transaction DML for selected retained statistics. Configuration and aliases are never changed. */
final class ReceiverActivityDeletion
{
    private static final Pattern IDENTITY_KEY = Pattern.compile(
        "v1-([rg])-(x|[0-9a-f]{5})-(x|[0-9a-f]{3})-([1-9][0-9]*)");

    private ReceiverActivityDeletion()
    {
    }

    record Result(ReceiverActivityMaintenance.DeletionOutcome outcome, int rowsDeleted)
    {
        boolean found()
        {
            return outcome == ReceiverActivityMaintenance.DeletionOutcome.DELETED;
        }

        static Result missing()
        {
            return new Result(ReceiverActivityMaintenance.DeletionOutcome.NOT_FOUND, 0);
        }

        static Result staleSite()
        {
            return new Result(ReceiverActivityMaintenance.DeletionOutcome.STALE_SITE, 0);
        }

        static Result tooLarge()
        {
            return new Result(ReceiverActivityMaintenance.DeletionOutcome.TOO_LARGE, 0);
        }

        static Result deleted(int rows)
        {
            return new Result(ReceiverActivityMaintenance.DeletionOutcome.DELETED, rows);
        }
    }

    /** Legacy atomic operations keep a safety cap; current scoped requests use the yielding batch engine. */
    static boolean legacySelectionTooLarge(Connection connection, DeletionTarget target) throws SQLException
    {
        if(target instanceof ScopedData) return false;
        ScopedData estimate = switch(target)
        {
            case Identity identity -> new ScopedData("radio_system", identity.radioSystemKey(), null, null,
                identity.kind() == IdentityKind.RADIO ? "radios" : "talkgroups", identity.identityKey(), List.of("summary"));
            case System system -> new ScopedData("radio_system", system.radioSystemKey(), null, null,
                "all", null, List.of("current", "summary", "buckets", "events"));
            case LearnedSite site -> new ScopedData("radio_system", site.radioSystemKey(), null, null,
                "all", null, List.of("current", "summary", "buckets", "events"));
            case ConventionalIdentity identity -> new ScopedData("saved_channel", identity.configurationId(), null, null,
                identity.kind() == IdentityKind.RADIO ? "radios" : "talkgroups",
                identity.frequencyHz() + ":" + identity.timeslot() + ":" + identity.identityId(), List.of("summary"));
            case Channel channel -> legacyChannelScope(connection, channel.configurationId(), "all", null,
                List.of("current", "summary", "buckets", "events"));
            case SavedSite site -> legacyChannelScope(connection, site.configurationId(),
                site.includeChannelHistory() ? "all" : "site_state", null, site.includeChannelHistory() ?
                    List.of("current", "summary", "buckets", "events") : List.of("current"));
            case Frequency frequency -> legacyChannelScope(connection, frequency.configurationId(), "frequencies",
                Long.toString(frequency.frequencyHz()), List.of("current", "summary"));
            case ScopedData ignored -> null;
        };
        if(estimate != null)
            return ReceiverActivityScopedDeletion.Batch.start(connection, estimate, Long.MAX_VALUE).rowsTotal() > 100_000;
        if(target instanceof Channel channel) return orphanChannelTooLarge(connection, channel.configurationId());
        if(target instanceof SavedSite site && site.includeChannelHistory())
            return orphanChannelTooLarge(connection, site.configurationId());
        return false;
    }

    private static ScopedData legacyChannelScope(Connection connection, String configurationId, String type,
                                                  String recordKey, List<String> parts) throws SQLException
    {
        SavedChannel channel = savedChannel(connection, configurationId);
        if(channel == null)
        {
            try(PreparedStatement statement = connection.prepareStatement("SELECT system_key FROM radio_system WHERE configuration_id=?"))
            {
                statement.setString(1, configurationId);
                try(ResultSet rows = statement.executeQuery())
                {
                    if(rows.next()) return new ScopedData("radio_system", rows.getString(1), null, null,
                        "all", null, List.of("current", "summary", "buckets", "events"));
                }
            }
            return null;
        }
        if(type.equals("site_state") && !"TRUNKED".equals(channel.kind())) return null;
        if("CONVENTIONAL".equals(channel.kind()))
            return new ScopedData("saved_channel", configurationId, null, null, type, recordKey,
                type.equals("frequencies") ? List.of("summary", "buckets") :
                    parts.stream().filter(part -> !part.equals("current")).toList());
        String systemKey = null;
        try(PreparedStatement statement = connection.prepareStatement("SELECT system.system_key FROM radio_system system " +
            "JOIN receiver_channel channel ON channel.radio_system_id=system.id WHERE channel.id=?"))
        {
            statement.setInt(1, channel.id());
            try(ResultSet rows = statement.executeQuery()) { if(rows.next()) systemKey = rows.getString(1); }
        }
        if(systemKey == null) return null;
        return new ScopedData("radio_system", systemKey, configurationId, channel.siteKey(), type, recordKey, parts);
    }

    private static boolean orphanChannelTooLarge(Connection connection, String configurationId) throws SQLException
    {
        SavedChannel channel = savedChannel(connection, configurationId);
        if(channel == null) return false;
        List<String> tables = new ArrayList<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT name FROM sqlite_master " +
            "WHERE type='table' AND (name LIKE 'p25_%' OR name LIKE 'trunked_%' OR name LIKE 'conventional_%' " +
                "OR name LIKE 'dmr_conventional_%' OR name='receiver_activity_event')"))
        {
            while(rows.next()) tables.add(rows.getString(1));
        }
        long total = 0;
        for(String table: tables)
        {
            boolean owned = false;
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA table_info(" + table + ")"))
            {
                while(rows.next()) owned |= rows.getString("name").equals("channel_id");
            }
            if(!owned) continue;
            try(PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM (SELECT 1 FROM " + table +
                " WHERE channel_id=? LIMIT ?)"))
            {
                statement.setInt(1, channel.id());
                statement.setLong(2, 100_001 - total);
                try(ResultSet rows = statement.executeQuery()) { if(rows.next()) total += rows.getLong(1); }
            }
            if(total > 100_000) return true;
        }
        try(PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM (SELECT 1 FROM " +
            "activity_event_identity_member member JOIN receiver_activity_event event ON event.id=member.event_id " +
            "WHERE event.channel_id=? LIMIT ?)"))
        {
            statement.setInt(1, channel.id());
            statement.setLong(2, 100_001 - total);
            try(ResultSet rows = statement.executeQuery()) { if(rows.next()) total += rows.getLong(1); }
        }
        return total > 100_000;
    }

    static Result delete(Connection connection, DeletionTarget target) throws SQLException
    {
        return switch(target)
        {
            case Frequency frequency -> deleteFrequency(connection, frequency);
            case Identity identity -> deleteIdentity(connection, identity);
            case ConventionalIdentity identity -> deleteConventionalIdentity(connection, identity);
            case LearnedSite site -> deleteLearnedSite(connection, site);
            case SavedSite site -> deleteSavedSite(connection, site);
            case Channel channel -> deleteChannel(connection, channel);
            case System system -> deleteSystem(connection, system);
            case ScopedData scoped -> ReceiverActivityScopedDeletion.delete(connection, scoped);
        };
    }

    private static Result deleteFrequency(Connection connection, Frequency target) throws SQLException
    {
        SavedChannel channel = savedChannel(connection, target.configurationId());

        if(channel == null) return Result.missing();
        if(!target.expectedSiteKey().equals(channel.siteKey())) return Result.staleSite();

        int deleted = 0;

        if("TRUNKED".equals(channel.kind()) && channel.siteKey().startsWith("p25:"))
        {
            List<String> keys = new ArrayList<>();
            try(PreparedStatement statement = connection.prepareStatement("""
                SELECT summary.channel_key
                FROM p25_site_channel_summary summary
                LEFT JOIN p25_site_channel current
                    ON current.channel_id = summary.channel_id AND current.channel_key = summary.channel_key
                WHERE summary.channel_id = ? AND coalesce(current.downlink_hz, summary.downlink_hz) = ?
                """))
            {
                statement.setInt(1, channel.id());
                statement.setLong(2, target.frequencyHz());
                try(ResultSet rows = statement.executeQuery())
                {
                    while(rows.next()) keys.add(rows.getString(1));
                }
            }

            if(keys.isEmpty()) return Result.missing();

            for(String key: keys)
            {
                deleted = Math.addExact(deleted, execute(connection,
                    "DELETE FROM p25_site_channel WHERE channel_id = ? AND channel_key = ?", channel.id(), key));
                deleted = Math.addExact(deleted, execute(connection,
                    "DELETE FROM p25_site_channel_summary WHERE channel_id = ? AND channel_key = ?",
                    channel.id(), key));
            }

            execute(connection, """
                UPDATE p25_site_snapshot
                SET primary_frequency_hz = CASE WHEN primary_frequency_hz = ? THEN NULL ELSE primary_frequency_hz END,
                    current_control_hz = CASE WHEN current_control_hz = ? THEN NULL ELSE current_control_hz END
                WHERE channel_id = ? AND (primary_frequency_hz = ? OR current_control_hz = ?)
                """, target.frequencyHz(), target.frequencyHz(), channel.id(), target.frequencyHz(),
                target.frequencyHz());
        }
        else if("TRUNKED".equals(channel.kind()) &&
            (channel.siteKey().startsWith("dmr:") || channel.siteKey().startsWith("nxdn:")))
        {
            deleted = execute(connection,
                "DELETE FROM trunked_site_channel_summary WHERE channel_id = ? AND frequency_hz = ?",
                channel.id(), target.frequencyHz());
            if(deleted == 0) return Result.missing();
            execute(connection, """
                UPDATE trunked_site_snapshot
                SET primary_frequency_hz = CASE WHEN primary_frequency_hz = ? THEN NULL ELSE primary_frequency_hz END,
                    current_control_hz = CASE WHEN current_control_hz = ? THEN NULL ELSE current_control_hz END
                WHERE channel_id = ? AND (primary_frequency_hz = ? OR current_control_hz = ?)
                """, target.frequencyHz(), target.frequencyHz(), channel.id(), target.frequencyHz(),
                target.frequencyHz());
        }
        else if("CONVENTIONAL".equals(channel.kind()))
        {
            deleted = execute(connection,
                "DELETE FROM conventional_activity_summary WHERE channel_id = ? AND frequency_hz = ?",
                channel.id(), target.frequencyHz());
            deleted = Math.addExact(deleted, execute(connection,
                "DELETE FROM conventional_activity_bucket WHERE channel_id = ? AND frequency_hz = ?",
                channel.id(), target.frequencyHz()));
            deleted = Math.addExact(deleted, execute(connection,
                "DELETE FROM dmr_conventional_talkgroup_summary WHERE channel_id = ? AND frequency_hz = ?",
                channel.id(), target.frequencyHz()));
            deleted = Math.addExact(deleted, execute(connection,
                "DELETE FROM dmr_conventional_radio_summary WHERE channel_id = ? AND frequency_hz = ?",
                channel.id(), target.frequencyHz()));
        }

        return deleted > 0 ? Result.deleted(deleted) : Result.missing();
    }

    private static Result deleteIdentity(Connection connection, Identity target) throws SQLException
    {
        ParsedIdentity parsed = parseIdentity(target);
        int systemId = systemId(connection, target.radioSystemKey());
        if(systemId == 0) return Result.missing();

        int summaryId = 0;
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id FROM radio_system_identity_summary
            WHERE radio_system_id = ? AND identity_kind_code = ? AND home_wacn = ?
              AND home_system_id = ? AND identity_id = ?
            """))
        {
            statement.setInt(1, systemId);
            statement.setInt(2, parsed.kindCode());
            statement.setInt(3, parsed.homeWacn());
            statement.setInt(4, parsed.homeSystemId());
            statement.setInt(5, parsed.identityId());
            try(ResultSet rows = statement.executeQuery())
            {
                if(rows.next()) summaryId = rows.getInt(1);
            }
        }

        if(summaryId == 0) return Result.missing();
        return Result.deleted(execute(connection,
            "DELETE FROM radio_system_identity_summary WHERE id = ? AND radio_system_id = ? AND identity_kind_code = ?",
            summaryId, systemId, parsed.kindCode()));
    }

    private static Result deleteConventionalIdentity(Connection connection, ConventionalIdentity target)
        throws SQLException
    {
        SavedChannel channel = savedChannel(connection, target.configurationId());
        if(channel == null || !"CONVENTIONAL".equals(channel.kind()) || !"DMR".equals(channel.decoder()))
        {
            return Result.missing();
        }

        int deleted;
        if(target.kind() == IdentityKind.RADIO)
        {
            deleted = execute(connection, """
                DELETE FROM dmr_conventional_radio_summary
                WHERE channel_id = ? AND frequency_hz = ? AND timeslot = ? AND radio_id = ?
                """, channel.id(), target.frequencyHz(), target.timeslot(), target.identityId());
            if(deleted == 0) return Result.missing();
            execute(connection, """
                UPDATE dmr_conventional_talkgroup_summary SET last_source_radio_id = NULL
                WHERE channel_id = ? AND frequency_hz = ? AND timeslot = ? AND last_source_radio_id = ?
                """, channel.id(), target.frequencyHz(), target.timeslot(), target.identityId());
            execute(connection, """
                UPDATE dmr_conventional_radio_summary SET last_peer_radio_id = NULL
                WHERE channel_id = ? AND frequency_hz = ? AND timeslot = ? AND last_peer_radio_id = ?
                """, channel.id(), target.frequencyHz(), target.timeslot(), target.identityId());
        }
        else
        {
            deleted = execute(connection, """
                DELETE FROM dmr_conventional_talkgroup_summary
                WHERE channel_id = ? AND frequency_hz = ? AND timeslot = ? AND talkgroup_id = ?
                """, channel.id(), target.frequencyHz(), target.timeslot(), target.identityId());
            if(deleted == 0) return Result.missing();
            execute(connection, """
                UPDATE dmr_conventional_radio_summary SET last_talkgroup_id = NULL
                WHERE channel_id = ? AND frequency_hz = ? AND timeslot = ? AND last_talkgroup_id = ?
                """, channel.id(), target.frequencyHz(), target.timeslot(), target.identityId());
        }

        return Result.deleted(deleted);
    }

    static ParsedIdentity parseIdentity(Identity target)
    {
        var match = IDENTITY_KEY.matcher(target.identityKey());
        if(!match.matches() || target.kind() == IdentityKind.RADIO && !"r".equals(match.group(1)) ||
            target.kind() == IdentityKind.TALKGROUP && !"g".equals(match.group(1)) ||
            "x".equals(match.group(2)) != "x".equals(match.group(3)))
        {
            throw new IllegalArgumentException("Identity key does not match the selected kind");
        }

        try
        {
            int identityId = Integer.parseInt(match.group(4));
            if(identityId <= 0 || identityId > 16_777_215)
                throw new IllegalArgumentException("Identity ID is outside its supported range");
            return new ParsedIdentity(target.kind() == IdentityKind.RADIO ? 2 : 1,
                "x".equals(match.group(2)) ? -1 : Integer.parseInt(match.group(2), 16),
                "x".equals(match.group(3)) ? -1 : Integer.parseInt(match.group(3), 16), identityId);
        }
        catch(NumberFormatException e)
        {
            throw new IllegalArgumentException("Identity key is invalid", e);
        }
    }

    private static Result deleteLearnedSite(Connection connection, LearnedSite target) throws SQLException
    {
        int radioSystemId = systemId(connection, target.radioSystemKey());
        if(radioSystemId == 0) return Result.missing();
        int siteId = 0;
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT learned_site_id FROM p25_learned_site
            WHERE radio_system_id = ? AND rfss = ? AND site = ?
            """))
        {
            statement.setInt(1, radioSystemId);
            statement.setInt(2, target.rfss());
            statement.setInt(3, target.site());
            try(ResultSet rows = statement.executeQuery())
            {
                if(rows.next()) siteId = rows.getInt(1);
            }
        }
        if(siteId == 0) return Result.missing();

        int deleted = 0;
        if(target.includeChannelHistory())
        {
            List<String> channels = new ArrayList<>();
            try(PreparedStatement statement = connection.prepareStatement("""
                SELECT receiver.configuration_id
                FROM receiver_channel receiver
                JOIN p25_site_snapshot snapshot ON snapshot.channel_id = receiver.id
                WHERE receiver.radio_system_id = ? AND snapshot.rfss = ? AND snapshot.site = ?
                """))
            {
                statement.setInt(1, radioSystemId);
                statement.setInt(2, target.rfss());
                statement.setInt(3, target.site());
                try(ResultSet rows = statement.executeQuery())
                {
                    while(rows.next()) channels.add(rows.getString(1));
                }
            }
            for(String channel: channels) deleted = Math.addExact(deleted, clearChannel(connection, channel));
        }
        deleted = Math.addExact(deleted, execute(connection,
            "DELETE FROM p25_learned_site WHERE learned_site_id = ? AND radio_system_id = ?",
            siteId, radioSystemId));
        return Result.deleted(deleted);
    }

    private static Result deleteSavedSite(Connection connection, SavedSite target) throws SQLException
    {
        SavedChannel channel = savedChannel(connection, target.configurationId());
        if(channel == null) return Result.missing();
        if(!target.expectedSiteKey().equals(channel.siteKey())) return Result.staleSite();
        if(!"TRUNKED".equals(channel.kind())) return Result.missing();

        if(target.includeChannelHistory()) return Result.deleted(clearChannel(connection, target.configurationId()));
        int deleted = execute(connection, "DELETE FROM p25_site_snapshot WHERE channel_id = ?", channel.id());
        deleted = Math.addExact(deleted,
            execute(connection, "DELETE FROM trunked_site_snapshot WHERE channel_id = ?", channel.id()));
        return deleted > 0 ? Result.deleted(deleted) : Result.missing();
    }

    private static Result deleteChannel(Connection connection, Channel target) throws SQLException
    {
        if(savedChannel(connection, target.configurationId()) == null &&
            scalar(connection, "SELECT id FROM radio_system WHERE configuration_id = ?",
                target.configurationId()) == 0)
        {
            return Result.missing();
        }

        return Result.deleted(clearChannel(connection, target.configurationId()));
    }

    private static int clearChannel(Connection connection, String configurationId) throws SQLException
    {
        int deleted = DmrActivitySchema.clearChannelStats(connection, configurationId);
        deleted = Math.addExact(deleted, ReceiverActivitySchema.clearChannelStats(connection, configurationId));
        return Math.addExact(deleted, TrunkedSiteSchema.clearChannelStats(connection, configurationId));
    }

    private static Result deleteSystem(Connection connection, System target) throws SQLException
    {
        int radioSystemId = systemId(connection, target.radioSystemKey());
        if(radioSystemId == 0) return Result.missing();
        int deleted = 0;

        if(target.includeChannelHistory())
        {
            deleted = execute(connection, """
                DELETE FROM receiver_channel
                WHERE radio_system_id = ? OR configuration_id =
                    (SELECT configuration_id FROM radio_system WHERE id = ?)
                """, radioSystemId, radioSystemId);
        }
        else
        {
            // ON DELETE SET NULL detaches the system, but not its assignment timestamp.
            // Capture the bounded saved-channel IDs and clear their timestamps after the parent delete.
            List<Integer> channels = new ArrayList<>();
            try(PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM receiver_channel WHERE radio_system_id = ?"))
            {
                statement.setInt(1, radioSystemId);
                try(ResultSet rows = statement.executeQuery())
                {
                    while(rows.next()) channels.add(rows.getInt(1));
                }
            }
            deleted = execute(connection, "DELETE FROM radio_system WHERE id = ? AND system_key = ?",
                radioSystemId, target.radioSystemKey());
            for(Integer channelId: channels)
            {
                execute(connection, """
                    UPDATE receiver_channel SET radio_system_assigned_at_ms = NULL
                    WHERE id = ? AND radio_system_id IS NULL
                    """, channelId);
            }
            return Result.deleted(deleted);
        }

        deleted = Math.addExact(deleted, execute(connection,
            "DELETE FROM radio_system WHERE id = ? AND system_key = ?", radioSystemId, target.radioSystemKey()));
        return Result.deleted(deleted);
    }

    static SavedChannel savedChannel(Connection connection, String configurationId) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT receiver.id, receiver.radio_system_id, receiver.first_seen_ms AS channel_first_seen_ms,
                config.channel_kind, config.decoder_type,
                p25.channel_id AS p25_snapshot_id, p25.rfss, p25.site,
                p25.first_seen_ms AS p25_first_seen_ms,
                trunked.channel_id AS trunked_snapshot_id,
                trunked.protocol_code, trunked.variant_code, trunked.observed_location_category_code,
                trunked.observed_network_id, trunked.observed_system_id, trunked.observed_site_id,
                trunked.observed_ran, trunked.observed_model_code,
                trunked.first_seen_ms AS trunked_first_seen_ms
            FROM receiver_channel receiver
            JOIN configuration_channel config ON config.configuration_id = receiver.configuration_id
            LEFT JOIN p25_site_snapshot p25 ON p25.channel_id = receiver.id
            LEFT JOIN trunked_site_snapshot trunked ON trunked.channel_id = receiver.id
            WHERE receiver.configuration_id = ?
            """))
        {
            statement.setString(1, configurationId);
            try(ResultSet rows = statement.executeQuery())
            {
                if(!rows.next()) return null;
                String kind = rows.getString("channel_kind");
                int channelId = rows.getInt("id");
                Integer radioSystemId = nullableInt(rows, "radio_system_id");
                String siteKey = null;
                if("CONVENTIONAL".equals(kind))
                {
                    siteKey = RetainedSiteKey.conventional(channelId, rows.getLong("channel_first_seen_ms"));
                }
                else if(rows.getObject("p25_snapshot_id") != null)
                {
                    siteKey = RetainedSiteKey.p25(nullableInt(rows, "rfss"), nullableInt(rows, "site"),
                        channelId, radioSystemId, rows.getLong("p25_first_seen_ms"));
                }
                else if(rows.getObject("trunked_snapshot_id") != null)
                {
                    siteKey = RetainedSiteKey.trunked(rows.getInt("protocol_code"), rows.getInt("variant_code"),
                        rows.getInt("observed_location_category_code"), nullableInt(rows, "observed_network_id"),
                        nullableInt(rows, "observed_system_id"), nullableInt(rows, "observed_site_id"),
                        nullableInt(rows, "observed_ran"), nullableInt(rows, "observed_model_code"),
                        channelId, radioSystemId, rows.getLong("trunked_first_seen_ms"));
                }
                else if("TRUNKED".equals(kind))
                {
                    siteKey = RetainedSiteKey.snapshotless(channelId, radioSystemId,
                        rows.getLong("channel_first_seen_ms"));
                }
                return new SavedChannel(channelId, kind, rows.getString("decoder_type"), siteKey);
            }
        }
    }

    private static Integer nullableInt(ResultSet rows, String column) throws SQLException
    {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }

    static int systemId(Connection connection, String systemKey) throws SQLException
    {
        return scalar(connection, "SELECT id FROM radio_system WHERE system_key = ?", systemKey);
    }

    private static int scalar(Connection connection, String sql, Object... values) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            bind(statement, values);
            try(ResultSet rows = statement.executeQuery())
            {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private static int execute(Connection connection, String sql, Object... values) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            bind(statement, values);
            return statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, Object[] values) throws SQLException
    {
        for(int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
    }

    record ParsedIdentity(int kindCode, int homeWacn, int homeSystemId, int identityId)
    {
    }

    record SavedChannel(int id, String kind, String decoder, String siteKey)
    {
    }
}
