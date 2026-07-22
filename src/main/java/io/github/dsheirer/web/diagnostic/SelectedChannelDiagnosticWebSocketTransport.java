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

package io.github.dsheirer.web.diagnostic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.dsp.symbol.stream.SelectedChannelSymbolSource;
import io.github.dsheirer.dsp.symbol.stream.SymbolFrame;
import io.github.dsheirer.dsp.symbol.stream.SymbolFrameCodec;
import io.github.dsheirer.spectrum.stream.SelectedChannelSpectrumSource;
import io.github.dsheirer.spectrum.stream.SpectrumFrame;
import io.github.dsheirer.spectrum.stream.SpectrumFrameCodec;
import io.github.dsheirer.web.access.AuthorizationSubject;
import io.github.dsheirer.web.access.FeatureAccessDecision;
import io.github.dsheirer.web.access.InMemoryFeatureAccessPolicy;
import io.github.dsheirer.web.access.RemoteAddressAdmissionPolicy;
import io.github.dsheirer.web.access.WebFeature;
import io.github.dsheirer.web.access.WebTransport;
import io.github.dsheirer.web.diagnostic.DiagnosticWorkspaceLease.Owner;
import io.github.dsheirer.web.diagnostic.SelectedChannelDiagnosticService.ContextDetails;
import io.github.dsheirer.web.diagnostic.SelectedChannelDiagnosticService.OpenResult;
import io.github.dsheirer.web.diagnostic.SelectedChannelDiagnosticService.OpenStatus;
import io.github.dsheirer.web.diagnostic.SelectedChannelDiagnosticService.State;
import io.github.dsheirer.web.diagnostic.SelectedChannelDiagnosticService.StateType;
import io.github.dsheirer.web.diagnostic.SelectedChannelDiagnosticService.View;
import io.github.dsheirer.web.signal.SignalOriginPolicy;
import io.github.dsheirer.web.signal.SignalSubjectResolver;
import io.github.dsheirer.web.signal.SignalSubjectResolver.SignalAuthorization;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * Administrator-only WebSocket for the one demand-owned selected-channel FFT or symbol source.
 *
 * <p>Socket writes and processing-chain attachment run on virtual control tasks.  Decoder and sample callbacks only
 * publish into one bounded latest-frame slot and never wait for this transport.</p>
 */
public final class SelectedChannelDiagnosticWebSocketTransport implements AutoCloseable
{
    public static final String PATH = "/api/v1/ws/channel-diagnostics";
    public static final int ACCESS_REVOKED_CLOSE_CODE = 4403;
    public static final int BUSY_CLOSE_CODE = 4409;
    public static final int SELECTION_ENDED_CLOSE_CODE = 4411;

    private static final int MAXIMUM_CONTROL_CHARACTERS = 1_024;
    private static final long MAXIMUM_SAFE_JSON_INTEGER = 9_007_199_254_740_991L;
    private static final Logger mLog = LoggerFactory.getLogger(SelectedChannelDiagnosticWebSocketTransport.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Configuration mConfiguration;
    private final SelectedChannelDiagnosticService mDiagnosticService;
    private final InMemoryFeatureAccessPolicy mAccessPolicy;
    private final SignalSubjectResolver mSubjectResolver;
    private final SignalOriginPolicy mOriginPolicy;
    private final RemoteAddressAdmissionPolicy mRemoteAddressAdmissionPolicy;
    private final DiagnosticWorkspaceLease mWorkspaceLease;
    private final Set<DiagnosticEndpoint> mEndpoints = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ExecutorService mExecutor;
    private final AtomicBoolean mConfigured = new AtomicBoolean();
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final AtomicLong mRejectedHandshakeCount = new AtomicLong();
    private final AtomicLong mDeliveredSignalFrameCount = new AtomicLong();
    private final AtomicLong mDeliveredSymbolFrameCount = new AtomicLong();
    private final AtomicLong mFailedSendCount = new AtomicLong();
    private final AtomicLong mRevokedSessionCount = new AtomicLong();
    private final AtomicLong mMaximumSendNanos = new AtomicLong();

    public SelectedChannelDiagnosticWebSocketTransport(Configuration configuration,
                                                        SelectedChannelDiagnosticService diagnosticService,
                                                        InMemoryFeatureAccessPolicy accessPolicy,
                                                        SignalSubjectResolver subjectResolver,
                                                        SignalOriginPolicy originPolicy,
                                                        RemoteAddressAdmissionPolicy remoteAddressAdmissionPolicy,
                                                        DiagnosticWorkspaceLease workspaceLease)
    {
        mConfiguration = Objects.requireNonNull(configuration, "Diagnostic transport configuration cannot be null");
        mDiagnosticService = Objects.requireNonNull(diagnosticService, "Diagnostic service cannot be null");
        mAccessPolicy = Objects.requireNonNull(accessPolicy, "Feature access policy cannot be null");
        mSubjectResolver = Objects.requireNonNull(subjectResolver, "Diagnostic subject resolver cannot be null");
        mOriginPolicy = Objects.requireNonNull(originPolicy, "Diagnostic origin policy cannot be null");
        mRemoteAddressAdmissionPolicy = Objects.requireNonNull(remoteAddressAdmissionPolicy,
            "Remote-address admission policy cannot be null");
        mWorkspaceLease = Objects.requireNonNull(workspaceLease, "Diagnostic workspace lease cannot be null");
        mExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
            .name(configuration.threadNamePrefix(), 0)
            .uncaughtExceptionHandler((thread, throwable) ->
                mLog.warn("Uncaught selected-channel diagnostic transport failure", throwable))
            .factory());
    }

