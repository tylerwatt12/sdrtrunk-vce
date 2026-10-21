/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import io.github.dsheirer.service.radioreference.RadioSystemNameCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.sqlite.ProgressHandler;

/** Recording-backed system, heard-at site, and saved-channel browsers. No configuration is an activity total. */
final class ManagedRecordingEntityDiscovery
{
    private final Path mMainDatabase;
    private final RadioSystemNameCatalog mSystemNames;

    ManagedRecordingEntityDiscovery(Path mainDatabase) { this(mainDatabase, RadioSystemNameCatalog.bundled()); }

    ManagedRecordingEntityDiscovery(Path mainDatabase, RadioSystemNameCatalog systemNames)
    {
        mMainDatabase = mainDatabase;
        mSystemNames = java.util.Objects.requireNonNull(systemNames);
    }

    Map<String,Object> search(Path catalog, String kind, String query, String systemKey, Long fromMs, Long toMs,
                              int offset, int limit, String sort) throws SQLException
    {
        return search(catalog, kind, query, systemKey, fromMs, toMs, offset, limit, sort, false, null);
    }

    Map<String,Object> groups(Path catalog, String kind, String query, String systemKey, Long fromMs, Long toMs,
                              int offset, int limit, String sort, String groupKey) throws SQLException
    {
        return search(catalog, kind, query, systemKey, fromMs, toMs, offset, limit, sort, true, groupKey);
    }

