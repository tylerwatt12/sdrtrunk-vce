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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * One bounded, fair pass over retention-owned activity data.
 *
 * <p>Each task removes only a small indexed batch.  The cursor is persisted in the existing bounded status store,
 * so a busy or restarted receiver resumes at the next table instead of repeatedly favoring the first table.  Parent
 * rows are deleted only after retained descendants are gone, preventing an {@code ON DELETE CASCADE} from turning a
 * small routine pass into an unbounded delete.</p>
 */
final class ReceiverActivityRetention
{
    static final String CURSOR_STATUS_KEY = "retention_task_cursor";
    static final int MAXIMUM_ROWS_PER_PASS = 1_000;
    static final int MAXIMUM_ROWS_PER_TASK = 256;

    private static final List<RetentionTask> TASKS = tasks();

    private ReceiverActivityRetention()
    {
    }

    static Pass runPass(Connection connection, long cutoffEpochMilliseconds) throws SQLException
    {
        int cursor = Math.floorMod((int)ReceiverActivitySchema.readStatusLong(connection, CURSOR_STATUS_KEY),
            TASKS.size());
        int visited = 0;
        int deleted = 0;
        int consecutiveEmptyTasks = 0;
        long hourlyCutoff = cutoffEpochMilliseconds - Math.floorMod(cutoffEpochMilliseconds, 3_600_000L);

        while(consecutiveEmptyTasks < TASKS.size() && deleted < MAXIMUM_ROWS_PER_PASS)
        {
            RetentionTask task = TASKS.get(cursor);
            int maximumRows = Math.min(MAXIMUM_ROWS_PER_TASK, MAXIMUM_ROWS_PER_PASS - deleted);
            int taskDeleted = task.delete(connection, task.hourly() ? hourlyCutoff : cutoffEpochMilliseconds,
                maximumRows);
            deleted = Math.addExact(deleted, taskDeleted);
            consecutiveEmptyTasks = taskDeleted > 0 ? 0 : consecutiveEmptyTasks + 1;
            cursor = (cursor + 1) % TASKS.size();
            visited++;
        }

        ReceiverActivitySchema.updateStatus(connection, CURSOR_STATUS_KEY, Integer.toString(cursor));
        boolean moreWorkLikely = deleted == MAXIMUM_ROWS_PER_PASS;
        return new Pass(deleted, moreWorkLikely, visited, cursor);
    }

    static int taskCount()
    {
        return TASKS.size();
    }

