/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.web.signal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.spectrum.stream.SpectrumFrame;
import io.github.dsheirer.spectrum.stream.SpectrumFrameCodec;
import io.github.dsheirer.spectrum.stream.SpectrumStreamService;
import io.github.dsheirer.web.access.AuthorizationSubject;
import io.github.dsheirer.web.access.FeatureAccessDecision;
import io.github.dsheirer.web.access.FeaturePolicyChange;
import io.github.dsheirer.web.access.InMemoryFeatureAccessPolicy;
import io.github.dsheirer.web.access.WebFeature;
import io.github.dsheirer.web.access.WebTransport;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.server.ServerUpgradeRequest;
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded WebSocket adapter for the shared wideband spectrum stream.
 *
 * <p>Handshakes and live sessions use the same feature-access policy.  A PUBLIC to ADMIN_ONLY transition revokes
 * anonymous sessions immediately.  Each subscribed socket gets one virtual send pump and one latest-only stream
 * slot; the spectrum producer never serializes a frame, waits for a socket, or creates per-viewer data.  The first
 * transport pump that needs a frame materializes its cached SFFT v1 payload and every viewer sends an independently
 * positioned read-only view of those same bytes.</p>
 */
public final class SignalWebSocketTransport implements AutoCloseable
{
    public static final String PATH = "/api/v1/ws/signal";

    private static final int MAXIMUM_CONTROL_CHARACTERS = 512;
    private static final Logger mLog = LoggerFactory.getLogger(SignalWebSocketTransport.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Configuration mConfiguration;
    private final SpectrumStreamService mSpectrumStreamService;
    private final InMemoryFeatureAccessPolicy mAccessPolicy;
    private final SignalSubjectResolver mSubjectResolver;
    private final SignalOriginPolicy mOriginPolicy;
    private final Semaphore mSessionPermits;
    private final Set<SignalEndpoint> mEndpoints = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ExecutorService mSendExecutor;
    private final InMemoryFeatureAccessPolicy.Registration mPolicyRegistration;
    private final AtomicBoolean mConfigured = new AtomicBoolean();
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final AtomicLong mDeliveredFrameCount = new AtomicLong();
    private final AtomicLong mRejectedHandshakeCount = new AtomicLong();
    private final AtomicLong mRevokedSessionCount = new AtomicLong();
    private final AtomicLong mFailedSendCount = new AtomicLong();

    public SignalWebSocketTransport(Configuration configuration, SpectrumStreamService spectrumStreamService,
                                    InMemoryFeatureAccessPolicy accessPolicy,
                                    SignalSubjectResolver subjectResolver, SignalOriginPolicy originPolicy)
    {
        mConfiguration = Objects.requireNonNull(configuration, "Signal transport configuration cannot be null");
        mSpectrumStreamService = Objects.requireNonNull(spectrumStreamService,
            "Spectrum stream service cannot be null");
        mAccessPolicy = Objects.requireNonNull(accessPolicy, "Feature access policy cannot be null");
        mSubjectResolver = Objects.requireNonNull(subjectResolver, "Signal subject resolver cannot be null");
        mOriginPolicy = Objects.requireNonNull(originPolicy, "Signal origin policy cannot be null");
        mSessionPermits = new Semaphore(configuration.maximumSessions());
        mSendExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
            .name(configuration.sendThreadNamePrefix(), 0)
            .uncaughtExceptionHandler((thread, throwable) -> mLog.warn("Uncaught signal transport failure", throwable))
            .factory());
        mPolicyRegistration = accessPolicy.addListener(this::policyChanged);
    }

    /**
     * Adds this transport's one exact-path mapping to the shared web container.
     */
    public void configure(ServerWebSocketContainer container)
    {
        Objects.requireNonNull(container, "WebSocket container cannot be null");

        if(mClosed.get())
        {
            throw new IllegalStateException("Signal WebSocket transport is closed");
        }

        if(!mConfigured.compareAndSet(false, true))
        {
            throw new IllegalStateException("Signal WebSocket transport is already configured");
        }

        container.addMapping(PATH, this::createEndpoint);
    }

