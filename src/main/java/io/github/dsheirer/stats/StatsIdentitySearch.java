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
package io.github.dsheirer.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared, scoped search for dashboard and retained-statistics identity directories.
 * Callers must name the identity and radio-system table aliases {@code summary} and {@code system}.
 */
final class StatsIdentitySearch
{
    private StatsIdentitySearch() {}

    static void append(StringBuilder sql, List<Object> parameters, String search, String aliasMatcher,
                       boolean includeTalkerAlias)
    {
        if(search == null)
        {
            return;
        }
        if(!"TALKGROUP".equals(aliasMatcher) && !"RADIO_ID".equals(aliasMatcher))
        {
            throw new IllegalArgumentException("Unsupported radio-system Alias matcher");
        }
        String pattern = like(search);
        sql.append(" AND (CAST(summary.identity_id AS TEXT) LIKE ? ESCAPE '\\' ")
            .append("OR (system.protocol_code=4 AND system.address_domain_code=2 AND ")
            .append("printf('%02d-%04d', ((summary.identity_id >> 11) & 31), ")
            .append("(summary.identity_id & 2047)) LIKE ? ESCAPE '\\')");
        parameters.add(pattern);
        parameters.add(pattern);
        sql.append(" OR system.id IN (SELECT named_system.id FROM radio_system named_system WHERE ")
            .append("lower(coalesce(").append(StatsSystemNameResolver.configuredNameSql("named_system"))
            .append(",'')) LIKE ? ESCAPE '\\') OR printf('p25:%05x:%03x', ")
            .append("summary.home_wacn,summary.home_system_id) IN (SELECT named_system.system_key ")
            .append("FROM radio_system named_system WHERE lower(coalesce(")
            .append(StatsSystemNameResolver.configuredNameSql("named_system"))
            .append(",'')) LIKE ? ESCAPE '\\')");
        parameters.add(pattern);
        parameters.add(pattern);
        if(includeTalkerAlias)
        {
            sql.append(" OR lower(coalesce(summary.last_talker_alias,'')) LIKE ? ESCAPE '\\'");
            parameters.add(pattern);
        }
        // Alias text is independent of the retained identity being tested. Resolve it once and avoid
        // reading any historical address evidence when no configured Alias can match the search.
        sql.append(" OR EXISTS (WITH identity_search_aliases AS MATERIALIZED (")
            .append("SELECT definition.id,definition.matcher_type FROM alias definition WHERE definition.matcher_type IN ('")
            .append(aliasMatcher).append("','").append(aliasMatcher).append("_RANGE'")
            .append("RADIO_ID".equals(aliasMatcher) ? ",'P25_SUBSCRIBER_IDENTITY') AND " : ") AND ")
            .append(aliasTextSearch())
            .append(") SELECT 1 WHERE EXISTS (SELECT 1 FROM identity_search_aliases) AND (0");
        addTextParameters(parameters, pattern);
        if("RADIO_ID".equals(aliasMatcher))
        {
            sql.append(" OR EXISTS (SELECT 1 FROM alias_p25_subscriber_identity canonical_match ")
                .append("JOIN alias definition ON definition.id=canonical_match.alias_id ")
                .append("WHERE canonical_match.p25_subscriber_identity_id=summary.p25_subscriber_identity_id ")
                .append("AND EXISTS (SELECT 1 FROM receiver_channel assigned_channel ")
                .append("JOIN configuration_channel assigned_config ON ")
                .append("assigned_config.configuration_id=assigned_channel.configuration_id ")
                .append("WHERE assigned_channel.radio_system_id=system.id ")
                .append("AND assigned_config.alias_list_id=definition.alias_list_id) AND ")
                .append(matchedAlias()).append(')');
        }
        appendAliasSearch(sql, aliasMatcher, false);
        appendAliasSearch(sql, aliasMatcher, true);
        sql.append(")))");
    }

