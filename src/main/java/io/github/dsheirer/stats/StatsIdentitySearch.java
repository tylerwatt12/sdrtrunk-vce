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
        if(includeTalkerAlias)
        {
            sql.append(" OR lower(coalesce(summary.last_talker_alias,'')) LIKE ? ESCAPE '\\'");
            parameters.add(pattern);
        }
        appendAliasSearch(sql, parameters, pattern, aliasMatcher, false);
        appendAliasSearch(sql, parameters, pattern, aliasMatcher, true);
        sql.append(')');
    }

    private static void appendAliasSearch(StringBuilder sql, List<Object> parameters, String pattern,
                                          String aliasMatcher, boolean ranged)
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
            .append(" AND ").append(aliasTextSearch())
            .append(" AND (system.protocol_code<>1 OR NOT (")
            .append(p25ProjectedEvidencePresent(kind)).append(")))");
        addTextParameters(parameters, pattern);
        for(LocalEvidenceSource source: compactSources(kind))
        {
            appendLocalAliasSearch(sql, parameters, pattern, matcher, index, source, ranged, null);
        }
        for(LocalEvidenceSource source: detailSources())
        {
            appendLocalAliasSearch(sql, parameters, pattern, matcher, index, source, ranged,
                p25RawCompactEvidencePresent(kind));
        }
    }

    private static void appendLocalAliasSearch(StringBuilder sql, List<Object> parameters, String pattern,
                                               String matcher, String index, LocalEvidenceSource source,
                                               boolean ranged, String requiredAbsent)
    {
        sql.append(" OR (system.protocol_code=1 AND EXISTS (SELECT 1 FROM ")
            .append(source.from()).append(" JOIN receiver_channel observed_channel ON ")
            .append("observed_channel.id=").append(source.channelId())
            .append(" JOIN configuration_channel observed_config ON ")
            .append("observed_config.configuration_id=observed_channel.configuration_id WHERE ")
            .append(source.summaryId()).append("=summary.id AND ")
            .append(source.observedId()).append(">0 AND ")
            .append("observed_channel.radio_system_id=system.id AND ")
            .append("observed_config.alias_list_id IS NOT NULL");
        if(source.systemId() != null)
        {
            sql.append(" AND ").append(source.systemId()).append("=system.id");
        }
        if(requiredAbsent != null)
        {
            sql.append(" AND NOT (").append(requiredAbsent).append(')');
        }
        sql.append(" AND EXISTS (SELECT 1 FROM alias definition INDEXED BY ").append(index)
            .append(" WHERE definition.matcher_type='").append(matcher).append("' ")
            .append("AND definition.protocol IN ('APCO25','APCO25_PHASE2') AND ")
            .append("definition.alias_list_id=observed_config.alias_list_id AND ")
            .append(aliasMatch(source.observedId(), ranged)).append(" AND ")
            .append(aliasTextSearch()).append(")))");
        addTextParameters(parameters, pattern);
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
            compact.add(localEvidenceExists(source));
        }
        List<String> detail = new ArrayList<>();
        for(LocalEvidenceSource source: detailSources())
        {
            detail.add(localEvidenceExists(source));
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
                (source.systemId() == null ? "" : " AND " + source.systemId() + "=system.id") + ")");
        }
        return String.join(" OR ", clauses);
    }

    private static List<LocalEvidenceSource> compactSources(int kind)
    {
        List<LocalEvidenceSource> sources = new ArrayList<>();
        sources.add(new LocalEvidenceSource("p25_site_call_identity_bucket evidence " +
            "INDEXED BY idx_p25_site_call_identity_identity", "evidence.identity_summary_id",
            "evidence.channel_id", "evidence.observed_local_id", null));
        sources.add(new LocalEvidenceSource("activity_event_identity_member evidence " +
            "INDEXED BY idx_activity_event_member_identity_event " +
            "JOIN receiver_activity_event event ON event.id=evidence.event_id",
            "evidence.identity_summary_id", "event.channel_id", "evidence.observed_local_id", null));
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
            "INDEXED BY idx_receiver_activity_event_source_time", "evidence.source_identity_summary_id",
            "evidence.channel_id", "evidence.source_observed_local_id", null),
            new LocalEvidenceSource("receiver_activity_event evidence " +
                "INDEXED BY idx_receiver_activity_event_target_time", "evidence.target_identity_summary_id",
                "evidence.channel_id", "evidence.target_observed_local_id", null));
    }

    private static String localEvidenceExists(LocalEvidenceSource source)
    {
        return "EXISTS (SELECT 1 FROM " + source.from() + " JOIN receiver_channel observed_channel ON " +
            "observed_channel.id=" + source.channelId() + " JOIN configuration_channel observed_config ON " +
            "observed_config.configuration_id=observed_channel.configuration_id WHERE " +
            source.summaryId() + "=summary.id AND " + source.observedId() + ">0 AND " +
            "observed_channel.radio_system_id=system.id AND " +
            (source.systemId() == null ? "" : source.systemId() + "=system.id AND ") +
            "observed_config.alias_list_id IS NOT NULL)";
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
