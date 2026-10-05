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

import io.github.dsheirer.stats.AliasActivitySummaryMaintenance;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.Identity;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.IdentityKind;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.ScopedData;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Exact, owner-bound delete plans shared by read-only preview and the statistics writer. */
final class ReceiverActivityScopedDeletion
{
    private static final String CONVENTIONAL_CALL_RESET =
        "call_count=0,encrypted_count=0,recorded_count=0,streamed_count=0";
    private static final String CONVENTIONAL_CALL_MATCH =
        "call_count>0 OR encrypted_count>0 OR recorded_count>0 OR streamed_count>0";
    private static final String IDENTITY_CALL_RESET =
        "call_count=0,logical_call_count=0,source_logical_call_count=0,target_logical_call_count=0," +
            "encrypted_logical_call_count=0,recorded_output_count=0,streamed_output_count=0";
    private static final String IDENTITY_CALL_MATCH =
        "call_count>0 OR logical_call_count>0 OR source_logical_call_count>0 OR " +
            "target_logical_call_count>0 OR encrypted_logical_call_count>0 OR " +
            "recorded_output_count>0 OR streamed_output_count>0";
    private static final String GROUP_CALL_RESET =
        "call_count=0,logical_call_count=0,encrypted_logical_call_count=0," +
            "recorded_output_count=0,streamed_output_count=0";
    private static final String GROUP_CALL_MATCH =
        "call_count>0 OR logical_call_count>0 OR encrypted_logical_call_count>0 OR " +
            "recorded_output_count>0 OR streamed_output_count>0";
    private static final List<String> SIGNALING_COLUMNS = ReceiverActivityCodes.actionCodes().stream()
        .filter(action -> action != ReceiverActivityRecords.Action.CALL)
        .map(action -> action.name().toLowerCase(Locale.ROOT) + "_count").toList();
    private static final String SIGNALING_RESET = SIGNALING_COLUMNS.stream()
        .map(column -> column + "=0").collect(Collectors.joining(","));
    private static final String SIGNALING_MATCH = SIGNALING_COLUMNS.stream()
        .map(column -> column + ">0").collect(Collectors.joining(" OR "));

    private ReceiverActivityScopedDeletion()
    {
    }

    record Preview(ReceiverActivityMaintenance.DeletionOutcome outcome, Map<String,Integer> countsByPart,
                   int rowsTotal, List<String> effects)
    {
    }

    static Preview preview(Connection connection, ScopedData target) throws SQLException
    {
        return Batch.start(connection, target, System.currentTimeMillis()).preview();
    }

    static ReceiverActivityDeletion.Result delete(Connection connection, ScopedData target) throws SQLException
    {
        Plan plan = plan(connection, target);
        if(plan.outcome == ReceiverActivityMaintenance.DeletionOutcome.NOT_FOUND)
            return ReceiverActivityDeletion.Result.missing();
        if(plan.outcome == ReceiverActivityMaintenance.DeletionOutcome.STALE_SITE)
            return ReceiverActivityDeletion.Result.staleSite();
        if("alias_activity".equals(target.sourceKind()))
        {
            int removed = target.recordKey() == null ? AliasActivitySummaryMaintenance.resetAll(connection) :
                resetOneAlias(connection, parsePositive(target.recordKey()));
            return ReceiverActivityDeletion.Result.deleted(removed);
        }

        int deleted = 0;
        for(Step step: plan.steps)
        {
            String sql = step.updateSet == null ?
                "DELETE FROM " + step.table + " WHERE " + step.predicate :
                "UPDATE " + step.table + " SET " + step.updateSet + " WHERE " + step.predicate;
            try(PreparedStatement statement = connection.prepareStatement(sql))
            {
                bind(statement, step.parameters);
                deleted = Math.addExact(deleted, statement.executeUpdate());
            }
        }
        if("frequencies".equals(target.dataType()) && target.recordKey() != null &&
            target.parts().contains("current"))
        {
            deleted = Math.addExact(deleted,
                clearSnapshotFrequency(connection, plan.scope, parsePositive(target.recordKey())));
        }
        if(plan.scope.conventional && target.parts().contains("summary") &&
            Set.of("radios", "talkgroups").contains(target.dataType()))
            clearConventionalLastReferences(connection, plan.scope.channelId, target);
        return deleted > 0 ? ReceiverActivityDeletion.Result.deleted(deleted) :
            ReceiverActivityDeletion.Result.missing();
    }

    private static Plan plan(Connection connection, ScopedData target) throws SQLException
    {
        if("alias_activity".equals(target.sourceKind()))
        {
            if(target.recordKey() != null)
            {
                long id = parsePositive(target.recordKey());
                if(scalar(connection, "SELECT id FROM alias WHERE id=?", List.of(id)) == 0)
                    return Plan.missing();
            }
            return new Plan(null);
        }

        Scope scope = resolve(connection, target);
        if(scope == null) return Plan.missing();
        if(scope.stale) return Plan.staleSite();
        validateTarget(target, scope);
        Plan plan = new Plan(connection, scope);

        if(target.dataType().equals("all"))
        {
            addAll(plan, target);
        }
        else if(target.dataType().equals("site_state")) addSiteState(plan);
        else addFamily(plan, target, target.dataType());

        applyHistoryFilters(plan, target);
        plan.steps.sort(Comparator.comparingInt(ReceiverActivityScopedDeletion::priority));
        if(plan.steps.stream().anyMatch(step -> "receiver_activity_event".equals(step.table)))
            plan.effects.add("Matching detailed events also remove their linked identity-member rows.");
        return plan;
    }

    private static void addAll(Plan plan, ScopedData target)
    {
        Scope scope = plan.scope;
        if(has(target, "events"))
        {
            plan.event("events", "receiver_activity_event");
        }
        if(has(target, "buckets"))
        {
            for(String table: List.of("conventional_activity_bucket", "conventional_call_identity_bucket",
                "trunked_control_channel_quality")) plan.channel("buckets", table);
            plan.systemOwnedChannel("buckets", "trunked_signaling_activity_bucket");
            plan.systemOwnedChannel("buckets", "p25_site_call_identity_bucket");
            if(scope.systemWide)
            {
                for(String table: List.of("trunked_logical_call_bucket", "trunked_logical_call_identity_bucket",
                    "p25_site_call_bucket")) plan.system("buckets", table);
            }
        }
        if(has(target, "summary"))
        {
            for(String table: List.of("conventional_activity_summary", "dmr_conventional_radio_summary",
                "dmr_conventional_talkgroup_summary", "p25_site_channel_tag_summary",
                "p25_site_channel_summary", "p25_site_frequency_band_summary",
                "p25_foreign_system_band_summary", "p25_site_neighbor_summary",
                "p25_site_patch_group_radio_summary", "p25_site_patch_group_talkgroup_summary",
                "p25_site_patch_group_summary", "trunked_site_channel_summary",
                "trunked_site_neighbor_summary")) plan.channel("summary", table);
            if(scope.systemWide)
            {
                plan.system("summary", "trunked_radio_group_summary");
                plan.system("summary", "radio_system_identity_summary");
                plan.effects.add("Deleting system identity summaries also removes linked identity history, " +
                    "detailed events, and radio relationships through database foreign keys.");
            }
        }
        if(has(target, "current") && !scope.conventional)
        {
            for(String table: List.of("p25_site_channel_tag", "p25_site_channel",
                "p25_site_frequency_band", "p25_foreign_system_band", "p25_site_neighbor",
                "p25_site_patch_group_radio", "p25_site_patch_group_talkgroup",
                "p25_site_patch_group")) plan.channel("current", table);
            if(scope.systemWide)
            {
                for(String table: List.of("trunked_radio_affiliation", "trunked_radio_channel_presence",
                    "trunked_radio_channel_presence_clear", "p25_learned_site"))
                    plan.system("current", table);
                plan.effects.add("Removing learned site state also removes linked site call buckets.");
            }
            addSiteState(plan);
        }
    }