    private static List<RetentionTask> tasks()
    {
        List<RetentionTask> tasks = new ArrayList<>();

        tasks.add(sql("activity event members", false, """
            DELETE FROM activity_event_identity_member
            WHERE (event_id, identity_summary_id) IN (
                SELECT member.event_id, member.identity_summary_id
                FROM receiver_activity_event event INDEXED BY idx_receiver_activity_event_retention
                JOIN activity_event_identity_member member ON member.event_id = event.id
                WHERE event.observed_at_ms < ?
                ORDER BY event.observed_at_ms, event.id, member.identity_summary_id
                LIMIT ?
            )
            """));
        tasks.add(sql("activity events", false, """
            DELETE FROM receiver_activity_event
            WHERE id IN (
                SELECT event.id
                FROM receiver_activity_event event INDEXED BY idx_receiver_activity_event_retention
                WHERE event.observed_at_ms < ?
                  AND NOT EXISTS (
                      SELECT 1 FROM activity_event_identity_member member WHERE member.event_id = event.id
                  )
                ORDER BY event.observed_at_ms, event.id
                LIMIT ?
            )
            """));
        tasks.add(tuple("trunked logical-call identities", true,
            "trunked_logical_call_identity_bucket", "idx_trunked_logical_identity_dashboard_time",
            "bucket_start_ms", "radio_system_id, bucket_start_ms, identity_role_code, identity_summary_id"));
        tasks.add(tuple("trunked logical calls", true, "trunked_logical_call_bucket",
            "idx_trunked_logical_call_bucket_time", "bucket_start_ms", "radio_system_id, bucket_start_ms"));
        tasks.add(tuple("P25 site-call identities", true, "p25_site_call_identity_bucket",
            "idx_p25_site_call_identity_retention", "bucket_start_ms",
            "radio_system_id, learned_site_id, bucket_start_ms, channel_id, identity_role_code, identity_summary_id"));
        tasks.add(tuple("P25 site calls", true, "p25_site_call_bucket", "idx_p25_site_call_bucket_time",
            "bucket_start_ms", "radio_system_id, learned_site_id, bucket_start_ms"));
        tasks.add(tuple("trunked signaling", true, "trunked_signaling_activity_bucket",
            "idx_trunked_signaling_activity_time", "bucket_start_ms",
            "channel_id, radio_system_id, bucket_start_ms"));
        tasks.add(tuple("conventional call identities", true, "conventional_call_identity_bucket",
            "idx_conventional_call_identity_dashboard_time", "bucket_start_ms",
            "channel_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id"));
        tasks.add(rowId("conventional activity", true, "conventional_activity_bucket",
            "idx_conventional_bucket_dashboard_time", "bucket_start_ms", ""));

        addP25SiteTasks(tasks, false);
        addP25SiteTasks(tasks, true);

        tasks.add(tuple("control-channel quality", false, "trunked_control_channel_quality",
            "idx_trunked_control_quality_retention", "observed_at_ms",
            "channel_id, frequency_hz, bucket_start_ms"));
        tasks.add(sql("P25 learned sites", false, """
            DELETE FROM p25_learned_site
            WHERE learned_site_id IN (
                SELECT site.learned_site_id
                FROM p25_learned_site site INDEXED BY idx_p25_learned_site_retention
                WHERE site.last_seen_ms < ?
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_site_call_bucket fact
                      WHERE fact.radio_system_id = site.radio_system_id
                        AND fact.learned_site_id = site.learned_site_id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_site_call_identity_bucket fact
                      WHERE fact.radio_system_id = site.radio_system_id
                        AND fact.learned_site_id = site.learned_site_id
                  )
                ORDER BY site.last_seen_ms, site.radio_system_id, site.learned_site_id
                LIMIT ?
            )
            """));

        tasks.add(tuple("radio affiliations", false, "trunked_radio_affiliation",
            "idx_trunked_radio_affiliation_retention", "confirmed_at_ms",
            "radio_system_id, radio_identity_id"));
        tasks.add(tuple("radio presence", false, "trunked_radio_channel_presence",
            "idx_trunked_radio_channel_presence_retention", "confirmed_at_ms",
            "radio_system_id, radio_identity_id"));
        tasks.add(tuple("radio presence clears", false, "trunked_radio_channel_presence_clear",
            "idx_trunked_radio_channel_presence_clear_retention", "cleared_at_ms",
            "radio_system_id, radio_identity_id, channel_id"));
        tasks.add(tuple("radio/group relationships", false, "trunked_radio_group_summary",
            "idx_trunked_radio_group_retention", "last_seen_ms",
            "radio_system_id, radio_identity_id, group_identity_id"));
        tasks.add(sql("radio-system identities", false, """
            DELETE FROM radio_system_identity_summary
            WHERE id IN (
                SELECT identity.id
                FROM radio_system_identity_summary identity INDEXED BY idx_radio_system_identity_retention
                WHERE identity.last_seen_ms < ?
                  AND NOT EXISTS (SELECT 1 FROM trunked_radio_group_summary child
                      WHERE child.radio_system_id = identity.radio_system_id
                        AND (child.radio_identity_id = identity.id OR child.group_identity_id = identity.id))
                  AND NOT EXISTS (SELECT 1 FROM trunked_radio_affiliation child
                      WHERE child.radio_system_id = identity.radio_system_id
                        AND (child.radio_identity_id = identity.id OR child.talkgroup_identity_id = identity.id))
                  AND NOT EXISTS (SELECT 1 FROM trunked_radio_channel_presence child
                      WHERE child.radio_system_id = identity.radio_system_id
                        AND child.radio_identity_id = identity.id)
                  AND NOT EXISTS (SELECT 1 FROM trunked_radio_channel_presence_clear child
                      WHERE child.radio_system_id = identity.radio_system_id
                        AND child.radio_identity_id = identity.id)
                  AND NOT EXISTS (SELECT 1 FROM trunked_logical_call_identity_bucket child
                      WHERE child.radio_system_id = identity.radio_system_id
                        AND child.identity_summary_id = identity.id)
                  AND NOT EXISTS (SELECT 1 FROM p25_site_call_identity_bucket child
                      WHERE child.radio_system_id = identity.radio_system_id
                        AND child.identity_summary_id = identity.id)
                  AND NOT EXISTS (SELECT 1 FROM receiver_activity_event child
                      WHERE child.radio_system_id = identity.radio_system_id
                        AND (child.source_identity_summary_id = identity.id
                          OR child.target_identity_summary_id = identity.id))
                  AND NOT EXISTS (SELECT 1 FROM activity_event_identity_member child
                      WHERE child.radio_system_id = identity.radio_system_id
                        AND child.identity_summary_id = identity.id)
                ORDER BY identity.last_seen_ms, identity.radio_system_id, identity.id
                LIMIT ?
            )
            """));

        tasks.add(tuple("conventional DMR talkgroups", false, DmrActivitySchema.TALKGROUP_TABLE,
            DmrActivitySchema.TALKGROUP_RETENTION_INDEX, "last_seen_ms",
            "channel_id, frequency_hz, timeslot, talkgroup_id"));
        tasks.add(tuple("conventional DMR radios", false, DmrActivitySchema.RADIO_TABLE,
            DmrActivitySchema.RADIO_RETENTION_INDEX, "last_seen_ms",
            "channel_id, frequency_hz, timeslot, radio_id"));

        tasks.add(tuple("DMR/NXDN site channels", false, "trunked_site_channel_summary",
            "idx_trunked_site_channel_last_seen", "last_seen_ms",
            "channel_id, channel_number, inbound_channel_number, timeslot, frequency_hz"));
        tasks.add(tuple("DMR/NXDN site neighbors", false, "trunked_site_neighbor_summary",
            "idx_trunked_site_neighbor_last_seen", "last_seen_ms",
            "channel_id, protocol_code, variant_code, dmr_model_code, nxdn_location_category_code, network_id, " +
                "system_id, site_id, channel_number, frequency_hz"));
        tasks.add(sql("DMR/NXDN site snapshots", false, """
            DELETE FROM trunked_site_snapshot
            WHERE channel_id IN (
                SELECT site.channel_id
                FROM trunked_site_snapshot site INDEXED BY idx_trunked_site_snapshot_last_seen
                WHERE site.last_seen_ms < ?
                  AND NOT EXISTS (SELECT 1 FROM trunked_site_channel_summary child
                      WHERE child.channel_id = site.channel_id)
                  AND NOT EXISTS (SELECT 1 FROM trunked_site_neighbor_summary child
                      WHERE child.channel_id = site.channel_id)
                ORDER BY site.last_seen_ms, site.channel_id
                LIMIT ?
            )
            """));

        tasks.add(sqlWithoutCutoff("unused radio systems", """
            DELETE FROM radio_system
            WHERE id IN (
                SELECT system.id
                FROM radio_system system
                WHERE NOT EXISTS (SELECT 1 FROM receiver_channel WHERE radio_system_id = system.id)
                  AND NOT EXISTS (SELECT 1 FROM radio_system_identity_summary WHERE radio_system_id = system.id)
                  AND NOT EXISTS (SELECT 1 FROM trunked_radio_group_summary WHERE radio_system_id = system.id)
                  AND NOT EXISTS (SELECT 1 FROM trunked_radio_affiliation WHERE radio_system_id = system.id)
                  AND NOT EXISTS (SELECT 1 FROM trunked_radio_channel_presence WHERE radio_system_id = system.id)
                  AND NOT EXISTS (SELECT 1 FROM trunked_radio_channel_presence_clear WHERE radio_system_id = system.id)
                  AND NOT EXISTS (SELECT 1 FROM trunked_logical_call_bucket WHERE radio_system_id = system.id)
                  AND NOT EXISTS (SELECT 1 FROM trunked_logical_call_identity_bucket WHERE radio_system_id = system.id)
                  AND NOT EXISTS (SELECT 1 FROM receiver_activity_event WHERE radio_system_id = system.id)
                  AND NOT EXISTS (SELECT 1 FROM trunked_signaling_activity_bucket WHERE radio_system_id = system.id)
                  AND NOT EXISTS (SELECT 1 FROM p25_learned_site WHERE radio_system_id = system.id)
                ORDER BY system.id
                LIMIT ?
            )
            """));
        return List.copyOf(tasks);
    }