    public void configure(ServerWebSocketContainer container)
    {
        Objects.requireNonNull(container, "WebSocket container cannot be null");

        if(mClosed.get())
        {
            throw new IllegalStateException("Selected-channel diagnostic transport is closed");
        }

        if(!mConfigured.compareAndSet(false, true))
        {
            throw new IllegalStateException("Selected-channel diagnostic transport is already configured");
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
                "Diagnostic subject resolver returned null");
        }
        catch(RuntimeException exception)
        {
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

        DiagnosticWorkspaceLease.Lease lease = mWorkspaceLease.tryAcquire(Owner.SELECTED_CHANNEL).orElse(null);

        if(lease == null)
        {
            mRejectedHandshakeCount.incrementAndGet();
            reject(response, callback, HttpStatus.CONFLICT_409, false);
            return null;
        }

        DiagnosticEndpoint endpoint = new DiagnosticEndpoint(authorization, lease);
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

        if(subject == null || !subject.isAuthenticatedAdmin() || !authorization.isSessionValid())
        {
            return false;
        }

        FeatureAccessDecision decision = mAccessPolicy.authorize(WebFeature.SELECTED_CHANNEL_SIGNAL, subject,
            WebTransport.WEBSOCKET);
        return decision.isAllowed();
    }

    private void release(DiagnosticEndpoint endpoint)
    {
        mEndpoints.remove(endpoint);
    }

    public int getActiveSessionCount()
    {
        return mEndpoints.size();
    }

    public long getRejectedHandshakeCount()
    {
        return mRejectedHandshakeCount.get();
    }

    public long getDeliveredSignalFrameCount()
    {
        return mDeliveredSignalFrameCount.get();
    }

    public long getDeliveredSymbolFrameCount()
    {
        return mDeliveredSymbolFrameCount.get();
    }

    public long getFailedSendCount()
    {
        return mFailedSendCount.get();
    }

    public long getRevokedSessionCount()
    {
        return mRevokedSessionCount.get();
    }

    public long getMaximumSendNanos()
    {
        return mMaximumSendNanos.get();
    }

    public boolean isExecutorTerminated()
    {
        return mExecutor.isTerminated();
    }

    @Override
    public void close()
    {
        if(!mClosed.compareAndSet(false, true))
        {
            return;
        }

        RuntimeException failure = null;

        for(DiagnosticEndpoint endpoint: Set.copyOf(mEndpoints))
        {
            try
            {
                endpoint.terminate(StatusCode.SHUTDOWN, "diagnostic service shutdown");
            }
            catch(RuntimeException exception)
            {
                failure = exception;
            }
        }

        mExecutor.shutdownNow();

        try
        {
            if(!mExecutor.awaitTermination(mConfiguration.shutdownTimeout().toNanos(), TimeUnit.NANOSECONDS))
            {
                IllegalStateException exception =
                    new IllegalStateException("Selected-channel diagnostic executor did not terminate");

                if(failure != null)
                {
                    exception.addSuppressed(failure);
                }

                throw exception;
            }
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping selected-channel diagnostics", exception);
        }

        if(failure != null)
        {
            throw failure;
        }
    }

