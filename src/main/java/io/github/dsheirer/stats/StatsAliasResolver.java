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
        List<Rule> rules = loadRules(connection, RuleType.RADIO, P25_PROTOCOLS,
            ruleTargets(rows, row -> systemAliasLists(row, aliasLists),
                source(identifierColumn, ignored -> true)));
        enrich(rows, rules, aliasLists, identifierColumn, prefix);
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
            enrichActivityIdentity(row, snapshot, true, "source_radio_id", "source_alias_");
            Integer targetKind = integer(row.get("target_kind_code"));

            if(targetKind != null && (targetKind == 1 || targetKind == 3))
            {
                enrichActivityIdentity(row, snapshot, false, "target_id", "target_alias_");
            }
            else if(targetKind != null && targetKind == 2)
            {
                enrichActivityIdentity(row, snapshot, true, "target_id", "target_alias_");
            }
        }
    }

    /**
     * Activity is channel-owned evidence. Every identity resolves its observed local address against the Alias List
     * assigned to that exact saved channel; another channel in a shared P25 system must never lend an alias.
     */
    private void enrichActivityIdentity(Map<String,Object> row, Snapshot snapshot, boolean radio,
                                        String identifierColumn, String prefix)
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

            enrichByAssignedAliasList(row, rules, identifierColumn, prefix);
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
        enrich(rows, loadRules(connection, RuleType.RADIO, P25_PROTOCOLS,
            ruleTargets(rows, row -> systemAliasLists(row, aliasLists),
                source("radio_id", ignored -> true))), aliasLists,
            "radio_id", "radio_alias_");
        enrich(rows, loadRules(connection, RuleType.TALKGROUP, P25_PROTOCOLS,
            ruleTargets(rows, row -> systemAliasLists(row, aliasLists),
                source("talkgroup_id", ignored -> true))), aliasLists,
            "talkgroup_id", "talkgroup_alias_");
    }

    /**
     * Projects each compact evidence row to the one winning configured alias.  This is deliberately the same
     * precedence used by the normal Stats read models, so overlapping exact and range rules cannot make
     * one observation count against multiple aliases.
     *
     * <p>Expected row fields are {@code protocol_code}, {@code topology}, {@code identity_kind_code},
     * {@code identity_id}, and the normal system or assigned-list lookup fields. P25 evidence can also carry a decoded
     * home tuple, but alias resolution deliberately uses only the local address stored in {@code identity_id}; the
     * home tuple remains diagnostic protocol evidence.</p>
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

            if(trunkedP25)
            {
                Long summaryId = positiveLong(row.get("identity_summary_id"));
                List<LocalEvidence> local = summaryId != null ?
                    p25Evidence.getOrDefault(summaryId, List.of()) : List.of();
                if(!local.isEmpty())
                {
                    best = rules.best(local, true);
                }
                else
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
                best = aliasListId != null ? rules.best(identifier, aliasListId) : null;
            }

            if(best != null)
            {
                row.put("resolved_alias_id", best.aliasId());
            }
        }
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
        enrichByAssignedAliasList(connection, rows, RuleType.RADIO, P25_PROTOCOLS,
            identifierColumn, prefix);
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
        RuleTargets dmrTargets = new RuleTargets();
        RuleTargets nxdnTargets = new RuleTargets();

        for(Map<String,Object> row: rows)
        {
            Integer protocol = integer(row.get("protocol_code"));
            Integer identifier = integer(row.get(identifierColumn));
            String systemKey = string(row.get("radio_system_key"));
            if(protocol == null || identifier == null || systemKey == null)
            {
                continue;
            }

            if(protocol == 1)
            {
                Long summaryId = positiveLong(row.get(summaryIdColumn));
                List<LocalEvidence> evidence = summaryId != null ?
                    p25Evidence.getOrDefault(summaryId, List.of()) : List.of();
                if(evidence.isEmpty())
                {
                    p25Targets.add(aliasListsBySystem.getOrDefault(systemKey, Set.of()), identifier);
                }
                else
                {
                    for(LocalEvidence item: evidence)
                    {
                        p25Targets.add(Set.of(item.aliasListId()), item.observedLocalId());
                    }
                }
            }
            else if(protocol == 3)
            {
                dmrTargets.add(aliasListsBySystem.getOrDefault(systemKey, Set.of()), identifier);
            }
            else if(protocol == 4)
            {
                nxdnTargets.add(aliasListsBySystem.getOrDefault(systemKey, Set.of()), identifier);
            }
        }

        RuleIndex p25 = index(loadRules(connection, type, P25_PROTOCOLS, p25Targets));
        RuleIndex dmr = index(loadRules(connection, type, DMR_PROTOCOLS, dmrTargets));
        RuleIndex nxdn = index(loadRules(connection, type, NXDN_PROTOCOLS, nxdnTargets));

        for(Map<String,Object> row: rows)
        {
            Integer protocol = integer(row.get("protocol_code"));
            Integer identifier = integer(row.get(identifierColumn));
            String systemKey = string(row.get("radio_system_key"));
            if(protocol == null || identifier == null || systemKey == null)
            {
                continue;
            }

            Rule best = null;
            if(protocol == 1)
            {
                Long summaryId = positiveLong(row.get(summaryIdColumn));
                List<LocalEvidence> evidence = summaryId != null ?
                    p25Evidence.getOrDefault(summaryId, List.of()) : List.of();
                best = evidence.isEmpty() ? p25.best(identifier,
                    aliasListsBySystem.getOrDefault(systemKey, Set.of())) : p25.best(evidence, false);
            }
            else if(protocol == 3)
            {
                best = dmr.best(identifier, aliasListsBySystem.getOrDefault(systemKey, Set.of()));
            }
            else if(protocol == 4)
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
                    source("source_radio_id", row -> protocolCode(row) == 1),
                    source("target_id", row -> protocolCode(row) == 1 && radioTarget.test(row))))),
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

        return new Snapshot(
            index(loadRules(connection, RuleType.TALKGROUP, P25_PROTOCOLS, p25TalkgroupTargets)),
            index(loadRules(connection, RuleType.RADIO, P25_PROTOCOLS, p25RadioTargets)),
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
                if(identifier == null)
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

    private Snapshot observedSnapshot(Connection connection, List<Map<String,Object>> rows) throws SQLException
    {
        return new Snapshot(
            index(loadRules(connection, RuleType.TALKGROUP, P25_PROTOCOLS,
                ruleTargets(rows, StatsAliasResolver::assignedAliasList,
                    source("group_identity_id", row -> protocolCode(row) == 1)))),
            RuleIndex.empty(),
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
        if(requestedCount < 1 || requestedCount > QUERY_VALUE_CHUNK)
        {
            throw new IllegalArgumentException("P25 local evidence query size is out of bounds");
        }

        return """
                WITH requested(identity_summary_id) AS (VALUES %s), compact_evidence AS (
                    SELECT bucket.identity_summary_id, bucket.channel_id, bucket.observed_local_id
                    FROM requested
                    CROSS JOIN p25_site_call_identity_bucket bucket
                        INDEXED BY idx_p25_site_call_identity_identity
                      ON bucket.identity_summary_id = requested.identity_summary_id
                    WHERE bucket.observed_local_id > 0
                    UNION
                    SELECT member.identity_summary_id, event.channel_id, member.observed_local_id
                    FROM requested
                    JOIN activity_event_identity_member member
                      ON member.identity_summary_id = requested.identity_summary_id
                    JOIN receiver_activity_event event ON event.id = member.event_id
                    WHERE member.observed_local_id > 0
                    UNION
                    SELECT presence.radio_identity_id, presence.channel_id, presence.observed_local_id
                    FROM requested
                    JOIN trunked_radio_channel_presence presence
                      ON presence.radio_identity_id = requested.identity_summary_id
                    WHERE presence.observed_local_id > 0
                    UNION
                    SELECT affiliation.radio_identity_id, affiliation.channel_id,
                        affiliation.radio_observed_local_id
                    FROM requested
                    JOIN trunked_radio_affiliation affiliation
                      ON affiliation.radio_identity_id = requested.identity_summary_id
                    WHERE affiliation.radio_observed_local_id > 0
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
                    SELECT event.source_identity_summary_id, event.channel_id,
                        event.source_observed_local_id
                    FROM detail_fallback
                    CROSS JOIN receiver_activity_event event
                        INDEXED BY idx_receiver_activity_event_source_time
                      ON event.source_identity_summary_id = detail_fallback.identity_summary_id
                    WHERE event.source_observed_local_id > 0
                    UNION
                    SELECT event.target_identity_summary_id, event.channel_id,
                        event.target_observed_local_id
                    FROM detail_fallback
                    CROSS JOIN receiver_activity_event event
                        INDEXED BY idx_receiver_activity_event_target_time
                      ON event.target_identity_summary_id = detail_fallback.identity_summary_id
                    WHERE event.target_observed_local_id > 0
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
                LIMIT ?
                """.formatted(valuesPlaceholders(requestedCount));
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

    private record RuleTarget(long aliasListId, int identifier) {}

    private record LocalEvidence(long aliasListId, int observedLocalId) {}

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

    private record Snapshot(RuleIndex talkgroups, RuleIndex radios, RuleIndex dmrTalkgroups,
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
