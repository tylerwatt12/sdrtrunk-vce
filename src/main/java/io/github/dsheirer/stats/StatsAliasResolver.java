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
    private volatile Snapshot mSnapshot = new Snapshot(List.of(), List.of(), List.of(), List.of(), Map.of(), 0);

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
        Snapshot snapshot = snapshot(connection);
        enrich(rows, snapshot.talkgroups(), snapshot.aliasLists(), identifierColumn, prefix);
    }

    void enrichRadios(Connection connection, List<Map<String,Object>> rows, String identifierColumn,
                      String prefix) throws SQLException
    {
        Snapshot snapshot = snapshot(connection);
        enrich(rows, snapshot.radios(), snapshot.aliasLists(), identifierColumn, prefix);
    }

    void enrichActivity(Connection connection, List<Map<String,Object>> rows) throws SQLException
    {
        Snapshot snapshot = snapshot(connection);
        enrich(rows, snapshot.radios(), snapshot.aliasLists(), "source_radio_id", "source_alias_");

        for(Map<String,Object> row: rows)
        {
            Integer targetKind = integer(row.get("target_kind_code"));

            if(targetKind != null && (targetKind == 1 || targetKind == 3))
            {
                enrich(row, snapshot.talkgroups(), snapshot.aliasLists(), "target_id", "target_alias_");
            }
            else if(targetKind != null && targetKind == 2)
            {
                enrich(row, snapshot.radios(), snapshot.aliasLists(), "target_id", "target_alias_");
            }
        }
    }

    void enrichRelationships(Connection connection, List<Map<String,Object>> rows) throws SQLException
    {
        Snapshot snapshot = snapshot(connection);
        enrich(rows, snapshot.radios(), snapshot.aliasLists(), "radio_id", "radio_alias_");
        enrich(rows, snapshot.talkgroups(), snapshot.aliasLists(), "talkgroup_id", "talkgroup_alias_");
    }

    /**
     * Resolves conventional DMR aliases only from the exact alias list assigned to each receiver context.
     */
    void enrichDmrTalkgroups(Connection connection, List<Map<String,Object>> rows, String identifierColumn,
                             String prefix) throws SQLException
    {
        enrichDmr(rows, snapshot(connection).dmrTalkgroups(), identifierColumn, prefix);
    }

    /**
     * Resolves conventional DMR aliases only from the exact alias list assigned to each receiver context.
     */
    void enrichDmrRadios(Connection connection, List<Map<String,Object>> rows, String identifierColumn,
                         String prefix) throws SQLException
    {
        enrichDmr(rows, snapshot(connection).dmrRadios(), identifierColumn, prefix);
    }

    private void enrich(List<Map<String,Object>> rows, List<Rule> rules, Map<Integer,Set<String>> aliasLists,
                        String identifierColumn, String prefix)
    {
        for(Map<String,Object> row: rows)
        {
            enrich(row, rules, aliasLists, identifierColumn, prefix);
        }
    }

    private void enrich(Map<String,Object> row, List<Rule> rules, Map<Integer,Set<String>> aliasListsBySystem,
                        String identifierColumn, String prefix)
    {
        Integer identifier = integer(row.get(identifierColumn));
        Integer wacn = integer(row.get("wacn"));
        Integer system = integer(row.get("system_id"));
        Integer systemKey = integer(row.get("system_key"));

        if(identifier == null || wacn == null || system == null)
        {
            return;
        }

        Set<String> aliasLists = systemKey != null ? aliasListsBySystem.getOrDefault(systemKey, Set.of()) : Set.of();
        Rule best = null;

        for(Rule rule: rules)
        {
            if(!rule.matches(identifier, wacn, system) || !rule.isEligible(aliasLists))
            {
                continue;
            }

            if(best == null || rule.isPreferredTo(best))
            {
                best = rule;
            }
        }

        if(best != null)
        {
            row.put(prefix + "name", best.name());
            row.put(prefix + "group", best.group());
            row.put(prefix + "color", best.color());
            row.put(prefix + "list_name", best.aliasList());
        }
    }

    private void enrichDmr(List<Map<String,Object>> rows, List<Rule> rules, String identifierColumn, String prefix)
    {
        for(Map<String,Object> row: rows)
        {
            Integer identifier = integer(row.get(identifierColumn));
            Object aliasListValue = row.get("alias_list_name");

            if(identifier == null || !(aliasListValue instanceof String aliasList) || aliasList.isBlank())
            {
                continue;
            }

            Rule best = null;

            for(Rule rule: rules)
            {
                if(!aliasList.equals(rule.aliasList()) || !rule.matchesIdentifier(identifier))
                {
                    continue;
                }

                if(best == null || rule.isPreferredDmrTo(best))
                {
                    best = rule;
                }
            }

            if(best != null)
            {
                row.put(prefix + "name", best.name());
                row.put(prefix + "group", best.group());
                row.put(prefix + "color", best.color());
                row.put(prefix + "list_name", best.aliasList());
            }
        }
    }

    private Map<Integer,Set<String>> loadAliasLists(Connection connection) throws SQLException
    {
        Map<Integer,Set<String>> aliasLists = new HashMap<>();

        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery("""
            SELECT DISTINCT system_key, alias_list_name
            FROM p25_site_snapshot
            WHERE system_key IS NOT NULL AND alias_list_name IS NOT NULL AND trim(alias_list_name) <> ''
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
                snapshot = new Snapshot(load(connection, "alias_talkgroup", false),
                    load(connection, "alias_radio", false), load(connection, "alias_talkgroup", true),
                    load(connection, "alias_radio", true), loadAliasLists(connection), now);
                mSnapshot = snapshot;
            }
        }

        return snapshot;
    }

    private List<Rule> load(Connection connection, String identifierTable, boolean dmr) throws SQLException
    {
        List<Rule> rules = new ArrayList<>();

        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery("""
            SELECT identifier.value, identifier.min_value, identifier.max_value, identifier.wacn,
                identifier.system_id, identifier.fully_qualified, identifier.ranged, alias.name,
                alias.group_name, alias.color, identifier.alias_list_name, alias.id AS alias_id
            FROM %s identifier
            JOIN alias ON alias.id = identifier.alias_id
            WHERE %s
            ORDER BY alias.id
            """.formatted(identifierTable, dmr ? "identifier.protocol = 'DMR'" :
                "identifier.protocol IN ('APCO25', 'APCO25_PHASE2')")))
        {
            while(resultSet.next())
            {
                rules.add(new Rule(integer(resultSet.getObject("value")),
                    integer(resultSet.getObject("min_value")), integer(resultSet.getObject("max_value")),
                    integer(resultSet.getObject("wacn")), integer(resultSet.getObject("system_id")),
                    resultSet.getInt("fully_qualified") != 0, resultSet.getInt("ranged") != 0,
                    resultSet.getString("name"), resultSet.getString("group_name"), resultSet.getInt("color"),
                    resultSet.getString("alias_list_name"), resultSet.getLong("alias_id")));
            }
        }

        return List.copyOf(rules);
    }

    private static Integer integer(Object value)
    {
        return value instanceof Number number ? number.intValue() : null;
    }

    private record Snapshot(List<Rule> talkgroups, List<Rule> radios, List<Rule> dmrTalkgroups,
                            List<Rule> dmrRadios, Map<Integer,Set<String>> aliasLists, long loadedAt)
    {
    }

    private record Rule(Integer value, Integer minimum, Integer maximum, Integer wacn, Integer systemId,
                        boolean fullyQualified, boolean ranged, String name, String group, int color,
                        String aliasList, long aliasId)
    {
        boolean isEligible(Set<String> systemAliasLists)
        {
            return systemAliasLists.contains(aliasList) || (systemAliasLists.isEmpty() && fullyQualified);
        }

        boolean isPreferredTo(Rule other)
        {
            int specificity = specificity();
            int otherSpecificity = other.specificity();
            return specificity > otherSpecificity || (specificity == otherSpecificity && aliasId < other.aliasId);
        }

        boolean isPreferredDmrTo(Rule other)
        {
            int specificity = ranged ? 0 : 1;
            int otherSpecificity = other.ranged ? 0 : 1;
            return specificity > otherSpecificity || (specificity == otherSpecificity && aliasId < other.aliasId);
        }

        boolean matchesIdentifier(int identifier)
        {
            return ranged ? minimum != null && maximum != null && identifier >= minimum && identifier <= maximum :
                value != null && identifier == value;
        }

        private int specificity()
        {
            if(fullyQualified && !ranged)
            {
                return 3;
            }
            else if(!ranged)
            {
                return 2;
            }
            else if(fullyQualified)
            {
                return 1;
            }

            return 0;
        }

        boolean matches(int identifier, int observedWacn, int observedSystem)
        {
            if(fullyQualified && (!Integer.valueOf(observedWacn).equals(wacn) ||
                !Integer.valueOf(observedSystem).equals(systemId)))
            {
                return false;
            }

            return ranged ? minimum != null && maximum != null && identifier >= minimum && identifier <= maximum :
                value != null && identifier == value;
        }
    }
}