    /** Public so Jetty can validate listener callbacks through method handles. */
    public final class DiagnosticEndpoint implements Session.Listener.AutoDemanding
    {
        private final SignalAuthorization mAuthorization;
        private final DiagnosticWorkspaceLease.Lease mWorkspaceLease;
        private final Object mLifecycleLock = new Object();
        private final ArrayBlockingQueue<ControlMessage> mControls =
            new ArrayBlockingQueue<>(mConfiguration.maximumQueuedControls());
        private final AtomicBoolean mControlDraining = new AtomicBoolean();
        private final AtomicBoolean mReleased = new AtomicBoolean();
        private final AtomicBoolean mClosing = new AtomicBoolean();
        private final AtomicBoolean mRevoked = new AtomicBoolean();
        private final AtomicLong mLastRequestId = new AtomicLong(-1);
        private final AtomicReference<SelectedChannelDiagnosticService.Session> mDiagnosticSession =
            new AtomicReference<>();
        private final ReentrantLock mSendLock = new ReentrantLock();
        private volatile Session mSession;
        private volatile Future<?> mAuthorizationMonitorTask;
        private volatile Future<?> mPumpTask;

        private DiagnosticEndpoint(SignalAuthorization authorization, DiagnosticWorkspaceLease.Lease workspaceLease)
        {
            mAuthorization = authorization;
            mWorkspaceLease = workspaceLease;
        }

        @Override
        public void onWebSocketOpen(Session session)
        {
            mSession = session;

            if(mClosed.get() || mReleased.get() || mClosing.get() || !authorize(mAuthorization))
            {
                terminate(ACCESS_REVOKED_CLOSE_CODE, "diagnostic access changed");
                return;
            }

            try
            {
                mAuthorizationMonitorTask = mExecutor.submit(this::monitorAuthorization);
                sendStateAsync(readyState());
            }
            catch(RuntimeException exception)
            {
                terminate(StatusCode.SERVER_ERROR, "diagnostic startup failed");
            }
        }

