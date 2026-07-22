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
import io.github.dsheirer.spectrum.stream.InteractiveSpectrumFrameSource;
import io.github.dsheirer.spectrum.stream.SpectrumFrame;
import io.github.dsheirer.spectrum.stream.SpectrumFrameCodec;
import io.github.dsheirer.spectrum.stream.SpectrumStreamService;
import io.github.dsheirer.web.access.AuthorizationSubject;
import io.github.dsheirer.web.access.FeatureAccessDecision;
import io.github.dsheirer.web.access.FeaturePolicyChange;
import io.github.dsheirer.web.access.InMemoryFeatureAccessPolicy;
import io.github.dsheirer.web.access.RemoteAddressAdmissionPolicy;
import io.github.dsheirer.web.access.WebFeature;
import io.github.dsheirer.web.access.WebTransport;
import io.github.dsheirer.web.signal.SignalSubjectResolver.SignalAuthorization;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
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
 * Exclusive, administrator-only WebSocket adapter for the interactive wideband spectrum stream.
 *
 * <p>The feature remains administrator-only even if a mutable compatibility policy is accidentally configured
 * PUBLIC.  Exactly one authorized socket is admitted node-wide.  The producer never serializes a frame or waits for
 * a socket, and view controls are validated before being coalesced by the interactive source.</p>
 */
public final class SignalWebSocketTransport implements AutoCloseable
{
    public static final String PATH = "/api/v1/ws/signal";
    public static final int ACCESS_REVOKED_CLOSE_CODE = 4403;
    public static final int BUSY_CLOSE_CODE = 4409;
    public static final int SOURCE_UNAVAILABLE_CLOSE_CODE = 4410;