    private static void applyHistoryFilters(Plan plan, ScopedData target) throws SQLException
    {
        if(target.fromMs() == null && target.toMs() == null && target.frequencyHz() == null) return;
        for(int i = 0; i < plan.steps.size(); i++)
        {
            Step step = plan.steps.get(i);
            Set<String> columns = tableColumns(plan.connection, step.table);
            String time = columns.contains("observed_at_ms") ? "observed_at_ms" : "bucket_start_ms";
            String predicate = step.predicate;
            List<Object> arguments = new ArrayList<>(step.parameters);
            if(target.fromMs() != null)
            {
                predicate += " AND " + time + ">=?";
                arguments.add(target.fromMs());
            }
            if(target.toMs() != null)
            {
                predicate += " AND " + time + "<?";
                arguments.add(target.toMs());
            }
            if(target.frequencyHz() != null)
            {
                // Some hourly totals are system-wide and contain no physical-frequency attribution.
                if(!columns.contains("frequency_hz"))
                {
                    predicate += " AND 0=1";
                }
                else
                {
                    predicate += " AND frequency_hz=?";
                    arguments.add(target.frequencyHz());
                }
            }
            plan.steps.set(i, new Step(step.part, step.table, predicate, List.copyOf(arguments), step.updateSet));
        }
    }

    private static Set<String> tableColumns(Connection connection, String table) throws SQLException
    {
        Set<String> columns = new LinkedHashSet<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA table_info(" + table + ")"))
        {
            while(rows.next()) columns.add(rows.getString("name"));
        }
        return columns;
    }

    private static void validateTarget(ScopedData target, Scope scope)
    {
        String type = target.dataType();
        Set<String> allowed = switch(type)
        {
            case "site_state", "affiliations" -> Set.of("current");
            case "band_plans", "foreign_band_plans", "neighbors", "patches" -> Set.of("current", "summary");
            case "control_quality", "hourly_history" -> Set.of("buckets");
            case "relationships" -> Set.of("summary");
            case "detailed_events" -> Set.of("events");
            case "frequencies", "all" -> Set.of("current", "summary", "buckets", "events");
            case "radios", "talkgroups", "call_activity", "signaling_activity" ->
                Set.of("summary", "buckets", "events");
            default -> throw new IllegalArgumentException("Data type is invalid");
        };
        if(!allowed.containsAll(target.parts())) throw new IllegalArgumentException("Part is unavailable for data type");
        if(scope.conventional && Set.of("site_state", "band_plans", "foreign_band_plans", "neighbors",
            "patches", "control_quality", "relationships", "affiliations").contains(type))
            throw new IllegalArgumentException("Data type requires a trunked system");
        if(scope.conventional && "frequencies".equals(type) && target.parts().contains("current"))
            throw new IllegalArgumentException("Conventional frequencies have no current site projection");
        if(scope.protocol != 1 && Set.of("band_plans", "foreign_band_plans", "patches").contains(type))
            throw new IllegalArgumentException("Data type requires P25");
        if(scope.protocol != 1 && "neighbors".equals(type) && target.parts().contains("current"))
            throw new IllegalArgumentException("Current neighbors require P25");
        if(scope.protocol != 1 && "neighbors".equals(type) && target.recordKey() != null)
            throw new IllegalArgumentException("Individual non-P25 neighbors are not selected by this key");
        if(scope.protocol != 1 && "frequencies".equals(type) && target.parts().contains("current"))
            throw new IllegalArgumentException("Current frequencies require P25");
        if(!scope.systemWide && !scope.conventional && Set.of("radios", "talkgroups", "relationships",
            "affiliations").contains(type))
            throw new IllegalArgumentException("System identities cannot be attributed to one saved site");
        if(scope.conventional && target.recordKey() != null &&
            Set.of("radios", "talkgroups").contains(type) && target.parts().contains("buckets"))
            throw new IllegalArgumentException("Hourly identity history has no conventional frequency key");
        if(target.recordKey() != null && !Set.of("frequencies", "band_plans", "foreign_band_plans",
            "neighbors", "patches", "radios", "talkgroups").contains(type))
            throw new IllegalArgumentException("Data type cannot select a single row");
        if("frequencies".equals(type) && target.recordKey() != null) parsePositive(target.recordKey());
        if("band_plans".equals(type) && target.recordKey() != null) parseBand(target.recordKey());
        if("patches".equals(type) && target.recordKey() != null) parsePositive(target.recordKey());
        if("foreign_band_plans".equals(type) && target.recordKey() != null) parseForeign(target.recordKey());
    }

    private static Scope resolve(Connection connection, ScopedData target) throws SQLException
    {
        if("saved_channel".equals(target.sourceKind()))
        {
            ReceiverActivityDeletion.SavedChannel channel =
                ReceiverActivityDeletion.savedChannel(connection, target.sourceKey());
            if(channel == null) return null;
            if(!"CONVENTIONAL".equals(channel.kind()))
                throw new IllegalArgumentException("Select a conventional saved channel");
            return new Scope(0, channel.id(), 0, true, false, false);
        }

        long systemId = ReceiverActivityDeletion.systemId(connection, target.sourceKey());
        if(systemId == 0) return null;
        int protocol = Math.toIntExact(scalar(connection, "SELECT protocol_code FROM radio_system WHERE id=?", List.of(systemId)));
        if(target.siteConfigurationId() == null)
            return new Scope(systemId, 0, protocol, false, true, false);

        ReceiverActivityDeletion.SavedChannel channel =
            ReceiverActivityDeletion.savedChannel(connection, target.siteConfigurationId());
        if(channel == null) return null;
        long owner = scalar(connection,
            "SELECT radio_system_id FROM receiver_channel WHERE id=? AND radio_system_id=?",
            List.of(channel.id(), systemId));
        if(owner == 0 || !"TRUNKED".equals(channel.kind())) return null;
        if(!target.expectedSiteKey().equals(channel.siteKey()))
            return new Scope(systemId, channel.id(), protocol, false, false, true);
        return new Scope(systemId, channel.id(), protocol, false, false, false);
    }