    private Map<String,Object> search(Path catalog, String kind, String query, String systemKey, Long fromMs, Long toMs,
                                      int offset, int limit, String sort, boolean grouped, String groupKey) throws SQLException
    {
        ManagedRecordingBrowseOrder.sort(sort);
        boolean labels = Files.isRegularFile(mMainDatabase);
        try(Connection connection = DriverManager.getConnection(labels ?
            "jdbc:sqlite:" + mMainDatabase.toAbsolutePath().toUri() + "?mode=ro" : "jdbc:sqlite::memory:"))
        {
            try(PreparedStatement statement = connection.prepareStatement("ATTACH DATABASE ? AS recordings"))
            {
                statement.setString(1, catalog.toAbsolutePath().normalize().toUri() + "?mode=ro");
                statement.execute();
            }
            try(Statement statement = connection.createStatement())
            {
                statement.execute("PRAGMA query_only=ON");
                statement.execute("PRAGMA busy_timeout=1000");
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            ProgressHandler.setHandler(connection, 10000, new ProgressHandler()
            {
                @Override protected int progress() { return System.nanoTime() >= deadline ? 1 : 0; }
            });
            if("dcs".equals(kind))
            {
                List<Map<String,Object>> rows = ManagedRecordingDcsDiscovery.rows(connection,systemKey,fromMs,toMs,
                    query,deadline);
                rows.sort(ManagedRecordingBrowseOrder.comparator(sort,null));
                checkDeadline(deadline);
                return grouped ? ManagedRecordingPickerGroups.page(rows,kind,groupKey,offset,limit,deadline) :
                    ManagedRecordingBrowseOrder.page(rows,offset,limit);
            }
            Map<String,Entity> entities = aggregate(connection, kind, systemKey, fromMs, toMs);
            Map<String,Channel> channels = labels ? channels(connection) : Map.of();
            Map<String,String> sites = labels && "site".equals(kind) ? siteNames(connection) : Map.of();
            StatsSystemNameResolver names = new StatsSystemNameResolver(labels ? connection : null, mSystemNames);
            Map<String,String> systemNames = new LinkedHashMap<>();
            List<Map<String,Object>> rows = new ArrayList<>();
            String match = query != null ? query.toLowerCase(Locale.ROOT) : null;
            for(Entity entity: entities.values())
            {
                checkDeadline(deadline);
                Map<String,Object> row = entity.row(kind);
                String key = string(row.get("system_key"));
                if(key != null)
                {
                    if(!systemNames.containsKey(key))
                        systemNames.put(key, string(names.enrichMap(Map.of("system_key",key)).get("system_name")));
                    put(row,"system_name",systemNames.get(key));
                }
                if("system".equals(kind))
                {
                    row.put("label", row.getOrDefault("system_name",entity.id));
                    try { WebEntityRef.put(row, WebEntityRef.radioSystem(entity.id)); }
                    catch(IllegalArgumentException ignored) { /* Historical noncanonical keys remain filterable. */ }
                }
                else if("channel".equals(kind))
                {
                    Channel channel = channels.get(entity.id);
                    row.put("label", channel != null && channel.name() != null ? channel.name() : entity.id);
                    if(channel != null)
                    {
                        put(row,"site_name",channel.siteName());
                        if(key == null) put(row,"system_name",channel.systemName());
                    }
                    try { WebEntityRef.put(row,WebEntityRef.channel(entity.id)); }
                    catch(IllegalArgumentException ignored) { /* Preserve old saved IDs as literal filters. */ }
                }
                else if(entity.channelSite)
                {
                    Channel channel = channels.get(entity.id);
                    if(channel == null || channel.siteName() == null && channel.name() == null) continue;
                    row.put("label",channel.siteName() != null ? channel.siteName() : channel.name());
                    put(row,"channel_name",channel.name());
                    if(key == null) put(row,"system_name",channel.systemName());
                }
                else
                {
                    String fallback = "RFSS " + row.get("rfss") + " · Site " + row.get("site_id");
                    row.put("label",sites.getOrDefault(entity.id,fallback));
                }
                List<String> detail = new ArrayList<>();
                if(!"system".equals(kind) && row.get("system_name") instanceof String name) detail.add(name);
                if("channel".equals(kind) && row.get("site_name") instanceof String site) detail.add(site);
                if(entity.channelSite && row.get("channel_name") instanceof String channel) detail.add(channel);
                if(!entity.protocols.isEmpty()) detail.add(String.join(" / ",entity.protocols));
                if(!detail.isEmpty()) row.put("detail",String.join(" · ",detail));
                if(match == null || searchable(row).contains(match)) rows.add(row);
            }
            rows.sort(ManagedRecordingBrowseOrder.comparator(sort,null));
            checkDeadline(deadline);
            return grouped ? ManagedRecordingPickerGroups.page(rows,kind,groupKey,offset,limit,deadline) :
                ManagedRecordingBrowseOrder.page(rows,offset,limit);
        }
    }

    private static Map<String,Entity> aggregate(Connection connection, String kind, String systemKey,
                                                 Long fromMs, Long toMs) throws SQLException
    {
        String select = switch(kind)
        {
            case "system" -> "SELECT system_key AS entity_id,system_key,protocol,count(*) AS calls," +
                "max(start_ms) AS last_ms FROM scoped WHERE system_key IS NOT NULL GROUP BY system_key,protocol";
            case "channel" -> "SELECT channel_uuid AS entity_id,system_key,protocol,count(*) AS calls," +
                "max(start_ms) AS last_ms FROM scoped WHERE channel_uuid IS NOT NULL " +
                "GROUP BY channel_uuid,system_key,protocol";
            case "site" -> "SELECT printf('%d:%d:%d:%d',site.wacn,site.system_id,site.rfss,site.site_id) AS entity_id," +
                "printf('p25:%05x:%03x',site.wacn,site.system_id) AS system_key,scoped.protocol," +
                "site.wacn,site.system_id AS sysid,site.rfss,site.site_id,count(DISTINCT scoped.call_id) AS calls," +
                "max(scoped.start_ms) AS last_ms,0 AS channel_site FROM scoped JOIN recordings.recording_call_site cs ON cs.call_id=scoped.call_id " +
                "JOIN recordings.recording_site site ON site.id=cs.site_id GROUP BY site.id,scoped.protocol " +
                "UNION ALL SELECT channel_uuid AS entity_id,system_key,protocol,NULL,NULL,NULL,NULL,count(*)," +
                "max(start_ms),1 FROM scoped WHERE channel_uuid IS NOT NULL AND channel_uuid IN(" +
                "SELECT channel_uuid FROM scoped WHERE protocol NOT IN(1,2)) GROUP BY channel_uuid,system_key,protocol";
            default -> throw new IllegalArgumentException("Unknown recording entity kind");
        };
        String sql = scopedCallsSql(select);
        Map<String,Entity> result = new LinkedHashMap<>();
        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setLong(1,fromMs != null ? fromMs : 0);
            statement.setLong(2,toMs != null ? toMs : Long.MAX_VALUE);
            statement.setString(3,systemKey);
            statement.setString(4,systemKey);
            try(ResultSet rows = statement.executeQuery())
            {
                while(rows.next())
                {
                    String id = rows.getString("entity_id");
                    Entity entity = result.computeIfAbsent(id,Entity::new);
                    entity.count += rows.getLong("calls");
                    entity.last = Math.max(entity.last,rows.getLong("last_ms"));
                    entity.protocols.add(protocol(rows.getInt("protocol")));
                    String key = rows.getString("system_key");
                    if(key != null) entity.systems.add(key);
                    else entity.unknownSystem = true;
                    if("site".equals(kind))
                    {
                        entity.channelSite=rows.getInt("channel_site")==1;
                        if(!entity.channelSite)
                            for(String field: List.of("wacn","sysid","rfss","site_id"))
                                entity.coordinates.put(field,rows.getInt(field));
                    }
                }
            }
        }
        return result;
    }

