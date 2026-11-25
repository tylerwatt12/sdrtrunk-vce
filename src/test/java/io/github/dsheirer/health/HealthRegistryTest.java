/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HealthRegistryTest
{
    @Test
    void history_reads_do_not_start_sampler_and_last_enabled_node_releases_it()
    {
        HealthRegistry registry = new HealthRegistry();
        HealthHistory history = registry.getHistory();
        assertNull(history.latest());
        assertFalse(registry.isSamplingForTest());
        try
        {
            assertSame(history, registry.retainHistory());
            registry.snapshot();
            assertNotNull(history.latest());
            assertTrue(registry.isSamplingForTest());
            registry.retainHistory();
            registry.releaseHistory();
            assertTrue(registry.isSamplingForTest(), "A second provider node still needs sampling");
            registry.releaseHistory();
            assertFalse(registry.isSamplingForTest());
            assertSame(history, registry.getHistory());
            assertNotNull(history.latest(), "Disabling preserves bounded history for an honest restart boundary");
            registry.retainHistory();
            assertTrue(registry.isSamplingForTest());
        }
        finally
        {
            registry.releaseHistory();
            registry.releaseHistory();
        }
        assertFalse(registry.isSamplingForTest());
    }
    @Test
    void replaced_runtime_supersedes_old_totals_and_late_lookup_cannot_revive_it()
    {
        HealthRegistry registry = new HealthRegistry();
        Channel original = channel("Before edit", "123e4567-e89b-12d3-a456-426614174001");
        ChannelHealth before = registry.channel(original);
        before.started(1_000);
        before.stopped(2_000);
        registry.retainHistory();
        registry.snapshot();
        assertNotNull(registry.existingChannel(original), "A stopped current receiver remains reportable");
        Channel replacement = channel("After edit", original.getPersistedConfigurationId());
        ChannelHealth after = registry.channel(replacement);
        assertNull(registry.existingChannel(original));
        assertSame(after, registry.existingChannel(replacement));
        registry.snapshot();
        assertEquals(1, registry.getHistory().latest().channels().size());
        assertEquals("After edit", registry.getHistory().latest().channels().getFirst().info().name());
        assertNull(registry.existingChannel(original), "Reading late metadata must not register obsolete totals");
        registry.releaseHistory();
    }

    @Test
    void worker_reconciliation_releases_deleted_saved_receivers_and_retains_current_stopped_ones()
    {
        HealthRegistry registry = new HealthRegistry();
        Channel channel = channel("Current saved receiver", "123e4567-e89b-12d3-a456-426614174002");
        AtomicReference<List<Channel>> configured = new AtomicReference<>(List.of(channel));
        registry.setConfiguredChannelsSupplier(configured::get);
        registry.retainHistory();
        ChannelHealth health = registry.channel(channel);
        health.started(1_000);
        health.stopped(2_000);
        registry.snapshot();
        assertNotNull(registry.existingChannel(channel));
        assertEquals(1, registry.getHistory().latest().channels().size());
        configured.set(List.of());
        registry.snapshot();
        assertNull(registry.existingChannel(channel));
        assertTrue(registry.getHistory().latest().channels().isEmpty());
        registry.releaseHistory();
    }

    private static Channel channel(String name, String configurationId)
    {
        Channel channel = new Channel(name);
        channel.setConfigurationId(configurationId);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        return channel;
    }

    @Test
    void lifecycle_registration_and_retirement_do_not_wait_for_snapshot_monitor() throws Exception
    {
        HealthRegistry registry = new HealthRegistry();
        Channel channel = channel("Lifecycle during slow reporting", "123e4567-e89b-12d3-a456-426614174003");
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread lifecycle = new Thread(() ->
        {
            try
            {
                assertNotNull(registry.channel(channel));
                registry.retire(channel);
            }
            catch(Throwable exception)
            {
                failure.set(exception);
            }
            finally
            {
                finished.countDown();
            }
        }, "health-lifecycle-registration");
        //snapshot() owns this monitor throughout report projection. New lifecycle construction and deletion must
        //remain independent even if that observer is paused, and deletion must work with no sampler running.
        synchronized(registry)
        {
            lifecycle.start();
            assertTrue(finished.await(2, TimeUnit.SECONDS), "Lifecycle waited for the reporting monitor");
        }
        lifecycle.join(2_000);
        if(failure.get() != null) throw new AssertionError(failure.get());
        assertNull(registry.existingChannel(channel));
        assertFalse(registry.isSamplingForTest());
    }

    @Test
    void only_union_of_enabled_provider_scopes_collects_and_duplicate_owner_retain_is_idempotent()
    {
        HealthRegistry registry = new HealthRegistry();
        Channel one = channel("One", "123e4567-e89b-12d3-a456-426614174011");
        Channel two = channel("Two", "123e4567-e89b-12d3-a456-426614174012");
        ChannelHealth a = registry.channel(one);
        ChannelHealth b = registry.channel(two);
        assertFalse(a.isCollecting());
        assertFalse(b.isCollecting());
        Object first = new Object();
        Object shared = new Object();
        try
        {
            registry.retainHistory(first, info -> info.channelId() == one.getChannelID());
            registry.retainHistory(first, info -> info.channelId() == one.getChannelID());
            registry.snapshot();
            assertTrue(a.isCollecting());
            assertFalse(b.isCollecting());
            assertEquals(1, registry.getHistory().latest().channels().size());
            long generation = a.collectionEpoch();
            registry.retainHistory(shared, info -> info.channelId() == one.getChannelID());
            registry.releaseHistory(first);
            registry.snapshot();
            assertTrue(a.isCollecting());
            assertEquals(generation, a.collectionEpoch(), "shared subscribers do not restart accounting");
            registry.releaseHistory(shared);
            assertFalse(a.isCollecting());
            assertFalse(registry.isSamplingForTest(), "duplicate retain did not leave an active reader");
            registry.retainHistory(first, info -> info.channelId() == two.getChannelID());
            registry.snapshot();
            assertFalse(a.isCollecting());
            assertTrue(b.isCollecting());
            assertEquals(1, registry.getHistory().latest().channels().size());
        }
        finally
        {
            registry.releaseHistory(first);
            registry.releaseHistory(shared);
        }
    }

}
