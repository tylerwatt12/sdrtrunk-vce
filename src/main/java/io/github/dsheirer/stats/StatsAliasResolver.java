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

package io.github.dsheirer.stats;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Central alias lookup for Stats Server read models. Each lookup loads only rules that can match the bounded
 * identities and alias lists present in the current response page; SQLite remains the source of truth.
 */
class StatsAliasResolver
{
    static final int MAX_INPUT_ROWS = StatsCsvExport.MAX_ROWS + 1;
    static final int MAX_ALIAS_LISTS = 256;
    static final int MAX_SYSTEM_KEYS = 256;
    static final int MAX_SYSTEM_ALIAS_LIST_PAIRS = 512;
    static final int MAX_LOADED_RULES = 4_096;
    static final int MAX_RULE_LOOKUP_PAIRS = 20_000;
    private static final int QUERY_VALUE_CHUNK = 200;
    private static final int RULE_TARGET_CHUNK = 500;
    private static final List<String> P25_PROTOCOLS = List.of("APCO25", "APCO25_PHASE2");
    private static final List<String> DMR_PROTOCOLS = List.of("DMR");
    private static final List<String> NXDN_PROTOCOLS = List.of("NXDN");

    void enrichTalkgroups(Connection connection, List<Map<String,Object>> rows) throws SQLException
    {
        enrichTalkgroups(connection, rows, "talkgroup_id", "alias_");
    }

    void enrichRadios(Connection connection, List<Map<String,Object>> rows) throws SQLException
    {
        enrichRadios(connection, rows, "radio_id", "alias_");
    }

    void enrichTalkgroups(Connection connection, List<Map<String,Object>> rows, String identifierColumn,
                          String prefix) throws SQLException
    {
        if(rows.isEmpty())
        {
            return;
        }

        requireBoundedRows(rows);
        Map<String,Set<Long>> aliasLists = loadAliasLists(connection, systemKeys(rows));
        List<Rule> rules = loadRules(connection, RuleType.TALKGROUP, P25_PROTOCOLS,
            ruleTargets(rows, row -> systemAliasLists(row, aliasLists),
                source(identifierColumn, ignored -> true)));
        enrich(rows, rules, aliasLists, identifierColumn, prefix);
    }

    void enrichRadios(Connection connection, List<Map<String,Object>> rows, String identifierColumn,
                      String prefix) throws SQLException
    {
        if(rows.isEmpty())
        {
            return;
        }

        requireBoundedRows(rows);
        Map<String,Set<Long>> aliasLists = loadAliasLists(connection, systemKeys(rows));
        enrichP25SystemRadios(connection, rows, aliasLists, identifierColumn,
            "observed_working_id", prefix);
    }

    /**
     * Resolves a canonical group identity shown at radio-system scope. P25 identities use the local address observed
     * by each exact saved channel. Native DMR Tier III and NXDN Type-C systems can also span several saved channels.
     * A system-level Alias is shown only when every applicable channel/list pair agrees on its presentation.
     */
    void enrichCanonicalSystemTalkgroups(Connection connection, List<Map<String,Object>> rows,
                                         String summaryIdColumn, String identifierColumn, String prefix)
        throws SQLException
    {
        enrichCanonicalSystemIdentities(connection, rows, RuleType.TALKGROUP, summaryIdColumn, identifierColumn,
            prefix);
    }

    /** See {@link #enrichCanonicalSystemTalkgroups(Connection, List, String, String, String)}. */
    void enrichCanonicalSystemRadios(Connection connection, List<Map<String,Object>> rows,
                                     String summaryIdColumn, String identifierColumn, String prefix)
        throws SQLException
    {
        enrichCanonicalSystemIdentities(connection, rows, RuleType.RADIO, summaryIdColumn, identifierColumn,
            prefix);
    }

    /**
     * Resolves the ISSI Subscribers page without treating the permanent subscriber number as a local radio ID.
     * An assignment row is owned by the exact saved channel that supplied it: its canonical Alias is tried first
     * in that channel's Alias List, followed only by an Alias for the explicitly assigned Working ID in the same
     * list. Observed-only rows retain the normal canonical system/evidence consensus and never gain a local fallback.
     */
    void enrichIssiSubscriberAliases(Connection connection, List<Map<String,Object>> rows,
                                     String summaryIdColumn, String workingIdColumn,
                                     String observationAliasListColumn, String prefix) throws SQLException
    {
        if(rows.isEmpty())
        {
            return;
        }

        requireBoundedRows(rows);
        //The observing channel owns its Alias List; retained evidence is only a fallback for unowned rows.
        List<Map<String,Object>> fallbackRows = rows.stream()
            .filter(row -> positiveLong(row.get(observationAliasListColumn)) == null).toList();
        Map<String,Set<Long>> aliasListsBySystem = loadAliasLists(connection, systemKeys(fallbackRows));
        Map<Long,List<LocalEvidence>> evidenceBySummary = loadP25LocalEvidence(connection, fallbackRows,
            summaryIdColumn);
        P25SubscriberRuleTargets canonicalTargets = new P25SubscriberRuleTargets();
        RuleTargets workingTargets = new RuleTargets();

        for(Map<String,Object> row: rows)
        {
            Long subscriberIdentityId = positiveLong(row.get("p25_subscriber_identity_id"));
            Set<Long> canonicalAliasLists = issiCanonicalAliasLists(row, aliasListsBySystem,
                evidenceBySummary, summaryIdColumn, observationAliasListColumn);
            canonicalTargets.add(canonicalAliasLists, subscriberIdentityId);

            Long observationAliasListId = positiveLong(row.get(observationAliasListColumn));
            Integer workingId = integer(row.get(workingIdColumn));
            if(observationAliasListId != null && workingId != null)
            {
                workingTargets.add(Set.of(observationAliasListId), workingId);
            }
        }

        P25SubscriberRuleIndex canonical = loadP25SubscriberRules(connection, canonicalTargets);
        RuleIndex working = index(loadRules(connection, RuleType.RADIO, P25_PROTOCOLS, workingTargets));

        for(Map<String,Object> row: rows)
        {
            Long subscriberIdentityId = positiveLong(row.get("p25_subscriber_identity_id"));
            Set<Long> canonicalAliasLists = issiCanonicalAliasLists(row, aliasListsBySystem,
                evidenceBySummary, summaryIdColumn, observationAliasListColumn);
            Rule best = subscriberIdentityId != null ? canonical.best(subscriberIdentityId,
                canonicalAliasLists) : null;
            boolean canonicalMatch = best != null;
            Long observationAliasListId = positiveLong(row.get(observationAliasListColumn));
            Integer workingId = integer(row.get(workingIdColumn));
            if(best == null && observationAliasListId != null && workingId != null)
            {
                best = working.best(workingId, observationAliasListId);
            }

            applyPresentation(row, best, prefix);
            if(best != null)
            {
                //A shared presentation does not make an arbitrary list the editing owner.
                if(observationAliasListId != null || canonicalAliasLists.size() == 1)
                {
                    row.put(prefix + "id", best.aliasId());
                    row.put(prefix + "list_id", best.aliasListId());
                }
                row.put(prefix + "matcher_type", canonicalMatch ? "p25_subscriber_identity" :
                    best.ranged() ? "radio_id_range" : "radio_id");
            }
            row.remove(observationAliasListColumn);
        }
    }

    private static Set<Long> issiCanonicalAliasLists(Map<String,Object> row,
                                                      Map<String,Set<Long>> aliasListsBySystem,
                                                      Map<Long,List<LocalEvidence>> evidenceBySummary,
                                                      String summaryIdColumn,
                                                      String observationAliasListColumn)
    {
        Long observationAliasListId = positiveLong(row.get(observationAliasListColumn));
        if(observationAliasListId != null)
        {
            return Set.of(observationAliasListId);
        }

        Long summaryId = positiveLong(row.get(summaryIdColumn));
        List<LocalEvidence> evidence = summaryId != null ?
            evidenceBySummary.getOrDefault(summaryId, List.of()) : List.of();
        if(!evidence.isEmpty())
        {
            Set<Long> aliasLists = new LinkedHashSet<>();
            evidence.forEach(item -> aliasLists.add(item.aliasListId()));
            return Set.copyOf(aliasLists);
        }

        String systemKey = string(row.get("radio_system_key"));
        return systemKey != null ? aliasListsBySystem.getOrDefault(systemKey, Set.of()) : Set.of();
    }

    void enrichActivity(Connection connection, List<Map<String,Object>> rows) throws SQLException
    {
        if(rows.isEmpty())
        {
            return;
        }

        requireBoundedRows(rows);
        Snapshot snapshot = activitySnapshot(connection, rows);

        for(Map<String,Object> row: rows)
        {
            enrichActivityIdentity(row, snapshot, true, "source_radio_id",
                "source_p25_subscriber_identity_id", "source_observed_working_id", "source_alias_");
            Integer targetKind = integer(row.get("target_kind_code"));

            if(targetKind != null && (targetKind == 1 || targetKind == 3))
            {
                enrichActivityIdentity(row, snapshot, false, "target_id", null, null, "target_alias_");
            }
            else if(targetKind != null && targetKind == 2)
            {
                enrichActivityIdentity(row, snapshot, true, "target_id",
                    "target_p25_subscriber_identity_id", "target_observed_working_id", "target_alias_");
            }
        }
    }

    /**
     * Activity is channel-owned evidence. Every identity resolves its observed local address against the Alias List
     * assigned to that exact saved channel; another channel in a shared P25 system must never lend an alias.
     */
    private void enrichActivityIdentity(Map<String,Object> row, Snapshot snapshot, boolean radio,
                                        String identifierColumn, String p25SubscriberIdentityColumn,
                                        String observedWorkingColumn, String prefix)
    {
        String protocol = string(row.get("protocol"));
        RuleIndex rules;

        if("DMR".equals(protocol))
        {
            rules = radio ? snapshot.dmrRadios() : snapshot.dmrTalkgroups();
            enrichByAssignedAliasList(row, rules, identifierColumn, prefix);
        }
        else if("NXDN".equals(protocol))
        {
            rules = radio ? snapshot.nxdnRadios() : snapshot.nxdnTalkgroups();
            enrichByAssignedAliasList(row, rules, identifierColumn, prefix);
        }
        else if("APCO25".equals(protocol) || "APCO25_PHASE2".equals(protocol) ||
            protocol == null && integer(row.get("wacn")) != null && integer(row.get("system_id")) != null)
        {
            rules = radio ? snapshot.radios() : snapshot.talkgroups();
            Rule best = null;
            Long aliasListId = positiveLong(row.get("alias_list_id"));
            Long p25SubscriberIdentityId = p25SubscriberIdentityColumn != null ?
                positiveLong(row.get(p25SubscriberIdentityColumn)) : null;
            if(radio && aliasListId != null && p25SubscriberIdentityId != null)
            {
                best = snapshot.p25Subscribers().best(p25SubscriberIdentityId, aliasListId);
            }
            if(best != null)
            {
                apply(row, best, prefix);
            }
            else
            {
                String fallbackColumn = radio && p25SubscriberIdentityId != null ? observedWorkingColumn :
                    identifierColumn;
                if(fallbackColumn != null)
                {
                    enrichByAssignedAliasList(row, rules, fallbackColumn, prefix);
                }
            }
        }
    }

