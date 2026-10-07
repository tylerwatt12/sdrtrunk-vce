/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.record.managed;

import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.MaintenanceResult;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.IdentityNameMatch;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.Member;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.RecordingCall;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.SearchFilter;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.SearchPage;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.Site;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.Transcript;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.TranscriptionCounts;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Isolated SQLite implementation for Managed Recordings. This file has its own format version. */
final class ManagedRecordingStore implements AutoCloseable
{
    private static final int FORMAT_VERSION = ManagedRecordingSchema.CURRENT_FORMAT_VERSION;
    private static final int APPLICATION_ID = ManagedRecordingSchema.APPLICATION_ID;
    private static final int MAINTENANCE_BATCH = 256;
    private static final Map<String,String> SCHEMA = ManagedRecordingSchema.DDL;
    private final Path mDatabaseFile;
    private final Path mRoot;
    private final Connection mWriterConnection;

    ManagedRecordingStore(Path databaseFile, Path root) throws SQLException, IOException
    {
        mDatabaseFile = Objects.requireNonNull(databaseFile).toAbsolutePath().normalize();
        mRoot = Objects.requireNonNull(root).toAbsolutePath().normalize();
        Path parent = mDatabaseFile.getParent();
        if(parent == null)
        {
            throw new IOException("Managed recordings database requires a parent directory");
        }
        Files.createDirectories(parent);
        boolean fresh = !Files.exists(mDatabaseFile);
        if(!fresh && Files.size(mDatabaseFile) == 0L)
        {
            throw new IOException("Existing managed recordings catalog is empty or corrupt");
        }
        mWriterConnection = open();
        try
        {
            if(fresh)
            {
                createFresh();
            }
            else
            {
                validateExisting();
            }
            //A refused old or unknown catalog must retain its journal mode for retry with the proper migrator.
            try(Statement statement = mWriterConnection.createStatement())
            {
                statement.execute("PRAGMA journal_mode=WAL");
            }
        }
        catch(SQLException | RuntimeException exception)
        {
            mWriterConnection.close();
            throw exception;
        }
    }

