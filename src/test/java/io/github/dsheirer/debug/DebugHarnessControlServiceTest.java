/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.debug.DebugHarnessControlAdapter.HttpResult;
import io.github.dsheirer.debug.DebugHarnessControlService.ChannelRuntime;
import io.github.dsheirer.debug.DebugHarnessControlService.ChannelCatalogSnapshot;
import io.github.dsheirer.debug.DebugHarnessControlService.ChannelSnapshot;
import io.github.dsheirer.debug.DebugHarnessControlService.RuntimeState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DebugHarnessControlServiceTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final List<DebugHarnessControlService> mServices = new ArrayList<>();

    @AfterEach
    void cleanup()
    {
        mServices.forEach(DebugHarnessControlService::close);
    }

    @Test
    void listsPrimitiveSavedChannelSnapshots() throws Exception
    {
        FakeRuntime runtime = new FakeRuntime(channel("22222222-2222-4222-8222-222222222222", "Same", true,
            RuntimeState.REGISTERED_NOT_RUNNING),
            channel("11111111-1111-4111-8111-111111111111", "Same", false, RuntimeState.STOPPED));
        DebugHarnessControlService service = service(runtime, new Clock(), new ManualScheduler());

        JsonNode root = OBJECT_MAPPER.readTree(service.channelsJson());

        assertEquals(2, root.path("channels").size());
        assertEquals("11111111-1111-4111-8111-111111111111",
            root.path("channels").get(0).path("configuration_id").asText());
        assertEquals("registered_not_running", root.path("channels").get(1).path("state").asText());
        assertFalse(root.path("channels").get(0).has("channel_id"));
    }

    @Test
    void serializesDesiredStateAndRejectsStaleRevision() throws Exception
    {
        String id = "11111111-1111-4111-8111-111111111111";
        FakeRuntime runtime = new FakeRuntime(channel(id, "Control", true, RuntimeState.STOPPED));
        DebugHarnessControlService service = service(runtime, new Clock(), new ManualScheduler());
        HttpResult created = service.createSession(60);
        JsonNode createdBody = json(created);
        String token = createdBody.path("session_id").asText();

        assertEquals(201, created.status());
        JsonNode listingDuringSession = OBJECT_MAPPER.readTree(service.channelsJson());
        assertTrue(listingDuringSession.path("session").path("state").isTextual());
        assertFalse(listingDuringSession.path("session").has("session_id"));
        assertFalse(json(service.createSession(60)).has("session_id"));
        HttpResult start = service.setChannel(token, 1, id, true);
        assertEquals(200, start.status());
        assertTrue(json(start).path("changed").asBoolean());
        assertEquals(2, json(start).path("revision").asLong());
        assertEquals(List.of("start:" + id), runtime.operations());

        HttpResult stale = service.setChannel(token, 1, id, false);
        assertEquals(409, stale.status());
        assertEquals(List.of("start:" + id), runtime.operations());

        HttpResult repeated = service.setChannel(token, 2, id, true);
        assertEquals(200, repeated.status());
        assertFalse(json(repeated).path("changed").asBoolean());
        assertEquals(3, json(repeated).path("revision").asLong());
        assertEquals(List.of("start:" + id), runtime.operations());
    }

    @Test
    void exclusiveSessionRestoresExactBaselineAndContinuesAfterFailure() throws Exception
    {
        String running = "11111111-1111-4111-8111-111111111111";
        String stopped = "22222222-2222-4222-8222-222222222222";
        FakeRuntime runtime = new FakeRuntime(channel(running, "Running", true, RuntimeState.RUNNING),
            channel(stopped, "Stopped", true, RuntimeState.STOPPED));
        DebugHarnessControlService service = service(runtime, new Clock(), new ManualScheduler());
        HttpResult created = service.createSession(60);
        String token = json(created).path("session_id").asText();

        assertEquals(409, service.createSession(60).status());
        assertEquals(403, service.endSession("wrong").status());
        assertEquals(200, service.setChannel(token, 1, running, false).status());
        assertEquals(200, service.setChannel(token, 2, stopped, true).status());
        runtime.failOn(running);

        HttpResult ended = service.endSession(token);
        assertEquals(207, ended.status());
        JsonNode body = json(ended);
        assertEquals("ended", body.path("state").asText());
        assertEquals(1, body.path("restore_failures").size());
        assertEquals(RuntimeState.RUNNING, runtime.state(running));
        assertEquals(RuntimeState.STOPPED, runtime.state(stopped));
        assertEquals(List.of("stop:" + running, "start:" + stopped, "stop:" + stopped, "start:" + running),
            runtime.operations());
    }

    @Test
    void leaseExpiryRestoresWithoutAnotherClientRequest() throws Exception
    {
        String id = "11111111-1111-4111-8111-111111111111";
        Clock clock = new Clock();
        ManualScheduler scheduler = new ManualScheduler();
        FakeRuntime runtime = new FakeRuntime(channel(id, "Control", true, RuntimeState.STOPPED));
        DebugHarnessControlService service = service(runtime, clock, scheduler);
        HttpResult created = service.createSession(10);
        String token = json(created).path("session_id").asText();
        service.setChannel(token, 1, id, true);

        clock.advanceSeconds(10);
        scheduler.runLatest();
        awaitState(runtime, id, RuntimeState.STOPPED);

        assertEquals(List.of("start:" + id, "stop:" + id), runtime.operations());
        assertEquals(404, service.getSession(token).status());
    }

    @Test
    void getSessionIsReadOnlyAndDoesNotRenewTheLease() throws Exception
    {
        String id = "11111111-1111-4111-8111-111111111111";
        Clock clock = new Clock();
        ManualScheduler scheduler = new ManualScheduler();
        FakeRuntime runtime = new FakeRuntime(channel(id, "Control", true, RuntimeState.STOPPED));
        DebugHarnessControlService service = service(runtime, clock, scheduler);
        String token = json(service.createSession(10)).path("session_id").asText();
        clock.advanceSeconds(5);
        assertEquals(200, service.getSession(token).status());

        clock.advanceSeconds(5);
        scheduler.runLatest();
        assertEquals(404, service.getSession(token).status());
    }

    @Test
    void closeNeverRestoresOrStartsChannels() throws Exception
    {
        String id = "11111111-1111-4111-8111-111111111111";
        Clock clock = new Clock();
        ManualScheduler scheduler = new ManualScheduler();
        FakeRuntime runtime = new FakeRuntime(channel(id, "Control", true, RuntimeState.RUNNING));
        DebugHarnessControlService service = service(runtime, clock, scheduler);
        String token = json(service.createSession(10)).path("session_id").asText();
        service.setChannel(token, 1, id, false);
        service.close();
        clock.advanceSeconds(10);
        scheduler.runLatest();

        assertEquals(List.of("stop:" + id), runtime.operations());
        assertEquals(RuntimeState.STOPPED, runtime.state(id));
    }

    @Test
    void boundsChannelProjectionAndRefusesAnOversizedSession() throws Exception
    {
        List<ChannelSnapshot> channels = IntStream.range(0, DebugHarnessControlService.MAXIMUM_SAVED_CHANNELS + 1)
            .mapToObj(index -> channel(new UUID(0, index + 1L).toString(), "Channel " + index, true,
                RuntimeState.STOPPED)).toList();
        FakeRuntime runtime = new FakeRuntime(channels.toArray(ChannelSnapshot[]::new));
        DebugHarnessControlService service = service(runtime, new Clock(), new ManualScheduler());

        JsonNode listing = OBJECT_MAPPER.readTree(service.channelsJson());
        assertTrue(listing.path("channels_truncated").asBoolean());
        assertEquals(DebugHarnessControlService.MAXIMUM_SAVED_CHANNELS, listing.path("channels").size());
        assertEquals(DebugHarnessControlService.MAXIMUM_SAVED_CHANNELS + 1,
            listing.path("saved_channel_count").asInt());
        assertEquals(409, service.createSession(60).status());
    }

    @Test
    void lifecycleOperationsRunOnTheDedicatedWorker() throws Exception
    {
        String id = "11111111-1111-4111-8111-111111111111";
        FakeRuntime runtime = new FakeRuntime(channel(id, "Control", true, RuntimeState.STOPPED));
        DebugHarnessControlService service = service(runtime, new Clock(), new ManualScheduler());
        String caller = Thread.currentThread().getName();
        String token = json(service.createSession(60)).path("session_id").asText();
        service.setChannel(token, 1, id, true);

        assertNotNull(runtime.operationThread());
        assertTrue(runtime.operationThread().startsWith("receiver debug control thread "));
        assertFalse(caller.equals(runtime.operationThread()));
    }

    private DebugHarnessControlService service(FakeRuntime runtime, Clock clock, ManualScheduler scheduler)
    {
        DebugHarnessControlService service = new DebugHarnessControlService(runtime, clock::nanoTime,
            clock::wallClock, scheduler);
        mServices.add(service);
        return service;
    }

    private static ChannelSnapshot channel(String id, String name, boolean runnable, RuntimeState state)
    {
        return new ChannelSnapshot(id, "System", "Site", name, "P25_PHASE1", "TUNER",
            List.of(851_000_000L), 1, true, runnable, state);
    }

    private static JsonNode json(HttpResult result) throws Exception
    {
        return OBJECT_MAPPER.readTree(result.body());
    }

    private static void awaitState(FakeRuntime runtime, String id, RuntimeState expected) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

        while(System.nanoTime() < deadline && runtime.state(id) != expected)
        {
            Thread.sleep(5);
        }

        assertEquals(expected, runtime.state(id));
    }

    private static class Clock
    {
        private final AtomicLong mNanos = new AtomicLong();
        private final AtomicLong mWall = new AtomicLong(1_700_000_000_000L);

        long nanoTime()
        {
            return mNanos.get();
        }

        long wallClock()
        {
            return mWall.get();
        }

        void advanceSeconds(long seconds)
        {
            mNanos.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
            mWall.addAndGet(TimeUnit.SECONDS.toMillis(seconds));
        }
    }

    private static class ManualScheduler implements DebugHarnessControlService.ExpiryScheduler
    {
        private final List<Runnable> mTasks = new ArrayList<>();

        @Override
        public Future<?> schedule(Runnable task, long delay, TimeUnit unit)
        {
            mTasks.add(task);
            return new FakeFuture();
        }

        Runnable latestTask()
        {
            assertFalse(mTasks.isEmpty());
            return mTasks.getLast();
        }

        void runLatest()
        {
            latestTask().run();
        }
    }

    private static class FakeFuture implements Future<Object>
    {
        @Override public boolean cancel(boolean mayInterruptIfRunning) { return true; }
        @Override public boolean isCancelled() { return false; }
        @Override public boolean isDone() { return false; }
        @Override public Object get() { return null; }
        @Override public Object get(long timeout, TimeUnit unit) { return null; }
    }

    private static class FakeRuntime implements ChannelRuntime
    {
        private final Map<String,ChannelSnapshot> mChannels = new LinkedHashMap<>();
        private final List<String> mOperations = new ArrayList<>();
        private String mFailureId;
        private String mOperationThread;

        FakeRuntime(ChannelSnapshot... channels)
        {
            for(ChannelSnapshot channel: channels)
            {
                mChannels.put(channel.configurationId(), channel);
            }
        }

        @Override
        public synchronized ChannelCatalogSnapshot listSavedChannels(int limit)
        {
            return new ChannelCatalogSnapshot(mChannels.size(), mChannels.values().stream()
                .limit(Math.max(0, limit)).toList());
        }

        @Override
        public synchronized ChannelSnapshot findSavedChannel(String id)
        {
            return mChannels.get(id);
        }

        @Override
        public synchronized boolean setProcessing(String id, boolean processing) throws Exception
        {
            ChannelSnapshot current = mChannels.get(id);
            assertNotNull(current);
            RuntimeState target = processing ? RuntimeState.RUNNING : RuntimeState.STOPPED;

            if(current.state().isStarted() == processing)
            {
                return false;
            }

            mChannels.put(id, new ChannelSnapshot(current.configurationId(), current.system(), current.site(),
                current.name(), current.decoder(), current.source(), current.frequenciesHz(), current.frequencyCount(),
                current.autoStart(), current.runnable(), target));
            mOperations.add((processing ? "start:" : "stop:") + id);
            mOperationThread = Thread.currentThread().getName();

            if(id.equals(mFailureId))
            {
                throw new Exception("injected failure");
            }

            return true;
        }

        synchronized RuntimeState state(String id)
        {
            return mChannels.get(id).state();
        }

        synchronized List<String> operations()
        {
            return List.copyOf(mOperations);
        }

        void failOn(String id)
        {
            mFailureId = id;
        }

        synchronized String operationThread()
        {
            return mOperationThread;
        }
    }
}