    void enrichRelationships(Connection connection, List<Map<String,Object>> rows) throws SQLException
    {
        if(rows.isEmpty())
        {
            return;
        }

        requireBoundedRows(rows);
        Map<String,Set<Long>> aliasLists = loadAliasLists(connection, systemKeys(rows));
        enrichP25SystemRadios(connection, rows, aliasLists, "radio_id",
            "radio_working_subscriber_id", "radio_alias_");
        enrich(rows, loadRules(connection, RuleType.TALKGROUP, P25_PROTOCOLS,
            ruleTargets(rows, row -> systemAliasLists(row, aliasLists),
                source("talkgroup_id", ignored -> true))), aliasLists,
            "talkgroup_id", "talkgroup_alias_");
    }

    /**
     * Resolves a P25 radio shown at system scope. A permanent subscriber Alias has precedence. Once a row carries
     * that canonical foreign key, ordinary radio-number fallback is allowed only for a separately retained explicit
     * Working ID; the permanent subscriber number itself is never treated as a local radio address.
     */
    private void enrichP25SystemRadios(Connection connection, List<Map<String,Object>> rows,
                                       Map<String,Set<Long>> aliasLists, String identifierColumn,
                                       String workingIdColumn, String prefix) throws SQLException
    {
        RuleIndex local = index(loadRules(connection, RuleType.RADIO, P25_PROTOCOLS,
            ruleTargets(rows, row -> systemAliasLists(row, aliasLists),
                source(identifierColumn,
                    row -> positiveLong(row.get("p25_subscriber_identity_id")) == null),
                source(workingIdColumn,
                    row -> positiveLong(row.get("p25_subscriber_identity_id")) != null))));
        P25SubscriberRuleIndex canonical = loadP25SubscriberRules(connection,
            p25SubscriberRuleTargets(rows, row -> systemAliasLists(row, aliasLists),
                p25SubscriberSource("p25_subscriber_identity_id", row -> protocolCode(row) == 1)));

        for(Map<String,Object> row: rows)
        {
            Long p25SubscriberIdentityId = positiveLong(row.get("p25_subscriber_identity_id"));
            String systemKey = string(row.get("radio_system_key"));
            Set<Long> systemAliasLists = systemKey != null ?
                aliasLists.getOrDefault(systemKey, Set.of()) : Set.of();
            Rule best = p25SubscriberIdentityId != null ?
                canonical.best(p25SubscriberIdentityId, systemAliasLists) : null;
            Integer fallback = integer(row.get(p25SubscriberIdentityId != null ?
                workingIdColumn : identifierColumn));
            if(best == null && fallback != null)
            {
                best = local.best(fallback, systemAliasLists);
            }
            applyPresentation(row, best, prefix);
        }
    }

    /**
     * Projects each compact evidence row to the one winning configured alias.  This is deliberately the same
     * precedence used by the normal Stats read models, so overlapping exact and range rules cannot make
     * one observation count against multiple aliases.
     *
     * <p>Expected row fields are {@code protocol_code}, {@code topology}, {@code identity_kind_code},
     * {@code identity_id}, and the normal system or assigned-list lookup fields. A P25 radio row can additionally
     * carry an explicit positive {@code p25_subscriber_identity_id}; that canonical matcher is tried first. Rows
     * without the foreign key, including legacy home-tuple-only rows, use only the channel-local address.</p>
     */
    void resolveEvidenceAliases(Connection connection, List<Map<String,Object>> rows) throws SQLException
    {
        if(rows.isEmpty())
        {
            return;
        }

        requireBoundedRows(rows);
        Map<String,Set<Long>> systemAliasLists = loadAliasLists(connection, systemKeys(rows));
        Map<Long,List<LocalEvidence>> p25Evidence = loadP25LocalEvidence(connection, rows,
            "identity_summary_id");
        Snapshot snapshot = evidenceSnapshot(connection, rows, systemAliasLists, p25Evidence);
        RuleIndex p25Talkgroups = snapshot.talkgroups();
        RuleIndex p25Radios = snapshot.radios();
        P25SubscriberRuleIndex p25Subscribers = snapshot.p25Subscribers();
        RuleIndex dmrTalkgroups = snapshot.dmrTalkgroups();
        RuleIndex dmrRadios = snapshot.dmrRadios();
        RuleIndex nxdnTalkgroups = snapshot.nxdnTalkgroups();
        RuleIndex nxdnRadios = snapshot.nxdnRadios();

        for(Map<String,Object> row: rows)
        {
            Integer identifier = integer(row.get("identity_id"));
            Integer kind = integer(row.get("identity_kind_code"));
            Integer protocol = integer(row.get("protocol_code"));

            if(identifier == null || kind == null || protocol == null)
            {
                continue;
            }

            boolean radio = kind == 2;
            RuleIndex rules = switch(protocol)
            {
                case 1 -> radio ? p25Radios : p25Talkgroups;
                case 3 -> radio ? dmrRadios : dmrTalkgroups;
                case 4 -> radio ? nxdnRadios : nxdnTalkgroups;
                default -> null;
            };

            if(rules == null)
            {
                continue;
            }

            Rule best = null;
            boolean trunkedP25 = protocol == 1 && "TRUNKED".equals(row.get("topology"));
            Long p25SubscriberIdentityId = protocol == 1 && radio ?
                positiveLong(row.get("p25_subscriber_identity_id")) : null;

            if(trunkedP25)
            {
                Long summaryId = positiveLong(row.get("identity_summary_id"));
                List<LocalEvidence> local = summaryId != null ?
                    p25Evidence.getOrDefault(summaryId, List.of()) : List.of();
                if(p25SubscriberIdentityId != null)
                {
                    if(!local.isEmpty())
                    {
                        best = p25Subscribers.best(p25SubscriberIdentityId, local, true);
                    }
                    else
                    {
                        String systemKey = string(row.get("radio_system_key"));
                        Set<Long> aliasLists = systemKey != null ?
                            systemAliasLists.getOrDefault(systemKey, Set.of()) : Set.of();
                        best = p25Subscribers.bestSameAlias(p25SubscriberIdentityId, aliasLists);
                    }
                }
                if(!local.isEmpty())
                {
                    if(best == null)
                    {
                        best = rules.best(local, true);
                    }
                }
                else if(best == null && p25SubscriberIdentityId == null)
                {
                    String systemKey = string(row.get("radio_system_key"));
                    Set<Long> aliasLists = systemKey != null ?
                        systemAliasLists.getOrDefault(systemKey, Set.of()) : Set.of();
                    best = rules.bestSameAlias(identifier, aliasLists);
                }
            }
            else
            {
                Long aliasListId = positiveLong(row.get("alias_list_id"));
                if(aliasListId != null && p25SubscriberIdentityId != null)
                {
                    best = p25Subscribers.best(p25SubscriberIdentityId, aliasListId);
                }
                if(best == null && p25SubscriberIdentityId == null)
                {
                    best = aliasListId != null ? rules.best(identifier, aliasListId) : null;
                }
            }

            if(best != null)
            {
                row.put("resolved_alias_id", best.aliasId());
            }
        }
    }

    /**
     * Migration-only form of {@link #resolveEvidenceAliases(Connection, List)}.  The interactive resolver loads all
     * matching rules for one bounded response and therefore deliberately rejects pathological rule sets.  A database
     * upgrade cannot reject otherwise-valid configuration merely because thousands of ranges overlap one retained
     * identity, so this path asks SQLite for only the single winning exact/range rule for each bounded evidence row.
     */
    void resolveEvidenceAliasesForMigration(Connection connection, List<Map<String,Object>> rows)
        throws SQLException
    {
        if(rows.isEmpty())
        {
            return;
        }

        try(MigrationWinnerResolver resolver = new MigrationWinnerResolver(connection))
        {
            Map<Long,MigrationConsensus> p25Local = loadMigrationP25Consensus(connection, rows, resolver);

            for(Map<String,Object> row: rows)
            {
                Integer identifier = integer(row.get("identity_id"));
                Integer kind = integer(row.get("identity_kind_code"));
                Integer protocol = integer(row.get("protocol_code"));
                if(identifier == null || kind == null || protocol == null ||
                    protocol != 1 && protocol != 3 && protocol != 4)
                {
                    continue;
                }

                int ruleKind = kind == 2 ? 2 : 1;
                Long p25SubscriberIdentityId = protocol == 1 && ruleKind == 2 ?
                    positiveLong(row.get("p25_subscriber_identity_id")) : null;
                Long winner;
                if(protocol == 1 && "TRUNKED".equals(row.get("topology")))
                {
                    Long summaryId = positiveLong(row.get("identity_summary_id"));
                    MigrationConsensus local = summaryId != null ? p25Local.get(summaryId) : null;
                    if(local != null)
                    {
                        winner = local.winner();
                    }
                    else
                    {
                        winner = resolver.resolveSystemConsensus(string(row.get("radio_system_key")),
                            ruleKind, identifier, p25SubscriberIdentityId);
                    }
                }
                else
                {
                    Long aliasListId = positiveLong(row.get("alias_list_id"));
                    winner = aliasListId != null ? resolver.resolvePreferred(protocol, ruleKind, aliasListId,
                        identifier, p25SubscriberIdentityId) : null;
                }

                if(winner != null)
                {
                    row.put("resolved_alias_id", winner);
                }
            }
        }
    }

    /**
     * Streams local P25 site evidence and retains only one consensus value per identity in the current evidence page.
     * No collection grows with the number of retained channel observations or overlapping Alias rules.
     */
    private static Map<Long,MigrationConsensus> loadMigrationP25Consensus(Connection connection,
                                                                           List<Map<String,Object>> rows,
                                                                           MigrationWinnerResolver resolver)
        throws SQLException
    {
        Map<Long,Integer> requested = new LinkedHashMap<>();
        Map<Long,Long> p25Subscribers = new HashMap<>();
        for(Map<String,Object> row: rows)
        {
            if(protocolCode(row) == 1)
            {
                Long summaryId = positiveLong(row.get("identity_summary_id"));
                Integer kind = integer(row.get("identity_kind_code"));
                if(summaryId != null && kind != null)
                {
                    requested.put(summaryId, kind == 2 ? 2 : 1);
                    Long p25SubscriberIdentityId = positiveLong(row.get("p25_subscriber_identity_id"));
                    if(kind == 2 && p25SubscriberIdentityId != null)
                    {
                        p25Subscribers.put(summaryId, p25SubscriberIdentityId);
                    }
                }
            }
        }

        if(requested.isEmpty())
        {
            return Map.of();
        }

        Map<Long,MigrationConsensus> result = new HashMap<>();
        List<Long> ids = List.copyOf(requested.keySet());
        for(int offset = 0; offset < ids.size(); offset += QUERY_VALUE_CHUNK)
        {
            List<Long> chunk = ids.subList(offset, Math.min(ids.size(), offset + QUERY_VALUE_CHUNK));
            try(PreparedStatement statement = connection.prepareStatement(p25LocalEvidenceSql(chunk.size(), false)))
            {
                bind(statement, 1, chunk);
                try(ResultSet resultSet = statement.executeQuery())
                {
                    while(resultSet.next())
                    {
                        long summaryId = resultSet.getLong("identity_summary_id");
                        int kind = requested.getOrDefault(summaryId, 1);
                        Long winner = resolver.resolvePreferred(1, kind, resultSet.getLong("alias_list_id"),
                            resultSet.getInt("observed_local_id"), p25Subscribers.get(summaryId), true);
                        result.computeIfAbsent(summaryId, ignored -> new MigrationConsensus()).accept(winner);
                    }
                }
            }
        }

        return result;
    }