    /** Matches friendly labels in SQL before the subscriber page is ordered or limited. */
    static void appendIssiSubscriber(StringBuilder sql, List<Object> parameters, String search)
    {
        if(search == null) return;
        String pattern = like(search);
        sql.append(" AND (lower(printf('%05x.%03x.%d', canonical_wacn,canonical_system_id,")
            .append("canonical_subscriber_id)||' '||canonical_wacn||' '||canonical_system_id||' '||")
            .append("canonical_subscriber_id||' '||coalesce(last_observed_working_id,'')||' '||")
            .append("coalesce(observation_name,'')||' '||coalesce(observation_site_name,'')) LIKE ? ESCAPE '\\'");
        parameters.add(pattern);
        appendP25SystemName(sql, parameters, pattern, "canonical_wacn", "canonical_system_id");
        String scope = "(definition.alias_list_id=subscriber_rows.observation_alias_list_id OR " +
            "(subscriber_rows.observation_alias_list_id IS NULL AND EXISTS (" +
            "SELECT 1 FROM receiver_channel assigned_channel JOIN configuration_channel assigned_config " +
            "ON assigned_config.configuration_id=assigned_channel.configuration_id " +
            "WHERE assigned_channel.radio_system_id=subscriber_rows.radio_system_id " +
            "AND assigned_config.alias_list_id=definition.alias_list_id)))";
        String canonical = "SELECT 1 FROM alias_p25_subscriber_identity matched " +
            "JOIN alias definition ON definition.id=matched.alias_id " +
            "WHERE matched.p25_subscriber_identity_id=subscriber_rows.p25_subscriber_identity_id AND " + scope;
        sql.append(" OR EXISTS (").append(canonical).append(" AND ").append(aliasTextSearch()).append(')');
        addTextParameters(parameters, pattern);
        sql.append(" OR (subscriber_rows.observation_alias_list_id IS NOT NULL ")
            .append("AND subscriber_rows.last_observed_working_id>0 AND NOT EXISTS (")
            .append(canonical).append(") AND EXISTS (SELECT 1 FROM alias definition ")
            .append("WHERE definition.alias_list_id=subscriber_rows.observation_alias_list_id ")
            .append("AND definition.protocol IN ('APCO25','APCO25_PHASE2') AND (")
            .append("(definition.matcher_type='RADIO_ID' AND definition.value=last_observed_working_id) OR ")
            .append("(definition.matcher_type='RADIO_ID_RANGE' AND last_observed_working_id ")
            .append("BETWEEN definition.min_value AND definition.max_value)) AND ")
            .append(aliasTextSearch()).append(")))");
        addTextParameters(parameters, pattern);
    }

    static void appendIssiForeignSystem(StringBuilder sql, List<Object> parameters, String search)
    {
        if(search == null) return;
        String pattern = like(search);
        sql.append(" AND (lower(printf('%05x.%03x',foreign_systems.home_wacn,")
            .append("foreign_systems.home_system_id)||' '||foreign_systems.home_wacn||' '||")
            .append("foreign_systems.home_system_id) LIKE ? ESCAPE '\\'");
        parameters.add(pattern);
        appendP25SystemName(sql, parameters, pattern, "foreign_systems.home_wacn",
            "foreign_systems.home_system_id");
        sql.append(')');
    }

    static void appendIssiBand(StringBuilder sql, List<Object> parameters, String search)
    {
        if(search == null) return;
        String pattern = like(search);
        sql.append(" AND (lower(printf('%05x.%03x',summary.foreign_wacn,summary.foreign_system_id)||' '||")
            .append("summary.foreign_wacn||' '||summary.foreign_system_id||' '||summary.band||' '||")
            .append("summary.base_hz||' '||coalesce(config.name,'')||' '||coalesce(config.site_name,'')||' '||")
            .append("coalesce(config.system_name,'')) LIKE ? ESCAPE '\\'");
        parameters.add(pattern);
        appendP25SystemName(sql, parameters, pattern, "summary.foreign_wacn", "summary.foreign_system_id");
        sql.append(')');
    }

