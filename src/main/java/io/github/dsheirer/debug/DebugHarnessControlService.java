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
package io.github.dsheirer.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.ProcessingChain;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exclusive, expiring control lease for the receiver debug harness.  All receiver lifecycle work is serialized on one
 * bounded worker. A session restores only the saved standard channels it actually changed, using each channel's state
 * immediately before the first successful change made by that session.
 */
final class DebugHarnessControlService implements DebugHarnessControlAdapter, AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(DebugHarnessControlService.class);
    static final long MINIMUM_SESSION_SECONDS = 10;
    static final long MAXIMUM_SESSION_SECONDS = 900;
    static final long DEFAULT_SESSION_SECONDS = 60;
    static final int MAXIMUM_SAVED_CHANNELS = 512;
    static final int MAXIMUM_FREQUENCIES_PER_CHANNEL = 64;
    static final int MAXIMUM_CHANNEL_TEXT_LENGTH = 256;
    private static final long CALL_TIMEOUT_SECONDS = 30;
    static final long CLOSE_QUIESCE_TIMEOUT_SECONDS = 5;
    private static final int QUEUE_CAPACITY = 8;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ChannelRuntime mRuntime;
    private final LongSupplier mNanoTime;
    private final LongSupplier mWallClock;
    private final ExpiryScheduler mExpiryScheduler;
    private final AutoCloseable mOwnedExpiryScheduler;
    private final ThreadPoolExecutor mExecutor;
    private final Object mPriorityLock = new Object();
    private final Object mRestoreLock = new Object();
    private volatile Runnable mPriorityTask;
    private volatile boolean mClosed;
    private volatile Session mSession;
    private volatile Future<?> mExpiryFuture;

    DebugHarnessControlService(ConfigurationManager configurationManager)
    {
        this(new ApplicationChannelRuntime(configurationManager), System::nanoTime, System::currentTimeMillis);
    }

    DebugHarnessControlService(ChannelRuntime runtime, LongSupplier nanoTime, LongSupplier wallClock)
    {
        this(runtime, nanoTime, wallClock, ownedScheduler());
    }

    DebugHarnessControlService(ChannelRuntime runtime, LongSupplier nanoTime, LongSupplier wallClock,
                               ExpiryScheduler expiryScheduler)
    {
        this(runtime, nanoTime, wallClock, expiryScheduler, expiryScheduler instanceof AutoCloseable closeable ?
            closeable : null);
    }

    private DebugHarnessControlService(ChannelRuntime runtime, LongSupplier nanoTime, LongSupplier wallClock,
                                       ExpiryScheduler expiryScheduler, AutoCloseable ownedExpiryScheduler)
    {
        mRuntime = Objects.requireNonNull(runtime);
        mNanoTime = Objects.requireNonNull(nanoTime);
        mWallClock = Objects.requireNonNull(wallClock);
        mExpiryScheduler = Objects.requireNonNull(expiryScheduler);
        mOwnedExpiryScheduler = ownedExpiryScheduler;
        mExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            new NamingThreadFactory("receiver debug control"), new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public byte[] channelsJson()
    {
        return invoke(() -> {
            expireIfNecessary();
            Map<String,Object> body = new LinkedHashMap<>();
            ChannelCatalogSnapshot catalog = mRuntime.listSavedChannels(MAXIMUM_SAVED_CHANNELS);
            List<ChannelSnapshot> channels = catalog.channels().stream()
                .sorted(Comparator.comparing(ChannelSnapshot::configurationId))
                .toList();
            body.put("saved_channel_count", catalog.totalCount());
            body.put("channel_limit", MAXIMUM_SAVED_CHANNELS);
            body.put("channels_truncated", catalog.totalCount() > channels.size());
            body.put("channels", channels.stream()
                .map(DebugHarnessControlService::channelView).toList());
            body.put("session", mSession != null ? sessionSummary(mSession) : null);
            return OBJECT_MAPPER.writeValueAsBytes(body);
        }, "Unable to list saved channels");
    }

    @Override
    public HttpResult createSession(long durationSeconds)
    {
        return invoke(() -> {
            expireIfNecessary();

            if(mSession != null)
            {
                return result(409, "A debug control session is already active", sessionSummary(mSession));
            }

            long duration = durationSeconds == 0 ? DEFAULT_SESSION_SECONDS : durationSeconds;

            if(duration < MINIMUM_SESSION_SECONDS || duration > MAXIMUM_SESSION_SECONDS)
            {
                return result(400, "duration_seconds must be between " + MINIMUM_SESSION_SECONDS + " and " +
                    MAXIMUM_SESSION_SECONDS, Map.of());
            }

            long nowNanos = mNanoTime.getAsLong();
            mSession = new Session(UUID.randomUUID().toString(), 1, duration, deadline(nowNanos, duration),
                expiresAt(mWallClock.getAsLong(), duration), new LinkedHashMap<>());

            if(!scheduleExpiry(mSession))
            {
                mSession = null;
                return result(503, "Debug control expiry coordinator is unavailable", Map.of());
            }
            return result(201, null, sessionView(mSession));
        }, "Unable to create debug control session");
    }

    @Override
    public HttpResult getSession(String token)
    {
        return invoke(() -> {
            expireIfNecessary();
            HttpResult rejection = requireSession(token, null);

            if(rejection != null)
            {
                return rejection;
            }

            return result(200, null, sessionView(mSession));
        }, "Unable to read debug control session");
    }

    @Override
    public HttpResult endSession(String token)
    {
        return invoke(() -> {
            expireIfNecessary();
            HttpResult rejection = requireSession(token, null);

            if(rejection != null)
            {
                return rejection;
            }

            return restoreSession("ended");
        }, "Unable to end debug control session");
    }

    @Override
    public HttpResult setChannel(String token, long revision, String configurationId, boolean processing)
    {
        return invoke(() -> {
            expireIfNecessary();
            HttpResult rejection = requireSession(token, revision);

            if(rejection != null)
            {
                return rejection;
            }

            String normalizedId;

            try
            {
                normalizedId = UUID.fromString(configurationId).toString();
            }
            catch(IllegalArgumentException | NullPointerException e)
            {
                return result(400, "configuration_id must be a UUID", sessionView(mSession));
            }

            ChannelSnapshot channel = find(normalizedId);

            if(channel == null)
            {
                return result(404, "Saved channel was not found", sessionView(mSession));
            }

            if(!channel.runnable())
            {
                return result(409, "Saved channel is retired or unsupported", sessionView(mSession));
            }

            Session session = mSession;

            if(!session.touched().containsKey(normalizedId) &&
                session.touched().size() >= MAXIMUM_SAVED_CHANNELS)
            {
                return result(409, "This debug session has reached its changed-channel limit", sessionView(session));
            }

            if(mClosed)
            {
                return result(503, "Debug control service is closing", Map.of());
            }

            boolean changed;
            RuntimeState preTouchState = channel.state();
            long acceptedNanos = mNanoTime.getAsLong();
            long acceptedWallClock = mWallClock.getAsLong();

            try
            {
                changed = mRuntime.setProcessing(normalizedId, processing);
            }
            catch(ChannelException e)
            {
                return result(409, safeMessage(e, "Unable to change saved channel state"), sessionView(session));
            }

            if(mClosed)
            {
                return result(503, "Debug control service is closing", Map.of());
            }

            ChannelTouch touch = session.touched().get(normalizedId);

            if(changed && touch == null)
            {
                session.touched().put(normalizedId, new ChannelTouch(preTouchState.isRunning(), processing));
            }
            else if(touch != null)
            {
                session.touched().put(normalizedId, touch.withLastRequested(processing));
            }

            mSession = session.advanced(deadline(acceptedNanos, session.durationSeconds()),
                expiresAt(acceptedWallClock, session.durationSeconds()));

            if(!scheduleExpiry(mSession))
            {
                RestoreOutcome outcome = restoreSessionOutcome("expiry_unavailable");
                String error = "skipped_application_shutdown".equals(outcome.body().get("restore_outcome")) ?
                    "Debug control service is closing; no channel restoration was attempted" :
                    "Debug control expiry coordinator is unavailable";
                return result(503, error, outcome.body());
            }
            Map<String,Object> view = new LinkedHashMap<>(sessionView(mSession));
            view.put("configuration_id", normalizedId);
            view.put("requested_processing", processing);
            view.put("changed", changed);
            return result(200, null, view);
        }, "Unable to change saved channel state");
    }

    @Override
    public synchronized void close()
    {
        if(mClosed)
        {
            return;
        }

        //Reject new requests before interrupting queued and active work. The session remains available to an active
        //wrapper until it observes mClosed, but close itself never restores session-owned channel state.
        synchronized(mRestoreLock)
        {
            mClosed = true;
        }
        cancelExpiry();

        if(mOwnedExpiryScheduler != null)
        {
            try
            {
                mOwnedExpiryScheduler.close();
            }
            catch(Exception _)
            {
                //Nothing to restore during application shutdown.
            }
        }

        mExecutor.shutdownNow();

        try
        {
            if(!mExecutor.awaitTermination(CLOSE_QUIESCE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            {
                mLog.warn("Receiver debug control worker did not quiesce within {} seconds",
                    CLOSE_QUIESCE_TIMEOUT_SECONDS);
            }
        }
        catch(InterruptedException _)
        {
            Thread.currentThread().interrupt();
            mLog.warn("Interrupted while waiting for receiver debug control worker to quiesce");
        }
        finally
        {
            mSession = null;

            synchronized(mPriorityLock)
            {
                mPriorityTask = null;
            }
        }
    }

    private HttpResult restoreSession(String disposition)
    {
        RestoreOutcome outcome = restoreSessionOutcome(disposition);
        return result(outcome.status(), outcome.error(), outcome.body());
    }

    private RestoreOutcome restoreSessionOutcome(String disposition)
    {
        synchronized(mRestoreLock)
        {
            Session session = mSession;

            if(session == null)
            {
                return new RestoreOutcome(404, "No debug control session is active", Map.of(
                    "restore_outcome", "not_started", "restore_attempted", false));
            }

            if(mClosed)
            {
                return new RestoreOutcome(503, "Debug control service is closing", restoreBody(session, disposition,
                    "skipped_application_shutdown", false, List.of(), List.of(), List.of()));
            }

            mSession = null;
            cancelExpiry();
            List<String> restored = new ArrayList<>();
            List<String> skippedExternalChanges = new ArrayList<>();
            List<Map<String,String>> failures = new ArrayList<>();

            restoreTouchedState(session, false, restored, skippedExternalChanges, failures);
            restoreTouchedState(session, true, restored, skippedExternalChanges, failures);
            String restoreOutcome = failures.isEmpty() ?
                skippedExternalChanges.isEmpty() ? "complete" : "complete_with_external_changes_skipped" : "partial";
            return new RestoreOutcome(failures.isEmpty() ? 200 : 207, null, restoreBody(session, disposition,
                restoreOutcome, true, restored, skippedExternalChanges, failures));
        }
    }

    private static Map<String,Object> restoreBody(Session session, String disposition, String restoreOutcome,
                                                   boolean attempted, List<String> restored,
                                                   List<String> skippedExternalChanges,
                                                   List<Map<String,String>> failures)
    {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("session_id", session.id());
        body.put("state", disposition);
        body.put("restore_outcome", restoreOutcome);
        body.put("restore_attempted", attempted);
        body.put("restored_configuration_ids", restored);
        body.put("restore_skipped_external_change_configuration_ids", skippedExternalChanges);
        body.put("restore_failures", failures);
        return body;
    }

    private void restoreTouchedState(Session session, boolean processing, List<String> restored,
                                     List<String> skippedExternalChanges, List<Map<String,String>> failures)
    {
        for(Map.Entry<String,ChannelTouch> entry: session.touched().entrySet())
        {
            ChannelTouch touch = entry.getValue();

            if(touch.baselineProcessing() != processing)
            {
                continue;
            }

            try
            {
                ChannelSnapshot channel = find(entry.getKey());

                if(channel == null)
                {
                    failures.add(Map.of("configuration_id", entry.getKey(), "error", "Saved channel was removed"));
                }
                else if(channel.state().isRunning() != touch.lastRequestedProcessing())
                {
                    skippedExternalChanges.add(entry.getKey());
                }
                else if(!channel.runnable() && touch.baselineProcessing())
                {
                    failures.add(Map.of("configuration_id", entry.getKey(),
                        "error", "Saved channel is retired or unsupported"));
                }
                else if(mRuntime.setProcessing(entry.getKey(), touch.baselineProcessing()))
                {
                    restored.add(entry.getKey());
                }
            }
            catch(Exception e)
            {
                failures.add(Map.of("configuration_id", entry.getKey(),
                    "error", safeMessage(e, "Unable to restore saved channel")));
            }
        }
    }

    private void expireIfNecessary()
    {
        if(mSession != null && mNanoTime.getAsLong() >= mSession.deadlineNanos())
        {
            restoreSession("expired");
        }
    }

    private boolean scheduleExpiry(Session session)
    {
        cancelExpiry();

        if(!mClosed && session != null)
        {
            long delay = Math.max(0L, session.deadlineNanos() - mNanoTime.getAsLong());

            try
            {
                mExpiryFuture = mExpiryScheduler.schedule(() -> prioritizeExpiry(session.id(), session.deadlineNanos()),
                    delay, TimeUnit.NANOSECONDS);
                return true;
            }
            catch(RejectedExecutionException e)
            {
                return false;
            }
        }

        return false;
    }

    private void prioritizeExpiry(String sessionId, long expectedDeadline)
    {
        Session current = mSession;

        if(mClosed || current == null || !current.id().equals(sessionId) ||
            current.deadlineNanos() != expectedDeadline)
        {
            return;
        }

        synchronized(mPriorityLock)
        {
            current = mSession;

            if(!mClosed && current != null && current.id().equals(sessionId) &&
                current.deadlineNanos() == expectedDeadline)
            {
                mPriorityTask = () -> expireSession(sessionId, expectedDeadline);
            }
        }

        trySubmitPriority();
    }

    private void trySubmitPriority()
    {
        Runnable priority = mPriorityTask;

        if(priority == null || mClosed)
        {
            return;
        }

        try
        {
            mExecutor.execute(this::runPriorityTask);
        }
        catch(RejectedExecutionException _)
        {
            //A full queue already contains an ordinary wrapper that checks the priority slot before doing its work.
        }
    }

    private void runPriorityTask()
    {
        Runnable task;

        synchronized(mPriorityLock)
        {
            task = mPriorityTask;
            mPriorityTask = null;
        }

        if(task != null)
        {
            task.run();
        }
    }

    private void expireSession(String sessionId, long expectedDeadline)
    {
        Session current = mSession;

        if(!mClosed && current != null && current.id().equals(sessionId) && current.deadlineNanos() == expectedDeadline)
        {
            if(mNanoTime.getAsLong() >= expectedDeadline)
            {
                restoreSession("expired");
            }
            else
            {
                if(!scheduleExpiry(current))
                {
                    restoreSession("expiry_unavailable");
                }
            }
        }
    }

    private void cancelExpiry()
    {
        Future<?> future = mExpiryFuture;
        mExpiryFuture = null;

        if(future != null)
        {
            future.cancel(false);
        }
    }

    private HttpResult requireSession(String token, Long revision)
    {
        if(mSession == null)
        {
            return result(404, "No debug control session is active", Map.of());
        }

        if(token == null || !mSession.id().equals(token))
        {
            return result(403, "Debug control session token is invalid", Map.of());
        }

        if(revision != null && revision != mSession.revision())
        {
            return result(409, "Debug control session revision is stale", sessionView(mSession));
        }

        return null;
    }

    private ChannelSnapshot find(String configurationId) throws Exception
    {
        return mRuntime.findSavedChannel(configurationId);
    }

    private static Map<String,Object> channelView(ChannelSnapshot channel)
    {
        Map<String,Object> view = new LinkedHashMap<>();
        view.put("configuration_id", channel.configurationId());
        view.put("system", channel.system());
        view.put("site", channel.site());
        view.put("name", channel.name());
        view.put("decoder", channel.decoder());
        view.put("source", channel.source());
        view.put("frequencies_hz", channel.frequenciesHz());
        view.put("frequency_count", channel.frequencyCount());
        view.put("frequencies_truncated", channel.frequencyCount() > channel.frequenciesHz().size());
        view.put("auto_start", channel.autoStart());
        view.put("runnable", channel.runnable());
        view.put("state", channel.state().jsonValue());
        return view;
    }

    private static Map<String,Object> sessionView(Session session)
    {
        Map<String,Object> view = new LinkedHashMap<>();
        view.put("session_id", session.id());
        view.put("revision", session.revision());
        view.put("duration_seconds", session.durationSeconds());
        view.put("expires_at_ms", session.expiresAtMilliseconds());
        view.put("expires_at", Instant.ofEpochMilli(session.expiresAtMilliseconds()).toString());
        view.put("state", "active");
        return view;
    }

    /** Public contention/listing state deliberately omits the bearer-style session identifier. */
    private static Map<String,Object> sessionSummary(Session session)
    {
        Map<String,Object> view = sessionView(session);
        view.remove("session_id");
        return view;
    }

    private static long deadline(long nowNanos, long seconds)
    {
        long durationNanos = TimeUnit.SECONDS.toNanos(seconds);
        return nowNanos > Long.MAX_VALUE - durationNanos ? Long.MAX_VALUE : nowNanos + durationNanos;
    }

    private static long expiresAt(long nowMilliseconds, long seconds)
    {
        long durationMilliseconds = TimeUnit.SECONDS.toMillis(seconds);
        return nowMilliseconds > Long.MAX_VALUE - durationMilliseconds ? Long.MAX_VALUE :
            nowMilliseconds + durationMilliseconds;
    }

    private <T> T invoke(CheckedSupplier<T> supplier, String failureMessage)
    {
        if(mClosed)
        {
            return failure(failureMessage, 503, "Debug control service is closed");
        }

        CompletableFuture<T> future = new CompletableFuture<>();

        try
        {
            mExecutor.execute(() -> {
                try
                {
                    //An expired lease takes priority over every queued ordinary request.  It can be delayed only by
                    //the one lifecycle operation already active on this serial worker.
                    runPriorityTask();
                    future.complete(supplier.get());
                }
                catch(Exception e)
                {
                    future.completeExceptionally(e);
                }
                finally
                {
                    trySubmitPriority();
                }
            });
        }
        catch(RejectedExecutionException e)
        {
            return failure(failureMessage, 503, "Debug control worker is busy");
        }

        try
        {
            return future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return failure(failureMessage, 503, "Debug control request was interrupted");
        }
        catch(TimeoutException e)
        {
            return failure(failureMessage, 504, "Debug control request timed out; the serialized operation may still complete");
        }
        catch(ExecutionException e)
        {
            return failure(failureMessage, 500, failureMessage);
        }
    }

    int pendingRequestCount()
    {
        return mExecutor.getQueue().size();
    }

    boolean isClosed()
    {
        return mClosed;
    }

    @SuppressWarnings("unchecked")
    private static <T> T failure(String operation, int status, String message)
    {
        if(operation.startsWith("Unable to list"))
        {
            try
            {
                return (T)OBJECT_MAPPER.writeValueAsBytes(Map.of("error", message));
            }
            catch(Exception e)
            {
                return (T)"{\"error\":\"Debug control failure\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
        }

        return (T)result(status, message, Map.of());
    }

    private static HttpResult result(int status, String error, Map<String,?> body)
    {
        Map<String,Object> value = new LinkedHashMap<>();

        if(error != null)
        {
            value.put("error", error);
        }

        value.putAll(body);

        try
        {
            return new HttpResult(status, OBJECT_MAPPER.writeValueAsBytes(value));
        }
        catch(Exception e)
        {
            return new HttpResult(500, "{\"error\":\"Debug control response failed\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static String safeMessage(Exception exception, String fallback)
    {
        String message = exception.getMessage();
        return message == null || message.isBlank() || message.length() > 160 ? fallback : message;
    }

    interface ChannelRuntime
    {
        ChannelCatalogSnapshot listSavedChannels(int limit) throws Exception;
        ChannelSnapshot findSavedChannel(String configurationId) throws Exception;
        boolean setProcessing(String configurationId, boolean processing) throws Exception;
    }

    record ChannelSnapshot(String configurationId, String system, String site, String name, String decoder,
                           String source, List<Long> frequenciesHz, int frequencyCount, boolean autoStart, boolean runnable,
                           RuntimeState state)
    {
        ChannelSnapshot
        {
            configurationId = boundedText(configurationId);
            system = boundedText(system);
            site = boundedText(site);
            name = boundedText(name);
            decoder = boundedText(decoder);
            source = boundedText(source);
            frequenciesHz = frequenciesHz != null ? frequenciesHz.stream()
                .limit(MAXIMUM_FREQUENCIES_PER_CHANNEL).toList() : List.of();
            frequencyCount = Math.max(frequencyCount, frequenciesHz.size());
            state = state != null ? state : RuntimeState.STOPPED;
        }
    }

    record ChannelCatalogSnapshot(int totalCount, List<ChannelSnapshot> channels)
    {
        ChannelCatalogSnapshot
        {
            channels = channels != null ? List.copyOf(channels) : List.of();
            totalCount = Math.max(totalCount, channels.size());
        }
    }

    enum RuntimeState
    {
        STOPPED("stopped"), RUNNING("running"), REGISTERED_NOT_RUNNING("registered_not_running");

        private final String mJsonValue;

        RuntimeState(String jsonValue)
        {
            mJsonValue = jsonValue;
        }

        String jsonValue()
        {
            return mJsonValue;
        }

        boolean isRunning()
        {
            return this == RUNNING;
        }
    }

    private record ChannelTouch(boolean baselineProcessing, boolean lastRequestedProcessing)
    {
        ChannelTouch withLastRequested(boolean processing)
        {
            return new ChannelTouch(baselineProcessing, processing);
        }
    }

    private record RestoreOutcome(int status, String error, Map<String,Object> body)
    {
    }

    private record Session(String id, long revision, long durationSeconds, long deadlineNanos,
                           long expiresAtMilliseconds, Map<String,ChannelTouch> touched)
    {
        Session
        {
            touched = new LinkedHashMap<>(touched);
        }

        Session advanced(long deadlineNanos, long expiresAtMilliseconds)
        {
            return new Session(id, revision + 1, durationSeconds, deadlineNanos, expiresAtMilliseconds, touched);
        }
    }

    private interface CheckedSupplier<T>
    {
        T get() throws Exception;
    }

    interface ExpiryScheduler
    {
        Future<?> schedule(Runnable task, long delay, TimeUnit unit);
    }

    private static OwnedExpiryScheduler ownedScheduler()
    {
        return new OwnedExpiryScheduler();
    }

    static final class OwnedExpiryScheduler implements ExpiryScheduler, AutoCloseable
    {
        private final ScheduledThreadPoolExecutor mExecutor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "receiver debug expiry coordinator");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });

        OwnedExpiryScheduler()
        {
            //Each lease renewal replaces the prior deadline. Remove cancelled timers immediately so repeated control
            //requests cannot accumulate delayed tasks during a long-running session.
            mExecutor.setRemoveOnCancelPolicy(true);
            mExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        }

        @Override
        public Future<?> schedule(Runnable task, long delay, TimeUnit unit)
        {
            return mExecutor.schedule(task, delay, unit);
        }

        @Override
        public void close()
        {
            mExecutor.shutdownNow();

            try
            {
                if(!mExecutor.awaitTermination(CLOSE_QUIESCE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                {
                    mLog.warn("Receiver debug expiry coordinator did not quiesce within {} seconds",
                        CLOSE_QUIESCE_TIMEOUT_SECONDS);
                }
            }
            catch(InterruptedException _)
            {
                Thread.currentThread().interrupt();
                mLog.warn("Interrupted while waiting for receiver debug expiry coordinator to quiesce");
            }
        }
    }

    private static final class ApplicationChannelRuntime implements ChannelRuntime
    {
        private final ConfigurationManager mConfigurationManager;
        private final ChannelProcessingManager mProcessingManager;

        private ApplicationChannelRuntime(ConfigurationManager configurationManager)
        {
            mConfigurationManager = Objects.requireNonNull(configurationManager);
            mProcessingManager = configurationManager.getChannelProcessingManager();
        }

        @Override
        public ChannelCatalogSnapshot listSavedChannels(int limit) throws Exception
        {
            return onConfigurationThread(() -> snapshot(limit));
        }

        private ChannelCatalogSnapshot snapshot(int requestedLimit)
        {
            List<ChannelSnapshot> snapshots = new ArrayList<>();
            List<Channel> channels = mConfigurationManager.getChannelModel().getChannels();
            int limit = Math.max(0, Math.min(MAXIMUM_SAVED_CHANNELS, requestedLimit));

            for(int x = 0; x < channels.size() && snapshots.size() < limit; x++)
            {
                Channel channel = channels.get(x);

                if(!isSavedStandard(channel))
                {
                    continue;
                }
                ProcessingChain chain = mProcessingManager.getProcessingChain(channel);
                RuntimeState state = chain == null ? RuntimeState.STOPPED :
                    chain.isProcessing() ? RuntimeState.RUNNING : RuntimeState.REGISTERED_NOT_RUNNING;
                List<Long> frequencies = channel.getFrequencyList();
                snapshots.add(new ChannelSnapshot(channel.getConfigurationId(), nullable(channel.getSystem()),
                    nullable(channel.getSite()), nullable(channel.getName()),
                    channel.getDecodeConfiguration().getDecoderType().name(),
                    channel.getSourceConfiguration().getSourceType().name(), frequencies.stream()
                    .limit(MAXIMUM_FREQUENCIES_PER_CHANNEL).toList(), frequencies.size(), channel.isAutoStart(),
                    ChannelConfigurationPolicy.isActive(channel), state));
            }

            int standardCount = (int)channels.stream()
                .filter(DebugHarnessControlService::isSavedStandard).count();
            return new ChannelCatalogSnapshot(standardCount, snapshots);
        }

        @Override
        public ChannelSnapshot findSavedChannel(String configurationId) throws Exception
        {
            return onConfigurationThread(() -> {
                Channel channel = resolveCurrent(configurationId);

                if(!isSavedStandard(channel))
                {
                    return null;
                }

                ProcessingChain chain = mProcessingManager.getProcessingChain(channel);
                RuntimeState state = chain == null ? RuntimeState.STOPPED :
                    chain.isProcessing() ? RuntimeState.RUNNING : RuntimeState.REGISTERED_NOT_RUNNING;
                List<Long> frequencies = channel.getFrequencyList();
                return new ChannelSnapshot(channel.getConfigurationId(), nullable(channel.getSystem()),
                    nullable(channel.getSite()), nullable(channel.getName()),
                    channel.getDecodeConfiguration().getDecoderType().name(),
                    channel.getSourceConfiguration().getSourceType().name(), frequencies.stream()
                    .limit(MAXIMUM_FREQUENCIES_PER_CHANNEL).toList(), frequencies.size(), channel.isAutoStart(),
                    ChannelConfigurationPolicy.isActive(channel), state);
            });
        }

        @Override
        public boolean setProcessing(String configurationId, boolean processing) throws Exception
        {
            Channel channel = resolve(configurationId);

            if(channel == null)
            {
                throw new ChannelException("Saved channel was not found");
            }

            ProcessingChain chain = mProcessingManager.getProcessingChain(channel);

            if(processing)
            {
                if(chain != null && chain.isProcessing())
                {
                    return false;
                }

                if(chain != null)
                {
                    //A registered but non-running chain is transient/degraded. Remove it before requesting a fresh,
                    //fully running chain instead of treating mere map registration as success.
                    mProcessingManager.stop(channel);
                }

                mProcessingManager.start(channel);
            }
            else
            {
                if(chain == null)
                {
                    return false;
                }

                mProcessingManager.stop(channel);
            }

            return true;
        }

        private Channel resolve(String configurationId) throws Exception
        {
            return onConfigurationThread(() -> resolveCurrent(configurationId));
        }

        private Channel resolveCurrent(String configurationId)
        {
            for(Channel channel: mConfigurationManager.getChannelModel().getChannels())
            {
                if(isSavedStandard(channel) && configurationId.equals(channel.getConfigurationId()))
                {
                    return channel;
                }
            }

            return null;
        }

        private <T> T onConfigurationThread(CheckedSupplier<T> supplier) throws Exception
        {
            if(Platform.isFxApplicationThread())
            {
                return supplier.get();
            }

            CompletableFuture<T> future = new CompletableFuture<>();
            Runnable task = () -> {
                try
                {
                    future.complete(supplier.get());
                }
                catch(Exception e)
                {
                    future.completeExceptionally(e);
                }
            };

            try
            {
                Platform.runLater(task);
            }
            catch(IllegalStateException e)
            {
                mConfigurationManager.runHeadlessWebConfigurationTask(task);
            }

            return future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private static String nullable(String value)
        {
            return value != null ? value : "";
        }
    }

    static boolean isSavedStandard(Channel channel)
    {
        return channel != null && channel.getChannelType() == ChannelType.STANDARD;
    }

    private static String boundedText(String value)
    {
        if(value == null)
        {
            return "";
        }

        return value.length() <= MAXIMUM_CHANNEL_TEXT_LENGTH ? value : value.substring(0, MAXIMUM_CHANNEL_TEXT_LENGTH);
    }
}
