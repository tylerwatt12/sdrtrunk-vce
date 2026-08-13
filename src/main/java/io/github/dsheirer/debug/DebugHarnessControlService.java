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
import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.util.ThreadPool;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import javafx.application.Platform;

/**
 * Exclusive, expiring control lease for the receiver debug harness.  All receiver lifecycle work is serialized on one
 * bounded worker.  A session owns the running state of saved standard channels and restores the exact state it found
 * when the session ends or expires.
 */
final class DebugHarnessControlService implements DebugHarnessControlAdapter, AutoCloseable
{
    static final long MINIMUM_SESSION_SECONDS = 10;
    static final long MAXIMUM_SESSION_SECONDS = 900;
    static final long DEFAULT_SESSION_SECONDS = 60;
    static final int MAXIMUM_SAVED_CHANNELS = 512;
    static final int MAXIMUM_FREQUENCIES_PER_CHANNEL = 64;
    static final int MAXIMUM_CHANNEL_TEXT_LENGTH = 256;
    private static final long CALL_TIMEOUT_SECONDS = 30;
    private static final int QUEUE_CAPACITY = 8;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ChannelRuntime mRuntime;
    private final LongSupplier mNanoTime;
    private final LongSupplier mWallClock;
    private final ExpiryScheduler mExpiryScheduler;
    private final ThreadPoolExecutor mExecutor;
    private volatile boolean mClosed;
    private volatile Session mSession;
    private volatile Future<?> mExpiryFuture;

    DebugHarnessControlService(ConfigurationManager configurationManager)
    {
        this(new ApplicationChannelRuntime(configurationManager), System::nanoTime, System::currentTimeMillis,
            (task, delay, unit) -> ThreadPool.SCHEDULED.schedule(task, delay, unit));
    }

    DebugHarnessControlService(ChannelRuntime runtime, LongSupplier nanoTime, LongSupplier wallClock)
    {
        this(runtime, nanoTime, wallClock,
            (task, delay, unit) -> ThreadPool.SCHEDULED.schedule(task, delay, unit));
    }

