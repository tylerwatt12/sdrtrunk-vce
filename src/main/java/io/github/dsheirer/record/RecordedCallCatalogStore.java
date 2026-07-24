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
package io.github.dsheirer.record;

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallRecordingMetadata;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Synchronous SQLite operations used only by the catalog's background worker and bounded web readers.
 */
final class RecordedCallCatalogStore
{
    static final int FLAG_ENCRYPTED = 1;
    static final int FLAG_RECORD_ELIGIBLE = 1 << 1;
    static final int FORMAT_WAVE = 1;
    static final int FORMAT_MP3 = 2;
    static final int MAXIMUM_FACET_PAGE_SIZE = 200;
    static final String RETENTION_OLDEST_SQL = """
        SELECT c.producer_id, c.call_sequence, c.timeslot, c.completed_at_ms, c.format_code,
               c.byte_size, b.relative_directory
        FROM recorded_call AS c
        JOIN recorded_call_bucket AS b ON b.id = c.bucket_id
        ORDER BY c.completed_at_ms, c.producer_id, c.call_sequence, c.timeslot
        LIMIT ?
        """;
    static final String RETENTION_AFTER_SQL = """
        SELECT c.producer_id, c.call_sequence, c.timeslot, c.completed_at_ms, c.format_code,
               c.byte_size, b.relative_directory
        FROM recorded_call AS c
        JOIN recorded_call_bucket AS b ON b.id = c.bucket_id
        WHERE (c.completed_at_ms, c.producer_id, c.call_sequence, c.timeslot) > (?, ?, ?, ?)
        ORDER BY c.completed_at_ms, c.producer_id, c.call_sequence, c.timeslot
        LIMIT ?
        """;
    private static final long SELECTIVE_DURATION_WINDOW_MS = 2_000;

    private final Path mRecordingRoot;

    RecordedCallCatalogStore(Path recordingRoot)
    {
        mRecordingRoot = ManagedRecordingPath.prepareRoot(recordingRoot);
    }

    AdmissionResult admit(Connection connection, RecordedCallArtifact artifact) throws IOException, SQLException
    {
        Objects.requireNonNull(connection, "SQLite connection cannot be null");
        PreparedAdmission prepared = prepare(artifact);
        return prepared.result() != null ? prepared.result() : admit(connection, prepared);
    }

    PreparedAdmission prepare(RecordedCallArtifact artifact) throws IOException
    {
        if(artifact == null ||
            artifact.destinationTalkgroupRecordEnabled() !=
                artifact.metadata().destinationTalkgroupRecordEnabled())
        {
            return new PreparedAdmission(AdmissionResult.INVALID_ARTIFACT, null, null);
        }

        Optional<ManagedRecordingPath> inspected = ManagedRecordingPath.inspect(mRecordingRoot, artifact.path());

        if(inspected.isEmpty() || !inspected.get().relativePath().equals(artifact.relativePath()) ||
            inspected.get().completedAtMs() != artifact.completedAtMs() ||
            inspected.get().format() != artifact.format() ||
            !expectedCallIdentity(artifact.callId()).equals(inspected.get().callIdentity()) ||
            Files.size(artifact.path()) != artifact.byteSize())
        {
            return new PreparedAdmission(AdmissionResult.INVALID_ARTIFACT, null, null);
        }

        return new PreparedAdmission(null, artifact, inspected.get());
    }

    /**
     * Validates an uncataloged completed file using its bounded embedded manifest.  A valid CRC is not sufficient:
     * the manifest identity, completion timestamp, eligibility decision, format, and canonical path must all agree.
     */
    PreparedAdmission prepareRecovered(Path path) throws IOException
    {
        if(path == null)
        {
            return new PreparedAdmission(AdmissionResult.INVALID_ARTIFACT, null, null);
        }

        Path normalized = path.toAbsolutePath().normalize();
        Optional<ManagedRecordingPath> inspected = ManagedRecordingPath.inspect(mRecordingRoot, normalized);

        if(inspected.isEmpty())
        {
            return new PreparedAdmission(AdmissionResult.INVALID_ARTIFACT, null, null);
        }

        ManagedRecordingPath managedPath = inspected.get();
        Optional<RecordedCallManifest> recovered =
            RecordedCallManifest.readFromAudioFile(normalized, managedPath.format());

        if(recovered.isEmpty())
        {
            return new PreparedAdmission(AdmissionResult.INVALID_ARTIFACT, null, null);
        }

        RecordedCallManifest manifest = recovered.get();
        String expectedIdentity = expectedCallIdentity(manifest.callId());

        if(manifest.recordEligible() != manifest.metadata().destinationTalkgroupRecordEnabled() ||
            manifest.completedAtMs() != managedPath.completedAtMs() ||
            !expectedIdentity.equals(managedPath.callIdentity()) ||
            !ManagedRecordingPath.fileName(manifest.callId(), manifest.completedAtMs(), managedPath.format())
                .equals(managedPath.relativePath().getFileName().toString()))
        {
            return new PreparedAdmission(AdmissionResult.INVALID_ARTIFACT, null, null);
        }

        RecordedCallArtifact artifact;

        try
        {
            artifact = new RecordedCallArtifact(normalized, managedPath.relativePath(), managedPath.format(),
                Files.size(normalized), manifest.callId(), manifest.metadata(), manifest.startAtMs(),
                manifest.completedAtMs(), manifest.durationMs(), manifest.encrypted(),
                manifest.recordEligible());
        }
        catch(IllegalArgumentException exception)
        {
            return new PreparedAdmission(AdmissionResult.INVALID_ARTIFACT, null, null);
        }

        return new PreparedAdmission(null, artifact, managedPath);
    }

