/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pages complete, already filtered discovery results within their actual system and configured Alias group. */
final class ManagedRecordingPickerGroups
{
    static final int PAGE_SIZE = 10;

    private ManagedRecordingPickerGroups() {}

    static String validateKey(String value)
    {
        if(value != null && !value.matches("(?:talkgroup|radio|system|site|channel|dcs):" +
            "[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}"))
            throw new StatsApiException(400, "invalid_parameter", "Choose a valid filter group", "group_key");
        return value;
    }

    static Map<String,Object> page(List<Map<String,Object>> rows, String kind, String selectedGroup,
                                   int offset, int limit, long deadline) throws SQLException
    {
        validateKey(selectedGroup);
        int size = Math.max(1, Math.min(PAGE_SIZE, limit));
        Map<String,Group> groups = new LinkedHashMap<>();
        for(Map<String,Object> row: rows)
        {
            if(System.nanoTime() >= deadline)
                throw new SQLException("Recording browser exceeded its time limit","",9);
            String system = "system".equals(kind) ? null : text(row.get("system_key"));
            String aliasGroup = "talkgroup".equals(kind) || "radio".equals(kind) ? text(row.get("group")) : null;
            String aliasList = aliasGroup != null ? text(row.get("alias_list_id")) : null;
            String label = aliasGroup != null ? aliasGroup : switch(kind)
            {
                case "talkgroup" -> "Ungrouped";
                case "radio" -> "Radios";
                case "channel" -> "Channels";
                case "site" -> "Sites";
                case "dcs" -> "DCS";
                default -> "Systems";
            };
            // Length-delimited fields preserve arbitrary saved group names without using them as request syntax.
            String identity = field(kind) + field(system) + field(aliasList) + field(aliasGroup);
            String key = kind + ':' + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
            if(selectedGroup != null && !selectedGroup.equals(key)) continue;
            groups.computeIfAbsent(key, ignored -> new Group(key, kind, label, system,
                text(row.get("system_name")), aliasGroup != null ? row.get("alias_list_id") : null,
                text(row.get("alias_list_name")))).rows.add(row);
        }
        List<Map<String,Object>> result = new ArrayList<>(groups.size());
        for(Group group: groups.values())
        {
            Map<String,Object> value = new LinkedHashMap<>();
            value.put("group_key", group.key);
            value.put("kind", group.kind);
            value.put("label", group.label);
            value.put("system_key", group.system);
            if(group.systemName != null) value.put("system_name", group.systemName);
            if(group.aliasList != null) value.put("alias_list_id", group.aliasList);
            if(group.aliasListName != null) value.put("alias_list_name", group.aliasListName);
            value.put("match_count", group.rows.size());
            value.putAll(ManagedRecordingBrowseOrder.page(group.rows, offset, size));
            result.add(value);
        }
        return Map.of("groups", result);
    }

    private static String field(String value) { return value == null ? "-1:" : value.length() + ":" + value; }
    private static String text(Object value) { return value == null || value.toString().isBlank() ? null : value.toString(); }

    private static final class Group
    {
        final String key, kind, label, system, systemName, aliasListName;
        final Object aliasList;
        final List<Map<String,Object>> rows = new ArrayList<>();
        Group(String key, String kind, String label, String system, String systemName, Object aliasList,
              String aliasListName)
        { this.key=key; this.kind=kind; this.label=label; this.system=system; this.systemName=systemName;
            this.aliasList=aliasList; this.aliasListName=aliasListName; }
    }
}