    /** Shared captured-call scope keeps DCS and entity browsing aligned with recorded P25 ownership. */
    static String scopedCallsSql(String select)
    {
        return """
            WITH recorded AS (
                SELECT c.id AS call_id,c.start_ms,c.protocol,ch.channel_uuid,c.tone_kind,c.tone,
                    coalesce(sys.system_key,CASE WHEN c.protocol IN(1,2) THEN
                        CASE WHEN winner.wacn IS NOT NULL THEN printf('p25:%%05x:%%03x',winner.wacn,winner.system_id)
                        WHEN c.winner_site_id IS NULL THEN (SELECT
                            CASE WHEN count(DISTINCT printf('p25:%%05x:%%03x',s.wacn,s.system_id))=1
                                THEN printf('p25:%%05x:%%03x',s.wacn,s.system_id) END
                            FROM recordings.recording_call_site observed JOIN recordings.recording_site s ON s.id=observed.site_id
                            WHERE observed.call_id=c.id) END END) AS system_key
                FROM recordings.recording_call c
                LEFT JOIN recordings.recording_system sys ON sys.id=c.system_id
                LEFT JOIN recordings.recording_channel ch ON ch.id=c.channel_id
                LEFT JOIN recordings.recording_site winner ON winner.id=c.winner_site_id
                WHERE c.start_ms BETWEEN ? AND ?
            ), scoped AS (SELECT * FROM recorded WHERE (? IS NULL OR system_key=?)) %s
            """.formatted(select);
    }

    private static Map<String,Channel> channels(Connection connection) throws SQLException
    {
        Map<String,Channel> result = new LinkedHashMap<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
            "SELECT configuration_id,name,system_name,site_name FROM configuration_channel"))
        {
            while(rows.next()) result.put(rows.getString(1),new Channel(string(rows.getString(2)),
                string(rows.getString(3)),string(rows.getString(4))));
        }
        return result;
    }

    private static Map<String,String> siteNames(Connection connection) throws SQLException
    {
        Map<String,Set<String>> names = new LinkedHashMap<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
            SELECT system.p25_wacn,system.p25_system_id,snapshot.rfss,snapshot.site,config.site_name
            FROM p25_site_snapshot snapshot JOIN receiver_channel receiver ON receiver.id=snapshot.channel_id
            JOIN configuration_channel config ON config.configuration_id=receiver.configuration_id
            JOIN radio_system system ON system.id=receiver.radio_system_id
            WHERE system.protocol_code=1 AND system.p25_wacn IS NOT NULL AND system.p25_system_id IS NOT NULL
                AND snapshot.rfss IS NOT NULL AND snapshot.site IS NOT NULL AND nullif(trim(config.site_name),'') IS NOT NULL
            ORDER BY lower(config.site_name),config.site_name
            """))
        {
            while(rows.next())
                names.computeIfAbsent(rows.getInt(1)+":"+rows.getInt(2)+":"+rows.getInt(3)+":"+rows.getInt(4),
                    ignored -> new LinkedHashSet<>()).add(rows.getString(5));
        }
        Map<String,String> result = new LinkedHashMap<>();
        names.forEach((key,value) -> result.put(key,String.join(" / ",value)));
        return result;
    }

    private static String searchable(Map<String,Object> row)
    {
        StringBuilder value = new StringBuilder();
        for(String field: List.of("id","label","system_key","system_name","site_name","channel_name","detail",
            "protocol","protocols","wacn","sysid","rfss","site_id","channel_id"))
            if(row.get(field) != null) value.append(row.get(field)).append('\n');
        return value.toString().toLowerCase(Locale.ROOT);
    }
    private static String string(Object value) { return value instanceof String text && !text.isBlank() ? text : null; }
    private static void put(Map<String,Object> row, String key, String value) { if(value != null) row.put(key,value); }
    private static void checkDeadline(long deadline) throws SQLException
    { if(System.nanoTime() >= deadline) throw new SQLException("Recording browser exceeded its time limit","",9); }
    private static String protocol(int value)
    {
        return switch(value) { case 1 -> "APCO25"; case 2 -> "APCO25_PHASE2"; case 3 -> "DMR";
            case 4 -> "NXDN"; case 5 -> "NBFM"; case 6 -> "AM"; case 7 -> "DCS"; case 8 -> "FLEETSYNC"; case 9 -> "MDC1200";
            case 10 -> "LOJACK"; case 11 -> "TAIT1200"; default -> "UNKNOWN"; };
    }
    private record Channel(String name, String systemName, String siteName) {}

    private static final class Entity
    {
        private final String id;
        private long count,last;
        private boolean unknownSystem,channelSite;
        private final Set<String> systems = new LinkedHashSet<>();
        private final Set<String> protocols = new java.util.TreeSet<>();
        private final Map<String,Object> coordinates = new LinkedHashMap<>();
        Entity(String id) { this.id = id; }
        Map<String,Object> row(String kind)
        {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("kind",kind); row.put("id",id); row.put("call_count",count); row.put("last_recorded_ms",last);
            row.put("count_kind","site".equals(kind) && !channelSite ? "heard_at" : "calls");
            row.put("protocols",List.copyOf(protocols));
            if(protocols.size() == 1) row.put("protocol",protocols.iterator().next());
            if(systems.size() == 1 && !unknownSystem) row.put("system_key",systems.iterator().next());
            Map<String,Object> selection = new LinkedHashMap<>();
            if("system".equals(kind)) { row.put("system_key",id); selection.put("system_key",id); }
            else if("channel".equals(kind)) { row.put("channel_id",id); selection.put("channel_id",id); }
            else if(channelSite) { row.put("channel_id",id); selection.put("channel_id",id); }
            else { row.putAll(coordinates); selection.putAll(coordinates); }
            row.put("selection",selection);
            return row;
        }
    }
}
