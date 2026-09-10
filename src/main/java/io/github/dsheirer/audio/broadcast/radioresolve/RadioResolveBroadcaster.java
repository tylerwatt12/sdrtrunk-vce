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
package io.github.dsheirer.audio.broadcast.radioresolve;

import com.google.common.net.HttpHeaders;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.broadcast.AbstractAudioBroadcaster;
import io.github.dsheirer.audio.broadcast.AudioRecording;
import io.github.dsheirer.audio.broadcast.BroadcastEvent;
import io.github.dsheirer.audio.broadcast.BroadcastState;
import io.github.dsheirer.audio.convert.InputAudioFormat;
import io.github.dsheirer.audio.convert.MP3Setting;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.metadata.site.SiteMetadataListener;
import io.github.dsheirer.metadata.site.SiteReceiverContext;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** RadioResolve v3 completed-call and canonical metadata publisher. */
public class RadioResolveBroadcaster extends AbstractAudioBroadcaster<RadioResolveConfiguration>
    implements SiteMetadataListener
{
    private static final Logger mLog = LoggerFactory.getLogger(RadioResolveBroadcaster.class);
    public static final String UPLOAD_PATH = "/api/node/v3/upload-calls";
    public static final String METADATA_PATH = "/api/node/v3/metadata";
    public static final String TEST_PATH = "/api/node/test";
    public static final String AGENT_VERSION = "sdrtrunk-vce";
    /** Process-wide JVM override for the number of calls included in one upload request. */
    public static final String CALLS_PER_UPLOAD_PROPERTY = "sdrtrunk.radioresolve.callsPerUpload";
    static final long METADATA_HOLD_MILLISECONDS = TimeUnit.MINUTES.toMillis(2);
    /** Mirrors RadioResolve's default Live admission window for upload priority; the server remains authoritative. */
    static final long LIVE_UPLOAD_PRIORITY_WINDOW_MILLISECONDS = TimeUnit.MINUTES.toMillis(3);
    static final int MAXIMUM_CALLS_PER_UPLOAD = 32;
    static final long MAXIMUM_CALL_AUDIO_BYTES = 25L * 1024L * 1024L;
    static final long MAXIMUM_UPLOAD_BYTES = 32L * 1024L * 1024L;
    private static final String MULTIPART_FORM_DATA = "multipart/form-data";
    private static final Duration CALL_UPLOAD_TIMEOUT = Duration.ofSeconds(30);
    private static final long METADATA_MINIMUM_SEND_INTERVAL_MILLISECONDS = TimeUnit.SECONDS.toMillis(30);
    private static final long METADATA_MAXIMUM_PAST_OBSERVATION_MILLISECONDS = TimeUnit.HOURS.toMillis(26);
    private static final long METADATA_MAXIMUM_FUTURE_OBSERVATION_MILLISECONDS = TimeUnit.MINUTES.toMillis(5);
    /** A recent proof survives a short control-channel fade, but never crosses a processing incarnation. */
    static final long VERIFIED_SITE_RETENTION_MILLISECONDS = TimeUnit.MINUTES.toMillis(5);
    private static final int MAXIMUM_VERIFIED_SITE_ENTRIES = 512;
    private static final int METADATA_HANDOFF_SLOTS = 128;
    private static final long[] RETRY_BACKOFF_MS = {5_000L, 15_000L, 30_000L, 60_000L, 120_000L};
    private static final AtomicLong CALL_WORKER_SEQUENCE = new AtomicLong();
    private static final AtomicLong METADATA_WORKER_SEQUENCE = new AtomicLong();

    private final RadioResolveSpool mSpool;
    private final RadioResolveClockSynchronizer mClockSynchronizer;
    private final int mCallsPerUpload;
    private final String mEvidenceSessionId = UUID.randomUUID().toString();
    private final Object mConnectionLock = new Object();
    private final Object mVerifiedSiteLock = new Object();
    private final Map<ReceiverGeneration,VerifiedSite> mVerifiedSites = new HashMap<>();
    private final Map<String,MetadataState> mMetadataStates = new HashMap<>();
    private final AtomicBoolean mUploadInFlight = new AtomicBoolean();
    /** Fixed latest-value slots keyed by receiver configuration; collisions coalesce instead of blocking decode. */
    private final AtomicReferenceArray<SiteMetadataEvent> mPendingSiteMetadata =
        new AtomicReferenceArray<>(METADATA_HANDOFF_SLOTS);
    private final AtomicLong mCoalescedSiteMetadataCount = new AtomicLong();
    private final Object mSpoolOpenLock = new Object();
    private final ScheduledExecutorService mCallWorker;
    private final Thread mMetadataWorker;
    private volatile boolean mMetadataWorkerShutdown;
    private int mNextMetadataHandoffSlot;
    private volatile boolean mSpoolOpen;
    private ScheduledFuture<?> mProcessorFuture;
    private final HttpClient mHttpClient;
    private volatile boolean mRunning;
    private volatile boolean mServerReachable;
    private long mLastConnectionAttemptNanos;
    private long mConnectionAttemptInterval = 5_000L;

    /** Compatibility constructor used by focused tests and older factory callers. */
    public RadioResolveBroadcaster(RadioResolveConfiguration configuration, InputAudioFormat inputAudioFormat,
                                   MP3Setting mp3Setting, AliasModel aliasModel)
    {
        this(configuration, inputAudioFormat, mp3Setting, aliasModel,
            Path.of(System.getProperty("java.io.tmpdir"), "sdrtrunk-radioresolve-v3",
                configuration != null ? configuration.getConfigurationId() : UUID.randomUUID().toString()));
    }

    /** Constructs a publisher with an explicit durable spool directory. */
    public RadioResolveBroadcaster(RadioResolveConfiguration configuration, InputAudioFormat inputAudioFormat,
                                   MP3Setting mp3Setting, AliasModel aliasModel, Path spoolDirectory)
    {
        this(configuration, inputAudioFormat, mp3Setting, aliasModel, spoolDirectory,
            new RadioResolveClockSynchronizer());
    }

    /** Package-visible clock injection keeps the network timing gate deterministic in focused tests. */
    RadioResolveBroadcaster(RadioResolveConfiguration configuration, InputAudioFormat inputAudioFormat,
                            MP3Setting mp3Setting, AliasModel aliasModel, Path spoolDirectory,
                            RadioResolveClockSynchronizer clockSynchronizer)
    {
        super(configuration);
        mHttpClient = createHttpClient(configuration);
        mSpool = new RadioResolveSpool(spoolDirectory);
        mClockSynchronizer = clockSynchronizer != null ? clockSynchronizer : new RadioResolveClockSynchronizer();
        mCallsPerUpload = resolveCallsPerUpload(System.getProperty(CALLS_PER_UPLOAD_PROPERTY));
        mCallWorker = Executors.newSingleThreadScheduledExecutor(new ObserverThreadFactory(
            "radioresolve-v3-calls-" + CALL_WORKER_SEQUENCE.incrementAndGet()));
        mMetadataWorker = new Thread(this::runMetadataWorker,
            "radioresolve-v3-metadata-" + METADATA_WORKER_SEQUENCE.incrementAndGet());
        mMetadataWorker.setDaemon(true);
        mMetadataWorker.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        mMetadataWorker.start();
    }

    @Override
    public void start()
    {
        mRunning = true;

        try
        {
            ensureSpoolOpen();
        }
        catch(IOException exception)
        {
            mRunning = false;
            setBroadcastState(BroadcastState.ERROR);
            mLog.error("Unable to open the durable RadioResolve spool: {}", safeMessage(exception));
            return;
        }

        setBroadcastState(BroadcastState.CONNECTING);

        if(mProcessorFuture == null)
        {
            //Connection probes can block for their bounded network timeout. Keep them off the application-wide
            //scheduler, which also owns tuner and receiver timing work.
            mProcessorFuture = mCallWorker.scheduleWithFixedDelay(new RecordingProcessor(), 0L, 500L,
                TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void stop()
    {
        mRunning = false;
        clearPendingSiteMetadata();

        if(mProcessorFuture != null)
        {
            mProcessorFuture.cancel(false);
            mProcessorFuture = null;
        }

        setBroadcastState(BroadcastState.DISCONNECTED);
    }

    /** The spool owns queued files across stops, provider recreation, and application restarts. */
    @Override
    public void dispose()
    {
        mRunning = false;

        if(mProcessorFuture != null)
        {
            mProcessorFuture.cancel(false);
            mProcessorFuture = null;
        }

        mCallWorker.shutdownNow();
        mMetadataWorkerShutdown = true;
        clearPendingSiteMetadata();
        mMetadataWorker.interrupt();
        LockSupport.unpark(mMetadataWorker);
    }

    @Override
    public int getAudioQueueSize()
    {
        try
        {
            ensureSpoolOpen();
            return mSpool.size();
        }
        catch(IOException exception)
        {
            return 0;
        }
    }

    @Override
    public void receive(AudioRecording recording)
    {
        if(recording == null)
        {
            return;
        }

        try
        {
            if(!getBroadcastConfiguration().isCallUploadEnabled())
            {
                return;
            }

            String submissionId = UUID.randomUUID().toString();
            RadioResolveCallEnvelope.BuildResult build = RadioResolveCallEnvelope.create(recording, submissionId);

            if(!build.accepted())
            {
                incrementAgedOffAudioCount();
                broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_AGED_OFF_COUNT_CHANGE));
                mLog.info("RadioResolve v3 dropped a completed call: {}", build.rejectionReason());
                return;
            }

            ensureSpoolOpen();
            RadioResolveCallEnvelope.HoldContext holdContext =
                build.holdContext().withEvidenceSession(mEvidenceSessionId);
            RadioResolveCallEnvelope envelope = applyVerifiedPlacements(build.envelope(), holdContext);
            RadioResolveSpool.EnqueueResult enqueueResult = mSpool.enqueue(recording.getPath(), envelope,
                holdContext, System.currentTimeMillis());

            if(enqueueResult.evictedEntries() > 0)
            {
                for(int index = 0; index < enqueueResult.evictedEntries(); index++)
                {
                    incrementAgedOffAudioCount();
                }

                broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_AGED_OFF_COUNT_CHANGE));
                mLog.warn("RadioResolve v3 evicted {} oldest queued call(s) to stay within the 2 GiB spool limit",
                    enqueueResult.evictedEntries());
            }

            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_QUEUE_CHANGE));
        }
        catch(RadioResolveSpool.ExpiredCallException exception)
        {
            incrementAgedOffAudioCount();
            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_AGED_OFF_COUNT_CHANGE));
            mLog.info("RadioResolve v3 dropped a completed call outside the 24-hour spool window");
        }
        catch(Exception exception)
        {
            incrementErrorAudioCount();
            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_ERROR_COUNT_CHANGE));
            mLog.error("Unable to place a completed call in the durable RadioResolve spool: {}",
                safeMessage(exception));
        }
        finally
        {
            recording.removePendingReplay();
        }
    }

    @Override
    public void receiveSiteMetadata(SiteMetadataEvent event)
    {
        if(event == null || !mRunning || mMetadataWorkerShutdown)
        {
            return;
        }

        int slot = metadataHandoffSlot(event);

        if(mPendingSiteMetadata.getAndSet(slot, event) != null)
        {
            mCoalescedSiteMetadataCount.incrementAndGet();
        }

        LockSupport.unpark(mMetadataWorker);
    }

    /**
     * Performs all snapshot validation, projection, hashing, registry locking, and network work away from the
     * decoder callback. Package visibility permits a focused saturation test to substitute a blocked consumer.
     */
    void processSiteMetadata(SiteMetadataEvent event)
    {
        if(event == null || !event.isUseful() || !event.matchesCurrentChannel() || event.receiverContext() == null)
        {
            return;
        }

        if(!hasVerifiedSiteProof(event))
        {
            return;
        }

        rememberVerifiedSite(event);

        if(!getBroadcastConfiguration().isSiteMetadataEnabled())
        {
            return;
        }

        RadioResolveMetadataReadiness readiness = RadioResolveMetadataReadiness.evaluate(event.snapshot());

        if(!readiness.ready())
        {
            return;
        }

        if(!connected())
        {
            return;
        }

        RadioResolveClockSynchronizer.ClockProof clockProof = mClockSynchronizer.currentProof();

        if(clockProof == null)
        {
            return;
        }

        String identityKey = metadataIdentityKey(event.snapshot());
        String hash = hash(event.snapshot());
        long now = System.currentTimeMillis();

        synchronized(mMetadataStates)
        {
            MetadataState state = mMetadataStates.computeIfAbsent(identityKey, ignored -> new MetadataState());
            boolean changed = !hash.equals(state.lastSuccessfulHash);
            boolean heartbeatDue = now - state.lastSuccessfulAtMs >= METADATA_MINIMUM_SEND_INTERVAL_MILLISECONDS;

            if((!changed && !heartbeatDue) ||
                now - state.lastAttemptAtMs < METADATA_MINIMUM_SEND_INTERVAL_MILLISECONDS)
            {
                return;
            }

            state.lastAttemptAtMs = now;
        }

        long observedAt = event.observedAtEpochMilliseconds();

        if(observedAt <= 0L)
        {
            return;
        }

        sendSiteMetadata(event, identityKey, hash, observedAt, clockProof);
    }

    void rememberVerifiedSite(SiteMetadataEvent event)
    {
        if(!hasVerifiedSiteProof(event))
        {
            return;
        }

        P25SiteIdentity identity = P25SiteIdentity.from(event.snapshot());

        String configurationId = event.receiverContext().configurationId();
        long incarnation = event.receiverContext().processingIncarnation();
        long tuningGeneration = event.receiverContext().siteEvidenceTuningGeneration();

        if(configurationId == null || incarnation <= 0L || tuningGeneration <= 0L)
        {
            return;
        }

        Integer nac = event.snapshot().currentSite() != null ? event.snapshot().currentSite().nac() : null;
        long observedAt = event.observedAtEpochMilliseconds();

        if(observedAt <= 0L)
        {
            return;
        }

        synchronized(mVerifiedSiteLock)
        {
            pruneVerifiedSites(System.currentTimeMillis());
            mVerifiedSites.put(new ReceiverGeneration(configurationId, incarnation, tuningGeneration),
                new VerifiedSite(new RadioResolveCallEnvelope.Placement("p25", identity.wacn(), identity.system(),
                    identity.rfss(), identity.site(), nac), observedAt, event.channel(), event.receiverContext()));

            while(mVerifiedSites.size() > MAXIMUM_VERIFIED_SITE_ENTRIES)
            {
                Iterator<ReceiverGeneration> iterator = mVerifiedSites.keySet().iterator();
                if(iterator.hasNext())
                {
                    iterator.next();
                    iterator.remove();
                }
            }
        }
    }

    RadioResolveCallEnvelope applyVerifiedPlacements(RadioResolveCallEnvelope envelope,
                                                      RadioResolveCallEnvelope.HoldContext context)
    {
        if(envelope == null || context == null)
        {
            return envelope;
        }

        RadioResolveCallEnvelope.Placement selected = verifiedPlacement(context.selected());
        List<RadioResolveCallEnvelope.IndexedPlacement> physicalLegs = new ArrayList<>();

        for(RadioResolveCallEnvelope.PlacementLookup lookup : context.physicalLegs())
        {
            RadioResolveCallEnvelope.Placement placement = verifiedPlacement(lookup);

            if(placement != null && lookup.physicalLegIndex() >= 0)
            {
                physicalLegs.add(new RadioResolveCallEnvelope.IndexedPlacement(lookup.physicalLegIndex(),
                    placement));
            }
        }

        if(selected == null && physicalLegs.isEmpty())
        {
            return envelope;
        }

        return envelope.withVerifiedPlacements(selected, physicalLegs);
    }

    private RadioResolveCallEnvelope.Placement verifiedPlacement(RadioResolveCallEnvelope.PlacementLookup lookup)
    {
        if(lookup == null || !lookup.isUsable() || !mEvidenceSessionId.equals(lookup.evidenceSessionId()))
        {
            return null;
        }

        synchronized(mVerifiedSiteLock)
        {
            pruneVerifiedSites(System.currentTimeMillis());
            ReceiverGeneration generation = new ReceiverGeneration(lookup.channelConfigurationId(),
                lookup.processingIncarnation(), lookup.tuningGeneration());
            VerifiedSite site = mVerifiedSites.get(generation);

            if(site != null && !site.matchesCurrentChannel())
            {
                mVerifiedSites.remove(generation);
                return null;
            }

            return site != null ? site.placement() : null;
        }
    }

    String evidenceSessionId()
    {
        return mEvidenceSessionId;
    }

    long coalescedSiteMetadataCount()
    {
        return mCoalescedSiteMetadataCount.get();
    }

    private void runMetadataWorker()
    {
        while(!mMetadataWorkerShutdown)
        {
            SiteMetadataEvent event = pollPendingSiteMetadata();

            if(event == null)
            {
                LockSupport.parkNanos(this, TimeUnit.SECONDS.toNanos(1));
            }
            else if(mRunning)
            {
                try
                {
                    processSiteMetadata(event);
                }
                catch(Throwable throwable)
                {
                    if(throwable instanceof Error error)
                    {
                        throw error;
                    }

                    mLog.warn("RadioResolve v3 metadata worker discarded an invalid snapshot: {}",
                        safeMessage(throwable));
                }
            }
        }
    }

    private SiteMetadataEvent pollPendingSiteMetadata()
    {
        for(int attempt = 0; attempt < METADATA_HANDOFF_SLOTS; attempt++)
        {
            int slot = mNextMetadataHandoffSlot++ & (METADATA_HANDOFF_SLOTS - 1);
            SiteMetadataEvent event = mPendingSiteMetadata.getAndSet(slot, null);

            if(event != null)
            {
                return event;
            }
        }

        return null;
    }

    private void clearPendingSiteMetadata()
    {
        for(int slot = 0; slot < METADATA_HANDOFF_SLOTS; slot++)
        {
            mPendingSiteMetadata.set(slot, null);
        }
    }

    private static int metadataHandoffSlot(SiteMetadataEvent event)
    {
        if(event != null && event.receiverContext() != null &&
            event.receiverContext().configurationId() != null)
        {
            return event.receiverContext().configurationId().hashCode() & (METADATA_HANDOFF_SLOTS - 1);
        }

        return 0;
    }

    private void processQueue()
    {
        try
        {
            ensureSpoolOpen();
            int expired = mSpool.pruneIfDue(System.currentTimeMillis());

            if(expired > 0)
            {
                for(int index = 0; index < expired; index++)
                {
                    incrementAgedOffAudioCount();
                }
                broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_AGED_OFF_COUNT_CHANGE));
                broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_QUEUE_CHANGE));
            }

            //This runs even with an empty queue, keeping a current server-clock proof ready for the next completed
            //call and for metadata-only configurations.
            boolean connectionAndClockReady = connected();

            if(mUploadInFlight.get())
            {
                return;
            }

            List<RadioResolveSpool.Entry> entries = nextUploadBatch(System.currentTimeMillis());

            if(entries.isEmpty())
            {
                return;
            }

            if(!connectionAndClockReady)
            {
                return;
            }

            List<RadioResolveSpool.Entry> preparedEntries = prepareUploadEntries(entries);

            if(preparedEntries.isEmpty())
            {
                return;
            }

            PreparedUpload preparedUpload = createBoundedUploadRequest(preparedEntries);

            if(preparedUpload == null || !mUploadInFlight.compareAndSet(false, true))
            {
                return;
            }

            List<RadioResolveSpool.Entry> uploadEntries = preparedUpload.entries();

            if(!mSpool.protectAll(uploadEntries))
            {
                mUploadInFlight.set(false);
                return;
            }

            try
            {
                mHttpClient.sendAsync(preparedUpload.request(), HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, throwable) -> handleUploadResponse(uploadEntries, response, throwable));
            }
            catch(Exception exception)
            {
                handleUploadResponse(uploadEntries, null, exception);
            }
        }
        catch(Exception exception)
        {
            incrementErrorAudioCount();
            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_ERROR_COUNT_CHANGE));
            mLog.warn("RadioResolve spool processing failed: {}", safeMessage(exception));
        }
    }

    /** Freezes the same current clock proof into each byte-stable manifest before the batch is claimed. */
    List<RadioResolveSpool.Entry> prepareUploadEntries(List<RadioResolveSpool.Entry> entries) throws IOException
    {
        RadioResolveClockSynchronizer.ClockProof clockProof = mClockSynchronizer.currentProof();

        if(clockProof == null || entries == null || entries.isEmpty())
        {
            return List.of();
        }

        List<RadioResolveSpool.Entry> prepared = new ArrayList<>(entries.size());

        for(RadioResolveSpool.Entry entry : entries)
        {
            RadioResolveSpool.Entry adjusted = mSpool.applyServerClockOffset(entry,
                clockProof.offsetMilliseconds());

            if(adjusted == null)
            {
                return List.of();
            }

            prepared.add(adjusted);
        }

        return List.copyOf(prepared);
    }

    /** Builds the largest oldest-first prefix whose exact multipart Content-Length fits the server contract. */
    PreparedUpload createBoundedUploadRequest(List<RadioResolveSpool.Entry> entries) throws IOException
    {
        List<RadioResolveSpool.Entry> prefix = new ArrayList<>(entries);

        while(!prefix.isEmpty())
        {
            try
            {
                List<RadioResolveSpool.Entry> immutablePrefix = List.copyOf(prefix);
                return new PreparedUpload(immutablePrefix,
                    createUploadRequest(getBroadcastConfiguration(), immutablePrefix));
            }
            catch(CallAudioTooLargeException exception)
            {
                RadioResolveSpool.Entry oversized = exception.entry();
                prefix.removeIf(entry -> entry.manifest().envelope().submissionId().equals(
                    oversized.manifest().envelope().submissionId()));
                discardOversizedCall(oversized, "its audio exceeds 25 MiB");
            }
            catch(UploadBatchTooLargeException exception)
            {
                if(prefix.size() > 1)
                {
                    prefix.removeLast();
                    continue;
                }

                RadioResolveSpool.Entry oversized = prefix.getFirst();
                discardOversizedCall(oversized, "its multipart upload exceeds 32 MiB");
                return null;
            }
        }

        return null;
    }

    private void discardOversizedCall(RadioResolveSpool.Entry entry, String reason) throws IOException
    {
        mSpool.remove(entry);
        incrementErrorAudioCount();
        broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_ERROR_COUNT_CHANGE));
        broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_QUEUE_CHANGE));
        mLog.error("RadioResolve v3 discarded call {} because {}",
            entry.manifest().envelope().submissionId(), reason);
    }

    /**
     * Prioritizes the oldest call that can still enter Live, then resumes the durable backlog. Within the fresh lane,
     * a held or retry-delayed call remains an ordering barrier so later speech cannot play first.
     */
    List<RadioResolveSpool.Entry> nextUploadBatch(long now) throws IOException
    {
        return nextUploadBatch(now, mCallsPerUpload, MAXIMUM_UPLOAD_BYTES);
    }

    List<RadioResolveSpool.Entry> nextUploadBatch(long now, int maximumEntries, long maximumBytes)
        throws IOException
    {
        for(RadioResolveSpool.Entry candidate : mSpool.heldEntries())
        {
            RadioResolveCallEnvelope envelope = candidate.manifest().envelope();

            if(envelope.isReady())
            {
                continue;
            }

            RadioResolveCallEnvelope updated = applyVerifiedPlacements(envelope,
                candidate.manifest().holdContext());

            if(updated != envelope && updated.isReady())
            {
                mSpool.updateEnvelope(candidate, updated);
            }
            else if(now >= candidate.manifest().metadataDeadlineMs())
            {
                mSpool.remove(candidate);
                incrementAgedOffAudioCount();
                broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_AGED_OFF_COUNT_CHANGE));
                broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_QUEUE_CHANGE));
                mLog.info("RadioResolve v3 dropped call {} after waiting two minutes for verified site identity",
                    envelope.submissionId());
            }
        }

        return mSpool.uploadBatch(now, LIVE_UPLOAD_PRIORITY_WINDOW_MILLISECONDS, maximumEntries, maximumBytes);
    }

    private void handleUploadResponse(List<RadioResolveSpool.Entry> entries, HttpResponse<String> response,
                                      Throwable throwable)
    {
        try
        {
            if(throwable != null)
            {
                retry(entries, "temporary batch upload failure");
                return;
            }

            int status = response.statusCode();

            if(status >= 200 && status < 300)
            {
                recordConnectionSuccess();
                applyBatchUploadResponse(entries, status, response.body());
            }
            else if(status == 401 || status == 403)
            {
                setBroadcastState(BroadcastState.INVALID_CREDENTIALS);
                incrementErrorAudioCount();
                broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_ERROR_COUNT_CHANGE));
                mLog.error("RadioResolve v3 upload rejected: invalid API key or access denied");
            }
            else
            {
                //Only a matched per-call result can discard durable data. A whole-request error has an uncertain
                //per-call outcome, including when a proxy replaces the server response.
                setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
                retry(entries, "unexpected batch HTTP " + status);
            }
        }
        catch(RuntimeException exception)
        {
            incrementErrorAudioCount();
            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_ERROR_COUNT_CHANGE));
            mLog.warn("Unable to update the durable RadioResolve spool: {}", safeMessage(exception));
        }
        finally
        {
            try
            {
                int evicted = mSpool.unprotectAll(entries);

                if(evicted > 0)
                {
                    for(int index = 0; index < evicted; index++)
                    {
                        incrementAgedOffAudioCount();
                    }

                    broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_AGED_OFF_COUNT_CHANGE));
                    mLog.warn("RadioResolve v3 evicted {} oldest queued call(s) after an active upload released " +
                        "the spool capacity", evicted);
                }
            }
            catch(IOException exception)
            {
                incrementErrorAudioCount();
                broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_ERROR_COUNT_CHANGE));
                mLog.warn("Unable to release RadioResolve spool upload ownership: {}", safeMessage(exception));
            }

            mUploadInFlight.set(false);
            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_QUEUE_CHANGE));
            enqueueImmediateQueuePass();
        }
    }

    /** Continues draining a backlog without waiting for the fixed-delay maintenance pass. */
    private void enqueueImmediateQueuePass()
    {
        if(!mRunning || mCallWorker.isShutdown())
        {
            return;
        }

        try
        {
            //The same single-thread worker owns periodic and response-triggered passes, preserving serialized queue
            //selection while the in-flight flag continues to enforce one HTTP request at a time.
            mCallWorker.execute(new RecordingProcessor());
        }
        catch(RejectedExecutionException exception)
        {
            if(mRunning)
            {
                mLog.warn("Unable to schedule the next RadioResolve queue pass: {}", safeMessage(exception));
            }
        }
    }

    /** Applies only exact index/submission acknowledgements; every missing or uncertain item remains durable. */
    void applyBatchUploadResponse(List<RadioResolveSpool.Entry> entries, int status, String responseBody)
    {
        List<String> submissionIds = entries.stream()
            .map(entry -> entry.manifest().envelope().submissionId()).toList();
        BatchResponse batchResponse = classifyBatchResponse(submissionIds, status, responseBody);

        if(!batchResponse.valid())
        {
            setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
        }

        int retryCount = 0;

        for(int index = 0; index < entries.size(); index++)
        {
            RadioResolveSpool.Entry entry = entries.get(index);
            BatchUploadDecision decision = batchResponse.decisions().get(index);

            try
            {
                if(decision.disposition() == UploadDisposition.ACCEPTED)
                {
                    mSpool.remove(entry);
                    incrementStreamedAudioCount();
                    broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_STREAMED_COUNT_CHANGE));
                }
                else if(decision.disposition() == UploadDisposition.TERMINAL_REJECTION)
                {
                    mSpool.remove(entry);
                    incrementErrorAudioCount();
                    broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_ERROR_COUNT_CHANGE));

                    if(decision.httpStatus() == 410 || decision.httpStatus() == 422)
                    {
                        incrementAgedOffAudioCount();
                        broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_AGED_OFF_COUNT_CHANGE));
                    }

                    mLog.error("RadioResolve v3 permanently rejected call {} with HTTP {}",
                        decision.submissionId(), decision.httpStatus());
                }
                else
                {
                    retry(entry);
                    retryCount++;
                }
            }
            catch(IOException exception)
            {
                incrementErrorAudioCount();
                broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_ERROR_COUNT_CHANGE));
                mLog.warn("Unable to update RadioResolve spool entry {}: {}", decision.submissionId(),
                    safeMessage(exception));
            }
        }

        if(retryCount > 0)
        {
            mLog.info("RadioResolve v3 retained {} of {} batched call(s) for retry", retryCount, entries.size());
        }
    }

    private void retry(List<RadioResolveSpool.Entry> entries, String reason)
    {
        int retried = 0;

        for(RadioResolveSpool.Entry entry : entries)
        {
            try
            {
                retry(entry);
                retried++;
            }
            catch(IOException exception)
            {
                incrementErrorAudioCount();
                broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_ERROR_COUNT_CHANGE));
                mLog.warn("Unable to retain RadioResolve call {} for retry: {}",
                    entry.manifest().envelope().submissionId(), safeMessage(exception));
            }
        }

        if(retried > 0)
        {
            mLog.info("RadioResolve v3 will retry {} batched call(s) [{}]", retried, reason);
        }
    }

    private void retry(RadioResolveSpool.Entry entry) throws IOException
    {
        int attempts = entry.manifest().attemptCount();
        long delay = RETRY_BACKOFF_MS[Math.min(attempts, RETRY_BACKOFF_MS.length - 1)];
        mSpool.retry(entry, System.currentTimeMillis() + delay);
        incrementErrorAudioCount();
        broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_ERROR_COUNT_CHANGE));
    }

    static BatchResponse classifyBatchResponse(List<String> submissionIds, int status, String responseBody)
    {
        List<BatchUploadDecision> retry = submissionIds.stream()
            .map(submissionId -> new BatchUploadDecision(submissionId, status, UploadDisposition.RETRY)).toList();

        if(status < 200 || status >= 300 || responseBody == null || responseBody.isBlank())
        {
            return new BatchResponse(retry, false);
        }

        try
        {
            JsonElement parsed = JsonParser.parseString(responseBody);

            if(!parsed.isJsonObject())
            {
                return new BatchResponse(retry, false);
            }

            JsonObject root = parsed.getAsJsonObject();
            JsonObject data = root.has("data") && root.get("data").isJsonObject() ?
                root.getAsJsonObject("data") : null;
            JsonArray results = data != null && data.has("results") && data.get("results").isJsonArray() ?
                data.getAsJsonArray("results") : null;

            if(results == null)
            {
                return new BatchResponse(retry, false);
            }

            List<BatchUploadDecision> decisions = new ArrayList<>(submissionIds.size());

            for(int index = 0; index < submissionIds.size(); index++)
            {
                String submissionId = submissionIds.get(index);
                JsonElement resultElement = index < results.size() ? results.get(index) : null;
                JsonObject result = resultElement != null && resultElement.isJsonObject() ?
                    resultElement.getAsJsonObject() : null;
                Integer resultIndex = exactResultInteger(result, "index");
                Integer resultStatus = result != null ? exactResultInteger(result, "http_status") : null;
                String resultSubmissionId = result != null && result.has("submission_id") &&
                    result.get("submission_id").isJsonPrimitive() ?
                    result.get("submission_id").getAsString() : null;

                if(resultIndex == null || resultIndex != index || resultStatus == null ||
                    !submissionId.equalsIgnoreCase(resultSubmissionId))
                {
                    decisions.add(new BatchUploadDecision(submissionId, status, UploadDisposition.RETRY));
                }
                else if(resultStatus >= 200 && resultStatus < 300)
                {
                    decisions.add(new BatchUploadDecision(submissionId, resultStatus,
                        UploadDisposition.ACCEPTED));
                }
                else if(isPermanentCallRejectionStatus(resultStatus))
                {
                    decisions.add(new BatchUploadDecision(submissionId, resultStatus,
                        UploadDisposition.TERMINAL_REJECTION));
                }
                else
                {
                    decisions.add(new BatchUploadDecision(submissionId, resultStatus, UploadDisposition.RETRY));
                }
            }

            return new BatchResponse(List.copyOf(decisions), true);
        }
        catch(RuntimeException exception)
        {
            return new BatchResponse(retry, false);
        }
    }

    private static Integer exactResultInteger(JsonObject result, String name)
    {
        if(result == null || !result.has(name) || !result.get(name).isJsonPrimitive() ||
            !result.getAsJsonPrimitive(name).isNumber())
        {
            return null;
        }

        String value = result.get(name).getAsString();

        if(!value.matches("0|[1-9][0-9]*"))
        {
            return null;
        }

        try
        {
            return Integer.valueOf(value);
        }
        catch(NumberFormatException exception)
        {
            return null;
        }
    }

    static boolean isPermanentCallRejectionStatus(int status)
    {
        return status == 400 || status == 409 || status == 410 || status == 413 || status == 415 ||
            status == 422;
    }

    enum UploadDisposition
    {
        ACCEPTED,
        TERMINAL_REJECTION,
        RETRY
    }

    record BatchUploadDecision(String submissionId, int httpStatus, UploadDisposition disposition)
    {
    }

    record BatchResponse(List<BatchUploadDecision> decisions, boolean valid)
    {
    }

    record PreparedUpload(List<RadioResolveSpool.Entry> entries, HttpRequest request)
    {
    }

    private static class UploadBatchTooLargeException extends IOException
    {
    }

    private static class CallAudioTooLargeException extends IOException
    {
        private final RadioResolveSpool.Entry mEntry;

        private CallAudioTooLargeException(RadioResolveSpool.Entry entry)
        {
            mEntry = entry;
        }

        private RadioResolveSpool.Entry entry()
        {
            return mEntry;
        }
    }

    private void sendSiteMetadata(SiteMetadataEvent event, String identityKey, String hash, long observedAt,
                                  RadioResolveClockSynchronizer.ClockProof clockProof)
    {
        try
        {
            JsonObject payload = createSiteMetadataPayload(event, observedAt, clockProof.offsetMilliseconds());
            HttpRequest request = HttpRequest.newBuilder()
                .uri(createUri(getBroadcastConfiguration().getHost(), METADATA_PATH))
                .timeout(Duration.ofSeconds(10))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + getBroadcastConfiguration().getApiKey())
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header(HttpHeaders.USER_AGENT, AGENT_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
            HttpResponse<Void> response = mHttpClient.send(request, HttpResponse.BodyHandlers.discarding());

            if(response.statusCode() >= 200 && response.statusCode() < 300)
            {
                synchronized(mMetadataStates)
                {
                    MetadataState state = mMetadataStates.computeIfAbsent(identityKey,
                        ignored -> new MetadataState());
                    state.lastSuccessfulHash = hash;
                    state.lastSuccessfulAtMs = System.currentTimeMillis();
                }
                recordConnectionSuccess();
            }
            else if(response.statusCode() == 401 || response.statusCode() == 403)
            {
                setBroadcastState(BroadcastState.INVALID_CREDENTIALS);
                mLog.warn("RadioResolve v3 metadata rejected: invalid API key or access denied");
            }
            else
            {
                setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
                mLog.warn("RadioResolve v3 metadata rejected: HTTP {}", response.statusCode());
            }
        }
        catch(Exception exception)
        {
            setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
            mLog.warn("RadioResolve v3 metadata upload failed: {}", safeMessage(exception));
        }
    }

    static HttpRequest createUploadRequest(RadioResolveConfiguration configuration,
                                           List<RadioResolveSpool.Entry> entries) throws IOException
    {
        if(entries == null || entries.isEmpty() || entries.size() > MAXIMUM_CALLS_PER_UPLOAD)
        {
            throw new IOException("RadioResolve upload batch must contain between one and " +
                MAXIMUM_CALLS_PER_UPLOAD + " calls");
        }

        JsonObject batch = new JsonObject();
        batch.addProperty("schema_version", 3);
        JsonArray items = new JsonArray();
        RadioResolveBuilder body = new RadioResolveBuilder();

        for(int index = 0; index < entries.size(); index++)
        {
            RadioResolveSpool.Entry entry = entries.get(index);
            Path audioPath = entry != null ? entry.audioPath() : null;

            if(audioPath == null || !Files.isRegularFile(audioPath))
            {
                throw new FileNotFoundException(String.valueOf(audioPath));
            }

            if(Files.size(audioPath) > MAXIMUM_CALL_AUDIO_BYTES)
            {
                throw new CallAudioTooLargeException(entry);
            }

            String audioPart = "audio-" + index;
            JsonObject item = new JsonObject();
            item.addProperty("audio_part", audioPart);
            item.add("call", RadioResolveJson.GSON.toJsonTree(entry.manifest().envelope()));
            items.add(item);
            String filename = audioPath.getFileName() != null ? audioPath.getFileName().toString() : "call.mp3";
            body.addFile(audioPart, audioPath, filename);
        }

        batch.add("items", items);
        body.addJsonPart("batch", batch.toString());
        HttpRequest.BodyPublisher publisher = body.build();

        if(publisher.contentLength() < 0L || publisher.contentLength() > MAXIMUM_UPLOAD_BYTES)
        {
            throw new UploadBatchTooLargeException();
        }

        return HttpRequest.newBuilder()
            .uri(createUri(configuration.getHost(), UPLOAD_PATH))
            .version(HttpClient.Version.HTTP_1_1)
            .timeout(CALL_UPLOAD_TIMEOUT)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.getApiKey())
            .header(HttpHeaders.CONTENT_TYPE, MULTIPART_FORM_DATA + "; boundary=" + body.getBoundary())
            .header(HttpHeaders.USER_AGENT, AGENT_VERSION)
            .POST(publisher)
            .build();
    }

    static JsonObject createSiteMetadataPayload(SiteMetadataEvent event, long observedAt)
    {
        return createSiteMetadataPayload(event, observedAt, 0L);
    }

    static JsonObject createSiteMetadataPayload(SiteMetadataEvent event, long observedAt,
                                                long serverClockOffsetMilliseconds)
    {
        P25NetworkConfigurationSnapshot snapshot = event.snapshot();
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", 3);
        root.addProperty("agent_version", AGENT_VERSION);
        root.addProperty("observed_at_ms", adjustedObservationTime(observedAt, serverClockOffsetMilliseconds));
        root.addProperty("observed_control_frequency_hz", event.sourceFrequency());
        JsonObject modes = new JsonObject();

        if(snapshot.network() != null)
        {
            P25NetworkConfigurationSnapshot.Network network = snapshot.network();
            root.add("system", RadioResolveJson.GSON.toJsonTree(new MetadataSystem("p25", network.wacn(),
                network.system(), network.nac(), network.lra())));
            modes.addProperty("system", "merge");
        }

        if(snapshot.currentSite() != null)
        {
            P25NetworkConfigurationSnapshot.CurrentSite site = snapshot.currentSite();
            root.add("site", RadioResolveJson.GSON.toJsonTree(new MetadataSite(site.system(), site.nac(),
                site.rfss(), site.site(), site.lra(), site.activeRfssNetworkConnection())));
            modes.addProperty("site", "merge");
        }

        addMergeObservation(root, modes, "channels", snapshot.channels().stream()
            .map(channel -> metadataChannel(channel, observedAt, serverClockOffsetMilliseconds))
            .filter(java.util.Objects::nonNull).toList());
        Integer servingSystem = snapshot.network() != null ? snapshot.network().system() : null;
        addMergeObservation(root, modes, "neighbors", snapshot.neighborSites().stream()
            .map(neighbor -> metadataNeighbor(neighbor, servingSystem, observedAt,
                serverClockOffsetMilliseconds))
            .filter(java.util.Objects::nonNull).toList());
        addMergeObservation(root, modes, "bandplans", snapshot.frequencyBands().stream()
            .map(band -> metadataBandplan(band, observedAt, serverClockOffsetMilliseconds))
            .filter(java.util.Objects::nonNull).toList());
        addMergeObservation(root, modes, "foreign_bandplans", snapshot.foreignSystemBands().stream()
            .map(band -> metadataForeignBandplan(band, observedAt, serverClockOffsetMilliseconds))
            .filter(java.util.Objects::nonNull).toList());

        if(isTrustedMergeObservation(snapshot.activePatchesObservedAtMs(), observedAt))
        {
            root.add("active_patches", RadioResolveJson.GSON.toJsonTree(snapshot.patchGroups()));
            root.addProperty("active_patches_observed_at_ms", adjustedObservationTime(
                snapshot.activePatchesObservedAtMs(), serverClockOffsetMilliseconds));
            modes.addProperty("active_patches", "complete_site_scope");
        }

        addMergeObservation(root, modes, "talker_aliases", snapshot.talkerAliases().stream()
            .map(alias -> metadataTalkerAlias(alias, observedAt, serverClockOffsetMilliseconds))
            .filter(java.util.Objects::nonNull).toList());

        if(snapshot.siteStatus() != null)
        {
            root.add("site_status", RadioResolveJson.GSON.toJsonTree(snapshot.siteStatus().withoutVolatileTiming()));
            modes.addProperty("site_status", "merge");
        }

        root.add("observation_modes", modes);

        return root;
    }

    private static void addMergeObservation(JsonObject root, JsonObject modes, String name, List<?> observations)
    {
        if(observations != null && !observations.isEmpty())
        {
            root.add(name, RadioResolveJson.GSON.toJsonTree(observations));
            modes.addProperty(name, "merge");
        }
    }

    private static boolean isTrustedMergeObservation(Long itemObservedAt, long rootObservedAt)
    {
        return itemObservedAt != null && itemObservedAt > 0L &&
            itemObservedAt >= rootObservedAt - METADATA_MAXIMUM_PAST_OBSERVATION_MILLISECONDS &&
            itemObservedAt <= rootObservedAt + METADATA_MAXIMUM_FUTURE_OBSERVATION_MILLISECONDS;
    }

    private static MetadataChannel metadataChannel(P25NetworkConfigurationSnapshot.Channel channel,
                                                    long rootObservedAt, long serverClockOffsetMilliseconds)
    {
        if(channel == null || !isTrustedMergeObservation(channel.observedAtMs(), rootObservedAt) ||
            channel.role() == null || !channel.role().matches("[a-z][a-z0-9_]{0,23}") ||
            channel.descriptor() == null || channel.descriptor().isBlank() || channel.descriptor().length() > 64 ||
            channel.downlink() == null || channel.downlink() <= 0L)
        {
            return null;
        }

        return new MetadataChannel(channel.role(), channel.descriptor(), channel.downlink(),
            positive(channel.uplink()), channel.tdma(), validTimeslots(channel.timeslots()),
            boundedText(channel.callsign(), 32), adjustedObservationTime(channel.observedAtMs(),
                serverClockOffsetMilliseconds));
    }

    private static MetadataNeighbor metadataNeighbor(P25NetworkConfigurationSnapshot.NeighborSite neighbor,
                                                      Integer servingSystem, long rootObservedAt,
                                                      long serverClockOffsetMilliseconds)
    {
        Integer system = neighbor != null && neighbor.system() != null ? neighbor.system() : servingSystem;

        if(neighbor == null || !isTrustedMergeObservation(neighbor.observedAtMs(), rootObservedAt) ||
            !inRange(system, 0, 0xFFF) || !inRange(neighbor.rfss(), 0, 0xFF) ||
            !inRange(neighbor.site(), 0, 0xFF))
        {
            return null;
        }

        return new MetadataNeighbor(system, neighbor.nac(), neighbor.rfss(), neighbor.site(), neighbor.lra(),
            boundedText(neighbor.channel(), 64), positive(neighbor.downlink()), positive(neighbor.uplink()),
            boundedText(neighbor.status(), 64), adjustedObservationTime(neighbor.observedAtMs(),
                serverClockOffsetMilliseconds));
    }

    private static MetadataBandplan metadataBandplan(P25NetworkConfigurationSnapshot.FrequencyBand band,
                                                      long rootObservedAt,
                                                      long serverClockOffsetMilliseconds)
    {
        if(band == null || !isTrustedMergeObservation(band.observedAtMs(), rootObservedAt) ||
            !inRange(band.band(), 0, 0xFFFF) || band.base() == null || band.base() <= 0L ||
            band.spacing() == null || band.spacing() <= 0L || !inRange(band.timeslots(), 1, 8))
        {
            return null;
        }

        return new MetadataBandplan(band.band(), band.tdma(), band.base(), positive(band.bandwidth()), band.spacing(),
            band.transmitOffset(), band.timeslots(), adjustedObservationTime(band.observedAtMs(),
                serverClockOffsetMilliseconds));
    }

    private static MetadataForeignBandplan metadataForeignBandplan(
        P25NetworkConfigurationSnapshot.ForeignSystemBand band, long rootObservedAt,
        long serverClockOffsetMilliseconds)
    {
        if(band == null || !isTrustedMergeObservation(band.observedAtMs(), rootObservedAt) ||
            !inRange(band.wacn(), 0, 0xFFFFF) || !inRange(band.system(), 0, 0xFFF) ||
            !inRange(band.band(), 0, 0xFFFF) || band.base() == null || band.base() <= 0L ||
            band.spacing() == null || band.spacing() <= 0L)
        {
            return null;
        }

        Integer channelType = inRange(band.channelType(), 0, 0xFF) ? band.channelType() : null;
        return new MetadataForeignBandplan(band.wacn(), band.system(), band.band(), channelType, band.base(),
            band.spacing(), band.transmitOffset(), adjustedObservationTime(band.observedAtMs(),
                serverClockOffsetMilliseconds));
    }

    private static MetadataTalkerAlias metadataTalkerAlias(P25NetworkConfigurationSnapshot.TalkerAlias alias,
                                                            long rootObservedAt,
                                                            long serverClockOffsetMilliseconds)
    {
        String value = alias != null ? boundedText(alias.alias(), 255) : null;

        if(alias == null || !isTrustedMergeObservation(alias.observedAtMs(), rootObservedAt) ||
            !inRange(alias.radio(), 1, 0xFFFFFF) || value == null)
        {
            return null;
        }

        return new MetadataTalkerAlias(alias.radio(), value, adjustedObservationTime(alias.observedAtMs(),
            serverClockOffsetMilliseconds));
    }

    private static long adjustedObservationTime(long observedAt, long serverClockOffsetMilliseconds)
    {
        long adjusted = Math.addExact(observedAt, serverClockOffsetMilliseconds);

        if(adjusted <= 0L)
        {
            throw new IllegalArgumentException("RadioResolve metadata clock normalization produced an invalid time");
        }

        return adjusted;
    }

    private static Long positive(Long value)
    {
        return value != null && value > 0L ? value : null;
    }

    private static Integer positive(Integer value)
    {
        return value != null && value > 0 ? value : null;
    }

    private static Integer validTimeslots(Integer value)
    {
        return inRange(value, 1, 8) ? value : null;
    }

    private static boolean inRange(Integer value, int minimum, int maximum)
    {
        return value != null && value >= minimum && value <= maximum;
    }

    private static String boundedText(String value, int maximumLength)
    {
        if(value == null || value.isBlank())
        {
            return null;
        }

        String normalized = value.trim();
        if(normalized.length() <= maximumLength)
        {
            return normalized;
        }

        int end = maximumLength;
        if(end > 0 && Character.isHighSurrogate(normalized.charAt(end - 1)))
        {
            end--;
        }
        return normalized.substring(0, end);
    }

    boolean connected()
    {
        if(getBroadcastState() == BroadcastState.INVALID_CREDENTIALS)
        {
            return false;
        }

        synchronized(mConnectionLock)
        {
            long now = System.nanoTime();
            boolean attemptDue = mLastConnectionAttemptNanos == 0L || now < mLastConnectionAttemptNanos ||
                now - mLastConnectionAttemptNanos >= TimeUnit.MILLISECONDS.toNanos(mConnectionAttemptInterval);

            if((!mServerReachable || mClockSynchronizer.needsRefresh()) && attemptDue)
            {
                refreshConnectionAndClockLocked();
            }

            return mServerReachable && mClockSynchronizer.currentProof() != null;
        }
    }

    /** A successful authenticated v3 request restores reachability; clock proof remains independently bounded. */
    void recordConnectionSuccess()
    {
        mServerReachable = true;
        setBroadcastState(BroadcastState.CONNECTED);
    }

    /**
     * Requires an internally consistent native site identity observed on the exact advertised control frequency.
     * This is evaluated only by the asynchronous metadata worker, never by the decoder callback.
     */
    static boolean hasVerifiedSiteProof(SiteMetadataEvent event)
    {
        return event != null && event.receiverContext() != null && P25SiteIdentity.from(event.snapshot()) != null &&
            event.isSourceAdvertisedControlChannel();
    }

    private void ensureSpoolOpen() throws IOException
    {
        if(!mSpoolOpen)
        {
            synchronized(mSpoolOpenLock)
            {
                if(!mSpoolOpen)
                {
                    RadioResolveSpool.RecoveryReport recovery = mSpool.open();
                    mSpoolOpen = true;

                    if(recovery.hasCleanup())
                    {
                        for(int index = 0; index < recovery.discardedCallEntries(); index++)
                        {
                            incrementAgedOffAudioCount();
                        }

                        if(recovery.discardedCallEntries() > 0)
                        {
                            broadcast(new BroadcastEvent(this,
                                BroadcastEvent.Event.BROADCASTER_AGED_OFF_COUNT_CHANGE));
                        }

                        mLog.warn("RadioResolve v3 spool recovery removed {} corrupt entries, {} orphan audio " +
                                "files, {} incomplete temporary files, {} expired entries, and {} entries over " +
                                "capacity", recovery.corruptEntries(), recovery.orphanFiles(),
                            recovery.temporaryFiles(), recovery.expiredEntries(), recovery.capacityEvictions());
                    }
                }
            }
        }
    }

    private void pruneVerifiedSites(long now)
    {
        mVerifiedSites.entrySet().removeIf(entry ->
            now - entry.getValue().observedAtMs() > VERIFIED_SITE_RETENTION_MILLISECONDS);
    }

    private static String metadataIdentityKey(P25NetworkConfigurationSnapshot snapshot)
    {
        P25SiteIdentity identity = P25SiteIdentity.from(snapshot);
        return identity != null ? identity.wacn() + ":" + identity.system() + ":" + identity.rfss() + ":" +
            identity.site() : "unknown";
    }

    private static String hash(P25NetworkConfigurationSnapshot snapshot)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            P25NetworkConfigurationSnapshot stable = new P25NetworkConfigurationSnapshot(snapshot.decoder(),
                snapshot.network(), snapshot.currentSite(), snapshot.channels(), snapshot.neighborSites(),
                snapshot.frequencyBands(), snapshot.patchGroups(), snapshot.talkerAliases(),
                snapshot.siteStatus() != null ? snapshot.siteStatus().withoutVolatileTiming() : null,
                snapshot.foreignSystemBands(), snapshot.activePatchesObservedAtMs());
            byte[] value = digest.digest(RadioResolveJson.GSON.toJson(stable).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(value.length * 2);
            for(byte item : value)
            {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        }
        catch(Exception exception)
        {
            throw new IllegalStateException("Could not hash RadioResolve v3 metadata", exception);
        }
    }

    private static URI createUri(String host, String path)
    {
        String normalized = host != null ? host.trim() : "";
        while(normalized.endsWith("/"))
        {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return URI.create(normalized + path);
    }

    public static HttpClient createHttpClient(RadioResolveConfiguration configuration)
    {
        HttpClient.Builder builder = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20));

        if(configuration != null && configuration.isIgnoreCertificateErrors())
        {
            try
            {
                builder.sslContext(createTrustAllSSLContext());
                SSLParameters parameters = new SSLParameters();
                parameters.setEndpointIdentificationAlgorithm("");
                builder.sslParameters(parameters);
            }
            catch(Exception exception)
            {
                mLog.error("Unable to configure RadioResolve certificate error bypass [{}]", safeMessage(exception));
            }
        }

        return builder.build();
    }

    private static SSLContext createTrustAllSSLContext() throws Exception
    {
        TrustManager[] trustManagers = new TrustManager[]{new X509TrustManager()
        {
            @Override public java.security.cert.X509Certificate[] getAcceptedIssuers()
            {
                return new java.security.cert.X509Certificate[0];
            }

            @Override public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType)
            {
            }

            @Override public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType)
            {
            }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers, new java.security.SecureRandom());
        return context;
    }

    private void refreshConnectionAndClockLocked()
    {
        setBroadcastState(BroadcastState.CONNECTING);
        mServerReachable = testConnection(getBroadcastConfiguration());
        mLastConnectionAttemptNanos = System.nanoTime();

        if(getBroadcastState() != BroadcastState.INVALID_CREDENTIALS)
        {
            setBroadcastState(mServerReachable ? BroadcastState.CONNECTED : BroadcastState.ERROR);
        }
    }

    private boolean testConnection(RadioResolveConfiguration configuration)
    {
        if(configuration == null || configuration.getApiKey() == null || configuration.getApiKey().isBlank())
        {
            return false;
        }

        try
        {
            HttpRequest request = HttpRequest.newBuilder().uri(createUri(configuration.getHost(), TEST_PATH))
                .timeout(Duration.ofSeconds(10))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.getApiKey())
                .header(HttpHeaders.USER_AGENT, AGENT_VERSION).GET().build();
            RadioResolveClockSynchronizer.RequestTiming timing = mClockSynchronizer.beginRequest();
            HttpResponse<String> response = mHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if(response.statusCode() == 401 || response.statusCode() == 403)
            {
                mClockSynchronizer.clear();
                setBroadcastState(BroadcastState.INVALID_CREDENTIALS);
                return false;
            }

            Long serverEpochMilliseconds = response.statusCode() >= 200 && response.statusCode() < 300 ?
                serverEpochMilliseconds(response.body()) : null;
            return serverEpochMilliseconds != null &&
                mClockSynchronizer.completeRequest(timing, serverEpochMilliseconds);
        }
        catch(Exception exception)
        {
            Throwable cause = exception instanceof CompletionException && exception.getCause() != null ?
                exception.getCause() : exception;
            mLog.warn("RadioResolve connection failed: {}", safeMessage(cause));
            return false;
        }
    }

    static Long serverEpochMilliseconds(String responseBody)
    {
        try
        {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            if(!root.has("serverEpochMilliseconds") || root.get("serverEpochMilliseconds").isJsonNull())
            {
                return null;
            }

            long value = root.get("serverEpochMilliseconds").getAsLong();
            return value > 0L ? value : null;
        }
        catch(RuntimeException exception)
        {
            return null;
        }
    }

    /** Parses the process-wide upload-count override, defaulting safely and clamping to the server contract. */
    static int resolveCallsPerUpload(String configuredValue)
    {
        if(configuredValue == null || configuredValue.isBlank())
        {
            return MAXIMUM_CALLS_PER_UPLOAD;
        }

        try
        {
            int parsed = Integer.parseInt(configuredValue.trim());
            return Math.max(1, Math.min(MAXIMUM_CALLS_PER_UPLOAD, parsed));
        }
        catch(NumberFormatException exception)
        {
            return MAXIMUM_CALLS_PER_UPLOAD;
        }
    }

    private static String safeMessage(Throwable throwable)
    {
        if(throwable == null)
        {
            return "unknown error";
        }

        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private class RecordingProcessor implements Runnable
    {
        @Override
        public void run()
        {
            if(mRunning)
            {
                processQueue();
            }
        }
    }

    private record ReceiverGeneration(String configurationId, long processingIncarnation, long tuningGeneration)
    {
    }

    private record VerifiedSite(RadioResolveCallEnvelope.Placement placement, long observedAtMs, Channel channel,
                                SiteReceiverContext receiverContext)
    {
        private boolean matchesCurrentChannel()
        {
            //Production observations always carry the live channel. A null channel is retained only for detached
            //focused tests that call rememberVerifiedSite directly instead of entering through processSiteMetadata.
            return channel == null || receiverContext != null &&
                receiverContext.matchesCurrentSiteEvidenceGeneration(channel);
        }
    }

    private record MetadataSystem(String protocol, Integer wacn, Integer systemId, Integer nac, Integer lra)
    {
    }

    private record MetadataSite(Integer systemId, Integer nac, Integer rfssId, Integer siteId, Integer lra,
                                Boolean activeRfssNetworkConnection)
    {
    }

    private record MetadataChannel(String role, String channelDescriptor, Long downlinkFrequencyHz,
                                   Long uplinkFrequencyHz, Boolean tdma, Integer timeslots, String callsign,
                                   Long observedAtMs)
    {
    }

    private record MetadataNeighbor(Integer systemId, Integer nac, Integer rfssId, Integer siteId, Integer lra,
                                    String channelDescriptor, Long downlinkFrequencyHz, Long uplinkFrequencyHz,
                                    String status, Long observedAtMs)
    {
    }

    private record MetadataBandplan(Integer bandId, Boolean tdma, Long baseFrequencyHz, Integer bandwidthHz,
                                    Long channelSpacingHz, Long transmitOffsetHz, Integer timeslots,
                                    Long observedAtMs)
    {
    }

    private record MetadataForeignBandplan(Integer wacn, Integer systemId, Integer bandId, Integer channelType,
                                           Long baseFrequencyHz, Long channelSpacingHz, Long transmitOffsetHz,
                                           Long observedAtMs)
    {
    }

    private record MetadataTalkerAlias(Integer localRadioId, String talkerAlias, Long observedAtMs)
    {
    }

    private static class MetadataState
    {
        private String lastSuccessfulHash;
        private long lastSuccessfulAtMs;
        private long lastAttemptAtMs;
    }
}