        private void monitorAuthorization()
        {
            try
            {
                while(!mClosed.get() && !mReleased.get() && !mClosing.get() && !mRevoked.get())
                {
                    Thread.sleep(mConfiguration.pollTimeout());

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
                ControlMessage control = ControlMessage.parse(message);

                if(!mControls.offer(control))
                {
                    throw new IllegalArgumentException("Too many queued diagnostic controls");
                }

                startControlDrain();
            }
            catch(IllegalArgumentException exception)
            {
                terminate(StatusCode.POLICY_VIOLATION, "invalid diagnostic control");
            }
        }

        private void startControlDrain()
        {
            if(mControlDraining.compareAndSet(false, true))
            {
                try
                {
                    mExecutor.execute(this::drainControls);
                }
                catch(RuntimeException exception)
                {
                    mControlDraining.set(false);
                    terminate(StatusCode.SERVER_ERROR, "diagnostic control failed");
                }
            }
        }

        private void drainControls()
        {
            try
            {
                while(!mReleased.get() && !mClosing.get())
                {
                    ControlMessage control = mControls.poll();

                    if(control == null)
                    {
                        return;
                    }

                    apply(control);
                }
            }
            catch(IllegalArgumentException exception)
            {
                terminate(StatusCode.POLICY_VIOLATION, "invalid diagnostic control");
            }
            catch(RuntimeException exception)
            {
                terminate(StatusCode.SERVER_ERROR, "diagnostic source failed");
            }
            finally
            {
                mControlDraining.set(false);

                if(!mControls.isEmpty() && !mReleased.get() && !mClosing.get())
                {
                    startControlDrain();
                }
            }
        }

        private void apply(ControlMessage control)
        {
            synchronized(mLifecycleLock)
            {
                if(mReleased.get() || mClosing.get())
                {
                    return;
                }

                applyLocked(control);
            }
        }

        private void applyLocked(ControlMessage control)
        {
            acceptRequestId(control.requestId());

            if(control.action() == ControlAction.SUBSCRIBE)
            {
                if(mDiagnosticSession.get() != null)
                {
                    throw new IllegalArgumentException("Diagnostic socket is already subscribed");
                }

                OpenResult result = mDiagnosticService.tryOpen(control.selectionId(), control.view(),
                    control.requestId());

                if(result.status() == OpenStatus.BUSY)
                {
                    terminate(BUSY_CLOSE_CODE, "diagnostic workspace is busy");
                    return;
                }

                if(result.status() == OpenStatus.ENDED)
                {
                    terminate(SELECTION_ENDED_CLOSE_CODE, "selected channel ended");
                    return;
                }

                if(result.status() != OpenStatus.OPEN)
                {
                    throw new IllegalArgumentException("Unable to open selected-channel diagnostics");
                }

                SelectedChannelDiagnosticService.Session candidate = result.session();

                if(mReleased.get() || mClosing.get() || !mDiagnosticSession.compareAndSet(null, candidate))
                {
                    candidate.close();
                    return;
                }

                try
                {
                    mPumpTask = mExecutor.submit(this::pump);
                }
                catch(RuntimeException exception)
                {
                    mDiagnosticSession.compareAndSet(candidate, null);
                    candidate.close();
                    throw exception;
                }

                return;
            }

            SelectedChannelDiagnosticService.Session session = mDiagnosticSession.get();

            if(session == null)
            {
                throw new IllegalArgumentException("Diagnostic socket must subscribe before updating");
            }

            session.update(control.view(), control.requestId());
        }

        private void acceptRequestId(long requested)
        {
            while(true)
            {
                long previous = mLastRequestId.get();

                if(requested <= previous)
                {
                    throw new IllegalArgumentException("Diagnostic requestId must increase");
                }

                if(mLastRequestId.compareAndSet(previous, requested))
                {
                    return;
                }
            }
        }

        private void pump()
        {
            long lastStateRevision = -1;

            try
            {
                while(!mReleased.get() && !mClosing.get() && !mRevoked.get())
                {
                    SelectedChannelDiagnosticService.Session diagnosticSession = mDiagnosticSession.get();

                    if(diagnosticSession == null || diagnosticSession.isClosed())
                    {
                        return;
                    }

                    State state = diagnosticSession.state();

                    if(state != null && state.revision() != lastStateRevision)
                    {
                        if(state.state() == StateType.ENDED)
                        {
                            mClosing.set(true);
                            releaseDiagnosticResources();

                            try
                            {
                                sendText(stateMap(state));
                            }
                            finally
                            {
                                terminate(SELECTION_ENDED_CLOSE_CODE, "selected channel ended");
                            }

                            return;
                        }

                        sendText(stateMap(state));
                        lastStateRevision = state.revision();
                    }

                    if(state == null || state.state() != StateType.LIVE)
                    {
                        Thread.sleep(mConfiguration.pollTimeout());
                        continue;
                    }

                    if(state.view() == View.SIGNAL)
                    {
                        SpectrumFrame frame = diagnosticSession.pollSpectrum(mConfiguration.pollTimeout());
                        State current = diagnosticSession.state();

                        if(frame != null && current != null && current.state() == StateType.LIVE &&
                            current.view() == View.SIGNAL && frame.getTargetGeneration() == current.generation())
                        {
                            sendBinary(SpectrumFrameCodec.encodeReadOnly(frame));
                            mDeliveredSignalFrameCount.incrementAndGet();
                        }
                    }
                    else
                    {
                        SymbolFrame frame = diagnosticSession.pollSymbols(mConfiguration.pollTimeout());
                        State current = diagnosticSession.state();

                        if(frame != null && current != null && current.state() == StateType.LIVE &&
                            current.view() == View.SYMBOLS && frame.getGeneration() == current.generation())
                        {
                            sendBinary(SymbolFrameCodec.encodeReadOnly(frame));
                            mDeliveredSymbolFrameCount.incrementAndGet();
                        }
                    }
                }
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
                    terminate(StatusCode.SERVER_ERROR, "diagnostic delivery failed");
                }
            }
        }