    private Connection open() throws SQLException
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabaseFile);
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA synchronous=NORMAL");
        }
        catch(SQLException exception)
        {
            connection.close();
            throw exception;
        }
        return connection;
    }

    private Connection openReader() throws SQLException
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabaseFile);
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA query_only=ON");
        }
        return connection;
    }

    private void createFresh() throws SQLException
    {
        mWriterConnection.setAutoCommit(false);
        try(Statement statement = mWriterConnection.createStatement())
        {
            for(String ddl : SCHEMA.values())
            {
                statement.execute(ddl);
            }
            statement.execute("INSERT INTO catalog_metadata(id, format_version, call_count, total_bytes) " +
                "VALUES(1, " + FORMAT_VERSION + ", 0, 0)");
            statement.execute("PRAGMA application_id=" + APPLICATION_ID);
            statement.execute("PRAGMA user_version=" + FORMAT_VERSION);
            mWriterConnection.commit();
        }
        catch(SQLException exception)
        {
            mWriterConnection.rollback();
            throw exception;
        }
        finally
        {
            mWriterConnection.setAutoCommit(true);
        }
    }

    private void validateExisting() throws SQLException
    {
        ManagedRecordingSchema.validate(mWriterConnection, FORMAT_VERSION);
    }

    /** Only the dedicated catalog thread calls this method. */
    void insert(ManagedRecordingMetadata item) throws SQLException
    {
        mWriterConnection.setAutoCommit(false);
        try
        {
            Long systemId = dimension(mWriterConnection, "recording_system", "system_key", item.systemKey());
            Long channelId = dimension(mWriterConnection, "recording_channel", "channel_uuid", item.channelId());
            Long winnerSiteId = siteId(mWriterConnection, item.winnerSite());
            String sql = "INSERT INTO recording_call(start_ms,end_ms,duration_ms,relative_path,size_bytes," +
                "system_id,channel_id,alias_list_id,protocol,call_type,voice_type,source_id," +
                "source_home_wacn,source_home_system,source_home_id,target_id,target_home_wacn," +
                "target_home_system,target_home_id,frequency_hz,timeslot,nac,tone_kind,tone,winner_site_id) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            long callId;
            try(PreparedStatement statement = mWriterConnection.prepareStatement(sql))
            {
                int p = 1;
                statement.setLong(p++, item.startMs());
                statement.setLong(p++, item.endMs());
                statement.setLong(p++, item.durationMs());
                statement.setString(p++, item.relativePath());
                statement.setLong(p++, item.sizeBytes());
                set(statement, p++, systemId);
                set(statement, p++, channelId);
                set(statement, p++, item.aliasListId() > 0 ? item.aliasListId() : null);
                statement.setInt(p++, item.protocol());
                statement.setInt(p++, item.callType());
                statement.setInt(p++, item.voiceType());
                set(statement, p++, item.sourceId());
                set(statement, p++, item.sourceHomeWacn());
                set(statement, p++, item.sourceHomeSystem());
                set(statement, p++, item.sourceHomeId());
                set(statement, p++, item.targetId());
                set(statement, p++, item.targetHomeWacn());
                set(statement, p++, item.targetHomeSystem());
                set(statement, p++, item.targetHomeId());
                set(statement, p++, item.frequencyHz());
                set(statement, p++, item.timeslot());
                set(statement, p++, item.nac());
                set(statement, p++, item.toneKind());
                statement.setString(p++, item.tone());
                set(statement, p, winnerSiteId);
                statement.executeUpdate();
            }
            try(Statement statement = mWriterConnection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT last_insert_rowid()"))
            {
                rows.next();
                callId = rows.getLong(1);
            }
            try(PreparedStatement statement = mWriterConnection.prepareStatement(
                "INSERT OR IGNORE INTO recording_call_site(call_id,site_id,start_ms) VALUES(?,?,?)"))
            {
                for(Site site : item.observedSites())
                {
                    Long siteId = siteId(mWriterConnection, site);
                    statement.setLong(1, callId);
                    statement.setLong(2, siteId);
                    statement.setLong(3, item.startMs());
                    statement.addBatch();
                    if(systemId != null)
                    {
                        try(PreparedStatement pair = mWriterConnection.prepareStatement(
                            "INSERT OR IGNORE INTO recording_system_site(system_id,site_id) VALUES(?,?)"))
                        {
                            pair.setLong(1, systemId);
                            pair.setLong(2, siteId);
                            pair.executeUpdate();
                        }
                    }
                }
                statement.executeBatch();
            }
            try(PreparedStatement statement = mWriterConnection.prepareStatement(
                "INSERT OR IGNORE INTO recording_patch_member(call_id,kind,local_id,home_wacn," +
                    "home_system,home_id,start_ms) VALUES(?,?,?,?,?,?,?)"))
            {
                for(Member member : item.patchMembers())
                {
                    statement.setLong(1, callId);
                    statement.setInt(2, "radio".equals(member.kind()) ? 2 : 1);
                    statement.setInt(3, member.id());
                    statement.setInt(4, member.homeWacn() != null ? member.homeWacn() : -1);
                    statement.setInt(5, member.homeSystemId() != null ? member.homeSystemId() : -1);
                    statement.setInt(6, member.homeIdentityId() != null ? member.homeIdentityId() : -1);
                    statement.setLong(7, item.startMs());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            try(PreparedStatement statement = mWriterConnection.prepareStatement(
                "UPDATE catalog_metadata SET call_count=call_count+1,total_bytes=total_bytes+? WHERE id=1"))
            {
                statement.setLong(1, item.sizeBytes());
                statement.executeUpdate();
            }
            mWriterConnection.commit();
        }
        catch(SQLException | RuntimeException exception)
        {
            mWriterConnection.rollback();
            throw exception;
        }
        finally
        {
            mWriterConnection.setAutoCommit(true);
        }
    }

    private static void set(PreparedStatement statement, int position, Number value) throws SQLException
    {
        if(value == null)
        {
            statement.setObject(position, null);
        }
        else
        {
            statement.setLong(position, value.longValue());
        }
    }

    private static Long dimension(Connection connection, String table, String column, String value)
        throws SQLException
    {
        if(value == null || value.isBlank())
        {
            return null;
        }
        try(PreparedStatement insert = connection.prepareStatement(
            "INSERT OR IGNORE INTO " + table + "(" + column + ") VALUES(?)"))
        {
            insert.setString(1, value);
            insert.executeUpdate();
        }
        try(PreparedStatement select = connection.prepareStatement(
            "SELECT id FROM " + table + " WHERE " + column + "=?"))
        {
            select.setString(1, value);
            try(ResultSet rows = select.executeQuery())
            {
                if(rows.next())
                {
                    return rows.getLong(1);
                }
            }
        }
        throw new SQLException("Unable to locate recording dimension");
    }

    private static Long siteId(Connection connection, Site site) throws SQLException
    {
        if(site == null)
        {
            return null;
        }
        try(PreparedStatement insert = connection.prepareStatement(
            "INSERT OR IGNORE INTO recording_site(wacn,system_id,rfss,site_id) VALUES(?,?,?,?)"))
        {
            insert.setInt(1, site.wacn());
            insert.setInt(2, site.systemId());
            insert.setInt(3, site.rfss());
            insert.setInt(4, site.siteId());
            insert.executeUpdate();
        }
        try(PreparedStatement select = connection.prepareStatement(
            "SELECT id FROM recording_site WHERE wacn=? AND system_id=? AND rfss=? AND site_id=?"))
        {
            select.setInt(1, site.wacn());
            select.setInt(2, site.systemId());
            select.setInt(3, site.rfss());
            select.setInt(4, site.siteId());
            try(ResultSet rows = select.executeQuery())
            {
                if(rows.next())
                {
                    return rows.getLong(1);
                }
            }
        }
        throw new SQLException("Unable to locate recording site");
    }

    private static Long findSiteId(Connection connection, Site site) throws SQLException
    {
        try(PreparedStatement select = connection.prepareStatement(
            "SELECT id FROM recording_site WHERE wacn=? AND system_id=? AND rfss=? AND site_id=?"))
        {
            select.setInt(1, site.wacn());
            select.setInt(2, site.systemId());
            select.setInt(3, site.rfss());
            select.setInt(4, site.siteId());
            try(ResultSet rows = select.executeQuery())
            {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    private static final String SELECT_CALL = "SELECT c.id,c.start_ms,c.end_ms,c.duration_ms,c.relative_path," +
        "c.size_bytes,c.alias_list_id,c.protocol,c.call_type,c.voice_type,c.source_id," +
        "c.source_home_wacn,c.source_home_system,c.source_home_id,c.target_id,c.target_home_wacn," +
        "c.target_home_system,c.target_home_id,c.frequency_hz,c.timeslot,c.nac,c.tone_kind,c.tone," +
        "sys.system_key,ch.channel_uuid,ws.wacn AS winner_wacn," +
        "ws.system_id AS winner_system_id,ws.rfss AS winner_rfss,ws.site_id AS winner_site_id " +
        "FROM recording_call c LEFT JOIN recording_system sys ON sys.id=c.system_id " +
        "LEFT JOIN recording_channel ch ON ch.id=c.channel_id " +
        "LEFT JOIN recording_site ws ON ws.id=c.winner_site_id ";

    SearchPage search(SearchFilter filter) throws SQLException
    {
        Objects.requireNonNull(filter);
        try(Connection connection = openReader())
        {
            if(filter.transcript != null || !filter.identityNameMatches.isEmpty())
            {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                org.sqlite.ProgressHandler.setHandler(connection, 10000, new org.sqlite.ProgressHandler()
                {
                    @Override
                    protected int progress()
                    {
                        return System.nanoTime() >= deadline ? 1 : 0;
                    }
                });
            }
            List<Integer> anyIds = new ArrayList<>(filter.anyIdentityIds);
            if(filter.anyIdentityId != null && !anyIds.contains(filter.anyIdentityId))
            {
                anyIds.add(filter.anyIdentityId);
            }
            if(anyIds.size() > 200)
            {
                throw new IllegalArgumentException("Too many recording identity search IDs");
            }
            List<CandidateQuery> candidateQueries = singleIdentityCandidateQueries(filter, anyIds);
            if(!candidateQueries.isEmpty())
            {
                return searchSingleIdentity(connection, filter, candidateQueries);
            }
            boolean anySite = filter.wacn != null || filter.systemId != null ||
                filter.rfss != null || filter.siteId != null;
            boolean exactSite = filter.wacn != null && filter.systemId != null &&
                filter.rfss != null && filter.siteId != null;
            boolean identityDriver = filter.talkgroupId != null || filter.talkgroupMin != null ||
                filter.sourceId != null || filter.sourceMin != null || !anyIds.isEmpty();
            boolean siteDriver = exactSite && !identityDriver;
            boolean indexedBaseFilter = identityDriver || filter.systemKey != null &&
                !filter.systemKey.isBlank() || filter.channelId != null && !filter.channelId.isBlank() ||
                filter.frequencyHz != null;
            Long selectedSiteId = siteDriver ? findSiteId(connection,
                new Site(filter.wacn, filter.systemId, filter.rfss, filter.siteId)) : null;
            if(siteDriver && selectedSiteId == null)
            {
                return new SearchPage(List.of(), null);
            }
            List<String> predicates = new ArrayList<>();
            List<Object> parameters = new ArrayList<>();
            String timeColumn = siteDriver ? "cs.start_ms" : "c.start_ms";
            String idColumn = siteDriver ? "cs.call_id" : "c.id";
            predicates.add(timeColumn + ">=? AND " + timeColumn + "<=?");
            parameters.add(filter.fromMs);
            parameters.add(filter.toMs);
            if(siteDriver)
            {
                predicates.add("cs.site_id=?");
                parameters.add(selectedSiteId);
            }
            addSystemPredicate(predicates, parameters, filter.systemKey, "sys.system_key=?");
            if(filter.channelId != null && !filter.channelId.isBlank())
            {
                predicates.add("ch.channel_uuid=?");
                parameters.add(filter.channelId);
            }
            if(filter.aliasListId != null)
            {
                predicates.add("c.alias_list_id=?");
                parameters.add(filter.aliasListId);
            }
            if(filter.minDurationMs != null)
            {
                predicates.add("c.duration_ms>=?");
                parameters.add(filter.minDurationMs);
            }
            if(filter.maxDurationMs != null)
            {
                predicates.add("c.duration_ms<=?");
                parameters.add(filter.maxDurationMs);
            }
            if(filter.frequencyHz != null)
            {
                predicates.add("c.frequency_hz=?");
                parameters.add(filter.frequencyHz);
            }
            if(filter.sourceId != null)
            {
                predicates.add("c.id IN (SELECT id FROM recording_call INDEXED BY " +
                    "idx_recording_call_source_time WHERE source_id=? AND start_ms BETWEEN ? AND ? " +
                    "UNION SELECT id FROM recording_call INDEXED BY idx_recording_call_target_time " +
                    "WHERE target_id=? AND call_type=3 AND start_ms BETWEEN ? AND ? " +
                    "UNION SELECT pm.call_id FROM recording_patch_member pm INDEXED BY " +
                    "idx_recording_patch_member_lookup WHERE pm.kind=2 AND pm.local_id=? " +
                    "AND pm.start_ms BETWEEN ? AND ?)");
                parameters.add(filter.sourceId);
                parameters.add(filter.fromMs);
                parameters.add(filter.toMs);
                parameters.add(filter.sourceId);
                parameters.add(filter.fromMs);
                parameters.add(filter.toMs);
                parameters.add(filter.sourceId);
                parameters.add(filter.fromMs);
                parameters.add(filter.toMs);
            }
            if(filter.sourceMin != null)
            {
                predicates.add("c.id IN (SELECT id FROM recording_call INDEXED BY " +
                    "idx_recording_call_source_time WHERE source_id BETWEEN ? AND ? " +
                    "AND start_ms BETWEEN ? AND ? UNION SELECT id FROM recording_call " +
                    "INDEXED BY idx_recording_call_target_time WHERE target_id BETWEEN ? AND ? " +
                    "AND call_type=3 AND start_ms BETWEEN ? AND ? UNION SELECT pm.call_id FROM " +
                    "recording_patch_member pm INDEXED BY idx_recording_patch_member_lookup " +
                    "WHERE pm.kind=2 AND pm.local_id BETWEEN ? AND ? " +
                    "AND pm.start_ms BETWEEN ? AND ?)");
                parameters.add(filter.sourceMin);
                parameters.add(filter.sourceMax);
                parameters.add(filter.fromMs);
                parameters.add(filter.toMs);
                parameters.add(filter.sourceMin);
                parameters.add(filter.sourceMax);
                parameters.add(filter.fromMs);
                parameters.add(filter.toMs);
                parameters.add(filter.sourceMin);
                parameters.add(filter.sourceMax);
                parameters.add(filter.fromMs);
                parameters.add(filter.toMs);
            }
            if(filter.talkgroupId != null)
            {
                predicates.add("c.id IN (SELECT id FROM recording_call INDEXED BY " +
                    "idx_recording_call_target_time WHERE target_id=? AND call_type IN(1,2) " +
                    "AND start_ms BETWEEN ? AND ? UNION SELECT pm.call_id FROM " +
                    "recording_patch_member pm INDEXED BY idx_recording_patch_member_lookup " +
                    "WHERE pm.kind=1 AND pm.local_id=? AND pm.start_ms BETWEEN ? AND ?)");
                parameters.add(filter.talkgroupId);
                parameters.add(filter.fromMs);
                parameters.add(filter.toMs);
                parameters.add(filter.talkgroupId);
                parameters.add(filter.fromMs);
                parameters.add(filter.toMs);
            }
            if(filter.talkgroupMin != null)
            {
                predicates.add("c.id IN (SELECT id FROM recording_call INDEXED BY " +
                    "idx_recording_call_target_time WHERE target_id BETWEEN ? AND ? " +
                    "AND call_type IN(1,2) AND start_ms BETWEEN ? AND ? UNION " +
                    "SELECT pm.call_id FROM recording_patch_member pm INDEXED BY " +
                    "idx_recording_patch_member_lookup WHERE pm.kind=1 " +
                    "AND pm.local_id BETWEEN ? AND ? AND pm.start_ms BETWEEN ? AND ?)");
                parameters.add(filter.talkgroupMin);
                parameters.add(filter.talkgroupMax);
                parameters.add(filter.fromMs);
                parameters.add(filter.toMs);
                parameters.add(filter.talkgroupMin);
                parameters.add(filter.talkgroupMax);
                parameters.add(filter.fromMs);
                parameters.add(filter.toMs);
            }
            if(!anyIds.isEmpty())
            {
                String placeholders = String.join(",", java.util.Collections.nCopies(anyIds.size(), "?"));
                predicates.add("c.id IN (SELECT id FROM recording_call INDEXED BY " +
                    "idx_recording_call_source_time WHERE source_id IN(" + placeholders +
                    ") AND start_ms BETWEEN ? AND ? UNION SELECT id FROM recording_call INDEXED BY " +
                    "idx_recording_call_target_time WHERE target_id IN(" + placeholders +
                    ") AND start_ms BETWEEN ? AND ? UNION SELECT pm.call_id FROM " +
                    "recording_patch_member pm INDEXED BY idx_recording_patch_member_lookup " +
                    "WHERE pm.kind IN(1,2) AND pm.local_id IN(" + placeholders +
                    ") AND pm.start_ms BETWEEN ? AND ?)");
                parameters.addAll(anyIds);
                parameters.add(filter.fromMs);
                parameters.add(filter.toMs);
                parameters.addAll(anyIds);
                parameters.add(filter.fromMs);
                parameters.add(filter.toMs);
                parameters.addAll(anyIds);
                parameters.add(filter.fromMs);
                parameters.add(filter.toMs);
            }
            if(anySite && !siteDriver)
            {
                StringBuilder site = indexedBaseFilter ? new StringBuilder(
                    "EXISTS(SELECT 1 FROM recording_call_site cs JOIN recording_site s " +
                        "ON s.id=cs.site_id WHERE cs.call_id=c.id") : new StringBuilder(
                    "c.id IN (SELECT cs.call_id FROM recording_call_site cs JOIN recording_site s " +
                        "ON s.id=cs.site_id WHERE cs.start_ms BETWEEN ? AND ?");
                if(!indexedBaseFilter)
                {
                    parameters.add(filter.fromMs);
                    parameters.add(filter.toMs);
                }
                if(filter.wacn != null) { site.append(" AND s.wacn=?"); parameters.add(filter.wacn); }
                if(filter.systemId != null) { site.append(" AND s.system_id=?"); parameters.add(filter.systemId); }
                if(filter.rfss != null) { site.append(" AND s.rfss=?"); parameters.add(filter.rfss); }
                if(filter.siteId != null) { site.append(" AND s.site_id=?"); parameters.add(filter.siteId); }
                predicates.add(site.append(')').toString());
            }
            if(filter.protocol != null && !filter.protocol.isBlank())
            {
                int code = protocolFilter(filter.protocol);
                predicates.add("c.protocol=?");
                parameters.add(code);
            }
            if(filter.callType != null && !filter.callType.isBlank())
            {
                predicates.add("c.call_type=?");
                parameters.add(callTypeFilter(filter.callType));
            }
            if(filter.voiceType != null && !filter.voiceType.isBlank())
            {
                predicates.add("c.voice_type=?");
                parameters.add(voiceTypeFilter(filter.voiceType));
            }
            addTranscriptPredicate(predicates, parameters, filter.transcript);
            if(filter.cursor != null && !filter.cursor.isBlank())
            {
                long[] cursor = decodeCursor(filter.cursor);
                String compare = filter.sortAscending ? ">" : "<";
                predicates.add("(" + timeColumn + compare + "? OR (" + timeColumn + "=? AND " +
                    idColumn + compare + "?))");
                parameters.add(cursor[0]);
                parameters.add(cursor[0]);
                parameters.add(cursor[1]);
            }
            String order = filter.sortAscending ? "ASC" : "DESC";
            String select = siteDriver ? SELECT_CALL.replace("FROM recording_call c ",
                "FROM recording_call_site cs INDEXED BY idx_recording_call_site_lookup " +
                    "JOIN recording_call c ON c.id=cs.call_id ") : SELECT_CALL;
            String sql = select + " WHERE " + String.join(" AND ", predicates) +
                " ORDER BY " + timeColumn + " " + order + "," + idColumn + " " + order + " LIMIT ?";
            List<RecordingCall> calls = new ArrayList<>(filter.limit + 1);
            try(PreparedStatement statement = connection.prepareStatement(sql))
            {
                int p = 1;
                for(Object value : parameters)
                {
                    statement.setObject(p++, value);
                }
                statement.setInt(p, filter.limit + 1);
                try(ResultSet rows = statement.executeQuery())
                {
                    while(rows.next())
                    {
                        calls.add(mapCall(connection, rows));
                    }
                }
            }
            boolean more = calls.size() > filter.limit;
            if(more)
            {
                calls.remove(calls.size() - 1);
            }
            String next = more && !calls.isEmpty() ? encodeCursor(calls.getLast()) : null;
            return new SearchPage(List.copyOf(calls), next);
        }
    }

    /**
     * A single exact identity has a few independently ordered index streams. Reading only the first page from each
     * stream gives the exact first page of their union, even when a call appears in more than one stream. This avoids
     * materializing every matching call ID in a multi-year interval before applying the page limit.
     */
    private SearchPage searchSingleIdentity(Connection connection, SearchFilter filter,
                                            List<CandidateQuery> branches) throws SQLException
    {
        try(Statement transaction = connection.createStatement())
        {
            transaction.execute("BEGIN");
            try
            {
                Map<Long,CallKey> distinct = new HashMap<>();
                for(CandidateQuery branch : branches)
                {
                    try(PreparedStatement statement = connection.prepareStatement(branch.sql()))
                    {
                        int p = 1;
                        for(Object value : branch.parameters())
                        {
                            statement.setObject(p++, value);
                        }
                        try(ResultSet rows = statement.executeQuery())
                        {
                            while(rows.next())
                            {
                                long id = rows.getLong(1);
                                distinct.putIfAbsent(id, new CallKey(rows.getLong(2), id));
                            }
                        }
                    }
                }
                Comparator<CallKey> order = Comparator.comparingLong(CallKey::startMs)
                    .thenComparingLong(CallKey::id);
                if(!filter.sortAscending)
                {
                    order = order.reversed();
                }
                List<CallKey> keys = distinct.values().stream().sorted(order).toList();
                boolean more = keys.size() > filter.limit;
                List<CallKey> pageKeys = keys.subList(0, Math.min(keys.size(), filter.limit));
                Map<Long,RecordingCall> byId = new HashMap<>();
                if(!pageKeys.isEmpty())
                {
                    String placeholders = String.join(",",
                        java.util.Collections.nCopies(pageKeys.size(), "?"));
                    try(PreparedStatement statement = connection.prepareStatement(
                        SELECT_CALL + " WHERE c.id IN (" + placeholders + ")"))
                    {
                        int p = 1;
                        for(CallKey key : pageKeys)
                        {
                            statement.setLong(p++, key.id());
                        }
                        try(ResultSet rows = statement.executeQuery())
                        {
                            while(rows.next())
                            {
                                RecordingCall call = mapCall(connection, rows);
                                byId.put(call.id(), call);
                            }
                        }
                    }
                }
                List<RecordingCall> calls = new ArrayList<>(pageKeys.size());
                for(CallKey key : pageKeys)
                {
                    RecordingCall call = byId.get(key.id());
                    if(call != null)
                    {
                        calls.add(call);
                    }
                }
                transaction.execute("COMMIT");
                String next = more && !calls.isEmpty() ? encodeCursor(calls.getLast()) : null;
                return new SearchPage(List.copyOf(calls), next);
            }
            catch(SQLException | RuntimeException exception)
            {
                transaction.execute("ROLLBACK");
                throw exception;
            }
        }
    }

    /** Package visibility permits the plan test to inspect the actual bounded search statements. */
    List<CandidateQuery> singleIdentityCandidateQueries(SearchFilter filter)
    {
        List<Integer> anyIds = new ArrayList<>(filter.anyIdentityIds);
        if(filter.anyIdentityId != null && !anyIds.contains(filter.anyIdentityId))
        {
            anyIds.add(filter.anyIdentityId);
        }
        return singleIdentityCandidateQueries(filter, anyIds);
    }

    private List<CandidateQuery> singleIdentityCandidateQueries(SearchFilter filter, List<Integer> anyIds)
    {
        if(!filter.identityNameMatches.isEmpty())
        {
            List<CandidateQuery> queries = new ArrayList<>();
            for(IdentityNameMatch match: filter.identityNameMatches)
            {
                if(match.systemKey() == null)
                {
                    queries.add(candidateQuery(filter, "recording_call c INDEXED BY idx_recording_call_source_time",
                        "c.source_id=?", List.of(match.identityId()), "c.start_ms", "c.id"));
                    queries.add(candidateQuery(filter, "recording_call c INDEXED BY idx_recording_call_target_time",
                        "c.target_id=?", List.of(match.identityId()), "c.start_ms", "c.id"));
                    queries.add(patchCandidateQuery(filter, match.identityId(), 1));
                    queries.add(patchCandidateQuery(filter, match.identityId(), 2));
                }
                else
                {
                    queries.addAll(nameIdentityCandidateQueries(filter, match));
                }
            }
            return List.copyOf(queries);
        }
        boolean talkgroup = filter.talkgroupId != null;
        boolean radio = filter.sourceId != null;
        boolean any = !anyIds.isEmpty();
        if((talkgroup ? 1 : 0) + (radio ? 1 : 0) + (any ? 1 : 0) != 1 ||
            filter.talkgroupMin != null || filter.sourceMin != null || anyIds.size() > 1)
        {
            return List.of();
        }
        int id = talkgroup ? filter.talkgroupId : radio ? filter.sourceId : anyIds.getFirst();
        List<CandidateQuery> queries = new ArrayList<>(4);
        if(!talkgroup)
        {
            queries.add(candidateQuery(filter, "recording_call c INDEXED BY idx_recording_call_source_time",
                "c.source_id=?", List.of(id), "c.start_ms", "c.id"));
        }
        queries.add(candidateQuery(filter, "recording_call c INDEXED BY idx_recording_call_target_time",
            talkgroup ? "c.target_id=? AND c.call_type IN(1,2)" : radio ?
                "c.target_id=? AND c.call_type=3" : "c.target_id=?",
            List.of(id), "c.start_ms", "c.id"));
        if(!radio)
        {
            queries.add(patchCandidateQuery(filter, id, 1));
        }
        if(!talkgroup)
        {
            queries.add(patchCandidateQuery(filter, id, 2));
        }
        return List.copyOf(queries);
    }

    private List<CandidateQuery> nameIdentityCandidateQueries(SearchFilter filter, IdentityNameMatch match)
    {
        List<Object> values = new ArrayList<>();
        String source = nameRadioValue(match, "c.source_id", "c.source_home_wacn",
            "c.source_home_system", "c.source_home_id", values);
        String target = nameRadioValue(match, "c.target_id", "c.target_home_wacn",
            "c.target_home_system", "c.target_home_id", values);
        String member = nameRadioValue(match, "member.local_id", "nullif(member.home_wacn,-1)",
            "nullif(member.home_system,-1)", "nullif(member.home_id,-1)", values);
        String radio = "(" + source + " OR (c.call_type=3 AND " + target + ") OR " +
            "EXISTS(SELECT 1 FROM recording_patch_member member WHERE member.call_id=c.id " +
            "AND member.kind=2 AND " + member + "))";
        List<CandidateQuery> queries = new ArrayList<>(2);
        List<Object> parameters = new ArrayList<>();
        parameters.add(match.systemKey());
        parameters.addAll(values);
        boolean p25 = RadioSystemKey.isP25Native(match.systemKey());
        queries.add(candidateQuery(filter, "recording_call c INDEXED BY idx_recording_call_system_time",
            "c.system_id=(SELECT id FROM recording_system WHERE system_key=?) AND " +
                (p25 ? "c.protocol IN(1,2) AND " : "") + radio, parameters, "c.start_ms", "c.id"));
        if(p25)
        {
            //The system/time index excludes NULL; legacy captured-site proofs use the bounded ordered time index.
            List<String> conditions = new ArrayList<>();
            parameters = new ArrayList<>();
            conditions.add("c.system_id IS NULL");
            addSystemPredicate(conditions, parameters, match.systemKey(),
                "c.system_id=(SELECT id FROM recording_system WHERE system_key=?)");
            conditions.add(radio);
            parameters.addAll(values);
            queries.add(candidateQuery(filter, "recording_call c INDEXED BY idx_recording_call_time",
                String.join(" AND ", conditions), parameters, "c.start_ms", "c.id"));
        }
        return List.copyOf(queries);
    }

    /** Home metadata represents the permanent subscriber; its local ID may be a different Working ID. */
    private static String nameRadioValue(IdentityNameMatch match, String localId, String homeWacn,
                                         String homeSystem, String homeId, List<Object> parameters)
    {
        List<String> choices = new ArrayList<>();
        if(match.homeWacn() >= 0)
        {
            choices.add("(" + homeWacn + "=? AND " + homeSystem + "=? AND coalesce(" +
                homeId + "," + localId + ")=?)");
            parameters.add(match.homeWacn());
            parameters.add(match.homeSystemId());
            parameters.add(match.identityId());
        }
        if(match.includeNullHome())
        {
            choices.add("(" + homeWacn + " IS NULL AND " + homeSystem + " IS NULL AND " +
                homeId + " IS NULL AND " + localId + "=?)");
            parameters.add(match.identityId());
        }
        return choices.isEmpty() ? "0" : "(" + String.join(" OR ", choices) + ")";
    }

    private CandidateQuery patchCandidateQuery(SearchFilter filter, int id, int kind)
    {
        String patchPredicate = "pm.kind=? AND pm.local_id=? AND pm.start_ms BETWEEN ? AND ? " +
            "AND NOT EXISTS(SELECT 1 FROM recording_patch_member earlier WHERE " +
            "earlier.call_id=pm.call_id AND earlier.kind=pm.kind AND earlier.local_id=pm.local_id " +
            "AND (earlier.home_wacn,earlier.home_system,earlier.home_id)<" +
            "(pm.home_wacn,pm.home_system,pm.home_id))";
        return candidateQuery(filter, "recording_patch_member pm INDEXED BY " +
            "idx_recording_patch_member_lookup JOIN recording_call c ON c.id=pm.call_id",
            patchPredicate, List.of(kind, id, filter.fromMs, filter.toMs), "pm.start_ms", "pm.call_id");
    }

    private CandidateQuery candidateQuery(SearchFilter filter, String from, String identityPredicate,
                                          List<Object> identityParameters, String orderTime, String orderId)
    {
        List<String> conditions = new ArrayList<>();
        List<Object> parameters = new ArrayList<>(identityParameters);
        conditions.add(identityPredicate);
        conditions.add("c.start_ms BETWEEN ? AND ?");
        parameters.add(filter.fromMs);
        parameters.add(filter.toMs);
        addSystemPredicate(conditions, parameters, filter.systemKey,
            "c.system_id=(SELECT id FROM recording_system WHERE system_key=?)");
        if(filter.channelId != null && !filter.channelId.isBlank())
        {
            conditions.add("c.channel_id=(SELECT id FROM recording_channel WHERE channel_uuid=?)");
            parameters.add(filter.channelId);
        }
        if(filter.aliasListId != null)
        {
            conditions.add("c.alias_list_id=?");
            parameters.add(filter.aliasListId);
        }
        if(filter.minDurationMs != null)
        {
            conditions.add("c.duration_ms>=?");
            parameters.add(filter.minDurationMs);
        }
        if(filter.maxDurationMs != null)
        {
            conditions.add("c.duration_ms<=?");
            parameters.add(filter.maxDurationMs);
        }
        if(filter.frequencyHz != null)
        {
            conditions.add("c.frequency_hz=?");
            parameters.add(filter.frequencyHz);
        }
        if(filter.wacn != null || filter.systemId != null || filter.rfss != null || filter.siteId != null)
        {
            StringBuilder site = new StringBuilder("EXISTS(SELECT 1 FROM recording_call_site cs " +
                "JOIN recording_site s ON s.id=cs.site_id WHERE cs.call_id=c.id");
            if(filter.wacn != null) { site.append(" AND s.wacn=?"); parameters.add(filter.wacn); }
            if(filter.systemId != null) { site.append(" AND s.system_id=?"); parameters.add(filter.systemId); }
            if(filter.rfss != null) { site.append(" AND s.rfss=?"); parameters.add(filter.rfss); }
            if(filter.siteId != null) { site.append(" AND s.site_id=?"); parameters.add(filter.siteId); }
            conditions.add(site.append(')').toString());
        }
        if(filter.protocol != null && !filter.protocol.isBlank())
        {
            conditions.add("c.protocol=?");
            parameters.add(protocolFilter(filter.protocol));
        }
        if(filter.callType != null && !filter.callType.isBlank())
        {
            conditions.add("c.call_type=?");
            parameters.add(callTypeFilter(filter.callType));
        }
        if(filter.voiceType != null && !filter.voiceType.isBlank())
        {
            conditions.add("c.voice_type=?");
            parameters.add(voiceTypeFilter(filter.voiceType));
        }
        addTranscriptPredicate(conditions, parameters, filter.transcript);
        if(filter.cursor != null && !filter.cursor.isBlank())
        {
            long[] cursor = decodeCursor(filter.cursor);
            String compare = filter.sortAscending ? ">" : "<";
            conditions.add("(c.start_ms" + compare + "? OR (c.start_ms=? AND c.id" + compare + "?))");
            parameters.add(cursor[0]);
            parameters.add(cursor[0]);
            parameters.add(cursor[1]);
        }
        String order = filter.sortAscending ? "ASC" : "DESC";
        String sql = "SELECT c.id,c.start_ms FROM " + from + " WHERE " +
            String.join(" AND ", conditions) + " ORDER BY " + orderTime + " " + order + "," +
            orderId + " " + order + " LIMIT ?";
        parameters.add(filter.limit + 1);
        return new CandidateQuery(sql, List.copyOf(parameters));
    }

    private static void addTranscriptPredicate(List<String> conditions, List<Object> parameters, String text)
    {
        if(text != null)
        {
            // Probe only transcripts belonging to calls in the existing indexed search scope.
            // instr treats percent and underscore as literal text, rather than SQL wildcards.
            conditions.add("EXISTS(SELECT 1 FROM recording_transcript t WHERE t.call_id=c.id " +
                "AND instr(lower(t.text),lower(?))>0)");
            parameters.add(text);
        }
    }

    private static void addSystemPredicate(List<String> conditions, List<Object> parameters,
                                           String systemKey, String exactPredicate)
    {
        if(systemKey == null || systemKey.isBlank())
        {
            return;
        }

        parameters.add(systemKey);
        if(!RadioSystemKey.isP25Native(systemKey))
        {
            conditions.add(exactPredicate);
            return;
        }

        int wacn = Integer.parseInt(systemKey.substring(4, 9), 16);
        int systemId = Integer.parseInt(systemKey.substring(10), 16);
        // Older P25 calls have a verified site but no recording_system row. A stored system remains authoritative;
        // if the winner is absent, accept observed sites only when they agree on one radio system.
        conditions.add("(" + exactPredicate + " OR (c.system_id IS NULL AND c.protocol IN (1,2) AND (" +
            "EXISTS(SELECT 1 FROM recording_site winner WHERE winner.id=c.winner_site_id " +
            "AND winner.wacn=? AND winner.system_id=?) OR (c.winner_site_id IS NULL AND " +
            "EXISTS(SELECT 1 FROM recording_call_site cs JOIN recording_site observed " +
            "ON observed.id=cs.site_id WHERE cs.call_id=c.id AND observed.wacn=? " +
            "AND observed.system_id=?) AND NOT EXISTS(SELECT 1 FROM recording_call_site cs " +
            "JOIN recording_site observed ON observed.id=cs.site_id WHERE cs.call_id=c.id " +
            "AND (observed.wacn<>? OR observed.system_id<>?))))))");
        parameters.add(wacn);
        parameters.add(systemId);
        parameters.add(wacn);
        parameters.add(systemId);
        parameters.add(wacn);
        parameters.add(systemId);
    }

    private static boolean isP25Protocol(int protocol)
    {
        return protocol == 1 || protocol == 2;
    }

    record CandidateQuery(String sql, List<Object> parameters) {}

    private record CallKey(long startMs, long id) {}

    RecordingCall find(long id) throws SQLException
    {
        if(id <= 0)
        {
            return null;
        }
        try(Connection connection = openReader();
            PreparedStatement statement = connection.prepareStatement(SELECT_CALL + "WHERE c.id=?"))
        {
            statement.setLong(1, id);
            try(ResultSet rows = statement.executeQuery())
            {
                return rows.next() ? mapCall(connection, rows) : null;
            }
        }
    }

    private static RecordingCall mapCall(Connection connection, ResultSet row) throws SQLException
    {
        long id = row.getLong("id");
        Site winner = site(row, "winner_");
        List<Site> also = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT s.wacn,s.system_id,s.rfss,s.site_id FROM recording_call_site cs " +
                "JOIN recording_site s ON s.id=cs.site_id WHERE cs.call_id=? ORDER BY s.id"))
        {
            statement.setLong(1, id);
            try(ResultSet sites = statement.executeQuery())
            {
                while(sites.next())
                {
                    Site observed = new Site(sites.getInt(1), sites.getInt(2), sites.getInt(3),
                        sites.getInt(4));
                    if(!observed.equals(winner))
                    {
                        also.add(observed);
                    }
                }
            }
        }
        List<Member> members = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT kind,local_id,home_wacn,home_system,home_id FROM recording_patch_member " +
                "WHERE call_id=? ORDER BY kind,local_id"))
        {
            statement.setLong(1, id);
            try(ResultSet rows = statement.executeQuery())
            {
                while(rows.next())
                {
                    members.add(new Member(rows.getInt(1) == 2 ? "radio" : "talkgroup", rows.getInt(2),
                        home(rows.getInt(3)), home(rows.getInt(4)), home(rows.getInt(5))));
                }
            }
        }
        int protocol = row.getInt("protocol");
        String systemKey = row.getString("system_key");
        if(systemKey == null && isP25Protocol(protocol))
        {
            Site owner = winner;
            if(owner == null && !also.isEmpty())
            {
                Site first = also.getFirst();
                if(also.stream().allMatch(site -> site.wacn() == first.wacn() &&
                    site.systemId() == first.systemId()))
                {
                    owner = first;
                }
            }
            if(owner != null)
            {
                systemKey = RadioSystemKey.p25(owner.wacn(), owner.systemId());
            }
        }
        return new RecordingCall(id, row.getLong("start_ms"), row.getLong("end_ms"),
            row.getLong("duration_ms"), row.getString("relative_path"), row.getLong("size_bytes"),
            systemKey, row.getString("channel_uuid"),
            nullableLong(row, "alias_list_id") != null ? row.getLong("alias_list_id") : 0L,
            ManagedRecordingCatalog.protocolName(protocol),
            callTypeName(row.getInt("call_type")), voiceTypeName(row.getInt("voice_type")),
            nullableInt(row, "source_id"), nullableInt(row, "source_home_wacn"),
            nullableInt(row, "source_home_system"), nullableInt(row, "source_home_id"),
            nullableInt(row, "target_id"), nullableInt(row, "target_home_wacn"),
            nullableInt(row, "target_home_system"), nullableInt(row, "target_home_id"),
            nullableLong(row, "frequency_hz"), nullableInt(row, "timeslot"), nullableInt(row, "nac"),
            nullableInt(row, "tone_kind"), row.getString("tone"), winner, List.copyOf(also),
            List.copyOf(members));
    }

    private static Site site(ResultSet rows, String prefix) throws SQLException
    {
        Integer wacn = nullableInt(rows, prefix + "wacn");
        return wacn != null ? new Site(wacn, rows.getInt(prefix + "system_id"),
            rows.getInt(prefix + "rfss"), rows.getInt(prefix + "site_id")) : null;
    }

    private static Integer nullableInt(ResultSet rows, String name) throws SQLException
    {
        int value = rows.getInt(name);
        return rows.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rows, int column) throws SQLException
    {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }

    private static Integer home(int value)
    {
        return value >= 0 ? value : null;
    }

    private static Long nullableLong(ResultSet rows, String name) throws SQLException
    {
        long value = rows.getLong(name);
        return rows.wasNull() ? null : value;
    }

    private static int protocolFilter(String protocol)
    {
        try
        {
            return ManagedRecordingCatalog.protocolCode(
                io.github.dsheirer.protocol.Protocol.valueOf(protocol.toUpperCase(java.util.Locale.ROOT)));
        }
        catch(IllegalArgumentException exception)
        {
            throw new IllegalArgumentException("Unknown recording protocol");
        }
    }

    private static int callTypeFilter(String type)
    {
        return switch(type.toLowerCase(java.util.Locale.ROOT))
        {
            case "conventional" -> ManagedRecordingCatalog.CALL_CONVENTIONAL;
            case "group" -> ManagedRecordingCatalog.CALL_GROUP;
            case "patch" -> ManagedRecordingCatalog.CALL_PATCH;
            case "direct" -> ManagedRecordingCatalog.CALL_DIRECT;
            default -> throw new IllegalArgumentException("Unknown recording call type");
        };
    }

    private static int voiceTypeFilter(String type)
    {
        return switch(type.toLowerCase(java.util.Locale.ROOT))
        {
            case "unknown" -> ManagedRecordingCatalog.VOICE_UNKNOWN;
            case "clear" -> ManagedRecordingCatalog.VOICE_CLEAR;
            case "encrypted" -> ManagedRecordingCatalog.VOICE_ENCRYPTED;
            default -> throw new IllegalArgumentException("Unknown recording voice type");
        };
    }

    private static String callTypeName(int code)
    {
        return switch(code)
        {
            case ManagedRecordingCatalog.CALL_GROUP -> "GROUP";
            case ManagedRecordingCatalog.CALL_PATCH -> "PATCH";
            case ManagedRecordingCatalog.CALL_DIRECT -> "DIRECT";
            default -> "CONVENTIONAL";
        };
    }

    private static String voiceTypeName(int code)
    {
        return switch(code)
        {
            case ManagedRecordingCatalog.VOICE_CLEAR -> "CLEAR";
            case ManagedRecordingCatalog.VOICE_ENCRYPTED -> "ENCRYPTED";
            default -> "UNKNOWN";
        };
    }

    private static String encodeCursor(RecordingCall call)
    {
        String value = call.startMs() + ":" + call.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static long[] decodeCursor(String cursor)
    {
        if(cursor.length() > 64)
        {
            throw new IllegalArgumentException("Invalid recording cursor");
        }
        try
        {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            String[] parts = value.split(":", -1);
            if(parts.length != 2)
            {
                throw new IllegalArgumentException();
            }
            long timestamp = Long.parseLong(parts[0]);
            long id = Long.parseLong(parts[1]);
            if(timestamp < 0 || id <= 0)
            {
                throw new IllegalArgumentException();
            }
            return new long[]{timestamp, id};
        }
        catch(IllegalArgumentException exception)
        {
            throw new IllegalArgumentException("Invalid recording cursor");
        }
    }

    StoredStats stats() throws SQLException
    {
        try(Connection connection = openReader();
            Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(
                "SELECT call_count,total_bytes FROM catalog_metadata WHERE id=1"))
        {
            if(!rows.next())
            {
                throw new SQLException("Managed recordings catalog metadata is missing");
            }
            return new StoredStats(rows.getLong(1), rows.getLong(2));
        }
    }

    long nextPendingTranscription(long afterId, long minimumDurationMs) throws SQLException
    {
        try(Connection connection = openReader();
            PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM recording_call INDEXED BY idx_recording_call_transcription_pending " +
                    "WHERE transcription_status='pending' AND id>? AND duration_ms>=? " +
                    "ORDER BY id LIMIT 1"))
        {
            statement.setLong(1, Math.max(0L, afterId));
            statement.setLong(2, Math.max(0L, minimumDurationMs));
            try(ResultSet rows = statement.executeQuery())
            {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }
    }

    Transcript transcription(long id) throws SQLException
    {
        if(id <= 0)
        {
            return null;
        }
        try(Connection connection = openReader();
            PreparedStatement statement = connection.prepareStatement(
                "SELECT c.transcription_status,t.text,t.stored_at_ms FROM recording_call c " +
                    "LEFT JOIN recording_transcript t ON t.call_id=c.id WHERE c.id=?"))
        {
            statement.setLong(1, id);
            try(ResultSet rows = statement.executeQuery())
            {
                if(!rows.next())
                {
                    return null;
                }
                long storedAtMs = rows.getLong(3);
                return new Transcript(rows.getString(1), rows.getString(2),
                    rows.wasNull() ? null : storedAtMs);
            }
        }
    }

    Map<Long,String> transcriptExcerpts(List<Long> ids) throws SQLException
    {
        if(ids.isEmpty()) return Map.of();
        if(ids.size() > ManagedRecordingCatalog.MAX_PAGE_SIZE)
        {
            throw new IllegalArgumentException("Too many transcript excerpts requested");
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Map<Long,String> excerpts = new HashMap<>();
        try(Connection connection = openReader();
            PreparedStatement statement = connection.prepareStatement(
                "SELECT call_id,substr(text,1,300) FROM recording_transcript WHERE call_id IN (" + placeholders + ")"))
        {
            for(int index = 0; index < ids.size(); index++) statement.setLong(index + 1, ids.get(index));
            try(ResultSet rows = statement.executeQuery())
            {
                while(rows.next()) excerpts.put(rows.getLong(1), rows.getString(2));
            }
        }
        return Map.copyOf(excerpts);
    }

    TranscriptionCounts transcriptionCounts(long minimumDurationMs) throws SQLException
    {
        try(Connection connection = openReader();
            PreparedStatement statement = connection.prepareStatement(
                "SELECT coalesce(sum(CASE WHEN transcription_status='pending' THEN 1 ELSE 0 END),0)," +
                    "coalesce(sum(CASE WHEN transcription_status='complete' THEN 1 ELSE 0 END),0)," +
                    "coalesce(sum(CASE WHEN transcription_status='failed' THEN 1 ELSE 0 END),0) " +
                    "FROM recording_call WHERE duration_ms>=?"))
        {
            statement.setLong(1, Math.max(0L, minimumDurationMs));
            try(ResultSet rows = statement.executeQuery())
            {
                rows.next();
                return new TranscriptionCounts(rows.getLong(1), rows.getLong(2), rows.getLong(3));
            }
        }
    }

    /** Only the dedicated catalog writer invokes this method. The transcript and status commit together. */
    boolean storeTranscript(long id, String text, long storedAtMs) throws SQLException
    {
        // An empty response is a valid no-speech result and must not be retried indefinitely.
        if(id <= 0 || text == null || storedAtMs < 0)
        {
            return false;
        }
        mWriterConnection.setAutoCommit(false);
        try
        {
            int updated;
            try(PreparedStatement statement = mWriterConnection.prepareStatement(
                "UPDATE recording_call SET transcription_status='complete' " +
                    "WHERE id=? AND transcription_status='pending'"))
            {
                statement.setLong(1, id);
                updated = statement.executeUpdate();
            }
            if(updated == 0)
            {
                mWriterConnection.rollback();
                return false;
            }
            try(PreparedStatement statement = mWriterConnection.prepareStatement(
                "INSERT INTO recording_transcript(call_id,text,stored_at_ms) VALUES(?,?,?)"))
            {
                statement.setLong(1, id);
                statement.setString(2, text);
                statement.setLong(3, storedAtMs);
                statement.executeUpdate();
            }
            mWriterConnection.commit();
            return true;
        }
        catch(SQLException exception)
        {
            mWriterConnection.rollback();
            throw exception;
        }
        finally
        {
            mWriterConnection.setAutoCommit(true);
        }
    }

    /** Only the dedicated catalog writer invokes this method. */
    boolean failTranscription(long id) throws SQLException
    {
        if(id <= 0)
        {
            return false;
        }
        try(PreparedStatement statement = mWriterConnection.prepareStatement(
            "UPDATE recording_call SET transcription_status='failed' " +
                "WHERE id=? AND transcription_status='pending'"))
        {
            statement.setLong(1, id);
            return statement.executeUpdate() > 0;
        }
    }

    /** Admin-only retry uses its own short connection, never a receiver or catalog callback. */
    boolean retryTranscription(long id) throws SQLException
    {
        if(id <= 0)
        {
            return false;
        }
        try(Connection connection = open();
            PreparedStatement statement = connection.prepareStatement(
                "UPDATE recording_call SET transcription_status='pending' " +
                    "WHERE id=? AND transcription_status='failed'"))
        {
            statement.setLong(1, id);
            return statement.executeUpdate() > 0;
        }
    }

    boolean delete(long id) throws SQLException
    {
        if(id <= 0)
        {
            return false;
        }
        try(Connection connection = open(); Statement control = connection.createStatement())
        {
            control.execute("BEGIN IMMEDIATE");
            try
            {
                Long size = storedSize(connection, id);
                if(size == null)
                {
                    control.execute("ROLLBACK");
                    return false;
                }
                try(PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM recording_call WHERE id=?"))
                {
                    statement.setLong(1, id);
                    statement.executeUpdate();
                }
                adjustCounts(connection, -1L, -size);
                control.execute("COMMIT");
                return true;
            }
            catch(SQLException exception)
            {
                control.execute("ROLLBACK");
                throw exception;
            }
        }
    }

    private static Long storedSize(Connection connection, long id) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT size_bytes FROM recording_call WHERE id=?"))
        {
            statement.setLong(1, id);
            try(ResultSet rows = statement.executeQuery())
            {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    private static void adjustCounts(Connection connection, long countChange, long bytesChange)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "UPDATE catalog_metadata SET call_count=max(0,call_count+?)," +
                "total_bytes=max(0,total_bytes+?) WHERE id=1"))
        {
            statement.setLong(1, countChange);
            statement.setLong(2, bytesChange);
            if(statement.executeUpdate() != 1)
            {
                throw new SQLException("Unable to update managed recordings counters");
            }
        }
    }

    MaintenanceResult recount(boolean rebuildIndexes) throws SQLException, IOException
    {
        long inspected = 0L;
        long removed = 0L;
        long corrected = 0L;
        long afterId = 0L;
        while(true)
        {
            checkInterrupted();
            List<FileEntry> batch = new ArrayList<>(MAINTENANCE_BATCH);
            try(Connection connection = openReader();
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT id,relative_path,size_bytes FROM recording_call WHERE id>? " +
                        "ORDER BY id LIMIT ?"))
            {
                statement.setLong(1, afterId);
                statement.setInt(2, MAINTENANCE_BATCH);
                try(ResultSet rows = statement.executeQuery())
                {
                    while(rows.next())
                    {
                        batch.add(new FileEntry(rows.getLong(1), rows.getString(2), rows.getLong(3)));
                    }
                }
            }
            if(batch.isEmpty())
            {
                break;
            }
            List<FileEntry> invalid = new ArrayList<>();
            for(FileEntry entry : batch)
            {
                if(Thread.currentThread().isInterrupted())
                {
                    deleteBatch(invalid);
                    checkInterrupted();
                }
                afterId = entry.id();
                inspected++;
                Path file = safePath(entry.relativePath());
                boolean valid;
                try
                {
                    valid = file != null && validMp3(file);
                }
                catch(java.nio.file.NoSuchFileException exception)
                {
                    valid = false;
                }
                if(!valid)
                {
                    if(file != null && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    {
                        Files.deleteIfExists(file);
                    }
                    invalid.add(entry);
                    continue;
                }
                long actual;
                try
                {
                    actual = Files.size(file);
                }
                catch(java.nio.file.NoSuchFileException exception)
                {
                    invalid.add(entry);
                    continue;
                }
                if(actual != entry.sizeBytes() && updateSize(entry.id(), actual))
                {
                    corrected++;
                }
            }
            removed += deleteBatch(invalid);
        }
        recalculateTotals();
        if(rebuildIndexes)
        {
            checkInterrupted();
            try(Connection connection = open(); Statement statement = connection.createStatement())
            {
                statement.execute("REINDEX");
                statement.execute("PRAGMA optimize");
            }
        }
        StoredStats totals = stats();
        return new MaintenanceResult(inspected, removed, corrected, totals.callCount(), totals.totalBytes());
    }

    MaintenanceResult pruneOlderThan(long cutoffEpochMs) throws SQLException, IOException
    {
        if(cutoffEpochMs <= 0L)
        {
            throw new IllegalArgumentException("Age cutoff must be positive");
        }
        long inspected = 0L;
        long removed = 0L;
        long afterStart = Long.MIN_VALUE;
        long afterId = 0L;
        while(true)
        {
            checkInterrupted();
            List<FileEntry> batch = new ArrayList<>(MAINTENANCE_BATCH);
            try(Connection connection = openReader();
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT id,relative_path,size_bytes,start_ms FROM recording_call " +
                        "WHERE start_ms<? AND (start_ms>? OR (start_ms=? AND id>?)) " +
                        "ORDER BY start_ms,id LIMIT ?"))
            {
                statement.setLong(1, cutoffEpochMs);
                statement.setLong(2, afterStart);
                statement.setLong(3, afterStart);
                statement.setLong(4, afterId);
                statement.setInt(5, MAINTENANCE_BATCH);
                try(ResultSet rows = statement.executeQuery())
                {
                    while(rows.next())
                    {
                        batch.add(new FileEntry(rows.getLong(1), rows.getString(2), rows.getLong(3),
                            rows.getLong(4)));
                    }
                }
            }
            if(batch.isEmpty())
            {
                break;
            }
            for(FileEntry entry : batch)
            {
                afterStart = entry.startMs();
                afterId = entry.id();
                inspected++;
                Path file = safePath(entry.relativePath());
                if(file != null && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                {
                    Files.deleteIfExists(file);
                }
            }
            removed += deleteBatch(batch);
        }
        StoredStats totals = stats();
        return new MaintenanceResult(inspected, removed, 0L, totals.callCount(), totals.totalBytes());
    }

    private long deleteBatch(List<FileEntry> entries) throws SQLException
    {
        if(entries.isEmpty())
        {
            return 0L;
        }
        try(Connection connection = open(); Statement control = connection.createStatement())
        {
            control.execute("BEGIN IMMEDIATE");
            try(PreparedStatement remove = connection.prepareStatement(
                "DELETE FROM recording_call WHERE id=?"))
            {
                long count = 0L;
                long bytes = 0L;
                for(FileEntry entry : entries)
                {
                    Long size = storedSize(connection, entry.id());
                    if(size != null)
                    {
                        remove.setLong(1, entry.id());
                        if(remove.executeUpdate() == 1)
                        {
                            count++;
                            bytes += size;
                        }
                    }
                }
                if(count > 0)
                {
                    adjustCounts(connection, -count, -bytes);
                }
                control.execute("COMMIT");
                return count;
            }
            catch(SQLException exception)
            {
                control.execute("ROLLBACK");
                throw exception;
            }
        }
    }

    private static void checkInterrupted() throws InterruptedIOException
    {
        if(Thread.currentThread().isInterrupted())
        {
            throw new InterruptedIOException("Managed recordings maintenance was interrupted");
        }
    }

    /**
     * Reads the full aggregate under a stable WAL snapshot and applies only its discrepancy. Concurrent call inserts
     * update rows and counters atomically, so their changes cancel out of this discrepancy without holding the writer
     * lock during the potentially expensive scan.
     */
    private void recalculateTotals() throws SQLException
    {
        long countDrift;
        long byteDrift;
        try(Connection connection = openReader(); Statement control = connection.createStatement())
        {
            control.execute("BEGIN");
            try(Statement statement = connection.createStatement())
            {
                long recordedCount;
                long recordedBytes;
                try(ResultSet rows = statement.executeQuery(
                    "SELECT call_count,total_bytes FROM catalog_metadata WHERE id=1"))
                {
                    if(!rows.next())
                    {
                        throw new SQLException("Managed recordings metadata is missing");
                    }
                    recordedCount = rows.getLong(1);
                    recordedBytes = rows.getLong(2);
                }
                long count;
                long bytes;
                try(ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*),COALESCE(SUM(size_bytes),0) FROM recording_call"))
                {
                    rows.next();
                    count = rows.getLong(1);
                    bytes = rows.getLong(2);
                }
                countDrift = count - recordedCount;
                byteDrift = bytes - recordedBytes;
                control.execute("COMMIT");
            }
            catch(SQLException exception)
            {
                control.execute("ROLLBACK");
                throw exception;
            }
        }
        if(countDrift != 0L || byteDrift != 0L)
        {
            try(Connection connection = open(); Statement control = connection.createStatement())
            {
                control.execute("BEGIN IMMEDIATE");
                try
                {
                    adjustCounts(connection, countDrift, byteDrift);
                    control.execute("COMMIT");
                }
                catch(SQLException exception)
                {
                    control.execute("ROLLBACK");
                    throw exception;
                }
            }
        }
    }

    private boolean updateSize(long id, long size) throws SQLException
    {
        if(size <= 0)
        {
            return false;
        }
        try(Connection connection = open(); Statement control = connection.createStatement())
        {
            control.execute("BEGIN IMMEDIATE");
            try
            {
                Long prior = storedSize(connection, id);
                if(prior == null || prior == size)
                {
                    control.execute("ROLLBACK");
                    return false;
                }
                try(PreparedStatement statement = connection.prepareStatement(
                    "UPDATE recording_call SET size_bytes=? WHERE id=?"))
                {
                    statement.setLong(1, size);
                    statement.setLong(2, id);
                    statement.executeUpdate();
                }
                adjustCounts(connection, 0L, size - prior);
                control.execute("COMMIT");
                return true;
            }
            catch(SQLException exception)
            {
                control.execute("ROLLBACK");
                throw exception;
            }
        }
    }

    private Path safePath(String stored) throws IOException
    {
        if(stored == null || stored.isBlank())
        {
            return null;
        }
        Path relative;
        try
        {
            relative = Path.of(stored);
        }
        catch(RuntimeException exception)
        {
            return null;
        }
        if(relative.isAbsolute() || relative.startsWith(".."))
        {
            return null;
        }
        Path path = mRoot.resolve(relative).normalize();
        if(!path.startsWith(mRoot))
        {
            return null;
        }
        Path probe = Files.exists(path) ? path : path.getParent();
        while(probe != null && !Files.exists(probe))
        {
            probe = probe.getParent();
        }
        if(probe == null)
        {
            return null;
        }
        try
        {
            if(!probe.toRealPath().startsWith(mRoot.toRealPath()))
            {
                return null;
            }
        }
        catch(java.nio.file.NoSuchFileException ignored)
        {
            // A file removed between the existence and containment checks is an ordinary missing row.
        }
        return path;
    }

    private static boolean validMp3(Path file) throws IOException
    {
        if(!Files.isRegularFile(file) || Files.size(file) < 4L ||
            !file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".mp3"))
        {
            return false;
        }
        try(java.io.InputStream stream = Files.newInputStream(file))
        {
            int a = stream.read();
            int b = stream.read();
            int c = stream.read();
            return a == 'I' && b == 'D' && c == '3' || a == 0xFF && (b & 0xE0) == 0xE0;
        }
    }

    List<String> systemKeys(String query, int limit) throws SQLException
    {
        return strings("SELECT sys.system_key FROM recording_system sys " +
            "WHERE lower(sys.system_key) LIKE ? AND EXISTS(" +
            "SELECT 1 FROM recording_call c WHERE c.system_id=sys.id) " +
            "ORDER BY sys.system_key LIMIT ?", query, List.of(), limit);
    }

    List<String> channelIds(String query, int limit) throws SQLException
    {
        return channelIds(null, query, limit);
    }

    List<String> channelIds(String systemKey, String query, int limit) throws SQLException
    {
        List<String> predicates = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        predicates.add("c.channel_id=ch.id");
        addSystemPredicate(predicates, parameters, systemKey, "sys.system_key=?");
        return strings("SELECT ch.channel_uuid FROM recording_channel ch " +
            "WHERE lower(ch.channel_uuid) LIKE ? AND EXISTS(" +
            "SELECT 1 FROM recording_call c LEFT JOIN recording_system sys ON sys.id=c.system_id " +
            "WHERE " + String.join(" AND ", predicates) + ") ORDER BY ch.channel_uuid LIMIT ?",
            query, parameters, limit);
    }

    /**
     * Decimal prefixes become at most ten disjoint numeric intervals. Each role seeks the next identity through
     * its existing index, then advances past all calls with that identity instead of reading their duplicates.
     */
    List<Integer> identitySuggestions(String systemKey, String query, boolean radio, int limit) throws SQLException
    {
        String term = query == null ? "" : query.trim();
        if(!term.matches("[0-9]{1,10}") || (term.length() > 1 && term.charAt(0) == '0')) return List.of();
        long prefix = Long.parseLong(term);
        if(prefix > Integer.MAX_VALUE) return List.of();
        List<Integer> identities = new ArrayList<>();
        List<CandidateQuery> queries = identitySuggestionQueries(radio, systemKey);
        try(Connection connection = openReader())
        {
            try(Statement setup = connection.createStatement())
            {
                setup.execute("PRAGMA busy_timeout=1000");
            }
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            org.sqlite.ProgressHandler.setHandler(connection, 10000, new org.sqlite.ProgressHandler()
            {
                @Override
                protected int progress()
                {
                    return System.nanoTime() >= deadline ? 1 : 0;
                }
            });
            List<PreparedStatement> statements = new ArrayList<>();
            try
            {
                for(CandidateQuery queryPlan: queries)
                {
                    PreparedStatement statement = connection.prepareStatement(queryPlan.sql());
                    statements.add(statement);
                    int parameter = 3;
                    for(Object value: queryPlan.parameters()) statement.setObject(parameter++, value);
                }
                for(long factor = 1; prefix * factor <= Integer.MAX_VALUE; factor *= 10)
                {
                    long lower = prefix * factor;
                    long upper = Math.min(Integer.MAX_VALUE, (prefix + 1) * factor - 1);
                    while(lower <= upper && identities.size() < limit && System.nanoTime() < deadline)
                    {
                        Integer next = null;
                        for(PreparedStatement statement: statements)
                        {
                            statement.setLong(1, lower);
                            statement.setLong(2, upper);
                            try(ResultSet rows = statement.executeQuery())
                            {
                                if(rows.next())
                                {
                                    int value = rows.getInt(1);
                                    if(next == null || value < next) next = value;
                                }
                            }
                        }
                        if(next == null) break;
                        identities.add(next);
                        lower = (long)next + 1;
                    }
                    if(identities.size() >= limit || prefix == 0 || factor > Integer.MAX_VALUE / 10L ||
                        System.nanoTime() >= deadline) break;
                }
            }
            catch(SQLException exception)
            {
                if(System.nanoTime() < deadline) throw exception;
                // An optional suggestion lookup may return its verified prefix if its work budget expires.
            }
            finally
            {
                for(PreparedStatement statement: statements) statement.close();
            }
        }
        return List.copyOf(identities);
    }

    /** Production SQL exposed to focused query-plan tests; the first two bindings are the numeric interval. */
    static List<CandidateQuery> identitySuggestionQueries(boolean radio, String systemKey)
    {
        List<CandidateQuery> result = new ArrayList<>();
        if(radio)
        {
            result.add(identitySuggestionQuery("c.source_id", "idx_recording_call_source_time", "1", systemKey));
            result.add(identitySuggestionQuery("c.target_id", "idx_recording_call_target_time", "c.call_type=3",
                systemKey));
        }
        else
        {
            result.add(identitySuggestionQuery("c.target_id", "idx_recording_call_target_time",
                "c.call_type IN (1,2)", systemKey));
        }
        result.add(identitySuggestionQuery("member.local_id", "idx_recording_patch_member_lookup",
            "member.kind=" + (radio ? 2 : 1), systemKey));
        return List.copyOf(result);
    }

    private static CandidateQuery identitySuggestionQuery(String identity, String index, String role, String systemKey)
    {
        List<String> predicates = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        predicates.add(identity + " IS NOT NULL");
        predicates.add(identity + " BETWEEN ? AND ?");
        predicates.add(role);
        addSystemPredicate(predicates, parameters, systemKey,
            "c.system_id=(SELECT id FROM recording_system WHERE system_key=?)");
        String from = identity.startsWith("member.") ?
            "recording_patch_member member INDEXED BY " + index +
                " CROSS JOIN recording_call c ON c.id=member.call_id" :
            "recording_call c INDEXED BY " + index;
        return new CandidateQuery("SELECT " + identity + " FROM " + from + " WHERE " +
            String.join(" AND ", predicates) + " ORDER BY " + identity + " LIMIT 1", List.copyOf(parameters));
    }

    private List<String> strings(String sql, String query, List<Object> parameters, int limit) throws SQLException
    {
        String term = query != null ? query.trim().toLowerCase(java.util.Locale.ROOT) : "";
        if(term.length() > 80)
        {
            term = term.substring(0, 80);
        }
        term = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        sql = sql.replace("LIKE ?", "LIKE ? ESCAPE '\\'");
        List<String> values = new ArrayList<>();
        try(Connection connection = openReader(); PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setString(1, "%" + term + "%");
            int parameter = 2;
            for(Object value : parameters)
            {
                statement.setObject(parameter++, value);
            }
            statement.setInt(parameter, limit);
            try(ResultSet rows = statement.executeQuery())
            {
                while(rows.next())
                {
                    values.add(rows.getString(1));
                }
            }
        }
        return List.copyOf(values);
    }

    List<Site> sites(String systemKey, String query, int limit) throws SQLException
    {
        List<String> predicates = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        predicates.add("cs.site_id=site.id");
        addSystemPredicate(predicates, parameters, systemKey, "sys.system_key=?");
        String sql = "SELECT site.wacn,site.system_id,site.rfss,site.site_id FROM recording_site site " +
            "WHERE EXISTS(SELECT 1 FROM recording_call_site cs " +
            "JOIN recording_call c ON c.id=cs.call_id " +
            "LEFT JOIN recording_system sys ON sys.id=c.system_id WHERE " +
            String.join(" AND ", predicates) + ")";
        String term = query != null ? query.trim() : "";
        if(term.length() > 32)
        {
            term = term.substring(0, 32);
        }
        if(!term.isBlank())
        {
            sql += " AND (printf('%05X-%03X / %02X-%02X',site.wacn," +
                "site.system_id,site.rfss,site.site_id) LIKE ? " +
                "OR CAST(site.rfss AS TEXT) LIKE ? OR CAST(site.site_id AS TEXT) LIKE ?)";
        }
        sql += " ORDER BY site.wacn,site.system_id,site.rfss,site.site_id LIMIT ?";
        List<Site> sites = new ArrayList<>();
        try(Connection connection = openReader(); PreparedStatement statement = connection.prepareStatement(sql))
        {
            int p = 1;
            for(Object value : parameters)
            {
                statement.setObject(p++, value);
            }
            if(!term.isBlank())
            {
                statement.setString(p++, "%" + term + "%");
                statement.setString(p++, "%" + term + "%");
                statement.setString(p++, "%" + term + "%");
            }
            statement.setInt(p, limit);
            try(ResultSet rows = statement.executeQuery())
            {
                while(rows.next())
                {
                    sites.add(new Site(rows.getInt(1), rows.getInt(2), rows.getInt(3), rows.getInt(4)));
                }
            }
        }
        return List.copyOf(sites);
    }

    @Override
    public void close() throws SQLException
    {
        mWriterConnection.close();
    }

    record StoredStats(long callCount, long totalBytes)
    {
    }

    private record FileEntry(long id, String relativePath, long sizeBytes, long startMs)
    {
        FileEntry(long id, String relativePath, long sizeBytes)
        {
            this(id, relativePath, sizeBytes, 0L);
        }
    }
}