    private static void addFamily(Plan plan, ScopedData target, String family) throws SQLException
    {
        Scope scope = plan.scope;
        String key = target.recordKey();
        switch(family)
        {
            case "frequencies" ->
            {
                String frequency = key == null ? null : "frequency_hz=?";
                String downlink = key == null ? null : "downlink_hz=?";
                List<Object> value = key == null ? List.of() : List.of(parsePositive(key));
                if(scope.conventional)
                {
                    if(has(target, "summary"))
                    {
                        plan.channel("summary", "conventional_activity_summary", frequency, value);
                        plan.channel("summary", "dmr_conventional_radio_summary", frequency, value);
                        plan.channel("summary", "dmr_conventional_talkgroup_summary", frequency, value);
                    }
                    if(has(target, "buckets")) plan.channel("buckets", "conventional_activity_bucket", frequency, value);
                }
                else
                {
                    if(has(target, "current")) plan.channel("current", "p25_site_channel", downlink, value);
                    if(has(target, "summary"))
                    {
                        String displayedFrequency = key == null ? null : "channel_key IN (" +
                            "SELECT summary.channel_key FROM p25_site_channel_summary summary " +
                            "LEFT JOIN p25_site_channel current ON current.channel_id=summary.channel_id " +
                            "AND current.channel_key=summary.channel_key " +
                            "WHERE summary.channel_id=p25_site_channel_summary.channel_id " +
                            "AND coalesce(current.downlink_hz,summary.downlink_hz)=?)";
                        plan.channel("summary", "p25_site_channel_summary", displayedFrequency, value);
                        plan.channel("summary", "trunked_site_channel_summary", frequency, value);
                    }
                    if(has(target, "current"))
                    {
                        String currentTags = key == null ? null : "channel_key IN (" +
                            "SELECT current.channel_key FROM p25_site_channel current " +
                            "WHERE current.channel_id=p25_site_channel_tag.channel_id AND current.downlink_hz=?)";
                        plan.channel("current", "p25_site_channel_tag", currentTags, value);
                    }
                    if(has(target, "summary"))
                    {
                        String summaryTags = key == null ? null : "channel_key IN (" +
                            "SELECT summary.channel_key FROM p25_site_channel_summary summary " +
                            "LEFT JOIN p25_site_channel current ON current.channel_id=summary.channel_id " +
                            "AND current.channel_key=summary.channel_key " +
                            "WHERE summary.channel_id=p25_site_channel_tag_summary.channel_id " +
                            "AND coalesce(current.downlink_hz,summary.downlink_hz)=?)";
                        plan.channel("summary", "p25_site_channel_tag_summary", summaryTags, value);
                    }
                    if(has(target, "buckets"))
                        plan.channel("buckets", "trunked_control_channel_quality", frequency, value);
                    if(has(target, "current") || has(target, "summary"))
                        plan.effects.add("Linked P25 frequency tags are removed with their selected layer.");
                }
                if(has(target, "events")) plan.event("events", "receiver_activity_event",
                    key == null ? "frequency_hz IS NOT NULL" : frequency, value);
            }
            case "band_plans" ->
            {
                String predicate = key == null ? null : "band=?";
                List<Object> args = key == null ? List.of() : List.of(parseBand(key));
                if(has(target, "current")) plan.channel("current", "p25_site_frequency_band", predicate, args);
                if(has(target, "summary")) plan.channel("summary", "p25_site_frequency_band_summary", predicate, args);
                plan.effects.add("Previously resolved frequency rows are stored separately and remain until selected.");
            }
            case "foreign_band_plans" ->
            {
                int[] foreign = key == null ? null : parseForeign(key);
                String predicate = key == null ? null :
                    "foreign_wacn=? AND foreign_system_id=? AND band=?";
                List<Object> args = foreign == null ? List.of() :
                    List.of(foreign[0], foreign[1], foreign[2]);
                if(has(target, "current")) plan.channel("current", "p25_foreign_system_band", predicate, args);
                if(has(target, "summary")) plan.channel("summary", "p25_foreign_system_band_summary", predicate, args);
                plan.effects.add("Previously resolved frequency rows are stored separately and remain until selected.");
            }
            case "neighbors" ->
            {
                String predicate = key == null ? null : "neighbor_key=?";
                List<Object> args = key == null ? List.of() : List.of(key);
                if(has(target, "current")) plan.channel("current", "p25_site_neighbor", predicate, args);
                if(has(target, "summary"))
                {
                    plan.channel("summary", "p25_site_neighbor_summary", predicate, args);
                    if(key == null) plan.channel("summary", "trunked_site_neighbor_summary");
                }
            }
            case "patches" ->
            {
                String predicate = key == null ? null : "local_patch_group_id=?";
                List<Object> args = key == null ? List.of() : List.of(parsePositive(key));
                if(has(target, "current"))
                {
                    plan.channel("current", "p25_site_patch_group_radio", predicate, args);
                    plan.channel("current", "p25_site_patch_group_talkgroup", predicate, args);
                    plan.channel("current", "p25_site_patch_group", predicate, args);
                }
                if(has(target, "summary"))
                {
                    plan.channel("summary", "p25_site_patch_group_radio_summary", predicate, args);
                    plan.channel("summary", "p25_site_patch_group_talkgroup_summary", predicate, args);
                    plan.channel("summary", "p25_site_patch_group_summary", predicate, args);
                }
            }
            case "control_quality" ->
            {
                if(has(target, "buckets")) plan.channel("buckets", "trunked_control_channel_quality");
            }
            case "radios", "talkgroups" -> addIdentities(plan, target, "radios".equals(family));
            case "relationships" ->
            {
                if(scope.systemWide && has(target, "summary"))
                    plan.system("summary", "trunked_radio_group_summary");
            }
            case "affiliations" ->
            {
                if(scope.systemWide && has(target, "current"))
                {
                    plan.system("current", "trunked_radio_affiliation");
                    plan.system("current", "trunked_radio_channel_presence");
                    plan.system("current", "trunked_radio_channel_presence_clear");
                }
            }
            case "call_activity" ->
            {
                if(scope.conventional)
                {
                    if(has(target, "summary"))
                        plan.updateChannel("summary", "conventional_activity_summary",
                            CONVENTIONAL_CALL_RESET, CONVENTIONAL_CALL_MATCH);
                    if(has(target, "buckets"))
                    {
                        plan.updateChannel("buckets", "conventional_activity_bucket",
                            CONVENTIONAL_CALL_RESET, CONVENTIONAL_CALL_MATCH);
                        plan.channel("buckets", "conventional_call_identity_bucket");
                    }
                }
                else
                {
                    if(has(target, "summary") && scope.systemWide)
                    {
                        plan.updateSystem("summary", "radio_system_identity_summary",
                            IDENTITY_CALL_RESET, IDENTITY_CALL_MATCH);
                        plan.updateSystem("summary", "trunked_radio_group_summary",
                            GROUP_CALL_RESET, GROUP_CALL_MATCH);
                    }
                    if(has(target, "buckets"))
                    {
                        plan.systemOwnedChannel("buckets", "p25_site_call_identity_bucket");
                        if(scope.systemWide)
                        {
                            plan.system("buckets", "p25_site_call_bucket");
                            plan.system("buckets", "trunked_logical_call_bucket");
                            plan.system("buckets", "trunked_logical_call_identity_bucket");
                        }
                    }
                }
                if(has(target, "events")) plan.event("events", "receiver_activity_event",
                    "action_code=4", List.of());
                if(has(target, "summary")) plan.effects.add("Call counters are reset in place; identity names and " +
                    "other signaling counters remain.");
            }
            case "signaling_activity" ->
            {
                if(scope.conventional)
                {
                    if(has(target, "summary"))
                        plan.updateChannel("summary", "conventional_activity_summary",
                            SIGNALING_RESET, SIGNALING_MATCH);
                    if(has(target, "buckets"))
                        plan.updateChannel("buckets", "conventional_activity_bucket",
                            SIGNALING_RESET, SIGNALING_MATCH);
                }
                else
                {
                    if(has(target, "summary") && scope.systemWide)
                    {
                        plan.updateSystem("summary", "radio_system_identity_summary",
                            SIGNALING_RESET, SIGNALING_MATCH);
                        plan.updateSystem("summary", "trunked_radio_group_summary",
                            SIGNALING_RESET, SIGNALING_MATCH);
                    }
                    if(has(target, "buckets"))
                        plan.systemOwnedChannel("buckets", "trunked_signaling_activity_bucket");
                }
                if(has(target, "events")) plan.event("events", "receiver_activity_event",
                    "action_code<>4", List.of());
                if(has(target, "summary")) plan.effects.add("Signaling counters are reset in place; identity names " +
                    "and call counters remain.");
            }
            case "hourly_history" ->
            {
                if(scope.conventional)
                {
                    plan.channel("buckets", "conventional_activity_bucket");
                    plan.channel("buckets", "conventional_call_identity_bucket");
                }
                else
                {
                    plan.systemOwnedChannel("buckets", "trunked_signaling_activity_bucket");
                    plan.systemOwnedChannel("buckets", "p25_site_call_identity_bucket");
                    if(scope.systemWide)
                    {
                        plan.system("buckets", "trunked_logical_call_bucket");
                        plan.system("buckets", "trunked_logical_call_identity_bucket");
                        plan.system("buckets", "p25_site_call_bucket");
                    }
                }
            }
            case "detailed_events" ->
            {
                if(has(target, "events")) plan.event("events", "receiver_activity_event");
            }
            default -> throw new IllegalArgumentException("Data type is invalid");
        }
    }