        private void sendStateAsync(Map<String,Object> state)
        {
            try
            {
                mExecutor.execute(() ->
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
                            terminate(StatusCode.SERVER_ERROR, "diagnostic state delivery failed");
                        }
                    }
                });
            }
            catch(RuntimeException exception)
            {
                terminate(StatusCode.SERVER_ERROR, "diagnostic state delivery failed");
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
                throw new IllegalStateException("Unable to encode diagnostic state", exception);
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

        private void sendBinary(ByteBuffer encoded)
            throws ExecutionException, InterruptedException, TimeoutException
        {
            Session session = mSession;

            if(session == null || !session.isOpen())
            {
                return;
            }

            mSendLock.lockInterruptibly();

            try
            {
                long started = System.nanoTime();
                CompletableFuture<Void> sent = new CompletableFuture<>();
                session.sendBinary(encoded, org.eclipse.jetty.websocket.api.Callback.from(
                    () -> sent.complete(null), sent::completeExceptionally));
                sent.get(mConfiguration.sendTimeout().toNanos(), TimeUnit.NANOSECONDS);
                mMaximumSendNanos.accumulateAndGet(System.nanoTime() - started, Math::max);
            }
            finally
            {
                mSendLock.unlock();
            }
        }

        private void revoke()
        {
            if(mRevoked.compareAndSet(false, true))
            {
                mRevokedSessionCount.incrementAndGet();
            }

            terminate(ACCESS_REVOKED_CLOSE_CODE, "diagnostic access changed");
        }

        private void terminate(int statusCode, String reason)
        {
            mClosing.set(true);
            Session session = mSession;

            if(session != null && session.isOpen())
            {
                session.close(statusCode, reason, org.eclipse.jetty.websocket.api.Callback.NOOP);
            }

            // Release processing-chain observers and the node-wide workspace immediately.  Jetty's close callback can
            // arrive later and cleanup is deliberately idempotent.
            cleanup();
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

            mControls.clear();
            Future<?> pumpTask = mPumpTask;
            mPumpTask = null;

            if(pumpTask != null)
            {
                pumpTask.cancel(true);
            }

            Future<?> monitorTask = mAuthorizationMonitorTask;
            mAuthorizationMonitorTask = null;

            if(monitorTask != null)
            {
                monitorTask.cancel(true);
            }

            try
            {
                releaseDiagnosticResources();
            }
            finally
            {
                release(this);
            }
        }

        /**
         * Closes any source attachment before releasing the shared lease.  The lifecycle lock also covers tryOpen,
         * so cleanup can never expose the lease while an in-flight control is still attaching a source.
         */
        private void releaseDiagnosticResources()
        {
            synchronized(mLifecycleLock)
            {
                SelectedChannelDiagnosticService.Session diagnosticSession = mDiagnosticSession.getAndSet(null);

                try
                {
                    if(diagnosticSession != null)
                    {
                        diagnosticSession.close();
                    }
                }
                finally
                {
                    mWorkspaceLease.close();
                }
            }
        }
    }

    private static Map<String,Object> readyState()
    {
        return Map.of(
            "type", "channel-diagnostics-state",
            "state", "ready",
            "exclusive", true,
            "views", java.util.List.of("signal", "symbols"),
            "signalFps", SelectedChannelSpectrumSource.FRAMES_PER_SECOND,
            "symbolBatchSize", SelectedChannelSymbolSource.BATCH_SIZE,
            "maxVisibleSymbols", SelectedChannelSymbolSource.MAXIMUM_VISIBLE_SYMBOLS
        );
    }

    private static Map<String,Object> stateMap(State state)
    {
        Map<String,Object> values = new LinkedHashMap<>();
        values.put("type", "channel-diagnostics-state");
        values.put("state", state.state().id());
        values.put("requestId", state.requestId());
        values.put("generation", state.generation());
        values.put("view", state.view().id());
        values.put("reason", state.reason());
        ContextDetails context = state.context();

        if(context != null)
        {
            values.put("tableTitle", context.tableTitle());
            values.put("channelName", context.channelName());
            values.put("scope", context.scope());
            values.put("frequencyHz", context.frequencyHz());

            if(context.timeslot() != null)
            {
                values.put("timeslot", context.timeslot());
            }

            values.put("decoder", context.decoder());
            values.put("protocol", context.protocol());
            values.put("sampleRateHz", context.sampleRateHz());
            values.put("channelBandwidthHz", context.channelBandwidthHz());
            values.put("signalSupported", context.signalSupported());
            values.put("symbolsSupported", context.symbolsSupported());
        }

        return values;
    }

    private enum ControlAction
    {
        SUBSCRIBE,
        UPDATE
    }

    private record ControlMessage(ControlAction action, long requestId, String selectionId, View view)
    {
        private static ControlMessage parse(String message)
        {
            if(message == null || message.isBlank() || message.length() > MAXIMUM_CONTROL_CHARACTERS)
            {
                throw new IllegalArgumentException("Diagnostic control is blank or too large");
            }

            try
            {
                JsonNode root = OBJECT_MAPPER.readTree(message);

                if(root == null || !root.isObject())
                {
                    throw new IllegalArgumentException("Diagnostic control must be a JSON object");
                }

                root.fieldNames().forEachRemaining(field ->
                {
                    if(!Set.of("action", "requestId", "selectionId", "view").contains(field))
                    {
                        throw new IllegalArgumentException("Unknown diagnostic control field");
                    }
                });
                JsonNode actionNode = root.get("action");

                if(actionNode == null || !actionNode.isTextual())
                {
                    throw new IllegalArgumentException("Diagnostic control requires an action");
                }

                ControlAction action = switch(actionNode.textValue())
                {
                    case "subscribe" -> ControlAction.SUBSCRIBE;
                    case "update" -> ControlAction.UPDATE;
                    default -> throw new IllegalArgumentException("Unknown diagnostic control action");
                };
                long requestId = safeInteger(root.get("requestId"), "requestId");
                JsonNode viewNode = root.get("view");

                if(viewNode == null || !viewNode.isTextual())
                {
                    throw new IllegalArgumentException("Diagnostic control requires a view");
                }

                View view = View.fromId(viewNode.textValue());
                JsonNode selectionNode = root.get("selectionId");
                String selectionId = null;

                if(selectionNode != null)
                {
                    if(!selectionNode.isTextual())
                    {
                        throw new IllegalArgumentException("selectionId must be text");
                    }

                    selectionId = selectionNode.textValue().strip();

                    if(selectionId.isEmpty() || selectionId.length() > 96)
                    {
                        throw new IllegalArgumentException("selectionId is invalid");
                    }
                }

                if(action == ControlAction.SUBSCRIBE && selectionId == null)
                {
                    throw new IllegalArgumentException("Diagnostic subscribe requires selectionId");
                }

                if(action == ControlAction.UPDATE && selectionId != null)
                {
                    throw new IllegalArgumentException("Diagnostic update cannot change selectionId");
                }

                return new ControlMessage(action, requestId, selectionId, view);
            }
            catch(IllegalArgumentException exception)
            {
                throw exception;
            }
            catch(Exception exception)
            {
                throw new IllegalArgumentException("Unable to parse diagnostic control", exception);
            }
        }

        private static long safeInteger(JsonNode node, String label)
        {
            if(node == null || !node.isIntegralNumber() || !node.canConvertToLong())
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

    public record Configuration(Duration pollTimeout, Duration sendTimeout, Duration shutdownTimeout,
                                int maximumQueuedControls, String threadNamePrefix)
    {
        public Configuration
        {
            requirePositive(pollTimeout, "Diagnostic poll timeout");
            requirePositive(sendTimeout, "Diagnostic send timeout");
            requirePositive(shutdownTimeout, "Diagnostic shutdown timeout");

            if(maximumQueuedControls < 1 || maximumQueuedControls > 64)
            {
                throw new IllegalArgumentException("Diagnostic control queue size must be between 1 and 64");
            }

            if(threadNamePrefix == null || threadNamePrefix.isBlank())
            {
                throw new IllegalArgumentException("Diagnostic thread name prefix cannot be blank");
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
            return new Configuration(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofSeconds(5), 8,
                "sdrtrunk selected-channel diagnostic-");
        }
    }
}