    AdmissionResult admit(Connection connection, PreparedAdmission prepared) throws SQLException
    {
        Objects.requireNonNull(connection, "SQLite connection cannot be null");
        Objects.requireNonNull(prepared, "Prepared recorded-call admission cannot be null");

        if(prepared.result() != null)
        {
            return prepared.result();
        }

        RecordedCallArtifact artifact = prepared.artifact();

        if(callExists(connection, artifact.completedAtMs(), artifact.callId()))
        {
            return AdmissionResult.DUPLICATE;
        }

        AudioCallRecordingMetadata metadata = artifact.metadata();
        String systemKey = catalogKey(firstText(metadata.systemIdentity(), metadata.systemName()));
        String siteKey = catalogKey(firstText(metadata.siteIdentity(), metadata.siteName()));
        String channelKey = catalogKey(firstText(metadata.channelIdentity(), metadata.channelName()));
        String talkgroupKey = scopedKey(systemKey,
            protocolValue(metadata.destinationProtocol(), metadata.destinationValue()));
        String sourceRadioKey = scopedKey(systemKey,
            protocolValue(metadata.sourceProtocol(), metadata.sourceValue()));
        ManagedRecordingPath parsed = prepared.path();
        long bucketId = bucket(connection, parsed.date(),
            RecordedCallCatalogPaths.portableDirectory(parsed.relativeDirectory()), systemKey,
            boundedNullableLabel(metadata.systemName()), siteKey, boundedNullableLabel(metadata.siteName()),
            channelKey, boundedNullableLabel(metadata.channelName()), talkgroupKey,
            boundedNullableLabel(firstText(metadata.destinationAlias(), metadata.destinationValue())));
        int flags = (artifact.destinationTalkgroupRecordEnabled() ? FLAG_RECORD_ELIGIBLE : 0) |
            (artifact.encrypted() ? FLAG_ENCRYPTED : 0);

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT OR IGNORE INTO recorded_call (
                producer_id, call_sequence, timeslot, completed_at_ms, start_at_ms, duration_ms, byte_size,
                format_code, flags, bucket_id, source_radio_key
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """))
        {
            AudioCallId callId = artifact.callId();
            statement.setLong(1, callId.producerId());
            statement.setLong(2, callId.sequence());
            statement.setInt(3, callId.timeslot());
            statement.setLong(4, artifact.completedAtMs());
            statement.setLong(5, artifact.startAtMs());
            statement.setLong(6, artifact.durationMs());
            statement.setLong(7, artifact.byteSize());
            statement.setInt(8, formatCode(artifact.format()));
            statement.setInt(9, flags);
            statement.setLong(10, bucketId);
            statement.setString(11, sourceRadioKey);
            return statement.executeUpdate() == 1 ? AdmissionResult.INSERTED : AdmissionResult.DUPLICATE;
        }
    }

    RecordedCallCatalogPage search(Connection connection, RecordedCallCatalogSearch search) throws SQLException
    {
        Objects.requireNonNull(connection, "SQLite connection cannot be null");
        Objects.requireNonNull(search, "Recorded-call search cannot be null");
        SearchStatement query = buildSearchStatement(search);
        List<RecordedCallCatalogEntry> calls = new ArrayList<>(search.pageSize() + 1);

        try(PreparedStatement statement = connection.prepareStatement(query.sql()))
        {
            bind(statement, query.parameters());

            try(ResultSet resultSet = statement.executeQuery())
            {
                while(resultSet.next())
                {
                    calls.add(entry(resultSet));
                }
            }
        }

        boolean hasMore = calls.size() > search.pageSize();

        if(hasMore)
        {
            calls.remove(calls.size() - 1);
        }

        RecordedCallCatalogSearch.Cursor cursor =
            hasMore && !calls.isEmpty() ? calls.get(calls.size() - 1).cursor() : null;
        return new RecordedCallCatalogPage(calls, cursor);
    }

    /**
     * Builds the exact public search statement used at runtime. Package visibility allows representative-volume tests
     * to EXPLAIN this statement instead of testing a simplified SQL surrogate.
     */
    static SearchStatement buildSearchStatement(RecordedCallCatalogSearch search)
    {
        Objects.requireNonNull(search, "Recorded-call search cannot be null");
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
            SELECT c.producer_id, c.call_sequence, c.timeslot, c.completed_at_ms, c.start_at_ms,
                   c.duration_ms, c.byte_size, c.format_code, c.flags, b.relative_directory,
                   b.system_key, b.system_label, b.site_key, b.site_label,
                   b.channel_key, b.channel_label, b.talkgroup_key, b.talkgroup_label,
                   c.source_radio_key
            FROM recorded_call AS c
            """);

        if(search.sourceRadioKey() != null)
        {
            sql.append(" INDEXED BY ").append(RecordedCallCatalogSchema.RADIO_TIME_INDEX);
        }
        else if(bucketSearchIndex(search) == null && isSelectiveDurationSearch(search))
        {
            //A narrow duration range can be sparse anywhere inside a long time window. Start with the duration index
            //instead of walking the complete time range. Broad/default ranges retain primary-key time order so the
            //page limit stops the query quickly without sorting a large duration result set.
            sql.append(" INDEXED BY ").append(RecordedCallCatalogSchema.DURATION_TIME_INDEX);
        }

        sql.append("\nJOIN recorded_call_bucket AS b");
        String bucketSearchIndex = bucketSearchIndex(search);

        if(bucketSearchIndex != null)
        {
            sql.append(" INDEXED BY ").append(bucketSearchIndex);
        }

        sql.append("""
             ON b.id = c.bucket_id
            WHERE c.completed_at_ms >= ? AND c.completed_at_ms < ?
              AND c.duration_ms >= ? AND c.duration_ms <= ?
            """);
        sql.append(" AND (c.flags & ").append(FLAG_RECORD_ELIGIBLE).append(") != 0\n");
        parameters.add(search.fromInclusiveMs());
        parameters.add(search.toExclusiveMs());
        parameters.add(search.minimumDurationMs());
        parameters.add(search.maximumDurationMs());

        if(bucketSearchIndex != null)
        {
            sql.append(" AND b.day_utc >= ? AND b.day_utc <= ?\n");
            parameters.add(Math.floorDiv(search.fromInclusiveMs(), 86_400_000L));
            parameters.add(Math.floorDiv(search.toExclusiveMs() - 1, 86_400_000L));
        }

        appendKey(sql, parameters, "b.system_key", search.systemKey());
        appendKey(sql, parameters, "b.site_key", search.siteKey());
        appendKey(sql, parameters, "b.talkgroup_key", search.talkgroupKey());
        appendKey(sql, parameters, "b.channel_key", search.channelKey());
        appendKey(sql, parameters, "c.source_radio_key", search.sourceRadioKey());

        if(search.before() != null)
        {
            sql.append("""
                 AND (c.completed_at_ms, c.producer_id, c.call_sequence, c.timeslot) < (?, ?, ?, ?)
                """);
            RecordedCallCatalogTokens.CursorValues cursor = search.before().values();
            parameters.add(cursor.completedAtMs());
            parameters.add(cursor.callId().producerId());
            parameters.add(cursor.callId().sequence());
            parameters.add(cursor.callId().timeslot());
        }

        sql.append("""
             ORDER BY c.completed_at_ms DESC, c.producer_id DESC, c.call_sequence DESC, c.timeslot DESC
             LIMIT ?
            """);
        parameters.add(search.pageSize() + 1);
        return new SearchStatement(sql.toString(), List.copyOf(parameters));
    }