    private static final int MAXIMUM_CONTROL_CHARACTERS = 1_024;
    private static final long MAXIMUM_SAFE_JSON_INTEGER = 9_007_199_254_740_991L;
    private static final long SOURCE_UNAVAILABLE_GRACE_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long FRAME_STALL_GRACE_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final Logger mLog = LoggerFactory.getLogger(SignalWebSocketTransport.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Configuration mConfiguration;
    private final SpectrumStreamService mSpectrumStreamService;
    private final InMemoryFeatureAccessPolicy mAccessPolicy;
    private final SignalSubjectResolver mSubjectResolver;
    private final SignalOriginPolicy mOriginPolicy;
    private final RemoteAddressAdmissionPolicy mRemoteAddressAdmissionPolicy;
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
    private final AtomicLong mMaximumSendNanos = new AtomicLong();
    private final AtomicLong mMaximumDeliveryGapNanos = new AtomicLong();

    public SignalWebSocketTransport(Configuration configuration, SpectrumStreamService spectrumStreamService,
                                    InMemoryFeatureAccessPolicy accessPolicy,
                                    SignalSubjectResolver subjectResolver, SignalOriginPolicy originPolicy,
                                    RemoteAddressAdmissionPolicy remoteAddressAdmissionPolicy)
    {
        mConfiguration = Objects.requireNonNull(configuration, "Signal transport configuration cannot be null");
        mSpectrumStreamService = Objects.requireNonNull(spectrumStreamService,
            "Spectrum stream service cannot be null");
        mAccessPolicy = Objects.requireNonNull(accessPolicy, "Feature access policy cannot be null");
        mSubjectResolver = Objects.requireNonNull(subjectResolver, "Signal subject resolver cannot be null");
        mOriginPolicy = Objects.requireNonNull(originPolicy, "Signal origin policy cannot be null");
        mRemoteAddressAdmissionPolicy = Objects.requireNonNull(remoteAddressAdmissionPolicy,
            "Remote-address admission policy cannot be null");
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

        if(!mRemoteAddressAdmissionPolicy.isAllowed(request) || !mOriginPolicy.isAllowed(request))
        {
            mRejectedHandshakeCount.incrementAndGet();
            reject(response, callback, HttpStatus.FORBIDDEN_403, false);
            return null;
        }

        SignalAuthorization authorization;

        try
        {
            authorization = Objects.requireNonNull(mSubjectResolver.resolveAuthorization(request),
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

        if(!authorize(authorization))
        {
            mRejectedHandshakeCount.incrementAndGet();
            reject(response, callback, HttpStatus.UNAUTHORIZED_401, true);
            return null;
        }

        if(!mSessionPermits.tryAcquire())
        {
            mRejectedHandshakeCount.incrementAndGet();
            reject(response, callback, HttpStatus.CONFLICT_409, false);
            return null;
        }

        SignalEndpoint endpoint = new SignalEndpoint(authorization);
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

    private boolean authorize(SignalAuthorization authorization)
    {
        AuthorizationSubject subject = authorization.subject();

        if(subject == null || !subject.isAuthenticatedAdmin())
        {
            return false;
        }

        if(!authorization.isSessionValid())
        {
            return false;
        }

        FeatureAccessDecision decision =
            mAccessPolicy.authorize(WebFeature.WIDEBAND_SIGNAL, subject, WebTransport.WEBSOCKET);
        return decision.isAllowed();
    }

    private void policyChanged(FeaturePolicyChange change)
    {
        if(change.feature() != WebFeature.WIDEBAND_SIGNAL || !change.revokesAnonymousAccess())
        {
            return;
        }

        for(SignalEndpoint endpoint: mEndpoints)
        {
            if(endpoint.isAnonymous())
            {
                endpoint.revoke();
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

    public long getMaximumSendNanos()
    {
        return mMaximumSendNanos.get();
    }

    public long getMaximumDeliveryGapNanos()
    {
        return mMaximumDeliveryGapNanos.get();
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
        private final SignalAuthorization mAuthorization;
        private final Object mSubscriptionLock = new Object();
        private final AtomicBoolean mReleased = new AtomicBoolean();
        private final AtomicBoolean mClosing = new AtomicBoolean();
        private final AtomicBoolean mRevoked = new AtomicBoolean();
        private final AtomicLong mMinimumFrameIntervalNanos = new AtomicLong(
            frameIntervalNanos(mConfiguration.defaultMaximumFramesPerSecond()));
        private final AtomicLong mLastRequestId = new AtomicLong(-1);
        private final AtomicReference<LiveStateKey> mLastLiveState = new AtomicReference<>();
        private final AtomicReference<Map<String,Object>> mPendingState = new AtomicReference<>();
        private final ReentrantLock mSendLock = new ReentrantLock();
        private volatile Session mSession;
        private volatile String mSelectedTargetId;
        private volatile Future<?> mAuthorizationMonitorTask;
        private SpectrumStreamService.Subscription mSubscription;
        private Future<?> mPumpTask;

        private SignalEndpoint(SignalAuthorization authorization)
        {
            mAuthorization = authorization;
        }

        private boolean isAnonymous()
        {
            return mAuthorization.subject() == AuthorizationSubject.ANONYMOUS;
        }

        @Override
        public void onWebSocketOpen(Session session)
        {
            mSession = session;

            // Close the authorization race between creator evaluation and session activation.
            if(mClosed.get() || mReleased.get() || mClosing.get() || mRevoked.get() ||
                !authorize(mAuthorization))
            {
                terminate(ACCESS_REVOKED_CLOSE_CODE, "signal access changed", false);
                return;
            }

            try
            {
                mAuthorizationMonitorTask = mSendExecutor.submit(this::monitorAuthorization);
            }
            catch(RuntimeException exception)
            {
                terminate(StatusCode.SERVER_ERROR, "signal authorization monitor failed", false);
                return;
            }

            sendReadyState();
        }

        private void monitorAuthorization()
        {
            try
            {
                while(!mClosed.get() && !mReleased.get() && !mClosing.get() && !mRevoked.get())
                {
                    Thread.sleep(mConfiguration.framePollTimeout());

                    if(!authorize(mAuthorization))
                    {
                        revoke();
                        return;
                    }
                }
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        }

        private void sendReadyState()
        {
            List<Map<String,Object>> targets = mSpectrumStreamService.getTargets().stream()
                .map(target -> Map.<String,Object>of("id", target.id(), "label", target.label())).toList();
            sendStateAsync(Map.of(
                "type", "signal-state",
                "state", "ready",
                "exclusive", true,
                "targets", targets
            ));
        }

        @Override
        public void onWebSocketText(String message)
        {
            if(mReleased.get() || mClosing.get() || mRevoked.get() || !authorize(mAuthorization))
            {
                revoke();
                return;
            }

            try
            {
                ControlMessage control = ControlMessage.parse(message, mConfiguration.maximumFramesPerSecond());

                switch(control.action())
                {
                    case SUBSCRIBE -> subscribe(control);
                    case UPDATE -> update(control);
                    case UNSUBSCRIBE -> unsubscribe();
                }
            }
            catch(IllegalArgumentException exception)
            {
                terminate(StatusCode.POLICY_VIOLATION, "invalid signal control", false);
            }
        }

        private void subscribe(ControlMessage control)
        {
            Integer maximumFramesPerSecond = control.maximumFramesPerSecond();

            if(maximumFramesPerSecond != null)
            {
                mMinimumFrameIntervalNanos.set(frameIntervalNanos(maximumFramesPerSecond));
            }

            long requestId = acceptRequestId(control.requestId());
            String targetId = resolveTarget(control.targetId());
            mSelectedTargetId = targetId;
            mSpectrumStreamService.requestView(new InteractiveSpectrumFrameSource.ViewRequest(requestId,
                targetId, null));
            queueRefining(requestId);

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

        private void update(ControlMessage control)
        {
            Integer maximumFramesPerSecond = control.maximumFramesPerSecond();

            if(maximumFramesPerSecond != null)
            {
                mMinimumFrameIntervalNanos.set(frameIntervalNanos(maximumFramesPerSecond));
            }

            if(control.targetId() == null && control.viewport() == null)
            {
                return;
            }

            long requestId = acceptRequestId(control.requestId());
            String targetId = control.targetId() != null ? resolveTarget(control.targetId()) : mSelectedTargetId;

            if(targetId == null)
            {
                throw new IllegalArgumentException("Signal target must be selected before changing its viewport");
            }

            if(control.targetId() != null && mSelectedTargetId != null &&
                !targetId.equals(mSelectedTargetId) && control.viewport() != null)
            {
                throw new IllegalArgumentException("Changing signal target requires a full-width view");
            }

            InteractiveSpectrumFrameSource.Viewport viewport = control.viewport();

            if(control.targetId() != null && !targetId.equals(mSelectedTargetId))
            {
                viewport = null;
            }

            mSelectedTargetId = targetId;
            mSpectrumStreamService.requestView(new InteractiveSpectrumFrameSource.ViewRequest(requestId,
                targetId, viewport));
            queueRefining(requestId);
        }

        private long acceptRequestId(Long requested)
        {
            long requestId = requested != null ? requested : Math.max(0, mLastRequestId.get() + 1);

            while(true)
            {
                long previous = mLastRequestId.get();

                if(requestId <= previous)
                {
                    throw new IllegalArgumentException("Signal requestId must increase");
                }

                if(mLastRequestId.compareAndSet(previous, requestId))
                {
                    return requestId;
                }
            }
        }

        private String resolveTarget(String requested)
        {
            List<InteractiveSpectrumFrameSource.Target> targets = mSpectrumStreamService.getTargets();

            if(requested != null)
            {
                return targets.stream().filter(target -> target.id().equals(requested))
                    .map(InteractiveSpectrumFrameSource.Target::id).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Signal target is unavailable"));
            }

            if(targets.size() != 1)
            {
                throw new IllegalArgumentException("Signal target selection is required");
            }

            return targets.getFirst().id();
        }

        private void queueRefining(long requestId)
        {
            mPendingState.set(Map.of(
                "type", "signal-state",
                "state", "refining",
                "requestId", requestId
            ));
        }

        private void sendStateAsync(Map<String,Object> state)
        {
            try
            {
                mSendExecutor.execute(() ->
                {
                    try
                    {
                        sendText(state);
                    }
                    catch(InterruptedException exception)
                    {
                        Thread.currentThread().interrupt();
                    }
                    catch(ExecutionException | TimeoutException | RuntimeException exception)
                    {
                        if(!mReleased.get() && !mClosed.get())
                        {
                            mFailedSendCount.incrementAndGet();
                            terminate(StatusCode.SERVER_ERROR, "signal state delivery failed", false);
                        }
                    }
                });
            }
            catch(RuntimeException exception)
            {
                terminate(StatusCode.SERVER_ERROR, "signal state delivery failed", false);
            }
        }

        private void sendText(Map<String,Object> state)
            throws ExecutionException, InterruptedException, TimeoutException
        {
            final String message;

            try
            {
                message = OBJECT_MAPPER.writeValueAsString(state);
            }
            catch(Exception exception)
            {
                throw new IllegalStateException("Unable to encode signal state", exception);
            }

            Session session = mSession;

            if(session == null || !session.isOpen())
            {
                return;
            }

            mSendLock.lockInterruptibly();

            try
            {
                CompletableFuture<Void> sent = new CompletableFuture<>();
                session.sendText(message, org.eclipse.jetty.websocket.api.Callback.from(
                    () -> sent.complete(null), sent::completeExceptionally));
                sent.get(mConfiguration.sendTimeout().toNanos(), TimeUnit.NANOSECONDS);
            }
            finally
            {
                mSendLock.unlock();
            }
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
            long nextFrameTimestampNanos = Long.MIN_VALUE;
            long previousDeliveryNanos = Long.MIN_VALUE;
            long sourceUnavailableSinceNanos = Long.MIN_VALUE;
            long lastFrameNanos = System.nanoTime();

            try
            {
                while(!mReleased.get() && !mClosing.get() && !mRevoked.get() && !subscription.isClosed())
                {
                    Map<String,Object> pendingState = mPendingState.getAndSet(null);

                    if(pendingState != null)
                    {
                        sendText(pendingState);
                    }

                    SpectrumFrame frame = subscription.poll(mConfiguration.framePollTimeout());

                    if(frame == null)
                    {
                        long now = System.nanoTime();

                        if(now - lastFrameNanos >= FRAME_STALL_GRACE_NANOS)
                        {
                            terminate(SOURCE_UNAVAILABLE_CLOSE_CODE, "signal source unavailable", false);
                            return;
                        }

                        if(mSpectrumStreamService.isSourceRunning())
                        {
                            sourceUnavailableSinceNanos = Long.MIN_VALUE;
                        }
                        else if(sourceUnavailableSinceNanos == Long.MIN_VALUE)
                        {
                            sourceUnavailableSinceNanos = System.nanoTime();
                        }
                        else if(System.nanoTime() - sourceUnavailableSinceNanos >= SOURCE_UNAVAILABLE_GRACE_NANOS)
                        {
                            terminate(SOURCE_UNAVAILABLE_CLOSE_CODE, "signal source unavailable", false);
                            return;
                        }

                        continue;
                    }

                    sourceUnavailableSinceNanos = Long.MIN_VALUE;
                    lastFrameNanos = System.nanoTime();

                    if(mSpectrumStreamService.isInteractive() && frame.getViewRevision() < mLastRequestId.get())
                    {
                        continue;
                    }

                    LiveStateKey liveState = LiveStateKey.from(frame);

                    if(!liveState.equals(mLastLiveState.getAndSet(liveState)))
                    {
                        sendLiveState(frame);
                    }

                    long minimumInterval = mMinimumFrameIntervalNanos.get();
                    long frameTimestamp = frame.getMonotonicTimestampNanos();

                    if(nextFrameTimestampNanos != Long.MIN_VALUE)
                    {
                        long lateBy = frameTimestamp - nextFrameTimestampNanos;

                        if(lateBy > minimumInterval)
                        {
                            // Do not burst to catch up after a source pause or a slow socket.
                            nextFrameTimestampNanos = frameTimestamp;
                        }
                        else if(frameTimestamp + pacingToleranceNanos(minimumInterval) < nextFrameTimestampNanos)
                        {
                            continue;
                        }
                    }
                    else
                    {
                        nextFrameTimestampNanos = frameTimestamp;
                    }

                    Session session = mSession;

                    if(session == null || !session.isOpen())
                    {
                        continue;
                    }

                    long sendStartedNanos = System.nanoTime();
                    sendBinary(session, SpectrumFrameCodec.encodeReadOnly(frame));
                    long deliveredNanos = System.nanoTime();
                    mMaximumSendNanos.accumulateAndGet(deliveredNanos - sendStartedNanos, Math::max);

                    if(previousDeliveryNanos != Long.MIN_VALUE)
                    {
                        mMaximumDeliveryGapNanos.accumulateAndGet(deliveredNanos - previousDeliveryNanos, Math::max);
                    }

                    previousDeliveryNanos = deliveredNanos;
                    nextFrameTimestampNanos = saturatingAdd(nextFrameTimestampNanos, minimumInterval);
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

        private void sendLiveState(SpectrumFrame frame)
            throws ExecutionException, InterruptedException, TimeoutException
        {
            String targetId = mSelectedTargetId != null ? mSelectedTargetId : "DEFAULT";
            String selectedTargetId = targetId;
            String targetLabel = mSpectrumStreamService.getTargets().stream()
                .filter(target -> target.id().equals(selectedTargetId)).map(InteractiveSpectrumFrameSource.Target::label)
                .findFirst().orElse("Spectrum");
            InteractiveSpectrumFrameSource.AppliedView applied = mSpectrumStreamService.getAppliedView();

            if(applied != null && applied.revision() == frame.getViewRevision() &&
                applied.targetGeneration() == frame.getTargetGeneration())
            {
                targetId = applied.targetId();
                targetLabel = applied.targetLabel();
            }

            double binWidthHz = (double)frame.getSampleRateHz() / frame.getFftSize();
            double visibleStartHz = frame.getCenterFrequencyHz() - frame.getSampleRateHz() / 2.0 +
                frame.getFirstBin() * binWidthHz;
            sendText(Map.ofEntries(
                Map.entry("type", "signal-state"),
                Map.entry("state", "live"),
                Map.entry("requestId", frame.getViewRevision()),
                Map.entry("targetId", targetId),
                Map.entry("targetLabel", targetLabel),
                Map.entry("targetGeneration", frame.getTargetGeneration()),
                Map.entry("viewRevision", frame.getViewRevision()),
                Map.entry("centerFrequencyHz", frame.getCenterFrequencyHz()),
                Map.entry("sampleRateHz", frame.getSampleRateHz()),
                Map.entry("visibleStartHz", visibleStartHz),
                Map.entry("visibleEndHz", visibleStartHz + frame.getBinCount() * binWidthHz),
                Map.entry("binWidthHz", binWidthHz),
                Map.entry("fftSize", frame.getFftSize()),
                Map.entry("firstBin", frame.getFirstBin()),
                Map.entry("binCount", frame.getBinCount()),
                Map.entry("maxFps", (int)(TimeUnit.SECONDS.toNanos(1) / mMinimumFrameIntervalNanos.get()))
            ));
        }

        private void sendBinary(Session session, ByteBuffer encoded)
            throws ExecutionException, InterruptedException, TimeoutException
        {
            mSendLock.lockInterruptibly();

            try
            {
                CompletableFuture<Void> sent = new CompletableFuture<>();
                session.sendBinary(encoded, org.eclipse.jetty.websocket.api.Callback.from(
                    () -> sent.complete(null), sent::completeExceptionally));
                sent.get(mConfiguration.sendTimeout().toNanos(), TimeUnit.NANOSECONDS);
            }
            finally
            {
                mSendLock.unlock();
            }
        }

        private boolean revoke()
        {
            boolean newlyRevoked = mRevoked.compareAndSet(false, true);

            if(newlyRevoked)
            {
                mRevokedSessionCount.incrementAndGet();
            }

            terminate(ACCESS_REVOKED_CLOSE_CODE, "signal access changed", false);
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
            Future<?> authorizationMonitorTask = mAuthorizationMonitorTask;
            mAuthorizationMonitorTask = null;

            if(authorizationMonitorTask != null)
            {
                authorizationMonitorTask.cancel(true);
            }

            release(this);
        }
    }

    private static long frameIntervalNanos(int maximumFramesPerSecond)
    {
        return TimeUnit.SECONDS.toNanos(1) / maximumFramesPerSecond;
    }

    private static long pacingToleranceNanos(long frameIntervalNanos)
    {
        return Math.min(TimeUnit.MILLISECONDS.toNanos(2), Math.max(1, frameIntervalNanos / 20));
    }

    private static long saturatingAdd(long value, long increment)
    {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private enum ControlAction
    {
        SUBSCRIBE,
        UPDATE,
        UNSUBSCRIBE
    }

    /**
     * Metadata that defines the frequency domain represented by a binary frame.  Tuner frequency and sample-rate
     * notifications can change this domain without changing the browser's view revision, so revision alone is not a
     * sufficient trigger for refreshed live metadata.
     */
    record LiveStateKey(long viewRevision, long targetGeneration, long centerFrequencyHz, long sampleRateHz,
                        int fftSize, int firstBin, int binCount)
    {
        static LiveStateKey from(SpectrumFrame frame)
        {
            return new LiveStateKey(frame.getViewRevision(), frame.getTargetGeneration(),
                frame.getCenterFrequencyHz(), frame.getSampleRateHz(), frame.getFftSize(), frame.getFirstBin(),
                frame.getBinCount());
        }
    }

    private record ControlMessage(ControlAction action, Long requestId, Integer maximumFramesPerSecond,
                                  String targetId, InteractiveSpectrumFrameSource.Viewport viewport)
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

                root.fieldNames().forEachRemaining(field ->
                {
                    if(!Set.of("action", "requestId", "maxFps", "targetId", "viewport").contains(field))
                    {
                        throw new IllegalArgumentException("Unknown signal control field");
                    }
                });

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
                Long requestId = optionalSafeInteger(root.get("requestId"), "requestId");
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

                String targetId = null;
                JsonNode targetIdNode = root.get("targetId");

                if(targetIdNode != null)
                {
                    if(!targetIdNode.isTextual())
                    {
                        throw new IllegalArgumentException("targetId must be text");
                    }

                    targetId = targetIdNode.textValue().trim().toUpperCase(Locale.ROOT);
                    new InteractiveSpectrumFrameSource.Target(targetId, targetId);
                }

                InteractiveSpectrumFrameSource.Viewport viewport = null;
                JsonNode viewportNode = root.get("viewport");

                if(viewportNode != null)
                {
                    if(!viewportNode.isObject() || viewportNode.size() != 2 ||
                        !viewportNode.has("startHz") || !viewportNode.has("endHz"))
                    {
                        throw new IllegalArgumentException("viewport requires only startHz and endHz");
                    }

                    Long startHz = optionalSafeInteger(viewportNode.get("startHz"), "viewport startHz");
                    Long endHz = optionalSafeInteger(viewportNode.get("endHz"), "viewport endHz");

                    if(startHz == null || endHz == null)
                    {
                        throw new IllegalArgumentException("viewport frequencies are required");
                    }

                    viewport = new InteractiveSpectrumFrameSource.Viewport(startHz, endHz);
                }

                if(action == ControlAction.UPDATE && maximumFramesPerSecond == null && targetId == null &&
                    viewport == null)
                {
                    throw new IllegalArgumentException("Signal update has no changes");
                }

                if(action == ControlAction.SUBSCRIBE && viewport != null)
                {
                    throw new IllegalArgumentException("Signal subscribe starts at full width");
                }

                if(action == ControlAction.UNSUBSCRIBE &&
                    (requestId != null || maximumFramesPerSecond != null || targetId != null || viewport != null))
                {
                    throw new IllegalArgumentException("Signal unsubscribe does not accept options");
                }

                return new ControlMessage(action, requestId, maximumFramesPerSecond, targetId, viewport);
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

        private static Long optionalSafeInteger(JsonNode node, String label)
        {
            if(node == null)
            {
                return null;
            }

            if(!node.isIntegralNumber() || !node.canConvertToLong())
            {
                throw new IllegalArgumentException(label + " must be an integer");
            }

            long value = node.longValue();

            if(value < 0 || value > MAXIMUM_SAFE_JSON_INTEGER)
            {
                throw new IllegalArgumentException(label + " is outside the safe integer range");
            }

            return value;
        }
    }

    public record Configuration(int maximumSessions, int defaultMaximumFramesPerSecond,
                                int maximumFramesPerSecond, Duration framePollTimeout, Duration sendTimeout,
                                Duration shutdownTimeout, String sendThreadNamePrefix)
    {
        public Configuration
        {
            if(maximumSessions != 1)
            {
                throw new IllegalArgumentException("Interactive signal transport requires exactly one session");
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
            return new Configuration(1, 20, 30, Duration.ofMillis(250), Duration.ofSeconds(3),
                Duration.ofSeconds(5), "sdrtrunk signal sender-");
        }
    }
}
