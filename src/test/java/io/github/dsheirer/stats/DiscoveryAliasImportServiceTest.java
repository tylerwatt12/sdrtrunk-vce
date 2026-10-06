/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver;
import io.github.dsheirer.configuration.ConfigurationManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DiscoveryAliasImportServiceTest
{
    @Test
    void sharedNativeSystemUsesAnyConfirmedSiteButConflictingDirectorySystemsStayUnavailable()
    {
        var selected = DiscoveryAliasImportService.groupDirectory(List.of(
            RadioReferenceDiscoveryResolver.Result.manual(), RadioReferenceDiscoveryResolver.Result.pending(),
            matched(10), matched(10)));
        assertTrue(selected.matched());
        assertEquals(10, selected.match().rrSystemId());
        var conflicting = DiscoveryAliasImportService.groupDirectory(List.of(matched(10), matched(11)));
        assertFalse(conflicting.matched());
        assertEquals("ambiguous", conflicting.state());
        var batch = new DiscoveryAliasImportService.Batch();
        batch.register(7, "County", "County", conflicting);
        assertEquals("unavailable", batch.snapshot().targets().getFirst().state());
        assertNull(batch.snapshot().targets().getFirst().systemId());
    }

    @Test
    void registersEachNewListOnceAndNoChoiceDoesNotReadOrImportAnything()
    {
        FakeOperations operations = new FakeOperations();
        var service = new DiscoveryAliasImportService(operations);
        var batch = new DiscoveryAliasImportService.Batch();
        batch.register(7, "Custom list name", "County P25", matched(10));
        batch.register(7, "Another site", "County P25", matched(10));
        service.retain("wizard", batch);
        assertEquals(1, batch.snapshot().targets().size());
        assertEquals("Custom list name", batch.snapshot().targets().getFirst().aliasListName());
        assertEquals("pending", batch.snapshot().targets().getFirst().state());
        assertFalse(batch.snapshot().complete());
        assertTrue(operations.catalogs.isEmpty());
        assertTrue(operations.applies.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.importAliases("wizard", 8));
        assertTrue(operations.catalogs.isEmpty());
    }

    @Test
    void importsMultipleListsReusesOneSystemCatalogAndRetainsSuccessForLostResponseRetry()
    {
        FakeOperations operations = new FakeOperations();
        var service = new DiscoveryAliasImportService(operations);
        var batch = new DiscoveryAliasImportService.Batch();
        batch.register(7, "North", "County", matched(10));
        batch.register(8, "South", "County", matched(10));
        batch.register(9, "City", "City", matched(11));
        service.retain("wizard", batch);
        service.importAliases("wizard", 7);
        service.importAliases("wizard", 8);
        var complete = service.importAliases("wizard", 9);
        assertTrue(complete.complete());
        assertEquals(List.of(10, 11), operations.catalogs);
        assertEquals(List.of(7L, 8L, 9L), operations.applies);
        assertEquals(complete, service.importAliases("wizard", 7));
        assertEquals(List.of(7L, 8L, 9L), operations.applies);
        assertEquals(3, complete.targets().getFirst().added());
    }

    @Test
    void failedListCanRetryWithoutReimportingSuccessfulLists()
    {
        FakeOperations operations = new FakeOperations();
        operations.failList = 8;
        var service = new DiscoveryAliasImportService(operations);
        var batch = new DiscoveryAliasImportService.Batch();
        batch.register(7, "County", "County", matched(10));
        batch.register(8, "City", "City", matched(11));
        service.retain("wizard", batch);
        service.importAliases("wizard", 7);
        var failed = service.importAliases("wizard", 8);
        assertEquals(List.of("imported", "failed"), failed.targets().stream().map(DiscoveryAliasImportService.Target::state).toList());
        assertFalse(failed.complete());
        operations.failList = 0;
        service.importAliases("wizard", 7);
        assertTrue(service.importAliases("wizard", 8).complete());
        assertEquals(List.of(7L, 8L), operations.applies);
        assertEquals(List.of(10, 11, 11), operations.catalogs);
        assertEquals(2, operations.previews.stream().filter(id -> id == 8).count());
    }

    @Test
    void publicationFailurePreservesSuccessAndStopsAllFurtherImportWritesUntilRestart()
    {
        FakeOperations operations = new FakeOperations();
        operations.publicationFailureList = 8;
        var service = new DiscoveryAliasImportService(operations);
        var batch = new DiscoveryAliasImportService.Batch();
        batch.register(7, "County", "County", matched(10));
        batch.register(8, "City", "City", matched(11));
        batch.register(9, "Other", "Other", matched(12));
        service.retain("wizard", batch);
        service.importAliases("wizard", 7);
        var stopped = service.importAliases("wizard", 8);
        assertEquals(List.of("imported", "restart_required", "restart_required"),
            stopped.targets().stream().map(DiscoveryAliasImportService.Target::state).toList());
        assertTrue(stopped.targets().get(1).message().contains("Aliases were saved"));
        assertFalse(stopped.complete());
        operations.publicationFailureList = 0;
        assertEquals(stopped, service.importAliases("wizard", 8));
        assertEquals(stopped, service.importAliases("wizard", 9));
        assertEquals(List.of(10, 11), operations.catalogs);
        assertEquals(List.of(7L), operations.applies);
    }

    @Test
    void unmatchedPendingOrUntrustedDirectoryResultsCannotImportArbitrarySystems()
    {
        FakeOperations operations = new FakeOperations();
        var service = new DiscoveryAliasImportService(operations);
        var batch = new DiscoveryAliasImportService.Batch();
        batch.register(7, "On-air", "P25 BEE00-49F", RadioReferenceDiscoveryResolver.Result.manual());
        batch.register(8, "Pending", "P25 BEE00-123", RadioReferenceDiscoveryResolver.Result.pending());
        batch.register(9, "Ambiguous", "County", new RadioReferenceDiscoveryResolver.Result("ambiguous", "", matched(10).match(), "on-air"));
        service.retain("wizard", batch);
        for(long id: List.of(7L, 8L, 9L)) service.importAliases("wizard", id);
        assertFalse(batch.snapshot().complete());
        assertTrue(batch.snapshot().targets().stream().allMatch(target -> "unavailable".equals(target.state()) && target.systemId() == null));
        assertTrue(operations.catalogs.isEmpty());
    }

    @Test
    void retainedTargetsNeedNoHardwareAndExpireAtTheirBoundedDeadline()
    {
        AtomicLong clock = new AtomicLong(1000);
        FakeOperations operations = new FakeOperations();
        var service = new DiscoveryAliasImportService(operations, clock::get);
        var batch = new DiscoveryAliasImportService.Batch();
        batch.register(7, "County", "County", matched(10));
        service.retain("closed-wizard", batch);
        // There is deliberately no receiver/wizard reference in the retained batch.
        assertTrue(service.importAliases("closed-wizard", 7).complete());
        clock.addAndGet(899999);
        service.retain("closed-wizard", batch);
        assertTrue(service.importAliases("closed-wizard", 7).complete());
        clock.incrementAndGet();
        assertThrows(IllegalStateException.class, () -> service.importAliases("closed-wizard", 7));
        for(int index = 0; index < 17; index++) service.retain("wizard-" + index, batch);
        assertThrows(IllegalStateException.class, () -> service.importAliases("wizard-0", 7));
        assertTrue(service.importAliases("wizard-16", 7).complete());
        service.clear();
        assertThrows(IllegalStateException.class, () -> service.importAliases("wizard-16", 7));
    }

    @Test
    void closingRuntimeBeforeApplyPreventsAnUncommittedImportAndEmptyCatalogSucceeds()
    {
        FakeOperations operations = new FakeOperations();
        var service = new DiscoveryAliasImportService(operations);
        var batch = new DiscoveryAliasImportService.Batch();
        batch.register(7, "County", "County", matched(10));
        service.retain("wizard", batch);
        operations.beforeApply = service::clear;
        // preview returns after clear; the current check must stop apply.
        assertEquals("failed", service.importAliases("wizard", 7).targets().getFirst().state());
        assertTrue(operations.applies.isEmpty());
        operations.beforeApply = () -> {};
        operations.rows = 0;
        service.retain("wizard", batch);
        var empty = service.importAliases("wizard", 7);
        assertTrue(empty.complete());
        assertEquals(0, empty.targets().getFirst().added());
        assertTrue(operations.applies.isEmpty());
    }

    @Test
    void concurrentRetriesForTheSameListApplyOnce() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeOperations operations = new FakeOperations();
        operations.beforeApply = () -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch(InterruptedException exception) { Thread.currentThread().interrupt(); throw new RuntimeException(exception); }
        };
        var service = new DiscoveryAliasImportService(operations);
        var batch = new DiscoveryAliasImportService.Batch();
        batch.register(7, "County", "County", matched(10));
        service.retain("wizard", batch);
        try(var executor = Executors.newFixedThreadPool(2))
        {
            var first = executor.submit(() -> service.importAliases("wizard", 7));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("importing", batch.snapshot().targets().getFirst().state());
            var second = executor.submit(() -> service.importAliases("wizard", 7));
            release.countDown();
            assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertEquals(List.of(7L), operations.applies);
        }
        finally { release.countDown(); }
    }

    private static RadioReferenceDiscoveryResolver.Result matched(int systemId)
    {
        return new RadioReferenceDiscoveryResolver.Result("matched", "", new RadioReferenceDiscoveryResolver.Match(
            systemId, 20, "County", "North", List.of(), "", ""), "radioreference");
    }

    private static final class FakeOperations implements DiscoveryAliasImportService.Operations
    {
        final List<Integer> catalogs = new ArrayList<>();
        final List<Long> previews = new ArrayList<>(), applies = new ArrayList<>();
        long failList, publicationFailureList;
        int rows = 3;
        Runnable beforeApply = () -> {};
        public DiscoveryAliasImportService.Catalog catalog(int systemId)
        { catalogs.add(systemId); return new DiscoveryAliasImportService.Catalog("catalog-" + systemId, rows); }
        public String preview(int systemId, long listId, String catalogId)
        { previews.add(listId); beforeApply.run(); return Long.toString(listId); }
        public DiscoveryAliasImportService.Counts apply(String previewId)
        {
            long listId = Long.parseLong(previewId);
            if(listId == failList) throw new IllegalStateException("Temporary failure");
            if(listId == publicationFailureList) throw new ConfigurationManager.ConfigurationPublicationException(
                "Alias configuration committed but could not be published", new IllegalStateException("observer failed"));
            applies.add(listId);
            return new DiscoveryAliasImportService.Counts(rows, 0, 0);
        }
    }
}
