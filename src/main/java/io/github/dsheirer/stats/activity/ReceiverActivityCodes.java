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

import io.github.dsheirer.module.decode.event.DecodeEventType;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Stable codes used by the receiver-activity schema.
 *
 * These values preserve the codes written by the original schema, but no longer depend on enum declaration order.
 * Adding a decode event type requires assigning a new, unused code here; existing codes must never be changed.
 */
final class ReceiverActivityCodes
{
    private static final List<ReceiverActivityRecords.Action> ACTION_CODES = List.of(
        ReceiverActivityRecords.Action.ACKNOWLEDGE,
        ReceiverActivityRecords.Action.ACTIVE,
        ReceiverActivityRecords.Action.BUSY,
        ReceiverActivityRecords.Action.CALL,
        ReceiverActivityRecords.Action.CHECK,
        ReceiverActivityRecords.Action.CHECK_ACK,
        ReceiverActivityRecords.Action.CONTINUE,
        ReceiverActivityRecords.Action.DATA,
        ReceiverActivityRecords.Action.DENIAL,
        ReceiverActivityRecords.Action.EMERGENCY,
        ReceiverActivityRecords.Action.GPS,
        ReceiverActivityRecords.Action.GRANT,
        ReceiverActivityRecords.Action.JOIN,
        ReceiverActivityRecords.Action.LOGOUT,
        ReceiverActivityRecords.Action.PAGE,
        ReceiverActivityRecords.Action.PATCH,
        ReceiverActivityRecords.Action.PATCH_CANCEL,
        ReceiverActivityRecords.Action.PATCH_CREATE,
        ReceiverActivityRecords.Action.QUEUED,
        ReceiverActivityRecords.Action.REGISTER,
        ReceiverActivityRecords.Action.REQUEST,
        ReceiverActivityRecords.Action.STATUS,
        ReceiverActivityRecords.Action.UNKNOWN
    );
    private static final List<EventTypeCode> EVENT_TYPE_CODES = List.of(
        event(DecodeEventType.AFFILIATE, 1),
        event(DecodeEventType.ANNOUNCEMENT, 2),
        event(DecodeEventType.ACKNOWLEDGE, 3),
        event(DecodeEventType.AUTOMATIC_REGISTRATION_SERVICE, 4),
        event(DecodeEventType.CALL, 5),
        event(DecodeEventType.CALL_ENCRYPTED, 6),
        event(DecodeEventType.CALL_GROUP, 7),
        event(DecodeEventType.CALL_GROUP_ENCRYPTED, 8),
        event(DecodeEventType.CALL_PATCH_GROUP, 9),
        event(DecodeEventType.CALL_PATCH_GROUP_ENCRYPTED, 10),
        event(DecodeEventType.CALL_ALERT, 11),
        event(DecodeEventType.CALL_DETECT, 12),
        event(DecodeEventType.CALL_IN_PROGRESS, 13),
        event(DecodeEventType.CALL_DO_NOT_MONITOR, 14),
        event(DecodeEventType.CALL_END, 15),
        event(DecodeEventType.CALL_INTERCONNECT, 16),
        event(DecodeEventType.CALL_INTERCONNECT_ENCRYPTED, 17),
        event(DecodeEventType.CALL_UNIQUE_ID, 18),
        event(DecodeEventType.CALL_UNIT_TO_UNIT, 19),
        event(DecodeEventType.CALL_UNIT_TO_UNIT_ENCRYPTED, 20),
        event(DecodeEventType.CALL_NO_TUNER, 21),
        event(DecodeEventType.CALL_TIMEOUT, 22),
        event(DecodeEventType.CELLOCATOR, 23),
        event(DecodeEventType.COMMAND, 24),
        event(DecodeEventType.DATA_CALL, 25),
        event(DecodeEventType.DATA_CALL_ENCRYPTED, 26),
        event(DecodeEventType.DATA_PACKET, 27),
        event(DecodeEventType.DEREGISTER, 28),
        event(DecodeEventType.DYNAMIC_REGROUP, 29),
        event(DecodeEventType.EMERGENCY, 30),
        event(DecodeEventType.FUNCTION, 31),
        event(DecodeEventType.GPS, 32),
        event(DecodeEventType.ICMP_PACKET, 33),
        event(DecodeEventType.ID_ANI, 34),
        event(DecodeEventType.ID_UNIQUE, 35),
        event(DecodeEventType.IP_PACKET, 36),
        event(DecodeEventType.LRRP, 37),
        event(DecodeEventType.NOTIFICATION, 38),
        event(DecodeEventType.PAGE, 39),
        event(DecodeEventType.QUERY, 40),
        event(DecodeEventType.RADIO_CHECK, 41),
        event(DecodeEventType.RADIO_REGISTRATION_SERVICE, 42),
        event(DecodeEventType.REGISTER, 43),
        event(DecodeEventType.REGISTER_ESN, 44),
        event(DecodeEventType.REQUEST, 45),
        event(DecodeEventType.RESPONSE, 46),
        event(DecodeEventType.RESPONSE_PACKET, 47),
        event(DecodeEventType.SDM, 48),
        event(DecodeEventType.SMS, 49),
        event(DecodeEventType.STATION_ID, 50),
        event(DecodeEventType.STATUS, 51),
        event(DecodeEventType.TEXT_MESSAGE, 52),
        event(DecodeEventType.UDP_PACKET, 53),
        event(DecodeEventType.UNKNOWN_PACKET, 54),
        event(DecodeEventType.XCMP, 55),
        event(DecodeEventType.UNKNOWN, 56),
        event(DecodeEventType.DENIAL, 57)
    );
    private static final Map<DecodeEventType,Integer> EVENT_TYPE_TO_CODE = buildCodeMap();

