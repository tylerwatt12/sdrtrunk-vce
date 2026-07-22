/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.source.tuner.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.test.TestTuner;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TunerRegistryTest
{
    @Test
    void duplicateClassesReceiveDistinctDeterministicOpaqueIds()
    {
        StubDiscoveredTuner first = new StubDiscoveredTuner(TunerClass.AIRSPY,
            "Airspy USB Bus:1 Port:2.1");
        StubDiscoveredTuner second = new StubDiscoveredTuner(TunerClass.AIRSPY,
            "Airspy USB Bus:1 Port:2.2");
        TunerRegistry registry = new TunerRegistry(() -> List.of(first, second));
        List<TunerSnapshot> initial = registry.snapshots();

        assertEquals(2, initial.size());
        assertNotEquals(initial.get(0).id(), initial.get(1).id());
        assertTrue(initial.stream().allMatch(snapshot -> snapshot.id().matches("TNR_[A-F0-9]{28}")));
        assertTrue(initial.stream().allMatch(snapshot -> snapshot.tunerClass() == TunerClass.AIRSPY));

        Set<String> initialIds = ids(initial);
        TunerRegistry reconstructed = new TunerRegistry(() -> List.of(second, first));
        assertEquals(initialIds, ids(reconstructed.snapshots()),
            "opaque IDs must not depend on discovery or display order");
        assertThrows(UnsupportedOperationException.class, () -> initial.add(initial.getFirst()));
    }

    @Test
    void eachSnapshotReflectsCurrentDiscoveryWithoutRetainingRemovedTuners()
    {
        StubDiscoveredTuner first = new StubDiscoveredTuner(TunerClass.RTL2832,
            "RTL-2832 USB Bus:3 Port:4");
        StubDiscoveredTuner second = new StubDiscoveredTuner(TunerClass.RTL2832,
            "RTL-2832 USB Bus:3 Port:5");
        AtomicReference<List<DiscoveredTuner>> discovered = new AtomicReference<>(List.of(first, second));
        TunerRegistry registry = new TunerRegistry(discovered::get);
        String firstId = registry.snapshots().stream().filter(snapshot -> snapshot.id().equals(
            new TunerRegistry(() -> List.of(first)).snapshots().getFirst().id())).findFirst().orElseThrow().id();

        discovered.set(List.of(first));
        assertEquals(List.of(firstId), registry.snapshots().stream().map(TunerSnapshot::id).toList());
        assertTrue(registry.findSnapshot(firstId.toLowerCase()).isPresent());

        discovered.set(List.of());
        assertTrue(registry.snapshots().isEmpty());
        assertFalse(registry.findSnapshot(firstId).isPresent());
        assertTrue(registry.availableTargets().isEmpty());
    }

    @Test
    void snapshotWorkIsBounded()
    {
        List<DiscoveredTuner> discovered = new ArrayList<>();

        for(int index = 0; index < TunerRegistry.MAXIMUM_SNAPSHOT_TUNERS + 20; index++)
        {
            discovered.add(new StubDiscoveredTuner(TunerClass.TEST_TUNER, "test-" + index));
        }

        TunerRegistry registry = new TunerRegistry(() -> discovered);
        assertEquals(TunerRegistry.MAXIMUM_SNAPSHOT_TUNERS, registry.snapshots().size());
    }

    @Test
    void webSnapshotsNeverRequestHardwareBackedTunerIdentity()
    {
        StubDiscoveredTuner discovered = new StubDiscoveredTuner(TunerClass.HACKRF,
            "HackRF USB Bus:1 Port:7");
        discovered.install(new IdentityReadFailsTuner());
        TunerRegistry registry = new TunerRegistry(() -> List.of(discovered));

        assertEquals(1, registry.snapshots().size());
        assertEquals(1, registry.availableTargets().size());
        assertTrue(registry.snapshots().getFirst().label().length() <= 64);
    }

    private static Set<String> ids(List<TunerSnapshot> snapshots)
    {
        return new HashSet<>(snapshots.stream().map(TunerSnapshot::id).toList());
    }

    private static class StubDiscoveredTuner extends DiscoveredTuner
    {
        private final TunerClass mTunerClass;
        private final String mId;

        private StubDiscoveredTuner(TunerClass tunerClass, String id)
        {
            mTunerClass = tunerClass;
            mId = id;
        }

        @Override
        public TunerClass getTunerClass()
        {
            return mTunerClass;
        }

        @Override
        public String getId()
        {
            return mId;
        }

        @Override
        public void start()
        {
        }

        private void install(Tuner tuner)
        {
            mTuner = tuner;
        }
    }

    private static class IdentityReadFailsTuner extends TestTuner
    {
        private IdentityReadFailsTuner()
        {
            super(null);
        }

        @Override
        public TunerClass getTunerClass()
        {
            return TunerClass.HACKRF;
        }

        @Override
        public String getPreferredName()
        {
            throw new AssertionError("Web snapshot attempted a hardware-backed preferred-name read");
        }

        @Override
        public String getUniqueID()
        {
            throw new AssertionError("Web snapshot attempted a hardware-backed unique-ID read");
        }

        @Override
        public TunerType getTunerType()
        {
            throw new AssertionError("Web snapshot attempted a hardware-backed tuner-type read");
        }
    }
}
