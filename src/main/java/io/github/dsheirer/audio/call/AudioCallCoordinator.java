/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.audio.call;

import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.audio.broadcast.AudioStreamingManager;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDecisionOutcome;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticCallIdentity;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticCohort;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticCounters;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticDecision;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticEvidence;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticLeg;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticOutputPolicy;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticSink;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticSnapshot;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticWinner;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallMergeProof;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallPairOutcome;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallSeparationReason;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallWinnerCriterion;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.record.AudioRecordingManager;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nonblocking receiver boundary and single-threaded owner of physical call-leg assembly and logical-call
 * resolution.
 *
 * <p>Receiver and vocoder threads perform one bounded queue offer and return.  All identifier interpretation,
 * content comparison, quality election, policy merging, statistics handoff, and output fanout runs on the observer
 * worker.  Queue pressure drops observer data; it never applies backpressure to decoding.</p>
 */
public class AudioCallCoordinator implements Listener<AudioCallEvent>
{
    private static final Logger mLog = LoggerFactory.getLogger(AudioCallCoordinator.class);
    static final int DEFAULT_INGRESS_CAPACITY = 4_096;
    static final int DEFAULT_LIFECYCLE_INGRESS_RESERVE = 256;
    static final long DEFAULT_SETTLE_QUIET_MILLISECONDS = 500L;
    static final long DEFAULT_ACTIVE_LEG_WAIT_CEILING_MILLISECONDS = 10_000L;
    static final int DEFAULT_MAXIMUM_ACTIVE_LEGS = 128;
    static final int DEFAULT_MAXIMUM_COHORT_LEGS = 32;
    static final long DEFAULT_MAXIMUM_AUDIO_SAMPLES_PER_LEG = 4_800_000L;
    static final long DEFAULT_MAXIMUM_RETAINED_AUDIO_SAMPLES = 32_000_000L;
    private static final int MAXIMUM_FINGERPRINTS_PER_LEG = 6_000;
    private static final long MAXIMUM_SHARED_FRAME_SITE_DELTA_MILLISECONDS = 500L;
    private static final int SHARED_FRAME_DELTA_CONSISTENCY_MILLISECONDS = 100;
    private static final int SHARED_FRAME_DELTA_RADIUS_MILLISECONDS =
        SHARED_FRAME_DELTA_CONSISTENCY_MILLISECONDS / 2;
    private static final int SHARED_FRAME_DELTA_BUCKETS =
        (int)(MAXIMUM_SHARED_FRAME_SITE_DELTA_MILLISECONDS * 2L + 1L);
    private static final long MINIMUM_SOURCE_FALLBACK_OVERLAP_MILLISECONDS = 500L;
    private static final int SOURCE_FALLBACK_SHORTER_COVERAGE_PERCENT = 80;
    private static final int EVENT = 1;
    private static final int ABORT_LEG = 2;
    private static final int CHECK_COHORT = 3;
    private static final long SHUTDOWN_DRAIN_MILLISECONDS = 2_000L;
    private static final long COHORT_RECHECK_MILLISECONDS = 250L;
    private static final long DIAGNOSTIC_REFRESH_MILLISECONDS = 1_000L;

    private final ResolverConfiguration mConfiguration;
    private final AudioCallIngressQueue mIngress;
    private final ScheduledThreadPoolExecutor mDeadlineScheduler;
    private final Thread mWorker;
    private final Consumer<CompletedAudioCall> mResolvedCallConsumer;
    private final Consumer<CompletedAudioCall> mRecordingConsumer;
    private final Consumer<CompletedAudioCall> mStreamingConsumer;
    private final Consumer<CompletedAudioCall> mWebConsumer;
    private final LogicalCallDiagnosticSink mDiagnosticSink;
    private final Map<CallLegId, ReceiverLeg> mActiveLegs = new HashMap<>();
    private final Map<Long, ResolutionCohort> mCohorts = new HashMap<>();
    private final List<CompletedAudioCall> mPendingFanouts = new ArrayList<>();
    private final LinkedHashSet<CallLegId> mPublishedAbortLegIds = new LinkedHashSet<>();
    private final int[] mSharedFrameDeltaCounts = new int[SHARED_FRAME_DELTA_BUCKETS];
    private final int[] mSharedFrameDeltaMarks = new int[SHARED_FRAME_DELTA_BUCKETS];
    private final AtomicBoolean mCohortSweepRequested = new AtomicBoolean();
    private final AtomicInteger mActiveReceives = new AtomicInteger();
    private final AtomicLong mAcceptedIngressCount = new AtomicLong();
    private final AtomicLong mDroppedIngressCount = new AtomicLong();
    private final AtomicLong mDroppedLifecycleCount = new AtomicLong();
    private final AtomicLong mDroppedOperationCount = new AtomicLong();
    private final AtomicLong mAbortedCallCount = new AtomicLong();
    private final AtomicBoolean mForcedDiscard = new AtomicBoolean();
    private final long mCoordinatorId = java.util.concurrent.ThreadLocalRandom.current().nextLong();
    private final long mDiagnosticStartedAtMs = System.currentTimeMillis();
    private final String mDiagnosticSessionId = Long.toUnsignedString(mCoordinatorId, 36);
    private long mNextLogicalCallSequence = 1L;
    private long mNextCohortId = 1L;
    private long mNextDiagnosticDecisionSequence = 1L;
    private long mRetainedAudioSamples;
    private long mCompletedReceiverLegCount;
    private long mEligibleReceiverLegCount;
    private long mEmittedLogicalCallCount;
    private long mMergedLogicalCallCount;
    private long mMergedReceiverCopyCount;
    private long mIndependentLogicalCallCount;
    private long mFailOpenLogicalCallCount;
    private long mSeparatedPairComparisonCount;
    /** Worker-only counters used by boundedness regression tests. */
    private long mAwaitedLegFullRefreshCount;
    private long mAwaitedLegScopeComparisonCount;
    private long mDiagnosticDecisionOfferedCount;
    private long mDiagnosticDecisionRejectedCount;
    private long mDiagnosticSnapshotRevision;
    private long mLastDiagnosticSnapshotNanos;
    private boolean mDiagnosticSnapshotDirty = true;
    private boolean mDiagnosticSnapshotFailureLogged;
    private int mSharedFrameDeltaEpoch;
    private volatile LogicalCallDiagnosticSnapshot mDiagnosticSnapshot = new LogicalCallDiagnosticSnapshot(
        mDiagnosticSessionId, mDiagnosticStartedAtMs, mDiagnosticStartedAtMs, 0L, true, false,
        new LogicalCallDiagnosticCounters(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
            0L, 0L, 0L), 0, 0, 0L, List.of(), List.of());
    private volatile boolean mAccepting = true;
    private volatile boolean mDisposed;

    /**
     * Production constructor.  The resolved-call consumer is notified once before any output handoff and normally
     * points at the activity statistics service.
     */
    public AudioCallCoordinator(AudioRecordingManager audioRecordingManager,
                                AudioStreamingManager audioStreamingManager,
                                Consumer<CompletedAudioCall> webConsumer,
                                Consumer<CompletedAudioCall> resolvedCallConsumer)
    {
        this(audioRecordingManager, audioStreamingManager, webConsumer, resolvedCallConsumer, null);
    }

    /**
     * Production constructor with an optional fixed-attempt diagnostic sink. The sink is offered decisions only by
     * the resolver observer thread.
     */
    public AudioCallCoordinator(AudioRecordingManager audioRecordingManager,
                                AudioStreamingManager audioStreamingManager,
                                Consumer<CompletedAudioCall> webConsumer,
                                Consumer<CompletedAudioCall> resolvedCallConsumer,
                                LogicalCallDiagnosticSink diagnosticSink)
    {
        this(resolvedCallConsumer,
            audioRecordingManager != null ? audioRecordingManager::receive : null,
            audioStreamingManager != null ? audioStreamingManager::receive : null,
            webConsumer, ResolverConfiguration.DEFAULT, diagnosticSink);
    }

    AudioCallCoordinator(Consumer<CompletedAudioCall> resolvedCallConsumer,
                         Consumer<CompletedAudioCall> recordingConsumer,
                         Consumer<CompletedAudioCall> streamingConsumer,
                         Consumer<CompletedAudioCall> webConsumer,
                         ResolverConfiguration configuration)
    {
        this(resolvedCallConsumer, recordingConsumer, streamingConsumer, webConsumer, configuration, null);
    }

    AudioCallCoordinator(Consumer<CompletedAudioCall> resolvedCallConsumer,
                         Consumer<CompletedAudioCall> recordingConsumer,
                         Consumer<CompletedAudioCall> streamingConsumer,
                         Consumer<CompletedAudioCall> webConsumer,
                         ResolverConfiguration configuration,
                         LogicalCallDiagnosticSink diagnosticSink)
    {
        mResolvedCallConsumer = resolvedCallConsumer;
        mRecordingConsumer = recordingConsumer;
        mStreamingConsumer = streamingConsumer;
        mWebConsumer = webConsumer;
        mDiagnosticSink = diagnosticSink;
        mConfiguration = configuration != null ? configuration : ResolverConfiguration.DEFAULT;
        mIngress = new AudioCallIngressQueue(mConfiguration.ingressCapacity(),
            mConfiguration.lifecycleIngressReserve());
        mDeadlineScheduler = new ScheduledThreadPoolExecutor(1,
            new ObserverThreadFactory("logical-call deadline"));
        mDeadlineScheduler.setRemoveOnCancelPolicy(true);
        mDeadlineScheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        mWorker = new ObserverThreadFactory("logical-call resolver").newThread(this::runWorker);
        mWorker.start();
    }

    /**
     * Fixed-attempt, nonblocking receiver-side handoff.
     */
    @Override
    public void receive(AudioCallEvent event)
    {
        if(event == null || event.callId() == null || !mAccepting)
        {
            return;
        }

        mActiveReceives.incrementAndGet();

        try
        {
            if(!mAccepting)
            {
                return;
            }

            CallLegId callLegId = event.snapshot().callLegId();

            if(callLegId.isIngressCompromised())
            {
                recordRejectedIngress(isLifecycle(event));
                signalWorker();
                return;
            }

            boolean lifecycle = isLifecycle(event);

            if(mIngress.offer(EVENT, lifecycle, event, 0L))
            {
                mAcceptedIngressCount.incrementAndGet();
                signalWorker();
            }
            else
            {
                recordRejectedIngress(lifecycle);
                compromiseLeg(callLegId);
            }
        }
        finally
        {
            mActiveReceives.decrementAndGet();

            if(!mAccepting)
            {
                signalWorker();
            }
        }
    }

    private static boolean isLifecycle(AudioCallEvent event)
    {
        return event.eventType() == AudioCallEventType.CALL_CREATED ||
            event.eventType() == AudioCallEventType.CALL_COMPLETED;
    }

    private void recordRejectedIngress(boolean lifecycle)
    {
        mDroppedIngressCount.incrementAndGet();
        mDroppedOperationCount.incrementAndGet();

        if(lifecycle)
        {
            mDroppedLifecycleCount.incrementAndGet();
        }
    }

    /**
     * Invalidates one exact physical leg.  The producer touches only the preallocated latch carried by the stable
     * CallLegId, then makes one fixed-attempt offer against the lifecycle reserve.  The latch remains authoritative
     * if that offer is full or contended.
     */
    private void compromiseLeg(CallLegId callLegId)
    {
        if(callLegId != null && callLegId.markIngressCompromised())
        {
            mAbortedCallCount.incrementAndGet();

            if(mIngress.offer(ABORT_LEG, true, callLegId, 0L))
            {
                mAcceptedIngressCount.incrementAndGet();
            }
            else
            {
                mDroppedLifecycleCount.incrementAndGet();
                mDroppedOperationCount.incrementAndGet();
            }
        }

        signalWorker();
    }

    private void signalWorker()
    {
        LockSupport.unpark(mWorker);
    }