    private static void addIdentities(Plan plan, ScopedData target, boolean radio) throws SQLException
    {
        Scope scope = plan.scope;
        int kind = radio ? 2 : 1;
        String key = target.recordKey();
        if(scope.conventional)
        {
            String table = radio ? "dmr_conventional_radio_summary" : "dmr_conventional_talkgroup_summary";
            String column = radio ? "radio_id" : "talkgroup_id";
            String predicate = null;
            List<Object> args = List.of();
            if(key != null)
            {
                String[] pieces = key.split(":", -1);
                if(pieces.length != 3) throw new IllegalArgumentException("Conventional identity key is invalid");
                long hz = parsePositive(pieces[0]);
                int timeslot = parseRange(pieces[1], 1, 2);
                int nativeId = parseRange(pieces[2], 1, 16_777_215);
                predicate = "frequency_hz=? AND timeslot=? AND " + column + "=?";
                args = List.of(hz, timeslot, nativeId);
            }
            if(has(target, "summary")) plan.channel("summary", table, predicate, args);
            if(has(target, "summary")) plan.effects.add("Last-radio or last-talkgroup references in other " +
                "conventional summaries are cleared when their target is removed.");
            if(has(target, "buckets"))
                plan.channel("buckets", "conventional_call_identity_bucket", "identity_kind_code=?",
                    List.of(kind));
            if(has(target, "events"))
            {
                // Conventional event IDs are local, not FK-linked to the DMR summary.
                // A bulk identity clear owns every conventional event on this channel.
                if(key == null) plan.event("events", "receiver_activity_event",
                    radio ? "source_observed_local_id IS NOT NULL OR " +
                        "(target_kind_code=2 AND target_observed_local_id IS NOT NULL)" :
                        "target_kind_code=1 AND target_observed_local_id IS NOT NULL", List.of());
                else
                {
                    String[] pieces = key.split(":", -1);
                    plan.event("events", "receiver_activity_event",
                        radio ? "frequency_hz=? AND timeslot=? AND (source_observed_local_id=? " +
                            "OR (target_kind_code=2 AND target_observed_local_id=?))" :
                            "frequency_hz=? AND timeslot=? AND target_kind_code=1 " +
                                "AND target_observed_local_id=?",
                        radio ? List.of(parsePositive(pieces[0]), parseRange(pieces[1], 1, 2),
                            parseRange(pieces[2], 1, 16_777_215), parseRange(pieces[2], 1, 16_777_215)) :
                            List.of(parsePositive(pieces[0]), parseRange(pieces[1], 1, 2),
                                parseRange(pieces[2], 1, 16_777_215)));
                }
            }
            return;
        }

        if(!scope.systemWide) return;
        String predicate = "identity_kind_code=?";
        List<Object> args = List.of(kind);
        long summaryId = 0;
        if(key != null)
        {
            ReceiverActivityDeletion.ParsedIdentity identity = ReceiverActivityDeletion.parseIdentity(
                new Identity(target.sourceKey(), key, radio ? IdentityKind.RADIO : IdentityKind.TALKGROUP));
            summaryId = scalar(plan.connection, """
                SELECT id FROM radio_system_identity_summary WHERE radio_system_id=? AND identity_kind_code=?
                    AND home_wacn=? AND home_system_id=? AND identity_id=?
                """, List.of(scope.systemId, kind, identity.homeWacn(), identity.homeSystemId(),
                identity.identityId()));
            predicate = "id=?";
            args = List.of(summaryId);
        }
        if(has(target, "events"))
        {
            if(key == null)
                plan.event("events", "receiver_activity_event",
                    radio ? "source_identity_summary_id IN (SELECT id FROM radio_system_identity_summary " +
                        "WHERE radio_system_id=? AND identity_kind_code=2) OR " +
                        "target_identity_summary_id IN (SELECT id FROM radio_system_identity_summary " +
                        "WHERE radio_system_id=? AND identity_kind_code=2)" :
                        "target_identity_summary_id IN (SELECT id FROM radio_system_identity_summary " +
                        "WHERE radio_system_id=? AND identity_kind_code=1)",
                    radio ? List.of(scope.systemId, scope.systemId) : List.of(scope.systemId));
            else
                plan.event("events", "receiver_activity_event",
                    radio ? "(source_identity_summary_id=? OR target_identity_summary_id=?)" :
                        "target_identity_summary_id=?",
                    radio ? List.of(summaryId, summaryId) : List.of(summaryId));
        }
        if(has(target, "buckets"))
        {
            if(key == null)
                plan.system("buckets", "trunked_logical_call_identity_bucket", "identity_kind_code=?",
                    List.of(kind));
            else
                plan.system("buckets", "trunked_logical_call_identity_bucket", "identity_summary_id=?",
                    List.of(summaryId));
            if(key == null)
                plan.system("buckets", "p25_site_call_identity_bucket", "identity_kind_code=?",
                    List.of(kind));
            else
                plan.system("buckets", "p25_site_call_identity_bucket", "identity_summary_id=?",
                    List.of(summaryId));
        }
        if(has(target, "summary"))
        {
            plan.system("summary", "radio_system_identity_summary", predicate, args);
            plan.effects.add("Deleting identity summaries also removes linked hourly identity history, detailed " +
                "events, and radio relationships through database foreign keys.");
        }
    }

    private static void addSiteState(Plan plan)
    {
        if(plan.scope.conventional) return;
        plan.channel("current", "p25_site_snapshot");
        plan.channel("current", "trunked_site_snapshot");
        plan.effects.add("Removing saved site state also removes its frequencies, band plans, neighbors, patches, " +
            "and their summaries through database foreign keys.");
    }

