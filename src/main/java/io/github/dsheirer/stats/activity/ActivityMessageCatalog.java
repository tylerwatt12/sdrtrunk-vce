/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats.activity;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared SQLite definitions for Activity names, explanations and implemented protocol support. */
public final class ActivityMessageCatalog
{
    private ActivityMessageCatalog() {}

    private static final String SQL = """
        CREATE TABLE activity_message_type (
            definition_kind TEXT NOT NULL CHECK(definition_kind IN ('ACTION','EVENT')),
            code INTEGER NOT NULL CHECK(code BETWEEN 1 AND 255),
            name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 64 AND name NOT GLOB '*[^A-Z0-9_]*'),
            label TEXT NOT NULL CHECK(length(trim(label)) BETWEEN 1 AND 80),
            meaning TEXT NOT NULL CHECK(length(trim(meaning)) BETWEEN 1 AND 512),
            p25 INTEGER NOT NULL CHECK(p25 IN (0,1)),
            dmr INTEGER NOT NULL CHECK(dmr IN (0,1)),
            nxdn INTEGER NOT NULL CHECK(nxdn IN (0,1)),
            sort_order INTEGER NOT NULL CHECK(sort_order BETWEEN 0 AND 10000),
            PRIMARY KEY(definition_kind,code),
            UNIQUE(definition_kind,name)
        ) WITHOUT ROWID, STRICT
        """;

    private static final Set<String> DMR_ACTIONS = Set.of("ACKNOWLEDGE", "BUSY", "CALL", "CHECK", "DATA",
        "DENIAL", "GPS", "JOIN", "QUEUED", "REGISTER", "REQUEST", "UNKNOWN", "STUN", "REVIVE", "KILL",
        "AUTHENTICATE", "CANCEL_CALL", "PROTECT", "VOTE_NOW", "TONE");
    private static final Set<String> NXDN_ACTIONS = Set.of("CALL", "DATA", "GPS", "PAGE", "REGISTER", "JOIN",
        "LOGOUT", "DENIAL", "CHECK", "STATUS", "REQUEST", "UNKNOWN", "STUN", "REVIVE", "KILL", "REMOTE_MONITOR",
        "AUTHENTICATE", "TONE");
    private static final Set<String> DMR_EVENTS = Set.of("AFFILIATE", "CALL", "CALL_ENCRYPTED", "CALL_GROUP",
        "CALL_GROUP_ENCRYPTED", "CALL_UNIT_TO_UNIT", "CALL_UNIT_TO_UNIT_ENCRYPTED", "COMMAND", "DATA_CALL",
        "DATA_CALL_ENCRYPTED", "DATA_PACKET", "DENIAL", "GPS", "LRRP", "RADIO_CHECK", "RADIO_REGISTRATION_SERVICE",
        "REGISTER", "REQUEST", "RESPONSE", "SDM", "SMS", "TEXT_MESSAGE", "XCMP", "STUN", "REVIVE", "KILL",
        "AUTHENTICATE", "CANCEL_CALL", "PROTECT", "VOTE_NOW", "BUSY", "QUEUED", "ACKNOWLEDGE", "TONE");
    private static final Set<String> NXDN_EVENTS = Set.of("AFFILIATE", "AUTHENTICATE", "CALL", "CALL_ENCRYPTED",
        "CALL_GROUP", "CALL_GROUP_ENCRYPTED", "CALL_INTERCONNECT", "CALL_INTERCONNECT_ENCRYPTED", "CALL_UNIT_TO_UNIT",
        "CALL_UNIT_TO_UNIT_ENCRYPTED", "DATA_CALL", "DATA_CALL_ENCRYPTED", "DENIAL", "DEREGISTER", "GPS", "PAGE",
        "QUERY", "REGISTER", "REQUEST", "RESPONSE", "STATUS", "STUN", "REVIVE", "KILL", "REMOTE_MONITOR",
        "STUN_RESPONSE", "REVIVE_RESPONSE", "KILL_RESPONSE", "REMOTE_MONITOR_RESPONSE", "TONE");
    private static final Set<String> P25_ACTIONS = Set.of("ACKNOWLEDGE", "ACTIVE", "BUSY", "CALL", "CHECK", "CHECK_ACK",
        "CONTINUE", "DATA", "DENIAL", "EMERGENCY", "GPS", "GRANT", "JOIN", "LOGOUT", "PAGE", "PATCH_CANCEL",
        "PATCH_CREATE", "QUEUED", "REGISTER", "REQUEST", "STATUS", "UNKNOWN", "TONE", "RADIO_INHIBIT",
        "RADIO_UNINHIBIT", "REMOTE_MONITOR", "AUTHENTICATE", "CANCEL_CALL", "RADIO_DETACH");
    private static final Set<String> P25_EVENTS = Set.of("ACKNOWLEDGE", "AUTHENTICATE", "AUTOMATIC_REGISTRATION_SERVICE",
        "CALL", "CALL_ENCRYPTED", "CALL_GROUP", "CALL_GROUP_ENCRYPTED", "CALL_PATCH_GROUP", "CALL_PATCH_GROUP_ENCRYPTED",
        "CALL_INTERCONNECT", "CALL_INTERCONNECT_ENCRYPTED", "CALL_UNIT_TO_UNIT", "CALL_UNIT_TO_UNIT_ENCRYPTED",
        "CALL_ALERT", "CANCEL_CALL", "CELLOCATOR", "COMMAND", "DATA_CALL", "DATA_CALL_ENCRYPTED", "DATA_PACKET",
        "DENIAL", "DEREGISTER", "DYNAMIC_REGROUP", "EMERGENCY", "GPS", "ICMP_PACKET", "IP_PACKET", "LRRP", "PAGE",
        "QUERY", "REGISTER", "REMOTE_MONITOR", "REQUEST", "RESPONSE", "SDM", "STATUS", "UDP_PACKET", "UNKNOWN_PACKET",
        "RADIO_CHECK", "RADIO_DETACH", "RADIO_DETACH_ACK", "RADIO_INHIBIT", "RADIO_UNINHIBIT", "RADIO_INHIBIT_ACK",
        "RADIO_UNINHIBIT_ACK", "TONE");

