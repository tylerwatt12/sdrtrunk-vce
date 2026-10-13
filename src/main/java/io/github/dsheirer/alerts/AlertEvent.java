/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.alerts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Detached bounded receiver observation, evaluated only by the alert worker. */
public record AlertEvent(String id, long observedAtMs, String trigger, Map<String,List<String>> values, AlertAction.Context actionContext)
{
    public AlertEvent
    {
        id = AlertRule.text(id, "Event ID", 240, false);
        if(observedAtMs <= 0 || !AlertRule.TRIGGERS.contains(trigger)) throw new IllegalArgumentException("Invalid alert event");
        if(values == null || values.size() > 32) throw new IllegalArgumentException("Event facts exceed their limit");
        Map<String,List<String>> bounded = new LinkedHashMap<>();
        values.forEach((key, items) -> {
            if(items == null || items.size() > 64 || items.stream().anyMatch(v -> v == null || v.length() > 2048))
                throw new IllegalArgumentException("Event facts exceed their limit");
            bounded.put(key, List.copyOf(items));
        });
        values = Map.copyOf(bounded);
        actionContext = actionContext != null ? actionContext : AlertAction.Context.EMPTY;
    }
    public AlertEvent(String id, long observedAtMs, String trigger, Map<String,List<String>> values)
    {
        this(id, observedAtMs, trigger, values, AlertAction.Context.EMPTY);
    }
    public List<String> values(String field) { return values.getOrDefault(field, List.of()); }
    public String first(String field) { return values(field).isEmpty() ? "" : values(field).getFirst(); }
}