    /**
     * Resolves each observed group identity only against the Alias List ID carried by that row. Unlike the
     * normal system enrichment, this projection deliberately does not consider another list assigned to a second
     * receiver for the same P25 system: the Alias Editor needs to show whether the selected list itself has an exact
     * definition, a range definition, or no definition for the observed identity.
     *
     * <p>Expected row fields are {@code protocol_code}, {@code topology}, {@code group_identity_id}, and
     * {@code alias_list_id}. Trunked P25 rows may also carry a decoded home identity, but matching and alias creation
     * deliberately use only the local talkgroup address.</p>
     */
    void resolveObservedGroupIdentities(Connection connection, List<Map<String,Object>> rows) throws SQLException
    {
        if(rows.isEmpty())
        {
            return;
        }

        requireBoundedRows(rows);
        Snapshot snapshot = observedSnapshot(connection, rows);
        RuleIndex p25 = snapshot.talkgroups();
        RuleIndex dmr = snapshot.dmrTalkgroups();
        RuleIndex nxdn = snapshot.nxdnTalkgroups();

        for(Map<String,Object> row: rows)
        {
            Integer identifier = integer(row.get("group_identity_id"));
            Integer protocol = integer(row.get("protocol_code"));
            Long aliasListId = positiveLong(row.get("alias_list_id"));
            RuleIndex rules = protocol != null ? switch(protocol)
            {
                case 1 -> p25;
                case 3 -> dmr;
                case 4 -> nxdn;
                default -> null;
            } : null;
            Rule best = null;
            boolean promotionSupported = protocol != null && protocol != 1;
            String promotionReason = null;

            if(identifier != null && aliasListId != null && rules != null)
            {
                if(protocol == 1 && "TRUNKED".equals(row.get("topology")))
                {
                    promotionSupported = identifier > 0 && identifier < 0xFFFF;
                    promotionReason = promotionSupported ? null : "The local P25 talkgroup address is reserved";
                    best = rules.best(identifier, aliasListId);
                }
                else if(protocol == 1)
                {
                    //Conventional call buckets predate canonical P25 identity storage. They remain useful for
                    //review, but creating an ordinary Alias from them could mislabel a fully-qualified destination.
                    best = rules.best(identifier, aliasListId);
                    promotionReason = "Conventional P25 observations do not retain canonical identity evidence yet";
                }
                else
                {
                    best = rules.best(identifier, aliasListId);
                }
            }

            row.put("match_kind", best == null ? "none" : best.ranged() ? "range" : "exact");
            row.put("matched_alias_id", best != null ? best.aliasId() : null);
            row.put("matched_alias_name", best != null ? best.name() : null);
            row.put("promotion_supported", promotionSupported);
            row.put("promotion_reason", promotionSupported ? null : promotionReason);
        }
    }

    /**
     * Resolves conventional DMR aliases only from the exact alias list assigned to each saved channel.
     */
    void enrichDmrTalkgroups(Connection connection, List<Map<String,Object>> rows, String identifierColumn,
                             String prefix) throws SQLException
    {
        enrichByAssignedAliasList(connection, rows, RuleType.TALKGROUP, DMR_PROTOCOLS,
            identifierColumn, prefix);
    }

    /**
     * Resolves conventional DMR aliases only from the exact alias list assigned to each saved channel.
     */
    void enrichDmrRadios(Connection connection, List<Map<String,Object>> rows, String identifierColumn,
                         String prefix) throws SQLException
    {
        enrichByAssignedAliasList(connection, rows, RuleType.RADIO, DMR_PROTOCOLS,
            identifierColumn, prefix);
    }

    /**
     * Resolves NXDN aliases only from the exact alias list assigned to each saved channel.
     */
    void enrichNxdnTalkgroups(Connection connection, List<Map<String,Object>> rows, String identifierColumn,
                              String prefix) throws SQLException
    {
        enrichByAssignedAliasList(connection, rows, RuleType.TALKGROUP, NXDN_PROTOCOLS,
            identifierColumn, prefix);
    }

    /**
     * Resolves NXDN aliases only from the exact alias list assigned to each saved channel.
     */
    void enrichNxdnRadios(Connection connection, List<Map<String,Object>> rows, String identifierColumn,
                          String prefix) throws SQLException
    {
        enrichByAssignedAliasList(connection, rows, RuleType.RADIO, NXDN_PROTOCOLS,
            identifierColumn, prefix);
    }

    /**
     * Resolves a conventional P25 identity from its receiver's exact alias list.
     */
    void enrichP25ConventionalTalkgroups(Connection connection, List<Map<String,Object>> rows,
                                         String identifierColumn, String prefix) throws SQLException
    {
        enrichByAssignedAliasList(connection, rows, RuleType.TALKGROUP, P25_PROTOCOLS,
            identifierColumn, prefix);
    }

    /**
     * Resolves a conventional P25 identity from its receiver's exact alias list.
     */
    void enrichP25ConventionalRadios(Connection connection, List<Map<String,Object>> rows,
                                     String identifierColumn, String prefix) throws SQLException
    {
        if(rows.isEmpty())
        {
            return;
        }

        requireBoundedRows(rows);
        RuleIndex local = index(loadRules(connection, RuleType.RADIO, P25_PROTOCOLS,
            ruleTargets(rows, StatsAliasResolver::assignedAliasList,
                source(identifierColumn, row -> positiveLong(row.get("p25_subscriber_identity_id")) == null),
                source("observed_working_id",
                    row -> positiveLong(row.get("p25_subscriber_identity_id")) != null))));
        P25SubscriberRuleIndex canonical = loadP25SubscriberRules(connection,
            p25SubscriberRuleTargets(rows, StatsAliasResolver::assignedAliasList,
                p25SubscriberSource("p25_subscriber_identity_id", ignored -> true)));
        for(Map<String,Object> row: rows)
        {
            Long aliasListId = positiveLong(row.get("alias_list_id"));
            Long p25SubscriberIdentityId = positiveLong(row.get("p25_subscriber_identity_id"));
            Rule best = aliasListId != null && p25SubscriberIdentityId != null ?
                canonical.best(p25SubscriberIdentityId, aliasListId) : null;
            if(best != null)
            {
                apply(row, best, prefix);
            }
            else
            {
                String fallbackColumn = p25SubscriberIdentityId != null ? "observed_working_id" : identifierColumn;
                enrichByAssignedAliasList(row, local, fallbackColumn, prefix);
            }
        }
    }

    private void enrichByAssignedAliasList(Connection connection, List<Map<String,Object>> rows, RuleType type,
                                           List<String> protocols, String identifierColumn, String prefix)
        throws SQLException
    {
        if(rows.isEmpty())
        {
            return;
        }

        requireBoundedRows(rows);
        List<Rule> rules = loadRules(connection, type, protocols,
            ruleTargets(rows, StatsAliasResolver::assignedAliasList,
                source(identifierColumn, ignored -> true)));
        enrichByAssignedAliasList(rows, rules, identifierColumn, prefix);
    }

    private void enrich(List<Map<String,Object>> rows, List<Rule> rules, Map<String,Set<Long>> aliasLists,
                        String identifierColumn, String prefix)
    {
        RuleIndex index = index(rules);

        for(Map<String,Object> row: rows)
        {
            enrich(row, index, aliasLists, identifierColumn, prefix);
        }
    }

    private void enrichCanonicalSystemIdentities(Connection connection, List<Map<String,Object>> rows,
                                                 RuleType type, String summaryIdColumn, String identifierColumn,
                                                 String prefix) throws SQLException
    {
        if(rows.isEmpty())
        {
            return;
        }

        requireBoundedRows(rows);
        Map<String,Set<Long>> aliasListsBySystem = loadAliasLists(connection, systemKeys(rows));
        Map<Long,List<LocalEvidence>> p25Evidence = loadP25LocalEvidence(connection, rows, summaryIdColumn);
        RuleTargets p25Targets = new RuleTargets();
        P25SubscriberRuleTargets p25SubscriberTargets = new P25SubscriberRuleTargets();
        RuleTargets dmrTargets = new RuleTargets();
        RuleTargets nxdnTargets = new RuleTargets();

        for(Map<String,Object> row: rows)
        {
            Integer protocol = integer(row.get("protocol_code"));
            Integer identifier = integer(row.get(identifierColumn));
            String systemKey = string(row.get("radio_system_key"));
            if(protocol == null || systemKey == null)
            {
                continue;
            }

            if(protocol == 1)
            {
                Long subscriberIdentityId = type == RuleType.RADIO ?
                    positiveLong(row.get("p25_subscriber_identity_id")) : null;
                if(identifier == null && subscriberIdentityId == null)
                {
                    continue;
                }
                Long summaryId = positiveLong(row.get(summaryIdColumn));
                List<LocalEvidence> evidence = summaryId != null ?
                    p25Evidence.getOrDefault(summaryId, List.of()) : List.of();
                if(evidence.isEmpty())
                {
                    Set<Long> aliasLists = aliasListsBySystem.getOrDefault(systemKey, Set.of());
                    if(identifier != null && subscriberIdentityId == null)
                    {
                        p25Targets.add(aliasLists, identifier);
                    }
                    p25SubscriberTargets.add(aliasLists, subscriberIdentityId);
                }
                else
                {
                    for(LocalEvidence item: evidence)
                    {
                        if(identifier != null)
                        {
                            p25Targets.add(Set.of(item.aliasListId()), item.observedLocalId());
                        }
                        p25SubscriberTargets.add(Set.of(item.aliasListId()), subscriberIdentityId);
                    }
                }
            }
            else if(protocol == 3 && identifier != null)
            {
                dmrTargets.add(aliasListsBySystem.getOrDefault(systemKey, Set.of()), identifier);
            }
            else if(protocol == 4 && identifier != null)
            {
                nxdnTargets.add(aliasListsBySystem.getOrDefault(systemKey, Set.of()), identifier);
            }
        }

        RuleIndex p25 = index(loadRules(connection, type, P25_PROTOCOLS, p25Targets));
        P25SubscriberRuleIndex p25Subscribers = loadP25SubscriberRules(connection, p25SubscriberTargets);
        RuleIndex dmr = index(loadRules(connection, type, DMR_PROTOCOLS, dmrTargets));
        RuleIndex nxdn = index(loadRules(connection, type, NXDN_PROTOCOLS, nxdnTargets));

        for(Map<String,Object> row: rows)
        {
            Integer protocol = integer(row.get("protocol_code"));
            Integer identifier = integer(row.get(identifierColumn));
            String systemKey = string(row.get("radio_system_key"));
            if(protocol == null || systemKey == null)
            {
                continue;
            }

            Rule best = null;
            if(protocol == 1)
            {
                Long subscriberIdentityId = type == RuleType.RADIO ?
                    positiveLong(row.get("p25_subscriber_identity_id")) : null;
                Long summaryId = positiveLong(row.get(summaryIdColumn));
                List<LocalEvidence> evidence = summaryId != null ?
                    p25Evidence.getOrDefault(summaryId, List.of()) : List.of();
                if(subscriberIdentityId != null)
                {
                    best = evidence.isEmpty() ? p25Subscribers.best(subscriberIdentityId,
                        aliasListsBySystem.getOrDefault(systemKey, Set.of())) :
                        p25Subscribers.best(subscriberIdentityId, evidence, false);
                }
                if(best == null && (subscriberIdentityId == null || !evidence.isEmpty()))
                {
                    best = identifier == null ? null : evidence.isEmpty() ? p25.best(identifier,
                        aliasListsBySystem.getOrDefault(systemKey, Set.of())) : p25.best(evidence, false);
                }
            }
            else if(protocol == 3 && identifier != null)
            {
                best = dmr.best(identifier, aliasListsBySystem.getOrDefault(systemKey, Set.of()));
            }
            else if(protocol == 4 && identifier != null)
            {
                best = nxdn.best(identifier, aliasListsBySystem.getOrDefault(systemKey, Set.of()));
            }

            applyPresentation(row, best, prefix);
        }
    }

