/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.record.managed;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRecordingSourceLinksTest
{
    @TempDir Path directory;
    private static final String A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private static final String C = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";

    @Test
    void splitPartsAndConfirmedReceiverCopiesKeepExactLinksAcrossRestartAndIndependentDeletion() throws Exception
    {
        Path database = directory.resolve("links.sqlite");
        try(var store = new ManagedRecordingStore(database, directory))
        {
            store.insert(call(1000, 1, List.of(A, B, A)));
            store.insert(call(2000, 2, List.of(A)));
            store.insert(call(3000, 1, List.of(C)));
            store.insert(call(4000, 1, List.of()));
            assertEquals(List.of(A, B), store.sourceCallReferences(1));
            assertEquals(Map.of(A, List.of(1L, 2L), B, List.of(1L), C, List.of(3L)),
                store.recordingIdsBySourceCallReferences(List.of(A, B, C, A), 100));
            assertEquals(List.of(1L, 2L), store.findBySourceCallReferences(List.of(A, B), 100).stream()
                .map(ManagedRecordingCatalog.RecordingCall::id).toList());
            assertEquals(List.of(1L), store.recordingIdsBySourceCallReferences(List.of(A), 1).get(A));
        }
        try(var catalog = new ManagedRecordingCatalog(database, directory))
        {
            assertEquals(List.of(A, B), catalog.sourceCallReferences(1));
            assertEquals(2, catalog.findBySourceCallReferences(List.of(A), 100).size());
            assertTrue(catalog.sourceCallReferences(4).isEmpty(), "Old or unconnected calls stay unlinked");
            assertTrue(catalog.delete(1));
            assertEquals(Map.of(A, List.of(2L), B, List.of()),
                catalog.recordingIdsBySourceCallReferences(List.of(A, B), 100));
            assertEquals(List.of(A), catalog.sourceCallReferences(2));
            assertTrue(catalog.sourceCallReferences(1).isEmpty());
            assertNotNull(catalog.find(3));
        }
    }

    @Test
    void exactReferencesWorkForEveryAudioProtocolWithoutIdentityOrTimestampMatching() throws Exception
    {
        try(var store = new ManagedRecordingStore(directory.resolve("protocols.sqlite"), directory))
        {
            List<String> references = new ArrayList<>();
            for(int protocol = 0; protocol <= 6; protocol++)
            {
                String reference = UUID.nameUUIDFromBytes(new byte[]{(byte)protocol}).toString();
                references.add(reference);
                store.insert(call(1000 + protocol, protocol, List.of(reference)));
            }
            var matches = store.recordingIdsBySourceCallReferences(references, 100);
            for(int protocol = 0; protocol <= 6; protocol++)
                assertEquals(List.of(protocol + 1L), matches.get(references.get(protocol)));
            assertEquals(7, store.findBySourceCallReferences(references, 100).size());
        }
    }

    @Test
    void writesAreAtomicWithTheRecordingAndUseCoveringIndexesForBothDirections() throws Exception
    {
        Path database = directory.resolve("atomic.sqlite");
        try(var store = new ManagedRecordingStore(database, directory))
        {
            try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement())
            {
                statement.execute("CREATE TRIGGER injected_source_failure BEFORE INSERT ON recording_source " +
                    "BEGIN SELECT RAISE(ABORT,'Injected source write failure'); END");
                assertThrows(SQLException.class, () -> store.insert(call(1000, 1, List.of(A))));
                assertEquals(0, store.stats().callCount());
                assertTrue(store.findBySourceCallReferences(List.of(A), 100).isEmpty());
                try(var rows = statement.executeQuery("SELECT count(*) FROM recording_call"))
                { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
                statement.execute("DROP TRIGGER injected_source_failure");
                store.insert(call(1000, 1, List.of(A, B)));
                for(String query: List.of(
                    "SELECT call_id FROM recording_source WHERE source_call_uuid='" + A + "' ORDER BY call_id LIMIT 100",
                    "SELECT source_call_uuid FROM recording_source WHERE call_id=1 ORDER BY source_call_uuid LIMIT 128"))
                {
                    List<String> details = new ArrayList<>();
                    try(var rows = statement.executeQuery("EXPLAIN QUERY PLAN " + query))
                    { while(rows.next()) details.add(rows.getString(4)); }
                    assertTrue(details.stream().anyMatch(detail -> detail.startsWith("SEARCH ") &&
                        (detail.contains("COVERING INDEX idx_recording_source_uuid") || detail.contains("PRIMARY KEY"))),
                        details.toString());
                    assertFalse(details.stream().anyMatch(detail -> detail.contains("TEMP B-TREE")), details.toString());
                }
            }
        }
    }

    @Test
    void partLookupsStayWithinOneNormalPage() throws Exception
    {
        try(var store = new ManagedRecordingStore(directory.resolve("many-parts.sqlite"), directory))
        {
            for(int part = 0; part < 105; part++) store.insert(call(1000 + part, 1, List.of(A, B)));
            assertEquals(100, store.recordingIdsBySourceCallReferences(List.of(A, B), Integer.MAX_VALUE).get(A).size());
            assertEquals(100, store.findBySourceCallReferences(List.of(A, B), Integer.MAX_VALUE).size());
            assertEquals(1, store.findBySourceCallReferences(List.of(A), 0).size());
        }
    }

    @Test
    void lookupAndMetadataBoundsRejectInvalidReferencesAndDoNotExposeMutableLists() throws Exception
    {
        var mutable = new ArrayList<>(List.of(A, A, B));
        var metadata = call(1000, 1, mutable);
        mutable.clear();
        assertEquals(List.of(A, B), metadata.sourceCallReferences());
        assertThrows(UnsupportedOperationException.class, () -> metadata.sourceCallReferences().clear());
        for(String invalid: List.of("", "not-a-uuid", A.toUpperCase(), "1-1-1-1-1"))
            assertThrows(IllegalArgumentException.class, () -> call(1000, 1, List.of(invalid)));
        assertThrows(IllegalArgumentException.class, () -> call(1000, 1, Collections.nCopies(129, A)));
        try(var store = new ManagedRecordingStore(directory.resolve("bounds.sqlite"), directory))
        {
            assertThrows(IllegalArgumentException.class, () ->
                store.recordingIdsBySourceCallReferences(Collections.nCopies(101, A), 100));
            assertThrows(IllegalArgumentException.class, () ->
                store.recordingIdsBySourceCallReferences(List.of("invalid"), 100));
            assertTrue(store.recordingIdsBySourceCallReferences(List.of(), 100).isEmpty());
            assertTrue(store.sourceCallReferences(-1).isEmpty());
            assertEquals(Map.of(A, List.of()), store.recordingIdsBySourceCallReferences(List.of(A), 100));
        }
    }

    private static ManagedRecordingMetadata call(long start, int protocol, List<String> references)
    {
        return new ManagedRecordingMetadata(start, start + 1000, 1000, 5, start + ".mp3", "channel:links",
            "source-channel", 1, protocol, 0, 1, null, null, null, null, null, null, null, null,
            851012500L, null, null, null, null, null, List.of(), List.of(), null, List.of(), references);
    }
}
