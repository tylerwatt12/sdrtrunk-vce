/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelDefinition;
import io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.manager.TunerSettingsService;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class DiscoveryCompletionRegistryTest
{
    @Test
    void completedSnapshotHasItsOwnFixedRetentionInsteadOfTheRfSessionDeadline()
    {
        AtomicLong clock = new AtomicLong(1000);
        var registry = new DiscoveryCompletionRegistry(clock::get);
        var original = snapshot("one", false);
        var retained = registry.retain(original);
        assertNotSame(original, retained);
        assertEquals(clock.get() + DiscoveryAliasImportService.RETENTION_MS, retained.expiresAtMs());
        assertSame(original.saved(), retained.saved());
        assertSame(original.aliasImport(), retained.aliasImport());
        assertSame(retained, registry.get("one"));
        assertNull(registry.get("missing"));
        clock.set(retained.expiresAtMs() - 1);
        assertSame(retained, registry.get("one"));
        clock.incrementAndGet();
        assertNull(registry.get("one"));
    }

    @Test
    void retryReplacesTheSavedResultWithoutExtendingItsOriginalDeadline()
    {
        AtomicLong clock = new AtomicLong(1000);
        var registry = new DiscoveryCompletionRegistry(clock::get);
        var original = registry.retain(snapshot("one", false));
        clock.addAndGet(DiscoveryAliasImportService.RETENTION_MS - 1);
        var retried = registry.retain(snapshot("one", true));
        assertTrue(retried.saved().running());
        assertEquals("running", retried.state());
        assertEquals(original.expiresAtMs(), retried.expiresAtMs());
        assertSame(retried, registry.get("one"));
        clock.incrementAndGet();
        assertNull(registry.get("one"));
    }

    @Test
    void retryFinishingAtTheDeadlineReturnsItsOutcomeWithoutRenewingExpiredRetention()
    {
        AtomicLong clock = new AtomicLong(1000);
        var registry = new DiscoveryCompletionRegistry(clock::get);
        var admitted = registry.retain(snapshot("one", false));
        clock.set(admitted.expiresAtMs());
        var outcome = registry.retain(snapshot("one", true));
        assertTrue(outcome.saved().running());
        assertEquals(admitted.expiresAtMs(), outcome.expiresAtMs());
        assertNull(registry.get("one"));
    }

    @Test
    void capacityEvictsTheOldestCompletionEvenAfterReadingOrReplacingIt()
    {
        AtomicLong clock = new AtomicLong(1000);
        var registry = new DiscoveryCompletionRegistry(clock::get);
        for(int index = 0; index < DiscoveryAliasImportService.MAXIMUM_BATCHES; index++)
        {
            registry.retain(snapshot("session-" + index, false));
            clock.incrementAndGet();
        }
        assertNotNull(registry.get("session-0"));
        registry.retain(snapshot("session-0", true));
        registry.retain(snapshot("newest", false));
        assertNull(registry.get("session-0"));
        assertNotNull(registry.get("session-1"));
        assertNotNull(registry.get("newest"));
        registry.clear();
        assertNull(registry.get("session-1"));
        assertNull(registry.get("newest"));
    }

    @Test
    void rejectsUnsavedSnapshotsInsteadOfKeepingAnActiveWizardAlive()
    {
        var registry = new DiscoveryCompletionRegistry(() -> 1000);
        var saved = snapshot("one", false);
        var active = new SpectrumDiscoveryService.Snapshot(saved.sessionId(), "ready", null, saved.protocolId(),
            saved.tunerId(), saved.targetId(), saved.frequencyHz(), saved.expiresAtMs(), null, null, null, null,
            null, saved.radioReference(), saved.aliasImport());
        assertThrows(IllegalArgumentException.class, () -> registry.retain(active));
        assertNull(registry.get("one"));
    }

    @Test
    void retainedDataIsImmutableDtosWithNoReceiverProbeWorkerOrClosureReferences() throws Exception
    {
        var registry = new DiscoveryCompletionRegistry(() -> 1000);
        var saved = snapshot("one", false);
        var aliases = new ArrayList<>(List.of(new ChannelAdministrationService.DiscoveryAliasList(7, "County", true)));
        var template = new ChannelDefinition(null, "p25-phase1", "County", "North", "Control", null, 7,
            new ChannelDefinition.Source(List.of(770_500_000L), null, null, 770_500_000L, "Test", null),
            Map.of("modulation", "CQPSK"), List.of(), List.of(), List.of(), List.of(), ChannelDefinition.Observed.EMPTY);
        var review = new ChannelAdministrationService.DiscoveryReview(1, template, aliases, 7L, "County");
        var modes = new ArrayList<>(List.of(new DigitalTrunkedDiscoveryProbe.ModeMetrics("DMR", 30, 30, 0,
            3, 2000, 100, false, null)));
        var digital = new DigitalTrunkedDiscoveryProbe.Status("ready", "Confirmed", "target", 770_500_000L,
            2000, 30000, 0, null, modes);
        var probe = new P25DiscoveryProbe.Status("ready", "Confirmed", "target", 770_500_000L, 1000,
            2000, 30000, 0, null, null, null, "CQPSK", new P25DiscoveryProbe.Identity(0xBEE00, 0x49F, 1, 2, 0x491));
        var retained = registry.retain(new SpectrumDiscoveryService.Snapshot(saved.sessionId(), saved.state(),
            saved.reason(), saved.protocolId(), saved.tunerId(), saved.targetId(), saved.frequencyHz(),
            saved.expiresAtMs(), probe, review, saved.saved(), digital, null, saved.radioReference(), saved.aliasImport()));
        aliases.clear();
        modes.clear();
        assertEquals(1, retained.review().aliasLists().size());
        assertEquals(1, retained.digitalProbe().modes().size());
        assertThrows(UnsupportedOperationException.class, () -> retained.review().aliasLists().clear());
        assertThrows(UnsupportedOperationException.class, () -> retained.digitalProbe().modes().clear());
        assertDtoOnly(retained);
    }

    private static SpectrumDiscoveryService.Snapshot snapshot(String id, boolean running)
    {
        return new SpectrumDiscoveryService.Snapshot(id, running ? "running" : "saved", null, "p25-phase1",
            "test-tuner", "target", 770_500_000L, 500, null, null,
            new SpectrumDiscoveryService.Saved("channel-" + id, 7, running, running ? null : "Not running"),
            null, null, RadioReferenceDiscoveryResolver.Result.manual(), DiscoveryAliasImportService.Result.EMPTY);
    }

    private static void assertDtoOnly(Object value) throws Exception
    {
        if(value == null || value instanceof String || value instanceof Number || value instanceof Boolean ||
            value instanceof Enum<?>) return;
        assertFalse(value instanceof Tuner);
        assertFalse(value instanceof P25DiscoveryProbe);
        assertFalse(value instanceof DigitalTrunkedDiscoveryProbe);
        assertFalse(value instanceof TunerSettingsService.ProbeHold);
        assertFalse(value instanceof Thread || value instanceof Future<?> || value instanceof Executor || value instanceof Supplier<?>);
        if(value instanceof Iterable<?> values)
        {
            for(Object item: values) assertDtoOnly(item);
        }
        else if(value instanceof Map<?,?> values)
        {
            for(var entry: values.entrySet()) { assertDtoOnly(entry.getKey()); assertDtoOnly(entry.getValue()); }
        }
        else
        {
            assertTrue(value.getClass().isRecord(), "Only detached data records belong in completed results");
            for(RecordComponent component: value.getClass().getRecordComponents())
                assertDtoOnly(component.getAccessor().invoke(value));
        }
    }
}