    private static void addP25SiteTasks(List<RetentionTask> tasks, boolean summary)
    {
        String suffix = summary ? "_summary" : "";
        String time = summary ? "last_seen_ms" : "confirmed_at_ms";
        tasks.add(rowId("P25 site channel tags" + suffix, false, "p25_site_channel_tag" + suffix,
            "idx_p25_site_channel_tag" + suffix + "_retention", time, ""));
        tasks.add(rowId("P25 site channels" + suffix, false, "p25_site_channel" + suffix,
            "idx_p25_site_channel" + suffix + "_retention", time,
            "AND NOT EXISTS (SELECT 1 FROM p25_site_channel_tag" + suffix + " child " +
                "WHERE child.channel_id = item.channel_id AND child.channel_key = item.channel_key)"));
        tasks.add(rowId("P25 frequency bands" + suffix, false, "p25_site_frequency_band" + suffix,
            "idx_p25_site_frequency_band" + suffix + "_retention", time, ""));
        tasks.add(tuple("P25 foreign-system bands" + suffix, false, "p25_foreign_system_band" + suffix,
            "idx_p25_foreign_system_band" + suffix + "_retention", time,
            "channel_id, foreign_wacn, foreign_system_id, band"));
        tasks.add(rowId("P25 site neighbors" + suffix, false, "p25_site_neighbor" + suffix,
            "idx_p25_site_neighbor" + suffix + "_retention", time, ""));
        tasks.add(rowId("P25 patch talkgroups" + suffix, false,
            "p25_site_patch_group_talkgroup" + suffix,
            "idx_p25_site_patch_group_talkgroup" + suffix + "_retention", time, ""));
        tasks.add(rowId("P25 patch radios" + suffix, false,
            "p25_site_patch_group_radio" + suffix,
            "idx_p25_site_patch_group_radio" + suffix + "_retention", time, ""));
        tasks.add(rowId("P25 patch groups" + suffix, false, "p25_site_patch_group" + suffix,
            "idx_p25_site_patch_group" + suffix + "_retention", time,
            "AND NOT EXISTS (SELECT 1 FROM p25_site_patch_group_talkgroup" + suffix + " child " +
                "WHERE child.channel_id = item.channel_id " +
                "AND child.local_patch_group_id = item.local_patch_group_id) " +
                "AND NOT EXISTS (SELECT 1 FROM p25_site_patch_group_radio" + suffix + " child " +
                "WHERE child.channel_id = item.channel_id " +
                "AND child.local_patch_group_id = item.local_patch_group_id)"));

        if(summary)
        {
            tasks.add(sql("P25 site snapshots", false, """
                DELETE FROM p25_site_snapshot
                WHERE channel_id IN (
                    SELECT site.channel_id
                    FROM p25_site_snapshot site INDEXED BY idx_p25_site_snapshot_retention
                    WHERE site.last_seen_ms < ?
                      AND NOT EXISTS (SELECT 1 FROM p25_site_channel child WHERE child.channel_id = site.channel_id)
                      AND NOT EXISTS (SELECT 1 FROM p25_site_channel_summary child WHERE child.channel_id = site.channel_id)
                      AND NOT EXISTS (SELECT 1 FROM p25_site_frequency_band child WHERE child.channel_id = site.channel_id)
                      AND NOT EXISTS (SELECT 1 FROM p25_site_frequency_band_summary child WHERE child.channel_id = site.channel_id)
                      AND NOT EXISTS (SELECT 1 FROM p25_foreign_system_band child WHERE child.channel_id = site.channel_id)
                      AND NOT EXISTS (SELECT 1 FROM p25_foreign_system_band_summary child WHERE child.channel_id = site.channel_id)
                      AND NOT EXISTS (SELECT 1 FROM p25_site_neighbor child WHERE child.channel_id = site.channel_id)
                      AND NOT EXISTS (SELECT 1 FROM p25_site_neighbor_summary child WHERE child.channel_id = site.channel_id)
                      AND NOT EXISTS (SELECT 1 FROM p25_site_patch_group child WHERE child.channel_id = site.channel_id)
                      AND NOT EXISTS (SELECT 1 FROM p25_site_patch_group_summary child WHERE child.channel_id = site.channel_id)
                    ORDER BY site.last_seen_ms, site.channel_id
                    LIMIT ?
                )
                """));
        }
    }