    DebugHarnessControlService(ChannelRuntime runtime, LongSupplier nanoTime, LongSupplier wallClock,
                               ExpiryScheduler expiryScheduler)
    {
        mRuntime = Objects.requireNonNull(runtime);
        mNanoTime = Objects.requireNonNull(nanoTime);
        mWallClock = Objects.requireNonNull(wallClock);
        mExpiryScheduler = Objects.requireNonNull(expiryScheduler);
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

            ChannelCatalogSnapshot catalog = mRuntime.listSavedChannels(MAXIMUM_SAVED_CHANNELS);
            List<ChannelSnapshot> channels = catalog.channels();

            if(catalog.totalCount() > MAXIMUM_SAVED_CHANNELS)
            {
                return result(409, "There are too many saved channels for one bounded debug session", Map.of(
                    "saved_channel_count", catalog.totalCount(), "channel_limit", MAXIMUM_SAVED_CHANNELS));
            }

            Map<String,Boolean> baseline = new LinkedHashMap<>();

            for(ChannelSnapshot channel: channels)
            {
                baseline.put(channel.configurationId(), channel.state().isStarted());
            }

            long nowNanos = mNanoTime.getAsLong();
            mSession = new Session(UUID.randomUUID().toString(), 1, duration, deadline(nowNanos, duration),
                expiresAt(mWallClock.getAsLong(), duration), baseline);
            scheduleExpiry(mSession);
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

            if(!mSession.baseline().containsKey(normalizedId))
            {
                return result(409, "Saved channel was added after this debug session began", sessionView(mSession));
            }

            if(!channel.runnable())
            {
                return result(409, "Saved channel is retired or unsupported", sessionView(mSession));
            }

            boolean changed;
            long acceptedNanos = mNanoTime.getAsLong();
            long acceptedWallClock = mWallClock.getAsLong();

            try
            {
                changed = mRuntime.setProcessing(normalizedId, processing);
            }
            catch(ChannelException e)
            {
                return result(409, safeMessage(e, "Unable to change saved channel state"), sessionView(mSession));
            }

            mSession = mSession.advanced(deadline(acceptedNanos, mSession.durationSeconds()),
                expiresAt(acceptedWallClock, mSession.durationSeconds()));
            scheduleExpiry(mSession);
            Map<String,Object> view = new LinkedHashMap<>(sessionView(mSession));
            view.put("configuration_id", normalizedId);
            view.put("requested_processing", processing);
            view.put("changed", changed);
            return result(200, null, view);
        }, "Unable to change saved channel state");
    }

    @Override
    public void close()
    {
        mClosed = true;
        mSession = null;
        cancelExpiry();
        mExecutor.shutdownNow();
    }

    private HttpResult restoreSession(String disposition)
    {
        Session session = mSession;

        if(session == null)
        {
            return result(404, "No debug control session is active", Map.of());
        }

        mSession = null;
        cancelExpiry();
        List<String> restored = new ArrayList<>();
        List<Map<String,String>> failures = new ArrayList<>();

        restoreBaselineState(session, false, restored, failures);
        restoreBaselineState(session, true, restored, failures);

        Map<String,Object> body = new LinkedHashMap<>();
        body.put("session_id", session.id());
        body.put("state", disposition);
        body.put("restored_configuration_ids", restored);
        body.put("restore_failures", failures);
        return result(failures.isEmpty() ? 200 : 207, null, body);
    }

    private void restoreBaselineState(Session session, boolean processing, List<String> restored,
                                      List<Map<String,String>> failures)
    {
        for(Map.Entry<String,Boolean> baseline: session.baseline().entrySet())
        {
            if(baseline.getValue() != processing)
            {
                continue;
            }

            try
            {
                ChannelSnapshot channel = find(baseline.getKey());

                if(channel == null)
                {
                    failures.add(Map.of("configuration_id", baseline.getKey(), "error", "Saved channel was removed"));
                }
                else if(!channel.runnable() && baseline.getValue())
                {
                    failures.add(Map.of("configuration_id", baseline.getKey(),
                        "error", "Saved channel is retired or unsupported"));
                }
                else if(mRuntime.setProcessing(baseline.getKey(), baseline.getValue()))
                {
                    restored.add(baseline.getKey());
                }
            }
            catch(Exception e)
            {
                failures.add(Map.of("configuration_id", baseline.getKey(),
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

    private void scheduleExpiry(Session session)
    {
        cancelExpiry();

        if(!mClosed && session != null)
        {
            long delay = Math.max(0L, session.deadlineNanos() - mNanoTime.getAsLong());
            mExpiryFuture = mExpiryScheduler.schedule(() -> enqueueExpiry(session.id(), session.deadlineNanos()),
                delay, TimeUnit.NANOSECONDS);
        }
    }

    private void enqueueExpiry(String sessionId, long expectedDeadline)
    {
        if(mClosed)
        {
            return;
        }

        try
        {
            mExecutor.execute(() -> {
                Session current = mSession;

                if(!mClosed && current != null && current.id().equals(sessionId) &&
                    current.deadlineNanos() == expectedDeadline)
                {
                    if(mNanoTime.getAsLong() >= expectedDeadline)
                    {
                        restoreSession("expired");
                    }
                    else
                    {
                        scheduleExpiry(current);
                    }
                }
            });
        }
        catch(RejectedExecutionException e)
        {
            if(!mClosed)
            {
                //Do not replace the tracked current-lease future from this scheduler thread.  A stale retry is harmless
                //because the worker validates both the session identifier and exact monotonic deadline.
                mExpiryScheduler.schedule(() -> enqueueExpiry(sessionId, expectedDeadline), 250,
                    TimeUnit.MILLISECONDS);
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
                    future.complete(supplier.get());
                }
                catch(Exception e)
                {
                    future.completeExceptionally(e);
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

        boolean isStarted()
        {
            return this != STOPPED;
        }
    }

    private record Session(String id, long revision, long durationSeconds, long deadlineNanos,
                           long expiresAtMilliseconds, Map<String,Boolean> baseline)
    {
        Session
        {
            baseline = Collections.unmodifiableMap(new LinkedHashMap<>(baseline));
        }

        Session advanced(long deadlineNanos, long expiresAtMilliseconds)
        {
            return new Session(id, revision + 1, durationSeconds, deadlineNanos, expiresAtMilliseconds, baseline);
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

            for(int x = 0; x < channels.size() && x < limit; x++)
            {
                Channel channel = channels.get(x);
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

            return new ChannelCatalogSnapshot(channels.size(), snapshots);
        }

        @Override
        public ChannelSnapshot findSavedChannel(String configurationId) throws Exception
        {
            return onConfigurationThread(() -> {
                Channel channel = resolveCurrent(configurationId);

                if(channel == null)
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
            boolean started = chain != null;

            if(started == processing)
            {
                return false;
            }

            if(processing)
            {
                mProcessingManager.start(channel);
            }
            else
            {
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
                if(configurationId.equals(channel.getConfigurationId()))
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

    private static String boundedText(String value)
    {
        if(value == null)
        {
            return "";
        }

        return value.length() <= MAXIMUM_CHANNEL_TEXT_LENGTH ? value : value.substring(0, MAXIMUM_CHANNEL_TEXT_LENGTH);
    }
}