    private static void appendP25SystemName(StringBuilder sql, List<Object> parameters, String pattern,
        String wacn, String systemId)
    {
        sql.append(" OR EXISTS (SELECT 1 FROM radio_system home_system WHERE ")
            .append("home_system.system_key=printf('p25:%05x:%03x',").append(wacn).append(',')
            .append(systemId).append(") AND lower(coalesce(")
            .append(StatsSystemNameResolver.configuredNameSql("home_system"))
            .append(",'')) LIKE ? ESCAPE '\\')");
        parameters.add(pattern);
    }

    private static void appendAliasSearch(StringBuilder sql, String aliasMatcher, boolean ranged)
    {
        String matcher = ranged ? aliasMatcher + "_RANGE" : aliasMatcher;
        String index = switch(matcher)
        {
            case "RADIO_ID" -> "idx_alias_radio_value";
            case "RADIO_ID_RANGE" -> "idx_alias_radio_range";
            case "TALKGROUP" -> "idx_alias_talkgroup_value";
            case "TALKGROUP_RANGE" -> "idx_alias_talkgroup_range";
            default -> throw new IllegalArgumentException("Unsupported radio-system Alias matcher");
        };
        int kind = "RADIO_ID".equals(aliasMatcher) ? 2 : 1;
        // A matching label for another matcher cannot use this branch's address evidence.
        sql.append(" OR (EXISTS (SELECT 1 FROM identity_search_aliases named WHERE ")
            .append("named.matcher_type='").append(matcher).append("') AND (0");
        String assigned = "(EXISTS (SELECT 1 FROM receiver_channel assigned_channel " +
            "JOIN configuration_channel assigned_config ON " +
            "assigned_config.configuration_id=assigned_channel.configuration_id " +
            "WHERE assigned_channel.radio_system_id=system.id AND " +
            "assigned_config.alias_list_id=definition.alias_list_id) OR EXISTS (" +
            "SELECT 1 FROM configuration_channel assigned_config WHERE " +
            "assigned_config.configuration_id=system.configuration_id AND " +
            "assigned_config.alias_list_id=definition.alias_list_id))";
        sql.append(" OR EXISTS (SELECT 1 FROM alias definition INDEXED BY ").append(index)
            .append(" WHERE definition.matcher_type='").append(matcher).append("' ")
            .append("AND definition.protocol IN (CASE system.protocol_code ")
            .append("WHEN 1 THEN 'APCO25' WHEN 3 THEN 'DMR' WHEN 4 THEN 'NXDN' END, ")
            .append("CASE WHEN system.protocol_code=1 THEN 'APCO25_PHASE2' END) AND ")
            .append(aliasMatch("summary.identity_id", ranged)).append(" AND ").append(assigned)
            .append(" AND ").append(matchedAlias())
            .append(" AND (system.protocol_code<>1 OR ")
            .append(kind == 2 ? "(summary.p25_subscriber_identity_id IS NULL OR " +
                StatsAliasResolver.homeSystemSql("summary", "system") + ") AND " : "")
            .append("NOT (")
            .append(p25ProjectedEvidencePresent(kind)).append(")))");
        for(LocalEvidenceSource source: compactSources(kind))
        {
            appendLocalAliasSearch(sql, matcher, source, ranged, null);
        }
        for(LocalEvidenceSource source: detailSources())
        {
            appendLocalAliasSearch(sql, matcher, source, ranged,
                p25RawCompactEvidencePresent(kind));
        }
        sql.append("))");
    }

    private static void appendLocalAliasSearch(StringBuilder sql, String matcher, LocalEvidenceSource source,
                                               boolean ranged, String requiredAbsent)
    {
        boolean radio = matcher.startsWith("RADIO_ID");
        appendLocalAliasSearchBranch(sql,matcher,source,ranged,requiredAbsent,radio,false);
        if(radio && !source.from().startsWith("activity_event_identity_member"))
        {
            appendLocalAliasSearchBranch(sql,matcher,source,ranged,requiredAbsent,true,true);
        }
    }