    /**
     * Bulk table exports can contain tens of thousands of identities.  Index exact rules once so each row only
     * evaluates rules for its identifier plus the comparatively small set of ranged rules.
     */
    private void enrich(Map<String,Object> row, RuleIndex index, Map<String,Set<Long>> aliasListsBySystem,
                        String identifierColumn, String prefix)
    {
        Integer identifier = integer(row.get(identifierColumn));
        String systemKey = string(row.get("radio_system_key"));

        if(identifier == null)
        {
            return;
        }

        Set<Long> aliasLists = systemKey != null ? aliasListsBySystem.getOrDefault(systemKey, Set.of()) : Set.of();
        Rule best = index.best(identifier, aliasLists);
        applyPresentation(row, best, prefix);
    }

    private void enrichByAssignedAliasList(List<Map<String,Object>> rows, List<Rule> rules, String identifierColumn,
                                           String prefix)
    {
        RuleIndex index = index(rules);

        for(Map<String,Object> row: rows)
        {
            enrichByAssignedAliasList(row, index, identifierColumn, prefix);
        }
    }

    private void enrichByAssignedAliasList(Map<String,Object> row, RuleIndex index, String identifierColumn,
                                           String prefix)
    {
        Integer identifier = integer(row.get(identifierColumn));
        Long aliasListId = positiveLong(row.get("alias_list_id"));

        if(identifier == null || aliasListId == null)
        {
            return;
        }

        Rule best = index.best(identifier, aliasListId);
        apply(row, best, prefix);
    }

    private static void apply(Map<String,Object> row, Rule rule, String prefix)
    {
        if(rule != null)
        {
            applyPresentation(row, rule, prefix);
            row.put(prefix + "list_id", rule.aliasListId());
            row.put(prefix + "list_name", rule.aliasListName());
        }
    }

    /**
     * Applies only the alias facts that are meaningful across every channel-owned Alias List for a radio system.
     * The matching rule deliberately does not make any one Alias List look system-owned.
     */
    private static void applyPresentation(Map<String,Object> row, Rule rule, String prefix)
    {
        if(rule != null)
        {
            row.put(prefix + "name", rule.name());
            row.put(prefix + "description", rule.description());
            row.put(prefix + "group", rule.group());
            row.put(prefix + "color", rule.color());
        }
    }

    private static RuleIndex index(List<Rule> rules)
    {
        Map<Long,AliasListRules> rulesByAliasList = new HashMap<>();

        for(Rule rule: rules)
        {
            rulesByAliasList.computeIfAbsent(rule.aliasListId(), ignored -> new AliasListRules()).add(rule);
        }

        return new RuleIndex(Map.copyOf(rulesByAliasList));
    }

    private Snapshot activitySnapshot(Connection connection, List<Map<String,Object>> rows) throws SQLException
    {
        Predicate<Map<String,Object>> talkgroupTarget = row -> {
            Integer kind = integer(row.get("target_kind_code"));
            return kind != null && (kind == 1 || kind == 3);
        };
        Predicate<Map<String,Object>> radioTarget = row -> Integer.valueOf(2)
            .equals(integer(row.get("target_kind_code")));

        return new Snapshot(
            index(loadRules(connection, RuleType.TALKGROUP, P25_PROTOCOLS,
                ruleTargets(rows, StatsAliasResolver::assignedAliasList,
                    source("target_id", row -> protocolCode(row) == 1 && talkgroupTarget.test(row))))),
            index(loadRules(connection, RuleType.RADIO, P25_PROTOCOLS,
                ruleTargets(rows, StatsAliasResolver::assignedAliasList,
                    source("source_radio_id", row -> protocolCode(row) == 1 &&
                        positiveLong(row.get("source_p25_subscriber_identity_id")) == null),
                    source("source_observed_working_id", row -> protocolCode(row) == 1 &&
                        positiveLong(row.get("source_p25_subscriber_identity_id")) != null),
                    source("target_id", row -> protocolCode(row) == 1 && radioTarget.test(row) &&
                        positiveLong(row.get("target_p25_subscriber_identity_id")) == null),
                    source("target_observed_working_id", row -> protocolCode(row) == 1 &&
                        radioTarget.test(row) &&
                        positiveLong(row.get("target_p25_subscriber_identity_id")) != null)))),
            loadP25SubscriberRules(connection,
                p25SubscriberRuleTargets(rows, StatsAliasResolver::assignedAliasList,
                    p25SubscriberSource("source_p25_subscriber_identity_id", row -> protocolCode(row) == 1),
                    p25SubscriberSource("target_p25_subscriber_identity_id",
                        row -> protocolCode(row) == 1 && radioTarget.test(row)))),
            index(loadRules(connection, RuleType.TALKGROUP, DMR_PROTOCOLS,
                ruleTargets(rows, StatsAliasResolver::assignedAliasList,
                    source("target_id", row -> protocolCode(row) == 3 && talkgroupTarget.test(row))))),
            index(loadRules(connection, RuleType.RADIO, DMR_PROTOCOLS,
                ruleTargets(rows, StatsAliasResolver::assignedAliasList,
                    source("source_radio_id", row -> protocolCode(row) == 3),
                    source("target_id", row -> protocolCode(row) == 3 && radioTarget.test(row))))),
            index(loadRules(connection, RuleType.TALKGROUP, NXDN_PROTOCOLS,
                ruleTargets(rows, StatsAliasResolver::assignedAliasList,
                    source("target_id", row -> protocolCode(row) == 4 && talkgroupTarget.test(row))))),
            index(loadRules(connection, RuleType.RADIO, NXDN_PROTOCOLS,
                ruleTargets(rows, StatsAliasResolver::assignedAliasList,
                    source("source_radio_id", row -> protocolCode(row) == 4),
                    source("target_id", row -> protocolCode(row) == 4 && radioTarget.test(row))))));
    }

    private Snapshot evidenceSnapshot(Connection connection, List<Map<String,Object>> rows,
                                      Map<String,Set<Long>> systemAliasLists,
                                      Map<Long,List<LocalEvidence>> p25Evidence) throws SQLException
    {
        Predicate<Map<String,Object>> talkgroup = row -> !Integer.valueOf(2)
            .equals(integer(row.get("identity_kind_code")));
        Predicate<Map<String,Object>> radio = row -> Integer.valueOf(2)
            .equals(integer(row.get("identity_kind_code")));

        RuleTargets p25TalkgroupTargets = canonicalEvidenceTargets(rows, p25Evidence, systemAliasLists, talkgroup);
        RuleTargets p25RadioTargets = canonicalEvidenceTargets(rows, p25Evidence, systemAliasLists, radio);
        P25SubscriberRuleTargets p25SubscriberTargets = canonicalP25SubscriberEvidenceTargets(rows,
            p25Evidence, systemAliasLists, radio);

        return new Snapshot(
            index(loadRules(connection, RuleType.TALKGROUP, P25_PROTOCOLS, p25TalkgroupTargets)),
            index(loadRules(connection, RuleType.RADIO, P25_PROTOCOLS, p25RadioTargets)),
            loadP25SubscriberRules(connection, p25SubscriberTargets),
            index(loadRules(connection, RuleType.TALKGROUP, DMR_PROTOCOLS,
                ruleTargets(rows, StatsAliasResolver::assignedAliasList,
                    source("identity_id", row -> protocolCode(row) == 3 && talkgroup.test(row))))),
            index(loadRules(connection, RuleType.RADIO, DMR_PROTOCOLS,
                ruleTargets(rows, StatsAliasResolver::assignedAliasList,
                    source("identity_id", row -> protocolCode(row) == 3 && radio.test(row))))),
            index(loadRules(connection, RuleType.TALKGROUP, NXDN_PROTOCOLS,
                ruleTargets(rows, StatsAliasResolver::assignedAliasList,
                    source("identity_id", row -> protocolCode(row) == 4 && talkgroup.test(row))))),
            index(loadRules(connection, RuleType.RADIO, NXDN_PROTOCOLS,
                ruleTargets(rows, StatsAliasResolver::assignedAliasList,
                    source("identity_id", row -> protocolCode(row) == 4 && radio.test(row))))));
    }

    private static RuleTargets canonicalEvidenceTargets(List<Map<String,Object>> rows,
                                                        Map<Long,List<LocalEvidence>> p25Evidence,
                                                        Map<String,Set<Long>> systemAliasLists,
                                                        Predicate<Map<String,Object>> include)
    {
        RuleTargets targets = new RuleTargets();
        for(Map<String,Object> row: rows)
        {
            if(protocolCode(row) != 1 || !include.test(row))
            {
                continue;
            }
            Long summaryId = positiveLong(row.get("identity_summary_id"));
            List<LocalEvidence> local = summaryId != null ?
                p25Evidence.getOrDefault(summaryId, List.of()) : List.of();
            if(!local.isEmpty())
            {
                for(LocalEvidence item: local)
                {
                    targets.add(Set.of(item.aliasListId()), item.observedLocalId());
                }
            }
            else
            {
                Integer identifier = integer(row.get("identity_id"));
                if(identifier == null || Integer.valueOf(2).equals(integer(row.get("identity_kind_code"))) &&
                    positiveLong(row.get("p25_subscriber_identity_id")) != null)
                {
                    continue;
                }

                if("TRUNKED".equals(row.get("topology")))
                {
                    String systemKey = string(row.get("radio_system_key"));
                    if(systemKey != null)
                    {
                        targets.add(systemAliasLists.getOrDefault(systemKey, Set.of()), identifier);
                    }
                }
                else
                {
                    targets.add(assignedAliasList(row), identifier);
                }
            }
        }
        return targets;
    }

