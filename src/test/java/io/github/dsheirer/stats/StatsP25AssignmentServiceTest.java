package io.github.dsheirer.stats;

import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.P25WuidAssignmentRegistry;
import io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StatsP25AssignmentServiceTest
{
    private static final String SYSTEM = RadioSystemKey.p25(0xABCDE, 0x123);

    @Test
    void friendlySearchAndSourceFiltersApplyBeforePagingAndRequestsNeverScanRegistry()
    {
        long now = System.currentTimeMillis();
        AtomicInteger scans = new AtomicInteger();
        var entries = List.of(entry(3, "A", now), entry(20, "B", now), entry(100, "B", now));
        var snapshot = snapshot(now, false, entries, true, 0);
        var service = new StatsP25AssignmentService(time -> { scans.incrementAndGet(); return snapshot; },
            (key, rows) -> rows.stream().map(row -> {
                Map<String,Object> named = new LinkedHashMap<>(row);
                named.put("alias_name", ((Number)row.get("canonical_subscriber_id")).intValue() == 3 ?
                    "Dispatch" : "Medic");
                named.put("home_system_name", "County Radio");
                return named;
            }).toList(), now - 100);
        service.refresh(now);
        Map<String,Object> page = service.currentAssignments(SYSTEM, request(
            "q=Medic&configuration_id=B&sort=canonical_identity&direction=asc&limit=1&offset=1"));
        assertEquals(2, page.get("total_count"));
        assertEquals(100, ((Map<?,?>)((List<?>)page.get("rows")).getFirst()).get("canonical_subscriber_id"));
        assertEquals(1, scans.get());
        assertEquals("current", service.currentState(SYSTEM).get("state"));
        assertEquals(3, service.currentState(SYSTEM).get("current_assignment_count"));
        assertEquals(now, service.currentState(SYSTEM).get("last_confirmation_ms"));
        assertEquals(1, scans.get());
    }

    @Test
    void assignmentPagesKeepStateFromTheirOwnSnapshotAndReportWholeSystemCounts()
    {
        long now = System.currentTimeMillis();
        AtomicReference<P25WuidAssignmentRegistry.Snapshot> source = new AtomicReference<>(
            snapshot(now, true, List.of(entry(3, "A", now), entry(20, "B", now)), true, 0));
        var service = new StatsP25AssignmentService(time -> source.get(), (key, rows) -> rows, now - 100);
        service.refresh(now);
        Map<String,Object> retained = service.currentAssignments(SYSTEM, request("configuration_id=A&limit=1"));
        Map<?,?> retainedState = (Map<?,?>)retained.get("current_state");
        assertEquals(retained.get("snapshot_at_ms"), retainedState.get("snapshot_at_ms"));
        assertEquals(retained.get("snapshot_stale"), retainedState.get("snapshot_stale"));
        assertEquals(0, retainedState.get("current_assignment_count"));
        assertEquals(2, retainedState.get("retained_assignment_count"), "State describes the complete scoped system");

        source.set(snapshot(now + 1_000, false, List.of(entry(100, "B", now + 1_000)), true, 0));
        service.refresh(now + 1_000);
        Map<String,Object> fresh = service.recentChanges(SYSTEM, request(""));
        Map<?,?> freshState = (Map<?,?>)fresh.get("current_state");
        assertEquals(now + 1_000, freshState.get("snapshot_at_ms"));
        assertEquals(false, freshState.get("snapshot_stale"));
        assertEquals(1, freshState.get("current_assignment_count"));
        assertEquals(now, retainedState.get("snapshot_at_ms"), "A later publish cannot change the returned page's state");
        assertEquals(true, retainedState.get("snapshot_stale"));
    }

    @Test
    void searchIncludesDisplayedLabelsAndIdentityIdsWithoutSearchingFieldNamesOrTimestamps()
    {
        long now = System.currentTimeMillis();
        var service = new StatsP25AssignmentService(time ->
            snapshot(now, false, List.of(entry(3, "A", now)), true, 0), (key, rows) -> rows.stream().map(row -> {
                Map<String,Object> named = new LinkedHashMap<>(row);
                named.put("alias_name", "Dispatch");
                named.put("home_system_name", "County");
                named.put("channel_name", "North");
                named.put("site_name", "Hill");
                named.put("alias_id", 987654321);
                return named;
            }).toList(), now - 100);
        service.refresh(now);
        for(String query: List.of("Dispatch", "County", "North", "Hill", "ABCDE.123.3", "203"))
        {
            assertEquals(1, service.currentAssignments(SYSTEM, request("q=" + query)).get("total_count"), query);
        }
        for(String query: List.of("radio_system_key", "canonical_identity", "987654321", String.valueOf(now)))
        {
            assertEquals(0, service.currentAssignments(SYSTEM, request("q=" + query)).get("total_count"), query);
        }
    }

    @Test
    void staleViewsRetainTheirOriginalTimeAndStoppedSystemsDoNotAppearCurrent()
    {
        long now = System.currentTimeMillis();
        AtomicReference<P25WuidAssignmentRegistry.Snapshot> source = new AtomicReference<>(
            snapshot(now, false, List.of(entry(3, "A", now)), true, 0));
        var service = new StatsP25AssignmentService(time -> source.get(), (key, rows) -> rows, now - 100);
        service.refresh(now);
        source.set(snapshot(now, true, List.of(entry(3, "A", now)), true, 0));
        service.refresh(now + 2_000);
        assertEquals(now, service.currentState(SYSTEM).get("snapshot_at_ms"));
        assertEquals(true, service.currentState(SYSTEM).get("snapshot_stale"));
        assertEquals("needs_confirmation", service.currentState(SYSTEM).get("state"));
        assertEquals(0, service.currentState(SYSTEM).get("current_assignment_count"));
        assertEquals(1, service.currentState(SYSTEM).get("retained_assignment_count"));
        source.set(snapshot(now + 3_000, false, List.of(), false, now + 3_000));
        service.refresh(now + 3_000);
        assertEquals("stopped", service.currentState(SYSTEM).get("state"));
        assertEquals(0, service.currentState(SYSTEM).get("current_assignment_count"));
    }

    @Test
    void receiverInvalidationNoticesSurviveExactFiltersAndMaximumSubscriberIdIsUsable()
    {
        long now = System.currentTimeMillis();
        var identity = new P25SubscriberIdentity(0xFFFFF, 0x456, 0xFFFFFC);
        var entry = new P25WuidAssignmentRegistry.Entry(0xABCDE, 0x123, 400, identity,
            now, now, Long.MAX_VALUE, P25WuidAssignmentRegistry.Evidence.REGISTRATION,
            P25WuidAssignmentRegistry.LeaseSource.ADVERTISED, new P25SiteIdentity(0xABCDE, 0x123, 1, 2), "A");
        var global = new P25WuidAssignmentRegistry.Change(1, now, P25WuidAssignmentRegistry.ChangeReason.RESET,
            null, null, null, null, null);
        var source = new P25WuidAssignmentRegistry.Snapshot(now, 1, 2, false, 1, List.of(entry), List.of(global),
            List.of(new P25WuidAssignmentRegistry.SystemObservation(0xABCDE, 0x123, true, 0, List.of("A"))), 0, 0);
        var service = new StatsP25AssignmentService(time -> source, (key, rows) -> rows, now);
        service.refresh(now);
        assertEquals(1, service.currentAssignments(SYSTEM, request("subscriber_id=16777212")).get("total_count"));
        Map<String,Object> changes = service.recentChanges(SYSTEM, request(
            "subscriber_id=999&configuration_id=B&roaming_only=true&q=NoMatchingName"));
        assertEquals(1, changes.get("total_count"));
        Map<?,?> notice = (Map<?,?>)((List<?>)changes.get("rows")).getFirst();
        assertEquals("receiver", notice.get("invalidation_scope"));
        assertEquals(SYSTEM, notice.get("radio_system_key"));
    }

    @Test
    void historicalSystemWithNoCurrentSourceShowsNoObservation()
    {
        long now = System.currentTimeMillis();
        var source = new P25WuidAssignmentRegistry.Snapshot(now, 1, 2, false, 0, List.of(), List.of(), List.of(), 0, 0);
        var service = new StatsP25AssignmentService(time -> source, (key, rows) -> rows, now);
        assertEquals("learning", service.currentState(SYSTEM).get("state"));
        service.refresh(now);
        assertEquals("stopped", service.currentState(SYSTEM).get("state"));
    }

    @Test
    void exactHomeIdentityFilterNeverMatchesAnotherSystemWithTheSameRadioNumber()
    {
        long now = System.currentTimeMillis();
        List<P25WuidAssignmentRegistry.Entry> entries = new ArrayList<>();
        entries.add(entry(3, "A", now));
        entries.add(new P25WuidAssignmentRegistry.Entry(0xABCDE, 0x123, 400,
            new P25SubscriberIdentity(0xFFFFF, 0x456, 3), now, now, Long.MAX_VALUE,
            P25WuidAssignmentRegistry.Evidence.REGISTRATION, P25WuidAssignmentRegistry.LeaseSource.ADVERTISED,
            new P25SiteIdentity(0xABCDE, 0x123, 1, 2), "A"));
        var service = new StatsP25AssignmentService(time -> snapshot(now, false, entries, true, 0),
            (key, rows) -> rows, now);
        service.refresh(now);
        assertEquals(1, service.currentAssignments(SYSTEM, request(
            "home_wacn=1048575&home_system_id=1110&subscriber_id=3")).get("total_count"));
        assertEquals(1, service.currentAssignments(SYSTEM, request("roaming_only=true")).get("total_count"));
        assertThrows(StatsApiException.class, () -> service.currentAssignments(SYSTEM, request("sort=arbitrary")));
        assertThrows(StatsApiException.class, () -> service.currentAssignments(SYSTEM, request("home_wacn=1048576")));
    }

    @Test
    void meaningfulCurrentMappingsKeepForeignEqualNumbersAndUnknownEvidenceBeforePaging()
    {
        long now = System.currentTimeMillis();
        var local = new P25SubscriberIdentity(0xABCDE, 0x123, 3);
        var foreign = new P25SubscriberIdentity(0xFFFFF, 0x456, 4);
        var entries = List.of(entry(local, 3, "A", now), entry(foreign, 4, "B", now),
            entry(20, "B", now), entry(null, 400, "A", now));
        AtomicReference<P25WuidAssignmentRegistry.Snapshot> source = new AtomicReference<>(
            snapshot(now, false, entries, true, 0));
        var service = new StatsP25AssignmentService(time -> source.get(), (key, rows) -> rows, now);
        service.refresh(now);

        assertEquals(4, service.currentAssignments(SYSTEM, request("")).get("total_count"),
            "Existing callers retain ordinary local assignments");
        Map<String,Object> page = service.currentAssignments(SYSTEM, request(
            "meaningful_only=true&sort=working_id&direction=asc&limit=1&offset=1"));
        assertEquals(3, page.get("total_count"));
        assertEquals(true, page.get("has_more"));
        assertEquals(20, ((Map<?,?>)((List<?>)page.get("rows")).getFirst()).get("canonical_subscriber_id"));
        assertEquals(2, service.currentAssignments(SYSTEM,
            request("meaningful_only=true&configuration_id=B")).get("total_count"));
        assertEquals(0, service.currentAssignments(SYSTEM,
            request("meaningful_only=true&subscriber_id=3")).get("total_count"));
        assertEquals(1, service.currentAssignments(SYSTEM,
            request("meaningful_only=true&subscriber_id=4")).get("total_count"),
            "A foreign home system matters even when the two numeric IDs match");
        Map<?,?> state = (Map<?,?>)page.get("current_state");
        assertEquals(4, state.get("current_assignment_count"));
        assertEquals(3L, state.get("meaningful_assignment_count"));
        assertEquals(0L, state.get("retained_meaningful_assignment_count"));

        source.set(snapshot(now, true, entries, true, 0));
        service.refresh(now);
        Map<?,?> stale = service.currentState(SYSTEM);
        assertEquals(0L, stale.get("meaningful_assignment_count"));
        assertEquals(3L, stale.get("retained_meaningful_assignment_count"));
        assertEquals(4, stale.get("retained_assignment_count"));
        assertEquals(3L, state.get("meaningful_assignment_count"), "A later view cannot alter the page's state");
    }

    @Test
    void ordinaryLocalMappingsRemainCurrentButHaveNoMeaningfulCount()
    {
        long now = System.currentTimeMillis();
        var entries = List.of(entry(new P25SubscriberIdentity(0xABCDE, 0x123, 3), 3, "A", now));
        var service = new StatsP25AssignmentService(time -> snapshot(now, false, entries, true, 0),
            (key, rows) -> rows, now);
        service.refresh(now);
        Map<String,Object> page = service.currentAssignments(SYSTEM, request("meaningful_only=true"));
        assertEquals(0, page.get("total_count"));
        Map<?,?> state = (Map<?,?>)page.get("current_state");
        assertEquals("current", state.get("state"));
        assertEquals(1, state.get("current_assignment_count"));
        assertEquals(0L, state.get("meaningful_assignment_count"));
    }

    @Test
    void meaningfulRecentChangesKeepMovesAndRemovalsButHideRefreshesAndOrdinaryLocalRows()
    {
        long now = System.currentTimeMillis();
        var local = new P25SubscriberIdentity(0xABCDE, 0x123, 3);
        var foreign = new P25SubscriberIdentity(0xFFFFF, 0x456, 4);
        var remapped = new P25SubscriberIdentity(0xABCDE, 0x123, 20);
        var changes = List.of(
            change(1, now, P25WuidAssignmentRegistry.ChangeReason.REGISTERED, local, 3, null),
            change(2, now, P25WuidAssignmentRegistry.ChangeReason.REGISTERED, foreign, 4, null),
            change(3, now, P25WuidAssignmentRegistry.ChangeReason.REGISTERED, remapped, 220, null),
            change(4, now, P25WuidAssignmentRegistry.ChangeReason.REFRESHED, remapped, 220, null),
            change(5, now, P25WuidAssignmentRegistry.ChangeReason.REASSIGNED, remapped, 20, 220),
            change(6, now, P25WuidAssignmentRegistry.ChangeReason.CLEARED, remapped, 20, 220),
            change(7, now, P25WuidAssignmentRegistry.ChangeReason.CLEARED, remapped, 220, null),
            change(8, now, P25WuidAssignmentRegistry.ChangeReason.CLEARED, local, 3, null),
            new P25WuidAssignmentRegistry.Change(9, now, P25WuidAssignmentRegistry.ChangeReason.RESET,
                null, null, null, null, null),
            change(10, now, P25WuidAssignmentRegistry.ChangeReason.OBSERVATION_GAP, null, null, null),
            change(11, now, P25WuidAssignmentRegistry.ChangeReason.REGISTERED, null, 500, null));
        var source = new P25WuidAssignmentRegistry.Snapshot(now, 1, 2, false, 0, List.of(), changes,
            List.of(new P25WuidAssignmentRegistry.SystemObservation(0xABCDE, 0x123, true, 0, List.of("A"))), 0, 0);
        var service = new StatsP25AssignmentService(time -> source, (key, rows) -> rows, now);
        service.refresh(now);

        assertEquals(11, service.recentChanges(SYSTEM, request("")).get("total_count"));
        Map<String,Object> useful = service.recentChanges(SYSTEM,
            request("meaningful_only=true&sort=changed_at&direction=asc&limit=1&offset=1"));
        assertEquals(8, useful.get("total_count"));
        assertEquals(3L, ((Map<?,?>)((List<?>)useful.get("rows")).getFirst()).get("sequence"),
            "Filtering happens before pagination");
        List<?> rows = (List<?>)service.recentChanges(SYSTEM, request("meaningful_only=true")).get("rows");
        assertTrue(rows.stream().map(row -> (Map<?,?>)row).noneMatch(row ->
            "refreshed".equals(row.get("change_reason"))));
        for(long sequence: List.of(5L, 6L, 7L))
        {
            assertTrue(rows.stream().map(row -> (Map<?,?>)row).anyMatch(row ->
                Long.valueOf(sequence).equals(row.get("sequence"))), "Remap transition " + sequence);
        }
        Map<String,Object> notices = service.recentChanges(SYSTEM, request(
            "meaningful_only=true&subscriber_id=999&configuration_id=B&roaming_only=true&q=NoMatchingName"));
        assertEquals(2, notices.get("total_count"), "Both receiver and system invalidations survive every filter");
        assertTrue(((List<?>)notices.get("rows")).stream().map(row -> (Map<?,?>)row)
            .allMatch(row -> row.containsKey("invalidation_scope")));
    }

    private static P25WuidAssignmentRegistry.Entry entry(int id, String configuration, long now)
    {
        return entry(new P25SubscriberIdentity(0xABCDE, 0x123, id), id + 200, configuration, now);
    }

    private static P25WuidAssignmentRegistry.Entry entry(P25SubscriberIdentity identity, int workingId,
                                                        String configuration, long now)
    {
        return new P25WuidAssignmentRegistry.Entry(0xABCDE, 0x123, workingId,
            identity, now, now, now + 20_000,
            P25WuidAssignmentRegistry.Evidence.REGISTRATION, P25WuidAssignmentRegistry.LeaseSource.FALLBACK,
            new P25SiteIdentity(0xABCDE, 0x123, 1, 2), configuration);
    }

    private static P25WuidAssignmentRegistry.Change change(long sequence, long now,
        P25WuidAssignmentRegistry.ChangeReason reason, P25SubscriberIdentity identity, Integer working,
        Integer previous)
    {
        return new P25WuidAssignmentRegistry.Change(sequence, now - 20 + sequence, reason, 0xABCDE, 0x123,
            working, previous, identity, new P25SiteIdentity(0xABCDE, 0x123, 1, 2), "A");
    }

    private static P25WuidAssignmentRegistry.Snapshot snapshot(long now, boolean stale,
        List<P25WuidAssignmentRegistry.Entry> entries, boolean observing, long gap)
    {
        return new P25WuidAssignmentRegistry.Snapshot(now, 1, 2, stale, entries.size(), entries, List.of(),
            List.of(new P25WuidAssignmentRegistry.SystemObservation(0xABCDE, 0x123, observing, gap,
                observing ? List.of("A", "B") : List.of())), 0, 0);
    }

    private static StatsRequest request(String query)
    {
        return StatsRequest.from(URI.create("http://localhost/?" + query));
    }
}
