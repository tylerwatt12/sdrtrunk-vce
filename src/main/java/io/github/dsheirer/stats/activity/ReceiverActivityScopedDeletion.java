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
        "logical_call_count=0,source_logical_call_count=0,target_logical_call_count=0," +
            "encrypted_logical_call_count=0,recorded_output_count=0,streamed_output_count=0";
    private static final String IDENTITY_CALL_MATCH =
        "logical_call_count>0 OR source_logical_call_count>0 OR " +
            "target_logical_call_count>0 OR encrypted_logical_call_count>0 OR " +
            "recorded_output_count>0 OR streamed_output_count>0";
    private static final String GROUP_CALL_RESET =
        "logical_call_count=0,encrypted_logical_call_count=0," +
            "recorded_output_count=0,streamed_output_count=0";
    private static final String GROUP_CALL_MATCH =
        "logical_call_count>0 OR encrypted_logical_call_count>0 OR " +
            "recorded_output_count>0 OR streamed_output_count>0";
    private static final String CURRENT_ALIAS_PROTOCOL = """
        CASE WHEN matcher_type='P25_SUBSCRIBER_IDENTITY' THEN 1
            WHEN matcher_type IN ('TALKGROUP','TALKGROUP_RANGE','RADIO_ID','RADIO_ID_RANGE')
            THEN CASE protocol WHEN 'APCO25' THEN 1 WHEN 'APCO25_PHASE2' THEN 1 WHEN 'DMR' THEN 3
                WHEN 'NXDN' THEN 4
                WHEN 'NBFM' THEN CASE WHEN matcher_type IN ('TALKGROUP','TALKGROUP_RANGE') THEN 10 ELSE 0 END
                WHEN 'AM' THEN CASE WHEN matcher_type IN ('TALKGROUP','TALKGROUP_RANGE') THEN 11 ELSE 0 END
                ELSE 0 END ELSE 0 END
        """.strip();
    private static final List<String> SIGNALING_COLUMNS = ReceiverActivityCodes.actionCodes().stream()
        .filter(action -> action != ReceiverActivityRecords.Action.CALL && action != ReceiverActivityRecords.Action.TONE)
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
        Batch batch = Batch.start(connection, target, System.currentTimeMillis());
        if(batch.outcome() == ReceiverActivityMaintenance.DeletionOutcome.NOT_FOUND)
            return ReceiverActivityDeletion.Result.missing();
        if(batch.outcome() == ReceiverActivityMaintenance.DeletionOutcome.STALE_SITE)
            return ReceiverActivityDeletion.Result.staleSite();
        int removed = 0;
        while(!batch.done()) removed = Math.addExact(removed, batch.runPass(connection));
        return ReceiverActivityDeletion.Result.deleted(removed);
    }

    private static Plan plan(Connection connection, ScopedData target) throws SQLException
    {
        if("alias_activity".equals(target.sourceKind()))
        {
            if(target.recordKey() != null)
            {
                long id = parsePositive(target.recordKey());
                if(!aliasActivityExists(connection, id))
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
            plan.effects.add("Matching detailed events also remove their linked identity members and tone sequences.");
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
            addAnalogSummary(plan, target);
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
                if(!has(target, "events"))
                    for(String reference: List.of("source_identity_summary_id", "target_identity_summary_id"))
                        plan.add("events", "receiver_activity_event", reference +
                            " IN (SELECT id FROM radio_system_identity_summary WHERE radio_system_id=?)",
                            List.of(scope.systemId), null, List.of());
                if(scope.protocol == 1) addIssiAssignmentHistory(plan);
                plan.effects.add("Deleting system identity summaries also removes linked identity history, " +
                    "detailed events, and radio relationships through database foreign keys.");
            }
            else if(scope.protocol == 1)
                plan.effects.add("System-wide ISSI assignment history remains because its observations can span several sites.");
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

    private static void addIssiAssignmentHistory(Plan plan)
    {
        plan.system("summary", "p25_wuid_assignment_observation_summary");
        plan.effects.add("Saved Working ID assignments and their observation counts are removed.");
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
            case "relationships", "issi_assignment_history" -> Set.of("summary");
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
        if(scope.protocol != 1 && Set.of("band_plans", "foreign_band_plans", "patches", "issi_assignment_history").contains(type))
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
            case "issi_assignment_history" ->
            {
                addIssiAssignmentHistory(plan);
                plan.effects.add("Current assignments, radio IDs and aliases remain. Receiving can create new assignment history.");
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
                    {
                        addAnalogSummary(plan, target);
                        plan.updateChannel("summary", "conventional_activity_summary",
                            CONVENTIONAL_CALL_RESET, CONVENTIONAL_CALL_MATCH);
                        // These rows retain calls only and require call_count>0; removing the row is the
                        // existing representation of an empty radio/talkgroup call summary.
                        plan.channel("summary", "dmr_conventional_radio_summary");
                        plan.channel("summary", "dmr_conventional_talkgroup_summary");
                    }
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
                if(has(target, "summary")) plan.effects.add(scope.conventional ?
                    "Call counters are reset. DMR radio and talkgroup call summaries, when present, are removed. Signaling counters remain." :
                    "Call counters are reset in place; identity names and other signaling counters remain.");
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
                    "action_code IN (" + ReceiverActivityCodes.actionCodes().stream()
                        .filter(action -> action != ReceiverActivityRecords.Action.CALL)
                        .map(action -> Integer.toString(action.code())).collect(Collectors.joining(",")) + ")", List.of());
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
                // Conventional IDs are local observations, not foreign keys to the call summaries.
                String suffix = "";
                List<Object> values = List.of();
                if(key != null)
                {
                    String[] pieces = key.split(":", -1);
                    suffix = "frequency_hz=? AND timeslot=? AND ";
                    values = List.of(parsePositive(pieces[0]), parseRange(pieces[1], 1, 2),
                        parseRange(pieces[2], 1, 16_777_215));
                }
                if(radio) plan.event("events", "receiver_activity_event", suffix +
                    (key == null ? "source_observed_local_id IS NOT NULL" : "source_observed_local_id=?"), values);
                plan.event("events", "receiver_activity_event", suffix + "target_kind_code=" + kind + " AND " +
                    (key == null ? "target_observed_local_id IS NOT NULL" : "target_observed_local_id=?"), values);
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
            Long resolvedId = RadioSystemIdentityLookup.find(plan.connection, scope.systemId, kind,
                identity.homeWacn(), identity.homeSystemId(), identity.identityId());
            summaryId = resolvedId != null ? resolvedId : 0;
            predicate = "id=?";
            args = List.of(summaryId);
        }
        if(has(target, "events") && !has(target, "summary"))
        {
            if(key == null)
            {
                if(radio) plan.event("events", "receiver_activity_event",
                    "source_identity_summary_id IN (SELECT id FROM radio_system_identity_summary " +
                        "WHERE radio_system_id=? AND identity_kind_code=2)", List.of(scope.systemId));
                plan.event("events", "receiver_activity_event",
                    "target_identity_summary_id IN (SELECT id FROM radio_system_identity_summary " +
                        "WHERE radio_system_id=? AND identity_kind_code=?)", List.of(scope.systemId, kind));
            }
            else
            {
                if(radio) plan.event("events", "receiver_activity_event", "source_identity_summary_id=?", List.of(summaryId));
                plan.event("events", "receiver_activity_event",
                    "target_identity_summary_id=?", List.of(summaryId));
            }
        }
        if(has(target, "buckets") && !has(target, "summary"))
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
            if(radio && scope.protocol == 1)
            {
                if(key == null)
                {
                    plan.system("issi_assignment_history", "p25_wuid_assignment_observation_summary",
                        "p25_subscriber_identity_id IN (SELECT p25_subscriber_identity_id " +
                            "FROM radio_system_identity_summary WHERE radio_system_id=? " +
                            "AND identity_kind_code=2 AND p25_subscriber_identity_id IS NOT NULL)",
                        List.of(scope.systemId));
                    plan.effects.add("Associated ISSI assignment history for these radios in this system is removed.");
                }
                else
                {
                    long subscriberId = scalar(plan.connection,
                        "SELECT p25_subscriber_identity_id FROM radio_system_identity_summary " +
                            "WHERE id=? AND radio_system_id=? AND identity_kind_code=2",
                        List.of(summaryId, scope.systemId));
                    if(subscriberId > 0)
                    {
                        // A local radio number is not evidence of a subscriber or a Working ID. Only the
                        // selected row's proven canonical reference owns associated assignment history.
                        plan.system("issi_assignment_history", "p25_wuid_assignment_observation_summary",
                            "p25_subscriber_identity_id=?", List.of(subscriberId));
                        plan.effects.add("Associated ISSI assignment history for this radio in this system is removed.");
                    }
                }
            }
            plan.system("summary", "radio_system_identity_summary", predicate, args);
            plan.effects.add("Also deletes linked hourly history, detailed events, and radio relationships.");
        }
    }

    private static void addAnalogSummary(Plan plan, ScopedData target)
    {
        if(plan.scope.conventional && target.frequencyHz() == null && target.fromMs() == null && target.toMs() == null)
        {
            plan.add("summary", "alias_list_analog_activity_summary",
                "channel_configuration_id=(SELECT configuration_id FROM receiver_channel WHERE id=?)",
                List.of(plan.scope.channelId), null, List.of(), null);
        }
    }

    private static boolean hasAnalogBoundary(Plan plan)
    {
        return plan.scope != null && plan.scope.conventional && plan.steps.stream().anyMatch(step ->
            "alias_list_analog_activity_summary".equals(step.table));
    }

    private static int analogBoundaryRows(Connection connection, Plan plan, long cutoff) throws SQLException
    {
        return hasAnalogBoundary(plan) ? count(connection, "receiver_channel",
            "id=? " +
                "AND coalesce((SELECT reset_at_ms FROM receiver_channel_statistics_reset reset " +
                "WHERE reset.configuration_id=receiver_channel.configuration_id),0)<?",
            List.of(plan.scope.channelId, Math.max(1, cutoff + 1))) : 0;
    }

    private static int resetAnalogBoundary(Connection connection, Plan plan, long cutoff) throws SQLException
    {
        if(hasAnalogBoundary(plan))
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO receiver_channel_statistics_reset(configuration_id,reset_at_ms)
                SELECT configuration_id,? FROM receiver_channel WHERE id=?
                ON CONFLICT(configuration_id) DO UPDATE SET reset_at_ms=excluded.reset_at_ms
                WHERE reset_at_ms<?
                """))
            {
                long boundary = Math.max(1, cutoff + 1);
                statement.setLong(1, boundary);
                statement.setLong(2, plan.scope.channelId);
                statement.setLong(3, boundary);
                return statement.executeUpdate();
            }
        }
        return 0;
    }

    private static void addSiteState(Plan plan)
    {
        if(plan.scope.conventional) return;
        plan.channel("current", "p25_site_snapshot");
        plan.channel("current", "trunked_site_snapshot");
        plan.effects.add("Removing saved site state also removes its frequencies, band plans, neighbors, patches, " +
            "and their summaries through database foreign keys.");
    }

    private static boolean aliasActivityExists(Connection connection, long aliasId) throws SQLException
    {
        return scalar(connection, "SELECT EXISTS(SELECT 1 FROM alias WHERE id=?) OR " +
            "EXISTS(SELECT 1 FROM alias_activity_summary WHERE alias_id=?)", List.of(aliasId, aliasId)) != 0;
    }

    private static boolean has(ScopedData target, String part)
    {
        return target.parts().contains(part);
    }

    private static int priority(Step step)
    {
        if(step.table.equals("p25_wuid_assignment_observation_summary") &&
            "last_channel_id=NULL".equals(step.updateSet)) return -4;
        return switch(step.table)
        {
            case "receiver_activity_tone" -> -3;
            case "receiver_activity_tone_sequence" -> -2;
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

    private static String lookupTable(Step step)
    {
        // Both time indexes can satisfy this order. System-first is a poor choice for one
        // subscriber in a large system, even when SQLite estimates the candidate sizes equally.
        return step.table + ("issi_assignment_history".equals(step.part) ?
            " INDEXED BY idx_p25_wuid_assignment_observation_subscriber" : "");
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
        private final String channelConfigurationId;
        private final boolean allStatistics;
        private boolean boundariesPending;
        private int cursor;
        private boolean aliasSeedsPending;
        private String aliasSeedPredicate;
        private List<Object> aliasSeedParameters = List.of();
        private boolean removedOwnSnapshot;
        private boolean removedOwnChannel;
        private ReceiverActivityMaintenance.DeletionOutcome outcome;

        private Batch(Connection connection, ScopedData target, long cutoffMs) throws SQLException
        {
            this(connection, target, plan(connection, target), cutoffMs, null, false);
        }

        private Batch(Connection connection, ScopedData target, Plan selectedPlan, long cutoffMs,
                      String channelConfigurationId, boolean allStatistics) throws SQLException
        {
            this.target = target;
            this.cutoffMs = cutoffMs;
            this.channelConfigurationId = channelConfigurationId;
            this.allStatistics = allStatistics;
            plan = selectedPlan;
            outcome = plan.outcome;
            steps = new ArrayList<>();
            countsByPart = new LinkedHashMap<>();
            if(outcome != ReceiverActivityMaintenance.DeletionOutcome.DELETED)
            {
                rowsTotal = 0;
                return;
            }
            boolean resetAliases = allStatistics || target != null && "alias_activity".equals(target.sourceKind());
            ScopedData aliasTarget = allStatistics ? new ScopedData("alias_activity", null, null, null,
                "alias_activity", null, List.of("summary")) : target;
            if(resetAliases) addAliasReset(plan, aliasTarget, cutoffMs);
            if(target != null) addFinalReferences(plan, target);
            Metadata metadata = new Metadata(connection);
            Map<String,Step> expanded = new LinkedHashMap<>();
            boolean systemIdentityEventsCovered = target != null && "all".equals(target.dataType()) &&
                plan.scope != null && plan.scope.systemWide && has(target, "summary");
            for(Step step: plan.steps)
            {
                // Every channel-owned table is already selected directly below; event-only children
                // are selected through their indexed event FKs. Do not repeat the owner subtree.
                if(allStatistics || channelConfigurationId != null && step.table.equals("receiver_channel"))
                    expanded.put(step.table + "|" + step.updateSet, step);
                else expand(step, metadata, expanded, new LinkedHashSet<>(), systemIdentityEventsCovered);
            }
            List<Step> ordered = new ArrayList<>(expanded.values());
            boolean completeOwnerReset = allStatistics || channelConfigurationId != null;
            ordered.sort(Comparator.comparingInt((Step step) -> metadata.rank(step.table, completeOwnerReset))
                .thenComparingInt(ReceiverActivityScopedDeletion::priority));
            int boundaryRows = channelConfigurationId != null || allStatistics ? boundaryRows(connection) :
                analogBoundaryRows(connection, plan, cutoffMs);
            boundariesPending = (channelConfigurationId != null || allStatistics) && boundaryRows > 0;
            if(resetAliases)
            {
                aliasSeedPredicate = "configured.id<=? AND NOT EXISTS(SELECT 1 FROM alias_activity_summary " +
                    "WHERE alias_id=configured.id)";
                aliasSeedParameters = new ArrayList<>(List.of(scalar(connection,
                    "SELECT coalesce(max(id),0) FROM alias", List.of())));
                if(aliasTarget.recordKey() != null)
                {
                    aliasSeedPredicate += " AND configured.id=?";
                    aliasSeedParameters.add(parsePositive(aliasTarget.recordKey()));
                }
                int missing = count(connection, "alias configured", aliasSeedPredicate, aliasSeedParameters);
                aliasSeedsPending = missing > 0;
                boundaryRows += missing;
            }
            if(boundaryRows > 0) countsByPart.put("summary", boundaryRows);
            long total = boundaryRows;
            long maximumEventId = scalar(connection, "SELECT coalesce(max(id),0) FROM receiver_activity_event", List.of());
            Map<String,List<BatchStep>> counted = new LinkedHashMap<>();
            Map<String,Boolean> populated = new LinkedHashMap<>();
            Set<String> unrelatedChildren = channelConfigurationId == null ? Set.of() :
                smallUnrelatedChannelChildren(connection, metadata, plan.scope.channelId);
            for(Step step: ordered)
            {
                Table table = metadata.tables.get(step.table);
                if(unrelatedChildren.contains(step.table)) continue;
                Boolean hasRows = populated.get(step.table);
                if(hasRows == null)
                {
                    hasRows = scalar(connection, "SELECT EXISTS(SELECT 1 FROM " + step.table + " LIMIT 1)", List.of()) != 0;
                    populated.put(step.table, hasRows);
                }
                if(!hasRows)
                {
                    countsByPart.putIfAbsent(step.part, 0);
                    continue;
                }
                String guards = step.updateSet == null ? metadata.childGuards(table, completeOwnerReset) : "";
                BatchStep selected = new BatchStep(connection, step, table, guards, metadata,
                    allStatistics ? Long.MAX_VALUE : cutoffMs, maximumEventId);
                steps.add(selected);
                counted.computeIfAbsent(step.table + "|" + step.updateSet, ignored -> new ArrayList<>()).add(selected);
            }
            // Writer-owned full/channel maintenance has no blocking preflight scan. Normal previews retain exact totals.
            if(target != null)
            {
                for(List<BatchStep> selected: counted.values())
                {
                    List<Object> values = new ArrayList<>();
                    List<String> alternatives = new ArrayList<>();
                    for(BatchStep step: selected)
                    {
                        alternatives.add(step.keysQuery());
                        values.addAll(step.selection.parameters);
                        values.addAll(step.bounds.parameters);
                    }
                    try(PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM (" +
                        String.join(" UNION ", alternatives) + ")"))
                    {
                        bind(statement, values);
                        try(ResultSet rows = statement.executeQuery())
                        {
                            int count = rows.next() ? rows.getInt(1) : 0;
                            countsByPart.merge(selected.getFirst().step.part, count, Math::addExact);
                            total += count;
                            // Exact preflight has already proved this ownership set empty. New child
                            // rows remain protected by their parent's FK guard and original event fence.
                            if(count == 0) steps.removeAll(selected);
                        }
                    }
                }
            }
            for(String part: target != null ? target.parts() : List.of("current", "summary", "buckets", "events"))
                countsByPart.putIfAbsent(part, 0);
            rowsTotal = total;
        }

        record Position(int cursor, boolean aliasSeedsPending, boolean boundariesPending, boolean removedOwnSnapshot,
                        boolean removedOwnChannel,
                        List<BatchStep.Position> selections,
                        ReceiverActivityMaintenance.DeletionOutcome outcome) { }
        Position position() { return new Position(cursor, aliasSeedsPending, boundariesPending, removedOwnSnapshot,
            removedOwnChannel, steps.stream().map(BatchStep::position).toList(), outcome); }
        void restore(Position position)
        {
            cursor = position.cursor;
            aliasSeedsPending = position.aliasSeedsPending;
            boundariesPending = position.boundariesPending;
            removedOwnSnapshot = position.removedOwnSnapshot;
            removedOwnChannel = position.removedOwnChannel;
            for(int index = 0; index < steps.size(); index++) steps.get(index).restore(position.selections.get(index));
            outcome = position.outcome;
        }

        static Batch start(Connection connection, ScopedData target, long cutoffMs) throws SQLException
        {
            return new Batch(connection, target, cutoffMs);
        }

        /** Clears one retained channel owner without scanning or deleting configuration. */
        static Batch startChannel(Connection connection, String configurationId, long cutoffMs) throws SQLException
        {
            if(configurationId == null || configurationId.isBlank())
                throw new IllegalArgumentException("Channel configuration ID is required");
            io.github.dsheirer.stats.StatsHistoryContext.installSubscriberContext(connection);
            long channelId = scalar(connection, "SELECT coalesce(max(id),0) FROM receiver_channel WHERE configuration_id=?",
                List.of(configurationId));
            if(channelId == 0 && scalar(connection, "SELECT EXISTS(SELECT 1 FROM configured_channel WHERE configuration_id=?) " +
                "OR EXISTS(SELECT 1 FROM radio_system WHERE configuration_id=?)", List.of(configurationId, configurationId)) == 0)
                return new Batch(connection, null, Plan.missing(), cutoffMs, configurationId, false);
            Plan plan = new Plan(connection, new Scope(0, channelId, 0, false, false, false));
            Metadata metadata = new Metadata(connection);
            for(Table table: metadata.tables.values())
            {
                // Members have no channel-leading index. Their event FK selects exactly the same
                // channel through the event_id primary key, without scanning other channels' members.
                if(table.columns.contains("channel_id") && !table.name.equals("activity_event_identity_member"))
                    plan.add(partFor(table.name), table.name, "channel_id=?", List.of(channelId), null, List.of());
            }
            plan.add("summary", "alias_list_analog_activity_summary", "channel_configuration_id=?",
                List.of(configurationId), null, List.of());
            //Keep system-wide assignment history, but detach old channel references explicitly so the
            //owner DELETE cannot perform thousands of implicit ON DELETE SET NULL writes in one pass.
            plan.add("summary", "p25_wuid_assignment_observation_summary", "last_channel_id=?",
                List.of(channelId), null, List.of(), "last_channel_id=NULL");
            plan.add("current", "receiver_channel", "id=?", List.of(channelId), null, List.of());
            // A channel-scoped fallback system belongs to this configuration; native shared systems have no such owner.
            plan.add("summary", "radio_system", "configuration_id=?", List.of(configurationId), null, List.of());
            return new Batch(connection, null, plan, cutoffMs, configurationId, false);
        }

        /** A tiny unrelated child set can be disproved without walking a large channel's event history. */
        private static Set<String> smallUnrelatedChannelChildren(Connection connection, Metadata metadata, long channelId)
            throws SQLException
        {
            Set<String> unrelated = new LinkedHashSet<>();
            for(String table: List.of("activity_event_identity_member", "receiver_activity_tone_sequence"))
            {
                if(!metadata.tables.containsKey(table)) continue;
                long size = scalar(connection, "SELECT count(*) FROM (SELECT 1 FROM " + table +
                    " LIMIT " + (MAXIMUM_ROWS_PER_PASS + 1) + ")", List.of());
                if(size > MAXIMUM_ROWS_PER_PASS) continue;
                long matched = scalar(connection, "SELECT EXISTS(SELECT 1 FROM (SELECT event_id FROM " + table +
                    " LIMIT " + (MAXIMUM_ROWS_PER_PASS + 1) + ") AS small CROSS JOIN receiver_activity_event AS owner " +
                    "NOT INDEXED WHERE owner.id=small.event_id AND owner.channel_id=?)", List.of(channelId));
                if(matched == 0)
                {
                    unrelated.add(table);
                    if(table.equals("receiver_activity_tone_sequence")) unrelated.add("receiver_activity_tone");
                }
            }
            return unrelated;
        }

        /** The caller pauses statistics admission and drains old observations before capturing this cutoff. */
        static Batch startAll(Connection connection, long cutoffMs) throws SQLException
        {
            io.github.dsheirer.stats.StatsHistoryContext.installSubscriberContext(connection);
            Plan plan = new Plan(connection, null);
            Metadata metadata = new Metadata(connection);
            for(Table table: metadata.tables.values())
            {
                if(table.name.equals("alias_activity_summary")) continue;
                String predicate = "1=1";
                List<Object> parameters = List.of();
                if(table.name.equals("receiver_channel_statistics_reset"))
                {
                    predicate = "NOT EXISTS(SELECT 1 FROM configured_channel configured WHERE " +
                        "configured.configuration_id=receiver_channel_statistics_reset.configuration_id)";
                }
                else if(table.name.equals("p25_subscriber_identity"))
                {
                    String configured = "SELECT 1 FROM configured_p25_subscriber_identity configured ";
                    if(!io.github.dsheirer.stats.StatsHistoryContext.configurationAttached(connection))
                        configured += "JOIN alias_p25_subscriber_identity alias_identity " +
                            "ON alias_identity.p25_subscriber_identity_id=configured.id ";
                    predicate = "NOT EXISTS(" + configured + "WHERE configured.home_wacn=p25_subscriber_identity.home_wacn " +
                        "AND configured.home_system_id=p25_subscriber_identity.home_system_id " +
                        "AND configured.subscriber_id=p25_subscriber_identity.subscriber_id)";
                }
                plan.add(partFor(table.name), table.name, predicate, parameters, null, List.of());
            }
            return new Batch(connection, null, plan, cutoffMs, null, true);
        }

        long rowsTotal() { return rowsTotal; }
        long cutoffMs() { return cutoffMs; }
        ReceiverActivityMaintenance.DeletionOutcome outcome() { return outcome; }
        boolean done() { return outcome != ReceiverActivityMaintenance.DeletionOutcome.DELETED ||
            !boundariesPending && !aliasSeedsPending && cursor >= steps.size(); }

        /** Only the paused, full reset may clear one complete table in its own transaction. */
        boolean fastResetTablePass()
        {
            return allStatistics && !done() && !boundariesPending && !aliasSeedsPending && cursor < steps.size() &&
                steps.get(cursor).step.updateSet == null && steps.get(cursor).step.predicate.equals("1=1");
        }

        Preview preview()
        {
            List<String> effects = new ArrayList<>(plan.effects);
            if(outcome == ReceiverActivityMaintenance.DeletionOutcome.DELETED)
            {
                effects.add("Cleanup runs in small background batches. New observations and active history buckets are kept.");
                if(target != null && target.frequencyHz() != null && "hourly_history".equals(target.dataType()))
                    effects.add("Frequency filtering omits hourly totals without frequency attribution.");
            }
            return new Preview(outcome, Map.copyOf(countsByPart), Math.toIntExact(rowsTotal), List.copyOf(effects));
        }

        /** Called inside one writer transaction. Cursor changes only after successful statements. */
        int runPass(Connection connection) throws SQLException
        {
            return runPass(connection, false);
        }

        int runPass(Connection connection, boolean clearWholeTable) throws SQLException
        {
            if(clearWholeTable)
            {
                if(!fastResetTablePass()) throw new SQLException("A complete table was not selected for full reset");
                if(connection.getAutoCommit()) throw new SQLException("Full table reset requires an owned transaction");
                if(scalar(connection, "PRAGMA foreign_keys", List.of()) != 0)
                    throw new SQLException("Full table reset requires foreign keys disabled before its transaction");
                if(scalar(connection, "PRAGMA secure_delete", List.of()) != 0)
                    throw new SQLException("Full table reset requires the existing secure-delete setting to be off");
                int changed;
                try(var statement = connection.createStatement())
                {
                    changed = statement.executeUpdate("DELETE FROM " + steps.get(cursor).table.name);
                }
                cursor++;
                return changed;
            }
            if(done()) return 0;
            ReceiverActivityMaintenance.DeletionOutcome valid = revalidate(connection);
            if(valid != ReceiverActivityMaintenance.DeletionOutcome.DELETED)
            {
                outcome = valid;
                return 0;
            }
            // Advancing the late-observation fence is one real row change within the same writer budget.
            int changed = boundariesPending ? writeBoundaries(connection, MAXIMUM_ROWS_PER_PASS) :
                resetAnalogBoundary(connection, plan, cutoffMs);
            if(aliasSeedsPending)
            {
                int capacity = MAXIMUM_ROWS_PER_PASS - changed;
                int seeded = seedMissingAliases(connection, capacity);
                changed += seeded;
                aliasSeedsPending = seeded == capacity;
            }
            int remaining = MAXIMUM_ROWS_PER_PASS - changed;
            int nextCursor = cursor;
            while(remaining > 0 && nextCursor < steps.size())
            {
                BatchStep step = steps.get(nextCursor);
                BatchStep.Pass pass = step.change(connection, remaining);
                int rows = pass.changed;
                changed = Math.addExact(changed, rows);
                remaining -= pass.scanned;
                if(rows > 0 && (step.step.table.equals("p25_site_snapshot") ||
                    step.step.table.equals("trunked_site_snapshot"))) removedOwnSnapshot = true;
                if(rows > 0 && step.step.table.equals("receiver_channel")) removedOwnChannel = true;
                if(pass.done) nextCursor++;
                else break;
            }
            cursor = nextCursor;
            return changed;
        }

        private int seedMissingAliases(Connection connection, int maximumRows) throws SQLException
        {
            if(maximumRows == 0) return 0;
            long resetAt = Math.max(1, cutoffMs + 1);
            String sql = """
                INSERT INTO alias_activity_summary(alias_id,alias_list_id,protocol_code,metrics_state,
                    updated_at_ms,last_reset_ms,activity_identity,alias_name,alias_list_name)
                SELECT configured.id,configured.alias_list_id,%s,
                    CASE WHEN (%s)=0 THEN 'unsupported' ELSE 'not_collected' END,
                    ?,max(?,coalesce(configured.activity_reset_ms,0)),configured.activity_identity,
                    configured.name,coalesce(list.name,'')
                FROM alias configured JOIN alias_list list ON list.id=configured.alias_list_id
                WHERE %s ORDER BY configured.id LIMIT ?
                ON CONFLICT(alias_id) DO NOTHING
                """.formatted(CURRENT_ALIAS_PROTOCOL.replace("matcher_type", "configured.matcher_type")
                    .replace("THEN CASE protocol", "THEN CASE configured.protocol"),
                CURRENT_ALIAS_PROTOCOL.replace("matcher_type", "configured.matcher_type")
                    .replace("THEN CASE protocol", "THEN CASE configured.protocol"), aliasSeedPredicate);
            try(PreparedStatement statement = connection.prepareStatement(sql))
            {
                List<Object> parameters = new ArrayList<>(List.of(resetAt, resetAt));
                parameters.addAll(aliasSeedParameters);
                parameters.add(maximumRows);
                bind(statement, parameters);
                return statement.executeUpdate();
            }
        }

        private ReceiverActivityMaintenance.DeletionOutcome revalidate(Connection connection) throws SQLException
        {
            if(allStatistics) return ReceiverActivityMaintenance.DeletionOutcome.DELETED;
            if(channelConfigurationId != null)
            {
                long current = scalar(connection, "SELECT coalesce(max(id),0) FROM receiver_channel WHERE configuration_id=?",
                    List.of(channelConfigurationId));
                return current == plan.scope.channelId || current == 0 && removedOwnChannel ?
                    ReceiverActivityMaintenance.DeletionOutcome.DELETED :
                    ReceiverActivityMaintenance.DeletionOutcome.NOT_FOUND;
            }
            if("alias_activity".equals(target.sourceKind()))
            {
                if(target.recordKey() == null || aliasActivityExists(connection, parsePositive(target.recordKey())))
                    return ReceiverActivityMaintenance.DeletionOutcome.DELETED;
                return ReceiverActivityMaintenance.DeletionOutcome.NOT_FOUND;
            }
            Scope current = resolve(connection, target);
            if(current == null || current.systemId != plan.scope.systemId || current.channelId != plan.scope.channelId ||
                current.protocol != plan.scope.protocol)
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

        private int boundaryRows(Connection connection) throws SQLException
        {
            return count(connection, "configured_channel configured",
                "coalesce((SELECT reset_at_ms FROM receiver_channel_statistics_reset reset " +
                    "WHERE reset.configuration_id=configured.configuration_id),0)" + (allStatistics ? "<>?" : "<?") +
                    (channelConfigurationId == null ? "" : " AND configured.configuration_id=?"),
                channelConfigurationId == null ? List.of(Math.max(1, cutoffMs + 1)) :
                    List.of(Math.max(1, cutoffMs + 1), channelConfigurationId));
        }

        private int writeBoundaries(Connection connection, int maximumRows) throws SQLException
        {
            String sql = "INSERT INTO receiver_channel_statistics_reset(configuration_id,reset_at_ms) " +
                "SELECT configured.configuration_id,? FROM configured_channel configured WHERE " +
                "coalesce((SELECT reset_at_ms FROM receiver_channel_statistics_reset reset " +
                    "WHERE reset.configuration_id=configured.configuration_id),0)" + (allStatistics ? "<>?" : "<?") +
                (channelConfigurationId == null ? "" : " AND configured.configuration_id=?") +
                " ORDER BY configured.configuration_id LIMIT ? ON CONFLICT(configuration_id) " +
                "DO UPDATE SET reset_at_ms=excluded.reset_at_ms";
            try(PreparedStatement statement = connection.prepareStatement(sql))
            {
                long boundary = Math.max(1, cutoffMs + 1);
                List<Object> values = new ArrayList<>(List.of(boundary, boundary));
                if(channelConfigurationId != null) values.add(channelConfigurationId);
                values.add(maximumRows);
                bind(statement, values);
                int changed = statement.executeUpdate();
                boundariesPending = changed == maximumRows;
                return changed;
            }
        }

        private static void addAliasReset(Plan plan, ScopedData target, long cutoffMs)
        {
            String configuredProtocol = "(SELECT " + CURRENT_ALIAS_PROTOCOL + " FROM alias WHERE id=alias_id)";
            String reset = "activity_identity=(SELECT activity_identity FROM alias WHERE id=alias_id)," +
                "alias_list_id=(SELECT alias_list_id FROM alias WHERE id=alias_id)," +
                "alias_name=(SELECT name FROM alias WHERE id=alias_id)," +
                "alias_list_name=(SELECT list.name FROM alias definition JOIN alias_list list " +
                    "ON list.id=definition.alias_list_id WHERE definition.id=alias_id)," +
                "protocol_code=" + configuredProtocol + ",metrics_state=CASE WHEN " + configuredProtocol +
                    "=0 THEN 'unsupported' ELSE 'not_collected' END," +
                "logical_call_count=NULL,recorded_logical_call_count=NULL,stream_submitted_logical_call_count=NULL," +
                "encrypted_logical_call_count=NULL,grant_observation_count=NULL,join_observation_count=NULL," +
                "emergency_observation_count=NULL,register_observation_count=NULL,logout_observation_count=NULL," +
                "denial_observation_count=NULL,data_observation_count=NULL,other_signaling_observation_count=NULL," +
                "signaling_observation_count=NULL,first_evidence_ms=NULL,last_evidence_ms=NULL," +
                "updated_at_ms=" + Math.max(1, cutoffMs + 1) + ",last_reset_ms=max(" + Math.max(1, cutoffMs + 1) +
                    ",coalesce((SELECT activity_reset_ms FROM alias WHERE id=alias_id),0))";
            String selected = target.recordKey() == null ? "1=1" : "alias_id=?";
            List<Object> parameters = target.recordKey() == null ? List.of() : List.of(parsePositive(target.recordKey()));
            String configured = "EXISTS(SELECT 1 FROM alias WHERE id=alias_id)";
            plan.add("summary", "alias_activity_summary", selected + " AND NOT " + configured,
                parameters, null, List.of(), null);
            plan.add("summary", "alias_activity_summary", selected + " AND " + configured,
                parameters, null, List.of(), reset);
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

        private static void expand(Step step, Metadata metadata, Map<String,Step> expanded, Set<String> ancestry,
                                   boolean systemIdentityEventsCovered)
        {
            if(!ancestry.add(step.table)) throw new IllegalArgumentException("Cyclic retained-data ownership");
            String key = step.table + "|" + step.updateSet + "|" + step.owner() + "|" + step.ownership;
            if(expanded.putIfAbsent(key, step) != null) return;
            if(step.updateSet == null)
            {
                for(ForeignKey child: metadata.children.getOrDefault(step.table, List.of()))
                {
                    // All system identities own exactly the system events with either identity reference set.
                    // Select that event set once instead of repeating it for every source and target identity.
                    if(systemIdentityEventsCovered && step.table.equals("radio_system_identity_summary") &&
                        child.child.equals("receiver_activity_event")) continue;
                    // Every member in this system is already selected by its own identity FK, including
                    // members attached to events whose source and target references are both unknown.
                    if(systemIdentityEventsCovered && step.table.equals("receiver_activity_event") &&
                        child.child.equals("activity_event_identity_member")) continue;
                    if(!step.ownership.isEmpty() && metadata.directOwnershipCovers(step.ownership.getLast(), child))
                        continue;
                    List<ForeignKey> ownership = new ArrayList<>(step.ownership);
                    ownership.add(child);
                    Step descendant = new Step(partFor(child.child), child.child, "", step.parameters, null,
                        step.owner(), List.copyOf(ownership));
                    expand(descendant, metadata, expanded, new LinkedHashSet<>(ancestry), systemIdentityEventsCovered);
                }
            }
        }

        private static String partFor(String table)
        {
            if(table.equals("receiver_activity_event") || table.equals("activity_event_identity_member") || table.startsWith("receiver_activity_tone")) return "events";
            if(table.endsWith("_bucket") || table.equals("trunked_control_channel_quality")) return "buckets";
            if(table.endsWith("_summary")) return "summary";
            return "current";
        }

    }

    /** One indexed ownership path, scanned once with a rollback-safe access-key cursor. */
    private static final class BatchStep
    {
        private final Step step;
        private final Table table;
        private final String guards;
        private final Selection selection;
        private final Conditions bounds;
        private List<Object> cursor;
        private boolean exhausted;

        private BatchStep(Connection connection, Step step, Table table, String guards, Metadata metadata,
                          long cutoff, long maximumEventId) throws SQLException
        {
            this.step = step;
            this.table = table;
            this.guards = guards;
            selection = new Selection(connection, step, metadata, cutoff, maximumEventId);
            bounds = rowBounds(table, cutoff, maximumEventId);
        }

        record Position(List<Object> cursor, boolean exhausted) { }
        Position position() { return new Position(cursor == null ? null : new ArrayList<>(cursor), exhausted); }
        void restore(Position position)
        {
            cursor = position.cursor == null ? null : new ArrayList<>(position.cursor);
            exhausted = position.exhausted;
        }

        String keysQuery()
        {
            return "SELECT " + columns(table.name, table.primaryKey) + " FROM " + selection.from +
                " WHERE " + table.name + "." + table.primaryKey.getFirst() + " IS NOT NULL AND " + bounds.sql;
        }

        record Pass(int changed, int scanned, boolean done) { }
        private Pass change(Connection connection, int maximumRows) throws SQLException
        {
            if(exhausted) return new Pass(0, 0, true);
            List<List<Object>> selected = new ArrayList<>();
            int scanned = 0;
            int seek = cursor == null ? -1 : selection.order.size() - 1;
            while(scanned < maximumRows && !exhausted)
            {
                List<Object> values = new ArrayList<>(bounds.parameters);
                values.addAll(selection.parameters);
                String range = "1=1";
                if(seek >= 0)
                {
                    List<String> conditions = new ArrayList<>();
                    for(int index = 0; index < seek; index++)
                    {
                        conditions.add(selection.order.get(index).sql + " IS ?");
                        values.add(cursor.get(index));
                    }
                    OrderColumn column = selection.order.get(seek);
                    Object last = cursor.get(seek);
                    if(last == null)
                        conditions.add(column.descending ? "0=1" : column.sql + " IS NOT NULL");
                    else
                    {
                        conditions.add("(" + column.sql + (column.descending ? "<? OR " + column.sql + " IS NULL)" : ">?)"));
                        values.add(last);
                    }
                    range = String.join(" AND ", conditions);
                }
                List<String> fields = new ArrayList<>();
                fields.add(columns(table.name, table.primaryKey));
                for(OrderColumn column: selection.order) fields.add(column.sql);
                fields.add("CASE WHEN " + table.name + "." + table.primaryKey.getFirst() + " IS NOT NULL AND " +
                    bounds.sql + guards + " THEN 1 ELSE 0 END");
                // CASE precedes FROM, so its bound parameters precede the ownership parameters above.
                String query = "SELECT " + String.join(",", fields) + " FROM " + selection.from + " WHERE " + range +
                    " ORDER BY " + selection.order.subList(Math.max(0, seek), selection.order.size()).stream()
                        .map(OrderColumn::ordering).collect(Collectors.joining(",")) + " LIMIT ?";
                int capacity = maximumRows - scanned;
                values.add(capacity);
                int read = 0;
                try(PreparedStatement statement = connection.prepareStatement(query))
                {
                    bind(statement, values);
                    try(ResultSet rows = statement.executeQuery())
                    {
                        while(rows.next())
                        {
                            read++;
                            List<Object> key = new ArrayList<>();
                            for(int index = 0; index < table.primaryKey.size(); index++) key.add(rows.getObject(index + 1));
                            List<Object> next = new ArrayList<>();
                            for(int index = 0; index < selection.order.size(); index++)
                                next.add(rows.getObject(table.primaryKey.size() + index + 1));
                            cursor = next;
                            if(rows.getInt(table.primaryKey.size() + selection.order.size() + 1) != 0) selected.add(key);
                        }
                    }
                }
                scanned += read;
                if(read == capacity) break;
                if(seek < 0) exhausted = true;
                else if(--seek < 0) exhausted = true;
            }
            int changed = 0;
            if(!selected.isEmpty())
            {
                String tuple = table.primaryKey.size() == 1 ? table.primaryKey.getFirst() :
                    "(" + String.join(",", table.primaryKey) + ")";
                String row = "(" + String.join(",", java.util.Collections.nCopies(table.primaryKey.size(), "?")) + ")";
                String sql = (step.updateSet == null ? "DELETE FROM " + table.name :
                    "UPDATE " + table.name + " SET " + step.updateSet) + " WHERE " + tuple +
                    " IN (VALUES " + String.join(",", java.util.Collections.nCopies(selected.size(), row)) +
                    ") AND " + bounds.sql + guards;
                try(PreparedStatement statement = connection.prepareStatement(sql))
                {
                    List<Object> values = new ArrayList<>();
                    for(List<Object> key: selected) values.addAll(key);
                    values.addAll(bounds.parameters);
                    bind(statement, values);
                    changed = statement.executeUpdate();
                }
            }
            return new Pass(changed, scanned, exhausted);
        }
    }

    private record Conditions(String sql, List<Object> parameters) { }
    private record OrderColumn(String sql, boolean descending)
    {
        String ordering() { return sql + (descending ? " DESC" : " ASC"); }
    }
    private record Access(String name, List<OrderColumn> order, boolean partial, String definition) { }

    /** Selected owners stay on the left; every child is sought by an existing foreign-key index. */
    private static final class Selection
    {
        private final String from;
        private final List<Object> parameters;
        private final List<OrderColumn> order;

        private Selection(Connection connection, Step step, Metadata metadata, long cutoff, long maximumEventId)
            throws SQLException
        {
            Step owner = step.owner();
            Access root = metadata.access(connection, owner);
            List<Object> values = new ArrayList<>(owner.parameters);
            String ownerCondition = owner.predicate;
            if(owner.table.equals("receiver_activity_event"))
            {
                ownerCondition += " AND observed_at_ms<=? AND id<=?";
                values.add(cutoff); values.add(maximumEventId);
            }
            StringBuilder source = new StringBuilder("(SELECT * FROM ").append(owner.table);
            if(root.name != null) source.append(" INDEXED BY ").append(root.name);
            source.append(" WHERE ").append(ownerCondition).append(") AS ").append(owner.table);
            List<OrderColumn> columns = new ArrayList<>();
            for(OrderColumn column: root.order)
                columns.add(new OrderColumn(owner.table + "." + column.sql, column.descending));
            for(ForeignKey link: step.ownership)
            {
                Access child = metadata.childAccess(link);
                source.append(" LEFT JOIN ").append(link.child);
                if(child.name != null) source.append(" INDEXED BY ").append(child.name);
                source.append(" ON ").append(link.match(link.parent));
                if(link.child.equals("receiver_activity_event"))
                {
                    source.append(" AND receiver_activity_event.observed_at_ms<=? AND receiver_activity_event.id<=?");
                    values.add(cutoff); values.add(maximumEventId);
                }
                for(OrderColumn column: child.order)
                    if(!link.from.contains(column.sql))
                        columns.add(new OrderColumn(link.child + "." + column.sql, column.descending));
            }
            from = source.toString();
            parameters = List.copyOf(values);
            order = List.copyOf(columns);
        }
    }

    private static String columns(String table, List<String> columns)
    {
        return columns.stream().map(column -> table + "." + column).collect(Collectors.joining(","));
    }

    private static Conditions rowBounds(Table table, long cutoff, long maximumEventId)
    {
        List<String> predicates = new ArrayList<>(List.of("1=1"));
        List<Object> values = new ArrayList<>();
        if(table.columns.contains("bucket_start_ms"))
        {
            long interval = table.name.equals("trunked_control_channel_quality") ? 10_000 : 3_600_000;
            predicates.add(table.name + ".bucket_start_ms<?"); values.add(cutoff - Math.floorMod(cutoff, interval));
        }
        for(String time: List.of("last_seen_ms", "last_observed_ms", "confirmed_at_ms", "observed_at_ms", "cleared_at_ms", "updated_at_ms"))
            if(table.columns.contains(time))
            {
                predicates.add(table.name + "." + time + "<=?"); values.add(cutoff); break;
            }
        if(table.name.equals("receiver_activity_event"))
        {
            predicates.add("receiver_activity_event.id<=?"); values.add(maximumEventId);
        }
        if(table.name.equals("activity_event_identity_member") || table.name.startsWith("receiver_activity_tone"))
        {
            predicates.add("EXISTS(SELECT 1 FROM receiver_activity_event cutoff_event WHERE cutoff_event.id=" +
                table.name + ".event_id AND cutoff_event.observed_at_ms<=? AND cutoff_event.id<=?)");
            values.add(cutoff); values.add(maximumEventId);
        }
        return new Conditions(String.join(" AND ", predicates), List.copyOf(values));
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
        private final Map<String,List<ForeignKey>> references = new LinkedHashMap<>();
        private final Map<String,List<Access>> indexes = new LinkedHashMap<>();

        private Metadata(Connection connection) throws SQLException
        {
            List<String> names = new ArrayList<>();
            try(var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND (name LIKE 'p25_%' OR name LIKE 'trunked_%' " +
                    "OR name LIKE 'conventional_%' OR name LIKE 'dmr_conventional_%' OR name LIKE 'radio_system%' " +
                    "OR name LIKE 'receiver_activity_tone%' OR name IN ('receiver_channel','receiver_channel_statistics_reset'," +
                    "'receiver_activity_event','activity_event_identity_member','alias_activity_summary','alias_list_analog_activity_summary'))"))
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
            for(Table table: tables.values())
            {
                List<Access> available = new ArrayList<>();
                try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA index_list(" + table.name + ")"))
                {
                    while(rows.next())
                    {
                        String index = rows.getString("name");
                        boolean partial = rows.getInt("partial") != 0;
                        List<OrderColumn> order = new ArrayList<>();
                        Set<String> included = new LinkedHashSet<>();
                        try(var details = connection.createStatement(); var fields = details.executeQuery("PRAGMA index_xinfo(" + index + ")"))
                        {
                            while(fields.next())
                            {
                                String field = fields.getString("name");
                                if(field == null || fields.getInt("cid") < 0) continue;
                                order.add(new OrderColumn(field, fields.getInt("desc") != 0));
                                included.add(field);
                                if(included.containsAll(table.primaryKey)) break;
                            }
                        }
                        for(String key: table.primaryKey)
                            if(included.add(key)) order.add(new OrderColumn(key, false));
                        String definition = "";
                        try(var sql = connection.prepareStatement("SELECT coalesce(sql,'') FROM sqlite_master WHERE name=?"))
                        {
                            sql.setString(1, index);
                            try(var result = sql.executeQuery()) { if(result.next()) definition = result.getString(1); }
                        }
                        available.add(new Access(index, List.copyOf(order), partial, definition));
                    }
                }
                indexes.put(table.name, List.copyOf(available));
            }
            for(String name: names)
            {
                Map<Integer,List<String>> from = new LinkedHashMap<>();
                Map<Integer,List<String>> to = new LinkedHashMap<>();
                Map<Integer,String> parents = new LinkedHashMap<>();
                Set<Integer> cascades = new LinkedHashSet<>();
                try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA foreign_key_list(" + name + ")"))
                {
                    while(rows.next())
                    {
                        String parent = rows.getString("table");
                        if(!tables.containsKey(parent)) continue;
                        int id = rows.getInt("id");
                        parents.put(id, parent);
                        if("CASCADE".equalsIgnoreCase(rows.getString("on_delete"))) cascades.add(id);
                        from.computeIfAbsent(id, ignored -> new ArrayList<>()).add(rows.getString("from"));
                        to.computeIfAbsent(id, ignored -> new ArrayList<>()).add(rows.getString("to"));
                    }
                }
                for(var parent: parents.entrySet())
                {
                    ForeignKey link = new ForeignKey(name, parent.getValue(), List.copyOf(from.get(parent.getKey())),
                        List.copyOf(to.get(parent.getKey())));
                    references.computeIfAbsent(parent.getValue(), ignored -> new ArrayList<>()).add(link);
                    if(cascades.contains(parent.getKey()))
                        children.computeIfAbsent(parent.getValue(), ignored -> new ArrayList<>()).add(link);
                }
            }
            for(var parent: children.entrySet())
            {
                Map<String,ForeignKey> unique = new LinkedHashMap<>();
                for(ForeignKey link: parent.getValue())
                {
                    List<String> owner = new ArrayList<>();
                    for(String key: tables.get(parent.getKey()).primaryKey)
                    {
                        int index = link.to.indexOf(key);
                        if(index < 0) { owner.clear(); break; }
                        owner.add(link.from.get(index));
                    }
                    // Composite validation FKs may add channel/system/kind to the same unique owner.
                    // The shared parent primary key makes those ownership paths equivalent.
                    String key = link.child + "|" + (owner.isEmpty() ? link : owner);
                    unique.putIfAbsent(key, link);
                }
                parent.setValue(new ArrayList<>(unique.values()));
            }
        }

        /** A direct FK also selects every row reached through an equivalent two-link ownership path. */
        private boolean directOwnershipCovers(ForeignKey first, ForeignKey second)
        {
            for(ForeignKey direct: children.getOrDefault(first.parent, List.of()))
            {
                if(!direct.child.equals(second.child)) continue;
                boolean implied = true;
                for(int index = 0; index < direct.from.size(); index++)
                {
                    int middle = second.from.indexOf(direct.from.get(index));
                    int parent = middle < 0 ? -1 : first.from.indexOf(second.to.get(middle));
                    if(parent < 0 || !first.to.get(parent).equals(direct.to.get(index)))
                    {
                        implied = false;
                        break;
                    }
                }
                if(implied) return true;
            }
            return false;
        }

        private Access access(Connection connection, Step owner) throws SQLException
        {
            if(owner.table.equals("receiver_activity_event"))
            {
                String predicate = owner.predicate;
                String preferred = null;
                if(predicate.contains("source_identity_summary_id=?") || predicate.contains("source_identity_summary_id IN"))
                    preferred = "idx_receiver_activity_event_source_time";
                else if(predicate.contains("target_identity_summary_id=?") || predicate.contains("target_identity_summary_id IN"))
                    preferred = "idx_receiver_activity_event_target_time";
                else if(predicate.contains("source_observed_local_id")) preferred = "idx_receiver_activity_event_channel_source_id_time";
                else if(predicate.contains("target_observed_local_id")) preferred = "idx_receiver_activity_event_channel_target_id_time";
                else if(predicate.contains("frequency_hz=?")) preferred = predicate.contains("channel_id") ?
                    "idx_receiver_activity_event_channel_frequency_time" : "idx_receiver_activity_event_frequency_time";
                else if(predicate.contains("action_code")) preferred = predicate.contains("channel_id") ?
                    "idx_receiver_activity_event_channel_action_time" : "idx_receiver_activity_event_system_action_time";
                else if(predicate.contains("channel_id")) preferred = "idx_receiver_activity_event_channel_time";
                else if(predicate.contains("radio_system_id")) preferred = "idx_receiver_activity_event_system_time";
                if(preferred != null)
                    for(Access access: indexes.get(owner.table)) if(access.name.equals(preferred)) return access;
            }
            String selected = null;
            try(PreparedStatement statement = connection.prepareStatement("EXPLAIN QUERY PLAN SELECT * FROM " +
                lookupTable(owner) + " WHERE " + owner.predicate))
            {
                bind(statement, owner.parameters);
                try(ResultSet rows = statement.executeQuery())
                {
                    while(rows.next())
                    {
                        String detail = rows.getString(4);
                        if(!detail.startsWith("SEARCH " + owner.table + " ") && !detail.startsWith("SCAN " + owner.table + " "))
                            continue;
                        var match = java.util.regex.Pattern.compile("(?:COVERING )?INDEX ([^ ]+)").matcher(detail);
                        if(match.find()) selected = match.group(1);
                    }
                }
            }
            if(selected != null)
                for(Access access: indexes.get(owner.table)) if(access.name.equals(selected)) return access;
            return primary(tables.get(owner.table));
        }

        private Access childAccess(ForeignKey link) throws SQLException
        {
            Access best = null;
            int bestPrefix = 0;
            for(Access access: indexes.get(link.child))
            {
                int prefix = 0;
                for(OrderColumn column: access.order)
                {
                    if(!link.from.contains(column.sql)) break;
                    prefix++;
                }
                if(prefix == 0) continue;
                if(access.partial && !access.definition.matches("(?is).*\\bWHERE\\s+" +
                    java.util.regex.Pattern.quote(access.order.getFirst().sql) + "\\s+IS\\s+NOT\\s+NULL\\s*")) continue;
                if(prefix > bestPrefix || prefix == bestPrefix && best != null && access.order.size() < best.order.size())
                {
                    best = access;
                    bestPrefix = prefix;
                }
            }
            Access primary = primary(tables.get(link.child));
            int prefix = 0;
            for(OrderColumn column: primary.order)
            {
                if(!link.from.contains(column.sql)) break;
                prefix++;
            }
            if(prefix > bestPrefix) return primary;
            if(best != null) return best;
            if(prefix > 0) return primary;
            throw new SQLException("Retained-data ownership has no indexed child lookup: " + link.child + " -> " + link.parent);
        }

        private static Access primary(Table table)
        {
            return new Access(null, table.primaryKey.stream().map(key -> new OrderColumn(key, false)).toList(), false, "");
        }

        private int rank(String table, boolean allReferences)
        {
            int rank = 0;
            //Owner resets also delete dictionary parents protected by RESTRICT and owners linked by SET NULL.
            //Those are dependencies, while only CASCADE grants ownership during selected-data cleanup.
            for(ForeignKey child: (allReferences ? references : children).getOrDefault(table, List.of()))
                rank = Math.max(rank, rank(child.child, allReferences) + 1);
            return rank;
        }

        private String childGuards(Table table, boolean allReferences)
        {
            StringBuilder guards = new StringBuilder();
            for(ForeignKey child: (allReferences ? references : children).getOrDefault(table.name, List.of()))
                guards.append(" AND NOT EXISTS (SELECT 1 FROM ")
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

    private record Step(String part, String table, String predicate, List<Object> parameters, String updateSet,
                        Step origin, List<ForeignKey> ownership)
    {
        Step(String part, String table, String predicate, List<Object> parameters, String updateSet)
        {
            this(part, table, predicate, parameters, updateSet, null, List.of());
        }

        Step owner() { return origin == null ? this : origin; }
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