    /**
     * Resolves media only through the same record-eligibility gate as search and facets. The returned path is
     * re-inspected beneath the canonical root so a missing, replaced, or linked file is never served.
     */
    Optional<Path> resolveMedia(Connection connection, String publicCallId) throws IOException, SQLException
    {
        Objects.requireNonNull(connection, "SQLite connection cannot be null");
        RecordedCallCatalogTokens.CursorValues values = RecordedCallCatalogTokens.parseCallId(publicCallId);
        AudioCallId callId = values.callId();

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT c.format_code, b.relative_directory
            FROM recorded_call AS c
            JOIN recorded_call_bucket AS b ON b.id = c.bucket_id
            WHERE c.completed_at_ms = ? AND c.producer_id = ? AND c.call_sequence = ? AND c.timeslot = ?
              AND (c.flags & ?) != 0
            """))
        {
            statement.setLong(1, values.completedAtMs());
            statement.setLong(2, callId.producerId());
            statement.setLong(3, callId.sequence());
            statement.setInt(4, callId.timeslot());
            statement.setInt(5, FLAG_RECORD_ELIGIBLE);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    RecordFormat format = recordFormat(resultSet.getInt("format_code"));
                    Path relative = RecordedCallCatalogPaths.relativePath(
                        resultSet.getString("relative_directory"), callId, values.completedAtMs(), format);
                    Path candidate = mRecordingRoot.resolve(relative).normalize();
                    Optional<ManagedRecordingPath> inspected = ManagedRecordingPath.inspect(mRecordingRoot, candidate);

                    if(inspected.isPresent() && inspected.get().relativePath().equals(relative))
                    {
                        return Optional.of(candidate);
                    }
                }
            }
        }

        return Optional.empty();
    }

    List<RecordedCallIdentity> listIdentities(Connection connection, RecordedCallIdentityKind kind, String scopeKey,
                                              String afterValueKey, int pageSize) throws SQLException
    {
        Objects.requireNonNull(connection, "SQLite connection cannot be null");
        Objects.requireNonNull(kind, "Recorded-call identity kind cannot be null");

        if(pageSize < 1 || pageSize > MAXIMUM_FACET_PAGE_SIZE)
        {
            throw new IllegalArgumentException("Facet page size must be between 1 and " +
                MAXIMUM_FACET_PAGE_SIZE);
        }

        String scope = nullSafe(scopeKey);
        String after = nullSafe(afterValueKey);
        validateBrowseKey(scope);
        validateBrowseKey(after);
        FacetQuery query = facetQuery(kind, scope);

        try(PreparedStatement statement = connection.prepareStatement(query.sql()))
        {
            int parameter = 1;

            if(query.hasScopeParameter())
            {
                statement.setString(parameter++, scope);
            }

            statement.setString(parameter++, after);
            statement.setInt(parameter, pageSize);
            List<RecordedCallIdentity> identities = new ArrayList<>(pageSize);

            try(ResultSet resultSet = statement.executeQuery())
            {
                while(resultSet.next())
                {
                    String value = resultSet.getString("value");
                    String returnedScope = query.returnsScope() ? nullSafe(resultSet.getString("scope")) : scope;
                    String label = resultSet.getString("label");

                    if(kind == RecordedCallIdentityKind.RADIO && (label == null || label.isBlank()))
                    {
                        label = unscopedKey(value);
                    }

                    identities.add(new RecordedCallIdentity(kind, returnedScope, value, label));
                }
            }

            return List.copyOf(identities);
        }
    }

    RetentionResult cleanupRetention(Connection connection, long cutoffMs, int batchSize)
        throws SQLException
    {
        return cleanupRetention(connection, cutoffMs, totalRetainedBytes(connection), Long.MAX_VALUE, batchSize,
            null);
    }

    /**
     * Removes one bounded oldest-first batch required by either age or the configured retained-audio byte cap.
     */
    RetentionResult cleanupRetention(Connection connection, long cutoffMs, long retainedBytes,
                                     long maximumRetainedBytes, int batchSize) throws SQLException
    {
        return cleanupRetention(connection, cutoffMs, retainedBytes, maximumRetainedBytes, batchSize, null);
    }

    /**
     * Removes one bounded oldest-first batch required by either age or the configured retained-audio byte cap,
     * continuing after the supplied keyset cursor when a previous pass encountered undeletable ownership rows.
     */
    RetentionResult cleanupRetention(Connection connection, long cutoffMs, long retainedBytes,
                                     long maximumRetainedBytes, int batchSize, RetentionCursor after)
        throws SQLException
    {
        Objects.requireNonNull(connection, "SQLite connection cannot be null");

        if(cutoffMs <= 0 || retainedBytes < 0 || maximumRetainedBytes < 1 ||
            batchSize < 1 || batchSize > 10_000)
        {
            throw new IllegalArgumentException("Recorded-call retention cleanup bounds are invalid");
        }

        List<ExpiredCall> oldest = oldestCallsAfter(connection, after, batchSize + 1);
        List<ExpiredCall> removable = new ArrayList<>(Math.min(batchSize, oldest.size()));
        int filesDeleted = 0;
        int filesMissing = 0;
        int fileFailures = 0;
        int candidates = 0;
        long retainedAfterCleanup = retainedBytes;
        RetentionCursor lastProcessed = after;

        for(int index = 0; index < oldest.size() && candidates < batchSize; index++)
        {
            ExpiredCall call = oldest.get(index);

            if(call.completedAtMs() >= cutoffMs && retainedAfterCleanup <= maximumRetainedBytes)
            {
                break;
            }

            candidates++;
            lastProcessed = RetentionCursor.from(call);

            try
            {
                Path relativePath = RecordedCallCatalogPaths.relativePath(call.relativeDirectory(), call.callId(),
                    call.completedAtMs(), call.format());
                DeleteResult result = deleteManagedFile(relativePath);

                if(result == DeleteResult.DELETED)
                {
                    filesDeleted++;
                    removable.add(call);
                    retainedAfterCleanup = Math.max(0, retainedAfterCleanup - call.byteSize());
                }
                else if(result == DeleteResult.MISSING)
                {
                    filesMissing++;
                    removable.add(call);
                    retainedAfterCleanup = Math.max(0, retainedAfterCleanup - call.byteSize());
                }
                else
                {
                    //An unsafe path/root is not proof that the managed file is gone. Keep its ownership row so a
                    //temporary permission problem or a replaced directory cannot orphan retained audio forever.
                    fileFailures++;
                }
            }
            catch(IOException exception)
            {
                fileFailures++;
            }
            catch(IllegalArgumentException exception)
            {
                fileFailures++;
            }
        }

        int rowsDeleted = deleteCalls(connection, removable);
        long bytesRemoved = removable.stream().mapToLong(ExpiredCall::byteSize).sum();
        int bucketsDeleted = deleteOrphanBuckets(connection, batchSize);
        boolean moreWork = false;

        if(candidates == batchSize && oldest.size() > batchSize)
        {
            ExpiredCall next = oldest.get(batchSize);
            moreWork = next.completedAtMs() < cutoffMs || retainedAfterCleanup > maximumRetainedBytes;
        }

        return new RetentionResult(candidates, filesDeleted, filesMissing, fileFailures, rowsDeleted,
            bytesRemoved, bucketsDeleted, moreWork, moreWork ? lastProcessed : null);
    }

    long totalRetainedBytes(Connection connection) throws SQLException
    {
        Objects.requireNonNull(connection, "SQLite connection cannot be null");

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COALESCE(SUM(byte_size), 0) FROM recorded_call"))
        {
            return resultSet.next() ? resultSet.getLong(1) : 0;
        }
    }

    private List<ExpiredCall> oldestCallsAfter(Connection connection, RetentionCursor after, int batchSize)
        throws SQLException
    {
        String sql = after == null ? RETENTION_OLDEST_SQL : RETENTION_AFTER_SQL;

        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            if(after == null)
            {
                statement.setInt(1, batchSize);
            }
            else
            {
                statement.setLong(1, after.completedAtMs());
                statement.setLong(2, after.producerId());
                statement.setLong(3, after.callSequence());
                statement.setInt(4, after.timeslot());
                statement.setInt(5, batchSize);
            }

            List<ExpiredCall> calls = new ArrayList<>(batchSize);

            try(ResultSet resultSet = statement.executeQuery())
            {
                while(resultSet.next())
                {
                    calls.add(new ExpiredCall(new AudioCallId(resultSet.getLong("producer_id"),
                        resultSet.getLong("call_sequence"), resultSet.getInt("timeslot")),
                        resultSet.getLong("completed_at_ms"), recordFormat(resultSet.getInt("format_code")),
                        resultSet.getString("relative_directory"), resultSet.getLong("byte_size")));
                }
            }

            return calls;
        }
    }

    private int deleteCalls(Connection connection, List<ExpiredCall> calls) throws SQLException
    {
        if(calls.isEmpty())
        {
            return 0;
        }

        int deleted = 0;

        try(PreparedStatement statement = connection.prepareStatement("""
            DELETE FROM recorded_call
            WHERE completed_at_ms = ? AND producer_id = ? AND call_sequence = ? AND timeslot = ?
            """))
        {
            for(ExpiredCall expired: calls)
            {
                AudioCallId call = expired.callId();
                statement.setLong(1, expired.completedAtMs());
                statement.setLong(2, call.producerId());
                statement.setLong(3, call.sequence());
                statement.setInt(4, call.timeslot());
                statement.addBatch();
            }

            for(int result: statement.executeBatch())
            {
                if(result > 0)
                {
                    deleted += result;
                }
                else if(result == Statement.SUCCESS_NO_INFO)
                {
                    deleted++;
                }
            }
        }

        return deleted;
    }

    private int deleteOrphanBuckets(Connection connection, int limit) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            DELETE FROM recorded_call_bucket
            WHERE id IN (
                SELECT b.id
                FROM recorded_call_bucket AS b
                WHERE NOT EXISTS (
                    SELECT 1 FROM recorded_call AS c INDEXED BY idx_recorded_call_bucket_time
                    WHERE c.bucket_id = b.id
                )
                ORDER BY b.id
                LIMIT ?
            )
            """))
        {
            statement.setInt(1, limit);
            return statement.executeUpdate();
        }
    }

    private DeleteResult deleteManagedFile(Path relativePath) throws IOException
    {
        if(relativePath == null || relativePath.isAbsolute() || !relativePath.normalize().equals(relativePath) ||
            ManagedRecordingPath.parse(relativePath).isEmpty())
        {
            return DeleteResult.UNSAFE;
        }

        Path root = mRecordingRoot;

        if(!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))
        {
            return DeleteResult.UNSAFE;
        }

        Path current = root;
        Path parent = relativePath.getParent();

        for(Path component: parent)
        {
            current = current.resolve(component);

            try
            {
                BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);

                if(attributes.isSymbolicLink() || !attributes.isDirectory())
                {
                    return DeleteResult.UNSAFE;
                }
            }
            catch(NoSuchFileException exception)
            {
                return DeleteResult.MISSING;
            }
        }

        Path target = root.resolve(relativePath).normalize();

        if(!target.startsWith(root))
        {
            return DeleteResult.UNSAFE;
        }

        try
        {
            BasicFileAttributes attributes = Files.readAttributes(target, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);

            if(!attributes.isRegularFile() && !attributes.isSymbolicLink())
            {
                return DeleteResult.UNSAFE;
            }

            Files.delete(target);
            deleteEmptyParents(target.getParent());
            return DeleteResult.DELETED;
        }
        catch(NoSuchFileException exception)
        {
            deleteEmptyParents(target.getParent());
            return DeleteResult.MISSING;
        }
    }

    private void deleteEmptyParents(Path start)
    {
        Path boundary = mRecordingRoot.resolve(ManagedCallRecording.MANAGED_DIRECTORY)
            .resolve(ManagedCallRecording.LAYOUT_VERSION).normalize();
        Path current = start;

        while(current != null && current.startsWith(boundary) && !current.equals(boundary))
        {
            try
            {
                Files.delete(current);
            }
            catch(IOException exception)
            {
                return;
            }

            current = current.getParent();
        }
    }

    private RecordedCallCatalogEntry entry(ResultSet resultSet) throws SQLException
    {
        AudioCallId callId = new AudioCallId(resultSet.getLong("producer_id"),
            resultSet.getLong("call_sequence"), resultSet.getInt("timeslot"));
        long completedAtMs = resultSet.getLong("completed_at_ms");
        RecordFormat format = recordFormat(resultSet.getInt("format_code"));
        Path relativePath;

        try
        {
            relativePath = RecordedCallCatalogPaths.relativePath(resultSet.getString("relative_directory"),
                callId, completedAtMs, format);
        }
        catch(IllegalArgumentException exception)
        {
            throw new SQLException("Recorded-call catalog contains an invalid managed path", exception);
        }

        return new RecordedCallCatalogEntry(RecordedCallCatalogTokens.callId(completedAtMs, callId), completedAtMs,
            resultSet.getLong("start_at_ms"),
            resultSet.getLong("duration_ms"), resultSet.getLong("byte_size"), format,
            (resultSet.getInt("flags") & FLAG_ENCRYPTED) != 0, relativePath,
            identity(RecordedCallIdentityKind.SYSTEM, "", resultSet.getString("system_key"),
                resultSet.getString("system_label")),
            identity(RecordedCallIdentityKind.SITE, nullSafe(resultSet.getString("system_key")),
                resultSet.getString("site_key"), resultSet.getString("site_label")),
            identity(RecordedCallIdentityKind.CHANNEL, nullSafe(resultSet.getString("site_key")),
                resultSet.getString("channel_key"), resultSet.getString("channel_label")),
            identity(RecordedCallIdentityKind.TALKGROUP, nullSafe(resultSet.getString("system_key")),
                resultSet.getString("talkgroup_key"), resultSet.getString("talkgroup_label")),
            identity(RecordedCallIdentityKind.RADIO, nullSafe(resultSet.getString("system_key")),
                resultSet.getString("source_radio_key"), unscopedKey(resultSet.getString("source_radio_key"))));
    }

    private static RecordedCallIdentity identity(RecordedCallIdentityKind kind, String scope, String value,
                                                 String label)
    {
        return value != null ? new RecordedCallIdentity(kind, scope, value, label) : null;
    }

    private static boolean callExists(Connection connection, long completedAtMs, AudioCallId callId)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM recorded_call
            WHERE completed_at_ms = ? AND producer_id = ? AND call_sequence = ? AND timeslot = ?
            """))
        {
            statement.setLong(1, completedAtMs);
            statement.setLong(2, callId.producerId());
            statement.setLong(3, callId.sequence());
            statement.setInt(4, callId.timeslot());

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }

    private static long bucket(Connection connection, LocalDate day, String relativeDirectory, String systemKey,
                               String systemLabel, String siteKey, String siteLabel, String channelKey,
                               String channelLabel, String talkgroupKey, String talkgroupLabel) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO recorded_call_bucket(
                day_utc, relative_directory, system_key, system_label, site_key, site_label,
                channel_key, channel_label, talkgroup_key, talkgroup_label
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(relative_directory) DO UPDATE SET
                system_label = COALESCE(excluded.system_label, recorded_call_bucket.system_label),
                site_label = COALESCE(excluded.site_label, recorded_call_bucket.site_label),
                channel_label = COALESCE(excluded.channel_label, recorded_call_bucket.channel_label),
                talkgroup_label = COALESCE(excluded.talkgroup_label, recorded_call_bucket.talkgroup_label)
            WHERE recorded_call_bucket.day_utc = excluded.day_utc
              AND recorded_call_bucket.system_key IS excluded.system_key
              AND recorded_call_bucket.site_key IS excluded.site_key
              AND recorded_call_bucket.channel_key IS excluded.channel_key
              AND recorded_call_bucket.talkgroup_key IS excluded.talkgroup_key
            """))
        {
            statement.setLong(1, day.toEpochDay());
            statement.setString(2, relativeDirectory);
            statement.setString(3, systemKey);
            statement.setString(4, systemLabel);
            statement.setString(5, siteKey);
            statement.setString(6, siteLabel);
            statement.setString(7, channelKey);
            statement.setString(8, channelLabel);
            statement.setString(9, talkgroupKey);
            statement.setString(10, talkgroupLabel);
            statement.executeUpdate();
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, day_utc, system_key, site_key, channel_key, talkgroup_key
            FROM recorded_call_bucket WHERE relative_directory = ?
            """))
        {
            statement.setString(1, relativeDirectory);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    if(resultSet.getLong("day_utc") != day.toEpochDay() ||
                        !Objects.equals(resultSet.getString("system_key"), systemKey) ||
                        !Objects.equals(resultSet.getString("site_key"), siteKey) ||
                        !Objects.equals(resultSet.getString("channel_key"), channelKey) ||
                        !Objects.equals(resultSet.getString("talkgroup_key"), talkgroupKey))
                    {
                        throw new SQLException("Recorded-call directory bucket hierarchy does not match");
                    }

                    return resultSet.getLong(1);
                }
            }
        }

        throw new SQLException("SQLite did not return a recorded-call directory bucket");
    }

    private static void appendKey(StringBuilder sql, List<Object> parameters, String column, String value)
    {
        if(value != null)
        {
            sql.append(" AND ").append(column).append(" = ?\n");
            parameters.add(value);
        }
    }

    private static FacetQuery facetQuery(RecordedCallIdentityKind kind, String scope)
    {
        return switch(kind)
        {
            case SYSTEM -> new FacetQuery("""
                SELECT '' AS scope, b.system_key AS value, MAX(b.system_label) AS label
                FROM recorded_call_bucket AS b INDEXED BY idx_recorded_call_bucket_system
                JOIN recorded_call AS c INDEXED BY idx_recorded_call_bucket_time ON c.bucket_id = b.id
                WHERE (c.flags & %d) != 0 AND b.system_key IS NOT NULL AND b.system_key > ?
                GROUP BY b.system_key
                ORDER BY b.system_key
                LIMIT ?
                """.formatted(FLAG_RECORD_ELIGIBLE), false, false);
            case SITE -> scope.isBlank() ? new FacetQuery("""
                SELECT MIN(b.system_key) AS scope, b.site_key AS value, MAX(b.site_label) AS label
                FROM recorded_call_bucket AS b INDEXED BY idx_recorded_call_bucket_site
                JOIN recorded_call AS c INDEXED BY idx_recorded_call_bucket_time ON c.bucket_id = b.id
                WHERE (c.flags & %d) != 0 AND b.site_key IS NOT NULL AND b.site_key > ?
                GROUP BY b.site_key
                ORDER BY b.site_key
                LIMIT ?
                """.formatted(FLAG_RECORD_ELIGIBLE), false, true) : new FacetQuery("""
                SELECT b.system_key AS scope, b.site_key AS value, MAX(b.site_label) AS label
                FROM recorded_call_bucket AS b INDEXED BY idx_recorded_call_bucket_system_site
                JOIN recorded_call AS c INDEXED BY idx_recorded_call_bucket_time ON c.bucket_id = b.id
                WHERE (c.flags & %d) != 0
                  AND b.system_key = ? AND b.site_key IS NOT NULL AND b.site_key > ?
                GROUP BY b.site_key
                ORDER BY b.site_key
                LIMIT ?
                """.formatted(FLAG_RECORD_ELIGIBLE), true, true);
            case CHANNEL -> scope.isBlank() ? new FacetQuery("""
                SELECT MIN(b.site_key) AS scope, b.channel_key AS value, MAX(b.channel_label) AS label
                FROM recorded_call_bucket AS b INDEXED BY idx_recorded_call_bucket_channel
                JOIN recorded_call AS c INDEXED BY idx_recorded_call_bucket_time ON c.bucket_id = b.id
                WHERE (c.flags & %d) != 0 AND b.channel_key IS NOT NULL AND b.channel_key > ?
                GROUP BY b.channel_key
                ORDER BY b.channel_key
                LIMIT ?
                """.formatted(FLAG_RECORD_ELIGIBLE), false, true) : new FacetQuery("""
                SELECT b.site_key AS scope, b.channel_key AS value, MAX(b.channel_label) AS label
                FROM recorded_call_bucket AS b INDEXED BY idx_recorded_call_bucket_site_channel
                JOIN recorded_call AS c INDEXED BY idx_recorded_call_bucket_time ON c.bucket_id = b.id
                WHERE (c.flags & %d) != 0
                  AND b.site_key = ? AND b.channel_key IS NOT NULL AND b.channel_key > ?
                GROUP BY b.channel_key
                ORDER BY b.channel_key
                LIMIT ?
                """.formatted(FLAG_RECORD_ELIGIBLE), true, true);
            case TALKGROUP -> scope.isBlank() ? new FacetQuery("""
                SELECT MIN(b.system_key) AS scope, b.talkgroup_key AS value, MAX(b.talkgroup_label) AS label
                FROM recorded_call_bucket AS b INDEXED BY idx_recorded_call_bucket_talkgroup_value
                JOIN recorded_call AS c INDEXED BY idx_recorded_call_bucket_time ON c.bucket_id = b.id
                WHERE (c.flags & %d) != 0 AND b.talkgroup_key IS NOT NULL AND b.talkgroup_key > ?
                GROUP BY b.talkgroup_key
                ORDER BY b.talkgroup_key
                LIMIT ?
                """.formatted(FLAG_RECORD_ELIGIBLE), false, true) : new FacetQuery("""
                SELECT b.system_key AS scope, b.talkgroup_key AS value, MAX(b.talkgroup_label) AS label
                FROM recorded_call_bucket AS b INDEXED BY idx_recorded_call_bucket_talkgroup
                JOIN recorded_call AS c INDEXED BY idx_recorded_call_bucket_time ON c.bucket_id = b.id
                WHERE (c.flags & %d) != 0
                  AND b.system_key = ? AND b.talkgroup_key IS NOT NULL AND b.talkgroup_key > ?
                GROUP BY b.talkgroup_key
                ORDER BY b.talkgroup_key
                LIMIT ?
                """.formatted(FLAG_RECORD_ELIGIBLE), true, true);
            case RADIO -> scope.isBlank() ? new FacetQuery("""
                SELECT MIN(b.system_key) AS scope, c.source_radio_key AS value, NULL AS label
                FROM recorded_call AS c INDEXED BY idx_recorded_call_radio_time
                JOIN recorded_call_bucket AS b ON b.id = c.bucket_id
                WHERE (c.flags & %d) != 0
                  AND c.source_radio_key IS NOT NULL AND c.source_radio_key > ?
                GROUP BY c.source_radio_key
                ORDER BY c.source_radio_key
                LIMIT ?
                """.formatted(FLAG_RECORD_ELIGIBLE), false, true) : new FacetQuery("""
                SELECT b.system_key AS scope, c.source_radio_key AS value, NULL AS label
                FROM recorded_call AS c INDEXED BY idx_recorded_call_radio_time
                JOIN recorded_call_bucket AS b ON b.id = c.bucket_id
                WHERE (c.flags & %d) != 0
                  AND b.system_key = ? AND c.source_radio_key IS NOT NULL AND c.source_radio_key > ?
                GROUP BY c.source_radio_key
                ORDER BY c.source_radio_key
                LIMIT ?
                """.formatted(FLAG_RECORD_ELIGIBLE), true, true);
        };
    }

    private static String bucketSearchIndex(RecordedCallCatalogSearch search)
    {
        if(search.talkgroupKey() != null)
        {
            return search.systemKey() != null ? RecordedCallCatalogSchema.BUCKET_TALKGROUP_INDEX :
                RecordedCallCatalogSchema.BUCKET_TALKGROUP_VALUE_INDEX;
        }
        else if(search.channelKey() != null)
        {
            return search.siteKey() != null ? RecordedCallCatalogSchema.BUCKET_SITE_CHANNEL_INDEX :
                RecordedCallCatalogSchema.BUCKET_CHANNEL_INDEX;
        }
        else if(search.siteKey() != null)
        {
            return search.systemKey() != null ? RecordedCallCatalogSchema.BUCKET_SYSTEM_SITE_INDEX :
                RecordedCallCatalogSchema.BUCKET_SITE_INDEX;
        }
        else if(search.systemKey() != null)
        {
            return RecordedCallCatalogSchema.BUCKET_SYSTEM_INDEX;
        }

        return null;
    }

    private static boolean isSelectiveDurationSearch(RecordedCallCatalogSearch search)
    {
        long width = search.maximumDurationMs() - search.minimumDurationMs();
        boolean constrained = search.minimumDurationMs() > 0 ||
            search.maximumDurationMs() < RecordedCallCatalogSearch.MAXIMUM_CALL_DURATION_MS;
        return constrained && width <= SELECTIVE_DURATION_WINDOW_MS;
    }

    private static void bind(PreparedStatement statement, List<Object> values) throws SQLException
    {
        for(int index = 0; index < values.size(); index++)
        {
            Object value = values.get(index);

            if(value instanceof Integer integer)
            {
                statement.setInt(index + 1, integer);
            }
            else if(value instanceof Long longValue)
            {
                statement.setLong(index + 1, longValue);
            }
            else
            {
                statement.setObject(index + 1, value);
            }
        }
    }

    private static String firstText(String primary, String fallback)
    {
        return primary != null && !primary.isBlank() ? primary :
            fallback != null && !fallback.isBlank() ? fallback : null;
    }

    private static String protocolValue(String protocol, String value)
    {
        return value != null && !value.isBlank() ? nullSafe(protocol) + ':' + value : null;
    }

    private static String catalogKey(String value)
    {
        return value == null || value.isBlank() ? null : compactKey(value);
    }

    private static String scopedKey(String scope, String value)
    {
        if(value == null || value.isBlank())
        {
            return null;
        }

        String safeScope = nullSafe(scope);

        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(safeScope.getBytes(StandardCharsets.UTF_8));
            return compactKey(value, 479) + '~' + HexFormat.of().formatHex(digest, 0, 16);
        }
        catch(NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String unscopedKey(String value)
    {
        if(value == null)
        {
            return null;
        }

        int separator = value.length() - 33;
        return separator >= 0 && value.charAt(separator) == '~' ? value.substring(0, separator) : value;
    }

    private static String compactKey(String value)
    {
        return compactKey(value, 512);
    }

    private static String compactKey(String value, int maximumLength)
    {
        if(value.length() <= maximumLength)
        {
            return value;
        }

        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            int digestBytes = 16;
            int suffixLength = 1 + digestBytes * 2;
            return value.substring(0, maximumLength - suffixLength) + '~' +
                HexFormat.of().formatHex(digest, 0, digestBytes);
        }
        catch(NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String boundedLabel(String value)
    {
        return value.length() <= 160 ? value : value.substring(0, 160);
    }

    private static String boundedNullableLabel(String value)
    {
        return value == null || value.isBlank() ? null : boundedLabel(value);
    }

    private static void validateBrowseKey(String key)
    {
        if(key.length() > 512)
        {
            throw new IllegalArgumentException("Recorded-call browse key exceeds 512 characters");
        }
    }

    private static String nullSafe(String value)
    {
        return value != null ? value : "";
    }

    private static String expectedCallIdentity(AudioCallId callId)
    {
        return Long.toUnsignedString(callId.producerId(), 36) + '-' +
            Long.toUnsignedString(callId.sequence(), 36) + '-' +
            Integer.toUnsignedString(Math.max(0, callId.timeslot()), 36);
    }

    static int formatCode(RecordFormat format)
    {
        return switch(format)
        {
            case WAVE -> FORMAT_WAVE;
            case MP3 -> FORMAT_MP3;
        };
    }

    static RecordFormat recordFormat(int code)
    {
        return switch(code)
        {
            case FORMAT_WAVE -> RecordFormat.WAVE;
            case FORMAT_MP3 -> RecordFormat.MP3;
            default -> throw new IllegalArgumentException("Unknown recorded-call format code: " + code);
        };
    }

    enum AdmissionResult
    {
        INSERTED,
        DUPLICATE,
        INVALID_ARTIFACT
    }

    record RetentionResult(int candidates, int filesDeleted, int filesMissing, int fileFailures, int rowsDeleted,
                           long bytesRemoved, int bucketsDeleted, boolean moreWork, RetentionCursor nextCursor)
    {
    }

    record RetentionCursor(long completedAtMs, long producerId, long callSequence, int timeslot)
    {
        private static RetentionCursor from(ExpiredCall call)
        {
            AudioCallId callId = call.callId();
            return new RetentionCursor(call.completedAtMs(), callId.producerId(), callId.sequence(),
                callId.timeslot());
        }
    }

    private enum DeleteResult
    {
        DELETED,
        MISSING,
        UNSAFE
    }

    private record ExpiredCall(AudioCallId callId, long completedAtMs, RecordFormat format,
                               String relativeDirectory, long byteSize)
    {
    }

    record PreparedAdmission(AdmissionResult result, RecordedCallArtifact artifact, ManagedRecordingPath path)
    {
    }

    private record FacetQuery(String sql, boolean hasScopeParameter, boolean returnsScope)
    {
    }

    record SearchStatement(String sql, List<Object> parameters)
    {
        SearchStatement
        {
            parameters = List.copyOf(parameters);
        }
    }
}
