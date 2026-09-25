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

package io.github.dsheirer.stats.activity;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Public, read-only lookup of the stable integer codes used by retained Activity filters.
 *
 * <p>The API accepts the same canonical names that the resolved Activity view returns, while SQL predicates use
 * these codes directly so SQLite can use the raw-table indexes.</p>
 */
public final class ReceiverActivityFilterCatalog
{
    private static final Map<String,Integer> ACTION_CODES = actionCodes();
    private static final Map<String,Integer> EVENT_TYPE_CODES = eventTypeCodes();

    private ReceiverActivityFilterCatalog()
    {
    }

    public static Integer actionCode(String name)
    {
        return ACTION_CODES.get(normalize(name));
    }

    public static Integer eventTypeCode(String name)
    {
        return EVENT_TYPE_CODES.get(normalize(name));
    }

    public static Map<String,Integer> actions()
    {
        return ACTION_CODES;
    }

    public static Map<String,Integer> eventTypes()
    {
        return EVENT_TYPE_CODES;
    }

    private static Map<String,Integer> actionCodes()
    {
        Map<String,Integer> codes = new LinkedHashMap<>();

        for(ReceiverActivityRecords.Action action: ReceiverActivityCodes.actionCodes())
        {
            codes.put(action.name(), action.code());
        }

        return Map.copyOf(codes);
    }

    private static Map<String,Integer> eventTypeCodes()
    {
        Map<String,Integer> codes = new LinkedHashMap<>();

        for(ReceiverActivityCodes.EventTypeCode eventType: ReceiverActivityCodes.eventTypeCodes())
        {
            codes.put(eventType.eventType().name(), eventType.code());
        }

        return Map.copyOf(codes);
    }

    private static String normalize(String name)
    {
        return name != null && !name.isBlank() ? name.strip().toUpperCase(Locale.ROOT) : null;
    }
}
