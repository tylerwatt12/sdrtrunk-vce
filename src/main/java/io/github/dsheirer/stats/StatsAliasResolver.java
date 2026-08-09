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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Central alias lookup for Stats Server read models. Rules are cached briefly so page polling does not repeatedly scan
 * large alias lists; SQLite remains the source of truth.
 */
class StatsAliasResolver
{
    private static final long CACHE_MILLISECONDS = 30_000L;
    private static final String P25_PROTOCOL_FILTER =
        "identifier.protocol IN ('APCO25', 'APCO25_PHASE2')";
    private static final String DMR_PROTOCOL_FILTER = "identifier.protocol = 'DMR'";
    private static final String NXDN_PROTOCOL_FILTER = "identifier.protocol = 'NXDN'";
    private volatile Snapshot mSnapshot = new Snapshot(List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), 0);

    /**
     * Discards cached alias rules after an administrator changes alias configuration.  The next read reloads one
     * coherent snapshot from SQLite instead of waiting for the normal polling cache to expire.
     */
    synchronized void invalidate()
    {
        mSnapshot = new Snapshot(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0);
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

        Snapshot snapshot = snapshot(connection);
        enrich(rows, snapshot.talkgroups(), loadAliasLists(connection), identifierColumn, prefix);
    }

    void enrichRadios(Connection connection, List<Map<String,Object>> rows, String identifierColumn,
                      String prefix) throws SQLException
    {
        if(rows.isEmpty())
        {
            return;
        }

        Snapshot snapshot = snapshot(connection);
        enrich(rows, snapshot.radios(), loadAliasLists(connection), identifierColumn, prefix);
    }

    void enrichActivity(Connection connection, List<Map<String,Object>> rows) throws SQLException
    {
        if(rows.isEmpty())
        {
            return;
        }

        Snapshot snapshot = snapshot(connection);
        Map<Integer,Set<String>> aliasLists = loadAliasLists(connection);

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
        List<Rule> rules;

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

        Snapshot snapshot = snapshot(connection);
        Map<Integer,Set<String>> aliasLists = loadAliasLists(connection);
        enrich(rows, snapshot.radios(), aliasLists, "radio_id", "radio_alias_");
        enrich(rows, snapshot.talkgroups(), aliasLists, "talkgroup_id", "talkgroup_alias_");
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

        Snapshot snapshot = snapshot(connection);
        Map<Integer,Set<String>> systemAliasLists = loadAliasLists(connection);
        RuleIndex p25Talkgroups = index(snapshot.talkgroups());
        RuleIndex p25Radios = index(snapshot.radios());
        RuleIndex dmrTalkgroups = index(snapshot.dmrTalkgroups());
        RuleIndex dmrRadios = index(snapshot.dmrRadios());
        RuleIndex nxdnTalkgroups = index(snapshot.nxdnTalkgroups());
        RuleIndex nxdnRadios = index(snapshot.nxdnRadios());

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
                best = bestSystemRule(rules.exact().getOrDefault(identifier, List.of()), null, identifier,
                    aliasLists);
                best = bestSystemRule(rules.ranged(), best, identifier, aliasLists);
            }
            else if(row.get("alias_list_name") instanceof String aliasList && !aliasList.isBlank())
            {
                best = bestAssignedRule(rules.exact().getOrDefault(identifier, List.of()), null, identifier,
                    aliasList);
                best = bestAssignedRule(rules.ranged(), best, identifier, aliasList);
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

        Snapshot snapshot = snapshot(connection);
        RuleIndex p25 = index(snapshot.talkgroups());
        RuleIndex dmr = index(snapshot.dmrTalkgroups());
        RuleIndex nxdn = index(snapshot.nxdnTalkgroups());

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
                    best = bestAssignedRule(rules.exact().getOrDefault(identifier, List.of()), null,
                        identifier, aliasList);
                    best = bestAssignedRule(rules.ranged(), best, identifier, aliasList);
                }
                else if(protocol == 1)
                {
                    //Conventional call buckets predate qualifier-aware P25 identity storage. They remain useful for
                    //review, but creating an ordinary Alias from them could mislabel a fully-qualified destination.
                    best = bestAssignedRule(rules.exact().getOrDefault(identifier, List.of()), null, identifier,
                        aliasList);
                    best = bestAssignedRule(rules.ranged(), best, identifier, aliasList);
                    promotionReason = "Conventional P25 observations do not retain identity qualification yet";
                }
                else
                {
                    best = bestAssignedRule(rules.exact().getOrDefault(identifier, List.of()), null, identifier,
                        aliasList);
                    best = bestAssignedRule(rules.ranged(), best, identifier, aliasList);
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
        enrichByAssignedAliasList(rows, snapshot(connection).dmrTalkgroups(), identifierColumn, prefix);
    }

    /**
     * Resolves conventional DMR aliases only from the exact alias list assigned to each receiver context.
     */
    void enrichDmrRadios(Connection connection, List<Map<String,Object>> rows, String identifierColumn,
                         String prefix) throws SQLException
    {
        enrichByAssignedAliasList(rows, snapshot(connection).dmrRadios(), identifierColumn, prefix);
    }

    /**
     * Resolves NXDN aliases only from the exact alias list assigned to each receiver context.
     */
    void enrichNxdnTalkgroups(Connection connection, List<Map<String,Object>> rows, String identifierColumn,
                              String prefix) throws SQLException
    {
        enrichByAssignedAliasList(rows, snapshot(connection).nxdnTalkgroups(), identifierColumn, prefix);
    }

    /**
     * Resolves NXDN aliases only from the exact alias list assigned to each receiver context.
     */
    void enrichNxdnRadios(Connection connection, List<Map<String,Object>> rows, String identifierColumn,
                          String prefix) throws SQLException
    {
        enrichByAssignedAliasList(rows, snapshot(connection).nxdnRadios(), identifierColumn, prefix);
    }

    /**
     * Resolves a conventional P25 identity from its receiver's exact alias list.
     */
    void enrichP25ConventionalTalkgroups(Connection connection, List<Map<String,Object>> rows,
                                         String identifierColumn, String prefix) throws SQLException
    {
        enrichByAssignedAliasList(rows, snapshot(connection).talkgroups(), identifierColumn, prefix);
    }

    /**
     * Resolves a conventional P25 identity from its receiver's exact alias list.
     */
    void enrichP25ConventionalRadios(Connection connection, List<Map<String,Object>> rows,
                                     String identifierColumn, String prefix) throws SQLException
    {
        enrichByAssignedAliasList(rows, snapshot(connection).radios(), identifierColumn, prefix);
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
        Rule best = bestSystemRule(index.exact().getOrDefault(identifier, List.of()), null, identifier, aliasLists);
        best = bestSystemRule(index.ranged(), best, identifier, aliasLists);
        apply(row, best, prefix);
    }

    private void enrich(Map<String,Object> row, List<Rule> rules, Map<Integer,Set<String>> aliasListsBySystem,
                        String identifierColumn, String prefix)
    {
        Integer identifier = integer(row.get(identifierColumn));
        Integer systemKey = integer(row.get("system_key"));

        if(identifier == null)
        {
            return;
        }

        Set<String> aliasLists = systemKey != null ? aliasListsBySystem.getOrDefault(systemKey, Set.of()) : Set.of();
        Rule best = null;

        for(Rule rule: rules)
        {
            if(!rule.matchesIdentifier(identifier) || !rule.isEligible(aliasLists))
            {
                continue;
            }

            if(best == null || rule.isPreferredAssignedListTo(best))
            {
                best = rule;
            }
        }

        if(best != null)
        {
            row.put(prefix + "name", best.name());
            row.put(prefix + "description", best.description());
            row.put(prefix + "group", best.group());
            row.put(prefix + "color", best.color());
            row.put(prefix + "list_name", best.aliasList());
        }
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

        Rule best = bestAssignedRule(index.exact().getOrDefault(identifier, List.of()), null, identifier,
            aliasList);
        best = bestAssignedRule(index.ranged(), best, identifier, aliasList);
        apply(row, best, prefix);
    }

    private void enrichByAssignedAliasList(Map<String,Object> row, List<Rule> rules, String identifierColumn,
                                           String prefix)
    {
        Integer identifier = integer(row.get(identifierColumn));
        Object aliasListValue = row.get("alias_list_name");

        if(identifier == null || !(aliasListValue instanceof String aliasList) || aliasList.isBlank())
        {
            return;
        }

        Rule best = null;

        for(Rule rule: rules)
        {
            if(!aliasList.equals(rule.aliasList()) || !rule.matchesIdentifier(identifier))
            {
                continue;
            }

            if(best == null || rule.isPreferredAssignedListTo(best))
            {
                best = rule;
            }
        }

        if(best != null)
        {
            apply(row, best, prefix);
        }
    }

    private static Rule bestSystemRule(List<Rule> rules, Rule best, int identifier, Set<String> aliasLists)
    {
        for(Rule rule: rules)
        {
            if(rule.matchesIdentifier(identifier) && rule.isEligible(aliasLists) &&
                (best == null || rule.isPreferredAssignedListTo(best)))
            {
                best = rule;
            }
        }

        return best;
    }

    private static Rule bestAssignedRule(List<Rule> rules, Rule best, int identifier, String aliasList)
    {
        for(Rule rule: rules)
        {
            if(aliasList.equals(rule.aliasList()) && rule.matchesIdentifier(identifier) &&
                (best == null || rule.isPreferredAssignedListTo(best)))
            {
                best = rule;
            }
        }

        return best;
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
        Map<Integer,List<Rule>> exact = new HashMap<>();
        List<Rule> ranged = new ArrayList<>();

        for(Rule rule: rules)
        {
            if(rule.ranged())
            {
                ranged.add(rule);
            }
            else if(rule.value() != null)
            {
                exact.computeIfAbsent(rule.value(), ignored -> new ArrayList<>()).add(rule);
            }
        }

        return new RuleIndex(exact, ranged);
    }

    private Map<Integer,Set<String>> loadAliasLists(Connection connection) throws SQLException
    {
        Map<Integer,Set<String>> aliasLists = new HashMap<>();

        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery("""
            SELECT DISTINCT scope.p25_system_key AS system_key, site.alias_list_name
            FROM trunked_identity_scope scope
            JOIN trunked_identity_scope_context ownership ON ownership.scope_id = scope.scope_id
            JOIN receiver_context context ON context.id = ownership.context_id
            JOIN p25_site_snapshot site
              ON site.guid = context.guid AND site.system_key = scope.p25_system_key
            WHERE scope.protocol_code = 1
              AND scope.p25_system_key IS NOT NULL
              AND site.alias_list_name IS NOT NULL
              AND trim(site.alias_list_name) <> ''
            """))
        {
            while(resultSet.next())
            {
                aliasLists.computeIfAbsent(resultSet.getInt("system_key"), key -> new HashSet<>())
                    .add(resultSet.getString("alias_list_name"));
            }
        }

        return Map.copyOf(aliasLists);
    }

    private Snapshot snapshot(Connection connection) throws SQLException
    {
        Snapshot snapshot = mSnapshot;
        long now = System.currentTimeMillis();

        if(now - snapshot.loadedAt() <= CACHE_MILLISECONDS)
        {
            return snapshot;
        }

        synchronized(this)
        {
            snapshot = mSnapshot;

            if(now - snapshot.loadedAt() > CACHE_MILLISECONDS)
            {
                snapshot = new Snapshot(load(connection, "alias_talkgroup", P25_PROTOCOL_FILTER),
                    load(connection, "alias_radio", P25_PROTOCOL_FILTER),
                    load(connection, "alias_talkgroup", DMR_PROTOCOL_FILTER),
                    load(connection, "alias_radio", DMR_PROTOCOL_FILTER),
                    load(connection, "alias_talkgroup", NXDN_PROTOCOL_FILTER),
                    load(connection, "alias_radio", NXDN_PROTOCOL_FILTER), now);
                mSnapshot = snapshot;
            }
        }

        return snapshot;
    }

    private List<Rule> load(Connection connection, String identifierTable, String protocolFilter) throws SQLException
    {
        List<Rule> rules = new ArrayList<>();

        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery("""
            SELECT identifier.value, identifier.min_value, identifier.max_value, identifier.ranged,
                alias.name, alias.description, alias.group_name, alias.color,
                identifier.alias_list_name, alias.id AS alias_id
            FROM %s identifier
            JOIN alias ON alias.id = identifier.alias_id
            WHERE %s
            ORDER BY alias.id
            """.formatted(identifierTable, protocolFilter)))
        {
            while(resultSet.next())
            {
                rules.add(new Rule(integer(resultSet.getObject("value")),
                    integer(resultSet.getObject("min_value")), integer(resultSet.getObject("max_value")),
                    resultSet.getInt("ranged") != 0, resultSet.getString("name"),
                    resultSet.getString("description"), resultSet.getString("group_name"),
                    resultSet.getInt("color"), resultSet.getString("alias_list_name"),
                    resultSet.getLong("alias_id")));
            }
        }

        return List.copyOf(rules);
    }

    private static Integer integer(Object value)
    {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static String string(Object value)
    {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private record Snapshot(List<Rule> talkgroups, List<Rule> radios, List<Rule> dmrTalkgroups,
                            List<Rule> dmrRadios, List<Rule> nxdnTalkgroups, List<Rule> nxdnRadios, long loadedAt)
    {
    }

    private record RuleIndex(Map<Integer,List<Rule>> exact, List<Rule> ranged)
    {
    }

    private record Rule(Integer value, Integer minimum, Integer maximum, boolean ranged, String name,
                        String description, String group, int color, String aliasList, long aliasId)
    {
        boolean isEligible(Set<String> systemAliasLists)
        {
            return systemAliasLists.contains(aliasList);
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