    /** Used only by fresh database creation and the bundled adjacent Application Migrator. */
    static void create(Connection connection) throws SQLException
    {
        try(var statement = connection.createStatement()) { statement.executeUpdate(SQL); }
        try(PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO activity_message_type(definition_kind,code,name,label,meaning,p25,dmr,nxdn,sort_order)
            VALUES(?,?,?,?,?,?,?,?,?)
            """))
        {
            for(Definition definition: definitions())
            {
                insert.setString(1, definition.kind()); insert.setInt(2, definition.code());
                insert.setString(3, definition.name()); insert.setString(4, definition.label());
                insert.setString(5, definition.meaning()); insert.setInt(6, definition.p25() ? 1 : 0);
                insert.setInt(7, definition.dmr() ? 1 : 0); insert.setInt(8, definition.nxdn() ? 1 : 0);
                insert.setInt(9, definition.code()); insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /** Bounded database reads are the sole runtime source of visible names, meanings and support. */
    public static List<Map<String,Object>> read(Connection connection) throws SQLException
    {
        return read(connection, "main");
    }

    private static List<Map<String,Object>> read(Connection connection, String schema) throws SQLException
    {
        List<Map<String,Object>> result = new ArrayList<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("""
            SELECT definition_kind,code,name,label,meaning,p25,dmr,nxdn,sort_order
            FROM %s.activity_message_type ORDER BY definition_kind,sort_order,code
            """.formatted(schema)))
        {
            while(rows.next())
            {
                Map<String,Object> item = new LinkedHashMap<>();
                item.put("definition_kind", rows.getString(1)); item.put("code", rows.getInt(2));
                item.put("name", rows.getString(3)); item.put("label", rows.getString(4));
                item.put("meaning", rows.getString(5)); item.put("p25", rows.getBoolean(6));
                item.put("dmr", rows.getBoolean(7)); item.put("nxdn", rows.getBoolean(8));
                item.put("sort_order", rows.getInt(9)); result.add(Map.copyOf(item));
            }
        }
        return List.copyOf(result);
    }

    /** Separate namespaces prevent an action code being mistaken for a decoder event code. */
    public static Map<String,Map<String,Integer>> codes(Connection connection) throws SQLException
    {
        Map<String,Integer> actions = new LinkedHashMap<>();
        Map<String,Integer> events = new LinkedHashMap<>();
        for(Map<String,Object> definition: read(connection))
        {
            ("ACTION".equals(definition.get("definition_kind")) ? actions : events)
                .put((String)definition.get("name"), (Integer)definition.get("code"));
        }
        return Map.of("ACTION", Map.copyOf(actions), "EVENT", Map.copyOf(events));
    }

    /** Stable identities are strict; database-owned friendly wording is deliberately not overwritten or compared. */
    public static void validate(Connection connection) throws SQLException
    {
        validate(connection, "main");
    }

    /** Checks the exact attached history schema and its bounded stable catalogue identities before commit. */
    public static void validate(Connection connection, String schema) throws SQLException
    {
        if(!List.of("main", "activity").contains(schema)) throw new SQLException("Unknown Activity catalogue schema");
        SqliteSchemaValidator.validateDefinitions(connection,
            List.of(new SqliteSchemaValidator.Definition("table", "activity_message_type", SQL)), schema);
        Map<String,String> expected = new LinkedHashMap<>();
        for(Definition definition: definitions())
            expected.put(definition.kind() + ":" + definition.code(), definition.name());
        Map<String,String> actual = new LinkedHashMap<>();
        for(Map<String,Object> definition: read(connection, schema))
            actual.put(definition.get("definition_kind") + ":" + definition.get("code"), (String)definition.get("name"));
        if(!expected.equals(actual)) throw new SQLException("Activity message definitions are incomplete or have changed stable identities");
    }

    private static List<Definition> definitions()
    {
        List<Definition> result = new ArrayList<>();
        for(var action: ReceiverActivityCodes.actionCodes())
        {
            String name = action.name();
            Text text = actionText(name);
            result.add(new Definition("ACTION", action.code(), name, text.label(), text.meaning(),
                P25_ACTIONS.contains(name), DMR_ACTIONS.contains(name), NXDN_ACTIONS.contains(name)));
        }
        for(var event: ReceiverActivityCodes.eventTypeCodes())
        {
            String name = event.eventType().name();
            Text text = eventText(name);
            result.add(new Definition("EVENT", event.code(), name, text.label(), text.meaning(),
                P25_EVENTS.contains(name), DMR_EVENTS.contains(name), NXDN_EVENTS.contains(name)));
        }
        return List.copyOf(result);
    }

    private static Text actionText(String name)
    {
        return switch(name)
        {
            case "ACKNOWLEDGE" -> text("Acknowledgment", "A radio or system acknowledged a message.");
            case "ACTIVE" -> text("Active channel", "The system reported activity on a traffic channel.");
            case "BUSY" -> text("Busy", "The requested radio, talkgroup or service was busy.");
            case "CALL" -> text("Call", "An accepted voice call was observed.");
            case "CHECK" -> text("Radio check", "A radio or service availability check was requested.");
            case "CHECK_ACK" -> text("Affiliation confirmation", "A radio confirmed its talkgroup affiliation in response to a system query.");
            case "CONTINUE" -> text("Channel update", "The system updated an existing channel assignment.");
            case "DATA" -> text("Data", "A data call or decoded data message was observed.");
            case "DENIAL" -> text("Denied", "The system or radio refused a request.");
            case "EMERGENCY" -> text("Emergency", "Emergency signaling was observed.");
            case "GPS" -> text("Location", "A decoded message supplied a radio location.");
            case "GRANT" -> text("Channel grant", "The trunked system assigned a traffic channel.");
            case "JOIN" -> text("Talkgroup affiliation", "A radio reported or received a talkgroup affiliation.");
            case "LOGOUT" -> text("Registration cleared", "A radio registration or affiliation was cleared.");
            case "PAGE" -> text("Call alert", "A call alert or radio page was observed.");
            case "PATCH" -> text("Patch", "A talkgroup patch or regrouping message was observed.");
            case "PATCH_CANCEL" -> text("Patch removed", "A talkgroup patch was canceled or deactivated.");
            case "PATCH_CREATE" -> text("Patch created", "A talkgroup patch was created or activated.");
            case "QUEUED" -> text("Queued", "A request was queued while waiting for a radio or resource.");
            case "REGISTER" -> text("Registration", "A radio registration message was observed.");
            case "REQUEST" -> text("Request", "A radio or system requested a service.");
            case "STATUS" -> text("Status", "A radio or system status message was observed.");
            case "UNKNOWN" -> text("Other signaling", "Another signaling message was observed.");
            case "TONE" -> text("Tone", "A tone was detected during a call.");
            default -> operationText(name);
        };
    }

    private static Text operationText(String name)
    {
        return switch(name)
        {
            case "STUN" -> text("Stun", "A command or response to disable a radio temporarily; a command alone does not confirm success.");
            case "REVIVE" -> text("Revive", "A command or response to restore a stunned radio; a command alone does not confirm success.");
            case "KILL" -> text("Kill", "A command or response to disable a radio; a command alone does not confirm success.");
            case "RADIO_INHIBIT" -> text("Radio inhibit", "A command to inhibit a radio was observed; its acknowledgment is a separate message.");
            case "RADIO_UNINHIBIT" -> text("Radio uninhibit", "A command to restore an inhibited radio was observed; its acknowledgment is a separate message.");
            case "REMOTE_MONITOR" -> text("Remote monitor", "A remote-monitor command or response was observed; a command alone does not confirm activation.");
            case "AUTHENTICATE" -> text("Authentication", "The system requested that a radio authenticate.");
            case "CANCEL_CALL" -> text("Cancel call", "A command to cancel a call was observed.");
            case "RADIO_DETACH" -> text("Radio detach", "A command to detach a radio from the system was observed.");
            case "PROTECT" -> text("Protect", "A DMR protect command was observed.");
            case "VOTE_NOW" -> text("Vote now", "The system directed radios to evaluate a site or channel.");
            default -> throw new IllegalStateException("Missing Activity message definition: " + name);
        };
    }

    private static Text eventText(String name)
    {
        return switch(name)
        {
            case "STUN" -> text("Stun command", "A command to disable a radio temporarily was observed.");
            case "REVIVE" -> text("Revive command", "A command to restore a stunned radio was observed.");
            case "KILL" -> text("Kill command", "A command to disable a radio was observed.");
            case "REMOTE_MONITOR" -> text("Remote monitor command", "A remote-monitor command was observed.");
            case "AFFILIATE" -> actionText("JOIN");
            case "ANNOUNCEMENT" -> text("Announcement", "The system broadcast an announcement.");
            case "AUTOMATIC_REGISTRATION_SERVICE" -> text("Motorola ARS", "A Motorola automatic registration service message was decoded.");
            case "CALL_ENCRYPTED" -> text("Encrypted call", "An encrypted voice call was observed.");
            case "CALL_GROUP" -> text("Group call", "A voice call to a talkgroup was observed.");
            case "CALL_GROUP_ENCRYPTED" -> text("Encrypted group call", "An encrypted talkgroup voice call was observed.");
            case "CALL_PATCH_GROUP" -> text("Patch call", "A voice call to a patched group was observed.");
            case "CALL_PATCH_GROUP_ENCRYPTED" -> text("Encrypted patch call", "An encrypted voice call to a patched group was observed.");
            case "CALL_ALERT" -> actionText("PAGE");
            case "CALL_DETECT" -> text("Call detected", "A receiver detected a call.");
            case "CALL_IN_PROGRESS" -> text("Call in progress", "A receiver reported an existing call.");
            case "CALL_DO_NOT_MONITOR" -> text("Call excluded", "A call was excluded by the monitoring policy.");
            case "CALL_END" -> text("Call ended", "A receiver reported the end of a call.");
            case "CALL_INTERCONNECT" -> text("Telephone call", "A telephone interconnect voice call was observed.");
            case "CALL_INTERCONNECT_ENCRYPTED" -> text("Encrypted telephone call", "An encrypted telephone interconnect call was observed.");
            case "CALL_UNIQUE_ID" -> text("Unique-ID call", "A call carrying a unique radio identifier was observed.");
            case "CALL_UNIT_TO_UNIT" -> text("Private call", "A voice call between individual radios was observed.");
            case "CALL_UNIT_TO_UNIT_ENCRYPTED" -> text("Encrypted private call", "An encrypted voice call between individual radios was observed.");
            case "CALL_NO_TUNER" -> text("Call without tuner", "A call could not be followed because no tuner was available.");
            case "CALL_TIMEOUT" -> text("Call timed out", "A receiver ended a call after its activity timeout.");
            case "CELLOCATOR" -> text("Cellocator data", "A Cellocator data message was decoded.");
            case "COMMAND" -> text("Other command", "A command was decoded without a more specific operation.");
            case "DATA_CALL" -> text("Data call", "A data-channel call was observed.");
            case "DATA_CALL_ENCRYPTED" -> text("Encrypted data call", "An encrypted data-channel call was observed.");
            case "DATA_PACKET" -> text("Data packet", "A data packet was decoded.");
            case "DEREGISTER" -> actionText("LOGOUT");
            case "DYNAMIC_REGROUP" -> actionText("PATCH");
            case "FUNCTION" -> text("Other function", "A radio control function was decoded.");
            case "ICMP_PACKET" -> text("ICMP packet", "An ICMP network packet was decoded.");
            case "ID_ANI" -> text("Automatic number identification", "An automatic number identification message was decoded.");
            case "ID_UNIQUE" -> text("Unique identifier", "A unique identifier message was decoded.");
            case "IP_PACKET" -> text("IP packet", "An IP data packet was decoded.");
            case "LRRP" -> text("Motorola LRRP", "A Motorola location request or response was decoded.");
            case "NOTIFICATION" -> text("Notification", "A radio or system notification was decoded.");
            case "QUERY" -> actionText("CHECK");
            case "RADIO_CHECK" -> actionText("CHECK");
            case "RADIO_REGISTRATION_SERVICE" -> text("Radio registration service", "A radio registration service packet was decoded.");
            case "REGISTER_ESN" -> text("Radio serial number", "A radio serial-number registration message was decoded.");
            case "RESPONSE" -> text("Other response", "A response was decoded without a more specific operation or result.");
            case "RESPONSE_PACKET" -> text("Response packet", "A data response packet was decoded.");
            case "SDM" -> text("Short data", "A short data message was decoded.");
            case "SMS" -> text("SMS", "An SMS message was decoded.");
            case "STATION_ID" -> text("Station identification", "A station identification message was decoded.");
            case "TEXT_MESSAGE" -> text("Text message", "A text message was decoded.");
            case "UDP_PACKET" -> text("UDP packet", "A UDP data packet was decoded.");
            case "UNKNOWN_PACKET" -> text("Unrecognized packet", "A packet was decoded without a recognized application type.");
            case "XCMP" -> text("Motorola XCMP", "A Motorola radio control protocol packet was decoded.");
            case "RADIO_UNINHIBIT_ACK" -> text("Radio uninhibit acknowledgment", "A radio acknowledged an uninhibit operation.");
            case "RADIO_INHIBIT_ACK" -> text("Radio inhibit acknowledgment", "A radio acknowledged an inhibit operation.");
            case "RADIO_DETACH_ACK" -> text("Radio detach acknowledgment", "A radio acknowledged a detach operation.");
            case "STUN_RESPONSE" -> text("Stun response", "A radio replied to a stun command. The result is unknown.");
            case "REVIVE_RESPONSE" -> text("Revive response", "A radio replied to a revive command. The result is unknown.");
            case "KILL_RESPONSE" -> text("Kill response", "A radio replied to a kill command. The result is unknown.");
            case "REMOTE_MONITOR_RESPONSE" -> text("Remote monitor response", "A radio replied to a remote-monitor command. The result is unknown.");
            case "ACKNOWLEDGE", "CALL", "DENIAL", "EMERGENCY", "GPS", "PAGE", "REGISTER", "REQUEST", "STATUS", "UNKNOWN", "TONE", "BUSY", "QUEUED" -> actionText(name);
            default -> operationText(name);
        };
    }

    private static Text text(String label, String meaning) { return new Text(label, meaning); }
    private record Text(String label, String meaning) {}
    private record Definition(String kind, int code, String name, String label, String meaning,
                              boolean p25, boolean dmr, boolean nxdn) {}
}
