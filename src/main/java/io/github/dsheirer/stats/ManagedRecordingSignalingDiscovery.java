/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import io.github.dsheirer.preference.identifier.talkgroup.FleetsyncTalkgroupFormatter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Typed auxiliary radio identities retain their protocol and literal value independently of the audio protocol. */
final class ManagedRecordingSignalingDiscovery
{
    private ManagedRecordingSignalingDiscovery() {}

    static List<Map<String,Object>> rows(Connection connection, boolean labels, String systemKey, Long fromMs,
                                         Long toMs, String role, String query, long deadline) throws SQLException
    {
        String sql = """
            WITH recorded AS (
                SELECT c.id AS call_id,c.start_ms,c.protocol AS primary_protocol,%s AS alias_list_id,
                    coalesce(sys.system_key,CASE WHEN c.protocol IN(1,2) THEN
                        CASE WHEN winner.wacn IS NOT NULL THEN printf('p25:%%05x:%%03x',winner.wacn,winner.system_id)
                        WHEN c.winner_site_id IS NULL THEN (SELECT
                            CASE WHEN count(DISTINCT printf('p25:%%05x:%%03x',s.wacn,s.system_id))=1
                                THEN printf('p25:%%05x:%%03x',s.wacn,s.system_id) END
                            FROM recordings.recording_call_site observed JOIN recordings.recording_site s ON s.id=observed.site_id
                            WHERE observed.call_id=c.id) END END) AS system_key
                FROM recordings.recording_call c LEFT JOIN recordings.recording_system sys ON sys.id=c.system_id
                LEFT JOIN recordings.recording_site winner ON winner.id=c.winner_site_id %s
                WHERE c.start_ms BETWEEN ? AND ?
            ) SELECT c.system_key,c.alias_list_id,c.primary_protocol,identity.protocol,identity.value,
                count(DISTINCT c.call_id) AS calls,max(c.start_ms) AS last_ms
                FROM recorded c JOIN recordings.recording_identifier identity ON identity.call_id=c.call_id
                WHERE identity.kind='radio' AND (? IS NULL OR c.system_key=?) %s
                GROUP BY c.system_key,c.alias_list_id,c.primary_protocol,identity.protocol,identity.value
            """.formatted(labels ? "coalesce(config.alias_list_id,c.alias_list_id)" : "c.alias_list_id",
                labels ? "LEFT JOIN recordings.recording_channel ch ON ch.id=c.channel_id " +
                    "LEFT JOIN configuration_channel config ON config.configuration_id=ch.channel_uuid" : "",
                "source".equals(role) ? "AND identity.role='source'" : "");
        List<Map<String,Object>> contexts = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setLong(1,fromMs != null ? fromMs : 0);
            statement.setLong(2,toMs != null ? toMs : Long.MAX_VALUE);
            statement.setString(3,systemKey); statement.setString(4,systemKey);
            try(ResultSet values = statement.executeQuery())
            {
                while(values.next())
                {
                    check(deadline);
                    Map<String,Object> row = new LinkedHashMap<>();
                    row.put("system_key",values.getString("system_key"));
                    row.put("alias_list_id",values.getObject("alias_list_id"));
                    row.put("primary_protocol",primaryProtocol(values.getInt("primary_protocol")));
                    row.put("protocol",values.getString("protocol")); row.put("value",values.getString("value"));
                    row.put("call_count",values.getLong("calls")); row.put("last_recorded_ms",values.getLong("last_ms"));
                    String literal = values.getString("value");
                    if(literal.matches("[0-9]{1,8}"))
                    {
                        long number = Long.parseLong(literal);
                        if(number <= 0xFFFFFF) row.put("numeric_value",(int)number);
                    }
                    contexts.add(row);
                }
            }
        }
        StatsAliasResolver aliases = new StatsAliasResolver();
        if(labels)
            for(int start=0;start<contexts.size();start+=200)
            {
                check(deadline);
                aliases.enrichRecordingSignaling(connection,contexts.subList(start,Math.min(start+200,contexts.size())));
            }
        StatsSystemNameResolver names = labels ? new StatsSystemNameResolver(connection) : null;
        Map<String,String> systemNames = new LinkedHashMap<>();
        Map<Key,Aggregate> combined = new LinkedHashMap<>();
        for(Map<String,Object> context: contexts)
        {
            check(deadline);
            Key key = new Key((String)context.get("system_key"),(String)context.get("protocol"),(String)context.get("value"));
            if(key.system() != null && names != null)
            {
                if(!systemNames.containsKey(key.system()))
                    systemNames.put(key.system(),(String)names.enrichMap(Map.of("system_key",key.system())).get("system_name"));
                if(systemNames.get(key.system()) != null) context.put("system_name",systemNames.get(key.system()));
            }
            combined.computeIfAbsent(key,Aggregate::new).add(context);
        }
        String match = query != null ? query.toLowerCase(Locale.ROOT) : null;
        return combined.values().stream().filter(row -> match == null || row.matches(match)).map(Aggregate::row).toList();
    }

    private static String primaryProtocol(int value)
    {
        return switch(value) { case 1 -> "APCO25"; case 2 -> "APCO25_PHASE2"; case 3 -> "DMR"; case 4 -> "NXDN";
            case 5 -> "NBFM"; case 6 -> "AM"; case 7 -> "DCS"; case 8 -> "FLEETSYNC"; case 9 -> "MDC1200";
            case 10 -> "LOJACK"; case 11 -> "TAIT1200"; default -> "UNKNOWN"; };
    }
    private static void check(long deadline) throws SQLException
    { if(System.nanoTime() >= deadline) throw new SQLException("Recording browser exceeded its time limit","",9); }
    private record Key(String system,String protocol,String value) {}

    private static final class Aggregate
    {
        private final Key key;
        private final StringBuilder terms = new StringBuilder();
        private final Set<String> primary = new java.util.TreeSet<>();
        private String alias,description,group,systemName;
        private boolean first = true;
        private long count,last;
        Aggregate(Key key) { this.key=key; }
        void add(Map<String,Object> row)
        {
            if(first)
            {
                alias=text(row.get("alias_name")); description=text(row.get("alias_description"));
                group=text(row.get("alias_group")); first=false;
            }
            else
            {
                if(!java.util.Objects.equals(alias,text(row.get("alias_name")))) alias=null;
                if(!java.util.Objects.equals(description,text(row.get("alias_description")))) description=null;
                if(!java.util.Objects.equals(group,text(row.get("alias_group")))) group=null;
            }
            systemName=text(row.get("system_name"));
            count+=((Number)row.get("call_count")).longValue();
            last=Math.max(last,((Number)row.get("last_recorded_ms")).longValue());
            primary.add(String.valueOf(row.get("primary_protocol")));
            for(String field: List.of("alias_name","alias_description","alias_group","value","protocol","system_key","system_name"))
                if(row.get(field) != null) terms.append(row.get(field)).append('\n');
            terms.append(displayValue()).append('\n');
        }
        boolean matches(String query) { return terms.toString().toLowerCase(Locale.ROOT).contains(query); }
        String displayValue()
        {
            if("FLEETSYNC".equals(key.protocol()) && key.value().matches("[0-9]{1,8}"))
                return new FleetsyncTalkgroupFormatter().format(Integer.parseInt(key.value()));
            return key.value();
        }
        Map<String,Object> row()
        {
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("kind","radio"); row.put("id",key.value()); row.put("value",key.value());
            row.put("protocol",key.protocol()); row.put("protocols",List.of(key.protocol()));
            row.put("primary_protocols",List.copyOf(primary));
            if(primary.size()==1) row.put("primary_protocol",primary.iterator().next());
            row.put("system_key",key.system());
            if(systemName!=null) row.put("system_name",systemName);
            row.put("label",alias!=null ? alias : displayValue());
            if(alias!=null) row.put("alias",alias);
            if(description!=null) row.put("description",description);
            if(group!=null) row.put("group",group);
            row.put("call_count",count); row.put("last_recorded_ms",last);
            Map<String,Object> selection=new LinkedHashMap<>();
            selection.put("id",null); selection.put("system_key",key.system());
            selection.put("protocol",key.protocol()); selection.put("value",key.value());
            row.put("selection",selection);
            return row;
        }
        private static String text(Object value) { return value instanceof String string && !string.isBlank() ? string : null; }
    }
}
