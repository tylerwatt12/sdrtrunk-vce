/* Copyright (C) 2026. Distributed under the GNU General Public License, version 3 or later. */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.channel.metadata.activity.ChannelActivityEvent;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityModel;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySnapshot;
import io.github.dsheirer.remote.RemoteOriginLookup;
import io.github.dsheirer.sample.Listener;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Controls the legal worker interleavings that previously consumed navigation changes without an admitted resync. */
class StatsLiveServiceNavigationRecoveryTest
{
    private static final String CONFIGURATION = "728d2d66-de4e-476b-a696-919f32dd4d12";

    @Test
    void catalogChangeDuringInitialProjectionReachesAnAlreadySubscribedBrowser() throws Exception
    {
        try(Fixture fixture = new Fixture(true, false))
        {
            fixture.service.start();
            fixture.initialProjection.awaitEntered();
            try(var subscription = fixture.service.subscribeChannelActivity())
            {
                var browser = fixture.service.snapshotWire();
                assertFalse(wire(browser).contains("\"entity_ref\""));
                fixture.navigation(0x49F);
                fixture.initialProjection.release();
                Map<String,Object> snapshot = resync(subscription);
                assertTrue(revision(snapshot) >= browser.revision());
                assertEquals("p25:bee00:49f", table(snapshot).get("radio_system_key"));
                assertTrue(wire(fixture.service.snapshotWire()).contains("\"entity_ref\""));
                fixture.publish("next");
                var next = subscription.poll(3, TimeUnit.SECONDS);
                assertNotNull(next);
                assertEquals("activity_table", next.name());
            }
        }
    }

    @Test
    void navigationRecoveryIncludesActivityAlreadyCoveredByTheBrowserSnapshot() throws Exception
    {
        try(Fixture fixture = new Fixture(false, true))
        {
            fixture.service.start();
            fixture.idleCheck.awaitEntered();
            try(var subscription = fixture.service.subscribeChannelActivity())
            {
                fixture.publish("queued");
                var browser = fixture.service.snapshotWire();
                assertEquals(2L, browser.revision());
                assertFalse(wire(browser).contains("\"entity_ref\""));
                fixture.navigation(0x49F);
                fixture.idleCheck.release();
                Map<String,Object> recovered = resync(subscription);
                assertEquals(browser.revision(), revision(recovered));
                assertEquals("queued", row(recovered).get("key"));
                assertEquals("p25:bee00:49f", table(recovered).get("radio_system_key"));
                assertNull(subscription.poll(150, TimeUnit.MILLISECONDS), "covered event2 must not follow resync2");
                fixture.publish("later");
                var later = subscription.poll(3, TimeUnit.SECONDS);
                assertNotNull(later);
                assertEquals("activity_table", later.name());
                assertEquals(3L, ((Number)((Map<?,?>)later.data()).get("revision")).longValue());
            }
        }
    }

    @Test
    void catalogChangeDuringAuthoritativeRecoveryGetsItsOwnCurrentResync() throws Exception
    {
        Barrier authoritativeRead = new Barrier(true);
        try(Fixture fixture = new Fixture(false, true))
        {
            fixture.service.start();
            fixture.idleCheck.awaitEntered();
            try(var subscription = fixture.service.subscribeChannelActivity())
            {
                fixture.workerSnapshotHook.set(authoritativeRead::pauseOnce);
                fixture.navigation(0x49F);
                fixture.idleCheck.release();
                authoritativeRead.awaitEntered();
                // Recovery has captured catalog B and source1. A browser now receives source2/catalog C.
                fixture.publish("queued");
                fixture.navigation(0x4A0);
                var browser = fixture.service.snapshotWire();
                assertEquals(2L, browser.revision());
                assertTrue(wire(browser).contains("p25:bee00:4a0"));
                authoritativeRead.release();
                Map<String,Object> earlier = resync(subscription);
                assertEquals(1L, revision(earlier));
                assertEquals("p25:bee00:49f", table(earlier).get("radio_system_key"));
                Map<String,Object> current = resync(subscription);
                assertTrue(revision(current) >= browser.revision());
                assertEquals("p25:bee00:4a0", table(current).get("radio_system_key"));
                assertEquals("queued", row(current).get("key"));
                assertNull(subscription.poll(150, TimeUnit.MILLISECONDS));
            }
        }
        finally { authoritativeRead.release(); }
    }

    @Test
    void remoteOriginChangeDuringInitialProjectionUsesTheSameRecovery() throws Exception
    {
        try(Fixture fixture = new Fixture(true, false))
        {
            fixture.service.start();
            fixture.initialProjection.awaitEntered();
            try(var subscription = fixture.service.subscribeChannelActivity())
            {
                var browser = fixture.service.snapshotWire();
                assertFalse(wire(browser).contains("remote_origin"));
                fixture.origins.set(new RemoteOriginLookup.OriginSnapshot(1L, Map.of(CONFIGURATION,
                    new RemoteOriginLookup.RemoteOrigin("sender", "Sender", "feed", "Feed", null, null))));
                fixture.initialProjection.release();
                Map<String,Object> recovered = resync(subscription);
                assertTrue(revision(recovered) >= browser.revision());
                assertEquals(Map.of("remote", true), table(recovered).get("remote_origin"));
            }
        }
    }