    static
    {
        if(ACTION_CODES.size() != ReceiverActivityRecords.Action.values().length ||
            !ACTION_CODES.containsAll(EnumSet.allOf(ReceiverActivityRecords.Action.class)) ||
            ACTION_CODES.stream().map(ReceiverActivityRecords.Action::code).distinct().count() != ACTION_CODES.size())
        {
            throw new IllegalStateException("Every receiver activity action must have one stable unique code");
        }

        if(EVENT_TYPE_TO_CODE.keySet().size() != DecodeEventType.values().length ||
            !EVENT_TYPE_TO_CODE.keySet().containsAll(EnumSet.allOf(DecodeEventType.class)))
        {
            throw new IllegalStateException("Every decode event type must have one stable receiver-activity code");
        }
    }

    private ReceiverActivityCodes()
    {
    }

    static int eventTypeCode(DecodeEventType eventType)
    {
        Integer code = EVENT_TYPE_TO_CODE.get(eventType);

        if(code == null)
        {
            throw new IllegalArgumentException("Decode event type has no receiver-activity code: " + eventType);
        }

        return code;
    }

    static List<ReceiverActivityRecords.Action> actionCodes()
    {
        return ACTION_CODES;
    }

    static List<EventTypeCode> eventTypeCodes()
    {
        return EVENT_TYPE_CODES;
    }

    private static EventTypeCode event(DecodeEventType eventType, int code)
    {
        return new EventTypeCode(eventType, code);
    }

    private static Map<DecodeEventType,Integer> buildCodeMap()
    {
        EnumMap<DecodeEventType,Integer> codes = new EnumMap<>(DecodeEventType.class);

        for(EventTypeCode entry: EVENT_TYPE_CODES)
        {
            if(codes.put(entry.eventType(), entry.code()) != null)
            {
                throw new IllegalStateException("Duplicate receiver-activity event type: " + entry.eventType());
            }
        }

        if(EVENT_TYPE_CODES.stream().map(EventTypeCode::code).distinct().count() != EVENT_TYPE_CODES.size())
        {
            throw new IllegalStateException("Receiver-activity event type codes must be unique");
        }

        return Map.copyOf(codes);
    }

    record EventTypeCode(DecodeEventType eventType, int code)
    {
        EventTypeCode
        {
            if(eventType == null || code <= 0)
            {
                throw new IllegalArgumentException("Event type codes require a type and a positive code");
            }
        }
    }
}