    private Object createEndpoint(ServerUpgradeRequest request, ServerUpgradeResponse response, Callback callback)
    {
        if(mClosed.get())
        {
            reject(response, callback, HttpStatus.SERVICE_UNAVAILABLE_503, false);
            return null;
        }

        if(!mOriginPolicy.isAllowed(request))
        {
            mRejectedHandshakeCount.incrementAndGet();
            reject(response, callback, HttpStatus.FORBIDDEN_403, false);
            return null;
        }

        AuthorizationSubject subject;

        try
        {
            subject = Objects.requireNonNull(mSubjectResolver.resolve(request),
                "Signal subject resolver returned null");
        }
        catch(RuntimeException exception)
        {
            // Authentication failures must not echo a token, cookie, or resolver exception into logs.
            mLog.warn("Unable to resolve signal WebSocket subject");
            mRejectedHandshakeCount.incrementAndGet();
            reject(response, callback, HttpStatus.UNAUTHORIZED_401, true);
            return null;
        }

        FeatureAccessDecision decision = authorize(subject);

        if(!decision.isAllowed())
        {
            mRejectedHandshakeCount.incrementAndGet();
            reject(response, callback, HttpStatus.UNAUTHORIZED_401, true);
            return null;
        }

        if(!mSessionPermits.tryAcquire())
        {
            mRejectedHandshakeCount.incrementAndGet();
            reject(response, callback, HttpStatus.SERVICE_UNAVAILABLE_503, false);
            return null;
        }

        SignalEndpoint endpoint = new SignalEndpoint(subject);
        mEndpoints.add(endpoint);
        return endpoint;
    }

    private static void reject(ServerUpgradeResponse response, Callback callback, int status, boolean authenticate)
    {
        response.setStatus(status);
        response.getHeaders().put(HttpHeader.CONTENT_LENGTH, 0);

        if(authenticate)
        {
            response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE, "Bearer realm=\"sdrtrunk-admin\"");
        }