    private static P25SubscriberRuleTargets canonicalP25SubscriberEvidenceTargets(
        List<Map<String,Object>> rows, Map<Long,List<LocalEvidence>> p25Evidence,
        Map<String,Set<Long>> systemAliasLists, Predicate<Map<String,Object>> include)
    {
        P25SubscriberRuleTargets targets = new P25SubscriberRuleTargets();
        for(Map<String,Object> row: rows)
        {
            Long p25SubscriberIdentityId = positiveLong(row.get("p25_subscriber_identity_id"));
            if(protocolCode(row) != 1 || p25SubscriberIdentityId == null || !include.test(row))
            {
                continue;
            }

            Long summaryId = positiveLong(row.get("identity_summary_id"));
            List<LocalEvidence> local = summaryId != null ?
                p25Evidence.getOrDefault(summaryId, List.of()) : List.of();
            if(!local.isEmpty())
            {
                for(LocalEvidence item: local)
                {
                    targets.add(Set.of(item.aliasListId()), p25SubscriberIdentityId);
                }
            }
            else if("TRUNKED".equals(row.get("topology")))
            {
                String systemKey = string(row.get("radio_system_key"));
                if(systemKey != null)
                {
                    targets.add(systemAliasLists.getOrDefault(systemKey, Set.of()), p25SubscriberIdentityId);
                }
            }
            else
            {
                targets.add(assignedAliasList(row), p25SubscriberIdentityId);
            }
        }
        return targets;
    }

    private Snapshot observedSnapshot(Connection connection, List<Map<String,Object>> rows) throws SQLException
    {
        return new Snapshot(
            index(loadRules(connection, RuleType.TALKGROUP, P25_PROTOCOLS,
                ruleTargets(rows, StatsAliasResolver::assignedAliasList,
                    source("group_identity_id", row -> protocolCode(row) == 1)))),
            RuleIndex.empty(),
            P25SubscriberRuleIndex.empty(),
            index(loadRules(connection, RuleType.TALKGROUP, DMR_PROTOCOLS,
                ruleTargets(rows, StatsAliasResolver::assignedAliasList,
                    source("group_identity_id", row -> protocolCode(row) == 3)))),
            RuleIndex.empty(),
            index(loadRules(connection, RuleType.TALKGROUP, NXDN_PROTOCOLS,
                ruleTargets(rows, StatsAliasResolver::assignedAliasList,
                    source("group_identity_id", row -> protocolCode(row) == 4)))),
            RuleIndex.empty());
    }

    /**
     * Loads only channel-owned local-address evidence for the bounded canonical P25 identities in this response.
     * The canonical home identity is never used as an Alias lookup value when a receiver observed a different local
     * address on a site. Compact call/member/presence/affiliation facts are authoritative when present; retained
     * detailed events are an index-backed fallback for signaling-only or legacy identities. This keeps a common
     * groups page from revisiting every retained event for identities already represented by compact facts.
     */
    private Map<Long,List<LocalEvidence>> loadP25LocalEvidence(Connection connection,
                                                               List<Map<String,Object>> rows,
                                                               String summaryIdColumn) throws SQLException
    {
        Set<Long> requested = new LinkedHashSet<>();
        for(Map<String,Object> row: rows)
        {
            if(protocolCode(row) == 1)
            {
                Long summaryId = positiveLong(row.get(summaryIdColumn));
                if(summaryId != null)
                {
                    requested.add(summaryId);
                }
            }
        }

        if(requested.isEmpty())
        {
            return Map.of();
        }

        Map<Long,Set<LocalEvidence>> evidence = new LinkedHashMap<>();
        List<Long> ids = List.copyOf(requested);
        int evidenceCount = 0;

        for(int offset = 0; offset < ids.size(); offset += QUERY_VALUE_CHUNK)
        {
            List<Long> chunk = ids.subList(offset, Math.min(ids.size(), offset + QUERY_VALUE_CHUNK));
            String sql = p25LocalEvidenceSql(chunk.size());

            try(PreparedStatement statement = connection.prepareStatement(sql))
            {
                int parameter = bind(statement, 1, chunk);
                statement.setInt(parameter, MAX_RULE_LOOKUP_PAIRS - evidenceCount + 1);
                try(ResultSet resultSet = statement.executeQuery())
                {
                    while(resultSet.next())
                    {
                        if(++evidenceCount > MAX_RULE_LOOKUP_PAIRS)
                        {
                            throw tooLarge("Alias lookup references too many channel-local identity pairs");
                        }
                        long summaryId = resultSet.getLong("identity_summary_id");
                        LocalEvidence item = new LocalEvidence(resultSet.getLong("alias_list_id"),
                            resultSet.getInt("observed_local_id"));
                        evidence.computeIfAbsent(summaryId, ignored -> new LinkedHashSet<>()).add(item);
                    }
                }
            }
        }

        Map<Long,List<LocalEvidence>> result = new LinkedHashMap<>();
        evidence.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }

    /** Exact bounded query used by the groups/radios page and query-plan regression coverage. */
    static String p25LocalEvidenceSql(int requestedCount)
    {
        return p25LocalEvidenceSql(requestedCount, true);
    }

    private static String p25LocalEvidenceSql(int requestedCount, boolean bounded)
    {
        if(requestedCount < 1 || requestedCount > QUERY_VALUE_CHUNK)
        {
            throw new IllegalArgumentException("P25 local evidence query size is out of bounds");
        }

        String sql = """
                WITH requested(identity_summary_id) AS (VALUES %s), compact_evidence AS (
                    SELECT bucket.identity_summary_id, bucket.channel_id,
                        CASE WHEN summary.identity_kind_code = 2
                                  AND summary.p25_subscriber_identity_id IS NOT NULL
                             THEN bucket.observed_working_id ELSE bucket.observed_local_id END AS observed_local_id
                    FROM requested
                    CROSS JOIN p25_site_call_identity_bucket bucket
                        INDEXED BY idx_p25_site_call_identity_identity
                      ON bucket.identity_summary_id = requested.identity_summary_id
                    JOIN radio_system_identity_summary summary ON summary.id = bucket.identity_summary_id
                    WHERE CASE WHEN summary.identity_kind_code = 2
                                      AND summary.p25_subscriber_identity_id IS NOT NULL
                               THEN bucket.observed_working_id ELSE bucket.observed_local_id END > 0
                    UNION
                    SELECT member.identity_summary_id, event.channel_id, member.observed_local_id
                    FROM requested
                    JOIN activity_event_identity_member member
                      ON member.identity_summary_id = requested.identity_summary_id
                    JOIN receiver_activity_event event ON event.id = member.event_id
                    WHERE member.observed_local_id > 0
                    UNION
                    SELECT presence.radio_identity_id, presence.channel_id,
                        CASE WHEN summary.p25_subscriber_identity_id IS NOT NULL
                             THEN presence.observed_working_id ELSE presence.observed_local_id END
                    FROM requested
                    JOIN trunked_radio_channel_presence presence
                      ON presence.radio_identity_id = requested.identity_summary_id
                    JOIN radio_system_identity_summary summary ON summary.id = presence.radio_identity_id
                    WHERE CASE WHEN summary.p25_subscriber_identity_id IS NOT NULL
                               THEN presence.observed_working_id ELSE presence.observed_local_id END > 0
                    UNION
                    SELECT affiliation.radio_identity_id, affiliation.channel_id,
                        CASE WHEN summary.p25_subscriber_identity_id IS NOT NULL
                             THEN affiliation.radio_observed_working_id
                             ELSE affiliation.radio_observed_local_id END
                    FROM requested
                    JOIN trunked_radio_affiliation affiliation
                      ON affiliation.radio_identity_id = requested.identity_summary_id
                    JOIN radio_system_identity_summary summary ON summary.id = affiliation.radio_identity_id
                    WHERE CASE WHEN summary.p25_subscriber_identity_id IS NOT NULL
                               THEN affiliation.radio_observed_working_id
                               ELSE affiliation.radio_observed_local_id END > 0
                    UNION
                    SELECT affiliation.talkgroup_identity_id, affiliation.channel_id,
                        affiliation.talkgroup_observed_local_id
                    FROM requested
                    JOIN trunked_radio_affiliation affiliation
                      ON affiliation.talkgroup_identity_id = requested.identity_summary_id
                    WHERE affiliation.talkgroup_observed_local_id > 0
                ), detail_fallback(identity_summary_id) AS (
                    SELECT requested.identity_summary_id
                    FROM requested
                    WHERE NOT EXISTS (
                        SELECT 1 FROM compact_evidence
                        WHERE compact_evidence.identity_summary_id = requested.identity_summary_id
                    )
                ), local_evidence AS (
                    SELECT identity_summary_id, channel_id, observed_local_id
                    FROM compact_evidence
                    UNION
                    %s
                    UNION
                    SELECT event.target_identity_summary_id, event.channel_id,
                        CASE WHEN event.target_kind_code = 2
                                  AND summary.p25_subscriber_identity_id IS NOT NULL
                             THEN event.target_observed_working_id ELSE event.target_observed_local_id END
                    FROM detail_fallback
                    CROSS JOIN receiver_activity_event event
                        INDEXED BY idx_receiver_activity_event_target_time
                      ON event.target_identity_summary_id = detail_fallback.identity_summary_id
                    JOIN radio_system_identity_summary summary ON summary.id = event.target_identity_summary_id
                    WHERE CASE WHEN event.target_kind_code = 2
                                      AND summary.p25_subscriber_identity_id IS NOT NULL
                               THEN event.target_observed_working_id ELSE event.target_observed_local_id END > 0
                )
                SELECT DISTINCT local_evidence.identity_summary_id, config.alias_list_id,
                    local_evidence.observed_local_id
                FROM local_evidence
                JOIN receiver_channel channel ON channel.id = local_evidence.channel_id
                JOIN radio_system system ON system.id = channel.radio_system_id
                JOIN configuration_channel config
                  ON config.configuration_id = channel.configuration_id
                WHERE system.protocol_code = 1 AND config.alias_list_id IS NOT NULL
                  AND local_evidence.observed_local_id > 0
                ORDER BY local_evidence.identity_summary_id, config.alias_list_id,
                    local_evidence.observed_local_id
                """.formatted(valuesPlaceholders(requestedCount), p25SourceEvidenceSql(bounded));
        return bounded ? sql + " LIMIT ?" : sql;
    }

    /** Earlier migration steps resolve consensus before the current Working-ID evidence index exists. */
    private static String p25SourceEvidenceSql(boolean currentIndex)
    {
        if(!currentIndex)
        {
            return """
                SELECT event.source_identity_summary_id, event.channel_id,
                    CASE WHEN summary.p25_subscriber_identity_id IS NOT NULL
                         THEN event.source_observed_working_id ELSE event.source_observed_local_id END
                FROM detail_fallback
                CROSS JOIN receiver_activity_event event
                    INDEXED BY idx_receiver_activity_event_source_time
                  ON event.source_identity_summary_id = detail_fallback.identity_summary_id
                JOIN radio_system_identity_summary summary ON summary.id = event.source_identity_summary_id
                WHERE CASE WHEN summary.p25_subscriber_identity_id IS NOT NULL
                           THEN event.source_observed_working_id ELSE event.source_observed_local_id END > 0
                """;
        }

        return """
            SELECT event.source_identity_summary_id, event.channel_id, event.source_observed_local_id
            FROM detail_fallback
            JOIN radio_system_identity_summary summary ON summary.id = detail_fallback.identity_summary_id
            CROSS JOIN receiver_activity_event event
                INDEXED BY idx_receiver_activity_event_source_time
              ON event.source_identity_summary_id = detail_fallback.identity_summary_id
            WHERE summary.p25_subscriber_identity_id IS NULL AND event.source_observed_local_id > 0
            UNION
            SELECT event.source_identity_summary_id, event.channel_id, event.source_observed_working_id
            FROM detail_fallback
            JOIN radio_system_identity_summary summary ON summary.id = detail_fallback.identity_summary_id
            CROSS JOIN receiver_activity_event event
                INDEXED BY idx_receiver_activity_event_source_working_evidence
              ON event.source_identity_summary_id = detail_fallback.identity_summary_id
            WHERE summary.p25_subscriber_identity_id IS NOT NULL AND event.source_observed_working_id > 0
            """;
    }