    private void runWorker()
    {
        try
        {
            publishDiagnosticSnapshot(true);

            while(!mDisposed && (mAccepting || mActiveReceives.get() > 0 || mIngress.size() > 0 ||
                mCohortSweepRequested.get()))
            {
                sweepCompromisedLegs();
                sweepDueCohorts();

                int drained = 0;
                AudioCallIngressQueue.Entry entry;

                while(!mDisposed && drained < mIngress.capacity() && (entry = mIngress.poll()) != null)
                {
                    try
                    {
                        sweepCompromisedLegs();
                        process(entry);
                        drainPendingFanouts();
                        publishDiagnosticSnapshot(false);
                    }
                    catch(Throwable throwable)
                    {
                        rethrowFatal(throwable);
                        mLog.error("Error processing logical-call command", throwable);
                    }

                    drained++;

                }

                publishDiagnosticSnapshot(false);

                if(drained == 0 && !mCohortSweepRequested.get())
                {
                    LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(50L));
                }
            }
        }
        finally
        {
            if(!mForcedDiscard.get())
            {
                flushCompletedCohorts();
                drainPendingFanouts();
            }

            discardWorkerState();
            mIngress.clear();
            mDisposed = true;
            markDiagnosticSnapshotDirty();
            publishDiagnosticSnapshot(true);
        }
    }

    private void process(AudioCallIngressQueue.Entry entry)
    {
        switch(entry.operation())
        {
            case EVENT -> processEvent((AudioCallEvent)entry.payload());
            case ABORT_LEG -> abortLeg((CallLegId)entry.payload());
            case CHECK_COHORT -> checkCohort(entry.value());
            default -> mLog.warn("Ignoring unknown logical-call operation [{}]", entry.operation());
        }
    }

    private void processEvent(AudioCallEvent event)
    {
        AudioCallSnapshot snapshot = event.snapshot();
        AudioCallId callId = snapshot.callId();
        CallLegId callLegId = snapshot.callLegId();

        if(callLegId.isIngressCompromised())
        {
            abortLeg(callLegId);
            return;
        }

        ReceiverLeg leg = mActiveLegs.get(callLegId);

        if(leg == null)
        {
            if(mActiveLegs.size() >= mConfiguration.maximumActiveLegs())
            {
                if(callLegId.markIngressCompromised())
                {
                    mAbortedCallCount.incrementAndGet();
                }

                publishAbortDecision(callLegId, diagnosticLeg(snapshot, callLegId.toString()),
                    LogicalCallSeparationReason.ACTIVE_LEG_CAPACITY);
                abortLeg(callLegId);
                return;
            }

            leg = new ReceiverLeg(callLegId, snapshot);
            mActiveLegs.put(callLegId, leg);
            markDiagnosticSnapshotDirty();
        }

        long acceptedSamples = leg.accept(event, false,
            Math.min(Math.max(0L, mConfiguration.maximumAudioSamplesPerLeg() - leg.audioSampleCount),
                Math.max(0L, mConfiguration.maximumRetainedAudioSamples() - mRetainedAudioSamples)));
        mRetainedAudioSamples += acceptedSamples;

        if(event.eventType() != AudioCallEventType.CALL_COMPLETED)
        {
            refreshAwaitedLegIfNeeded(leg);
            return;
        }

        if(event.continuationExpected())
        {
            refreshAwaitedLegIfNeeded(leg);
            markDiagnosticSnapshotDirty();
            return;
        }

        mActiveLegs.remove(callLegId);
        removeAwaitedLeg(callLegId);
        CompletedReceiverLeg completedLeg = leg.complete();
        markDiagnosticSnapshotDirty();
        resolve(completedLeg);
    }

    /**
     * Adds a completed physical leg to one explicit pairwise-compatible cohort, or releases it independently when
     * any hard identity/proof requirement is absent.  This is deliberately fail-open: uncertainty creates an extra
     * output rather than discarding a potentially distinct transmission.
     */
    private void resolve(CompletedReceiverLeg completedLeg)
    {
        mCompletedReceiverLegCount++;
        List<LogicalCallSeparationReason> eligibilityReasons = resolutionEligibilityReasons(completedLeg);

        if(!eligibilityReasons.isEmpty())
        {
            LogicalCallDecisionOutcome outcome = hasFailOpenReason(eligibilityReasons) ?
                LogicalCallDecisionOutcome.FAIL_OPEN : LogicalCallDecisionOutcome.INDEPENDENT;
            releaseIndependent(completedLeg, outcome, eligibilityReasons, LogicalCallDiagnosticEvidence.EMPTY,
                System.nanoTime());
            return;
        }

        mEligibleReceiverLegCount++;
        long now = System.nanoTime();
        ResolutionCohort cohort = null;
        EvidenceAccumulator rejectedEvidence = new EvidenceAccumulator();
        List<ResolutionCohort> candidates = new ArrayList<>(mCohorts.values());
        candidates.sort(Comparator.comparingLong(candidate -> candidate.cohortId));

        for(ResolutionCohort candidate : candidates)
        {
            if(candidate.legs.size() >= mConfiguration.maximumCohortLegs())
            {
                if(!candidate.legs.isEmpty())
                {
                    DuplicateEvaluation evaluation =
                        DuplicateEvaluation.failOpen(LogicalCallSeparationReason.COHORT_CAPACITY);
                    rejectedEvidence.add(evaluation);
                    candidate.recordRejectedEvaluation(evaluation,
                        candidate.legs.getFirst().snapshot.callLegId());
                }

                continue;
            }

            boolean compatible = true;
            List<MergeEvidenceContribution> candidateMergeEvidence = new ArrayList<>();

            for(CompletedReceiverLeg member : candidate.legs)
            {
                DuplicateEvaluation evaluation = evaluateDuplicate(member, completedLeg);

                if(evaluation.outcome != LogicalCallPairOutcome.MERGED)
                {
                    rejectedEvidence.add(evaluation);
                    candidate.recordRejectedEvaluation(evaluation, member.snapshot.callLegId());

                    if(evaluation.outcome == LogicalCallPairOutcome.SEPARATED)
                    {
                        mSeparatedPairComparisonCount++;
                    }

                    compatible = false;
                    break;
                }

                candidateMergeEvidence.add(new MergeEvidenceContribution(member.snapshot.callLegId(),
                    completedLeg.snapshot.callLegId(), evaluation.mergeProofs));
            }

            if(compatible)
            {
                cohort = candidate;
                cohort.recordMergeEvidence(candidateMergeEvidence);
                cohort.legs.add(completedLeg);
                break;
            }
        }

        if(cohort == null)
        {
            if(mCohorts.size() >= mConfiguration.maximumActiveLegs())
            {
                DuplicateEvaluation capacity =
                    DuplicateEvaluation.failOpen(LogicalCallSeparationReason.COHORT_CAPACITY);
                rejectedEvidence.add(capacity);
                releaseIndependent(completedLeg, LogicalCallDecisionOutcome.FAIL_OPEN,
                    rejectedEvidence.reasons(), rejectedEvidence.snapshot(), now);
                return;
            }

            cohort = new ResolutionCohort(mNextCohortId++, completedLeg, now,
                safeAdd(now, TimeUnit.MILLISECONDS.toNanos(mConfiguration.activeLegWaitCeilingMilliseconds())));
            cohort.recordInitialRejectedEvidence(rejectedEvidence,
                completedLeg.snapshot.callLegId());
            mCohorts.put(cohort.cohortId, cohort);
        }

        cohort.settleDeadlineNanos = safeAdd(now,
            TimeUnit.MILLISECONDS.toNanos(mConfiguration.settleQuietMilliseconds()));
        refreshAwaitedLegs(cohort);
        scheduleCohort(cohort, nextCohortDelayNanos(cohort, now));
        markDiagnosticSnapshotDirty();
    }

    private List<LogicalCallSeparationReason> resolutionEligibilityReasons(CompletedReceiverLeg leg)
    {
        if(leg == null || leg.snapshot == null || leg.snapshot.callLegSource() == null)
        {
            return List.of(LogicalCallSeparationReason.MISSING_CALL_SOURCE);
        }

        CallLegSource source = leg.snapshot.callLegSource();
        DecoderType decoderType = source.decoderType();

        if(decoderType != null && decoderType != DecoderType.P25_PHASE1 && decoderType != DecoderType.P25_PHASE2)
        {
            return List.of(LogicalCallSeparationReason.NON_P25_RESOLUTION_NOT_APPLICABLE);
        }

        List<LogicalCallSeparationReason> reasons = new ArrayList<>();

        if(decoderType == null)
        {
            reasons.add(LogicalCallSeparationReason.MISSING_DECODER_TYPE);
        }

        if(source.aliasListId() <= 0L)
        {
            reasons.add(LogicalCallSeparationReason.MISSING_DURABLE_ALIAS_LIST_ID);
        }

        if(source.p25SiteIdentity() == null)
        {
            reasons.add(LogicalCallSeparationReason.MISSING_LEARNED_SITE_IDENTITY);
        }

        if(leg.destinationIdentity == null)
        {
            reasons.add(LogicalCallSeparationReason.MISSING_DESTINATION_IDENTITY);
        }

        if(!leg.snapshot.isEncryptionKnown())
        {
            reasons.add(LogicalCallSeparationReason.MISSING_ENCRYPTION_STATE);
        }

        if(leg.startTimestamp <= 0L || leg.endTimestamp < leg.startTimestamp)
        {
            reasons.add(LogicalCallSeparationReason.INVALID_CALL_TIMING);
        }

        return List.copyOf(reasons);
    }

    private DuplicateEvaluation evaluateDuplicate(CompletedReceiverLeg first, CompletedReceiverLeg second)
    {
        List<LogicalCallSeparationReason> scopeReasons = comparisonScopeReasons(first, second);

        if(!scopeReasons.isEmpty())
        {
            return hasFailOpenReason(scopeReasons) ? DuplicateEvaluation.failOpen(scopeReasons) :
                DuplicateEvaluation.separated(scopeReasons);
        }

        long overlap = intervalOverlapMilliseconds(first, second);

        if(overlap <= 0L)
        {
            return DuplicateEvaluation.separated(LogicalCallSeparationReason.INSUFFICIENT_TIME_OVERLAP);
        }

        FingerprintIndex firstVoice = first.voiceFingerprintIndex();
        FingerprintIndex secondVoice = second.voiceFingerprintIndex();

        if(hasSharedVoiceContent(firstVoice, secondVoice))
        {
            return DuplicateEvaluation.merged(LogicalCallMergeProof.SHARED_VOICE_CONTENT);
        }

        //An encrypted message indicator is stronger than source/timing fallback and is evaluated first.  It can
        //confirm an opaque late-entry copy without depending on when its local call object happened to begin.
        if(first.snapshot.isEncrypted() && first.encryptionEvidence != null &&
            first.encryptionEvidence.hasMatchingMessageIndicator(second.encryptionEvidence))
        {
            return DuplicateEvaluation.merged(LogicalCallMergeProof.MATCHING_ENCRYPTION_MESSAGE_INDICATOR);
        }

        //Known matching source metadata can rescue damaged copies whose hashes no longer agree, but only when their
        //actual carrier-frame timelines strongly overlap.  Call-object timing is a fallback solely when one leg
        //truly lacks enough timestamped frames to establish a timeline.
        boolean matchingKnownSource = first.sourceIdentity != null && second.sourceIdentity != null;

        if(matchingKnownSource && (hasStrongVoiceTimelineOverlap(firstVoice, secondVoice) ||
            (firstVoice.frameCount() < 3 || secondVoice.frameCount() < 3) &&
                hasStrongCallIntervalFallbackOverlap(first, second, overlap)))
        {
            return DuplicateEvaluation.merged(LogicalCallMergeProof.MATCHING_SOURCE_IDENTITY_FALLBACK);
        }

        return DuplicateEvaluation.failOpen(LogicalCallSeparationReason.INSUFFICIENT_DUPLICATE_PROOF);
    }

    /**
     * One shared hard-scope comparison for completed and active legs.  It intentionally excludes start skew and
     * proof strength: active legs only advertise that a scoped copy may still complete, while completed legs must
     * independently prove duplicate content or a strong fallback relationship.
     */
    private List<LogicalCallSeparationReason> comparisonScopeReasons(CompletedReceiverLeg first,
                                                                     CompletedReceiverLeg second)
    {
        List<LogicalCallSeparationReason> reasons = new ArrayList<>();
        reasons.addAll(resolutionEligibilityReasons(first));
        reasons.addAll(resolutionEligibilityReasons(second));

        if(!reasons.isEmpty())
        {
            return List.copyOf(new LinkedHashSet<>(reasons));
        }

        CallLegSource firstSource = first.snapshot.callLegSource();
        CallLegSource secondSource = second.snapshot.callLegSource();
        P25SiteIdentity firstSite = firstSource.p25SiteIdentity();
        P25SiteIdentity secondSite = secondSource.p25SiteIdentity();

        if(firstSource.aliasListId() != secondSource.aliasListId())
        {
            reasons.add(LogicalCallSeparationReason.ALIAS_LIST_MISMATCH);
        }

        if(firstSite.wacn() != secondSite.wacn())
        {
            reasons.add(LogicalCallSeparationReason.WACN_MISMATCH);
        }

        if(firstSite.system() != secondSite.system())
        {
            reasons.add(LogicalCallSeparationReason.SYSTEM_ID_MISMATCH);
        }

        if(!first.destinationIdentity.matches(second.destinationIdentity))
        {
            reasons.add(LogicalCallSeparationReason.DESTINATION_MISMATCH);
        }

        if(first.snapshot.encryptionState() != second.snapshot.encryptionState())
        {
            reasons.add(LogicalCallSeparationReason.ENCRYPTION_STATE_MISMATCH);
        }

        if(first.sourceIdentity != null && second.sourceIdentity != null &&
            !first.sourceIdentity.matches(second.sourceIdentity))
        {
            reasons.add(LogicalCallSeparationReason.SOURCE_IDENTITY_MISMATCH);
        }

        return List.copyOf(reasons);
    }

    private static long intervalOverlapMilliseconds(CompletedReceiverLeg first, CompletedReceiverLeg second)
    {
        return Math.max(0L, Math.min(first.endTimestamp, second.endTimestamp) -
            Math.max(first.startTimestamp, second.startTimestamp));
    }

    private static boolean hasStrongCallIntervalFallbackOverlap(CompletedReceiverLeg first,
                                                                 CompletedReceiverLeg second, long overlap)
    {
        if(overlap < MINIMUM_SOURCE_FALLBACK_OVERLAP_MILLISECONDS)
        {
            return false;
        }

        long shorterDuration = Math.min(first.endTimestamp - first.startTimestamp,
            second.endTimestamp - second.startTimestamp);
        return hasStrongShorterCoverage(overlap, shorterDuration);
    }

    private static boolean hasStrongVoiceTimelineOverlap(FingerprintIndex first, FingerprintIndex second)
    {
        if(first == null || second == null || first.frameCount() < 3 || second.frameCount() < 3)
        {
            return false;
        }

        long overlap = Math.max(0L, Math.min(first.latestTimestamp(), second.latestTimestamp()) -
            Math.max(first.earliestTimestamp(), second.earliestTimestamp()));
        long shorterSpan = Math.min(first.timestampSpan(), second.timestampSpan());
        return hasStrongShorterCoverage(overlap, shorterSpan);
    }

    private static boolean hasStrongShorterCoverage(long overlap, long shorterSpan)
    {
        return overlap >= MINIMUM_SOURCE_FALLBACK_OVERLAP_MILLISECONDS && shorterSpan > 0L &&
            overlap * 100L >= shorterSpan * SOURCE_FALLBACK_SHORTER_COVERAGE_PERCENT;
    }

    /**
     * Audio frames normally reuse the same immutable snapshot.  Only re-evaluate all cohorts when the active leg's
     * cached comparison scope/start changes, or when its end time reaches a boundary that can turn a previously
     * non-overlapping interval into an overlap.  Fingerprint/audio/quality histories are deliberately absent from
     * this path.
     */
    private void refreshAwaitedLegIfNeeded(ReceiverLeg active)
    {
        if(active != null && active.requiresAwaitedLegRefresh())
        {
            refreshAwaitedLeg(active);
        }
    }

    private void refreshAwaitedLeg(ReceiverLeg active)
    {
        mAwaitedLegFullRefreshCount++;
        active.beginAwaitedLegRefresh();

        for(ResolutionCohort cohort : mCohorts.values())
        {
            updateAwaitedLeg(cohort, active);
        }
    }

    private void refreshAwaitedLegs(ResolutionCohort cohort)
    {
        cohort.awaitedLegIds.clear();

        for(ReceiverLeg active : mActiveLegs.values())
        {
            updateAwaitedLeg(cohort, active);
        }
    }

    /**
     * Evaluates one active leg against a completed clique using only cached identity scope and four primitive timing
     * values.  If scope is compatible but the active interval ends too early, retain the exact next end timestamp
     * that can change the result instead of rescanning on every voice frame.
     */
    private void updateAwaitedLeg(ResolutionCohort cohort, ReceiverLeg active)
    {
        boolean compatible = active.hasValidTiming();
        boolean canOverlapByExtendingEnd = compatible;
        long requiredEndTimestamp = Long.MIN_VALUE;

        for(CompletedReceiverLeg member : cohort.legs)
        {
            mAwaitedLegScopeComparisonCount++;

            if(!member.hasValidTiming() || !member.preliminaryScope.compatible(active.preliminaryScope))
            {
                compatible = false;
                canOverlapByExtendingEnd = false;
                break;
            }

            if(active.endTimestamp <= member.startTimestamp || member.endTimestamp <= active.startTimestamp)
            {
                compatible = false;

                if(member.endTimestamp <= active.startTimestamp)
                {
                    //Only an earlier active start (which independently marks scope/timing dirty) can overlap.
                    canOverlapByExtendingEnd = false;
                }
                else
                {
                    requiredEndTimestamp = Math.max(requiredEndTimestamp,
                        safeAdd(member.startTimestamp, 1L));
                }
            }
        }

        if(compatible)
        {
            cohort.awaitedLegIds.add(active.callLegId);
        }
        else
        {
            cohort.awaitedLegIds.remove(active.callLegId);

            if(canOverlapByExtendingEnd && requiredEndTimestamp != Long.MIN_VALUE)
            {
                active.considerAwaitedRefreshAt(requiredEndTimestamp);
            }
        }
    }

    private void removeAwaitedLeg(CallLegId callLegId)
    {
        for(ResolutionCohort cohort : mCohorts.values())
        {
            cohort.awaitedLegIds.remove(callLegId);
        }
    }

    /** Worker-only removal of one compromised leg from every stage of resolution. */
    private void abortLeg(CallLegId callLegId)
    {
        if(callLegId == null)
        {
            return;
        }

        ReceiverLeg active = mActiveLegs.remove(callLegId);
        LogicalCallDiagnosticLeg abortedDiagnosticLeg = null;
        boolean changed = active != null;

        if(active != null)
        {
            CompletedReceiverLeg preview = active.preview();

            if(preview != null)
            {
                abortedDiagnosticLeg = diagnosticLeg(preview, qualityBaselineFrames(preview), false);
            }

            mRetainedAudioSamples = Math.max(0L, mRetainedAudioSamples - active.discard());
        }

        removeAwaitedLeg(callLegId);
        List<Long> emptyCohorts = new ArrayList<>();

        for(ResolutionCohort cohort : mCohorts.values())
        {
            CompletedReceiverLeg removed = null;

            for(int index = cohort.legs.size() - 1; index >= 0; index--)
            {
                CompletedReceiverLeg candidate = cohort.legs.get(index);

                if(callLegId.equals(candidate.snapshot.callLegId()))
                {
                    removed = cohort.legs.remove(index);
                }
            }

            if(removed != null)
            {
                if(abortedDiagnosticLeg == null)
                {
                    abortedDiagnosticLeg = diagnosticLeg(removed, qualityBaselineFrames(removed), false);
                }

                changed = true;
                mRetainedAudioSamples = Math.max(0L, mRetainedAudioSamples - removed.audioSampleCount);
                removed.releaseCoordinatorReferences();
                cohort.removeMergeEvidence(callLegId);
                cohort.removeRejectedEvidence(callLegId);
                refreshAwaitedLegs(cohort);
            }

            if(cohort.legs.isEmpty())
            {
                if(cohort.deadlineFuture != null)
                {
                    cohort.deadlineFuture.cancel(false);
                }

                emptyCohorts.add(cohort.cohortId);
            }
        }

        for(Long cohortId : emptyCohorts)
        {
            mCohorts.remove(cohortId);
        }

        if(changed || callLegId.isIngressCompromised())
        {
            publishAbortDecision(callLegId, abortedDiagnosticLeg,
                LogicalCallSeparationReason.INGRESS_COMPROMISED);
            markDiagnosticSnapshotDirty();
        }
    }

    private void publishAbortDecision(CallLegId callLegId, LogicalCallDiagnosticLeg diagnosticLeg,
                                      LogicalCallSeparationReason reason)
    {
        if(mForcedDiscard.get() || mDiagnosticSink == null || callLegId == null ||
            !mPublishedAbortLegIds.add(callLegId))
        {
            return;
        }

        while(mPublishedAbortLegIds.size() > mIngress.capacity())
        {
            var iterator = mPublishedAbortLegIds.iterator();

            if(iterator.hasNext())
            {
                iterator.next();
                iterator.remove();
            }
        }

        try
        {
            List<LogicalCallDiagnosticLeg> legs = diagnosticLeg != null ? List.of(diagnosticLeg) : List.of();
            LogicalCallDiagnosticDecision decision = new LogicalCallDiagnosticDecision(
                mNextDiagnosticDecisionSequence++, System.currentTimeMillis(), null,
                LogicalCallDecisionOutcome.ABORTED, null, null, null, legs,
                LogicalCallDiagnosticEvidence.EMPTY,
                List.of(reason != null ? reason : LogicalCallSeparationReason.INGRESS_COMPROMISED));
            offerDiagnosticDecision(decision);
        }
        catch(Throwable throwable)
        {
            rethrowFatal(throwable);
            mDiagnosticDecisionRejectedCount++;
            mLog.warn("Unable to project an aborted logical-call diagnostic; resolver state was still preserved",
                throwable);
        }

        markDiagnosticSnapshotDirty();
    }

    /**
     * Latch scan is bounded by configured active/cohort limits and runs only on the observer owner.  It closes the
     * narrow case where the reserved abort offer itself was rejected under contention.
     */
    private void sweepCompromisedLegs()
    {
        List<CallLegId> compromised = new ArrayList<>();

        for(CallLegId callLegId : mActiveLegs.keySet())
        {
            if(callLegId.isIngressCompromised())
            {
                compromised.add(callLegId);
            }
        }

        for(ResolutionCohort cohort : mCohorts.values())
        {
            for(CompletedReceiverLeg leg : cohort.legs)
            {
                CallLegId callLegId = leg.snapshot.callLegId();

                if(callLegId != null && callLegId.isIngressCompromised() && !compromised.contains(callLegId))
                {
                    compromised.add(callLegId);
                }
            }
        }

        for(CallLegId callLegId : compromised)
        {
            abortLeg(callLegId);
        }
    }

    private void scheduleCohort(ResolutionCohort cohort, long delayNanos)
    {
        if(cohort == null || mDisposed)
        {
            return;
        }

        if(cohort.deadlineFuture != null)
        {
            cohort.deadlineFuture.cancel(false);
        }

        long cohortId = cohort.cohortId;

        try
        {
            cohort.deadlineFuture = mDeadlineScheduler.schedule(() -> {
                if(!mDisposed)
                {
                    if(mIngress.offer(CHECK_COHORT, true, null, cohortId))
                    {
                        mAcceptedIngressCount.incrementAndGet();
                        signalWorker();
                    }
                    else
                    {
                        mDroppedLifecycleCount.incrementAndGet();
                        mDroppedOperationCount.incrementAndGet();
                        mCohortSweepRequested.set(true);
                        signalWorker();
                    }
                }
            }, Math.max(0L, delayNanos), TimeUnit.NANOSECONDS);
        }
        catch(RejectedExecutionException _)
        {
            cohort.deadlineFuture = null;
        }
    }

    /** Owner-thread deadline fallback when a scheduled check could not enter the bounded command queue. */
    private void sweepDueCohorts()
    {
        if(!mCohortSweepRequested.compareAndSet(true, false))
        {
            return;
        }

        for(Long cohortId : List.copyOf(mCohorts.keySet()))
        {
            checkCohort(cohortId);
        }
    }

    private void checkCohort(long cohortId)
    {
        ResolutionCohort cohort = mCohorts.get(cohortId);

        if(cohort == null)
        {
            return;
        }

        ScheduledFuture<?> deadlineFuture = cohort.deadlineFuture;
        cohort.deadlineFuture = null;

        if(deadlineFuture != null && !deadlineFuture.isDone())
        {
            //A sweep can inspect this cohort because a different cohort's check was rejected.  Cancel this cohort's
            //still-pending task before scheduling a replacement so stale deadline tasks cannot multiply.
            deadlineFuture.cancel(false);
        }

        cohort.awaitedLegIds.removeIf(callLegId -> !mActiveLegs.containsKey(callLegId));
        long now = System.nanoTime();

        boolean awaitingActiveLeg = !cohort.awaitedLegIds.isEmpty();

        if(awaitingActiveLeg ? now >= cohort.activeLegWaitCeilingNanos : now >= cohort.settleDeadlineNanos)
        {
            finalizeCohort(cohortId);
        }
        else
        {
            scheduleCohort(cohort, nextCohortDelayNanos(cohort, now));
        }
    }

    private long nextCohortDelayNanos(ResolutionCohort cohort, long now)
    {
        long target = cohort.awaitedLegIds.isEmpty() ? cohort.settleDeadlineNanos :
            Math.min(cohort.activeLegWaitCeilingNanos,
                safeAdd(now, TimeUnit.MILLISECONDS.toNanos(COHORT_RECHECK_MILLISECONDS)));
        return Math.max(0L, target - now);
    }

    private void finalizeCohort(long cohortId)
    {
        ResolutionCohort cohort = mCohorts.remove(cohortId);

        if(cohort == null || cohort.legs.isEmpty())
        {
            return;
        }

        if(cohort.deadlineFuture != null)
        {
            cohort.deadlineFuture.cancel(false);
            cohort.deadlineFuture = null;
        }

        long decidedAtNanos = System.nanoTime();
        List<CompletedReceiverLeg> ranked = new ArrayList<>(cohort.legs);
        long cohortExpectedFrames = cohortExpectedFrames(cohort.legs);
        ranked.sort((first, second) -> compareQuality(first, second, cohortExpectedFrames));
        CompletedReceiverLeg winner = ranked.getFirst();
        CompletedAudioCall resolvedCall = buildResolvedCall(cohort.legs, winner, cohortExpectedFrames);
        LogicalCallDecisionOutcome outcome;
        List<LogicalCallSeparationReason> decisionReasons;
        LogicalCallDiagnosticEvidence evidence;

        if(cohort.legs.size() > 1)
        {
            outcome = LogicalCallDecisionOutcome.MERGED;
            decisionReasons = List.of();
            evidence = cohort.diagnosticEvidence(true);
        }
        else
        {
            evidence = cohort.diagnosticEvidence(false);
            decisionReasons = cohort.rejectedEvidence.reasons();

            if(decisionReasons.isEmpty())
            {
                decisionReasons = List.of(LogicalCallSeparationReason.NO_CANDIDATE_LEG);
            }

            outcome = hasFailOpenReason(decisionReasons) ? LogicalCallDecisionOutcome.FAIL_OPEN :
                LogicalCallDecisionOutcome.INDEPENDENT;
        }

        recordResolvedDecision(resolvedCall, cohort.legs, ranked, outcome, decisionReasons, evidence,
            decidedAtNanos, cohort.createdNanos, cohortExpectedFrames);
        queueResolvedCall(resolvedCall);
        releaseCoordinatorAudio(cohort.legs);
        markDiagnosticSnapshotDirty();
    }

    private void releaseIndependent(CompletedReceiverLeg leg, LogicalCallDecisionOutcome outcome,
                                    List<LogicalCallSeparationReason> reasons,
                                    LogicalCallDiagnosticEvidence evidence, long enteredResolverNanos)
    {
        long cohortExpectedFrames = cohortExpectedFrames(List.of(leg));
        CompletedAudioCall resolvedCall = buildResolvedCall(List.of(leg), leg, cohortExpectedFrames);
        long decidedAtNanos = System.nanoTime();
        recordResolvedDecision(resolvedCall, List.of(leg), List.of(leg), outcome, reasons, evidence, decidedAtNanos,
            enteredResolverNanos, cohortExpectedFrames);
        queueResolvedCall(resolvedCall);
        releaseCoordinatorAudio(List.of(leg));
        markDiagnosticSnapshotDirty();
    }

    private int compareQuality(CompletedReceiverLeg first, CompletedReceiverLeg second,
                               long cohortExpectedFrames)
    {
        return qualityComparison(first, second, cohortExpectedFrames).comparison;
    }

    private QualityComparison qualityComparison(CompletedReceiverLeg first, CompletedReceiverLeg second,
                                                long cohortExpectedFrames)
    {
        QualityScore firstScore = qualityScore(first, cohortExpectedFrames);
        QualityScore secondScore = qualityScore(second, cohortExpectedFrames);
        int comparison = Long.compare(secondScore.usableFrameCount, firstScore.usableFrameCount);
        LogicalCallWinnerCriterion criterion = LogicalCallWinnerCriterion.USABLE_FRAME_COUNT;

        if(comparison == 0)
        {
            comparison = Double.compare(firstScore.missingAndConcealedRate,
                secondScore.missingAndConcealedRate);
            criterion = LogicalCallWinnerCriterion.MISSING_AND_CONCEALED_RATE;
        }

        if(comparison == 0)
        {
            comparison = Double.compare(firstScore.repeatedRate, secondScore.repeatedRate);
            criterion = LogicalCallWinnerCriterion.REPEATED_FRAME_RATE;
        }

        if(comparison == 0)
        {
            comparison = Double.compare(firstScore.normalizedFecRate, secondScore.normalizedFecRate);
            criterion = LogicalCallWinnerCriterion.NORMALIZED_FEC_ERROR_RATE;
        }

        if(comparison == 0)
        {
            comparison = Boolean.compare(first.ingressLoss || first.audioTruncated,
                second.ingressLoss || second.audioTruncated);
            criterion = LogicalCallWinnerCriterion.INGRESS_LOSS_OR_AUDIO_TRUNCATION;
        }

        if(comparison == 0)
        {
            comparison = Long.compare(second.audioSampleCount, first.audioSampleCount);
            criterion = LogicalCallWinnerCriterion.RETAINED_AUDIO_SAMPLE_COUNT;
        }

        if(comparison == 0)
        {
            comparison = compareNullable(first.snapshot.callLegSource().siteGuid(),
                second.snapshot.callLegSource().siteGuid());
            criterion = LogicalCallWinnerCriterion.SITE_GUID;
        }

        if(comparison == 0)
        {
            comparison = compareNullable(first.snapshot.callLegSource().channelConfigurationId(),
                second.snapshot.callLegSource().channelConfigurationId());
            criterion = LogicalCallWinnerCriterion.CHANNEL_CONFIGURATION_ID;
        }

        if(comparison == 0)
        {
            comparison = compareCallLegIds(first.snapshot.callLegId(), second.snapshot.callLegId());
            criterion = LogicalCallWinnerCriterion.CALL_LEG_ID;
        }

        return new QualityComparison(comparison, criterion, firstScore, secondScore);
    }

    private QualityScore qualityScore(CompletedReceiverLeg leg, long cohortExpectedFrames)
    {
        VoiceCallQuality quality = leg.quality;
        long inferredAudioFrames = leg.audioSampleCount / 160L;
        long expected = Math.max(cohortExpectedFrames,
            Math.max(quality.expectedFrameCount(), inferredAudioFrames));
        long observed = quality.observedFrameCount();
        long availableFrames = leg.ingressLoss || leg.audioTruncated ?
            Math.min(observed, inferredAudioFrames) : Math.max(observed, inferredAudioFrames);
        long inferredMissing = Math.max(0L, expected - availableFrames);
        long missing = Math.max(quality.missingFrameCount(), inferredMissing);
        double missingAndConcealed = expected > 0L ?
            (double)(missing + quality.concealedFrameCount()) / expected : 1.0d;
        double repeated = expected > 0L ? (double)quality.repeatedFrameCount() / expected : 1.0d;
        double fec = quality.fecProtectedBitCount() > 0L ?
            (double)quality.fecErrorCount() / quality.fecProtectedBitCount() :
            1.0d;
        long usable = leg.ingressLoss || leg.audioTruncated ?
            Math.min(quality.decodedFrameCount(), inferredAudioFrames) : quality.decodedFrameCount();
        return new QualityScore(expected, missing + quality.concealedFrameCount(), missingAndConcealed,
            usable, quality.repeatedFrameCount(), repeated, quality.fecErrorCount(),
            quality.fecProtectedBitCount(), fec);
    }

    private long expectedFrames(CompletedReceiverLeg leg)
    {
        return Math.max(1L, VoiceCallQuality.expectedFrameCount(leg.startTimestamp, leg.endTimestamp));
    }

    private long qualityBaselineFrames(CompletedReceiverLeg leg)
    {
        return leg != null ? Math.max(expectedFrames(leg),
            Math.max(leg.quality.expectedFrameCount(), leg.audioSampleCount / 160L)) : 1L;
    }

    private long cohortExpectedFrames(Collection<CompletedReceiverLeg> legs)
    {
        long expected = 1L;

        if(legs != null)
        {
            for(CompletedReceiverLeg leg : legs)
            {
                expected = Math.max(expected, qualityBaselineFrames(leg));
            }
        }

        return expected;
    }

    private CompletedAudioCall buildResolvedCall(List<CompletedReceiverLeg> legs, CompletedReceiverLeg winner,
                                                 long cohortExpectedFrames)
    {
        VoiceCallQuality measuredWinnerQuality = withCohortExpectedFrames(winner, cohortExpectedFrames);
        List<AudioCallSnapshot> policySnapshots = legs.stream().map(leg -> leg.snapshot).toList();
        ResolvedCallPolicy policy = ResolvedCallPolicy.capture(policySnapshots);
        Set<BroadcastChannel> broadcastChannels = new LinkedHashSet<>();

        for(AudioCallSnapshot snapshot : policySnapshots)
        {
            if(snapshot.broadcastChannels() != null)
            {
                broadcastChannels.addAll(snapshot.broadcastChannels());
            }
        }

        IdentifierCollection identifiers = mergeWinnerIdentifiers(winner, legs);
        AudioCallRecordingMetadata recordingMetadata = selectRecordingMetadata(winner, legs);

        if(recordingMetadata == null)
        {
            recordingMetadata = AudioCallRecordingMetadata.captureAtSnapshot(winner.snapshot.aliasList(), identifiers);
        }

        recordingMetadata = recordingMetadata.withResolvedUserIdentifiers(identifiers.getToIdentifier(),
            identifiers.getFromIdentifier());
        AudioCallSnapshot winnerSnapshot = winner.snapshot;
        AudioCallSnapshot resolvedSnapshot = new AudioCallSnapshot(winnerSnapshot.callId(),
            winnerSnapshot.linkedCallId(), winnerSnapshot.aliasList(), identifiers, Set.copyOf(broadcastChannels),
            legs.stream().mapToLong(leg -> leg.startTimestamp).min().orElse(winner.startTimestamp),
            legs.stream().mapToLong(leg -> leg.endTimestamp).max().orElse(winner.endTimestamp),
            winner.burstCount, winnerSnapshot.burstGeneration(), winnerSnapshot.lastBurstStartTimestamp(),
            winnerSnapshot.lastBurstEndTimestamp(), false, true, winnerSnapshot.encryptionState(),
            policy.recordAudio(), recordingMetadata, measuredWinnerQuality,
            winnerSnapshot.callLegId(), winnerSnapshot.callLegSource(), winner.encryptionEvidence);

        List<CallLegSummary> summaries = legs.stream()
            .sorted((first, second) -> compareCallLegIds(first.snapshot.callLegId(), second.snapshot.callLegId()))
            .map(leg -> new CallLegSummary(leg.snapshot.callLegId(), leg.snapshot.callLegSource(),
                leg.startTimestamp, leg.endTimestamp, leg.quality, leg.audioSampleCount, leg.ingressLoss,
                leg.audioTruncated, leg == winner, leg.encryptionEvidence)).toList();

        return new CompletedAudioCall(new LogicalCallId(mCoordinatorId, mNextLogicalCallSequence++),
            resolvedSnapshot, winner.audioBuffers, policy, summaries);
    }

    private void recordResolvedDecision(CompletedAudioCall resolvedCall, List<CompletedReceiverLeg> legs,
                                        List<CompletedReceiverLeg> ranked, LogicalCallDecisionOutcome outcome,
                                        List<LogicalCallSeparationReason> decisionReasons,
                                        LogicalCallDiagnosticEvidence evidence,
                                        long decidedAtNanos, long enteredResolverNanos,
                                        long cohortExpectedFrames)
    {
        if(resolvedCall == null || legs == null || legs.isEmpty() || ranked == null || ranked.isEmpty())
        {
            return;
        }

        mEmittedLogicalCallCount++;

        switch(outcome)
        {
            case MERGED -> {
                mMergedLogicalCallCount++;
                mMergedReceiverCopyCount += Math.max(0, legs.size() - 1);
            }
            case INDEPENDENT -> mIndependentLogicalCallCount++;
            case FAIL_OPEN -> mFailOpenLogicalCallCount++;
            case ABORTED -> {
                //Resolved calls never use the aborted disposition.
            }
        }

        if(mDiagnosticSink != null)
        {
            try
            {
                long decidedAtMs = System.currentTimeMillis();
                CompletedReceiverLeg winner = ranked.getFirst();
                List<LogicalCallDiagnosticLeg> diagnosticLegs = legs.stream()
                    .sorted((first, second) -> compareCallLegIds(first.snapshot.callLegId(),
                        second.snapshot.callLegId()))
                    .map(leg -> diagnosticLeg(leg, cohortExpectedFrames, leg == winner)).toList();
                List<LogicalCallSeparationReason> exactReasons = decisionReasons != null ?
                    List.copyOf(new LinkedHashSet<>(decisionReasons)) : List.of();
                LogicalCallDiagnosticDecision decision = new LogicalCallDiagnosticDecision(
                    mNextDiagnosticDecisionSequence++, decidedAtMs, resolvedCall.logicalCallId(), outcome,
                    diagnosticCallIdentity(resolvedCall, legs, winner, decidedAtMs, decidedAtNanos,
                        enteredResolverNanos), diagnosticOutputPolicy(resolvedCall),
                    diagnosticWinner(ranked, cohortExpectedFrames), diagnosticLegs,
                    evidence != null ? evidence : LogicalCallDiagnosticEvidence.EMPTY, exactReasons);
                offerDiagnosticDecision(decision);
            }
            catch(Throwable throwable)
            {
                rethrowFatal(throwable);
                mDiagnosticDecisionRejectedCount++;
                mLog.warn("Unable to project a resolved logical-call diagnostic; call output was still preserved",
                    throwable);
            }
        }

        markDiagnosticSnapshotDirty();
    }

    private LogicalCallDiagnosticCallIdentity diagnosticCallIdentity(CompletedAudioCall resolvedCall,
                                                                      List<CompletedReceiverLeg> legs,
                                                                      CompletedReceiverLeg winner,
                                                                      long decidedAtMs, long decidedAtNanos,
                                                                      long enteredResolverNanos)
    {
        AudioCallSnapshot snapshot = resolvedCall.snapshot();
        AudioCallRecordingMetadata metadata = snapshot.recordingMetadata();
        CallLegSource source = winner.snapshot.callLegSource();
        P25SiteIdentity p25 = source != null ? source.p25SiteIdentity() : null;
        Identifier<?> destination = snapshot.identifierCollection() != null ?
            snapshot.identifierCollection().getToIdentifier() : null;
        String protocol = hasText(metadata != null ? metadata.destinationProtocol() : null) ?
            metadata.destinationProtocol() : destination != null && destination.getProtocol() != null ?
                destination.getProtocol().name() : null;
        String decoder = source != null && source.decoderType() != null ? source.decoderType().name() : null;
        String aliasListName = hasText(metadata != null ? metadata.aliasListName() : null) ?
            metadata.aliasListName() : snapshot.aliasList() != null ? snapshot.aliasList().getName() : null;
        Set<P25SiteIdentity> learnedSites = new HashSet<>();

        for(CompletedReceiverLeg leg : legs)
        {
            CallLegSource legSource = leg.snapshot.callLegSource();

            if(legSource != null && legSource.p25SiteIdentity() != null)
            {
                learnedSites.add(legSource.p25SiteIdentity());
            }
        }

        long wait = enteredResolverNanos > 0L ?
            TimeUnit.NANOSECONDS.toMillis(Math.max(0L, decidedAtNanos - enteredResolverNanos)) : 0L;
        return new LogicalCallDiagnosticCallIdentity(resolvedCall.logicalCallId().sequence(), protocol, decoder,
            snapshot.startTimestamp(), snapshot.lastActivityTimestamp(), decidedAtMs, wait,
            metadata != null ? metadata.destinationValue() : null,
            metadata != null ? metadata.destinationAlias() : null,
            metadata != null ? metadata.sourceValue() : null,
            metadata != null ? metadata.sourceAlias() : null, snapshot.encryptionState(),
            p25 != null ? p25.wacn() : null, p25 != null ? p25.system() : null,
            source != null ? source.aliasListId() : 0L, aliasListName, learnedSites.size());
    }

    private LogicalCallDiagnosticOutputPolicy diagnosticOutputPolicy(CompletedAudioCall call)
    {
        Set<String> routingKeys = call.resolvedPolicy().broadcastRoutingKeys();
        List<String> routes = routingKeys.stream().sorted()
            .limit(LogicalCallDiagnosticOutputPolicy.MAXIMUM_RETAINED_STREAM_ROUTE_NAMES).toList();
        return new LogicalCallDiagnosticOutputPolicy(call.resolvedPolicy().recordAudio(), routes,
            routingKeys.size(), call.hasAudio() && mWebConsumer != null);
    }

    private LogicalCallDiagnosticWinner diagnosticWinner(List<CompletedReceiverLeg> ranked,
                                                          long cohortExpectedFrames)
    {
        CompletedReceiverLeg winner = ranked.getFirst();

        if(ranked.size() == 1)
        {
            return new LogicalCallDiagnosticWinner(legId(winner), null, LogicalCallWinnerCriterion.SINGLE_LEG,
                LogicalCallDiagnosticWinner.CriterionValue.empty(),
                LogicalCallDiagnosticWinner.CriterionValue.empty());
        }

        CompletedReceiverLeg runnerUp = ranked.get(1);
        QualityComparison comparison = qualityComparison(winner, runnerUp, cohortExpectedFrames);
        return new LogicalCallDiagnosticWinner(legId(winner), legId(runnerUp), comparison.criterion,
            criterionValue(winner, comparison.firstScore, comparison.criterion),
            criterionValue(runnerUp, comparison.secondScore, comparison.criterion));
    }

    private LogicalCallDiagnosticWinner.CriterionValue criterionValue(CompletedReceiverLeg leg,
                                                                       QualityScore score,
                                                                       LogicalCallWinnerCriterion criterion)
    {
        return switch(criterion)
        {
            case MISSING_AND_CONCEALED_RATE -> rateValue(score.missingAndConcealedCount,
                score.expectedFrameCount, score.missingAndConcealedRate);
            case USABLE_FRAME_COUNT -> rateValue(score.usableFrameCount, score.expectedFrameCount,
                score.expectedFrameCount > 0L ? (double)score.usableFrameCount / score.expectedFrameCount : 0.0d);
            case REPEATED_FRAME_RATE -> rateValue(score.repeatedFrameCount, score.expectedFrameCount,
                score.repeatedRate);
            case NORMALIZED_FEC_ERROR_RATE -> score.fecProtectedBitCount > 0L ?
                rateValue(score.fecErrorCount, score.fecProtectedBitCount, score.normalizedFecRate) :
                textValue("not measured");
            case INGRESS_LOSS_OR_AUDIO_TRUNCATION -> textValue(Boolean.toString(
                leg.ingressLoss || leg.audioTruncated));
            case RETAINED_AUDIO_SAMPLE_COUNT -> wholeValue(leg.audioSampleCount);
            case SITE_GUID -> textValue(leg.snapshot.callLegSource().siteGuid());
            case CHANNEL_CONFIGURATION_ID -> textValue(leg.snapshot.callLegSource().channelConfigurationId());
            case CALL_LEG_ID -> textValue(legId(leg));
            case SINGLE_LEG -> LogicalCallDiagnosticWinner.CriterionValue.empty();
        };
    }

    private static LogicalCallDiagnosticWinner.CriterionValue rateValue(long numerator, long denominator,
                                                                         double rate)
    {
        String display = String.format(Locale.ROOT, "%d/%d (%.3f%%)", numerator, denominator, rate * 100.0d);
        return new LogicalCallDiagnosticWinner.CriterionValue(display, numerator, denominator);
    }

    private static LogicalCallDiagnosticWinner.CriterionValue wholeValue(long value)
    {
        return new LogicalCallDiagnosticWinner.CriterionValue(Long.toString(value), value, null);
    }

    private static LogicalCallDiagnosticWinner.CriterionValue textValue(String value)
    {
        return new LogicalCallDiagnosticWinner.CriterionValue(value != null ? value : "(missing)", null, null);
    }

    private LogicalCallDiagnosticLeg diagnosticLeg(CompletedReceiverLeg leg, long expectedFrames, boolean winner)
    {
        CallLegSource source = leg.snapshot.callLegSource();
        P25SiteIdentity site = source != null ? source.p25SiteIdentity() : null;
        QualityScore score = qualityScore(leg, expectedFrames);
        VoiceCallQuality quality = leg.quality;
        long effectiveMissing = Math.max(0L, score.missingAndConcealedCount - quality.concealedFrameCount());
        double qualityPercent = score.expectedFrameCount > 0L ?
            100.0d * score.usableFrameCount / score.expectedFrameCount : 0.0d;
        return new LogicalCallDiagnosticLeg(legId(leg),
            source != null && source.decoderType() != null ? source.decoderType().name() : null,
            source != null ? source.channelConfigurationId() : null, source != null ? source.channelName() : null,
            source != null ? source.siteGuid() : null,
            source != null ? source.aliasListId() : 0L, site != null ? site.wacn() : null,
            site != null ? site.system() : null, site != null ? site.rfss() : null,
            site != null ? site.site() : null, leg.startTimestamp, leg.endTimestamp,
            Math.max(0L, leg.endTimestamp - leg.startTimestamp), score.expectedFrameCount,
            quality.observedFrameCount(), score.usableFrameCount, quality.decodedFrameCount(),
            quality.repeatedFrameCount(), quality.concealedFrameCount(), effectiveMissing,
            quality.fecErrorCount(), quality.fecProtectedBitCount(), qualityPercent,
            score.missingAndConcealedRate, score.repeatedRate, score.normalizedFecRate,
            leg.audioSampleCount, leg.ingressLoss, leg.audioTruncated, winner);
    }

    private LogicalCallDiagnosticLeg diagnosticLeg(AudioCallSnapshot snapshot, String callLegId)
    {
        CallLegSource source = snapshot.callLegSource();
        P25SiteIdentity site = source != null ? source.p25SiteIdentity() : null;
        VoiceCallQuality quality = snapshot.voiceCallQuality();
        long expected = Math.max(VoiceCallQuality.expectedFrameCount(snapshot.startTimestamp(),
            snapshot.lastActivityTimestamp()), quality.expectedFrameCount());
        long missing = Math.max(quality.missingFrameCount(), Math.max(0L, expected - quality.observedFrameCount()));
        long missingAndConcealed = missing + quality.concealedFrameCount();
        double missingRate = expected > 0L ? (double)missingAndConcealed / expected : 1.0d;
        double repeatedRate = expected > 0L ? (double)quality.repeatedFrameCount() / expected : 1.0d;
        double fecRate = quality.fecProtectedBitCount() > 0L ?
            (double)quality.fecErrorCount() / quality.fecProtectedBitCount() : 1.0d;
        double qualityPercent = expected > 0L ? 100.0d * quality.decodedFrameCount() / expected : 0.0d;
        return new LogicalCallDiagnosticLeg(callLegId,
            source != null && source.decoderType() != null ? source.decoderType().name() : null,
            source != null ? source.channelConfigurationId() : null, source != null ? source.channelName() : null,
            source != null ? source.siteGuid() : null,
            source != null ? source.aliasListId() : 0L, site != null ? site.wacn() : null,
            site != null ? site.system() : null, site != null ? site.rfss() : null,
            site != null ? site.site() : null, snapshot.startTimestamp(), snapshot.lastActivityTimestamp(),
            Math.max(0L, snapshot.lastActivityTimestamp() - snapshot.startTimestamp()), expected,
            quality.observedFrameCount(), quality.decodedFrameCount(), quality.decodedFrameCount(),
            quality.repeatedFrameCount(), quality.concealedFrameCount(), missing, quality.fecErrorCount(),
            quality.fecProtectedBitCount(), qualityPercent, missingRate, repeatedRate, fecRate, 0L,
            snapshot.callLegId().isIngressCompromised(), false, false);
    }

    private LogicalCallDiagnosticLeg diagnosticLeg(ReceiverLeg leg)
    {
        AudioCallSnapshot snapshot = leg.latestSnapshot;
        long start = leg.startTimestamp != Long.MAX_VALUE ? leg.startTimestamp :
            snapshot != null ? snapshot.startTimestamp() : 0L;
        long end = Math.max(start, leg.endTimestamp);
        VoiceCallQuality quality = addQuality(leg.chunkSnapshots.values().stream()
            .map(AudioCallSnapshot::voiceCallQuality).toList());
        CompletedReceiverLeg projection = new CompletedReceiverLeg(snapshot, List.of(), List.of(),
            start, end, 0, quality, leg.audioSampleCount, leg.ingressLoss, leg.audioTruncated,
            destinationIdentity(snapshot), sourceIdentity(snapshot), snapshot != null ?
                snapshot.callEncryptionEvidence() : null);
        return diagnosticLeg(projection, qualityBaselineFrames(projection), false);
    }

    private static boolean hasFailOpenReason(Collection<LogicalCallSeparationReason> reasons)
    {
        return reasons != null && reasons.stream().anyMatch(LogicalCallSeparationReason::isFailOpen);
    }

    private static String legId(CompletedReceiverLeg leg)
    {
        return leg.snapshot.callLegId().toString();
    }

    private void offerDiagnosticDecision(LogicalCallDiagnosticDecision decision)
    {
        if(mForcedDiscard.get() || mDiagnosticSink == null || decision == null)
        {
            return;
        }

        mDiagnosticDecisionOfferedCount++;

        try
        {
            if(!mDiagnosticSink.offer(decision))
            {
                mDiagnosticDecisionRejectedCount++;
            }
        }
        catch(Throwable throwable)
        {
            rethrowFatal(throwable);
            mDiagnosticDecisionRejectedCount++;
        }
    }

    private VoiceCallQuality withCohortExpectedFrames(CompletedReceiverLeg winner, long expectedFrames)
    {
        VoiceCallQuality quality = winner.quality;
        QualityScore score = qualityScore(winner, expectedFrames);
        long missing = Math.max(0L, score.missingAndConcealedCount - quality.concealedFrameCount());

        return new VoiceCallQuality(quality.decodedFrameCount(), quality.repeatedFrameCount(),
            quality.concealedFrameCount(), missing, quality.fecErrorCount(), quality.fecProtectedBitCount());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private IdentifierCollection mergeWinnerIdentifiers(CompletedReceiverLeg winner,
                                                         List<CompletedReceiverLeg> legs)
    {
        List<Identifier> identifiers = new ArrayList<>();

        if(winner.snapshot.identifierCollection() != null)
        {
            identifiers.addAll(winner.snapshot.identifierCollection().getIdentifiers());
        }

        ExactDestination exactDestination = mostExactDestination(legs);

        if(exactDestination != null)
        {
            Identifier<?> promotedDestination = promotedDestinationIdentifier(winner, exactDestination);
            replaceDestinationIdentifier(identifiers, promotedDestination);
        }
        else
        {
            addMissingUserIdentifier(identifiers, legs, Form.PATCH_GROUP, Role.TO);
            addMissingUserIdentifier(identifiers, legs, Form.TALKGROUP, Role.TO);
        }

        FullyQualifiedRadioIdentifier exactSource = mostExactSource(legs);

        if(exactSource != null)
        {
            replaceUserIdentifier(identifiers, Form.RADIO, Role.FROM, exactSource);
        }
        else
        {
            addMissingUserIdentifier(identifiers, legs, Form.RADIO, Role.FROM);
        }

        addMissingUserIdentifier(identifiers, legs, Form.ENCRYPTION_KEY, Role.ANY);
        IdentifierCollection merged = new IdentifierCollection(identifiers);
        merged.setTimeslot(winner.snapshot.timeslot());
        return merged;
    }

    /**
     * Selects the single fully-qualified destination compatible with every confirmed cohort member.  Two different
     * qualified home identities can never be promoted, even if their local talkgroup values happen to match.
     */
    private ExactDestination mostExactDestination(List<CompletedReceiverLeg> legs)
    {
        ExactDestination selected = null;

        for(CompletedReceiverLeg leg : legs)
        {
            Identifier<?> destination = leg.snapshot.identifierCollection() != null ?
                leg.snapshot.identifierCollection().getToIdentifier() : null;
            PatchGroupIdentifier patch = destination instanceof PatchGroupIdentifier value ? value : null;
            TalkgroupIdentifier primary = primaryTalkgroup(destination);

            if(primary instanceof FullyQualifiedTalkgroupIdentifier fullyQualified)
            {
                DestinationIdentity candidate = destinationIdentity(fullyQualified);

                if(candidate == null || legs.stream().anyMatch(member -> member.destinationIdentity == null ||
                    !candidate.matches(member.destinationIdentity)))
                {
                    return null;
                }

                if(selected == null)
                {
                    selected = new ExactDestination(fullyQualified, patch);
                }
                else
                {
                    DestinationIdentity existing = destinationIdentity(selected.talkgroup);

                    if(existing == null || !existing.matches(candidate))
                    {
                        return null;
                    }
                }
            }
        }

        return selected;
    }

    /** Selects one exact source only when every known source in the confirmed cohort agrees with it. */
    private FullyQualifiedRadioIdentifier mostExactSource(List<CompletedReceiverLeg> legs)
    {
        FullyQualifiedRadioIdentifier selected = null;

        for(CompletedReceiverLeg leg : legs)
        {
            Identifier<?> source = leg.snapshot.identifierCollection() != null ?
                leg.snapshot.identifierCollection().getIdentifier(IdentifierClass.USER, Form.RADIO, Role.FROM) : null;

            if(source instanceof FullyQualifiedRadioIdentifier fullyQualified)
            {
                SourceIdentity candidate = sourceIdentity(fullyQualified);

                if(candidate == null || legs.stream().anyMatch(member -> member.sourceIdentity != null &&
                    !candidate.matches(member.sourceIdentity)))
                {
                    return null;
                }

                if(selected == null)
                {
                    selected = fullyQualified;
                }
                else
                {
                    SourceIdentity existing = sourceIdentity(selected);

                    if(existing == null || !existing.matches(candidate))
                    {
                        return null;
                    }
                }
            }
        }

        return selected;
    }

    private Identifier<?> promotedDestinationIdentifier(CompletedReceiverLeg winner,
                                                        ExactDestination exactDestination)
    {
        Identifier<?> winnerDestination = winner.snapshot.identifierCollection() != null ?
            winner.snapshot.identifierCollection().getToIdentifier() : null;

        if(!(winnerDestination instanceof PatchGroupIdentifier winnerPatch))
        {
            return exactDestination.talkgroup;
        }

        if(exactDestination.patch != null)
        {
            return exactDestination.patch;
        }

        PatchGroup original = winnerPatch.getValue();

        if(original == null)
        {
            return exactDestination.talkgroup;
        }

        PatchGroup promoted = new PatchGroup(exactDestination.talkgroup, original.getVersion());
        promoted.addPatchedTalkgroups(original.getPatchedTalkgroupIdentifiers());
        promoted.addPatchedRadios(original.getPatchedRadioIdentifiers());
        return APCO25PatchGroup.create(promoted);
    }

    private static TalkgroupIdentifier primaryTalkgroup(Identifier<?> destination)
    {
        if(destination instanceof PatchGroupIdentifier patch && patch.getValue() != null)
        {
            return patch.getValue().getPatchGroup();
        }

        return destination instanceof TalkgroupIdentifier talkgroup ? talkgroup : null;
    }

    private static DestinationIdentity destinationIdentity(TalkgroupIdentifier talkgroup)
    {
        ResolvedCallPolicy.DestinationIdentity identity = ResolvedCallPolicy.DestinationIdentity.from(talkgroup);
        return identity != null ? new DestinationIdentity(identity) : null;
    }

    @SuppressWarnings("rawtypes")
    private static void replaceDestinationIdentifier(List<Identifier> identifiers, Identifier<?> replacement)
    {
        identifiers.removeIf(identifier -> identifier.getIdentifierClass() == IdentifierClass.USER &&
            identifier.getRole() == Role.TO &&
            (identifier.getForm() == Form.PATCH_GROUP || identifier.getForm() == Form.TALKGROUP));

        if(replacement != null)
        {
            identifiers.add(replacement);
        }
    }

    @SuppressWarnings("rawtypes")
    private static void replaceUserIdentifier(List<Identifier> identifiers, Form form, Role role,
                                              Identifier<?> replacement)
    {
        identifiers.removeIf(identifier -> identifier.getIdentifierClass() == IdentifierClass.USER &&
            identifier.getForm() == form && identifier.getRole() == role);

        if(replacement != null)
        {
            identifiers.add(replacement);
        }
    }

    @SuppressWarnings("rawtypes")
    private void addMissingUserIdentifier(List<Identifier> identifiers, List<CompletedReceiverLeg> legs,
                                          Form form, Role role)
    {
        boolean present = identifiers.stream().anyMatch(identifier ->
            identifier.getIdentifierClass() == IdentifierClass.USER && identifier.getForm() == form &&
                identifier.getRole() == role);

        if(present)
        {
            return;
        }

        for(CompletedReceiverLeg leg : legs)
        {
            IdentifierCollection collection = leg.snapshot.identifierCollection();
            Identifier<?> candidate = collection != null ?
                collection.getIdentifier(IdentifierClass.USER, form, role) : null;

            if(candidate != null)
            {
                identifiers.add(candidate);
                return;
            }
        }
    }

    private AudioCallRecordingMetadata selectRecordingMetadata(CompletedReceiverLeg winner,
                                                                List<CompletedReceiverLeg> legs)
    {
        AudioCallRecordingMetadata selected = winner.snapshot.recordingMetadata();

        if(selected != null && hasText(selected.destinationValue()) && hasText(selected.sourceValue()))
        {
            return selected;
        }

        for(CompletedReceiverLeg leg : legs)
        {
            AudioCallRecordingMetadata candidate = leg.snapshot.recordingMetadata();

            if(candidate != null && hasText(candidate.destinationValue()) &&
                (!hasText(selected != null ? selected.destinationValue() : null) ||
                    hasText(candidate.sourceValue())))
            {
                selected = candidate;
            }
        }

        return selected;
    }

    private void releaseCoordinatorAudio(Collection<CompletedReceiverLeg> legs)
    {
        for(CompletedReceiverLeg leg : legs)
        {
            mRetainedAudioSamples = Math.max(0L, mRetainedAudioSamples - leg.audioSampleCount);
            leg.releaseCoordinatorReferences();
        }
    }

    private void queueResolvedCall(CompletedAudioCall call)
    {
        if(call != null && !mForcedDiscard.get())
        {
            mPendingFanouts.add(call);
        }
    }

    private void drainPendingFanouts()
    {
        for(CompletedAudioCall completedAudioCall : mPendingFanouts)
        {
            if(mForcedDiscard.get())
            {
                break;
            }

            deliver("resolved-call statistics", mResolvedCallConsumer, completedAudioCall);

            if(completedAudioCall.hasAudio() && !mForcedDiscard.get())
            {
                deliver("recording", mRecordingConsumer, completedAudioCall);
                deliver("streaming", mStreamingConsumer, completedAudioCall);
                deliver("browser", mWebConsumer, completedAudioCall);
            }
        }

        mPendingFanouts.clear();
    }

    private void deliver(String name, Consumer<CompletedAudioCall> consumer, CompletedAudioCall call)
    {
        if(mForcedDiscard.get() || consumer == null || call == null)
        {
            return;
        }

        try
        {
            consumer.accept(call);
        }
        catch(Throwable throwable)
        {
            rethrowFatal(throwable);
            mLog.warn("Logical-call {} consumer failed", name, throwable);
        }
    }

    private static void rethrowFatal(Throwable throwable)
    {
        if(throwable instanceof VirtualMachineError error)
        {
            throw error;
        }
    }

    private void flushCompletedCohorts()
    {
        if(mForcedDiscard.get())
        {
            return;
        }

        sweepCompromisedLegs();

        for(Long cohortId : List.copyOf(mCohorts.keySet()))
        {
            if(mForcedDiscard.get())
            {
                break;
            }

            finalizeCohort(cohortId);
        }
    }

    private void discardWorkerState()
    {
        for(ResolutionCohort cohort : mCohorts.values())
        {
            if(cohort.deadlineFuture != null)
            {
                cohort.deadlineFuture.cancel(false);
            }
        }

        mActiveLegs.clear();
        mCohorts.clear();
        mPendingFanouts.clear();
        mPublishedAbortLegIds.clear();
        mRetainedAudioSamples = 0L;
        markDiagnosticSnapshotDirty();
    }

    public synchronized void dispose()
    {
        if(!mAccepting)
        {
            return;
        }

        mAccepting = false;
        mDeadlineScheduler.shutdownNow();
        signalWorker();

        try
        {
            mWorker.join(SHUTDOWN_DRAIN_MILLISECONDS);
        }
        catch(InterruptedException _)
        {
            Thread.currentThread().interrupt();
        }

        if(mWorker.isAlive())
        {
            mForcedDiscard.set(true);
            mDisposed = true;
            mWorker.interrupt();
            mLog.warn("Logical-call resolver did not drain within {} milliseconds; remaining state was discarded",
                SHUTDOWN_DRAIN_MILLISECONDS);
        }
    }

    public CoordinatorQueueStatus getQueueStatus()
    {
        return new CoordinatorQueueStatus(mIngress.size(), mIngress.regularCapacity(), mIngress.capacity(),
            mAcceptedIngressCount.get(), mDroppedIngressCount.get(), mDroppedLifecycleCount.get(),
            mDroppedOperationCount.get(), mAbortedCallCount.get());
    }

    /**
     * Latest immutable resolver state. The value is assembled only by the resolver observer thread and can be polled
     * without locking or calling back onto that worker.
     */
    public LogicalCallDiagnosticSnapshot getDiagnosticSnapshot()
    {
        return mDiagnosticSnapshot;
    }

    private void markDiagnosticSnapshotDirty()
    {
        mDiagnosticSnapshotDirty = true;
    }

    private void publishDiagnosticSnapshot(boolean force)
    {
        try
        {
            updateDiagnosticSnapshot(force);
            mDiagnosticSnapshotFailureLogged = false;
        }
        catch(Throwable throwable)
        {
            rethrowFatal(throwable);

            if(!mDiagnosticSnapshotFailureLogged)
            {
                mLog.warn("Unable to refresh the logical-call diagnostic snapshot; resolver work will continue",
                    throwable);
                mDiagnosticSnapshotFailureLogged = true;
            }
        }
    }

    private void updateDiagnosticSnapshot(boolean force)
    {
        long nowNanos = System.nanoTime();

        if(!force && !mDiagnosticSnapshotDirty && nowNanos - mLastDiagnosticSnapshotNanos <
            TimeUnit.MILLISECONDS.toNanos(DIAGNOSTIC_REFRESH_MILLISECONDS))
        {
            return;
        }

        List<LogicalCallDiagnosticLeg> activeLegs = mActiveLegs.values().stream()
            .sorted((first, second) -> compareCallLegIds(first.callLegId, second.callLegId))
            .map(this::diagnosticLeg).toList();
        List<LogicalCallDiagnosticCohort> activeCohorts = mCohorts.values().stream()
            .sorted(Comparator.comparingLong(cohort -> cohort.cohortId))
            .map(cohort -> diagnosticCohort(cohort, nowNanos)).toList();
        LogicalCallDiagnosticCounters counters = new LogicalCallDiagnosticCounters(
            mAcceptedIngressCount.get(), mDroppedIngressCount.get(), mDroppedLifecycleCount.get(),
            mDroppedOperationCount.get(), mAbortedCallCount.get(), mCompletedReceiverLegCount,
            mEligibleReceiverLegCount, mEmittedLogicalCallCount, mMergedLogicalCallCount,
            mMergedReceiverCopyCount, mIndependentLogicalCallCount, mFailOpenLogicalCallCount,
            mSeparatedPairComparisonCount, mDiagnosticDecisionOfferedCount,
            mDiagnosticDecisionRejectedCount);
        mDiagnosticSnapshot = new LogicalCallDiagnosticSnapshot(mDiagnosticSessionId, mDiagnosticStartedAtMs,
            System.currentTimeMillis(), ++mDiagnosticSnapshotRevision, mAccepting, mDisposed, counters,
            activeLegs.size(), activeCohorts.size(), mRetainedAudioSamples, activeLegs, activeCohorts);
        mLastDiagnosticSnapshotNanos = nowNanos;
        mDiagnosticSnapshotDirty = false;
    }

    private LogicalCallDiagnosticCohort diagnosticCohort(ResolutionCohort cohort, long nowNanos)
    {
        long expectedFrames = cohortExpectedFrames(cohort.legs);
        List<LogicalCallDiagnosticLeg> legs = cohort.legs.stream()
            .sorted((first, second) -> compareCallLegIds(first.snapshot.callLegId(), second.snapshot.callLegId()))
            .map(leg -> diagnosticLeg(leg, expectedFrames, false)).toList();
        List<String> awaited = cohort.awaitedLegIds.stream().map(CallLegId::toString).sorted().toList();
        return new LogicalCallDiagnosticCohort(cohort.cohortId,
            TimeUnit.NANOSECONDS.toMillis(Math.max(0L, nowNanos - cohort.createdNanos)),
            TimeUnit.NANOSECONDS.toMillis(Math.max(0L, cohort.settleDeadlineNanos - nowNanos)),
            TimeUnit.NANOSECONDS.toMillis(Math.max(0L, cohort.activeLegWaitCeilingNanos - nowNanos)),
            legs, awaited);
    }

    private static long safeAdd(long value, long addition)
    {
        return addition > 0L && value > Long.MAX_VALUE - addition ? Long.MAX_VALUE : value + addition;
    }

    private static boolean hasText(String value)
    {
        return value != null && !value.isBlank();
    }

    private static int compareNullable(String first, String second)
    {
        if(first == null)
        {
            return second == null ? 0 : 1;
        }
        else if(second == null)
        {
            return -1;
        }

        return first.compareTo(second);
    }

    private static int compareCallLegIds(CallLegId first, CallLegId second)
    {
        if(first == second)
        {
            return 0;
        }
        else if(first == null)
        {
            return 1;
        }
        else if(second == null)
        {
            return -1;
        }

        int comparison = Long.compare(first.producerId(), second.producerId());

        if(comparison == 0)
        {
            comparison = Long.compare(first.sequence(), second.sequence());
        }

        return comparison != 0 ? comparison : Integer.compare(first.timeslot(), second.timeslot());
    }

    /**
     * Three distinct shared vocoder-frame fingerprints at aligned carrier times prove that two receiver legs carry
     * the same transmission.  Call-object start time, queue arrival, and fingerprint list position are deliberately
     * irrelevant.  Each distinct hash contributes at most once to a candidate site-to-site time delta.
     */
    private boolean hasSharedVoiceContent(FingerprintIndex first, FingerprintIndex second)
    {
        if(first == null || second == null || first.distinctCount() < 3 || second.distinctCount() < 3)
        {
            return false;
        }

        Arrays.fill(mSharedFrameDeltaCounts, 0);
        FingerprintIndex smaller = first.distinctCount() <= second.distinctCount() ? first : second;
        FingerprintIndex larger = smaller == first ? second : first;

        for(Map.Entry<Long,LongTimestamps> entry : smaller.entries())
        {
            LongTimestamps other = larger.timestamps(entry.getKey());

            if(other == null)
            {
                continue;
            }

            int epoch = nextSharedFrameDeltaEpoch();
            LongTimestamps current = entry.getValue();
            int otherStart = 0;

            for(int currentIndex = 0; currentIndex < current.size(); currentIndex++)
            {
                long currentTimestamp = current.get(currentIndex);
                long minimumOtherTimestamp = currentTimestamp - MAXIMUM_SHARED_FRAME_SITE_DELTA_MILLISECONDS;
                long maximumOtherTimestamp = currentTimestamp + MAXIMUM_SHARED_FRAME_SITE_DELTA_MILLISECONDS;

                while(otherStart < other.size() && other.get(otherStart) < minimumOtherTimestamp)
                {
                    otherStart++;
                }

                for(int otherIndex = otherStart; otherIndex < other.size(); otherIndex++)
                {
                    long otherTimestamp = other.get(otherIndex);

                    if(otherTimestamp > maximumOtherTimestamp)
                    {
                        break;
                    }

                    long delta = otherTimestamp - currentTimestamp;
                    int centerBucket = (int)(delta + MAXIMUM_SHARED_FRAME_SITE_DELTA_MILLISECONDS);
                    int firstBucket = Math.max(0, centerBucket - SHARED_FRAME_DELTA_RADIUS_MILLISECONDS);
                    int lastBucket = Math.min(SHARED_FRAME_DELTA_BUCKETS - 1,
                        centerBucket + SHARED_FRAME_DELTA_RADIUS_MILLISECONDS);

                    for(int bucket = firstBucket; bucket <= lastBucket; bucket++)
                    {
                        if(mSharedFrameDeltaMarks[bucket] != epoch)
                        {
                            mSharedFrameDeltaMarks[bucket] = epoch;

                            if(++mSharedFrameDeltaCounts[bucket] >= 3)
                            {
                                return true;
                            }
                        }
                    }
                }
            }
        }

        return false;
    }

    private int nextSharedFrameDeltaEpoch()
    {
        if(++mSharedFrameDeltaEpoch == 0)
        {
            Arrays.fill(mSharedFrameDeltaMarks, 0);
            mSharedFrameDeltaEpoch = 1;
        }

        return mSharedFrameDeltaEpoch;
    }

    private static DestinationIdentity destinationIdentity(AudioCallSnapshot snapshot)
    {
        IdentifierCollection identifiers = snapshot != null ? snapshot.identifierCollection() : null;
        Identifier<?> destination = identifiers != null ? identifiers.getToIdentifier() : null;
        return destinationIdentity(primaryTalkgroup(destination));
    }

    private static SourceIdentity sourceIdentity(AudioCallSnapshot snapshot)
    {
        IdentifierCollection identifiers = snapshot != null ? snapshot.identifierCollection() : null;
        Identifier<?> identifier = identifiers != null ?
            identifiers.getIdentifier(IdentifierClass.USER, Form.RADIO, Role.FROM) : null;

        return sourceIdentity(identifier);
    }

    private static SourceIdentity sourceIdentity(Identifier<?> identifier)
    {
        if(identifier instanceof FullyQualifiedRadioIdentifier fullyQualified)
        {
            return new SourceIdentity(identifier.getProtocol() != null ? identifier.getProtocol().name() : null,
                fullyQualified.getValue(), fullyQualified.getWacn(), fullyQualified.getSystem(),
                fullyQualified.getRadio());
        }
        else if(identifier instanceof RadioIdentifier radio && radio.isValid())
        {
            return new SourceIdentity(identifier.getProtocol() != null ? identifier.getProtocol().name() : null,
                radio.getValue(), null, null, null);
        }

        return null;
    }

    private static VoiceCallQuality addQuality(Collection<VoiceCallQuality> values)
    {
        long decoded = 0L;
        long repeated = 0L;
        long concealed = 0L;
        long missing = 0L;
        long errors = 0L;
        long protectedBits = 0L;

        for(VoiceCallQuality value : values)
        {
            if(value != null)
            {
                decoded += value.decodedFrameCount();
                repeated += value.repeatedFrameCount();
                concealed += value.concealedFrameCount();
                missing += value.missingFrameCount();
                errors += value.fecErrorCount();
                protectedBits += value.fecProtectedBitCount();
            }
        }

        return new VoiceCallQuality(decoded, repeated, concealed, missing, errors, protectedBits);
    }

    /** Worker-owned physical call leg, including all linked duration chunks. */
    private static final class ReceiverLeg
    {
        private final CallLegId callLegId;
        private final Map<AudioCallId,AudioCallSnapshot> chunkSnapshots = new HashMap<>();
        private final List<float[]> audioBuffers = new ArrayList<>();
        private final List<TimestampedVoiceFingerprint> voiceFrameFingerprints = new ArrayList<>();
        private AudioCallSnapshot latestSnapshot;
        private PreliminaryLegScope preliminaryScope = PreliminaryLegScope.INELIGIBLE;
        private long startTimestamp = Long.MAX_VALUE;
        private long endTimestamp;
        private long nextAwaitedRefreshEndTimestamp = Long.MAX_VALUE;
        private long audioSampleCount;
        private boolean ingressLoss;
        private boolean audioTruncated;
        private boolean awaitedLegRefreshRequired = true;

        private ReceiverLeg(CallLegId callLegId, AudioCallSnapshot snapshot)
        {
            this.callLegId = callLegId;
            updateSnapshot(snapshot);
        }

        private long accept(AudioCallEvent event, boolean lostIngress, long availableSamples)
        {
            AudioCallSnapshot snapshot = event.snapshot();
            updateSnapshot(snapshot);
            ingressLoss |= lostIngress;
            float[] frame = event.audioFrameView();

            if(frame == null)
            {
                return 0L;
            }

            if(frame.length > availableSamples)
            {
                audioTruncated = true;
                return 0L;
            }

            audioBuffers.add(frame);
            audioSampleCount += frame.length;

            if(voiceFrameFingerprints.size() < MAXIMUM_FINGERPRINTS_PER_LEG &&
                event.voiceFrameFingerprint() != 0L && event.voiceFrameTimestamp() > 0L)
            {
                voiceFrameFingerprints.add(new TimestampedVoiceFingerprint(event.voiceFrameFingerprint(),
                    event.voiceFrameTimestamp()));
            }

            return frame.length;
        }

        private void updateSnapshot(AudioCallSnapshot snapshot)
        {
            if(snapshot == null || snapshot.callId() == null)
            {
                return;
            }

            long previousStartTimestamp = startTimestamp;
            long previousEndTimestamp = endTimestamp;
            boolean previousTimingValid = hasValidTiming();

            if(snapshot != latestSnapshot)
            {
                PreliminaryLegScope updatedScope = PreliminaryLegScope.from(snapshot,
                    destinationIdentity(snapshot), sourceIdentity(snapshot));

                if(!updatedScope.equals(preliminaryScope))
                {
                    preliminaryScope = updatedScope;
                    awaitedLegRefreshRequired = true;
                }
            }

            latestSnapshot = snapshot;
            chunkSnapshots.put(snapshot.callId(), snapshot);

            if(snapshot.startTimestamp() > 0L)
            {
                startTimestamp = Math.min(startTimestamp, snapshot.startTimestamp());
            }

            endTimestamp = Math.max(endTimestamp, snapshot.lastActivityTimestamp());

            if(startTimestamp != previousStartTimestamp)
            {
                awaitedLegRefreshRequired = true;
            }

            if(hasValidTiming() != previousTimingValid)
            {
                awaitedLegRefreshRequired = true;
            }

            if(endTimestamp > previousEndTimestamp && endTimestamp >= nextAwaitedRefreshEndTimestamp)
            {
                awaitedLegRefreshRequired = true;
            }
        }

        private boolean hasValidTiming()
        {
            return startTimestamp != Long.MAX_VALUE && startTimestamp > 0L && endTimestamp >= startTimestamp;
        }

        private boolean requiresAwaitedLegRefresh()
        {
            return awaitedLegRefreshRequired || endTimestamp >= nextAwaitedRefreshEndTimestamp;
        }

        private void beginAwaitedLegRefresh()
        {
            awaitedLegRefreshRequired = false;
            nextAwaitedRefreshEndTimestamp = Long.MAX_VALUE;
        }

        private void considerAwaitedRefreshAt(long endTimestamp)
        {
            if(endTimestamp > this.endTimestamp)
            {
                nextAwaitedRefreshEndTimestamp = Math.min(nextAwaitedRefreshEndTimestamp, endTimestamp);
            }
        }

        private long discard()
        {
            long discardedSamples = audioSampleCount;
            audioBuffers.clear();
            voiceFrameFingerprints.clear();
            chunkSnapshots.clear();
            audioSampleCount = 0L;
            return discardedSamples;
        }

        private CompletedReceiverLeg preview()
        {
            return latestSnapshot != null ? build(false) : null;
        }

        private CompletedReceiverLeg complete()
        {
            return build(true);
        }

        private CompletedReceiverLeg build(boolean copyAudio)
        {
            long start = startTimestamp != Long.MAX_VALUE ? startTimestamp :
                latestSnapshot != null ? latestSnapshot.startTimestamp() : 0L;
            long end = Math.max(start, endTimestamp);
            VoiceCallQuality quality = addQuality(chunkSnapshots.values().stream()
                .map(AudioCallSnapshot::voiceCallQuality).toList());
            int bursts = chunkSnapshots.values().stream().mapToInt(AudioCallSnapshot::burstCount).sum();
            return new CompletedReceiverLeg(latestSnapshot,
                copyAudio ? List.copyOf(audioBuffers) : List.of(),
                List.copyOf(voiceFrameFingerprints), start, end, bursts,
                quality, audioSampleCount, ingressLoss, audioTruncated,
                destinationIdentity(latestSnapshot), sourceIdentity(latestSnapshot),
                latestSnapshot.callEncryptionEvidence());
        }
    }

    private static final class CompletedReceiverLeg
    {
        private final AudioCallSnapshot snapshot;
        private List<float[]> audioBuffers;
        private final List<TimestampedVoiceFingerprint> voiceFrameFingerprints;
        private final long startTimestamp;
        private final long endTimestamp;
        private final int burstCount;
        private final VoiceCallQuality quality;
        private final long audioSampleCount;
        private final boolean ingressLoss;
        private final boolean audioTruncated;
        private final DestinationIdentity destinationIdentity;
        private final SourceIdentity sourceIdentity;
        private final CallEncryptionEvidence encryptionEvidence;
        private final PreliminaryLegScope preliminaryScope;
        private FingerprintIndex voiceFingerprintIndex;

        private CompletedReceiverLeg(AudioCallSnapshot snapshot, List<float[]> audioBuffers,
                                     List<TimestampedVoiceFingerprint> voiceFrameFingerprints,
                                     long startTimestamp, long endTimestamp, int burstCount,
                                     VoiceCallQuality quality, long audioSampleCount, boolean ingressLoss,
                                     boolean audioTruncated, DestinationIdentity destinationIdentity,
                                     SourceIdentity sourceIdentity, CallEncryptionEvidence encryptionEvidence)
        {
            this.snapshot = snapshot;
            this.audioBuffers = audioBuffers;
            this.voiceFrameFingerprints = voiceFrameFingerprints;
            this.startTimestamp = startTimestamp;
            this.endTimestamp = endTimestamp;
            this.burstCount = burstCount;
            this.quality = quality;
            this.audioSampleCount = audioSampleCount;
            this.ingressLoss = ingressLoss;
            this.audioTruncated = audioTruncated;
            this.destinationIdentity = destinationIdentity;
            this.sourceIdentity = sourceIdentity;
            this.encryptionEvidence = encryptionEvidence;
            preliminaryScope = PreliminaryLegScope.from(snapshot, destinationIdentity, sourceIdentity);
        }

        private boolean hasValidTiming()
        {
            return startTimestamp > 0L && endTimestamp >= startTimestamp;
        }

        private void releaseCoordinatorReferences()
        {
            audioBuffers = List.of();
        }

        private FingerprintIndex voiceFingerprintIndex()
        {
            if(voiceFingerprintIndex == null)
            {
                voiceFingerprintIndex = FingerprintIndex.create(voiceFrameFingerprints);
            }

            return voiceFingerprintIndex;
        }
    }

    private record TimestampedVoiceFingerprint(long fingerprint, long carrierTimestamp)
    {
    }

    /** Worker-owned bounded timestamp index for content proof. */
    private static final class FingerprintIndex
    {
        private static final FingerprintIndex EMPTY = new FingerprintIndex(Map.of(), 0, 0L, 0L);
        private final Map<Long,LongTimestamps> timestamps;
        private final int frameCount;
        private final long earliestTimestamp;
        private final long latestTimestamp;

        private FingerprintIndex(Map<Long,LongTimestamps> timestamps, int frameCount, long earliestTimestamp,
                                 long latestTimestamp)
        {
            this.timestamps = timestamps;
            this.frameCount = frameCount;
            this.earliestTimestamp = earliestTimestamp;
            this.latestTimestamp = latestTimestamp;
        }

        private static FingerprintIndex create(List<TimestampedVoiceFingerprint> fingerprints)
        {
            if(fingerprints == null || fingerprints.isEmpty())
            {
                return EMPTY;
            }

            Map<Long,LongTimestamps> timestamps = new HashMap<>();
            int frameCount = 0;
            long earliestTimestamp = Long.MAX_VALUE;
            long latestTimestamp = 0L;

            for(TimestampedVoiceFingerprint fingerprint : fingerprints)
            {
                if(fingerprint != null && fingerprint.fingerprint() != 0L &&
                    fingerprint.carrierTimestamp() > 0L)
                {
                    frameCount++;
                    earliestTimestamp = Math.min(earliestTimestamp, fingerprint.carrierTimestamp());
                    latestTimestamp = Math.max(latestTimestamp, fingerprint.carrierTimestamp());
                    timestamps.computeIfAbsent(fingerprint.fingerprint(), ignored -> new LongTimestamps())
                        .add(fingerprint.carrierTimestamp());
                }
            }

            timestamps.values().forEach(LongTimestamps::sort);
            return timestamps.isEmpty() ? EMPTY : new FingerprintIndex(timestamps, frameCount,
                earliestTimestamp, latestTimestamp);
        }

        private int distinctCount()
        {
            return timestamps.size();
        }

        private Set<Map.Entry<Long,LongTimestamps>> entries()
        {
            return timestamps.entrySet();
        }

        private LongTimestamps timestamps(Long fingerprint)
        {
            return timestamps.get(fingerprint);
        }

        private int frameCount()
        {
            return frameCount;
        }

        private long earliestTimestamp()
        {
            return earliestTimestamp;
        }

        private long latestTimestamp()
        {
            return latestTimestamp;
        }

        private long timestampSpan()
        {
            return frameCount > 0 ? Math.max(0L, latestTimestamp - earliestTimestamp) : 0L;
        }
    }

    /** Small primitive timestamp list; total entries cannot exceed the per-leg fingerprint cap. */
    private static final class LongTimestamps
    {
        private long[] values = new long[4];
        private int size;

        private void add(long value)
        {
            if(size == values.length)
            {
                values = Arrays.copyOf(values, values.length * 2);
            }

            values[size++] = value;
        }

        private int size()
        {
            return size;
        }

        private long get(int index)
        {
            return values[index];
        }

        private void sort()
        {
            Arrays.sort(values, 0, size);
        }
    }

    private static final class ResolutionCohort
    {
        private final long cohortId;
        private final List<CompletedReceiverLeg> legs = new ArrayList<>();
        private final Set<CallLegId> awaitedLegIds = new HashSet<>();
        private final List<MergeEvidenceContribution> mergeEvidence = new ArrayList<>();
        private final EvidenceAccumulator rejectedEvidence = new EvidenceAccumulator();
        private final Map<CallLegId,EvidenceAccumulator> rejectedEvidenceByMember = new HashMap<>();
        private final long createdNanos;
        private final long activeLegWaitCeilingNanos;
        private long settleDeadlineNanos;
        private ScheduledFuture<?> deadlineFuture;

        private ResolutionCohort(long cohortId, CompletedReceiverLeg firstLeg, long now,
                                 long activeLegWaitCeilingNanos)
        {
            this.cohortId = cohortId;
            createdNanos = now;
            this.activeLegWaitCeilingNanos = activeLegWaitCeilingNanos;
            settleDeadlineNanos = now;
            legs.add(firstLeg);
        }

        private void recordMergeEvidence(Collection<MergeEvidenceContribution> contributions)
        {
            if(contributions != null)
            {
                for(MergeEvidenceContribution contribution : contributions)
                {
                    if(contribution != null)
                    {
                        mergeEvidence.add(contribution);
                    }
                }
            }
        }

        private void recordInitialRejectedEvidence(EvidenceAccumulator evidence, CallLegId memberId)
        {
            if(evidence == null || evidence.isEmpty())
            {
                return;
            }

            rejectedEvidence.add(evidence);

            if(memberId != null)
            {
                rejectedEvidenceByMember.computeIfAbsent(memberId, ignored -> new EvidenceAccumulator())
                    .add(evidence);
            }
        }

        private void removeMergeEvidence(CallLegId callLegId)
        {
            if(callLegId != null)
            {
                mergeEvidence.removeIf(contribution -> contribution.involves(callLegId));
            }
        }

        private void recordRejectedEvaluation(DuplicateEvaluation evaluation, CallLegId memberId)
        {
            if(evaluation == null)
            {
                return;
            }

            rejectedEvidence.add(evaluation);

            if(memberId != null)
            {
                rejectedEvidenceByMember.computeIfAbsent(memberId, ignored -> new EvidenceAccumulator())
                    .add(evaluation);
            }
        }

        private void removeRejectedEvidence(CallLegId memberId)
        {
            if(memberId != null)
            {
                rejectedEvidence.subtract(rejectedEvidenceByMember.remove(memberId));
            }
        }

        private LogicalCallDiagnosticEvidence diagnosticEvidence(boolean includeMergeEvidence)
        {
            EvidenceAccumulator combined = new EvidenceAccumulator();
            combined.add(rejectedEvidence);

            if(includeMergeEvidence)
            {
                for(MergeEvidenceContribution contribution : mergeEvidence)
                {
                    combined.addConfirmedDuplicate(contribution.proofs());
                }
            }

            return combined.snapshot();
        }
    }

    private record DestinationIdentity(ResolvedCallPolicy.DestinationIdentity identity)
    {
        private boolean matches(DestinationIdentity other)
        {
            return other != null && identity != null && identity.matches(other.identity);
        }
    }

    /** Immutable, compact subset needed only to decide whether an active leg could belong to a completed cohort. */
    private record PreliminaryLegScope(boolean eligible, DecoderType decoderType, long aliasListId,
                                       P25SiteIdentity siteIdentity, DestinationIdentity destinationIdentity,
                                       CallEncryptionState encryptionState, SourceIdentity sourceIdentity)
    {
        private static final PreliminaryLegScope INELIGIBLE = new PreliminaryLegScope(false, null, 0L, null,
            null, CallEncryptionState.UNKNOWN, null);

        private static PreliminaryLegScope from(AudioCallSnapshot snapshot, DestinationIdentity destinationIdentity,
                                                SourceIdentity sourceIdentity)
        {
            if(snapshot == null)
            {
                return INELIGIBLE;
            }

            CallLegSource source = snapshot.callLegSource();
            DecoderType decoderType = source != null ? source.decoderType() : null;
            long aliasListId = source != null ? source.aliasListId() : 0L;
            P25SiteIdentity siteIdentity = source != null ? source.p25SiteIdentity() : null;
            boolean eligible = source != null &&
                (decoderType == DecoderType.P25_PHASE1 || decoderType == DecoderType.P25_PHASE2) &&
                aliasListId > 0L && siteIdentity != null && destinationIdentity != null &&
                snapshot.isEncryptionKnown();
            return new PreliminaryLegScope(eligible, decoderType, aliasListId, siteIdentity,
                destinationIdentity, snapshot.encryptionState(), sourceIdentity);
        }

        private boolean compatible(PreliminaryLegScope other)
        {
            if(!eligible || other == null || !other.eligible || decoderType == null ||
                other.decoderType == null || aliasListId != other.aliasListId || siteIdentity == null ||
                other.siteIdentity == null || siteIdentity.wacn() != other.siteIdentity.wacn() ||
                siteIdentity.system() != other.siteIdentity.system() ||
                destinationIdentity == null || !destinationIdentity.matches(other.destinationIdentity) ||
                encryptionState != other.encryptionState)
            {
                return false;
            }

            return sourceIdentity == null || other.sourceIdentity == null ||
                sourceIdentity.matches(other.sourceIdentity);
        }
    }

    private record ExactDestination(FullyQualifiedTalkgroupIdentifier talkgroup, PatchGroupIdentifier patch)
    {
    }

    private record SourceIdentity(String protocol, int localAddress, Integer wacn, Integer system,
                                  Integer radio)
    {
        private boolean matches(SourceIdentity other)
        {
            if(other == null || protocol == null || other.protocol == null ||
                !protocol.equalsIgnoreCase(other.protocol))
            {
                return false;
            }

            if(wacn != null && system != null && radio != null && other.wacn != null &&
                other.system != null && other.radio != null)
            {
                return wacn.equals(other.wacn) && system.equals(other.system) && radio.equals(other.radio);
            }

            return localAddress > 0 && localAddress == other.localAddress;
        }
    }

    private record DuplicateEvaluation(LogicalCallPairOutcome outcome, List<LogicalCallMergeProof> mergeProofs,
                                       List<LogicalCallSeparationReason> separationReasons)
    {
        private DuplicateEvaluation
        {
            mergeProofs = mergeProofs != null ? List.copyOf(mergeProofs) : List.of();
            separationReasons = separationReasons != null ? List.copyOf(separationReasons) : List.of();
        }

        private static DuplicateEvaluation merged(LogicalCallMergeProof proof)
        {
            return merged(List.of(proof));
        }

        private static DuplicateEvaluation merged(List<LogicalCallMergeProof> proofs)
        {
            return new DuplicateEvaluation(LogicalCallPairOutcome.MERGED, proofs, List.of());
        }

        private static DuplicateEvaluation separated(LogicalCallSeparationReason reason)
        {
            return separated(List.of(reason));
        }

        private static DuplicateEvaluation separated(List<LogicalCallSeparationReason> reasons)
        {
            return new DuplicateEvaluation(LogicalCallPairOutcome.SEPARATED, List.of(), reasons);
        }

        private static DuplicateEvaluation failOpen(LogicalCallSeparationReason reason)
        {
            return failOpen(List.of(reason));
        }

        private static DuplicateEvaluation failOpen(List<LogicalCallSeparationReason> reasons)
        {
            return new DuplicateEvaluation(LogicalCallPairOutcome.FAIL_OPEN, List.of(), reasons);
        }
    }

    /** Worker-only positive evidence retained just long enough to remove an aborted cohort member exactly. */
    private record MergeEvidenceContribution(CallLegId firstLegId, CallLegId secondLegId,
                                             List<LogicalCallMergeProof> proofs)
    {
        private MergeEvidenceContribution
        {
            proofs = proofs != null ? List.copyOf(proofs) : List.of();
        }

        private boolean involves(CallLegId callLegId)
        {
            return callLegId != null && (callLegId.equals(firstLegId) || callLegId.equals(secondLegId));
        }
    }

    /**
     * Worker-owned fixed-cardinality counters.  Updates are primitive array increments; maps are created only once
     * when a completed decision is projected to the optional diagnostic sink.
     */
    private static final class EvidenceAccumulator
    {
        private final long[] mergeProofCounts = new long[LogicalCallMergeProof.values().length];
        private final long[] rejectionReasonCounts = new long[LogicalCallSeparationReason.values().length];
        private long confirmedDuplicatePairCount;
        private long separatedPairCount;
        private long uncertainPairCount;

        private void add(DuplicateEvaluation evaluation)
        {
            if(evaluation == null || evaluation.outcome == null)
            {
                return;
            }

            switch(evaluation.outcome)
            {
                case MERGED -> addConfirmedDuplicate(evaluation.mergeProofs);
                case SEPARATED -> {
                    separatedPairCount = saturatedIncrement(separatedPairCount);
                    addReasons(evaluation.separationReasons);
                }
                case FAIL_OPEN -> {
                    uncertainPairCount = saturatedIncrement(uncertainPairCount);
                    addReasons(evaluation.separationReasons);
                }
            }
        }

        private void addConfirmedDuplicate(Collection<LogicalCallMergeProof> proofs)
        {
            confirmedDuplicatePairCount = saturatedIncrement(confirmedDuplicatePairCount);

            if(proofs != null)
            {
                for(LogicalCallMergeProof proof : proofs)
                {
                    if(proof != null)
                    {
                        int index = proof.ordinal();
                        mergeProofCounts[index] = saturatedIncrement(mergeProofCounts[index]);
                    }
                }
            }
        }

        private void addReasons(Collection<LogicalCallSeparationReason> reasons)
        {
            if(reasons != null)
            {
                for(LogicalCallSeparationReason reason : reasons)
                {
                    if(reason != null)
                    {
                        int index = reason.ordinal();
                        rejectionReasonCounts[index] = saturatedIncrement(rejectionReasonCounts[index]);
                    }
                }
            }
        }

        private void add(EvidenceAccumulator other)
        {
            if(other == null)
            {
                return;
            }

            confirmedDuplicatePairCount = saturatedAdd(confirmedDuplicatePairCount,
                other.confirmedDuplicatePairCount);
            separatedPairCount = saturatedAdd(separatedPairCount, other.separatedPairCount);
            uncertainPairCount = saturatedAdd(uncertainPairCount, other.uncertainPairCount);

            for(int index = 0; index < mergeProofCounts.length; index++)
            {
                mergeProofCounts[index] = saturatedAdd(mergeProofCounts[index], other.mergeProofCounts[index]);
            }

            for(int index = 0; index < rejectionReasonCounts.length; index++)
            {
                rejectionReasonCounts[index] = saturatedAdd(rejectionReasonCounts[index],
                    other.rejectionReasonCounts[index]);
            }
        }

        private void subtract(EvidenceAccumulator other)
        {
            if(other == null)
            {
                return;
            }

            confirmedDuplicatePairCount = subtractNonnegative(confirmedDuplicatePairCount,
                other.confirmedDuplicatePairCount);
            separatedPairCount = subtractNonnegative(separatedPairCount, other.separatedPairCount);
            uncertainPairCount = subtractNonnegative(uncertainPairCount, other.uncertainPairCount);

            for(int index = 0; index < mergeProofCounts.length; index++)
            {
                mergeProofCounts[index] = subtractNonnegative(mergeProofCounts[index],
                    other.mergeProofCounts[index]);
            }

            for(int index = 0; index < rejectionReasonCounts.length; index++)
            {
                rejectionReasonCounts[index] = subtractNonnegative(rejectionReasonCounts[index],
                    other.rejectionReasonCounts[index]);
            }
        }

        private boolean isEmpty()
        {
            return confirmedDuplicatePairCount == 0L && separatedPairCount == 0L && uncertainPairCount == 0L;
        }

        private List<LogicalCallSeparationReason> reasons()
        {
            List<LogicalCallSeparationReason> reasons = new ArrayList<>();
            LogicalCallSeparationReason[] values = LogicalCallSeparationReason.values();

            for(int index = 0; index < values.length; index++)
            {
                if(rejectionReasonCounts[index] > 0L)
                {
                    reasons.add(values[index]);
                }
            }

            return List.copyOf(reasons);
        }

        private LogicalCallDiagnosticEvidence snapshot()
        {
            EnumMap<LogicalCallMergeProof,Long> proofCounts = new EnumMap<>(LogicalCallMergeProof.class);
            LogicalCallMergeProof[] proofs = LogicalCallMergeProof.values();

            for(int index = 0; index < proofs.length; index++)
            {
                if(mergeProofCounts[index] > 0L)
                {
                    proofCounts.put(proofs[index], mergeProofCounts[index]);
                }
            }

            EnumMap<LogicalCallSeparationReason,Long> reasonCounts =
                new EnumMap<>(LogicalCallSeparationReason.class);
            LogicalCallSeparationReason[] reasons = LogicalCallSeparationReason.values();

            for(int index = 0; index < reasons.length; index++)
            {
                if(rejectionReasonCounts[index] > 0L)
                {
                    reasonCounts.put(reasons[index], rejectionReasonCounts[index]);
                }
            }

            return new LogicalCallDiagnosticEvidence(confirmedDuplicatePairCount, separatedPairCount,
                uncertainPairCount, proofCounts, reasonCounts);
        }

        private static long saturatedIncrement(long value)
        {
            return value < Long.MAX_VALUE ? value + 1L : Long.MAX_VALUE;
        }

        private static long saturatedAdd(long first, long second)
        {
            return second > 0L && first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + Math.max(0L, second);
        }

        private static long subtractNonnegative(long first, long second)
        {
            return Math.max(0L, first - Math.min(first, Math.max(0L, second)));
        }
    }

    private record QualityScore(long expectedFrameCount, long missingAndConcealedCount,
                                double missingAndConcealedRate, long usableFrameCount,
                                long repeatedFrameCount, double repeatedRate, long fecErrorCount,
                                long fecProtectedBitCount, double normalizedFecRate)
    {
    }

    private record QualityComparison(int comparison, LogicalCallWinnerCriterion criterion,
                                     QualityScore firstScore, QualityScore secondScore)
    {
    }

    public record ResolverConfiguration(int ingressCapacity, int lifecycleIngressReserve,
                                        long settleQuietMilliseconds, long activeLegWaitCeilingMilliseconds,
                                        int maximumActiveLegs,
                                        int maximumCohortLegs, long maximumAudioSamplesPerLeg,
                                        long maximumRetainedAudioSamples)
    {
        public static final ResolverConfiguration DEFAULT = new ResolverConfiguration(DEFAULT_INGRESS_CAPACITY,
            DEFAULT_LIFECYCLE_INGRESS_RESERVE, DEFAULT_SETTLE_QUIET_MILLISECONDS,
            DEFAULT_ACTIVE_LEG_WAIT_CEILING_MILLISECONDS,
            DEFAULT_MAXIMUM_ACTIVE_LEGS, DEFAULT_MAXIMUM_COHORT_LEGS,
            DEFAULT_MAXIMUM_AUDIO_SAMPLES_PER_LEG, DEFAULT_MAXIMUM_RETAINED_AUDIO_SAMPLES);

        public ResolverConfiguration
        {
            if(ingressCapacity < 2 || Integer.bitCount(ingressCapacity) != 1 ||
                lifecycleIngressReserve < 1 || lifecycleIngressReserve >= ingressCapacity)
            {
                throw new IllegalArgumentException("Ingress capacity must be a power of two with a valid reserve");
            }

            settleQuietMilliseconds = Math.max(0L, settleQuietMilliseconds);
            activeLegWaitCeilingMilliseconds = Math.max(settleQuietMilliseconds,
                activeLegWaitCeilingMilliseconds);
            maximumActiveLegs = Math.max(1, maximumActiveLegs);
            maximumCohortLegs = Math.max(1, maximumCohortLegs);
            maximumAudioSamplesPerLeg = Math.max(1L, maximumAudioSamplesPerLeg);
            maximumRetainedAudioSamples = Math.max(maximumAudioSamplesPerLeg,
                maximumRetainedAudioSamples);
        }
    }

    public record CoordinatorQueueStatus(int ingressDepth, int regularIngressCapacity, int totalIngressCapacity,
                                         long acceptedIngress, long droppedIngress, long droppedLifecycle,
                                         long droppedOperations, long abortedCalls)
    {
    }
}