        response.write(true, null, callback);
    }

    private FeatureAccessDecision authorize(AuthorizationSubject subject)
    {
        return mAccessPolicy.authorize(WebFeature.WIDEBAND_SIGNAL, subject, WebTransport.WEBSOCKET);
    }

    private void policyChanged(FeaturePolicyChange change)
    {
        if(change.feature() != WebFeature.WIDEBAND_SIGNAL || !change.revokesAnonymousAccess())
        {
            return;
        }

        for(SignalEndpoint endpoint: mEndpoints)
        {
            if(endpoint.isAnonymous() && endpoint.revoke())
            {
                mRevokedSessionCount.incrementAndGet();
            }
        }
    }

    private void release(SignalEndpoint endpoint)
    {
        if(mEndpoints.remove(endpoint))
        {
            mSessionPermits.release();
        }
    }

    public int getActiveSessionCount()
    {
        return mEndpoints.size();
    }

    public long getDeliveredFrameCount()
    {
        return mDeliveredFrameCount.get();
    }

    public long getRejectedHandshakeCount()
    {
        return mRejectedHandshakeCount.get();
    }

    public long getRevokedSessionCount()
    {
        return mRevokedSessionCount.get();
    }

    public long getFailedSendCount()
    {
        return mFailedSendCount.get();
    }

    public boolean isSendExecutorTerminated()
    {
        return mSendExecutor.isTerminated();
    }

    @Override
    public void close()
    {
        if(!mClosed.compareAndSet(false, true))
        {
            return;
        }

        mPolicyRegistration.close();

        for(SignalEndpoint endpoint: Set.copyOf(mEndpoints))
        {
            endpoint.terminate(StatusCode.SHUTDOWN, "signal service shutdown", true);
        }

        mSendExecutor.shutdownNow();

        try
        {
            if(!mSendExecutor.awaitTermination(mConfiguration.shutdownTimeout().toNanos(), TimeUnit.NANOSECONDS))
            {
                throw new IllegalStateException("Failed to terminate signal WebSocket send executor");
            }
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping signal WebSocket transport", exception);
        }
    }

    /**
     * Jetty endpoint type.  The class is public because Jetty validates listener callbacks through method handles;
     * instances remain transport-owned and its constructor is private.
     */
    public final class SignalEndpoint implements Session.Listener.AutoDemanding
    {
        private final AuthorizationSubject mSubject;
        private final Object mSubscriptionLock = new Object();
        private final AtomicBoolean mReleased = new AtomicBoolean();
        private final AtomicBoolean mClosing = new AtomicBoolean();
        private final AtomicBoolean mRevoked = new AtomicBoolean();
        private final AtomicLong mMinimumFrameIntervalNanos = new AtomicLong(
            frameIntervalNanos(mConfiguration.defaultMaximumFramesPerSecond()));
        private volatile Session mSession;
        private SpectrumStreamService.Subscription mSubscription;
        private Future<?> mPumpTask;

        private SignalEndpoint(AuthorizationSubject subject)
        {
            mSubject = subject;
        }

        private boolean isAnonymous()
        {
            return mSubject == AuthorizationSubject.ANONYMOUS;
        }

        @Override
        public void onWebSocketOpen(Session session)
        {
            mSession = session;

            // Close the authorization race between creator evaluation and session activation.
            if(mClosed.get() || mReleased.get() || mClosing.get() || mRevoked.get() ||
                !authorize(mSubject).isAllowed())
            {
                terminate(StatusCode.POLICY_VIOLATION, "signal access changed", false);
            }
        }

        @Override
        public void onWebSocketText(String message)
        {
            if(mReleased.get() || mClosing.get() || mRevoked.get() || !authorize(mSubject).isAllowed())
            {
                revoke();
                return;
            }

            try
            {
                ControlMessage control = ControlMessage.parse(message, mConfiguration.maximumFramesPerSecond());

                switch(control.action())
                {
                    case SUBSCRIBE -> subscribe(control.maximumFramesPerSecond());
                    case UPDATE -> update(control.maximumFramesPerSecond());
                    case UNSUBSCRIBE -> unsubscribe();
                }
            }
            catch(IllegalArgumentException exception)
            {
                terminate(StatusCode.POLICY_VIOLATION, "invalid signal control", false);
            }
        }

        private void subscribe(Integer maximumFramesPerSecond)
        {
            if(maximumFramesPerSecond != null)
            {
                mMinimumFrameIntervalNanos.set(frameIntervalNanos(maximumFramesPerSecond));
            }

            synchronized(mSubscriptionLock)
            {
                if(mSubscription != null || mReleased.get() || mClosing.get() || mRevoked.get())
                {
                    return;
                }

                var candidate = mSpectrumStreamService.trySubscribe();

                if(candidate.isEmpty())
                {
                    terminate(StatusCode.TRY_AGAIN_LATER, "signal subscriber capacity is exhausted", false);
                    return;
                }

                SpectrumStreamService.Subscription subscription = candidate.get();
                mSubscription = subscription;

                try
                {
                    mPumpTask = mSendExecutor.submit(() -> pump(subscription));
                }
                catch(RuntimeException exception)
                {
                    mSubscription = null;
                    subscription.close();
                    throw exception;
                }
            }
        }

        private void update(Integer maximumFramesPerSecond)
        {
            if(maximumFramesPerSecond == null)
            {
                throw new IllegalArgumentException("Signal update requires maxFps");
            }

            mMinimumFrameIntervalNanos.set(frameIntervalNanos(maximumFramesPerSecond));
        }

        private void unsubscribe()
        {
            SpectrumStreamService.Subscription subscription;
            Future<?> pumpTask;

            synchronized(mSubscriptionLock)
            {
                subscription = mSubscription;
                pumpTask = mPumpTask;
                mSubscription = null;
                mPumpTask = null;
            }

            if(subscription != null)
            {
                subscription.close();
            }

            if(pumpTask != null)
            {
                pumpTask.cancel(true);
            }
        }

        private void pump(SpectrumStreamService.Subscription subscription)
        {
            long lastSentNanos = Long.MIN_VALUE;

            try
            {
                while(!mReleased.get() && !mClosing.get() && !mRevoked.get() && !subscription.isClosed())
                {
                    SpectrumFrame frame = subscription.poll(mConfiguration.framePollTimeout());

                    if(frame == null)
                    {
                        continue;
                    }

                    long now = System.nanoTime();
                    long minimumInterval = mMinimumFrameIntervalNanos.get();

                    if(lastSentNanos != Long.MIN_VALUE && now - lastSentNanos < minimumInterval)
                    {
                        continue;
                    }

                    Session session = mSession;

                    if(session == null || !session.isOpen())
                    {
                        continue;
                    }

                    ByteBuffer encoded = SpectrumFrameCodec.encodeReadOnly(frame);
                    CompletableFuture<Void> sent = new CompletableFuture<>();
                    session.sendBinary(encoded, org.eclipse.jetty.websocket.api.Callback.from(
                        () -> sent.complete(null), sent::completeExceptionally));
                    sent.get(mConfiguration.sendTimeout().toNanos(), TimeUnit.NANOSECONDS);
                    lastSentNanos = System.nanoTime();
                    mDeliveredFrameCount.incrementAndGet();
                }
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
            catch(ExecutionException | TimeoutException | RuntimeException exception)
            {
                if(!mReleased.get() && !mRevoked.get() && !mClosed.get())
                {
                    mFailedSendCount.incrementAndGet();
                    terminate(StatusCode.SERVER_ERROR, "signal delivery failed", false);
                }
            }
        }

        private boolean revoke()
        {
            boolean newlyRevoked = mRevoked.compareAndSet(false, true);
            terminate(StatusCode.POLICY_VIOLATION, "signal access changed", false);
            return newlyRevoked;
        }

        private void terminate(int statusCode, String reason, boolean forceRelease)
        {
            mClosing.set(true);
            unsubscribe();
            Session session = mSession;

            if(session != null && session.isOpen())
            {
                session.close(statusCode, reason, org.eclipse.jetty.websocket.api.Callback.NOOP);
            }

            if(forceRelease || session == null || !session.isOpen())
            {
                cleanup();
            }
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            cleanup();
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason,
                                     org.eclipse.jetty.websocket.api.Callback callback)
        {
            try
            {
                cleanup();
                callback.succeed();
            }
            catch(RuntimeException exception)
            {
                callback.fail(exception);
            }
        }

        private void cleanup()
        {
            if(!mReleased.compareAndSet(false, true))
            {
                return;
            }

            unsubscribe();
            release(this);
        }
    }

    private static long frameIntervalNanos(int maximumFramesPerSecond)
    {
        return TimeUnit.SECONDS.toNanos(1) / maximumFramesPerSecond;
    }

    private enum ControlAction
    {
        SUBSCRIBE,
        UPDATE,
        UNSUBSCRIBE
    }

    private record ControlMessage(ControlAction action, Integer maximumFramesPerSecond)
    {
        private static ControlMessage parse(String message, int configuredMaximumFramesPerSecond)
        {
            if(message == null || message.isBlank() || message.length() > MAXIMUM_CONTROL_CHARACTERS)
            {
                throw new IllegalArgumentException("Signal control is blank or too large");
            }

            try
            {
                JsonNode root = OBJECT_MAPPER.readTree(message);

                if(root == null || !root.isObject())
                {
                    throw new IllegalArgumentException("Signal control must be a JSON object");
                }

                JsonNode actionNode = root.get("action");

                if(actionNode == null || !actionNode.isTextual())
                {
                    throw new IllegalArgumentException("Signal control requires a textual action");
                }

                ControlAction action = switch(actionNode.textValue())
                {
                    case "subscribe" -> ControlAction.SUBSCRIBE;
                    case "update" -> ControlAction.UPDATE;
                    case "unsubscribe" -> ControlAction.UNSUBSCRIBE;
                    default -> throw new IllegalArgumentException("Unknown signal control action");
                };
                JsonNode maximumFramesPerSecondNode = root.get("maxFps");
                Integer maximumFramesPerSecond = null;

                if(maximumFramesPerSecondNode != null)
                {
                    if(!maximumFramesPerSecondNode.isIntegralNumber() ||
                        !maximumFramesPerSecondNode.canConvertToInt())
                    {
                        throw new IllegalArgumentException("maxFps must be an integer");
                    }

                    maximumFramesPerSecond = maximumFramesPerSecondNode.intValue();

                    if(maximumFramesPerSecond < 1 || maximumFramesPerSecond > configuredMaximumFramesPerSecond)
                    {
                        throw new IllegalArgumentException("maxFps is outside the configured bounds");
                    }
                }

                if(action == ControlAction.UPDATE && maximumFramesPerSecond == null)
                {
                    throw new IllegalArgumentException("Signal update requires maxFps");
                }

                if(action == ControlAction.UNSUBSCRIBE && maximumFramesPerSecond != null)
                {
                    throw new IllegalArgumentException("Signal unsubscribe does not accept maxFps");
                }

                return new ControlMessage(action, maximumFramesPerSecond);
            }
            catch(IllegalArgumentException exception)
            {
                throw exception;
            }
            catch(Exception exception)
            {
                throw new IllegalArgumentException("Unable to parse signal control", exception);
            }
        }
    }

    public record Configuration(int maximumSessions, int defaultMaximumFramesPerSecond,
                                int maximumFramesPerSecond, Duration framePollTimeout, Duration sendTimeout,
                                Duration shutdownTimeout, String sendThreadNamePrefix)
    {
        public Configuration
        {
            if(maximumSessions < 1 || maximumSessions > 256)
            {
                throw new IllegalArgumentException("Maximum signal session count must be between 1 and 256");
            }

            if(defaultMaximumFramesPerSecond < 1 || maximumFramesPerSecond < defaultMaximumFramesPerSecond ||
                maximumFramesPerSecond > 240)
            {
                throw new IllegalArgumentException("Invalid signal frame-rate bounds");
            }

            requirePositive(framePollTimeout, "Signal frame poll timeout");
            requirePositive(sendTimeout, "Signal send timeout");
            requirePositive(shutdownTimeout, "Signal shutdown timeout");

            if(sendThreadNamePrefix == null || sendThreadNamePrefix.isBlank())
            {
                throw new IllegalArgumentException("Signal send thread prefix cannot be blank");
            }
        }

        private static void requirePositive(Duration duration, String label)
        {
            Objects.requireNonNull(duration, label + " cannot be null");

            if(duration.isZero() || duration.isNegative() || duration.toNanos() <= 0)
            {
                throw new IllegalArgumentException(label + " must be positive");
            }
        }

        public static Configuration defaults()
        {
            return new Configuration(16, 20, 30, Duration.ofMillis(250), Duration.ofSeconds(3),
                Duration.ofSeconds(5), "sdrtrunk signal sender-");
        }
    }
}