    private Map<String,Set<Long>> loadAliasLists(Connection connection, Set<String> systemKeys)
        throws SQLException
    {
        if(systemKeys.isEmpty())
        {
            return Map.of();
        }

        Map<String,Set<Long>> aliasLists = new HashMap<>();
        Set<Long> loadedListIds = new HashSet<>();
        AliasListPairBudget pairBudget = new AliasListPairBudget(MAX_SYSTEM_ALIAS_LIST_PAIRS);
        List<String> keys = List.copyOf(systemKeys);

        for(int offset = 0; offset < keys.size(); offset += QUERY_VALUE_CHUNK)
        {
            List<String> chunk = keys.subList(offset, Math.min(keys.size(), offset + QUERY_VALUE_CHUNK));
            String sql = """
                WITH requested(system_key) AS (VALUES %s),
                assigned_alias_list(system_key, alias_list_id) AS (
                    SELECT system.system_key, configuration.alias_list_id
                    FROM requested
                    JOIN radio_system system ON system.system_key = requested.system_key
                    JOIN receiver_channel channel ON channel.radio_system_id = system.id
                    JOIN configuration_channel configuration
                      ON configuration.configuration_id = channel.configuration_id
                    WHERE configuration.alias_list_id IS NOT NULL
                    UNION
                    SELECT system.system_key, configuration.alias_list_id
                    FROM requested
                    JOIN radio_system system ON system.system_key = requested.system_key
                    JOIN configuration_channel configuration
                      ON configuration.configuration_id = system.configuration_id
                    WHERE configuration.alias_list_id IS NOT NULL
                )
                SELECT system_key, alias_list_id
                FROM assigned_alias_list
                ORDER BY system_key, alias_list_id
                LIMIT ?
                """.formatted(valuesPlaceholders(chunk.size()));

            try(PreparedStatement statement = connection.prepareStatement(sql))
            {
                int parameter = bind(statement, 1, chunk);
                statement.setInt(parameter, pairBudget.queryLimit());

                try(ResultSet resultSet = statement.executeQuery())
                {
                    while(resultSet.next())
                    {
                        String systemKey = resultSet.getString("system_key");
                        long aliasListId = resultSet.getLong("alias_list_id");
                        pairBudget.add(systemKey, aliasListId);
                        loadedListIds.add(aliasListId);
                        requireMaximum(loadedListIds.size(), MAX_ALIAS_LISTS,
                            "Alias lookup references too many alias lists");
                        aliasLists.computeIfAbsent(systemKey, ignored -> new HashSet<>())
                            .add(aliasListId);
                    }
                }
            }
        }

        Map<String,Set<Long>> immutable = new HashMap<>();
        aliasLists.forEach((key, value) -> immutable.put(key, Set.copyOf(value)));
        return Map.copyOf(immutable);
    }

    private List<Rule> loadRules(Connection connection, RuleType type, List<String> protocols,
                                 RuleTargets targets) throws SQLException
    {
        if(targets.isEmpty())
        {
            return List.of();
        }

        Map<Long,Rule> rules = new LinkedHashMap<>();
        List<RuleTarget> pairs = targets.pairs();

        for(int offset = 0; offset < pairs.size(); offset += RULE_TARGET_CHUNK)
        {
            List<RuleTarget> chunk = pairs.subList(offset, Math.min(pairs.size(), offset + RULE_TARGET_CHUNK));
            loadRules(connection, type, protocols, chunk, false, rules);
            loadRules(connection, type, protocols, chunk, true, rules);
        }

        return List.copyOf(rules.values());
    }

    /** Loads only exact canonical subscriber aliases requested by this bounded page. */
    private P25SubscriberRuleIndex loadP25SubscriberRules(Connection connection,
                                                           P25SubscriberRuleTargets targets) throws SQLException
    {
        if(targets.isEmpty())
        {
            return P25SubscriberRuleIndex.empty();
        }

        Map<P25SubscriberRuleTarget,Rule> rules = new LinkedHashMap<>();
        List<P25SubscriberRuleTarget> pairs = targets.pairs();
        for(int offset = 0; offset < pairs.size(); offset += RULE_TARGET_CHUNK)
        {
            List<P25SubscriberRuleTarget> chunk = pairs.subList(offset,
                Math.min(pairs.size(), offset + RULE_TARGET_CHUNK));
            String sql = """
                WITH requested(alias_list_id, p25_subscriber_identity_id) AS (VALUES %s)
                SELECT requested.alias_list_id, requested.p25_subscriber_identity_id,
                    definition.id AS alias_id, definition.name, definition.description,
                    definition.group_name, definition.color, list.name AS alias_list_name
                FROM requested
                JOIN alias_p25_subscriber_identity subscriber_alias
                  ON subscriber_alias.p25_subscriber_identity_id = requested.p25_subscriber_identity_id
                JOIN alias definition
                  ON definition.id = subscriber_alias.alias_id
                 AND definition.alias_list_id = requested.alias_list_id
                JOIN alias_list list ON list.id = definition.alias_list_id
                WHERE definition.matcher_type = 'P25_SUBSCRIBER_IDENTITY'
                ORDER BY definition.id
                LIMIT ?
                """.formatted(pairPlaceholders(chunk.size()));

            try(PreparedStatement statement = connection.prepareStatement(sql))
            {
                int parameter = 1;
                for(P25SubscriberRuleTarget target: chunk)
                {
                    statement.setLong(parameter++, target.aliasListId());
                    statement.setLong(parameter++, target.p25SubscriberIdentityId());
                }
                statement.setInt(parameter, MAX_LOADED_RULES + 1);
                try(ResultSet resultSet = statement.executeQuery())
                {
                    int loaded = 0;
                    while(resultSet.next())
                    {
                        if(++loaded > MAX_LOADED_RULES)
                        {
                            throw tooLarge("Alias lookup matched too many canonical subscriber rules");
                        }
                        P25SubscriberRuleTarget target = new P25SubscriberRuleTarget(
                            resultSet.getLong("alias_list_id"),
                            resultSet.getLong("p25_subscriber_identity_id"));
                        if(!rules.containsKey(target) && rules.size() >= MAX_LOADED_RULES)
                        {
                            throw tooLarge("Alias lookup matched too many canonical subscriber rules");
                        }
                        Rule rule = new Rule(null, null, null, false, resultSet.getString("name"),
                            resultSet.getString("description"), resultSet.getString("group_name"),
                            resultSet.getInt("color"), target.aliasListId(),
                            resultSet.getString("alias_list_name"), resultSet.getLong("alias_id"));
                        Rule current = rules.get(target);
                        if(current == null || rule.aliasId() > current.aliasId())
                        {
                            rules.put(target, rule);
                        }
                    }
                }
            }
        }
        return new P25SubscriberRuleIndex(Map.copyOf(rules));
    }

