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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.record.managed.ManagedRecordingCatalog.Member;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.SearchFilter;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.SearchPage;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.Site;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRecordingStoreTest
{
    @TempDir
    Path temporary;

    @Test
    void freshCatalogStoresTranscriptSeparatelyWithTimestampAndCascade() throws Exception
    {
        Path root = temporary.resolve("managed");
        Path db = temporary.resolve("database/recordings.sqlite");
        Files.createDirectories(root);
        try(ManagedRecordingStore store = new ManagedRecordingStore(db, root))
        {
            save(store, root, metadata(1000, "system-a", "one.mp3", 101, 4001,
                ManagedRecordingCatalog.CALL_GROUP, null, List.of(), List.of()));
            assertEquals(1, store.search(SearchFilter.builder().fromMs(0L).toMs(2000L)
                .build()).calls().size());
        }
        assertFalse(ManagedRecordingSchema.ddlForFormat(1).containsKey("recording_transcript"));
        assertTrue(ManagedRecordingSchema.ddlForFormat(2).containsKey("recording_transcript"));

        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
            var statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            try(var applicationId = statement.executeQuery("PRAGMA application_id"))
            {
                assertTrue(applicationId.next());
                assertEquals(ManagedRecordingSchema.APPLICATION_ID, applicationId.getInt(1));
            }
            try(var version = statement.executeQuery("PRAGMA user_version"))
            {
                assertTrue(version.next());
                assertEquals(2, version.getInt(1));
            }
            try(var metadata = statement.executeQuery(
                "SELECT format_version FROM catalog_metadata WHERE id=1"))
            {
                assertTrue(metadata.next());
                assertEquals(2, metadata.getInt(1));
            }
            statement.executeUpdate("INSERT INTO recording_transcript(call_id,text,stored_at_ms) " +
                "VALUES(1,'hello world',1234)");
            try(var rows = statement.executeQuery("SELECT text,stored_at_ms FROM recording_transcript " +
                "WHERE call_id=1"))
            {
                assertTrue(rows.next());
                assertEquals("hello world", rows.getString(1));
                assertEquals(1234L, rows.getLong(2));
            }
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "INSERT INTO recording_transcript(call_id,text,stored_at_ms) VALUES(1,'duplicate',2)"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "INSERT INTO recording_transcript(call_id,text,stored_at_ms) VALUES(999,'orphan',2)"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE recording_transcript SET text=NULL WHERE call_id=1"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE recording_transcript SET stored_at_ms=-1 WHERE call_id=1"));

            statement.executeUpdate("DELETE FROM recording_call WHERE id=1");
            try(var rows = statement.executeQuery("SELECT count(*) FROM recording_transcript"))
            {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        }
    }

    @Test
    void rejectsAdditionalOrMissingCatalogObjects() throws Exception
    {
        Path root = temporary.resolve("managed");
        Path db = temporary.resolve("database/recordings.sqlite");
        Files.createDirectories(root);
        try(ManagedRecordingStore ignored = new ManagedRecordingStore(db, root))
        {
        }
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
            var statement = connection.createStatement())
        {
            statement.execute("CREATE VIEW unexpected_view AS SELECT id FROM recording_call");
        }
        assertThrows(SQLException.class, () -> new ManagedRecordingStore(db, root));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
            var statement = connection.createStatement())
        {
            statement.execute("DROP VIEW unexpected_view");
            statement.execute("DROP TABLE recording_transcript");
        }
        assertThrows(SQLException.class, () -> new ManagedRecordingStore(db, root));
    }

    @Test
    void pagesAndFiltersAcrossSystemsSitesAndPatchMembers() throws Exception
    {
        Path root = temporary.resolve("managed");
        Path db = temporary.resolve("database/recordings.sqlite");
        Files.createDirectories(root);
        Site winner = new Site(0x12345, 0x321, 1, 2);
        Site also = new Site(0x12345, 0x321, 2, 3);
        try(ManagedRecordingStore store = new ManagedRecordingStore(db, root))
        {
            save(store, root, metadata(1000, "system-a", "one.mp3", 101, 4001,
                ManagedRecordingCatalog.CALL_GROUP, winner, List.of(winner), List.of()));
            save(store, root, metadata(2000, "system-a", "two.mp3", 102, 7001,
                ManagedRecordingCatalog.CALL_PATCH, winner, List.of(winner, also),
                List.of(new Member("talkgroup", 9001, null, null, null),
                    new Member("radio", 5555, null, null, null))));
            save(store, root, metadata(3000, "system-b", "three.mp3", 103, 4001,
                ManagedRecordingCatalog.CALL_GROUP, null, List.of(), List.of()));
            assertEquals(3, store.stats().callCount());

            SearchFilter firstPage = SearchFilter.builder().fromMs(0L).toMs(5000L).limit(2).build();
            SearchPage first = store.search(firstPage);
            assertEquals(List.of(3000L, 2000L), first.calls().stream().map(call -> call.startMs()).toList());
            assertNotNull(first.nextCursor());
            SearchPage next = store.search(SearchFilter.builder().fromMs(0L).toMs(5000L)
                .cursor(first.nextCursor()).limit(2).build());
            assertEquals(List.of(1000L), next.calls().stream().map(call -> call.startMs()).toList());
            assertNull(next.nextCursor());

            SearchPage ascending = store.search(SearchFilter.builder().fromMs(0L).toMs(5000L)
                .sortAscending(true).limit(2).build());
            assertEquals(List.of(1000L, 2000L), ascending.calls().stream().map(call -> call.startMs()).toList());
            assertNotNull(ascending.nextCursor());
            assertEquals(3000L, store.search(SearchFilter.builder().fromMs(0L).toMs(5000L)
                .sortAscending(true).cursor(ascending.nextCursor()).limit(2).build())
                .calls().getFirst().startMs());

            SearchPage site = store.search(SearchFilter.builder().fromMs(0L).toMs(5000L)
                .wacn(also.wacn()).systemId(also.systemId()).rfss(also.rfss())
                .siteId(also.siteId()).build());
            assertEquals(1, site.calls().size());
            assertEquals(2000L, site.calls().getFirst().startMs());
            assertEquals(List.of(also), site.calls().getFirst().alsoReceivedOn());
            assertEquals(1, store.search(SearchFilter.builder().fromMs(0L).toMs(5000L)
                .talkgroupId(9001).build()).calls().size());
            assertEquals(1, store.search(SearchFilter.builder().fromMs(0L).toMs(5000L)
                .talkgroupId(9001).wacn(also.wacn()).systemId(also.systemId())
                .rfss(also.rfss()).siteId(also.siteId()).build()).calls().size());
            assertEquals(1, store.search(SearchFilter.builder().fromMs(0L).toMs(5000L)
                .systemKey("system-a").anyIdentityIds(List.of(103, 9001)).build()).calls().size());
            assertEquals(1, store.search(SearchFilter.builder().fromMs(0L).toMs(5000L)
                .talkgroupMin(9000).talkgroupMax(9100).build()).calls().size());
            assertEquals(2, store.search(SearchFilter.builder().fromMs(0L).toMs(5000L)
                .sourceMin(102).sourceMax(103).build()).calls().size());
            assertEquals(1, store.search(SearchFilter.builder().fromMs(0L).toMs(5000L)
                .sourceId(5555).build()).calls().size());
            assertEquals(1, store.sites("system-a", "02-03", 25).size());
            assertEquals(List.of("system-a", "system-b"), store.systemKeys("system", 25));
        }
        try(ManagedRecordingStore reopened = new ManagedRecordingStore(db, root))
        {
            assertEquals(3, reopened.stats().callCount());
            assertNotNull(reopened.find(2L));
        }
    }

    @Test
    void directDestinationAndDplAreTypedWithoutInventingTalkgroup() throws Exception
    {
        Path root = temporary.resolve("managed");
        Files.createDirectories(root);
        try(ManagedRecordingStore store = new ManagedRecordingStore(
            temporary.resolve("database/recordings.sqlite"), root))
        {
            ManagedRecordingMetadata base = metadata(1000, "system-a", "direct.mp3", 101, 8008,
                ManagedRecordingCatalog.CALL_DIRECT, null, List.of(), List.of());
            ManagedRecordingMetadata tagged = new ManagedRecordingMetadata(base.startMs(), base.endMs(),
                base.durationMs(), base.sizeBytes(), base.relativePath(), base.systemKey(), base.channelId(),
                base.aliasListId(), base.protocol(), base.callType(), base.voiceType(), base.sourceId(),
                base.sourceHomeWacn(), base.sourceHomeSystem(), base.sourceHomeId(), base.targetId(),
                base.targetHomeWacn(), base.targetHomeSystem(), base.targetHomeId(), base.frequencyHz(),
                base.timeslot(), base.nac(), 1, "N023", base.winnerSite(), base.observedSites(),
                base.patchMembers());
            save(store, root, tagged);
            var map = store.find(1L).toMap();
            assertEquals(8008, map.get("destination_radio_id"));
            assertFalse(map.containsKey("talkgroup_id"));
            assertEquals("N023", map.get("dpl"));
            assertEquals("DPL", map.get("tone_kind"));
            assertEquals(1, store.search(SearchFilter.builder().fromMs(0L).toMs(5000L)
                .sourceId(8008).build()).calls().size());
        }
    }

    @Test
    void popularIdentityPagesUseBoundedIndexStreamsAndDeduplicatePatchMembers() throws Exception
    {
        Path root = temporary.resolve("managed");
        Path db = temporary.resolve("database/recordings.sqlite");
        Files.createDirectories(root);
        try(ManagedRecordingStore store = new ManagedRecordingStore(db, root))
        {
            try(var connection = DriverManager.getConnection("jdbc:sqlite:" + db))
            {
                connection.setAutoCommit(false);
                try(PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO recording_call(start_ms,end_ms,duration_ms,relative_path,size_bytes," +
                        "protocol,call_type,voice_type,source_id,target_id) VALUES(?,?,?,?,?,?,?,?,?,?)"))
                {
                    for(int index = 1; index <= 30_000; index++)
                    {
                        insert.setLong(1, index);
                        insert.setLong(2, index + 1L);
                        insert.setLong(3, 1L);
                        insert.setString(4, "popular-" + index + ".mp3");
                        insert.setLong(5, 5L);
                        insert.setInt(6, 1);
                        insert.setInt(7, ManagedRecordingCatalog.CALL_GROUP);
                        insert.setInt(8, ManagedRecordingCatalog.VOICE_CLEAR);
                        insert.setInt(9, 5555);
                        insert.setInt(10, 4001);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                try(var statement = connection.createStatement())
                {
                    statement.executeUpdate("INSERT INTO recording_system(system_key) VALUES('system-a')");
                    statement.executeUpdate("UPDATE recording_call SET system_id=(SELECT id FROM " +
                        "recording_system WHERE system_key='system-a') WHERE id=100");
                    statement.executeUpdate("INSERT INTO recording_site(wacn,system_id,rfss,site_id) " +
                        "VALUES(74565,801,1,2)");
                    statement.executeUpdate("INSERT INTO recording_call_site(call_id,site_id,start_ms) " +
                        "SELECT 200,id,200 FROM recording_site WHERE wacn=74565 AND system_id=801 " +
                        "AND rfss=1 AND site_id=2");
                }
                connection.commit();
            }
            store.insert(metadata(30_001, null, "patch.mp3", 100, 7001,
                ManagedRecordingCatalog.CALL_PATCH, null, List.of(),
                List.of(new Member("talkgroup", 4001, 1, 2, 3),
                    new Member("talkgroup", 4001, 4, 5, 6))));

            SearchFilter descending = SearchFilter.builder().fromMs(0L).toMs(40_000L)
                .talkgroupId(4001).limit(3).build();
            SearchPage first = store.search(descending);
            assertEquals(List.of(30_001L, 30_000L, 29_999L),
                first.calls().stream().map(call -> call.startMs()).toList());
            assertNotNull(first.nextCursor());
            SearchPage second = store.search(SearchFilter.builder().fromMs(0L).toMs(40_000L)
                .talkgroupId(4001).limit(3).cursor(first.nextCursor()).build());
            assertEquals(List.of(29_998L, 29_997L, 29_996L),
                second.calls().stream().map(call -> call.startMs()).toList());
            SearchPage ascending = store.search(SearchFilter.builder().fromMs(0L).toMs(40_000L)
                .talkgroupId(4001).sortAscending(true).limit(3).build());
            assertEquals(List.of(1L, 2L, 3L),
                ascending.calls().stream().map(call -> call.startMs()).toList());
            assertEquals(List.of(30_000L, 29_999L), store.search(SearchFilter.builder()
                .fromMs(0L).toMs(40_000L).sourceId(5555).limit(2).build()).calls().stream()
                .map(call -> call.startMs()).toList());
            assertEquals(List.of(100L), store.search(SearchFilter.builder().fromMs(0L)
                .toMs(40_000L).talkgroupId(4001).systemKey("system-a").limit(3).build())
                .calls().stream().map(call -> call.startMs()).toList());
            assertEquals(List.of(200L), store.search(SearchFilter.builder().fromMs(0L)
                .toMs(40_000L).talkgroupId(4001).wacn(74565).systemId(801).rfss(1).siteId(2)
                .limit(3).build()).calls().stream().map(call -> call.startMs()).toList());

            for(ManagedRecordingStore.CandidateQuery branch :
                store.singleIdentityCandidateQueries(descending))
            {
                String details = plan(db, branch);
                assertFalse(details.contains("USE TEMP B-TREE"), details);
                if(branch.sql().contains("recording_patch_member pm"))
                {
                    assertTrue(details.contains("SEARCH pm USING COVERING INDEX " +
                        "idx_recording_patch_member_lookup") || details.contains(
                            "SEARCH pm USING INDEX idx_recording_patch_member_lookup"), details);
                }
                else
                {
                    assertTrue(details.contains("SEARCH c USING INDEX idx_recording_call_target_time") ||
                        details.contains("SEARCH c USING COVERING INDEX idx_recording_call_target_time"), details);
                }
            }
            for(ManagedRecordingStore.CandidateQuery branch : store.singleIdentityCandidateQueries(
                SearchFilter.builder().fromMs(0L).toMs(40_000L).sourceId(5555).limit(3).build()))
            {
                String details = plan(db, branch);
                assertFalse(details.contains("USE TEMP B-TREE"), details);
                if(branch.sql().contains("recording_patch_member pm"))
                {
                    assertTrue(details.contains("idx_recording_patch_member_lookup"), details);
                }
                else
                {
                    assertTrue(details.contains("idx_recording_call_source_time") ||
                        details.contains("idx_recording_call_target_time"), details);
                }
            }
        }
    }

    @Test
    void manualRecountAndReindexDropMissingRowsWithoutImportingLooseFiles() throws Exception
    {
        Path root = temporary.resolve("managed");
        Path db = temporary.resolve("database/recordings.sqlite");
        Files.createDirectories(root);
        try(ManagedRecordingStore store = new ManagedRecordingStore(db, root))
        {
            save(store, root, metadata(1000, "system-a", "one.mp3", 101, 4001,
                ManagedRecordingCatalog.CALL_GROUP, null, List.of(), List.of()));
            save(store, root, metadata(2000, "system-a", "two.mp3", 102, 4002,
                ManagedRecordingCatalog.CALL_GROUP, null, List.of(), List.of()));
            try(var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
                var statement = connection.createStatement())
            {
                statement.executeUpdate("UPDATE catalog_metadata SET call_count=0,total_bytes=0 WHERE id=1");
            }
            assertEquals(2, store.recount(false).callCount());
            assertEquals(10, store.stats().totalBytes());
            Files.delete(root.resolve("two.mp3"));
            Files.write(root.resolve("loose.mp3"), new byte[]{'I', 'D', '3', 0, 0});
            var result = store.recount(true);
            assertEquals(2, result.inspected());
            assertEquals(1, result.removed());
            assertEquals(1, result.callCount());
            assertNull(store.find(2L));
            assertTrue(Files.exists(root.resolve("loose.mp3")));
            assertEquals(1, store.stats().callCount());
            assertEquals(1, store.pruneOlderThan(1500L).removed());
            assertEquals(0, store.stats().callCount());
        }
    }

    @Test
    void recountAndRetentionDropRowsWhoseAudioPathBecameADirectory() throws Exception
    {
        Path root = temporary.resolve("managed");
        Path db = temporary.resolve("database/recordings.sqlite");
        Files.createDirectories(root);
        try(ManagedRecordingStore store = new ManagedRecordingStore(db, root))
        {
            save(store, root, metadata(1000, "system-a", "corrupt.mp3", 101, 4001,
                ManagedRecordingCatalog.CALL_GROUP, null, List.of(), List.of()));
            Path corrupt = root.resolve("corrupt.mp3");
            Files.delete(corrupt);
            Files.createDirectory(corrupt);
            Files.writeString(corrupt.resolve("marker"), "keep");

            assertEquals(1, store.recount(false).removed());
            assertNull(store.find(1L));
            assertTrue(Files.exists(corrupt.resolve("marker")));

            save(store, root, metadata(2000, "system-a", "expired.mp3", 102, 4002,
                ManagedRecordingCatalog.CALL_GROUP, null, List.of(), List.of()));
            Path expired = root.resolve("expired.mp3");
            Files.delete(expired);
            Files.createDirectory(expired);
            Files.writeString(expired.resolve("marker"), "keep");

            assertEquals(1, store.pruneOlderThan(3000L).removed());
            assertNull(store.find(2L));
            assertTrue(Files.exists(expired.resolve("marker")));
            assertEquals(0, store.stats().callCount());
        }
    }

    @Test
    void rejectsInvalidExistingDatabaseAndUsesTimeIndex() throws Exception
    {
        Path root = temporary.resolve("managed");
        Path db = temporary.resolve("database/recordings.sqlite");
        Files.createDirectories(root);
        try(ManagedRecordingStore store = new ManagedRecordingStore(db, root))
        {
            for(int index = 1; index <= 2_000; index++)
            {
                store.insert(metadata(index, "system-a", "call-" + index + ".mp3", 101,
                    4001, ManagedRecordingCatalog.CALL_GROUP, null, List.of(), List.of()));
            }
        }
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
            var statement = connection.createStatement();
            var rows = statement.executeQuery("EXPLAIN QUERY PLAN SELECT id FROM recording_call " +
                "WHERE start_ms BETWEEN 1 AND 2000 ORDER BY start_ms DESC,id DESC LIMIT 50"))
        {
            assertTrue(rows.next());
            assertTrue(rows.getString("detail").contains("idx_recording_call_time"));
        }
        String talkgroupPlan = plan(db, "SELECT c.id FROM recording_call c WHERE " +
            "c.start_ms BETWEEN 1 AND 2000 AND c.id IN (" +
            "SELECT id FROM recording_call INDEXED BY idx_recording_call_target_time " +
            "WHERE target_id=4001 AND call_type IN(1,2) AND start_ms BETWEEN 1 AND 2000 " +
            "UNION SELECT pm.call_id FROM recording_patch_member pm " +
            "INDEXED BY idx_recording_patch_member_lookup WHERE pm.kind=1 AND pm.local_id=4001 " +
            "AND pm.start_ms BETWEEN 1 AND 2000) " +
            "ORDER BY c.start_ms DESC,c.id DESC LIMIT 50");
        assertTrue(talkgroupPlan.contains("SEARCH recording_call USING INDEX " +
            "idx_recording_call_target_time"), talkgroupPlan);
        assertTrue(talkgroupPlan.contains("SEARCH pm USING COVERING INDEX " +
            "idx_recording_patch_member_lookup") || talkgroupPlan.contains(
                "SEARCH pm USING INDEX idx_recording_patch_member_lookup"), talkgroupPlan);
        String sitePlan = plan(db, "SELECT c.id FROM recording_call_site cs " +
            "INDEXED BY idx_recording_call_site_lookup JOIN recording_call c ON c.id=cs.call_id " +
            "WHERE cs.site_id=1 AND cs.start_ms BETWEEN 1 AND 2000 " +
            "ORDER BY cs.start_ms DESC,cs.call_id DESC LIMIT 50");
        assertTrue(sitePlan.contains("SEARCH cs USING COVERING INDEX " +
            "idx_recording_call_site_lookup") || sitePlan.contains(
                "SEARCH cs USING INDEX idx_recording_call_site_lookup"), sitePlan);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
            var statement = connection.createStatement();
            var rows = statement.executeQuery("PRAGMA quick_check"))
        {
            assertTrue(rows.next());
            assertEquals("ok", rows.getString(1));
        }
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
            var statement = connection.createStatement())
        {
            statement.execute("PRAGMA user_version=999");
        }
        assertThrows(java.sql.SQLException.class, () -> new ManagedRecordingStore(db, root));
    }

    private static String plan(Path db, String query) throws Exception
    {
        StringBuilder details = new StringBuilder();
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
            var statement = connection.createStatement();
            var rows = statement.executeQuery("EXPLAIN QUERY PLAN " + query))
        {
            while(rows.next())
            {
                details.append(rows.getString("detail")).append('\n');
            }
        }
        return details.toString();
    }

    private static String plan(Path db, ManagedRecordingStore.CandidateQuery query) throws Exception
    {
        StringBuilder details = new StringBuilder();
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
            var statement = connection.prepareStatement("EXPLAIN QUERY PLAN " + query.sql()))
        {
            int position = 1;
            for(Object value : query.parameters())
            {
                statement.setObject(position++, value);
            }
            try(var rows = statement.executeQuery())
            {
                while(rows.next())
                {
                    details.append(rows.getString("detail")).append('\n');
                }
            }
        }
        return details.toString();
    }

    private static void save(ManagedRecordingStore store, Path root, ManagedRecordingMetadata metadata)
        throws Exception
    {
        Files.write(root.resolve(metadata.relativePath()), new byte[]{'I', 'D', '3', 0, 0});
        store.insert(metadata);
    }

    private static ManagedRecordingMetadata metadata(long start, String system, String path, int source,
                                                      int target, int type, Site winner, List<Site> sites,
                                                      List<Member> members)
    {
        return new ManagedRecordingMetadata(start, start + 1000, 1000L, 5L, path, system,
            "channel-a", 7L, 1, type, ManagedRecordingCatalog.VOICE_CLEAR,
            source, null, null, null, target, null, null, null,
            851012500L, 1, 0x293, null, null, winner, sites, members);
    }
}
