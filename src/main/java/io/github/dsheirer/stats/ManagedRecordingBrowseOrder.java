/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stable ordering and pagination shared by recording filter browsers. */
final class ManagedRecordingBrowseOrder
{
    private ManagedRecordingBrowseOrder() {}

    static String sort(String value)
    {
        if(value == null) return "calls";
        if(!Set.of("calls", "name", "recent").contains(value))
            throw new StatsApiException(400, "invalid_sort", "Choose Most calls, Name, or Last active", "sort");
        return value;
    }

    static Comparator<Map<String,Object>> comparator(String sort, String query)
    {
        Comparator<Map<String,Object>> name = Comparator.comparing(
            (Map<String,Object> row) -> text(row.get("label")), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(row -> text(row.get("system_key")))
            .thenComparing(row -> text(row.get("protocol")))
            .thenComparing(row -> text(row.get("id")))
            .thenComparing(row -> text(row.get("identity_key")));
        Comparator<Map<String,Object>> order = switch(sort(sort))
        {
            case "calls" -> Comparator.comparingLong((Map<String,Object> row) -> number(row.get("call_count")))
                .reversed().thenComparing(name);
            case "recent" -> Comparator.comparingLong((Map<String,Object> row) -> number(row.get("last_recorded_ms")))
                .reversed().thenComparing(name);
            default -> name;
        };
        // Exact identifiers remain the first useful suggestion when searching by number or text identity.
        return Comparator.comparingInt((Map<String,Object> row) ->
            query != null && query.equals(text(row.get("id"))) ? 0 : 1).thenComparing(order);
    }

    static Map<String,Object> page(List<Map<String,Object>> rows, int offset, int limit)
    {
        int end = Math.min(rows.size(), offset + limit);
        boolean more = end < rows.size();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("rows", offset < rows.size() ? rows.subList(offset, end) : List.of());
        result.put("has_more", more);
        if(more) result.put("next_offset", end);
        return result;
    }

    private static String text(Object value) { return value != null ? value.toString() : ""; }
    private static long number(Object value) { return value instanceof Number number ? number.longValue() : 0L; }
}