    private static RetentionTask rowId(String name, boolean hourly, String table, String index, String time,
                                       String guard)
    {
        String indexed = index == null ? "" : " INDEXED BY " + index;
        return sql(name, hourly, "DELETE FROM " + table + " WHERE rowid IN (SELECT item.rowid FROM " + table +
            " item" + indexed + " WHERE item." + time + " < ? " + guard + " ORDER BY item." + time +
            ", item.rowid LIMIT ?)");
    }

    private static RetentionTask tuple(String name, boolean hourly, String table, String index, String time,
                                       String columns)
    {
        return sql(name, hourly, "DELETE FROM " + table + " WHERE (" + columns + ") IN (SELECT " + columns +
            " FROM " + table + " INDEXED BY " + index + " WHERE " + time + " < ? ORDER BY " + time +
            ", " + columns + " LIMIT ?)");
    }

    private static RetentionTask sql(String name, boolean hourly, String sql)
    {
        return task(name, hourly, true, sql);
    }

    private static RetentionTask sqlWithoutCutoff(String name, String sql)
    {
        return task(name, false, false, sql);
    }

    private static RetentionTask task(String name, boolean hourly, boolean usesCutoff, String sql)
    {
        return new RetentionTask(name, hourly, usesCutoff, sql);
    }

    record Pass(int deletedRows, boolean moreWorkLikely, int tasksVisited, int nextCursor)
    {
    }

    private record RetentionTask(String name, boolean hourly, boolean usesCutoff, String sql)
    {
        private int delete(Connection connection, long cutoff, int maximumRows) throws SQLException
        {
            try(PreparedStatement statement = connection.prepareStatement(sql))
            {
                int index = 1;
                if(usesCutoff)
                {
                    statement.setLong(index++, cutoff);
                }
                statement.setInt(index, maximumRows);
                return statement.executeUpdate();
            }
            catch(SQLException e)
            {
                throw new SQLException("Activity retention task failed [" + name + "]", e);
            }
        }
    }
}
