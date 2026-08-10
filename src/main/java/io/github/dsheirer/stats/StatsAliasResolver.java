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
    private static final int MAX_ALIAS_LIST_NAME_CHARACTERS = 256;
    private static final int QUERY_VALUE_CHUNK = 200;
    private static final int RULE_TARGET_CHUNK = 500;
    private static final List<String> P25_PROTOCOLS = List.of("APCO25", "APCO25_PHASE2");
    private static final List<String> DMR_PROTOCOLS = List.of("DMR");
    private static final List<String> NXDN_PROTOCOLS = List.of("NXDN");

    /**
     * Retained as the mutation boundary hook. Lookups are query-scoped, so there is no shared rule corpus to clear.
     */
    synchronized void invalidate()
    {
        // Query-scoped rule loading observes committed alias changes immediately.
    }

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
        Map<Integer,Set<String>> aliasLists = loadAliasLists(connection, systemKeys(rows));
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
        Map<Integer,Set<String>> aliasLists = loadAliasLists(connection, systemKeys(rows));
        List<Rule> rules = loadRules(connection, RuleType.RADIO, P25_PROTOCOLS,
            ruleTargets(rows, row -> systemAliasLists(row, aliasLists),
                source(identifierColumn, ignored -> true)));
        enrich(rows, rules, aliasLists, identifierColumn, prefix);
    }

    void enrichActivity(Connection connection, List<Map<String,Object>> rows) throws SQLException
    {
        if(rows.isEmpty())
        {
            return;
        }

        requireBoundedRows(rows);
        Map<Integer,Set<String>> aliasLists = loadAliasLists(connection, systemKeys(rows));
        Snapshot snapshot = activitySnapshot(connection, rows, aliasLists);

        for(Map<String,Object> row: rows)
        {
            enrichActivityIdentity(row, snapshot, aliasLists, true, "source_radio_id", "source_alias_");
            Integer targetKind = integer(row.get("target_kind_code"));

            if(targetKind != null && (targetKind == 1 || targetKind == 3))
            {
                enrichActivityIdentity(row, snapshot, aliasLists, false, "target_id", "target_alias_");
            }
            else if(targetKind != null && targetKind == 2)
            {
                enrichActivityIdentity(row, snapshot, aliasLists, true, "target_id", "target_alias_");
            }
        }
    }

    /**
     * Activity spans several protocols and ownership models. P25 trunked identities resolve against the alias lists
     * assigned to their system, while conventional P25 and all DMR/NXDN identities resolve only against the alias
     * list assigned to the exact receiver context.
     */
    private void enrichActivityIdentity(Map<String,Object> row, Snapshot snapshot,
                                        Map<Integer,Set<String>> aliasLists, boolean radio,
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

            if(Integer.valueOf(1).equals(integer(row.get("channel_kind_code"))) ||
                integer(row.get("wacn")) != null && integer(row.get("system_id")) != null)
            {
                enrich(row, rules, aliasLists, identifierColumn, prefix);
            }
            else
            {
                enrichByAssignedAliasList(row, rules, identifierColumn, prefix);
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
        Map<Integer,Set<String>> aliasLists = loadAliasLists(connection, systemKeys(rows));
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
        Map<Integer,Set<String>> systemAliasLists = loadAliasLists(connection, systemKeys(rows));
        Snapshot snapshot = evidenceSnapshot(connection, rows, systemAliasLists);
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
                Integer systemKey = integer(row.get("system_key"));
                Set<String> aliasLists = systemKey != null ?
                    systemAliasLists.getOrDefault(systemKey, Set.of()) : Set.of();
                best = rules.best(identifier, aliasLists);
            }
            else if(row.get("alias_list_name") instanceof String aliasList && !aliasList.isBlank())
            {
                best = rules.best(identifier, aliasList);
            }

            if(best != null)
            {
                row.put("resolved_alias_id", best.aliasId());
            }
        }
    }

    /**
     * Resolves each observed talkgroup or patch identity only against the alias list named on that row.  Unlike the
     * normal system enrichment, this projection deliberately does not consider another list assigned to a second
     * receiver for the same P25 system: the Alias Editor needs to show whether the selected list itself has an exact
     * definition, a range definition, or no definition for the observed identity.
     *
     * <p>Expected row fields are {@code protocol_code}, {@code topology}, {@code talkgroup_id}, and
     * {@code alias_list_name}. Trunked P25 rows may also carry a decoded home identity, but matching and alias creation
     * deliberately use only the local talkgroup address.</p>
     */
    void resolveObservedTalkgroups(Connection connection, List<Map<String,Object>> rows) throws SQLException
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
            Integer identifier = integer(row.get("talkgroup_id"));
            Integer protocol = integer(row.get("protocol_code"));
            String aliasList = string(row.get("alias_list_name"));
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

            if(identifier != null && aliasList != null && rules != null)
            {
                if(protocol == 1 && "TRUNKED".equals(row.get("topology")))
                {
                    promotionSupported = identifier > 0 && identifier < 0xFFFF;
                    promotionReason = promotionSupported ? null : "The local P25 talkgroup address is reserved";
                    best = rules.best(identifier, aliasList);
                }
                else if(protocol == 1)
                {
                    //Conventional call buckets predate qualifier-aware P25 identity storage. They remain useful for
                    //review, but creating an ordinary Alias from them could mislabel a fully-qualified destination.
                    best = rules.best(identifier, aliasList);
                    promotionReason = "Conventional P25 observations do not retain identity qualification yet";
                }
                else
                {
                    best = rules.best(identifier, aliasList);
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
     * Resolves conventional DMR aliases only from the exact alias list assigned to each receiver context.
     */
    void enrichDmrTalkgroups(Connection connection, List<Map<String,Object>> rows, String identifierColumn,
                             String prefix) throws SQLException
    {
        enrichByAssignedAliasList(connection, rows, RuleType.TALKGROUP, DMR_PROTOCOLS,
            identifierColumn, prefix);
    }

    /**
     * Resolves conventional DMR aliases only from the exact alias list assigned to each receiver context.
     */
    void enrichDmrRadios(Connection connection, List<Map<String,Object>> rows, String identifierColumn,
                         String prefix) throws SQLException
    {
        enrichByAssignedAliasList(connection, rows, RuleType.RADIO, DMR_PROTOCOLS,
            identifierColumn, prefix);
    }

    /**
     * Resolves NXDN aliases only from the exact alias list assigned to each receiver context.
     */
    void enrichNxdnTalkgroups(Connection connection, List<Map<String,Object>> rows, String identifierColumn,
                              String prefix) throws SQLException
    {
        enrichByAssignedAliasList(connection, rows, RuleType.TALKGROUP, NXDN_PROTOCOLS,
            identifierColumn, prefix);
    }

    /**
     * Resolves NXDN aliases only from the exact alias list assigned to each receiver context.
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

    private void enrich(List<Map<String,Object>> rows, List<Rule> rules, Map<Integer,Set<String>> aliasLists,
                        String identifierColumn, String prefix)
    {
        RuleIndex index = index(rules);

        for(Map<String,Object> row: rows)
        {
            enrich(row, index, aliasLists, identifierColumn, prefix);
        }
    }

    /**
     * Bulk table exports can contain tens of thousands of identities.  Index exact rules once so each row only
     * evaluates rules for its identifier plus the comparatively small set of ranged rules.
     */
    private void enrich(Map<String,Object> row, RuleIndex index, Map<Integer,Set<String>> aliasListsBySystem,
                        String identifierColumn, String prefix)
    {
        Integer identifier = integer(row.get(identifierColumn));
        Integer systemKey = integer(row.get("system_key"));

        if(identifier == null)
        {
            return;
        }

        Set<String> aliasLists = systemKey != null ? aliasListsBySystem.getOrDefault(systemKey, Set.of()) : Set.of();
        Rule best = index.best(identifier, aliasLists);
        apply(row, best, prefix);
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
        Object aliasListValue = row.get("alias_list_name");

        if(identifier == null || !(aliasListValue instanceof String aliasList) || aliasList.isBlank())
        {
            return;
        }

        Rule best = index.best(identifier, aliasList);
        apply(row, best, prefix);
    }

    private static void apply(Map<String,Object> row, Rule rule, String prefix)
    {
        if(rule != null)
        {
            row.put(prefix + "name", rule.name());
            row.put(prefix + "description", rule.description());
            row.put(prefix + "group", rule.group());
            row.put(prefix + "color", rule.color());
            row.put(prefix + "list_name", rule.aliasList());
        }
    }

    private static RuleIndex index(List<Rule> rules)
    {
        Map<String,AliasListRules> rulesByAliasList = new HashMap<>();

        for(Rule rule: rules)
        {
            String aliasList = normalizeAliasList(rule.aliasList());

            if(aliasList != null)
            {
                rulesByAliasList.computeIfAbsent(aliasList, ignored -> new AliasListRules()).add(rule);
            }
        }

        return new RuleIndex(Map.copyOf(rulesByAliasList));
    }

    private Snapshot activitySnapshot(Connection connection, List<Map<String,Object>> rows,
                                      Map<Integer,Set<String>> systemAliasLists) throws SQLException
    {
        Predicate<Map<String,Object>> talkgroupTarget = row -> {
            Integer kind = integer(row.get("target_kind_code"));
            return kind != null && (kind == 1 || kind == 3);
        };
        Predicate<Map<String,Object>> radioTarget = row -> Integer.valueOf(2)
            .equals(integer(row.get("target_kind_code")));

        return new Snapshot(
            index(loadRules(connection, RuleType.TALKGROUP, P25_PROTOCOLS,
                ruleTargets(rows, row -> p25AliasLists(row, systemAliasLists),
                    source("target_id", row -> protocolCode(row) == 1 && talkgroupTarget.test(row))))),
            index(loadRules(connection, RuleType.RADIO, P25_PROTOCOLS,
                ruleTargets(rows, row -> p25AliasLists(row, systemAliasLists),
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
                                      Map<Integer,Set<String>> systemAliasLists) throws SQLException
    {
        Predicate<Map<String,Object>> talkgroup = row -> !Integer.valueOf(2)
            .equals(integer(row.get("identity_kind_code")));
        Predicate<Map<String,Object>> radio = row -> Integer.valueOf(2)
            .equals(integer(row.get("identity_kind_code")));

        return new Snapshot(
            index(loadRules(connection, RuleType.TALKGROUP, P25_PROTOCOLS,
                ruleTargets(rows, row -> p25AliasLists(row, systemAliasLists),
                    source("identity_id", row -> protocolCode(row) == 1 && talkgroup.test(row))))),
            index(loadRules(connection, RuleType.RADIO, P25_PROTOCOLS,
                ruleTargets(rows, row -> p25AliasLists(row, systemAliasLists),
                    source("identity_id", row -> protocolCode(row) == 1 && radio.test(row))))),
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

    private Snapshot observedSnapshot(Connection connection, List<Map<String,Object>> rows) throws SQLException
    {
        return new Snapshot(
            index(loadRules(connection, RuleType.TALKGROUP, P25_PROTOCOLS,
                ruleTargets(rows, StatsAliasResolver::assignedAliasList,
                    source("talkgroup_id", row -> protocolCode(row) == 1)))),
            RuleIndex.empty(),
            index(loadRules(connection, RuleType.TALKGROUP, DMR_PROTOCOLS,
                ruleTargets(rows, StatsAliasResolver::assignedAliasList,
                    source("talkgroup_id", row -> protocolCode(row) == 3)))),
            RuleIndex.empty(),
            index(loadRules(connection, RuleType.TALKGROUP, NXDN_PROTOCOLS,
                ruleTargets(rows, StatsAliasResolver::assignedAliasList,
                    source("talkgroup_id", row -> protocolCode(row) == 4)))),
            RuleIndex.empty());
    }

    private Map<Integer,Set<String>> loadAliasLists(Connection connection, Set<Integer> systemKeys)
        throws SQLException
    {
        if(systemKeys.isEmpty())
        {
            return Map.of();
        }

        Map<Integer,Set<String>> aliasLists = new HashMap<>();
        Set<String> loadedListNames = new HashSet<>();
        AliasListPairBudget pairBudget = new AliasListPairBudget(MAX_SYSTEM_ALIAS_LIST_PAIRS);
        List<Integer> keys = List.copyOf(systemKeys);

        for(int offset = 0; offset < keys.size(); offset += QUERY_VALUE_CHUNK)
        {
            List<Integer> chunk = keys.subList(offset, Math.min(keys.size(), offset + QUERY_VALUE_CHUNK));
            String sql = """
                WITH requested(system_key) AS (VALUES %s)
                SELECT DISTINCT scope.p25_system_key AS system_key, list.name AS alias_list_name
                FROM requested
                JOIN trunked_identity_scope scope ON scope.p25_system_key = requested.system_key
                JOIN trunked_identity_scope_context ownership INDEXED BY idx_trunked_identity_scope_context_scope
                  ON ownership.scope_id = scope.scope_id
                JOIN receiver_context context ON context.id = ownership.context_id
                JOIN p25_site_snapshot site INDEXED BY idx_p25_site_snapshot_identity
                  ON site.guid = context.guid AND site.system_key = scope.p25_system_key
                JOIN alias_list list ON list.name = site.alias_list_name COLLATE NOCASE
                WHERE scope.protocol_code = 1
                  AND trim(site.alias_list_name) <> ''
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
                        int systemKey = resultSet.getInt("system_key");
                        String aliasListName = resultSet.getString("alias_list_name");
                        pairBudget.add(systemKey, aliasListName);
                        loadedListNames.add(aliasListName);
                        requireMaximum(loadedListNames.size(), MAX_ALIAS_LISTS,
                            "Alias lookup references too many alias lists");
                        aliasLists.computeIfAbsent(systemKey, ignored -> new HashSet<>())
                            .add(aliasListName);
                    }
                }
            }
        }

        Map<Integer,Set<String>> immutable = new HashMap<>();
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
            WITH requested(alias_list_name, identifier) AS (VALUES %s)
            SELECT definition.id AS alias_id, definition.value, definition.min_value,
                definition.max_value, definition.name, definition.description, definition.group_name,
                definition.color, list.name AS alias_list_name
            FROM alias definition INDEXED BY %s
            JOIN alias_list list ON list.id = definition.alias_list_id
            WHERE definition.matcher_type = '%s'
              AND definition.protocol IN (%s)
              AND EXISTS (SELECT 1 FROM requested
                          WHERE list.name COLLATE NOCASE = requested.alias_list_name AND %s)
            ORDER BY definition.id
            LIMIT ?
            """.formatted(pairPlaceholders(targets.size()), index, matcher,
            placeholders(protocols.size()), match);

        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            int parameter = 1;

            for(RuleTarget target: targets)
            {
                statement.setString(parameter++, target.aliasList());
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
                        resultSet.getString("alias_list_name"), aliasId));
                }
            }
        }
    }

    private static Set<Integer> systemKeys(List<Map<String,Object>> rows)
    {
        Set<Integer> keys = new LinkedHashSet<>();

        for(Map<String,Object> row: rows)
        {
            Integer key = integer(row.get("system_key"));

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
                                           Function<Map<String,Object>,Set<String>> aliasLists,
                                           IdentifierSource... sources)
    {
        RuleTargets targets = new RuleTargets();

        for(Map<String,Object> row: rows)
        {
            Set<String> rowAliasLists = aliasLists.apply(row);

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

    private static Set<String> systemAliasLists(Map<String,Object> row,
                                                Map<Integer,Set<String>> aliasListsBySystem)
    {
        Integer systemKey = integer(row.get("system_key"));
        return systemKey != null ? aliasListsBySystem.getOrDefault(systemKey, Set.of()) : Set.of();
    }

    private static Set<String> p25AliasLists(Map<String,Object> row,
                                             Map<Integer,Set<String>> aliasListsBySystem)
    {
        Set<String> system = systemAliasLists(row, aliasListsBySystem);
        return !system.isEmpty() ? system : assignedAliasList(row);
    }

    private static Set<String> assignedAliasList(Map<String,Object> row)
    {
        String aliasList = string(row.get("alias_list_name"));
        return aliasList != null ? Set.of(aliasList) : Set.of();
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

    private static String string(Object value)
    {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static String normalizeAliasList(String value)
    {
        if(value == null || value.isBlank())
        {
            return null;
        }

        if(value.length() > MAX_ALIAS_LIST_NAME_CHARACTERS)
        {
            throw tooLarge("Alias List name exceeds the resolver safety limit");
        }

        return value.toLowerCase(Locale.ROOT);
    }

    private record SystemAliasListPair(int systemKey, String aliasListName) {}

    private record IdentifierSource(String column, Predicate<Map<String,Object>> include) {}

    private record RuleTarget(String aliasList, int identifier) {}

    private static final class RuleTargets
    {
        private final Set<RuleTarget> mPairs = new LinkedHashSet<>();
        private final Set<String> mAliasLists = new HashSet<>();

        private void add(Set<String> aliasLists, int identifier)
        {
            for(String aliasList: aliasLists)
            {
                String normalized = normalizeAliasList(aliasList);

                if(normalized == null)
                {
                    continue;
                }

                mAliasLists.add(normalized);
                requireMaximum(mAliasLists.size(), MAX_ALIAS_LISTS,
                    "Alias lookup references too many alias lists");
                mPairs.add(new RuleTarget(normalized, identifier));
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

        void add(int systemKey, String aliasListName)
        {
            if(mPairs.add(new SystemAliasListPair(systemKey, aliasListName)) && mPairs.size() > mMaximum)
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

    private record RuleIndex(Map<String,AliasListRules> rulesByAliasList)
    {
        private static RuleIndex empty()
        {
            return new RuleIndex(Map.of());
        }

        private Rule best(int identifier, String aliasList)
        {
            String normalized = normalizeAliasList(aliasList);
            AliasListRules rules = normalized != null ? rulesByAliasList.get(normalized) : null;
            return rules != null ? rules.best(identifier) : null;
        }

        private Rule best(int identifier, Set<String> aliasLists)
        {
            Rule best = null;

            for(String aliasList: aliasLists)
            {
                Rule candidate = best(identifier, aliasList);

                if(candidate != null && (best == null || candidate.isPreferredAssignedListTo(best)))
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
                        String description, String group, int color, String aliasList, long aliasId)
    {
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
