/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.stats;

import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.sqlite.ProgressHandler;

/** Read-only discovery over the recording catalog, with current channel-owned labels resolved in batches. */
final class ManagedRecordingIdentityDiscovery
{
    private final Path mMainDatabase;

    ManagedRecordingIdentityDiscovery(Path mainDatabase)
    {
        mMainDatabase = mainDatabase;
    }

    Map<String,Object> search(Path catalog, String kind, String query, String systemKey, Long fromMs, Long toMs,
                              int offset, int limit, String radioRole) throws SQLException
    {
        boolean labelsAvailable = Files.isRegularFile(mMainDatabase);
        String url = labelsAvailable ? "jdbc:sqlite:" + mMainDatabase.toAbsolutePath().toUri() + "?mode=ro" :
            "jdbc:sqlite::memory:";
        try(Connection connection = DriverManager.getConnection(url))
        {
            try(PreparedStatement attach = connection.prepareStatement("ATTACH DATABASE ? AS recordings"))
            {
                attach.setString(1, catalog.toAbsolutePath().normalize().toUri() + "?mode=ro");
                attach.execute();
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
            List<Map<String,Object>> candidates = candidates(connection, labelsAvailable, kind, systemKey,
                fromMs, toMs, radioRole, deadline);
            if(labelsAvailable)
            {
                StatsAliasResolver aliases = new StatsAliasResolver();
                for(int start = 0; start < candidates.size(); start += 500)
                {
                    checkDeadline(deadline);
                    aliases.enrichActivity(connection, candidates.subList(start, Math.min(start + 500,
                        candidates.size())));
                }
                StatsSystemNameResolver names = new StatsSystemNameResolver(connection);
                Map<String,String> systemNames = new LinkedHashMap<>();
                for(Map<String,Object> candidate: candidates)
                {
                    String key = (String)candidate.get("system_key");
                    if(key == null) continue;
                    if(!systemNames.containsKey(key))
                        systemNames.put(key, (String)names.enrichMap(Map.of("system_key", key)).get("system_name"));
                    if(systemNames.get(key) != null) candidate.put("system_name", systemNames.get(key));
                }
            }
            Map<SelectionKey,Aggregate> combined = new LinkedHashMap<>();
            for(Map<String,Object> candidate: candidates)
            {
                checkDeadline(deadline);
                SelectionKey key = new SelectionKey((String)candidate.get("system_key"),
                    ((Number)candidate.get("id")).intValue(), (String)candidate.get("identity_key"));
                combined.computeIfAbsent(key, ignored -> new Aggregate(candidate, kind)).add(candidate);
            }
            String match = query != null ? query.toLowerCase(Locale.ROOT) : null;
            List<Map<String,Object>> matching = combined.values().stream()
                .filter(value -> match == null || value.matches(match)).map(Aggregate::toMap)
                .sorted(Comparator.comparingInt((Map<String,Object> row) ->
                        query != null && query.equals(row.get("id").toString()) ? 0 : 1)
                    .thenComparing(row -> text(row.get("system_name")), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(row -> text(row.get("system_key")))
                    .thenComparing(row -> text(row.get("group")), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(row -> text(row.get("label")), String.CASE_INSENSITIVE_ORDER)
                    .thenComparingInt(row -> ((Number)row.get("id")).intValue())
                    .thenComparing(row -> text(row.get("identity_key"))))
                .toList();
            checkDeadline(deadline);
            int end = Math.min(matching.size(), offset + limit);
            boolean more = end < matching.size();
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("rows", offset < matching.size() ? matching.subList(offset, end) : List.of());
            result.put("has_more", more);
            if(more) result.put("next_offset", end);
            return result;
        }
    }

    /** One grouped catalog projection supplies counts; no archive query is performed for an individual identity. */
    private static List<Map<String,Object>> candidates(Connection connection, boolean labelsAvailable, String kind,
                                                       String systemKey, Long fromMs, Long toMs, String radioRole,
                                                       long deadline)
        throws SQLException
    {
        boolean radio = "radio".equals(kind);
        String selected = radio ? """
            SELECT call_id,start_ms,system_key,protocol,alias_list_id,source_id AS local_id,
                source_home_wacn AS home_wacn,source_home_system AS home_system,source_home_id AS home_id,
                2 AS identity_kind FROM scoped WHERE source_id>0
            """ : """
            SELECT call_id,start_ms,system_key,protocol,alias_list_id,target_id AS local_id,
                target_home_wacn AS home_wacn,target_home_system AS home_system,target_home_id AS home_id,
                1 AS identity_kind
            FROM scoped WHERE call_type IN (1,2) AND protocol IN (1,2,3,4) AND (target_id>0 OR protocol=4 AND target_id=0)
            """;
        if(!radio || !"source".equals(radioRole))
        {
            if(radio) selected += """
                UNION SELECT call_id,start_ms,system_key,protocol,alias_list_id,target_id,
                    target_home_wacn,target_home_system,target_home_id,2 FROM scoped
                    WHERE call_type=3 AND target_id>0
                """;
            selected += """
                UNION SELECT c.call_id,c.start_ms,c.system_key,c.protocol,c.alias_list_id,m.local_id,
                    nullif(m.home_wacn,-1),nullif(m.home_system,-1),nullif(m.home_id,-1),%d
                FROM scoped c JOIN recordings.recording_patch_member m ON m.call_id=c.call_id
                WHERE m.kind=%d AND (m.local_id>0 OR m.kind=1 AND c.protocol=4 AND m.local_id=0)
                    AND c.protocol IN (1,2,3,4)
                """.formatted(radio ? 2 : 1, radio ? 2 : 1);
        }
        String sql = """
            WITH recorded AS (
                SELECT c.id AS call_id,c.start_ms,c.protocol,c.call_type,c.source_id,c.source_home_wacn,
                    c.source_home_system,c.source_home_id,c.target_id,c.target_home_wacn,c.target_home_system,
                    c.target_home_id,%s AS alias_list_id,
                    coalesce(sys.system_key,CASE WHEN c.protocol IN (1,2) THEN
                        CASE WHEN winner.wacn IS NOT NULL THEN printf('p25:%%05x:%%03x',winner.wacn,winner.system_id)
                        WHEN c.winner_site_id IS NULL THEN (SELECT
                            CASE WHEN count(DISTINCT printf('p25:%%05x:%%03x',s.wacn,s.system_id))=1
                                THEN printf('p25:%%05x:%%03x',s.wacn,s.system_id) END
                            FROM recordings.recording_call_site cs JOIN recordings.recording_site s ON s.id=cs.site_id
                            WHERE cs.call_id=c.id) END END) AS system_key
                FROM recordings.recording_call c
                LEFT JOIN recordings.recording_system sys ON sys.id=c.system_id
                LEFT JOIN recordings.recording_site winner ON winner.id=c.winner_site_id
                %s
                WHERE c.start_ms BETWEEN ? AND ?
            ), scoped AS (SELECT * FROM recorded WHERE (? IS NULL OR system_key=?)),
            captured_identities AS (%s), identities AS (
                SELECT call_id,start_ms,system_key,protocol,alias_list_id,local_id,home_wacn,home_system,
                    CASE WHEN home_wacn IS NOT NULL AND home_system IS NOT NULL
                        THEN coalesce(home_id,local_id) ELSE home_id END AS home_id,identity_kind
                FROM captured_identities
            ), normalized AS (
                SELECT *,CASE WHEN protocol IN (1,2) AND home_wacn IS NOT NULL AND home_system IS NOT NULL
                    AND home_id>0 THEN home_id ELSE local_id END AS canonical_id FROM identities
            ), totals AS (
                SELECT system_key,canonical_id,home_wacn,home_system,home_id,identity_kind,
                    count(DISTINCT call_id) AS call_count,max(start_ms) AS last_recorded_ms
                FROM normalized GROUP BY system_key,canonical_id,home_wacn,home_system,home_id,identity_kind
            ), grouped AS (
                SELECT DISTINCT system_key,protocol,alias_list_id,local_id,canonical_id,
                    home_wacn,home_system,home_id,identity_kind FROM normalized
            ) SELECT g.*,totals.call_count,totals.last_recorded_ms%s FROM grouped g
                JOIN totals ON totals.system_key IS g.system_key AND totals.canonical_id=g.canonical_id
                    AND totals.home_wacn IS g.home_wacn AND totals.home_system IS g.home_system
                    AND totals.home_id IS g.home_id AND totals.identity_kind=g.identity_kind %s
            """.formatted(labelsAvailable ? "coalesce(config.alias_list_id,c.alias_list_id)" : "c.alias_list_id",
                labelsAvailable ? "LEFT JOIN recordings.recording_channel ch ON ch.id=c.channel_id " +
                    "LEFT JOIN configuration_channel config ON config.configuration_id=ch.channel_uuid" : "",
                selected, labelsAvailable ? ",summary.last_talker_alias,summary.p25_subscriber_identity_id" : "",
                labelsAvailable ? """
                    LEFT JOIN radio_system system ON system.system_key=g.system_key
                    LEFT JOIN radio_system_identity_summary summary ON summary.id=
                        CASE WHEN g.home_wacn IS NOT NULL AND g.home_system IS NOT NULL AND g.home_id>0 THEN (
                            SELECT identity.id FROM radio_system_identity_summary identity
                            WHERE identity.radio_system_id=system.id AND identity.identity_kind_code=g.identity_kind
                                AND identity.home_wacn=g.home_wacn AND identity.home_system_id=g.home_system
                                AND identity.identity_id=g.home_id
                        ) ELSE coalesce((
                            SELECT identity.id FROM radio_system_identity_summary identity
                            WHERE identity.radio_system_id=system.id AND identity.identity_kind_code=g.identity_kind
                                AND identity.home_wacn=-1 AND identity.home_system_id=-1
                                AND identity.identity_id=g.local_id AND identity.p25_subscriber_identity_id IS NULL
                        ), CASE WHEN g.identity_kind=2 THEN (
                            SELECT identity.id FROM radio_system_identity_summary identity
                            WHERE identity.radio_system_id=system.id AND identity.identity_kind_code=2
                                AND identity.home_wacn=system.p25_wacn AND identity.home_system_id=system.p25_system_id
                                AND identity.identity_id=g.local_id
                        ) END) END
                    """ : "");
        List<Map<String,Object>> result = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setLong(1, fromMs != null ? fromMs : 0);
            statement.setLong(2, toMs != null ? toMs : Long.MAX_VALUE);
            statement.setString(3, systemKey);
            statement.setString(4, systemKey);
            try(ResultSet rows = statement.executeQuery())
            {
                while(rows.next())
                {
                    if(result.size() % 500 == 0) checkDeadline(deadline);
                    Map<String,Object> row = new LinkedHashMap<>();
                    int id = rows.getInt("local_id");
                    row.put("id", rows.getInt("canonical_id"));
                    row.put("observed_id", id);
                    row.put("system_key", rows.getString("system_key"));
                    row.put("radio_system_key", rows.getString("system_key"));
                    int protocol = rows.getInt("protocol");
                    row.put("protocol", switch(protocol) {
                        case 1 -> "APCO25"; case 2 -> "APCO25_PHASE2"; case 3 -> "DMR";
                        case 4 -> "NXDN"; default -> "UNKNOWN";
                    });
                    row.put("protocol_code", protocol);
                    row.put("alias_list_id", rows.getObject("alias_list_id"));
                    Integer homeWacn = integer(rows.getObject("home_wacn"));
                    Integer homeSystem = integer(rows.getObject("home_system"));
                    Integer homeId = integer(rows.getObject("home_id"));
                    if((protocol == 1 || protocol == 2) && homeWacn != null && homeSystem != null &&
                        homeId != null && homeId > 0)
                    {
                        row.put("identity_key", RadioSystemIdentityKey.format(rows.getInt("identity_kind"),
                            homeWacn, homeSystem, homeId));
                    }
                    row.put("call_count", rows.getLong("call_count"));
                    row.put("last_recorded_ms", rows.getLong("last_recorded_ms"));
                    row.put(radio ? "source_radio_id" : "target_id", id);
                    row.put("target_kind_code", radio ? 0 : rows.getInt("identity_kind"));
                    row.put((radio ? "source_" : "target_") + "canonical_wacn", homeWacn);
                    row.put((radio ? "source_" : "target_") + "canonical_system_id", homeSystem);
                    row.put((radio ? "source_" : "target_") + "canonical_subscriber_id", homeId);
                    if(labelsAvailable)
                    {
                        row.put("ota_alias", rows.getString("last_talker_alias"));
                        Object subscriber = rows.getObject("p25_subscriber_identity_id");
                        row.put("source_p25_subscriber_identity_id", subscriber);
                        if(radio && homeId != null && homeId != id)
                            row.put("source_observed_working_id", id);
                        //An equal-number foreign permanent identity is not proof of a local Working ID.
                        if(radio && homeId != null && homeId == id && homeWacn != null && homeSystem != null &&
                            !String.format(Locale.ROOT, "p25:%05x:%03x", homeWacn, homeSystem)
                                .equals(row.get("system_key")) && subscriber == null)
                            row.remove("source_radio_id");
                    }
                    result.add(row);
                }
            }
        }
        return result;
    }

    private static void checkDeadline(long deadline) throws SQLException
    {
        if(System.nanoTime() >= deadline) throw new SQLException("Recording identity search exceeded its time limit", "", 9);
    }

    private static String text(Object value) { return value instanceof String string ? string : ""; }
    private static Integer integer(Object value) { return value instanceof Number number ? number.intValue() : null; }
    private record SelectionKey(String systemKey, int id, String identityKey) {}

    private static final class Aggregate
    {
        private final Map<String,Object> mRow;
        private final String mPrefix;
        private final StringBuilder mSearch = new StringBuilder();
        private boolean mFirst = true;
        private String mAlias, mDescription, mGroup, mOta;
        private Object mAliasList;
        private long mCount, mLast;
        private final java.util.Set<Integer> mObservedIds = new java.util.TreeSet<>();

        Aggregate(Map<String,Object> row, String kind)
        {
            mPrefix = "radio".equals(kind) ? "source_alias_" : "target_alias_";
            mRow = new LinkedHashMap<>();
            for(String field: List.of("id", "system_key", "identity_key", "system_name"))
                if(row.get(field) != null || "system_key".equals(field)) mRow.put(field, row.get(field));
            mRow.put("kind", kind);
        }

        void add(Map<String,Object> row)
        {
            String alias = nullableText(row.get(mPrefix + "name"));
            String description = nullableText(row.get(mPrefix + "description"));
            String group = nullableText(row.get(mPrefix + "group"));
            String ota = nullableText(row.get("ota_alias"));
            if(mFirst)
            {
                mAlias = alias; mDescription = description; mGroup = group; mOta = ota;
                mAliasList = row.get("alias_list_id"); mFirst = false;
            }
            else
            {
                if(!Objects.equals(mAlias, alias)) mAlias = null;
                if(!Objects.equals(mDescription, description)) mDescription = null;
                if(!Objects.equals(mGroup, group)) mGroup = null;
                if(!Objects.equals(mOta, ota)) mOta = null;
                if(!Objects.equals(mAliasList, row.get("alias_list_id"))) mAliasList = null;
            }
            for(Object value: List.of(text(alias), text(description), text(group), text(ota),
                String.valueOf(row.get("id")), String.valueOf(row.get("observed_id")),
                text(row.get("system_name")), text(row.get("system_key")),
                text(row.get("identity_key")))) mSearch.append(value).append('\n');
            mCount = Math.max(mCount, ((Number)row.get("call_count")).longValue());
            mObservedIds.add(((Number)row.get("observed_id")).intValue());
            mLast = Math.max(mLast, ((Number)row.get("last_recorded_ms")).longValue());
        }

        boolean matches(String query) { return mSearch.toString().toLowerCase(Locale.ROOT).contains(query); }

        Map<String,Object> toMap()
        {
            mRow.put("label", mOta != null ? mOta : mAlias != null ? mAlias : mRow.get("id").toString());
            if(mAlias != null)
            {
                mRow.put("alias", mAlias);
                if(mAliasList != null) mRow.put("alias_list_id", mAliasList);
            }
            if(mDescription != null) mRow.put("description", mDescription);
            if(mGroup != null) mRow.put("group", mGroup);
            if(mOta != null) mRow.put("ota_alias", mOta);
            if(mObservedIds.size() > 1 || !mObservedIds.contains(((Number)mRow.get("id")).intValue()))
                mRow.put("observed_ids", List.copyOf(mObservedIds));
            mRow.put("call_count", mCount);
            mRow.put("last_recorded_ms", mLast);
            Map<String,Object> selection = new LinkedHashMap<>();
            selection.put("id", mRow.get("id"));
            selection.put("system_key", mRow.get("system_key"));
            if(mRow.containsKey("identity_key")) selection.put("identity_key", mRow.get("identity_key"));
            mRow.put("selection", selection);
            return mRow;
        }

        private static String nullableText(Object value)
        {
            return value instanceof String string && !string.isBlank() ? string : null;
        }
    }
}