    private static void appendLocalAliasSearchBranch(StringBuilder sql, String matcher, LocalEvidenceSource source,
        boolean ranged, String requiredAbsent, boolean radio, boolean workingEvidence)
    {
        sql.append(" OR (system.protocol_code=1");
        if(radio)
        {
            sql.append(workingEvidence ? " AND summary.p25_subscriber_identity_id IS NOT NULL" :
                " AND (summary.p25_subscriber_identity_id IS NULL OR " +
                    StatsAliasResolver.homeSystemSql("summary","system") + ")");
        }
        if(requiredAbsent != null)
        {
            sql.append(" AND NOT (").append(requiredAbsent).append(')');
        }
        sql.append(" AND EXISTS (SELECT 1 FROM identity_search_aliases named ")
            .append("CROSS JOIN alias definition ON definition.id=named.id CROSS JOIN ")
            .append(source.from()).append(" CROSS JOIN receiver_channel observed_channel ON ")
            .append("observed_channel.id=").append(source.channelId())
            .append(" CROSS JOIN configuration_channel observed_config ON ")
            .append("observed_config.configuration_id=observed_channel.configuration_id WHERE ")
            .append("named.matcher_type='").append(matcher).append("' ")
            .append("AND definition.protocol IN ('APCO25','APCO25_PHASE2') AND ")
            .append(source.summaryId()).append("=summary.id AND ")
            .append(source.observedId()).append(">0 AND ")
            .append(aliasMatch(source.observedId(), ranged)).append(" AND ")
            .append("observed_channel.radio_system_id=system.id AND ")
            .append("observed_config.alias_list_id IS NOT NULL AND ")
            .append("observed_config.alias_list_id=definition.alias_list_id");
        if(source.systemId() != null)
        {
            sql.append(" AND ").append(source.systemId()).append("=system.id");
        }
        if(radio)
        {
            sql.append(" AND ").append(workingEvidence ?
                source.observedId().replace("observed_local_id","observed_working_id") + "=" + source.observedId() :
                "(summary.p25_subscriber_identity_id IS NULL OR " + source.observedId() + "=summary.identity_id)");
        }
        sql.append("))");
    }

    private static String matchedAlias()
    {
        return "definition.id IN (SELECT id FROM identity_search_aliases)";
    }

    private static String aliasTextSearch()
    {
        return "(lower(coalesce(definition.name,'')) LIKE ? ESCAPE '\\' OR " +
            "lower(coalesce(definition.description,'')) LIKE ? ESCAPE '\\' OR " +
            "lower(coalesce(definition.group_name,'')) LIKE ? ESCAPE '\\')";
    }

    private static void addTextParameters(List<Object> parameters, String pattern)
    {
        parameters.add(pattern);
        parameters.add(pattern);
        parameters.add(pattern);
    }

    private static String p25ProjectedEvidencePresent(int kind)
    {
        List<String> compact = new ArrayList<>();
        for(LocalEvidenceSource source: compactSources(kind))
        {
            compact.add(localEvidenceExists(source, kind));
        }
        List<String> detail = new ArrayList<>();
        for(LocalEvidenceSource source: detailSources())
        {
            detail.add(localEvidenceExists(source, kind));
        }
        return String.join(" OR ", compact) + " OR (NOT (" +
            p25RawCompactEvidencePresent(kind) + ") AND (" + String.join(" OR ", detail) + "))";
    }

    private static String p25RawCompactEvidencePresent(int kind)
    {
        List<String> clauses = new ArrayList<>();
        for(LocalEvidenceSource source: compactSources(kind))
        {
            clauses.add("EXISTS (SELECT 1 FROM " + source.from() + " WHERE " +
                source.summaryId() + "=summary.id AND " + source.observedId() + ">0" +
                (source.systemId() == null ? "" : " AND " + source.systemId() + "=system.id") +
                (kind == 2 ? " AND " + radioLocalEvidence(source) : "") + ")");
        }
        return String.join(" OR ", clauses);
    }