    private static int resetOneAlias(Connection connection, long aliasId) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT OR IGNORE INTO alias_activity_summary
                (alias_id,alias_list_id,protocol_code,metrics_state,updated_at_ms)
            SELECT id,alias_list_id,
                   CASE WHEN matcher_type IN
                       ('TALKGROUP','TALKGROUP_RANGE','RADIO_ID','RADIO_ID_RANGE')
                       THEN CASE protocol WHEN 'APCO25' THEN 1 WHEN 'APCO25_PHASE2' THEN 1
                           WHEN 'DMR' THEN 3 WHEN 'NXDN' THEN 4 ELSE 0 END
                       ELSE 0 END,
                   CASE WHEN matcher_type IN
                       ('TALKGROUP','TALKGROUP_RANGE','RADIO_ID','RADIO_ID_RANGE')
                       AND protocol IN ('APCO25','APCO25_PHASE2','DMR','NXDN')
                       THEN 'not_collected' ELSE 'unsupported' END, ?
            FROM alias WHERE id=?
            """))
        {
            statement.setLong(1, Math.max(1L, System.currentTimeMillis()));
            statement.setLong(2, aliasId);
            statement.executeUpdate();
        }
        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE alias_activity_summary
            SET metrics_state=CASE WHEN protocol_code=0 THEN 'unsupported' ELSE 'not_collected' END,
                logical_call_count=NULL, recorded_logical_call_count=NULL,
                stream_submitted_logical_call_count=NULL, encrypted_logical_call_count=NULL,
                grant_observation_count=NULL, join_observation_count=NULL,
                emergency_observation_count=NULL, register_observation_count=NULL,
                logout_observation_count=NULL, denial_observation_count=NULL,
                data_observation_count=NULL, other_signaling_observation_count=NULL,
                signaling_observation_count=NULL, first_evidence_ms=NULL, last_evidence_ms=NULL,
                updated_at_ms=?
            WHERE alias_id=?
            """))
        {
            statement.setLong(1, Math.max(1L, System.currentTimeMillis()));
            statement.setLong(2, aliasId);
            return statement.executeUpdate();
        }
    }

    private static void clearConventionalLastReferences(Connection connection, long channelId,
                                                         ScopedData target) throws SQLException
    {
        boolean radio = "radios".equals(target.dataType());
        String key = target.recordKey();
        String extra = "";
        List<Object> args = new ArrayList<>(List.of(channelId));
        if(key != null)
        {
            String[] parts = key.split(":", -1);
            extra = " AND frequency_hz=? AND timeslot=? AND " +
                (radio ? "last_source_radio_id=?" : "last_talkgroup_id=?");
            args.add(parsePositive(parts[0]));
            args.add(parseRange(parts[1], 1, 2));
            args.add(parseRange(parts[2], 1, 16_777_215));
        }
        if(radio)
        {
            updateNull(connection, "dmr_conventional_talkgroup_summary", "last_source_radio_id",
                "channel_id=?" + extra, args);
            List<Object> peerArgs = new ArrayList<>(args);
            String peerExtra = key == null ? "" : " AND frequency_hz=? AND timeslot=? AND last_peer_radio_id=?";
            updateNull(connection, "dmr_conventional_radio_summary", "last_peer_radio_id",
                "channel_id=?" + peerExtra, peerArgs);
        }
        else updateNull(connection, "dmr_conventional_radio_summary", "last_talkgroup_id",
            "channel_id=?" + extra, args);
    }

    private static void updateNull(Connection connection, String table, String column, String predicate,
                                   List<Object> args) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "UPDATE " + table + " SET " + column + "=NULL WHERE " + predicate))
        {
            bind(statement, args);
            statement.executeUpdate();
        }
    }

    private static int snapshotFrequencyCount(Connection connection, Scope scope, long frequencyHz)
        throws SQLException
    {
        int count = 0;
        for(String table: List.of("p25_site_snapshot", "trunked_site_snapshot"))
        {
            List<Object> arguments = new ArrayList<>(scope.channelArguments());
            arguments.add(frequencyHz);
            arguments.add(frequencyHz);
            count = Math.addExact(count, count(connection, table, scope.channelPredicate() +
                " AND (primary_frequency_hz=? OR current_control_hz=?)", arguments));
        }
        return count;
    }

    private static int clearSnapshotFrequency(Connection connection, Scope scope, long frequencyHz)
        throws SQLException
    {
        int changed = 0;
        for(String table: List.of("p25_site_snapshot", "trunked_site_snapshot"))
        {
            String predicate = scope.channelPredicate();
            try(PreparedStatement statement = connection.prepareStatement("UPDATE " + table + " SET " +
                "primary_frequency_hz=CASE WHEN primary_frequency_hz=? THEN NULL ELSE primary_frequency_hz END," +
                "current_control_hz=CASE WHEN current_control_hz=? THEN NULL ELSE current_control_hz END WHERE " +
                predicate + " AND (primary_frequency_hz=? OR current_control_hz=?)"))
            {
                int index = 1;
                statement.setLong(index++, frequencyHz);
                statement.setLong(index++, frequencyHz);
                for(Object arg: scope.channelArguments()) statement.setObject(index++, arg);
                statement.setLong(index++, frequencyHz);
                statement.setLong(index, frequencyHz);
                changed = Math.addExact(changed, statement.executeUpdate());
            }
        }
        return changed;
    }

    private static boolean has(ScopedData target, String part)
    {
        return target.parts().contains(part);
    }

    private static int priority(Step step)
    {
        return switch(step.table)
        {
            case "activity_event_identity_member" -> -1;
            case "receiver_activity_event" -> 0;
            case "p25_site_channel_tag", "p25_site_channel_tag_summary" -> 1;
            case "p25_site_patch_group_radio", "p25_site_patch_group_talkgroup",
                "p25_site_patch_group_radio_summary", "p25_site_patch_group_talkgroup_summary" -> 1;
            case "radio_system_identity_summary" -> 6;
            case "p25_site_snapshot", "trunked_site_snapshot", "p25_learned_site" -> 7;
            case "receiver_channel" -> 8;
            case "radio_system" -> 9;
            default -> "buckets".equals(step.part) ? 2 : "summary".equals(step.part) ? 3 : 4;
        };
    }

    private static int count(Connection connection, String table, String predicate, List<Object> args)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT count(*) FROM " + table + " WHERE " + predicate))
        {
            bind(statement, args);
            try(ResultSet rows = statement.executeQuery())
            {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private static long scalar(Connection connection, String sql, List<Object> args) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            bind(statement, args);
            try(ResultSet rows = statement.executeQuery())
            {
                return rows.next() ? rows.getLong(1) : 0;
            }
        }
    }

    private static void bind(PreparedStatement statement, List<Object> args) throws SQLException
    {
        for(int i = 0; i < args.size(); i++) statement.setObject(i + 1, args.get(i));
    }

    private static long parsePositive(String value)
    {
        try
        {
            long number = Long.parseLong(value);
            if(number > 0) return number;
        }
        catch(NumberFormatException ignored)
        {
        }
        throw new IllegalArgumentException("Record key must be a positive decimal integer");
    }

    private static int parseRange(String value, int minimum, int maximum)
    {
        long number = parsePositive(value);
        if(number > maximum || number < minimum) throw new IllegalArgumentException("Record key is out of range");
        return (int)number;
    }

    private static int parseBand(String value)
    {
        try
        {
            int band = Integer.parseInt(value);
            if(band >= 0 && band <= 15) return band;
        }
        catch(NumberFormatException ignored)
        {
        }
        throw new IllegalArgumentException("Band key is invalid");
    }

    private static int[] parseForeign(String value)
    {
        String[] parts = value.split(":", -1);
        if(parts.length != 3) throw new IllegalArgumentException("Foreign band key is invalid");
        try
        {
            int wacn = Integer.parseInt(parts[0]);
            int system = Integer.parseInt(parts[1]);
            int band = parseBand(parts[2]);
            if(wacn >= 0 && wacn <= 1_048_575 && system >= 0 && system <= 4095)
                return new int[]{wacn, system, band};
        }
        catch(NumberFormatException ignored)
        {
        }
        throw new IllegalArgumentException("Foreign band key is invalid");
    }

    /** One process-local plan. Each writer pass commits at most this many directly changed rows. */
    static final class Batch
    {
        static final int MAXIMUM_ROWS_PER_PASS = 512;
        private final ScopedData target;
        private final Plan plan;
        private final List<BatchStep> steps;
        private final long cutoffMs;
        private final Map<String,Integer> countsByPart;
        private final long rowsTotal;
        private int cursor;
        private boolean removedOwnSnapshot;
        private ReceiverActivityMaintenance.DeletionOutcome outcome;

        private Batch(Connection connection, ScopedData target, long cutoffMs) throws SQLException
        {
            this.target = target;
            this.cutoffMs = cutoffMs;
            plan = plan(connection, target);
            outcome = plan.outcome;
            steps = new ArrayList<>();
            countsByPart = new LinkedHashMap<>();
            if(outcome != ReceiverActivityMaintenance.DeletionOutcome.DELETED)
            {
                rowsTotal = 0;
                return;
            }
            if("alias_activity".equals(target.sourceKind())) addAliasReset(plan, target, cutoffMs);
            addFinalReferences(plan, target);
            Metadata metadata = new Metadata(connection);
            Map<String,Step> expanded = new LinkedHashMap<>();
            for(Step step: plan.steps) expand(step, metadata, expanded, new LinkedHashSet<>());
            List<Step> ordered = new ArrayList<>(expanded.values());
            ordered.sort(Comparator.comparingInt((Step step) -> metadata.rank(step.table))
                .thenComparingInt(ReceiverActivityScopedDeletion::priority));
            long total = 0;
            for(Step step: ordered)
            {
                Table table = metadata.tables.get(step.table);
                Step eligible = boundedByTime(connection, step, table, cutoffMs);
                String guards = step.updateSet == null ? metadata.childGuards(table) : "";
                int rows = count(connection, step.table, eligible.predicate, eligible.parameters);
                countsByPart.merge(step.part, rows, Math::addExact);
                total += rows;
                steps.add(new BatchStep(eligible, table, guards));
            }
            for(String part: target.parts()) countsByPart.putIfAbsent(part, 0);
            rowsTotal = total;
        }

        record Position(int cursor, boolean removedOwnSnapshot, ReceiverActivityMaintenance.DeletionOutcome outcome) { }
        Position position() { return new Position(cursor, removedOwnSnapshot, outcome); }
        void restore(Position position)
        {
            cursor = position.cursor;
            removedOwnSnapshot = position.removedOwnSnapshot;
            outcome = position.outcome;
        }

        static Batch start(Connection connection, ScopedData target, long cutoffMs) throws SQLException
        {
            return new Batch(connection, target, cutoffMs);
        }

        long rowsTotal() { return rowsTotal; }
        long cutoffMs() { return cutoffMs; }
        ReceiverActivityMaintenance.DeletionOutcome outcome() { return outcome; }
        boolean done() { return outcome != ReceiverActivityMaintenance.DeletionOutcome.DELETED || cursor >= steps.size(); }

        Preview preview()
        {
            List<String> effects = new ArrayList<>(plan.effects);
            if(outcome == ReceiverActivityMaintenance.DeletionOutcome.DELETED)
            {
                effects.add("Cleanup runs in small background batches. New observations and active history buckets are kept.");
                if(target.frequencyHz() != null && "hourly_history".equals(target.dataType()))
                    effects.add("Frequency filtering omits hourly totals without frequency attribution.");
            }
            return new Preview(outcome, Map.copyOf(countsByPart), Math.toIntExact(rowsTotal), List.copyOf(effects));
        }

        /** Called inside one writer transaction. Cursor changes only after successful statements. */
        int runPass(Connection connection) throws SQLException
        {
            if(done()) return 0;
            ReceiverActivityMaintenance.DeletionOutcome valid = revalidate(connection);
            if(valid != ReceiverActivityMaintenance.DeletionOutcome.DELETED)
            {
                outcome = valid;
                return 0;
            }
            int remaining = MAXIMUM_ROWS_PER_PASS;
            int changed = 0;
            int nextCursor = cursor;
            while(remaining > 0 && nextCursor < steps.size())
            {
                BatchStep step = steps.get(nextCursor);
                int rows = step.change(connection, remaining);
                changed = Math.addExact(changed, rows);
                remaining -= rows;
                if(rows > 0 && (step.step.table.equals("p25_site_snapshot") ||
                    step.step.table.equals("trunked_site_snapshot"))) removedOwnSnapshot = true;
                if(rows < remaining + rows) nextCursor++;
                else break;
            }
            cursor = nextCursor;
            return changed;
        }

        private ReceiverActivityMaintenance.DeletionOutcome revalidate(Connection connection) throws SQLException
        {
            if("alias_activity".equals(target.sourceKind()))
            {
                if(target.recordKey() == null || scalar(connection, "SELECT id FROM alias WHERE id=?",
                    List.of(parsePositive(target.recordKey()))) > 0)
                    return ReceiverActivityMaintenance.DeletionOutcome.DELETED;
                return ReceiverActivityMaintenance.DeletionOutcome.NOT_FOUND;
            }
            Scope current = resolve(connection, target);
            if(current == null || current.systemId != plan.scope.systemId || current.channelId != plan.scope.channelId)
                return ReceiverActivityMaintenance.DeletionOutcome.NOT_FOUND;
            if(!current.stale) return ReceiverActivityMaintenance.DeletionOutcome.DELETED;
            if(removedOwnSnapshot)
            {
                ReceiverActivityDeletion.SavedChannel channel =
                    ReceiverActivityDeletion.savedChannel(connection, target.siteConfigurationId());
                if(channel != null && channel.siteKey().startsWith("trunked-unsited:"))
                    return ReceiverActivityMaintenance.DeletionOutcome.DELETED;
            }
            return ReceiverActivityMaintenance.DeletionOutcome.STALE_SITE;
        }

        private static void addAliasReset(Plan plan, ScopedData target, long cutoffMs)
        {
            String reset = "metrics_state=CASE WHEN protocol_code=0 THEN 'unsupported' ELSE 'not_collected' END," +
                "logical_call_count=NULL,recorded_logical_call_count=NULL,stream_submitted_logical_call_count=NULL," +
                "encrypted_logical_call_count=NULL,grant_observation_count=NULL,join_observation_count=NULL," +
                "emergency_observation_count=NULL,register_observation_count=NULL,logout_observation_count=NULL," +
                "denial_observation_count=NULL,data_observation_count=NULL,other_signaling_observation_count=NULL," +
                "signaling_observation_count=NULL,first_evidence_ms=NULL,last_evidence_ms=NULL," +
                "updated_at_ms=" + Math.max(1, cutoffMs + 1);
            plan.add("summary", "alias_activity_summary", target.recordKey() == null ? "1=1" : "alias_id=?",
                target.recordKey() == null ? List.of() : List.of(parsePositive(target.recordKey())), null, List.of(), reset);
        }

        private static void addFinalReferences(Plan plan, ScopedData target)
        {
            if(plan.scope == null) return;
            if("frequencies".equals(target.dataType()) && target.recordKey() != null && has(target, "current"))
            {
                long hz = parsePositive(target.recordKey());
                String set = "primary_frequency_hz=CASE WHEN primary_frequency_hz=" + hz +
                    " THEN NULL ELSE primary_frequency_hz END,current_control_hz=CASE WHEN current_control_hz=" + hz +
                    " THEN NULL ELSE current_control_hz END";
                for(String table: List.of("p25_site_snapshot", "trunked_site_snapshot"))
                    plan.updateChannel("current", table, set, "primary_frequency_hz=" + hz + " OR current_control_hz=" + hz);
            }
            if(plan.scope.conventional && has(target, "summary") &&
                Set.of("radios", "talkgroups").contains(target.dataType()))
            {
                String suffix = "";
                String[] key = target.recordKey() == null ? null : target.recordKey().split(":", -1);
                if(key != null) suffix = " AND frequency_hz=" + parsePositive(key[0]) +
                    " AND timeslot=" + parseRange(key[1], 1, 2);
                if("radios".equals(target.dataType()))
                {
                    for(String[] reference: List.of(new String[]{"dmr_conventional_talkgroup_summary", "last_source_radio_id"},
                        new String[]{"dmr_conventional_radio_summary", "last_peer_radio_id"}))
                        plan.updateChannel("summary", reference[0], reference[1] + "=NULL", reference[1] +
                            (key == null ? " IS NOT NULL" : "=" + parsePositive(key[2])) + suffix);
                }
                else plan.updateChannel("summary", "dmr_conventional_radio_summary", "last_talkgroup_id=NULL",
                    "last_talkgroup_id" + (key == null ? " IS NOT NULL" : "=" + parsePositive(key[2])) + suffix);
            }
        }

        private static void expand(Step step, Metadata metadata, Map<String,Step> expanded, Set<String> ancestry)
        {
            if(!ancestry.add(step.table)) throw new IllegalArgumentException("Cyclic retained-data ownership");
            String key = step.table + "|" + step.updateSet;
            Step existing = expanded.get(key);
            if(existing == null) expanded.put(key, step);
            else if(!existing.predicate.equals(step.predicate) || !existing.parameters.equals(step.parameters))
            {
                List<Object> arguments = new ArrayList<>(existing.parameters);
                arguments.addAll(step.parameters);
                expanded.put(key, new Step(existing.part, existing.table,
                    "(" + existing.predicate + ") OR (" + step.predicate + ")", List.copyOf(arguments), step.updateSet));
            }
            if(step.updateSet == null)
            {
                for(ForeignKey child: metadata.children.getOrDefault(step.table, List.of()))
                {
                    String match = child.match(step.table);
                    Step descendant = new Step(partFor(child.child), child.child,
                        "EXISTS (SELECT 1 FROM " + step.table + " WHERE (" + step.predicate + ") AND " + match + ")",
                        step.parameters, null);
                    expand(descendant, metadata, expanded, new LinkedHashSet<>(ancestry));
                }
            }
        }

        private static String partFor(String table)
        {
            if(table.equals("receiver_activity_event") || table.equals("activity_event_identity_member")) return "events";
            if(table.endsWith("_bucket") || table.equals("trunked_control_channel_quality")) return "buckets";
            if(table.endsWith("_summary")) return "summary";
            return "current";
        }

        private static Step boundedByTime(Connection connection, Step step, Table table, long cutoffMs) throws SQLException
        {
            String predicate = "(" + step.predicate + ")";
            List<Object> arguments = new ArrayList<>(step.parameters);
            if(table.columns.contains("bucket_start_ms"))
            {
                long interval = table.name.equals("trunked_control_channel_quality") ? 10_000 : 3_600_000;
                predicate += " AND bucket_start_ms<?";
                arguments.add(cutoffMs - Math.floorMod(cutoffMs, interval));
            }
            for(String time: List.of("last_seen_ms", "confirmed_at_ms", "observed_at_ms", "cleared_at_ms", "updated_at_ms"))
            {
                if(table.columns.contains(time))
                {
                    predicate += " AND " + time + "<=?";
                    arguments.add(cutoffMs);
                    break;
                }
            }
            if(table.name.equals("receiver_activity_event"))
            {
                predicate += " AND id<=?";
                arguments.add(scalar(connection, "SELECT coalesce(max(id),0) FROM receiver_activity_event", List.of()));
            }
            // Member rows have no timestamp; bind their ownership to the same fixed event cutoff.
            if(table.name.equals("activity_event_identity_member"))
            {
                predicate += " AND event_id IN (SELECT id FROM receiver_activity_event WHERE observed_at_ms<=? AND id<=?)";
                arguments.add(cutoffMs);
                arguments.add(scalar(connection, "SELECT coalesce(max(id),0) FROM receiver_activity_event", List.of()));
            }
            return new Step(step.part, step.table, predicate, List.copyOf(arguments), step.updateSet);
        }
    }

    private record BatchStep(Step step, Table table, String guards)
    {
        int change(Connection connection, int limit) throws SQLException
        {
            String keys = String.join(",", table.primaryKey);
            String tuple = table.primaryKey.size() == 1 ? keys : "(" + keys + ")";
            String statement = (step.updateSet == null ? "DELETE FROM " + step.table :
                "UPDATE " + step.table + " SET " + step.updateSet) + " WHERE " + tuple + " IN (SELECT " + keys +
                " FROM " + step.table + " WHERE (" + step.predicate + ")" + guards +
                " ORDER BY " + keys + " LIMIT ?)";
            try(PreparedStatement sql = connection.prepareStatement(statement))
            {
                bind(sql, step.parameters);
                sql.setInt(step.parameters.size() + 1, limit);
                return sql.executeUpdate();
            }
        }
    }

    private record Table(String name, Set<String> columns, List<String> primaryKey) { }
    private record ForeignKey(String child, String parent, List<String> from, List<String> to)
    {
        String match(String parentTable)
        {
            List<String> conditions = new ArrayList<>();
            for(int i = 0; i < from.size(); i++) conditions.add(child + "." + from.get(i) + "=" + parentTable + "." + to.get(i));
            return String.join(" AND ", conditions);
        }
    }

    /** Reads only the already-validated schema; no indexes, tables, or persisted job state are added. */
    private static final class Metadata
    {
        private final Map<String,Table> tables = new LinkedHashMap<>();
        private final Map<String,List<ForeignKey>> children = new LinkedHashMap<>();

        private Metadata(Connection connection) throws SQLException
        {
            List<String> names = new ArrayList<>();
            try(var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND (name LIKE 'p25_%' OR name LIKE 'trunked_%' " +
                    "OR name LIKE 'conventional_%' OR name LIKE 'dmr_conventional_%' OR name LIKE 'radio_system%' " +
                    "OR name IN ('receiver_channel','receiver_activity_event','activity_event_identity_member','alias_activity_summary'))"))
            {
                while(rows.next()) names.add(rows.getString(1));
            }
            for(String name: names)
            {
                Set<String> columns = new LinkedHashSet<>();
                Map<Integer,String> primaryKey = new java.util.TreeMap<>();
                try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA table_info(" + name + ")"))
                {
                    while(rows.next())
                    {
                        columns.add(rows.getString("name"));
                        if(rows.getInt("pk") > 0) primaryKey.put(rows.getInt("pk"), rows.getString("name"));
                    }
                }
                if(primaryKey.isEmpty()) throw new SQLException("Retained-data table has no bounded primary key: " + name);
                tables.put(name, new Table(name, Set.copyOf(columns), List.copyOf(primaryKey.values())));
            }
            for(String name: names)
            {
                Map<Integer,List<String>> from = new LinkedHashMap<>();
                Map<Integer,List<String>> to = new LinkedHashMap<>();
                Map<Integer,String> parents = new LinkedHashMap<>();
                try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA foreign_key_list(" + name + ")"))
                {
                    while(rows.next())
                    {
                        String parent = rows.getString("table");
                        if(!tables.containsKey(parent) || !"CASCADE".equalsIgnoreCase(rows.getString("on_delete"))) continue;
                        int id = rows.getInt("id");
                        parents.put(id, parent);
                        from.computeIfAbsent(id, ignored -> new ArrayList<>()).add(rows.getString("from"));
                        to.computeIfAbsent(id, ignored -> new ArrayList<>()).add(rows.getString("to"));
                    }
                }
                for(var parent: parents.entrySet()) children.computeIfAbsent(parent.getValue(), ignored -> new ArrayList<>())
                    .add(new ForeignKey(name, parent.getValue(), List.copyOf(from.get(parent.getKey())), List.copyOf(to.get(parent.getKey()))));
            }
        }

        private int rank(String table)
        {
            int rank = 0;
            for(ForeignKey child: children.getOrDefault(table, List.of())) rank = Math.max(rank, rank(child.child) + 1);
            return rank;
        }

        private String childGuards(Table table)
        {
            StringBuilder guards = new StringBuilder();
            for(ForeignKey child: children.getOrDefault(table.name, List.of())) guards.append(" AND NOT EXISTS (SELECT 1 FROM ")
                .append(child.child).append(" WHERE ").append(child.match(table.name)).append(')');
            return guards.toString();
        }
    }

    private record Scope(long systemId, long channelId, int protocol, boolean conventional,
                         boolean systemWide, boolean stale)
    {
        String channelPredicate()
        {
            return systemWide ? "channel_id IN (SELECT id FROM receiver_channel WHERE radio_system_id=? OR " +
                "configuration_id=(SELECT configuration_id FROM radio_system WHERE id=?))" : "channel_id=?";
        }

        List<Object> channelArguments()
        {
            return systemWide ? List.of(systemId, systemId) : List.of(channelId);
        }
    }

    private record Step(String part, String table, String predicate, List<Object> parameters, String updateSet)
    {
    }

    private static final class Plan
    {
        private final Connection connection;
        private final Scope scope;
        private final ReceiverActivityMaintenance.DeletionOutcome outcome;
        private final List<Step> steps = new ArrayList<>();
        private final Set<String> keys = new LinkedHashSet<>();
        private final List<String> effects = new ArrayList<>();

        private Plan(Scope scope)
        {
            this(null, scope, ReceiverActivityMaintenance.DeletionOutcome.DELETED);
        }

        private Plan(Connection connection, Scope scope)
        {
            this(connection, scope, ReceiverActivityMaintenance.DeletionOutcome.DELETED);
        }

        private Plan(Connection connection, Scope scope, ReceiverActivityMaintenance.DeletionOutcome outcome)
        {
            this.connection = connection;
            this.scope = scope;
            this.outcome = outcome;
        }

        static Plan missing()
        {
            return new Plan(null, null, ReceiverActivityMaintenance.DeletionOutcome.NOT_FOUND);
        }

        static Plan staleSite()
        {
            return new Plan(null, null, ReceiverActivityMaintenance.DeletionOutcome.STALE_SITE);
        }

        void channel(String part, String table)
        {
            channel(part, table, null, List.of());
        }

        void channel(String part, String table, String extra, List<Object> extraArguments)
        {
            add(part, table, scope.channelPredicate(), scope.channelArguments(), extra, extraArguments);
        }

        void system(String part, String table)
        {
            system(part, table, null, List.of());
        }

        void system(String part, String table, String extra, List<Object> extraArguments)
        {
            if(scope.systemWide) add(part, table, "radio_system_id=?", List.of(scope.systemId),
                extra, extraArguments);
        }

        void event(String part, String table)
        {
            event(part, table, null, List.of());
        }

        void event(String part, String table, String extra, List<Object> extraArguments)
        {
            if(scope.systemWide)
                add(part, table, "radio_system_id=?", List.of(scope.systemId), extra, extraArguments);
            else if(scope.conventional) channel(part, table, extra, extraArguments);
            else add(part, table, "channel_id=? AND radio_system_id=?",
                    List.of(scope.channelId, scope.systemId), extra, extraArguments);
        }

        void systemOwnedChannel(String part, String table)
        {
            if(scope.systemWide) system(part, table);
            else if(scope.conventional) channel(part, table);
            else add(part, table, "channel_id=? AND radio_system_id=?",
                    List.of(scope.channelId, scope.systemId), null, List.of());
        }

        void eventMembers(String part)
        {
            String predicate = scope.systemWide ?
                "event_id IN (SELECT id FROM receiver_activity_event WHERE radio_system_id=?)" :
                scope.conventional ?
                    "event_id IN (SELECT id FROM receiver_activity_event WHERE channel_id=?)" :
                    "event_id IN (SELECT id FROM receiver_activity_event WHERE channel_id=? " +
                        "AND radio_system_id=?)";
            add(part, "activity_event_identity_member", predicate,
                scope.systemWide ? List.of(scope.systemId) : scope.conventional ? List.of(scope.channelId) :
                    List.of(scope.channelId, scope.systemId), null, List.of());
        }

        void add(String part, String table, String owner, List<Object> ownerArguments,
                 String extra, List<Object> extraArguments)
        {
            add(part, table, owner, ownerArguments, extra, extraArguments, null);
        }

        void updateChannel(String part, String table, String set, String condition)
        {
            add(part, table, scope.channelPredicate(), scope.channelArguments(), condition, List.of(), set);
        }

        void updateSystem(String part, String table, String set, String condition)
        {
            if(scope.systemWide)
                add(part, table, "radio_system_id=?", List.of(scope.systemId), condition, List.of(), set);
        }

        void add(String part, String table, String owner, List<Object> ownerArguments,
                 String extra, List<Object> extraArguments, String updateSet)
        {
            String predicate = owner + (extra == null ? "" : " AND (" + extra + ")");
            List<Object> arguments = new ArrayList<>(ownerArguments);
            arguments.addAll(extraArguments);
            String key = table + "|" + predicate + "|" + arguments + "|" + updateSet;
            if(keys.add(key)) steps.add(new Step(part, table, predicate, List.copyOf(arguments), updateSet));
        }
    }
}