    private void loadRules(Connection connection, RuleType type, List<String> protocols,
                           List<RuleTarget> targets, boolean ranged,
                           Map<Long,Rule> rules) throws SQLException
    {
        String index = ranged ? type.rangeIndex() : type.valueIndex();
        String matcher = ranged ? type.rangeMatcher() : type.valueMatcher();
        String match = ranged ? "requested.identifier BETWEEN definition.min_value AND definition.max_value" :
            "requested.identifier = definition.value";
        String sql = """
            WITH requested(alias_list_id, identifier) AS (VALUES %s)
            SELECT definition.id AS alias_id, definition.value, definition.min_value,
                definition.max_value, definition.name, definition.description, definition.group_name,
                definition.color, definition.alias_list_id, list.name AS alias_list_name
            FROM alias definition INDEXED BY %s
            JOIN alias_list list ON list.id = definition.alias_list_id
            WHERE definition.matcher_type = '%s'
              AND definition.protocol IN (%s)
              AND EXISTS (SELECT 1 FROM requested
                          WHERE definition.alias_list_id = requested.alias_list_id AND %s)
            ORDER BY definition.id
            LIMIT ?
            """.formatted(pairPlaceholders(targets.size()), index, matcher,
            placeholders(protocols.size()), match);

        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            int parameter = 1;

            for(RuleTarget target: targets)
            {
                statement.setLong(parameter++, target.aliasListId());
                statement.setInt(parameter++, target.identifier());
            }

            parameter = bind(statement, parameter, protocols);
            statement.setInt(parameter, MAX_LOADED_RULES + 1);

            try(ResultSet resultSet = statement.executeQuery())
            {
                while(resultSet.next())
                {
                    long aliasId = resultSet.getLong("alias_id");

                    if(rules.containsKey(aliasId))
                    {
                        continue;
                    }

                    if(rules.size() >= MAX_LOADED_RULES)
                    {
                        throw tooLarge("Alias lookup matched too many configured rules");
                    }

                    rules.put(aliasId, new Rule(integer(resultSet.getObject("value")),
                        integer(resultSet.getObject("min_value")), integer(resultSet.getObject("max_value")),
                        ranged, resultSet.getString("name"), resultSet.getString("description"),
                        resultSet.getString("group_name"), resultSet.getInt("color"),
                        resultSet.getLong("alias_list_id"), resultSet.getString("alias_list_name"), aliasId));
                }
            }
        }
    }

    private static Set<String> systemKeys(List<Map<String,Object>> rows)
    {
        Set<String> keys = new LinkedHashSet<>();

        for(Map<String,Object> row: rows)
        {
            String key = string(row.get("radio_system_key"));

            if(key != null)
            {
                keys.add(key);
                requireMaximum(keys.size(), MAX_SYSTEM_KEYS, "Alias lookup references too many systems");
            }
        }

        return Set.copyOf(keys);
    }

    @SafeVarargs
    private static RuleTargets ruleTargets(List<Map<String,Object>> rows,
                                           Function<Map<String,Object>,Set<Long>> aliasLists,
                                           IdentifierSource... sources)
    {
        RuleTargets targets = new RuleTargets();

        for(Map<String,Object> row: rows)
        {
            Set<Long> rowAliasLists = aliasLists.apply(row);

            if(rowAliasLists == null || rowAliasLists.isEmpty())
            {
                continue;
            }

            for(IdentifierSource source: sources)
            {
                Integer identifier = source.include().test(row) ? integer(row.get(source.column())) : null;

                if(identifier != null)
                {
                    targets.add(rowAliasLists, identifier);
                }
            }
        }

        return targets;
    }

    private static IdentifierSource source(String column, Predicate<Map<String,Object>> include)
    {
        return new IdentifierSource(column, include);
    }

    @SafeVarargs
    private static P25SubscriberRuleTargets p25SubscriberRuleTargets(List<Map<String,Object>> rows,
        Function<Map<String,Object>,Set<Long>> aliasLists, P25SubscriberSource... sources)
    {
        P25SubscriberRuleTargets targets = new P25SubscriberRuleTargets();
        for(Map<String,Object> row: rows)
        {
            Set<Long> rowAliasLists = aliasLists.apply(row);
            if(rowAliasLists == null || rowAliasLists.isEmpty())
            {
                continue;
            }
            for(P25SubscriberSource source: sources)
            {
                Long identityId = source.include().test(row) ? positiveLong(row.get(source.column())) : null;
                targets.add(rowAliasLists, identityId);
            }
        }
        return targets;
    }

    private static P25SubscriberSource p25SubscriberSource(String column,
                                                            Predicate<Map<String,Object>> include)
    {
        return new P25SubscriberSource(column, include);
    }

    private static Set<Long> systemAliasLists(Map<String,Object> row,
                                              Map<String,Set<Long>> aliasListsBySystem)
    {
        String systemKey = string(row.get("radio_system_key"));
        return systemKey != null ? aliasListsBySystem.getOrDefault(systemKey, Set.of()) : Set.of();
    }

    private static Set<Long> p25AliasLists(Map<String,Object> row,
                                           Map<String,Set<Long>> aliasListsBySystem)
    {
        Set<Long> system = systemAliasLists(row, aliasListsBySystem);
        return !system.isEmpty() ? system : assignedAliasList(row);
    }

    private static Set<Long> assignedAliasList(Map<String,Object> row)
    {
        Long aliasListId = positiveLong(row.get("alias_list_id"));
        return aliasListId != null ? Set.of(aliasListId) : Set.of();
    }

    private static void requireBoundedRows(List<Map<String,Object>> rows)
    {
        requireMaximum(rows.size(), MAX_INPUT_ROWS, "Alias lookup page is too large");
    }

    private static void requireMaximum(int size, int maximum, String message)
    {
        if(size > maximum)
        {
            throw tooLarge(message);
        }
    }

    private static StatsApiException tooLarge(String message)
    {
        return new StatsApiException(413, "response_too_large", message);
    }

    private static int protocolCode(Map<String,Object> row)
    {
        Integer code = integer(row.get("protocol_code"));

        if(code != null)
        {
            return code == 2 ? 1 : code;
        }

        String protocol = string(row.get("protocol"));

        if(protocol == null)
        {
            return 0;
        }

        return switch(protocol.toUpperCase(Locale.ROOT))
        {
            case "P25", "APCO25", "APCO25_PHASE2" -> 1;
            case "DMR" -> 3;
            case "NXDN" -> 4;
            default -> 0;
        };
    }

    private static String valuesPlaceholders(int count)
    {
        return String.join(",", java.util.Collections.nCopies(count, "(?)"));
    }

    private static String pairPlaceholders(int count)
    {
        return String.join(",", java.util.Collections.nCopies(count, "(?,?)"));
    }

    private static String placeholders(int count)
    {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static int bind(PreparedStatement statement, int parameter, List<?> values) throws SQLException
    {
        for(Object value: values)
        {
            statement.setObject(parameter++, value);
        }

        return parameter;
    }

    private static Integer integer(Object value)
    {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Long positiveLong(Object value)
    {
        return value instanceof Number number && number.longValue() > 0 ? number.longValue() : null;
    }

    private static String string(Object value)
    {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private record SystemAliasListPair(String systemKey, long aliasListId) {}

    private record IdentifierSource(String column, Predicate<Map<String,Object>> include) {}

    private record P25SubscriberSource(String column, Predicate<Map<String,Object>> include) {}

    private record RuleTarget(long aliasListId, int identifier) {}

    private record P25SubscriberRuleTarget(long aliasListId, long p25SubscriberIdentityId) {}

    private record LocalEvidence(long aliasListId, int observedLocalId) {}

    private static final class MigrationConsensus
    {
        private boolean mSeen;
        private boolean mValid = true;
        private Long mWinner;

        private void accept(Long winner)
        {
            mSeen = true;
            if(winner == null || mWinner != null && !mWinner.equals(winner))
            {
                mValid = false;
            }
            else if(mWinner == null)
            {
                mWinner = winner;
            }
        }

        private Long winner()
        {
            return mSeen && mValid ? mWinner : null;
        }
    }

    /** Prepared, index-backed winner lookups shared across one bounded migration evidence page. */
    private static final class MigrationWinnerResolver implements AutoCloseable
    {
        private final Connection mConnection;
        private final Map<MigrationRuleKey,PreparedStatement> mExact = new HashMap<>();
        private final Map<MigrationRuleKey,PreparedStatement> mRange = new HashMap<>();
        private PreparedStatement mP25Subscriber;

        private MigrationWinnerResolver(Connection connection)
        {
            mConnection = connection;
        }

        private Long resolve(int protocol, int kind, long aliasListId, int identifier) throws SQLException
        {
            MigrationRuleKey ruleKey = new MigrationRuleKey(protocol, kind);
            PreparedStatement exact = mExact.get(ruleKey);
            if(exact == null)
            {
                exact = mConnection.prepareStatement(migrationExactSql(ruleKey));
                mExact.put(ruleKey, exact);
            }
            exact.setInt(1, identifier);
            exact.setLong(2, aliasListId);
            try(ResultSet resultSet = exact.executeQuery())
            {
                if(resultSet.next())
                {
                    return resultSet.getLong(1);
                }
            }

            PreparedStatement range = mRange.get(ruleKey);
            if(range == null)
            {
                range = mConnection.prepareStatement(migrationRangeSql(ruleKey));
                mRange.put(ruleKey, range);
            }
            range.setLong(1, aliasListId);
            range.setInt(2, identifier);
            range.setInt(3, identifier);
            try(ResultSet resultSet = range.executeQuery())
            {
                return resultSet.next() ? resultSet.getLong(1) : null;
            }
        }

        private Long resolvePreferred(int protocol, int kind, long aliasListId, int identifier,
                                      Long p25SubscriberIdentityId) throws SQLException
        {
            return resolvePreferred(protocol, kind, aliasListId, identifier, p25SubscriberIdentityId, false);
        }

        private Long resolvePreferred(int protocol, int kind, long aliasListId, int identifier,
                                      Long p25SubscriberIdentityId, boolean explicitLocalEvidence)
            throws SQLException
        {
            Long canonical = protocol == 1 && kind == 2 && p25SubscriberIdentityId != null ?
                resolveP25Subscriber(aliasListId, p25SubscriberIdentityId) : null;
            return canonical != null ? canonical : p25SubscriberIdentityId == null || explicitLocalEvidence ?
                resolve(protocol, kind, aliasListId, identifier) : null;
        }

        private Long resolveP25Subscriber(long aliasListId, long p25SubscriberIdentityId) throws SQLException
        {
            if(mP25Subscriber == null)
            {
                mP25Subscriber = mConnection.prepareStatement("""
                    SELECT alias.id
                    FROM alias_p25_subscriber_identity subscriber_alias
                    JOIN alias ON alias.id=subscriber_alias.alias_id
                    WHERE alias.alias_list_id=? AND alias.matcher_type='P25_SUBSCRIBER_IDENTITY'
                      AND subscriber_alias.p25_subscriber_identity_id=?
                    ORDER BY alias.id DESC LIMIT 1
                    """);
            }
            mP25Subscriber.setLong(1, aliasListId);
            mP25Subscriber.setLong(2, p25SubscriberIdentityId);
            try(ResultSet resultSet = mP25Subscriber.executeQuery())
            {
                return resultSet.next() ? resultSet.getLong(1) : null;
            }
        }

        private Long resolveSystemConsensus(String systemKey, int kind, int identifier) throws SQLException
        {
            return resolveSystemConsensus(systemKey, kind, identifier, null);
        }

        private Long resolveSystemConsensus(String systemKey, int kind, int identifier,
                                            Long p25SubscriberIdentityId) throws SQLException
        {
            if(systemKey == null)
            {
                return null;
            }

            Long winner = null;
            int assignedCount = 0;
            try(PreparedStatement statement = mConnection.prepareStatement("""
                WITH selected AS (
                    SELECT id, configuration_id FROM radio_system WHERE system_key = ?
                ), assigned(alias_list_id) AS (
                    SELECT configuration.alias_list_id
                    FROM selected
                    JOIN receiver_channel channel ON channel.radio_system_id = selected.id
                    JOIN configuration_channel configuration
                      ON configuration.configuration_id = channel.configuration_id
                    WHERE configuration.alias_list_id IS NOT NULL
                    UNION
                    SELECT configuration.alias_list_id
                    FROM selected
                    JOIN configuration_channel configuration
                      ON configuration.configuration_id = selected.configuration_id
                    WHERE configuration.alias_list_id IS NOT NULL
                )
                SELECT alias_list_id FROM assigned ORDER BY alias_list_id
                """))
            {
                statement.setString(1, systemKey);
                try(ResultSet resultSet = statement.executeQuery())
                {
                    while(resultSet.next())
                    {
                        assignedCount++;
                        Long candidate = resolvePreferred(1, kind, resultSet.getLong(1), identifier,
                            p25SubscriberIdentityId);
                        if(candidate == null || winner != null && !winner.equals(candidate))
                        {
                            return null;
                        }
                        winner = candidate;
                    }
                }
            }
            return assignedCount > 0 ? winner : null;
        }

        @Override
        public void close() throws SQLException
        {
            SQLException failure = null;
            for(PreparedStatement statement: mExact.values())
            {
                try
                {
                    statement.close();
                }
                catch(SQLException exception)
                {
                    failure = exception;
                }
            }
            for(PreparedStatement statement: mRange.values())
            {
                try
                {
                    statement.close();
                }
                catch(SQLException exception)
                {
                    failure = exception;
                }
            }
            if(mP25Subscriber != null)
            {
                try
                {
                    mP25Subscriber.close();
                }
                catch(SQLException exception)
                {
                    failure = exception;
                }
            }
            if(failure != null)
            {
                throw failure;
            }
        }

        private static String migrationExactSql(MigrationRuleKey key)
        {
            String matcher = key.kind() == 2 ? "RADIO_ID" : "TALKGROUP";
            String index = key.kind() == 2 ? "idx_alias_radio_value" : "idx_alias_talkgroup_value";
            return "SELECT id FROM alias INDEXED BY " + index +
                " WHERE matcher_type='" + matcher + "' AND protocol IN " + protocolSql(key.protocol()) +
                " AND value=? AND alias_list_id=? ORDER BY id DESC LIMIT 1";
        }

        private static String migrationRangeSql(MigrationRuleKey key)
        {
            String matcher = key.kind() == 2 ? "RADIO_ID_RANGE" : "TALKGROUP_RANGE";
            String index = key.kind() == 2 ? "idx_alias_activity_radio_range" :
                "idx_alias_activity_talkgroup_range";
            return "SELECT id FROM alias INDEXED BY " + index +
                " WHERE alias_list_id=? AND matcher_type='" + matcher + "' AND protocol IN " +
                protocolSql(key.protocol()) + " AND min_value<=? AND max_value>=? " +
                "ORDER BY min_value DESC,max_value DESC,id DESC LIMIT 1";
        }

        private static String protocolSql(int protocol)
        {
            return protocol == 1 ? "('APCO25','APCO25_PHASE2')" :
                protocol == 3 ? "('DMR')" : "('NXDN')";
        }
    }

    private record MigrationRuleKey(int protocol, int kind) {}

    private static final class RuleTargets
    {
        private final Set<RuleTarget> mPairs = new LinkedHashSet<>();
        private final Set<Long> mAliasLists = new HashSet<>();

        private void add(Set<Long> aliasLists, int identifier)
        {
            for(Long aliasListId: aliasLists)
            {
                if(aliasListId == null || aliasListId <= 0)
                {
                    continue;
                }

                mAliasLists.add(aliasListId);
                requireMaximum(mAliasLists.size(), MAX_ALIAS_LISTS,
                    "Alias lookup references too many alias lists");
                mPairs.add(new RuleTarget(aliasListId, identifier));
                requireMaximum(mPairs.size(), MAX_RULE_LOOKUP_PAIRS,
                    "Alias lookup references too many list and identity pairs");
            }
        }

        private boolean isEmpty()
        {
            return mPairs.isEmpty();
        }

        private List<RuleTarget> pairs()
        {
            return List.copyOf(mPairs);
        }
    }

    private static final class P25SubscriberRuleTargets
    {
        private final Set<P25SubscriberRuleTarget> mPairs = new LinkedHashSet<>();
        private final Set<Long> mAliasLists = new HashSet<>();

        private void add(Set<Long> aliasLists, Long p25SubscriberIdentityId)
        {
            if(p25SubscriberIdentityId == null || p25SubscriberIdentityId <= 0)
            {
                return;
            }

            for(Long aliasListId: aliasLists)
            {
                if(aliasListId == null || aliasListId <= 0)
                {
                    continue;
                }
                mAliasLists.add(aliasListId);
                requireMaximum(mAliasLists.size(), MAX_ALIAS_LISTS,
                    "Alias lookup references too many alias lists");
                mPairs.add(new P25SubscriberRuleTarget(aliasListId, p25SubscriberIdentityId));
                requireMaximum(mPairs.size(), MAX_RULE_LOOKUP_PAIRS,
                    "Alias lookup references too many list and canonical subscriber pairs");
            }
        }

        private boolean isEmpty()
        {
            return mPairs.isEmpty();
        }

        private List<P25SubscriberRuleTarget> pairs()
        {
            return List.copyOf(mPairs);
        }
    }

    /** Shared bound across every system-key chunk in one resolver lookup. */
    static final class AliasListPairBudget
    {
        private final int mMaximum;
        private final Set<SystemAliasListPair> mPairs = new HashSet<>();

        AliasListPairBudget(int maximum)
        {
            if(maximum <= 0)
            {
                throw new IllegalArgumentException("maximum must be positive");
            }

            mMaximum = maximum;
        }

        int queryLimit()
        {
            return mMaximum - mPairs.size() + 1;
        }

        void add(String systemKey, long aliasListId)
        {
            if(mPairs.add(new SystemAliasListPair(systemKey, aliasListId)) && mPairs.size() > mMaximum)
            {
                throw tooLarge("Alias lookup references too many system/list pairs");
            }
        }
    }

    private record Snapshot(RuleIndex talkgroups, RuleIndex radios, P25SubscriberRuleIndex p25Subscribers,
                            RuleIndex dmrTalkgroups,
                            RuleIndex dmrRadios, RuleIndex nxdnTalkgroups, RuleIndex nxdnRadios)
    {
    }

    private enum RuleType
    {
        TALKGROUP("TALKGROUP", "TALKGROUP_RANGE", "idx_alias_talkgroup_value",
            "idx_alias_talkgroup_range"),
        RADIO("RADIO_ID", "RADIO_ID_RANGE", "idx_alias_radio_value", "idx_alias_radio_range");

        private final String mValueMatcher;
        private final String mRangeMatcher;
        private final String mValueIndex;
        private final String mRangeIndex;

        RuleType(String valueMatcher, String rangeMatcher, String valueIndex, String rangeIndex)
        {
            mValueMatcher = valueMatcher;
            mRangeMatcher = rangeMatcher;
            mValueIndex = valueIndex;
            mRangeIndex = rangeIndex;
        }

        String valueMatcher()
        {
            return mValueMatcher;
        }

        String rangeMatcher()
        {
            return mRangeMatcher;
        }

        String valueIndex()
        {
            return mValueIndex;
        }

        String rangeIndex()
        {
            return mRangeIndex;
        }
    }

    private record RuleIndex(Map<Long,AliasListRules> rulesByAliasList)
    {
        private static RuleIndex empty()
        {
            return new RuleIndex(Map.of());
        }

        private Rule best(int identifier, long aliasListId)
        {
            AliasListRules rules = rulesByAliasList.get(aliasListId);
            return rules != null ? rules.best(identifier) : null;
        }

        private Rule best(int identifier, Set<Long> aliasLists)
        {
            if(aliasLists.isEmpty())
            {
                return null;
            }

            Rule best = null;

            for(long aliasListId: aliasLists)
            {
                Rule candidate = best(identifier, aliasListId);

                //A native radio system can be received by channels assigned to different Alias Lists. A system-level
                //label is safe only when every applicable list resolves to the same effective Alias. Missing or
                //conflicting definitions deliberately leave the label blank rather than depending on set order.
                if(candidate == null || best != null && !candidate.hasSamePresentationAs(best))
                {
                    return null;
                }

                if(best == null || candidate.aliasId() < best.aliasId())
                {
                    best = candidate;
                }
            }

            return best;
        }

        private Rule bestSameAlias(int identifier, Set<Long> aliasLists)
        {
            if(aliasLists.isEmpty())
            {
                return null;
            }

            Rule best = null;
            for(long aliasListId: aliasLists)
            {
                Rule candidate = best(identifier, aliasListId);
                if(candidate == null || best != null && candidate.aliasId() != best.aliasId())
                {
                    return null;
                }
                best = candidate;
            }
            return best;
        }

        /**
         * Resolves channel-local evidence. Presentation consensus permits equivalent Alias definitions in distinct
         * lists for system pages; metric attribution can require the exact same Alias row and therefore returns no
         * winner when two lists merely happen to look alike.
         */
        private Rule best(List<LocalEvidence> evidence, boolean requireSameAliasId)
        {
            if(evidence.isEmpty())
            {
                return null;
            }

            Rule best = null;
            for(LocalEvidence item: evidence)
            {
                Rule candidate = best(item.observedLocalId(), item.aliasListId());
                if(candidate == null || best != null && (requireSameAliasId ?
                    candidate.aliasId() != best.aliasId() : !candidate.hasSamePresentationAs(best)))
                {
                    return null;
                }

                if(best == null || candidate.aliasId() < best.aliasId())
                {
                    best = candidate;
                }
            }
            return best;
        }
    }

    private record P25SubscriberRuleIndex(Map<P25SubscriberRuleTarget,Rule> rules)
    {
        private static P25SubscriberRuleIndex empty()
        {
            return new P25SubscriberRuleIndex(Map.of());
        }

        private Rule best(long p25SubscriberIdentityId, long aliasListId)
        {
            return rules.get(new P25SubscriberRuleTarget(aliasListId, p25SubscriberIdentityId));
        }

        private Rule best(long p25SubscriberIdentityId, Set<Long> aliasLists)
        {
            if(aliasLists.isEmpty())
            {
                return null;
            }

            Rule best = null;
            for(long aliasListId: aliasLists)
            {
                Rule candidate = best(p25SubscriberIdentityId, aliasListId);
                if(candidate == null || best != null && !candidate.hasSamePresentationAs(best))
                {
                    return null;
                }
                if(best == null || candidate.aliasId() < best.aliasId())
                {
                    best = candidate;
                }
            }
            return best;
        }

        private Rule bestSameAlias(long p25SubscriberIdentityId, Set<Long> aliasLists)
        {
            if(aliasLists.isEmpty())
            {
                return null;
            }

            Rule best = null;
            for(long aliasListId: aliasLists)
            {
                Rule candidate = best(p25SubscriberIdentityId, aliasListId);
                if(candidate == null || best != null && candidate.aliasId() != best.aliasId())
                {
                    return null;
                }
                best = candidate;
            }
            return best;
        }

        private Rule best(long p25SubscriberIdentityId, List<LocalEvidence> evidence,
                          boolean requireSameAliasId)
        {
            if(evidence.isEmpty())
            {
                return null;
            }

            Rule best = null;
            for(LocalEvidence item: evidence)
            {
                Rule candidate = best(p25SubscriberIdentityId, item.aliasListId());
                if(candidate == null || best != null && (requireSameAliasId ?
                    candidate.aliasId() != best.aliasId() : !candidate.hasSamePresentationAs(best)))
                {
                    return null;
                }
                if(best == null || candidate.aliasId() < best.aliasId())
                {
                    best = candidate;
                }
            }
            return best;
        }
    }

    private static final class AliasListRules
    {
        private final Map<Integer,List<Rule>> mExact = new HashMap<>();
        private final List<Rule> mRanged = new ArrayList<>();

        private void add(Rule rule)
        {
            if(rule.ranged())
            {
                mRanged.add(rule);
            }
            else if(rule.value() != null)
            {
                mExact.computeIfAbsent(rule.value(), ignored -> new ArrayList<>()).add(rule);
            }
        }

        private Rule best(int identifier)
        {
            Rule best = null;

            for(Rule rule: mExact.getOrDefault(identifier, List.of()))
            {
                if(best == null || rule.isPreferredAssignedListTo(best))
                {
                    best = rule;
                }
            }

            if(best != null)
            {
                return best;
            }

            for(Rule rule: mRanged)
            {
                if(rule.matchesIdentifier(identifier) &&
                    (best == null || rule.isPreferredAssignedListTo(best)))
                {
                    best = rule;
                }
            }

            return best;
        }
    }

    private record Rule(Integer value, Integer minimum, Integer maximum, boolean ranged, String name,
                        String description, String group, int color, long aliasListId, String aliasListName,
                        long aliasId)
    {
        private boolean hasSamePresentationAs(Rule other)
        {
            return other != null && Objects.equals(name, other.name) &&
                Objects.equals(description, other.description) && Objects.equals(group, other.group) &&
                color == other.color;
        }

        boolean isPreferredAssignedListTo(Rule other)
        {
            int specificity = ranged ? 0 : 1;
            int otherSpecificity = other.ranged ? 0 : 1;
            if(specificity != otherSpecificity)
            {
                return specificity > otherSpecificity;
            }
            return ranged ? isPreferredRangeTo(other) : aliasId > other.aliasId;
        }

        private boolean isPreferredRangeTo(Rule other)
        {
            int minimumComparison = Integer.compare(minimum != null ? minimum : Integer.MIN_VALUE,
                other.minimum != null ? other.minimum : Integer.MIN_VALUE);
            if(minimumComparison != 0)
            {
                return minimumComparison > 0;
            }

            int maximumComparison = Integer.compare(maximum != null ? maximum : Integer.MIN_VALUE,
                other.maximum != null ? other.maximum : Integer.MIN_VALUE);
            return maximumComparison != 0 ? maximumComparison > 0 : aliasId > other.aliasId;
        }

        boolean matchesIdentifier(int identifier)
        {
            return ranged ? minimum != null && maximum != null && identifier >= minimum && identifier <= maximum :
                value != null && identifier == value;
        }

    }
}
