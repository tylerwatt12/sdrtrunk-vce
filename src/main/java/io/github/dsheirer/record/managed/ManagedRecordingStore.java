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
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.Selection;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.AuxiliaryIdentifier;
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Isolated SQLite implementation for Managed Recordings. This file has its own format version. */
final class ManagedRecordingStore implements AutoCloseable
{
    private static final int FORMAT_VERSION = ManagedRecordingSchema.CURRENT_FORMAT_VERSION;
    private static final int APPLICATION_ID = ManagedRecordingSchema.APPLICATION_ID;
    private static final int MAINTENANCE_BATCH = 256;
    private static final int MAX_NAME_SEARCH_HOME_SYSTEMS = 128;
    private static final int MAX_NAME_GROUP_HOME_LOOKUPS = 4_096;
    // String.isBlank whitespace, so stored no-speech responses agree with the excerpt shown by the web API.
    private static final String TRANSCRIPT_WHITESPACE = "\t\n\u000B\f\r\u001C\u001D\u001E\u001F " +
        "\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2008\u2009\u200A\u2028\u2029\u205F\u3000";
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
        return openReader(5000);
    }

    private Connection openReader(int busyTimeoutMilliseconds) throws SQLException
    {
        org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(busyTimeoutMilliseconds);
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabaseFile, config.toProperties());
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA query_only=ON");
        }
        catch(SQLException exception)
        {
            connection.close();
            throw exception;
        }
        return connection;
    }

    private void createFresh() throws SQLException
    {
        mWriterConnection.setAutoCommit(false);
        try(Statement statement = mWriterConnection.createStatement())
        {
            for(String ddl : ManagedRecordingSchema.creationStatements())
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
    long insert(ManagedRecordingMetadata item) throws SQLException
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
                "INSERT OR IGNORE INTO recording_identifier(call_id,protocol,kind,role,value,start_ms) VALUES(?,?,?,?,?,?)"))
            {
                for(AuxiliaryIdentifier identifier: item.auxiliaryIdentifiers())
                {
                    statement.setLong(1,callId); statement.setString(2,identifier.protocol());
                    statement.setString(3,identifier.kind()); statement.setString(4,identifier.role());
                    statement.setString(5,identifier.value()); statement.setLong(6,item.startMs()); statement.addBatch();
                }
                statement.executeBatch();
            }
            try(PreparedStatement statement = mWriterConnection.prepareStatement(
                "INSERT INTO recording_source(call_id,source_call_uuid) VALUES(?,?)"))
            {
                for(String reference: item.sourceCallReferences())
                {
                    statement.setLong(1, callId);
                    statement.setString(2, reference);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            ManagedRecordingTones.insert(mWriterConnection, callId, item.completedTones());
            try(PreparedStatement statement = mWriterConnection.prepareStatement(
                "UPDATE catalog_metadata SET call_count=call_count+1,total_bytes=total_bytes+? WHERE id=1"))
            {
                statement.setLong(1, item.sizeBytes());
                statement.executeUpdate();
            }
            mWriterConnection.commit();
            return callId;
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

    /** Exact catalog ID supplied by its committed insert; optional attachment never alters the audio row. */
    void attachCompletedTones(long callId, io.github.dsheirer.audio.tone.CompletedToneSequence sequence) throws SQLException
    {
        mWriterConnection.setAutoCommit(false);
        try
        {
            try(var query = mWriterConnection.prepareStatement("SELECT 1 FROM recording_call WHERE id=? AND NOT EXISTS(" +
                "SELECT 1 FROM recording_tone_sequence WHERE call_id=?)"))
            {
                query.setLong(1, callId); query.setLong(2, callId);
                try(var row = query.executeQuery())
                {
                    if(!row.next()) { mWriterConnection.commit(); return; }
                }
            }
            ManagedRecordingTones.insert(mWriterConnection, callId, sequence);
            mWriterConnection.commit();
        }
        catch(SQLException | RuntimeException failure)
        {
            mWriterConnection.rollback();
            throw failure;
        }
        finally { mWriterConnection.setAutoCommit(true); }
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
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            org.sqlite.ProgressHandler.setHandler(connection, 10000, new org.sqlite.ProgressHandler()
            {
                @Override
                protected int progress()
                {
                    return Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline ? 1 : 0;
                }
            });
            try
            {
                return searchCandidates(connection, filter,deadline);
            }
            finally
            {
                org.sqlite.ProgressHandler.clearHandler(connection);
            }
        }
    }

    /**
     * Each identity or scope supplies an independently ordered stream. The first page from each stream gives the
     * exact first page of their union, even when a call appears in several streams. Every combined predicate and
     * cursor is applied before each stream's limit, avoiding materialization of the complete archive match set.
     */
    private SearchPage searchCandidates(Connection connection, SearchFilter filter, long deadline) throws SQLException
    {
        try(Statement transaction = connection.createStatement())
        {
            transaction.execute("BEGIN");
            try
            {
                Map<Long,CallKey> distinct = new HashMap<>();
                for(CandidateQuery branch : singleIdentityCandidateQueries(filter, connection))
                {
                    checkSearchDeadline(deadline);
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
                            checkSearchDeadline(deadline);
                            statement.setLong(p++, key.id());
                        }
                        try(ResultSet rows = statement.executeQuery())
                        {
                            while(rows.next())
                            {
                                checkSearchDeadline(deadline);
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
                org.sqlite.ProgressHandler.clearHandler(connection);
                try { transaction.execute("ROLLBACK"); }
                catch(SQLException rollback) { exception.addSuppressed(rollback); }
                throw exception;
            }
        }
    }

    private static void checkSearchDeadline(long deadline) throws SQLException
    {
        if(Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
            throw new SQLException("Recording search exceeded its time limit or was cancelled","",9);
    }

    /** Package visibility permits the plan test to inspect the actual bounded search statements. */
    List<CandidateQuery> singleIdentityCandidateQueries(SearchFilter filter) throws SQLException
    {
        try(Connection connection = openReader())
        {
            return singleIdentityCandidateQueries(filter,connection);
        }
    }

    private List<CandidateQuery> singleIdentityCandidateQueries(SearchFilter filter, Connection connection) throws SQLException
    {
        List<Integer> anyIds = new ArrayList<>(filter.anyIdentityIds);
        if(filter.anyIdentityId != null && !anyIds.contains(filter.anyIdentityId))
        {
            anyIds.add(filter.anyIdentityId);
        }
        if(anyIds.size() > 200) throw new IllegalArgumentException("Too many recording identity search IDs");
        return singleIdentityCandidateQueries(filter, anyIds, connection);
    }

    private List<CandidateQuery> singleIdentityCandidateQueries(SearchFilter filter, List<Integer> anyIds,
                                                               Connection connection) throws SQLException
    {
        if(filter.transcriptMatch != null)
            return List.of(candidateQuery(filter,"recording_transcript_fts CROSS JOIN recording_call c " +
                "ON c.id=recording_transcript_fts.rowid", "recording_transcript_fts MATCH ?",
                List.of(filter.transcriptMatch),"c.start_ms","c.id",new SelectionDriver(null,false,null,true)));
        if(!filter.channelIds.isEmpty() && (filter.targetsAny || filter.talkgroupSelections.isEmpty()))
        {
            List<CandidateQuery> queries = new ArrayList<>();
            if(filter.targetsAny && !filter.talkgroupSelections.isEmpty())
                queries.addAll(selectionCandidateQueries(filter,filter.talkgroupSelections,true));
            for(String channel: filter.channelIds)
                queries.add(candidateQuery(filter,"recording_call c INDEXED BY idx_recording_call_channel_time",
                    "c.channel_id=(SELECT id FROM recording_channel WHERE channel_uuid=?)",List.of(channel),
                    "c.start_ms","c.id",new SelectionDriver(null,false,channel,false)));
            return List.copyOf(queries);
        }
        if(!filter.talkgroupSelections.isEmpty() || !filter.radioSelections.isEmpty())
        {
            List<Selection> selections = !filter.talkgroupSelections.isEmpty() ?
                filter.talkgroupSelections : filter.radioSelections;
            return selectionCandidateQueries(filter,selections,!filter.talkgroupSelections.isEmpty());
        }
        return otherCandidateQueries(filter,anyIds,connection);
    }

    private List<CandidateQuery> selectionCandidateQueries(SearchFilter filter, List<Selection> selections, boolean groups)
    {
            List<CandidateQuery> queries = new ArrayList<>();
            for(Selection selection: selections)
                if(selection.value() == null && selection.identityKey() == null)
                    queries.addAll(numericIdentityCandidateQueries(filter,selection.id(),groups,!groups,
                        new SelectionDriver(selection,!groups)));
            for(Selection selection: selections)
            {
                if(selection.value() != null)
                {
                    queries.add(auxiliaryCandidateQuery(filter,selection));
                    continue;
                }
                if(selection.identityKey() == null) continue;
                RadioSystemIdentityKey.Identity identity = RadioSystemIdentityKey.parse(selection.identityKey());
                queries.addAll(homeIdentityCandidateQueries(filter,new HomeMatch(identity.identityId(),
                    selection.systemKey(),identity.homeWacn(),identity.homeSystemId(),false),!groups,
                    new SelectionDriver(selection,!groups)));
            }
            return List.copyOf(queries);
    }

    private List<CandidateQuery> otherCandidateQueries(SearchFilter filter, List<Integer> anyIds, Connection connection)
        throws SQLException
    {
        if(!filter.identityNameMatches.isEmpty())
        {
            List<CandidateQuery> queries = new ArrayList<>();
            List<String> homeSystems = filter.identityNameMatches.stream().anyMatch(IdentityNameMatch::configuredTalkgroup) ?
                nameSearchHomeSystems(filter, connection) : List.of();
            long homeLookups = filter.identityNameMatches.stream().filter(IdentityNameMatch::configuredTalkgroup)
                .map(IdentityNameMatch::identityId).distinct().count() * homeSystems.size();
            if(homeLookups > MAX_NAME_GROUP_HOME_LOOKUPS)
                throw new IllegalArgumentException("Too many talkgroups and systems match this name search. " +
                    "Choose a system and try again.");
            var rawIds = new LinkedHashSet<Integer>();
            for(IdentityNameMatch match: filter.identityNameMatches)
            {
                if(match.systemKey() == null)
                {
                    if(rawIds.add(match.identityId()))
                        queries.addAll(numericIdentityCandidateQueries(filter, match.identityId(), false, false));
                    //A qualified same-home group can have a known local Alias but no captured working address.
                    //Keep raw numeric searches unchanged and add this compatibility stream only for name searches.
                    for(String system: match.configuredTalkgroup() ? homeSystems : List.<String>of())
                    {
                        int wacn = Integer.parseInt(system.substring(4, 9), 16);
                        int sysid = Integer.parseInt(system.substring(10), 16);
                        queries.add(scopedIdentityCandidate(filter,
                            new HomeMatch(match.identityId(), system, wacn, sysid, false),
                            "recording_patch_member pm INDEXED BY idx_recording_patch_member_home_lookup " +
                                "JOIN recording_call c ON c.id=pm.call_id",
                            "pm.kind=1 AND pm.home_wacn=? AND pm.home_system=? AND pm.home_id=? " +
                                "AND pm.local_id=0 AND pm.start_ms BETWEEN ? AND ?",
                            List.of(wacn, sysid, match.identityId(), filter.fromMs, filter.toMs),
                            "pm.start_ms", "pm.call_id", null));
                    }
                }
                else
                    queries.addAll(nameIdentityCandidateQueries(filter, match));
            }
            return List.copyOf(queries);
        }
        if(filter.talkgroupIdentityMatch != null || filter.radioIdentityMatch != null)
            return nameIdentityCandidateQueries(filter, filter.talkgroupIdentityMatch != null ?
                filter.talkgroupIdentityMatch : filter.radioIdentityMatch);
        if(filter.talkgroupId != null)
            return numericIdentityCandidateQueries(filter, filter.talkgroupId, true, false);
        if(filter.sourceId != null)
            return numericIdentityCandidateQueries(filter, filter.sourceId, false, true);
        if(!anyIds.isEmpty())
        {
            List<CandidateQuery> queries = new ArrayList<>();
            for(int id: anyIds) queries.addAll(numericIdentityCandidateQueries(filter, id, false, false));
            return List.copyOf(queries);
        }
        if(filter.talkgroupMin != null && (long)filter.talkgroupMax - filter.talkgroupMin <= 31 ||
            filter.sourceMin != null && (long)filter.sourceMax - filter.sourceMin <= 31)
        {
            boolean groups = filter.talkgroupMin != null && (long)filter.talkgroupMax - filter.talkgroupMin <= 31;
            List<CandidateQuery> queries = new ArrayList<>();
            int first = groups ? filter.talkgroupMin : filter.sourceMin;
            int last = groups ? filter.talkgroupMax : filter.sourceMax;
            for(long id = first; id <= last; id++)
                queries.addAll(numericIdentityCandidateQueries(filter, (int)id, groups, !groups));
            return List.copyOf(queries);
        }
        if(filter.systemKey != null && !filter.systemKey.isBlank())
            return systemCandidateQueries(filter, filter.systemKey);
        if(filter.channelId != null && !filter.channelId.isBlank())
            return List.of(candidateQuery(filter, "recording_call c INDEXED BY idx_recording_call_channel_time",
                "c.channel_id=(SELECT id FROM recording_channel WHERE channel_uuid=?)", List.of(filter.channelId),
                "c.start_ms", "c.id"));
        if(filter.aliasListId != null)
            return List.of(candidateQuery(filter, "recording_call c INDEXED BY idx_recording_call_alias_list_time",
                "c.alias_list_id=?", List.of(filter.aliasListId), "c.start_ms", "c.id"));
        if(filter.frequencyHz != null)
            return List.of(candidateQuery(filter, "recording_call c INDEXED BY idx_recording_call_frequency_time",
                "c.frequency_hz=?", List.of(filter.frequencyHz), "c.start_ms", "c.id"));
        if(filter.wacn != null || filter.systemId != null || filter.rfss != null || filter.siteId != null)
        {
            List<CandidateQuery> sites = siteCandidateQueries(filter,connection);
            if(sites != null) return sites;
        }
        if(filter.pagingTones != null || filter.toneFrequencyHz != null)
            return List.of(candidateQuery(filter,"recording_tone_sequence tone_driver CROSS JOIN recording_call c " +
                "ON c.id=tone_driver.call_id","1",List.of(),"c.start_ms","c.id"));
        if(filter.protocol != null && !filter.protocol.isBlank() && protocolFilter(filter.protocol) < 7)
            return List.of(candidateQuery(filter,"recording_call c INDEXED BY idx_recording_call_protocol_time",
                "c.protocol=?",List.of(protocolFilter(filter.protocol)),"c.start_ms","c.id"));
        if(filter.callType != null && !filter.callType.isBlank())
            return List.of(candidateQuery(filter,"recording_call c INDEXED BY idx_recording_call_call_type_time",
                "c.call_type=?",List.of(callTypeFilter(filter.callType)),"c.start_ms","c.id"));
        if(filter.voiceType != null && !filter.voiceType.isBlank())
            return List.of(candidateQuery(filter,"recording_call c INDEXED BY idx_recording_call_voice_type_time",
                "c.voice_type=?",List.of(voiceTypeFilter(filter.voiceType)),"c.start_ms","c.id"));
        return List.of(candidateQuery(filter, "recording_call c INDEXED BY idx_recording_call_time", "1",
            List.of(), "c.start_ms", "c.id"));
    }

    /** Read only the small identity directories; never discover receiving homes by scanning call history. */
    private List<String> nameSearchHomeSystems(SearchFilter filter, Connection connection) throws SQLException
    {
        if(filter.systemKey != null && !filter.systemKey.isBlank())
            return RadioSystemKey.isP25Native(filter.systemKey) ? List.of(filter.systemKey) : List.of();
        var systems = new LinkedHashSet<String>();
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT system_key FROM recording_system " +
                "WHERE system_key GLOB 'p25:*' LIMIT " + (MAX_NAME_SEARCH_HOME_SYSTEMS + 1)))
        {
            int examined = 0;
            while(rows.next())
            {
                requireBoundedNameHomes(++examined);
                String key = rows.getString(1);
                if(RadioSystemKey.isP25Native(key)) systems.add(key);
            }
        }
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT DISTINCT wacn,system_id FROM recording_site LIMIT " +
                (MAX_NAME_SEARCH_HOME_SYSTEMS + 1)))
        {
            int examined = 0;
            while(rows.next())
            {
                requireBoundedNameHomes(++examined);
                String key = RadioSystemKey.p25(rows.getInt(1), rows.getInt(2));
                if(key != null) systems.add(key);
                requireBoundedNameHomes(systems.size());
            }
        }
        return List.copyOf(systems);
    }

    private static void requireBoundedNameHomes(int count)
    {
        if(count > MAX_NAME_SEARCH_HOME_SYSTEMS)
            throw new IllegalArgumentException("Too many recorded systems match this name search. " +
                "Choose a system and try again.");
    }

    /** Resolve the small site directory, then page each matching site's ordered index inside this read snapshot. */
    private List<CandidateQuery> siteCandidateQueries(SearchFilter filter, Connection connection) throws SQLException
    {
        List<String> conditions = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        if(filter.wacn != null) { conditions.add("wacn=?"); parameters.add(filter.wacn); }
        if(filter.systemId != null) { conditions.add("system_id=?"); parameters.add(filter.systemId); }
        if(filter.rfss != null) { conditions.add("rfss=?"); parameters.add(filter.rfss); }
        if(filter.siteId != null) { conditions.add("site_id=?"); parameters.add(filter.siteId); }
        List<CandidateQuery> queries = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement("SELECT id FROM recording_site WHERE " +
            String.join(" AND ",conditions) + " ORDER BY id LIMIT 129"))
        {
            int p=1; for(Object value: parameters) statement.setObject(p++,value);
            try(ResultSet rows = statement.executeQuery())
            {
                while(rows.next())
                {
                    if(queries.size() == 128) return null;
                    queries.add(candidateQuery(filter,"recording_call_site cs INDEXED BY " +
                        "idx_recording_call_site_lookup JOIN recording_call c ON c.id=cs.call_id",
                        "cs.site_id=? AND cs.start_ms BETWEEN ? AND ?",List.of(rows.getLong(1),filter.fromMs,filter.toMs),
                        "cs.start_ms","cs.call_id"));
                }
            }
        }
        return List.copyOf(queries);
    }

    private CandidateQuery auxiliaryCandidateQuery(SearchFilter filter, Selection selection)
    {
        String predicate = "ri.protocol=? AND ri.kind='radio' AND ri.value=? AND ri.start_ms BETWEEN ? AND ?";
        if("source".equals(filter.radioRole)) predicate += " AND ri.role='source'";
        else predicate += " AND NOT EXISTS(SELECT 1 FROM recording_identifier earlier WHERE " +
            "earlier.call_id=ri.call_id AND earlier.protocol=ri.protocol AND earlier.kind=ri.kind " +
            "AND earlier.value=ri.value AND earlier.role<ri.role)";
        return candidateQuery(filter,"recording_identifier ri INDEXED BY idx_recording_identifier_lookup " +
            "JOIN recording_call c ON c.id=ri.call_id",predicate,
            List.of(selection.protocol(),selection.value(),filter.fromMs,filter.toMs),"ri.start_ms","ri.call_id",
            new SelectionDriver(selection,true));
    }

    /** The index stream proves this one picker choice; other choices of its kind need no repeated OR predicate. */
    private record SelectionDriver(Selection selection, boolean radio, String channelId, boolean transcript)
    {
        SelectionDriver(Selection selection, boolean radio) { this(selection,radio,null,false); }
    }

    private List<CandidateQuery> numericIdentityCandidateQueries(SearchFilter filter, int id,
                                                                 boolean talkgroup, boolean radio)
    {
        return numericIdentityCandidateQueries(filter,id,talkgroup,radio,null);
    }

    private List<CandidateQuery> numericIdentityCandidateQueries(SearchFilter filter, int id,
                                                                 boolean talkgroup, boolean radio,
                                                                 SelectionDriver driver)
    {
        List<CandidateQuery> queries = new ArrayList<>(4);
        if(!talkgroup)
            queries.add(candidateQuery(filter, "recording_call c INDEXED BY idx_recording_call_source_time",
                "c.source_id=?", List.of(id), "c.start_ms", "c.id",driver));
        if(!radio || !"source".equals(filter.radioRole))
        {
            queries.add(candidateQuery(filter, "recording_call c INDEXED BY idx_recording_call_target_time",
                talkgroup ? "c.target_id=? AND c.call_type IN(1,2)" : radio ?
                    "c.target_id=? AND c.call_type=3" : "c.target_id=?", List.of(id), "c.start_ms", "c.id",driver));
            if(!radio) queries.add(patchCandidateQuery(filter, id, 1,driver));
            if(!talkgroup) queries.add(patchCandidateQuery(filter, id, 2,driver));
        }
        return List.copyOf(queries);
    }

    private List<CandidateQuery> systemCandidateQueries(SearchFilter filter, String system)
    {
        List<CandidateQuery> queries = new ArrayList<>();
        queries.add(candidateQuery(filter, "recording_call c INDEXED BY idx_recording_call_system_time",
            "c.system_id=(SELECT id FROM recording_system WHERE system_key=?)", List.of(system),
            "c.start_ms", "c.id"));
        if(RadioSystemKey.isP25Native(system))
        {
            List<String> conditions = new ArrayList<>();
            List<Object> parameters = new ArrayList<>();
            conditions.add("c.system_id IS NULL");
            addSystemPredicate(conditions,parameters,system,
                "c.system_id=(SELECT id FROM recording_system WHERE system_key=?)");
            queries.add(candidateQuery(filter, "recording_call c INDEXED BY idx_recording_call_unowned_time",
                String.join(" AND ",conditions), parameters, "c.start_ms", "c.id"));
        }
        return List.copyOf(queries);
    }

    private record HomeMatch(int identityId, String systemKey, Integer homeWacn,
                             Integer homeSystemId, boolean includeNullHome) {}

    private static HomeMatch homeMatch(IdentityNameMatch match)
    {
        return match == null ? null : new HomeMatch(match.identityId(),match.systemKey(),match.homeWacn(),
            match.homeSystemId(),match.includeNullHome());
    }

    private List<CandidateQuery> nameIdentityCandidateQueries(SearchFilter filter, IdentityNameMatch match)
    {
        return homeIdentityCandidateQueries(filter,homeMatch(match),!match.equals(filter.talkgroupIdentityMatch));
    }

    private List<CandidateQuery> homeIdentityCandidateQueries(SearchFilter filter, HomeMatch match,
                                                               boolean radio)
    {
        return homeIdentityCandidateQueries(filter,match,radio,null);
    }

    private List<CandidateQuery> homeIdentityCandidateQueries(SearchFilter filter, HomeMatch match,
                                                               boolean radio, SelectionDriver driver)
    {
        List<CandidateQuery> queries = new ArrayList<>();
        boolean sourceOnly = radio && "source".equals(filter.radioRole);
        if(match.homeWacn() >= 0)
        {
            if(radio)
                queries.add(scopedIdentityCandidate(filter,match,"recording_call c INDEXED BY " +
                    "idx_recording_call_source_home_time", "c.source_home_wacn=? AND c.source_home_system=? " +
                    "AND c.source_home_id=?", List.of(match.homeWacn(),match.homeSystemId(),match.identityId()),
                    "c.start_ms","c.id",driver));
            if(!sourceOnly)
            {
                queries.add(scopedIdentityCandidate(filter,match,"recording_call c INDEXED BY " +
                    "idx_recording_call_target_home_time", "c.target_home_wacn=? AND c.target_home_system=? " +
                    "AND c.target_home_id=? AND " + (radio ? "c.call_type=3" : "c.call_type IN(1,2)"),
                    List.of(match.homeWacn(),match.homeSystemId(),match.identityId()),"c.start_ms","c.id",driver));
                queries.add(scopedIdentityCandidate(filter,match,"recording_patch_member pm INDEXED BY " +
                    "idx_recording_patch_member_home_lookup JOIN recording_call c ON c.id=pm.call_id",
                    "pm.kind=? AND pm.home_wacn=? AND pm.home_system=? AND pm.home_id=? " +
                    "AND pm.start_ms BETWEEN ? AND ? AND NOT EXISTS(SELECT 1 FROM recording_patch_member earlier " +
                    "WHERE earlier.call_id=pm.call_id AND earlier.kind=pm.kind AND earlier.home_wacn=pm.home_wacn " +
                    "AND earlier.home_system=pm.home_system AND earlier.home_id=pm.home_id AND earlier.local_id<pm.local_id)",
                    List.of(radio ? 2 : 1,match.homeWacn(),match.homeSystemId(),match.identityId(),filter.fromMs,filter.toMs),
                    "pm.start_ms","pm.call_id",driver));
            }
        }
        if(match.homeWacn() >= 0)
        {
            // Older metadata can prove a home but omit the canonical number when it equals the local address.
            if(radio)
                queries.add(scopedIdentityCandidate(filter,match,"recording_call c INDEXED BY idx_recording_call_source_time",
                    "c.source_id=? AND c.source_home_wacn=? AND c.source_home_system=? AND c.source_home_id IS NULL",
                    List.of(match.identityId(),match.homeWacn(),match.homeSystemId()),"c.start_ms","c.id",driver));
            if(!sourceOnly)
            {
                queries.add(scopedIdentityCandidate(filter,match,"recording_call c INDEXED BY idx_recording_call_target_time",
                    "c.target_id=? AND c.target_home_wacn=? AND c.target_home_system=? AND c.target_home_id IS NULL AND " +
                    (radio ? "c.call_type=3" : "c.call_type IN(1,2)"),
                    List.of(match.identityId(),match.homeWacn(),match.homeSystemId()),"c.start_ms","c.id",driver));
                queries.add(scopedIdentityCandidate(filter,match,"recording_patch_member pm INDEXED BY " +
                    "idx_recording_patch_member_lookup JOIN recording_call c ON c.id=pm.call_id", "pm.kind=? AND pm.local_id=? " +
                    "AND pm.home_wacn=? AND pm.home_system=? AND pm.home_id=-1 AND pm.start_ms BETWEEN ? AND ?",
                    List.of(radio ? 2 : 1,match.identityId(),match.homeWacn(),match.homeSystemId(),filter.fromMs,filter.toMs),
                    "pm.start_ms","pm.call_id",driver));
            }
        }
        if(match.includeNullHome())
        {
            if(radio)
                queries.add(scopedIdentityCandidate(filter,match,"recording_call c INDEXED BY idx_recording_call_source_time",
                    "c.source_id=? AND c.source_home_wacn IS NULL AND c.source_home_system IS NULL AND c.source_home_id IS NULL",
                    List.of(match.identityId()),"c.start_ms","c.id",driver));
            if(!sourceOnly)
            {
                queries.add(scopedIdentityCandidate(filter,match,"recording_call c INDEXED BY idx_recording_call_target_time",
                    "c.target_id=? AND c.target_home_wacn IS NULL AND c.target_home_system IS NULL AND c.target_home_id IS NULL AND " +
                    (radio ? "c.call_type=3" : "c.call_type IN(1,2)"), List.of(match.identityId()),"c.start_ms","c.id",driver));
                queries.add(scopedIdentityCandidate(filter,match,"recording_patch_member pm INDEXED BY " +
                    "idx_recording_patch_member_lookup JOIN recording_call c ON c.id=pm.call_id", "pm.kind=? AND pm.local_id=? " +
                    "AND pm.home_wacn=-1 AND pm.home_system=-1 AND pm.home_id=-1 AND pm.start_ms BETWEEN ? AND ?",
                    List.of(radio ? 2 : 1,match.identityId(),filter.fromMs,filter.toMs),"pm.start_ms","pm.call_id",driver));
            }
        }
        return List.copyOf(queries);
    }

    private CandidateQuery scopedIdentityCandidate(SearchFilter filter, HomeMatch match, String from,
                                                    String predicate, List<Object> values, String orderTime, String orderId,
                                                    SelectionDriver driver)
    {
        List<String> conditions = new ArrayList<>();
        List<Object> parameters = new ArrayList<>(values);
        conditions.add("c.protocol IN(1,2) AND " + predicate);
        if(match.systemKey() == null) conditions.add(unknownSystemPredicate());
        else addSystemPredicate(conditions,parameters,match.systemKey(),
            "c.system_id=(SELECT id FROM recording_system WHERE system_key=?)");
        return candidateQuery(filter,from,String.join(" AND ",conditions),parameters,orderTime,orderId,driver);
    }

    private static void addRadioIdentityPredicate(List<String> conditions, List<Object> parameters,
                                                  HomeMatch match, String role)
    {
        if(match != null)
        {
            conditions.add("c.protocol IN(1,2) AND " + radioIdentityPredicate(match, parameters, role));
        }
    }

    private static void addGroupIdentityPredicate(List<String> conditions, List<Object> parameters,
                                                  HomeMatch match)
    {
        if(match == null) return;
        String target = nameRadioValue(match, "c.target_id", "c.target_home_wacn",
            "c.target_home_system", "c.target_home_id", parameters);
        String member = nameRadioValue(match, "member.local_id", "nullif(member.home_wacn,-1)",
            "nullif(member.home_system,-1)", "nullif(member.home_id,-1)", parameters);
        conditions.add("c.protocol IN(1,2) AND ((c.call_type IN(1,2) AND " + target + ") OR " +
            "EXISTS(SELECT 1 FROM recording_patch_member member WHERE member.call_id=c.id " +
            "AND member.kind=1 AND " + member + "))");
    }

    private static String radioIdentityPredicate(HomeMatch match, List<Object> parameters, String role)
    {
        String source = nameRadioValue(match, "c.source_id", "c.source_home_wacn",
            "c.source_home_system", "c.source_home_id", parameters);
        if("source".equals(role)) return source;
        String target = nameRadioValue(match, "c.target_id", "c.target_home_wacn",
            "c.target_home_system", "c.target_home_id", parameters);
        String member = nameRadioValue(match, "member.local_id", "nullif(member.home_wacn,-1)",
            "nullif(member.home_system,-1)", "nullif(member.home_id,-1)", parameters);
        return "(" + source + " OR (c.call_type=3 AND " + target + ") OR " +
            "EXISTS(SELECT 1 FROM recording_patch_member member WHERE member.call_id=c.id " +
            "AND member.kind=2 AND " + member + "))";
    }

    /** Home metadata represents the permanent identity; its captured local address can differ. */
    private static String nameRadioValue(HomeMatch match, String localId, String homeWacn,
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

    private CandidateQuery patchCandidateQuery(SearchFilter filter, int id, int kind, SelectionDriver driver)
    {
        String patchPredicate = "pm.kind=? AND pm.local_id=? AND pm.start_ms BETWEEN ? AND ? " +
            "AND NOT EXISTS(SELECT 1 FROM recording_patch_member earlier WHERE " +
            "earlier.call_id=pm.call_id AND earlier.kind=pm.kind AND earlier.local_id=pm.local_id " +
            "AND (earlier.home_wacn,earlier.home_system,earlier.home_id)<" +
            "(pm.home_wacn,pm.home_system,pm.home_id))";
        return candidateQuery(filter, "recording_patch_member pm INDEXED BY " +
            "idx_recording_patch_member_lookup JOIN recording_call c ON c.id=pm.call_id",
            patchPredicate, List.of(kind, id, filter.fromMs, filter.toMs), "pm.start_ms", "pm.call_id",driver);
    }

    private CandidateQuery candidateQuery(SearchFilter filter, String from, String identityPredicate,
                                          List<Object> identityParameters, String orderTime, String orderId)
    {
        return candidateQuery(filter,from,identityPredicate,identityParameters,orderTime,orderId,null);
    }

    private CandidateQuery candidateQuery(SearchFilter filter, String from, String identityPredicate,
                                          List<Object> identityParameters, String orderTime, String orderId,
                                          SelectionDriver driver)
    {
        List<String> conditions = new ArrayList<>();
        List<Object> parameters = new ArrayList<>(identityParameters);
        conditions.add(identityPredicate);
        conditions.add("c.start_ms BETWEEN ? AND ?");
        parameters.add(filter.fromMs);
        parameters.add(filter.toMs);
        addSystemPredicate(conditions, parameters, filter.systemKey,
            "c.system_id=(SELECT id FROM recording_system WHERE system_key=?)");
        addRadioIdentityPredicate(conditions, parameters, homeMatch(filter.radioIdentityMatch), filter.radioRole);
        addGroupIdentityPredicate(conditions, parameters, homeMatch(filter.talkgroupIdentityMatch));
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
            int code = protocolFilter(filter.protocol);
            if(code >= 7)
            {
                conditions.add("(c.protocol=? OR EXISTS(SELECT 1 FROM recording_identifier protocol_identifier " +
                    "WHERE protocol_identifier.call_id=c.id AND protocol_identifier.protocol=?)" +
                    (code == 7 ? " OR (c.tone_kind=1 AND c.tone IS NOT NULL AND length(c.tone)>0)" : "") + ")");
                parameters.add(code); parameters.add(ManagedRecordingCatalog.protocolName(code));
            }
            else { conditions.add("c.protocol=?"); parameters.add(code); }
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
        addNumericIdentityPredicates(conditions, parameters, filter);
        addTargetPredicate(conditions,parameters,filter,driver);
        addSelectionPredicate(conditions, parameters, driver != null && driver.selection() != null && driver.radio() ?
            List.of(driver.selection()) : filter.radioSelections, true, filter.radioRole);
        addTonePredicate(conditions, parameters, filter);
        if(driver == null || !driver.transcript()) addTranscriptPredicate(conditions, parameters, filter.transcriptMatch);
        if(filter.hasTranscript != null)
        {
            conditions.add((filter.hasTranscript ? "" : "NOT ") +
                "EXISTS(SELECT 1 FROM recording_transcript transcript_present WHERE transcript_present.call_id=c.id " +
                "AND length(trim(transcript_present.text,?))>0)");
            parameters.add(TRANSCRIPT_WHITESPACE);
        }
        if(filter.dcsCode != null)
        {
            conditions.add("(EXISTS(SELECT 1 FROM recording_identifier dcs WHERE dcs.call_id=c.id " +
                "AND dcs.protocol='DCS' AND dcs.kind='tone' AND dcs.value=?) OR " +
                "(c.tone_kind=1 AND c.tone IN(?,?,?)))");
            parameters.add(filter.dcsCode);
            parameters.add(filter.dcsCode.startsWith("N") ? filter.dcsCode.substring(1) : filter.dcsCode);
            parameters.add(io.github.dsheirer.module.decode.dcs.DCSCode.valueOf(filter.dcsCode).toString());
            parameters.add(filter.dcsCode);
        }
        if(filter.cursor != null && !filter.cursor.isBlank())
        {
            long[] cursor = decodeCursor(filter.cursor);
            String compare = filter.sortAscending ? ">" : "<";
            conditions.add(orderTime + (filter.sortAscending ? ">=?" : "<=?"));
            parameters.add(cursor[0]);
            conditions.add("(" + orderTime + "," + orderId + ")" + compare + "(?,?)");
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

    private static void addTargetPredicate(List<String> conditions, List<Object> parameters, SearchFilter filter,
                                           SelectionDriver driver)
    {
        boolean channelDriven = driver != null && driver.channelId() != null;
        boolean groupDriven = driver != null && driver.selection() != null && !driver.radio();
        List<String> group = new ArrayList<>();
        List<Object> groupParameters = new ArrayList<>();
        if(!(channelDriven && filter.targetsAny))
            addSelectionPredicate(group,groupParameters,groupDriven ? List.of(driver.selection()) :
                filter.talkgroupSelections,false,filter.radioRole);
        List<String> channels = new ArrayList<>();
        List<Object> channelParameters = new ArrayList<>();
        if(!channelDriven && !(groupDriven && filter.targetsAny) && !filter.channelIds.isEmpty())
        {
            channels.add("c.channel_id IN(SELECT id FROM recording_channel WHERE channel_uuid IN(" +
                String.join(",",java.util.Collections.nCopies(filter.channelIds.size(),"?")) + "))");
            channelParameters.addAll(filter.channelIds);
        }
        if(filter.targetsAny && !group.isEmpty() && !channels.isEmpty())
            conditions.add("(" + String.join(" AND ",group) + " OR " + String.join(" AND ",channels) + ")");
        else { conditions.addAll(group); conditions.addAll(channels); }
        parameters.addAll(groupParameters); parameters.addAll(channelParameters);
    }

    private static void addNumericIdentityPredicates(List<String> conditions, List<Object> parameters,
                                                      SearchFilter filter)
    {
        if(filter.talkgroupId != null)
            conditions.add(numericIdentityPredicate(parameters, filter.talkgroupId, null, true, false, false));
        if(filter.talkgroupMin != null)
            conditions.add(numericIdentityPredicate(parameters, filter.talkgroupMin, filter.talkgroupMax,
                true, false, false));
        if(filter.sourceId != null)
            conditions.add(numericIdentityPredicate(parameters, filter.sourceId, null, false, true,
                "source".equals(filter.radioRole)));
        if(filter.sourceMin != null)
            conditions.add(numericIdentityPredicate(parameters, filter.sourceMin, filter.sourceMax, false, true,
                "source".equals(filter.radioRole)));
        List<Integer> ids = new ArrayList<>(filter.anyIdentityIds);
        if(filter.anyIdentityId != null && !ids.contains(filter.anyIdentityId)) ids.add(filter.anyIdentityId);
        if(!ids.isEmpty())
        {
            List<String> choices = new ArrayList<>();
            for(int id: ids) choices.add(numericIdentityPredicate(parameters,id,null,false,false,false));
            conditions.add("(" + String.join(" OR ",choices) + ")");
        }
    }

    private static String numericIdentityPredicate(List<Object> parameters, int first, Integer last,
                                                    boolean group, boolean radio, boolean sourceOnly)
    {
        List<String> choices = new ArrayList<>();
        String comparison = last == null ? "=?" : " BETWEEN ? AND ?";
        if(!group)
        {
            choices.add("c.source_id" + comparison);
            parameters.add(first); if(last != null) parameters.add(last);
        }
        if(!sourceOnly)
        {
            choices.add("(" + (group ? "c.call_type IN(1,2) AND " : radio ? "c.call_type=3 AND " : "") +
                "c.target_id" + comparison + ")");
            parameters.add(first); if(last != null) parameters.add(last);
            choices.add("EXISTS(SELECT 1 FROM recording_patch_member identity_member WHERE " +
                "identity_member.call_id=c.id AND " + (group ? "identity_member.kind=1 AND " :
                    radio ? "identity_member.kind=2 AND " : "") + "identity_member.local_id" + comparison + ")");
            parameters.add(first); if(last != null) parameters.add(last);
        }
        return "(" + String.join(" OR ",choices) + ")";
    }

    /** A receiving channel may retain identities from several primary protocol namespaces over time. */
    private record SelectionScope(String systemKey, String protocol) {}

    private static void addSelectionPredicate(List<String> conditions, List<Object> parameters,
                                              List<Selection> selections, boolean radio, String role)
    {
        if(selections.isEmpty()) return;
        Map<SelectionScope,List<Selection>> bySystem = new LinkedHashMap<>();
        for(Selection selection: selections)
            bySystem.computeIfAbsent(new SelectionScope(selection.systemKey(),
                selection.value() == null ? selection.protocol() : null),ignored -> new ArrayList<>()).add(selection);
        List<String> choices = new ArrayList<>();
        for(var entry: bySystem.entrySet())
        {
            List<String> scoped = new ArrayList<>();
            if(entry.getKey().systemKey() == null) scoped.add(unknownSystemPredicate());
            else addSystemPredicate(scoped,parameters,entry.getKey().systemKey(),
                "c.system_id=(SELECT id FROM recording_system WHERE system_key=?)");
            if(entry.getKey().protocol() != null)
            {
                int code = protocolFilter(entry.getKey().protocol());
                if(code == 1 || code == 2) scoped.add("c.protocol IN(1,2)");
                else { scoped.add("c.protocol=?"); parameters.add(code); }
            }
            if(!radio) scoped.add("c.protocol IN(1,2,3,4)");
            List<Integer> rawIds = new ArrayList<>();
            List<RadioSystemIdentityKey.Identity> homes = new ArrayList<>();
            for(Selection selection: entry.getValue())
                if(selection.value() != null) continue;
                else if(selection.identityKey() == null) rawIds.add(selection.id());
                else homes.add(RadioSystemIdentityKey.parse(selection.identityKey()));
            List<String> roles = new ArrayList<>();
            if(radio && (!rawIds.isEmpty() || !homes.isEmpty()))
                roles.add(selectionValue(parameters,"c.source_id","c.source_home_wacn",
                    "c.source_home_system","c.source_home_id",rawIds,homes));
            if((!rawIds.isEmpty() || !homes.isEmpty()) && (!radio || !"source".equals(role)))
            {
                roles.add("(" + (radio ? "c.call_type=3" : "c.call_type IN(1,2)") + " AND " +
                    selectionValue(parameters,"c.target_id","c.target_home_wacn",
                        "c.target_home_system","c.target_home_id",rawIds,homes) + ")");
                roles.add("EXISTS(SELECT 1 FROM recording_patch_member selected_member WHERE " +
                    "selected_member.call_id=c.id AND selected_member.kind=" + (radio ? 2 : 1) + " AND " +
                    selectionValue(parameters,"selected_member.local_id","nullif(selected_member.home_wacn,-1)",
                        "nullif(selected_member.home_system,-1)","nullif(selected_member.home_id,-1)",rawIds,homes) + ")");
            }
            List<String> auxiliary = new ArrayList<>();
            for(Selection selection: entry.getValue())
            {
                if(selection.value() == null) continue;
                auxiliary.add("(selected_identifier.protocol=? AND selected_identifier.value=?)");
                parameters.add(selection.protocol()); parameters.add(selection.value());
            }
            if(!auxiliary.isEmpty())
                roles.add("EXISTS(SELECT 1 FROM recording_identifier selected_identifier WHERE " +
                    "selected_identifier.call_id=c.id AND selected_identifier.kind='radio'" +
                    ("source".equals(role) ? " AND selected_identifier.role='source'" : "") +
                    " AND (" + String.join(" OR ",auxiliary) + "))");
            scoped.add("(" + String.join(" OR ",roles) + ")");
            choices.add("(" + String.join(" AND ",scoped) + ")");
        }
        conditions.add("(" + String.join(" OR ",choices) + ")");
    }

    /** Keep each receiving scope once, with compact local-number and canonical-home membership probes. */
    private static String selectionValue(List<Object> parameters, String local, String homeWacn,
                                          String homeSystem, String homeId, List<Integer> rawIds,
                                          List<RadioSystemIdentityKey.Identity> homes)
    {
        List<String> choices = new ArrayList<>();
        if(!rawIds.isEmpty())
        {
            choices.add("(" + homeWacn + " IS NULL AND " + homeSystem + " IS NULL AND " + homeId +
                " IS NULL AND " + local + " IN(" + String.join(",",java.util.Collections.nCopies(rawIds.size(),"?")) + "))");
            parameters.addAll(rawIds);
        }
        if(!homes.isEmpty())
        {
            choices.add("(c.protocol IN(1,2) AND (" + homeWacn + "," + homeSystem + ",coalesce(" + homeId + "," +
                local + ")) IN(" + String.join(",",java.util.Collections.nCopies(homes.size(),"(?,?,?)")) + "))");
            for(RadioSystemIdentityKey.Identity identity: homes)
            {
                parameters.add(identity.homeWacn()); parameters.add(identity.homeSystemId());
                parameters.add(identity.identityId());
            }
        }
        return "(" + String.join(" OR ",choices) + ")";
    }

    /** Same unresolved routing rule as mapCall, without borrowing a system from disagreeing observed sites. */
    private static String unknownSystemPredicate()
    {
        return "(c.system_id IS NULL AND (c.protocol NOT IN(1,2) OR (c.winner_site_id IS NULL AND (" +
            "NOT EXISTS(SELECT 1 FROM recording_call_site unknown_site WHERE unknown_site.call_id=c.id) OR " +
            "EXISTS(SELECT 1 FROM recording_call_site ua JOIN recording_site sa ON sa.id=ua.site_id " +
            "JOIN recording_call_site ub ON ub.call_id=ua.call_id JOIN recording_site sb ON sb.id=ub.site_id " +
            "WHERE ua.call_id=c.id AND (sa.wacn<>sb.wacn OR sa.system_id<>sb.system_id))))))";
    }

    private static void addTonePredicate(List<String> conditions, List<Object> parameters, SearchFilter filter)
    {
        if(filter.toneFrequencyHz != null)
        {
            String first = toneFrequencyPredicate(parameters,"tone_a",filter.toneFrequencyHz,filter.toneToleranceHz);
            if(filter.toneThenHz == null)
                conditions.add("EXISTS(SELECT 1 FROM recording_tone tone_a WHERE tone_a.call_id=c.id AND " + first + ")");
            else
            {
                String next = toneFrequencyPredicate(parameters,"tone_b",filter.toneThenHz,filter.toneToleranceHz);
                conditions.add("EXISTS(SELECT 1 FROM recording_tone tone_a JOIN recording_tone tone_b " +
                    "ON tone_b.call_id=tone_a.call_id AND tone_b.ordinal=tone_a.ordinal+1 " +
                    "WHERE tone_a.call_id=c.id AND " + first + " AND " + next + ")");
            }
        }
        else if(filter.pagingTones != null)
            conditions.add("EXISTS(SELECT 1 FROM recording_tone_sequence tone_sequence WHERE tone_sequence.call_id=c.id)");
    }

    private static String toneFrequencyPredicate(List<Object> parameters, String alias, double frequency, double tolerance)
    {
        long low = Math.max(1L,(long)Math.ceil((frequency-tolerance)*1000));
        long high = Math.min(8_000_000L,(long)Math.floor((frequency+tolerance)*1000));
        parameters.add(low); parameters.add(high); parameters.add(low); parameters.add(high);
        return "(" + alias + ".frequency_1_millihz BETWEEN ? AND ? OR " + alias + ".frequency_2_millihz BETWEEN ? AND ?)";
    }

    private static void addTranscriptPredicate(List<String> conditions, List<Object> parameters, String text)
    {
        if(text != null)
        {
            conditions.add("c.id IN(SELECT rowid FROM recording_transcript_fts WHERE recording_transcript_fts MATCH ?)");
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

    /** One covering-index seek per requested source, with an independent bound before combining the results. */
    Map<String,List<Long>> recordingIdsBySourceCallReferences(List<String> references, int limitPerSource)
        throws SQLException
    {
        return recordingIdsBySourceCallReferences(references, limitPerSource, 0);
    }

    /** Optional availability reads share their page's short deadline instead of waiting behind maintenance. */
    Map<String,List<Long>> recordingIdsBySourceCallReferences(List<String> references, int limitPerSource,
                                                              long deadlineNanos) throws SQLException
    {
        List<String> sources = ManagedRecordingSourceReferences.copy(references, ManagedRecordingCatalog.MAX_PAGE_SIZE);
        if(sources.isEmpty()) return Map.of();
        int limit = Math.max(1, Math.min(ManagedRecordingCatalog.MAX_PAGE_SIZE, limitPerSource));
        Map<String,List<Long>> matches = new LinkedHashMap<>();
        List<String> branches = new ArrayList<>();
        for(String source: sources)
        {
            matches.put(source, new ArrayList<>());
            branches.add("SELECT * FROM (SELECT source_call_uuid,call_id FROM recording_source " +
                "WHERE source_call_uuid=? ORDER BY call_id LIMIT ?)");
        }
        boolean bounded = deadlineNanos != 0;
        if(bounded) requireSourceLookupBudget(deadlineNanos);
        int busyTimeout = bounded ? (int)Math.max(1, Math.min(100,
            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()))) : 5000;
        try(Connection connection = openReader(busyTimeout))
        {
            if(bounded) org.sqlite.ProgressHandler.setHandler(connection, 1000, new org.sqlite.ProgressHandler()
            {
                @Override protected int progress()
                {
                    return Thread.currentThread().isInterrupted() || System.nanoTime() >= deadlineNanos ? 1 : 0;
                }
            });
            try(PreparedStatement statement = connection.prepareStatement(String.join(" UNION ALL ", branches)))
            {
                if(bounded) requireSourceLookupBudget(deadlineNanos);
                int parameter = 1;
                for(String source: sources)
                {
                    statement.setString(parameter++, source);
                    statement.setInt(parameter++, limit);
                }
                try(ResultSet rows = statement.executeQuery())
                {
                    while(rows.next()) matches.get(rows.getString(1)).add(rows.getLong(2));
                }
                if(bounded) requireSourceLookupBudget(deadlineNanos);
            }
            finally
            {
                if(bounded) org.sqlite.ProgressHandler.clearHandler(connection);
            }
        }
        matches.replaceAll((source, ids) -> List.copyOf(ids));
        return java.util.Collections.unmodifiableMap(matches);
    }

    private static void requireSourceLookupBudget(long deadlineNanos) throws SQLException
    {
        if(Thread.currentThread().isInterrupted() || System.nanoTime() >= deadlineNanos)
            throw new SQLException("Recording source lookup exceeded its page budget");
    }

    /** Bounded exact receiver-leg references retained with one recording, independently of Activity retention. */
    List<String> sourceCallReferences(long recordingId) throws SQLException
    {
        if(recordingId <= 0) return List.of();
        try(Connection connection = openReader(); PreparedStatement statement = connection.prepareStatement(
            "SELECT source_call_uuid FROM recording_source WHERE call_id=? ORDER BY source_call_uuid LIMIT 128"))
        {
            statement.setLong(1, recordingId);
            List<String> references = new ArrayList<>();
            try(ResultSet rows = statement.executeQuery())
            { while(rows.next()) references.add(rows.getString(1)); }
            return List.copyOf(references);
        }
    }

    /** The first retained parts in recording-ID order; every candidate list is independently bounded. */
    List<RecordingCall> findBySourceCallReferences(List<String> references, int requestedLimit) throws SQLException
    {
        int limit = Math.max(1, Math.min(ManagedRecordingCatalog.MAX_PAGE_SIZE, requestedLimit));
        List<Long> ids = recordingIdsBySourceCallReferences(references, limit).values().stream()
            .flatMap(List::stream).distinct().sorted().limit(limit).toList();
        if(ids.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        try(Connection connection = openReader(); PreparedStatement statement = connection.prepareStatement(
            SELECT_CALL + "WHERE c.id IN (" + placeholders + ") ORDER BY c.start_ms,c.id"))
        {
            int parameter = 1;
            for(long id: ids) statement.setLong(parameter++, id);
            List<RecordingCall> calls = new ArrayList<>();
            try(ResultSet rows = statement.executeQuery())
            { while(rows.next()) calls.add(mapCall(connection, rows)); }
            return List.copyOf(calls);
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
        String servingSystemKey = isP25Protocol(protocol) && RadioSystemKey.isP25Native(systemKey) &&
            winner != null && systemKey.equals(RadioSystemKey.p25(winner.wacn(), winner.systemId())) ?
            systemKey : null;
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
            List.copyOf(members), servingSystemKey, ManagedRecordingTones.read(connection, id),
            readAuxiliaryIdentifiers(connection,id));
    }

    private static List<AuxiliaryIdentifier> readAuxiliaryIdentifiers(Connection connection, long callId) throws SQLException
    {
        List<AuxiliaryIdentifier> identifiers = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT protocol,kind,role,value FROM recording_identifier WHERE call_id=? ORDER BY protocol,kind,role,value"))
        {
            statement.setLong(1,callId);
            try(ResultSet rows = statement.executeQuery())
            {
                while(rows.next()) identifiers.add(new AuxiliaryIdentifier(rows.getString(1),rows.getString(2),
                    rows.getString(3),rows.getString(4)));
            }
        }
        return List.copyOf(identifiers);
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
        return ManagedRecordingCatalog.protocolFilterCode(protocol);
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
