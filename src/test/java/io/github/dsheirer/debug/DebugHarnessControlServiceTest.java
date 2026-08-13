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
import io.github.dsheirer.controller.channel.Channel;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
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
    void endingSessionLeavesUntouchedOperatorChangesAlone() throws Exception
    {
        String touched = "11111111-1111-4111-8111-111111111111";
        String operatorChanged = "22222222-2222-4222-8222-222222222222";
        FakeRuntime runtime = new FakeRuntime(channel(touched, "Touched", true, RuntimeState.STOPPED),
            channel(operatorChanged, "Operator", true, RuntimeState.STOPPED));
        DebugHarnessControlService service = service(runtime, new Clock(), new ManualScheduler());
        String token = json(service.createSession(60)).path("session_id").asText();

        assertEquals(200, service.setChannel(token, 1, touched, true).status());
        runtime.setExternalState(operatorChanged, RuntimeState.RUNNING);
        assertEquals(200, service.endSession(token).status());

        assertEquals(RuntimeState.STOPPED, runtime.state(touched));
        assertEquals(RuntimeState.RUNNING, runtime.state(operatorChanged));
        assertEquals(List.of("start:" + touched, "stop:" + touched), runtime.operations());
    }

    @Test
    void endingSessionDoesNotUndoAnExternalChangeToATouchedChannel() throws Exception
    {
        String id = "11111111-1111-4111-8111-111111111111";
        FakeRuntime runtime = new FakeRuntime(channel(id, "Control", true, RuntimeState.STOPPED));
        DebugHarnessControlService service = service(runtime, new Clock(), new ManualScheduler());
        String token = json(service.createSession(60)).path("session_id").asText();

        assertEquals(200, service.setChannel(token, 1, id, true).status());
        assertEquals(200, service.setChannel(token, 2, id, false).status());
        runtime.setExternalState(id, RuntimeState.RUNNING);

        HttpResult ended = service.endSession(token);
        assertEquals(200, ended.status());
        assertEquals(RuntimeState.RUNNING, runtime.state(id));
        assertEquals(List.of("start:" + id, "stop:" + id), runtime.operations());
        assertEquals(id, json(ended).path("restore_skipped_external_change_configuration_ids").get(0).asText());
    }

    @Test
    void noOpRequestDoesNotClaimAnOperatorChangedChannel() throws Exception
    {
        String id = "11111111-1111-4111-8111-111111111111";
        FakeRuntime runtime = new FakeRuntime(channel(id, "Control", true, RuntimeState.STOPPED));
        DebugHarnessControlService service = service(runtime, new Clock(), new ManualScheduler());
        String token = json(service.createSession(60)).path("session_id").asText();

        HttpResult noChange = service.setChannel(token, 1, id, false);
        assertEquals(200, noChange.status());
        assertFalse(json(noChange).path("changed").asBoolean());
        runtime.setExternalState(id, RuntimeState.RUNNING);

        assertEquals(200, service.endSession(token).status());
        assertEquals(RuntimeState.RUNNING, runtime.state(id));
        assertTrue(runtime.operations().isEmpty());
    }

    @Test
    void startsRegisteredButNotRunningChannelAndRestoresItAsStopped() throws Exception
    {
        String id = "11111111-1111-4111-8111-111111111111";
        FakeRuntime runtime = new FakeRuntime(channel(id, "Degraded", true, RuntimeState.REGISTERED_NOT_RUNNING));
        DebugHarnessControlService service = service(runtime, new Clock(), new ManualScheduler());
        String token = json(service.createSession(60)).path("session_id").asText();

        HttpResult started = service.setChannel(token, 1, id, true);
        assertEquals(200, started.status());
        assertTrue(json(started).path("changed").asBoolean());
        assertEquals(RuntimeState.RUNNING, runtime.state(id));

        assertEquals(200, service.endSession(token).status());
        assertEquals(RuntimeState.STOPPED, runtime.state(id));
        assertEquals(List.of("start:" + id, "stop:" + id), runtime.operations());
    }

    @Test
    void rejectedExpiryScheduleDoesNotCreateAnUnboundedSession()
    {
        FakeRuntime runtime = new FakeRuntime(channel("11111111-1111-4111-8111-111111111111", "Control", true,
            RuntimeState.STOPPED));
        DebugHarnessControlService service = new DebugHarnessControlService(runtime, System::nanoTime,
            System::currentTimeMillis, (task, delay, unit) -> {
                throw new RejectedExecutionException("injected");
            });
        mServices.add(service);

        assertEquals(503, service.createSession(60).status());
        assertEquals(404, service.getSession("missing").status());
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
    void closeWaitsForAnInFlightMutationToQuiesce() throws Exception
    {
        String id = "11111111-1111-4111-8111-111111111111";
        FakeRuntime runtime = new FakeRuntime(channel(id, "Control", true, RuntimeState.STOPPED));
        DebugHarnessControlService service = service(runtime, new Clock(), new ManualScheduler());
        String token = json(service.createSession(60)).path("session_id").asText();
        runtime.blockNextMutation();
        ExecutorService callers = Executors.newFixedThreadPool(2);

        try
        {
            Future<HttpResult> mutation = callers.submit(() -> service.setChannel(token, 1, id, true));
            assertTrue(runtime.awaitMutationBlocked());
            Future<?> closing = callers.submit(service::close);
            awaitClosed(service);
            Thread.sleep(100);
            assertFalse(closing.isDone());

            runtime.releaseMutation();
            closing.get(2, TimeUnit.SECONDS);
            assertEquals(503, mutation.get(2, TimeUnit.SECONDS).status());
            assertEquals(RuntimeState.RUNNING, runtime.state(id));
            assertEquals(List.of("start:" + id), runtime.operations());
        }
        finally
        {
            runtime.releaseMutation();
            callers.shutdownNow();
        }
    }

    @Test
    void closeBetweenMutationAndLeaseRescheduleNeverRestoresDuringShutdown() throws Exception
    {
        String id = "11111111-1111-4111-8111-111111111111";
        FakeRuntime runtime = new FakeRuntime(channel(id, "Control", true, RuntimeState.STOPPED));
        Clock clock = new Clock();
        BlockingRejectSecondSchedule scheduler = new BlockingRejectSecondSchedule();
        DebugHarnessControlService service = new DebugHarnessControlService(runtime, clock::nanoTime,
            clock::wallClock, scheduler);
        mServices.add(service);
        String token = json(service.createSession(60)).path("session_id").asText();
        ExecutorService callers = Executors.newFixedThreadPool(2);

        try
        {
            Future<HttpResult> mutation = callers.submit(() -> service.setChannel(token, 1, id, true));
            assertTrue(scheduler.awaitRescheduleBlocked());
            assertEquals(RuntimeState.RUNNING, runtime.state(id));
            Future<?> closing = callers.submit(service::close);
            awaitClosed(service);
            assertFalse(closing.isDone());

            scheduler.releaseReschedule();
            HttpResult response = mutation.get(2, TimeUnit.SECONDS);
            closing.get(2, TimeUnit.SECONDS);

            assertEquals(503, response.status());
            assertTrue(json(response).path("error").asText().contains("closing"));
            assertEquals("skipped_application_shutdown", json(response).path("restore_outcome").asText());
            assertFalse(json(response).path("restore_attempted").asBoolean());
            assertEquals(RuntimeState.RUNNING, runtime.state(id));
            assertEquals(List.of("start:" + id), runtime.operations());
        }
        finally
        {
            scheduler.releaseReschedule();
            callers.shutdownNow();
        }
    }

    @Test
    void ownedExpirySchedulerCloseWaitsForRunningTimerWorkToQuiesce() throws Exception
    {
        DebugHarnessControlService.OwnedExpiryScheduler scheduler =
            new DebugHarnessControlService.OwnedExpiryScheduler();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService closer = Executors.newSingleThreadExecutor();
        scheduler.schedule(() -> awaitIgnoringInterrupt(entered, release), 0, TimeUnit.NANOSECONDS);
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        try
        {
            Future<?> closing = closer.submit(scheduler::close);
            Thread.sleep(100);
            assertFalse(closing.isDone());
            release.countDown();
            closing.get(2, TimeUnit.SECONDS);
        }
        finally
        {
            release.countDown();
            scheduler.close();
            closer.shutdownNow();
        }
    }

    @Test
    void boundsChannelProjectionWithoutCopyingTheWholeCatalogIntoASession() throws Exception
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
        assertEquals(201, service.createSession(60).status());
    }

    @Test
    void boundsChangedChannelOwnership() throws Exception
    {
        List<ChannelSnapshot> channels = IntStream.range(0, DebugHarnessControlService.MAXIMUM_SAVED_CHANNELS + 1)
            .mapToObj(index -> channel(new UUID(0, index + 1L).toString(), "Channel " + index, true,
                RuntimeState.STOPPED)).toList();
        FakeRuntime runtime = new FakeRuntime(channels.toArray(ChannelSnapshot[]::new));
        DebugHarnessControlService service = service(runtime, new Clock(), new ManualScheduler());
        String token = json(service.createSession(60)).path("session_id").asText();

        for(int index = 0; index < DebugHarnessControlService.MAXIMUM_SAVED_CHANNELS; index++)
        {
            assertEquals(200, service.setChannel(token, index + 1L, channels.get(index).configurationId(), true).status());
        }

        assertEquals(409, service.setChannel(token, DebugHarnessControlService.MAXIMUM_SAVED_CHANNELS + 1L,
            channels.getLast().configurationId(), true).status());
        assertEquals(RuntimeState.STOPPED, runtime.state(channels.getLast().configurationId()));
    }

    @Test
    void rejectedLeaseRescheduleRestoresChangedChannelAndEndsSession() throws Exception
    {
        String id = "11111111-1111-4111-8111-111111111111";
        FakeRuntime runtime = new FakeRuntime(channel(id, "Control", true, RuntimeState.STOPPED));
        Clock clock = new Clock();
        DebugHarnessControlService service = new DebugHarnessControlService(runtime, clock::nanoTime,
            clock::wallClock, new RejectSecondSchedule());
        mServices.add(service);
        String token = json(service.createSession(60)).path("session_id").asText();

        HttpResult response = service.setChannel(token, 1, id, true);
        assertEquals(503, response.status());
        assertEquals("complete", json(response).path("restore_outcome").asText());
        assertTrue(json(response).path("restore_attempted").asBoolean());
        assertEquals(RuntimeState.STOPPED, runtime.state(id));
        assertEquals(List.of("start:" + id, "stop:" + id), runtime.operations());
        assertEquals(404, service.getSession(token).status());
    }

    @Test
    void expiryRunsBeforeQueuedTrafficWhenOrdinaryQueueIsFull() throws Exception
    {
        String id = "11111111-1111-4111-8111-111111111111";
        Clock clock = new Clock();
        ManualScheduler scheduler = new ManualScheduler();
        FakeRuntime runtime = new FakeRuntime(channel(id, "Control", true, RuntimeState.STOPPED));
        DebugHarnessControlService service = service(runtime, clock, scheduler);
        String token = json(service.createSession(10)).path("session_id").asText();
        assertEquals(200, service.setChannel(token, 1, id, true).status());
        runtime.blockNextListing();
        ExecutorService clients = Executors.newFixedThreadPool(9);
        List<Future<byte[]>> calls = new ArrayList<>();

        try
        {
            calls.add(clients.submit(service::channelsJson));
            assertTrue(runtime.awaitListingBlocked());

            for(int x = 0; x < 8; x++)
            {
                calls.add(clients.submit(service::channelsJson));
            }

            awaitPendingRequests(service, 8);
            clock.advanceSeconds(10);
            scheduler.runLatest();
            runtime.releaseListing();
            awaitState(runtime, id, RuntimeState.STOPPED);

            for(Future<byte[]> call: calls)
            {
                assertNotNull(call.get(2, TimeUnit.SECONDS));
            }

            assertTrue(runtime.listingStates().size() >= 2);
            assertEquals(RuntimeState.RUNNING, runtime.listingStates().get(0));
            assertEquals(RuntimeState.STOPPED, runtime.listingStates().get(1));
        }
        finally
        {
            runtime.releaseListing();
            clients.shutdownNow();
        }
    }

    @Test
    void excludesTemporaryTrafficChannelsFromSavedChannelContract()
    {
        assertTrue(DebugHarnessControlService.isSavedStandard(new Channel("Saved", Channel.ChannelType.STANDARD)));
        assertFalse(DebugHarnessControlService.isSavedStandard(new Channel("Traffic", Channel.ChannelType.TRAFFIC)));
        assertFalse(DebugHarnessControlService.isSavedStandard(null));
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

    private static void awaitPendingRequests(DebugHarnessControlService service, int expected) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

        while(System.nanoTime() < deadline && service.pendingRequestCount() != expected)
        {
            Thread.sleep(5);
        }

        assertEquals(expected, service.pendingRequestCount());
    }

    private static void awaitClosed(DebugHarnessControlService service) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

        while(System.nanoTime() < deadline && !service.isClosed())
        {
            Thread.sleep(5);
        }

        assertTrue(service.isClosed());
    }

    private static void awaitIgnoringInterrupt(CountDownLatch entered, CountDownLatch release)
    {
        entered.countDown();
        boolean interrupted = false;

        while(release.getCount() > 0)
        {
            try
            {
                release.await();
            }
            catch(InterruptedException _)
            {
                interrupted = true;
            }
        }

        if(interrupted)
        {
            Thread.currentThread().interrupt();
        }
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

    private static class RejectSecondSchedule implements DebugHarnessControlService.ExpiryScheduler
    {
        private int mCount;

        @Override
        public Future<?> schedule(Runnable task, long delay, TimeUnit unit)
        {
            if(++mCount > 1)
            {
                throw new RejectedExecutionException("injected");
            }

            return new FakeFuture();
        }
    }

    private static class BlockingRejectSecondSchedule implements DebugHarnessControlService.ExpiryScheduler
    {
        private final CountDownLatch mRescheduleEntered = new CountDownLatch(1);
        private final CountDownLatch mRescheduleRelease = new CountDownLatch(1);
        private int mCount;

        @Override
        public Future<?> schedule(Runnable task, long delay, TimeUnit unit)
        {
            if(++mCount == 1)
            {
                return new FakeFuture();
            }

            awaitIgnoringInterrupt(mRescheduleEntered, mRescheduleRelease);
            throw new RejectedExecutionException("injected after shutdown begins");
        }

        boolean awaitRescheduleBlocked() throws InterruptedException
        {
            return mRescheduleEntered.await(2, TimeUnit.SECONDS);
        }

        void releaseReschedule()
        {
            mRescheduleRelease.countDown();
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
        private final List<RuntimeState> mListingStates = new ArrayList<>();
        private volatile CountDownLatch mListingEntered;
        private volatile CountDownLatch mListingRelease;
        private boolean mBlockNextListing;
        private volatile CountDownLatch mMutationEntered;
        private volatile CountDownLatch mMutationRelease;
        private boolean mBlockNextMutation;
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
        public synchronized ChannelCatalogSnapshot listSavedChannels(int limit) throws Exception
        {
            RuntimeState state = mChannels.isEmpty() ? RuntimeState.STOPPED :
                mChannels.values().iterator().next().state();
            mListingStates.add(state);
            CountDownLatch entered = mBlockNextListing ? mListingEntered : null;
            CountDownLatch release = mListingRelease;

            if(entered != null)
            {
                mBlockNextListing = false;
                entered.countDown();

                if(release != null && !release.await(2, TimeUnit.SECONDS))
                {
                    throw new IllegalStateException("Timed out waiting to release injected listing block");
                }
            }

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
            if(mBlockNextMutation)
            {
                mBlockNextMutation = false;
                CountDownLatch entered = mMutationEntered;
                CountDownLatch release = mMutationRelease;
                entered.countDown();
                boolean interrupted = false;

                while(release.getCount() > 0)
                {
                    try
                    {
                        release.await();
                    }
                    catch(InterruptedException _)
                    {
                        interrupted = true;
                    }
                }

                if(interrupted)
                {
                    Thread.currentThread().interrupt();
                }
            }

            ChannelSnapshot current = mChannels.get(id);
            assertNotNull(current);
            RuntimeState target = processing ? RuntimeState.RUNNING : RuntimeState.STOPPED;

            if(current.state().isRunning() == processing)
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

        synchronized void setExternalState(String id, RuntimeState state)
        {
            ChannelSnapshot current = mChannels.get(id);
            assertNotNull(current);
            mChannels.put(id, new ChannelSnapshot(current.configurationId(), current.system(), current.site(),
                current.name(), current.decoder(), current.source(), current.frequenciesHz(), current.frequencyCount(),
                current.autoStart(), current.runnable(), state));
        }

        void blockNextListing()
        {
            mListingEntered = new CountDownLatch(1);
            mListingRelease = new CountDownLatch(1);
            mBlockNextListing = true;
        }

        boolean awaitListingBlocked() throws InterruptedException
        {
            CountDownLatch entered = mListingEntered;
            return entered != null && entered.await(2, TimeUnit.SECONDS);
        }

        void releaseListing()
        {
            CountDownLatch release = mListingRelease;

            if(release != null)
            {
                release.countDown();
            }
        }

        synchronized List<RuntimeState> listingStates()
        {
            return List.copyOf(mListingStates);
        }

        void blockNextMutation()
        {
            mMutationEntered = new CountDownLatch(1);
            mMutationRelease = new CountDownLatch(1);
            mBlockNextMutation = true;
        }

        boolean awaitMutationBlocked() throws InterruptedException
        {
            CountDownLatch entered = mMutationEntered;
            return entered != null && entered.await(2, TimeUnit.SECONDS);
        }

        void releaseMutation()
        {
            CountDownLatch release = mMutationRelease;

            if(release != null)
            {
                release.countDown();
            }
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