    private static final class Fixture implements AutoCloseable
    {
        final TestChannelActivitySource delegate = new TestChannelActivitySource();
        final AtomicReference<WebEntityNavigationCatalog.Snapshot> loaded =
            new AtomicReference<>(WebEntityNavigationCatalog.Snapshot.empty());
        final WebEntityNavigationCatalog catalog = new WebEntityNavigationCatalog(loaded::get, 60_000L);
        final AtomicReference<RemoteOriginLookup.OriginSnapshot> origins =
            new AtomicReference<>(RemoteOriginLookup.OriginSnapshot.EMPTY);
        final AtomicReference<Runnable> workerSnapshotHook = new AtomicReference<>();
        final Barrier initialProjection;
        final Barrier idleCheck;
        final StatsLiveService service;

        Fixture(boolean blockInitial, boolean blockIdle)
        {
            initialProjection = new Barrier(blockInitial);
            idleCheck = new Barrier(blockIdle);
            StatsLiveService.ActivitySource source = new StatsLiveService.ActivitySource()
            {
                @Override public ChannelActivityModel.SnapshotSet snapshot()
                {
                    var snapshot = delegate.snapshot();
                    Runnable hook = workerSnapshotHook.get();
                    if(worker() && hook != null) hook.run();
                    return snapshot;
                }
                @Override public void addListener(Listener<ChannelActivityEvent> listener) { delegate.addListener(listener); }
                @Override public void removeListener(Listener<ChannelActivityEvent> listener) { delegate.removeListener(listener); }
            };
            service = StatsLiveService.fromActivitySource(source, catalog, () -> {
                if(worker()) idleCheck.pauseOnce();
                return origins.get();
            });
            publish("initial", new AbstractList<>() {
                @Override public String get(int index) { if(worker()) initialProjection.pauseOnce(); return "CONTROL"; }
                @Override public int size() { return 1; }
            });
        }

        void publish(String key) { publish(key, List.of("CONTROL")); }

        void publish(String key, List<String> tags)
        {
            var row = new ChannelActivitySnapshot.Row(key, "Control", CONFIGURATION, "ACTIVE", tags, 1L, "1",
                451_000_000L, null, -25.5, 98.0, 1_000L, 1L, 0L, 0L, 0L, 0L, 1_000L,
                null, null, null, null, null, null, null, null, null, null, null, null,
                "DMR", null, null, "CURRENT_CONTROL");
            delegate.publish(new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT,
                new ChannelActivitySnapshot("site", "Live", "County", "Site", "Primary", CONFIGURATION,
                    true, true, List.of(), List.of(row))));
        }

        void navigation(int systemId)
        {
            loaded.set(WebEntityNavigationCatalog.Snapshot.of(List.of(new WebEntityNavigationCatalog.Channel(
                CONFIGURATION, WebEntityRef.channel(CONFIGURATION),
                WebEntityRef.radioSystem("p25:bee00:" + Integer.toHexString(systemId)), 1, 0, 0xBEE00, systemId))));
            catalog.refreshNow();
        }

        @Override public void close()
        {
            initialProjection.release();
            idleCheck.release();
            service.close();
        }
    }

    private static final class Barrier
    {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        final AtomicBoolean enabled;
        Barrier(boolean enabled) { this.enabled = new AtomicBoolean(enabled); }
        void pauseOnce()
        {
            if(!enabled.compareAndSet(true, false)) return;
            entered.countDown();
            try { assertTrue(released.await(10, TimeUnit.SECONDS), "controlled worker barrier timed out"); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        }
        void awaitEntered() throws InterruptedException { assertTrue(entered.await(3, TimeUnit.SECONDS)); }
        void release() { released.countDown(); }
    }

    private static boolean worker() { return Thread.currentThread().getName().startsWith("stats live projection"); }
    private static String wire(StatsLiveService.SnapshotWire snapshot) throws Exception
    {
        byte[] bytes = snapshot.frame().bytes(false);
        return new String(bytes, LiveMultiplexFrame.HEADER_BYTES, bytes.length - LiveMultiplexFrame.HEADER_BYTES,
            StandardCharsets.UTF_8);
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> resync(StatsLiveEventHub.Subscription subscription) throws Exception
    {
        var event = subscription.poll(3, TimeUnit.SECONDS);
        assertNotNull(event);
        assertEquals("activity_resync", event.name());
        return (Map<String,Object>)((Map<?,?>)event.data()).get("snapshot");
    }
    private static long revision(Map<String,Object> snapshot) { return ((Number)snapshot.get("revision")).longValue(); }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> table(Map<String,Object> snapshot)
    { return ((List<Map<String,Object>>)snapshot.get("tables")).getFirst(); }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> row(Map<String,Object> snapshot)
    { return ((List<Map<String,Object>>)table(snapshot).get("rows")).getFirst(); }
}