    private static List<LocalEvidenceSource> compactSources(int kind)
    {
        List<LocalEvidenceSource> sources = new ArrayList<>();
        sources.add(new LocalEvidenceSource("p25_site_call_identity_bucket evidence " +
            "INDEXED BY idx_p25_site_call_identity_identity_address", "evidence.identity_summary_id",
            "evidence.channel_id", "evidence.observed_local_id", null));
        sources.add(new LocalEvidenceSource("activity_event_identity_member evidence " +
            "INDEXED BY idx_activity_event_member_identity_channel_local",
            "evidence.identity_summary_id", "evidence.channel_id", "evidence.observed_local_id", null));
        if(kind == 2)
        {
            sources.add(new LocalEvidenceSource("trunked_radio_channel_presence evidence",
                "evidence.radio_identity_id", "evidence.channel_id", "evidence.observed_local_id",
                "evidence.radio_system_id"));
            sources.add(new LocalEvidenceSource("trunked_radio_affiliation evidence",
                "evidence.radio_identity_id", "evidence.channel_id", "evidence.radio_observed_local_id",
                "evidence.radio_system_id"));
        }
        else
        {
            sources.add(new LocalEvidenceSource("trunked_radio_affiliation evidence " +
                "INDEXED BY idx_trunked_radio_affiliation_talkgroup",
                "evidence.talkgroup_identity_id", "evidence.channel_id",
                "evidence.talkgroup_observed_local_id", "evidence.radio_system_id"));
        }
        return sources;
    }

    private static List<LocalEvidenceSource> detailSources()
    {
        return List.of(new LocalEvidenceSource("receiver_activity_event evidence " +
            "INDEXED BY idx_receiver_activity_event_source_identity_address", "evidence.source_identity_summary_id",
            "evidence.channel_id", "evidence.source_observed_local_id", null),
            new LocalEvidenceSource("receiver_activity_event evidence " +
                "INDEXED BY idx_receiver_activity_event_target_identity_address", "evidence.target_identity_summary_id",
                "evidence.channel_id", "evidence.target_observed_local_id", null));
    }

    private static String localEvidenceExists(LocalEvidenceSource source, int kind)
    {
        return "EXISTS (SELECT 1 FROM " + source.from() + " CROSS JOIN receiver_channel observed_channel ON " +
            "observed_channel.id=" + source.channelId() + " CROSS JOIN configuration_channel observed_config ON " +
            "observed_config.configuration_id=observed_channel.configuration_id WHERE " +
            source.summaryId() + "=summary.id AND " + source.observedId() + ">0 AND " +
            "observed_channel.radio_system_id=system.id AND " +
            (source.systemId() == null ? "" : source.systemId() + "=system.id AND ") +
            "observed_config.alias_list_id IS NOT NULL" +
            (kind == 2 ? " AND " + radioLocalEvidence(source) : "") + ")";
    }

    private static String radioLocalEvidence(LocalEvidenceSource source)
    {
        String working = source.observedId().replace("observed_local_id", "observed_working_id");
        return "(summary.p25_subscriber_identity_id IS NULL OR (" +
            StatsAliasResolver.homeSystemSql("summary", "system") + " AND " + source.observedId() +
            "=summary.identity_id)" + (source.from().startsWith("activity_event_identity_member") ? "" :
                " OR " + working + "=" + source.observedId()) + ")";
    }

    private static String aliasMatch(String observedId, boolean ranged)
    {
        return ranged ? observedId + " BETWEEN definition.min_value AND definition.max_value" :
            observedId + "=definition.value";
    }

    private static String like(String value)
    {
        return "%" + value.toLowerCase(Locale.ROOT).replace("\\", "\\\\")
            .replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private record LocalEvidenceSource(String from, String summaryId, String channelId, String observedId,
                                       String systemId) {}
}
