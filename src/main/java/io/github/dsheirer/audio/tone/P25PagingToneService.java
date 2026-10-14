/*
 * *****************************************************************************
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.audio.tone;

import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.identifier.tone.AmbeTone;
import io.github.dsheirer.identifier.tone.DetectedPagingToneIdentifier;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

/**
 * Shared bounded native-tone observer. Producer handoffs retain an already-owned immutable event and perform only
 * primitive atomic accounting plus a lock-free offer. FFT, PCM copies, projection and listener callbacks run on the
 * dedicated worker. Loss invalidates that registration's epoch; incomplete audio never produces tone evidence.
 *
 * Dispose/rebind invalidates legacy deliveries and unfinished work without waiting for slow listeners. Accepted
 * completed global outcomes retain their original context; an already-claimed callback may finish. Ordinary call
 * completion/reset is FIFO and does not invalidate already-completed work. The detector never
 * updates decoder identifiers or call routing. A slow observer can lose evidence but cannot backpressure reception.
 * The worker parks indefinitely when idle. Native frames wake it in bounded batches; call/control events and lifecycle
 * cleanup wake it promptly. Off rejects PCM handoffs before retention/inspection and changes their runtime epoch
 * so old PCM evidence cannot become current again after re-enable. Existing encoded tone reporting remains available.
 */
public final class P25PagingToneService
{
    private static final P25PagingToneService INSTANCE = new P25PagingToneService();
    private final ConcurrentLinkedQueue<QueuedEvent> mQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger mQueuedEvents = new AtomicInteger();
    private final AtomicLong mRetainedBytes = new AtomicLong();
    private final AtomicLong mDroppedEvents = new AtomicLong();
    private final AtomicBoolean mDraining = new AtomicBoolean();
    private final AtomicReference<RuntimeEpoch> mRuntime = new AtomicReference<>(new RuntimeEpoch(true, 1L));
    private final RuntimeEpoch mEncodedEpoch = new RuntimeEpoch(true, 0L);
    private final ConcurrentHashMap<CallLegId,CachedOutcome> mCompleted = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CallLegId,Pending> mPending = new ConcurrentHashMap<>();
    private final ArrayDeque<CachedOutcome> mCompletedOrder = new ArrayDeque<>(); //worker-owned
    private final CopyOnWriteArrayList<CompletedListener> mCompletedListeners = new CopyOnWriteArrayList<>();
    private static final int MAXIMUM_COMPLETED = 512;
    private static final long COMPLETED_AGE_MILLISECONDS = TimeUnit.MINUTES.toMillis(30);
    //P1 carrier time is re-anchored from native-buffer timestamps at each LDU boundary. A small clock shift does
    //not imply missing PCM: every accepted voice frame still contributes exactly 160 samples. Keep this allowance
    //below a missing 20 ms frame, and continue invalidating larger gaps, duplicate/backward frames and queue loss.
    private static final long MINIMUM_PCM_FRAME_DELTA_MILLISECONDS = 15;
    private static final long MAXIMUM_PCM_FRAME_DELTA_MILLISECONDS = 25;
    private final AtomicBoolean mWakeRequested = new AtomicBoolean();
    private final AtomicBoolean mStopped = new AtomicBoolean();
    private final AtomicLong mWorkerWakeCount = new AtomicLong();
    private final AtomicLong mAnalyzedCalls = new AtomicLong();
    private final Map<CallKey, Context> mContexts = new HashMap<>(); //worker-owned
    private final int mMaximumQueuedEvents;
    private final long mMaximumRetainedBytes;
    private final int mMaximumActiveCalls;
    private final Function<List<float[]>, List<P25PagingToneDetector.DetectedTone>> mDetector;
    private final Thread mWorker;
    private final int mWakeBatchThreshold;

    private P25PagingToneService()
    {
        this(4096, 16L * 1024L * 1024L, 64, new P25PagingToneDetector()::detect, true);
    }

    /** Isolated manual-worker constructor for bounded observer tests. */
    P25PagingToneService(int maximumQueuedEvents, long maximumRetainedBytes, int maximumActiveCalls,
                          Function<List<float[]>, List<P25PagingToneDetector.DetectedTone>> detector)
    {
        this(maximumQueuedEvents, maximumRetainedBytes, maximumActiveCalls, detector, false);
    }

    /** Isolated threaded instance for lifecycle tests and benchmarks; shares no state with the default service. */
    P25PagingToneService(int maximumQueuedEvents, long maximumRetainedBytes, int maximumActiveCalls,
                          Function<List<float[]>, List<P25PagingToneDetector.DetectedTone>> detector,
                          boolean startWorker)
    {
        if(maximumQueuedEvents <= 0 || maximumRetainedBytes <= 0 || maximumActiveCalls <= 0)
            throw new IllegalArgumentException("Positive observer bounds are required");
        mMaximumQueuedEvents = maximumQueuedEvents;
        mMaximumRetainedBytes = maximumRetainedBytes;
        mMaximumActiveCalls = maximumActiveCalls;
        mDetector = java.util.Objects.requireNonNull(detector);
        mWakeBatchThreshold = Math.min(128, Math.max(1, maximumQueuedEvents / 2));
        if(startWorker)
        {
            mWorker = new Thread(this::work, "p25-paging-tone-observer");
            mWorker.setDaemon(true);
            mWorker.setPriority(Thread.MIN_PRIORITY);
            mWorker.start();
        }
        else
        {
            mWorker = null;
        }
    }

    public static P25PagingToneService getInstance() { return INSTANCE; }

    public Registration register() { return new Registration(this, 0, false); }

    /** Module-owned PCM observation stays available independently of the optional receiver-message audience. */
    public Registration registerGlobalPcm() { return new Registration(this, 0, true); }

    /** Qualification is inherited from the audio producer: P2 three frames, DMR/NXDN one frame. */
    public Registration registerEncoded(int minimumToneFrames)
    {
        if(minimumToneFrames != 1 && minimumToneFrames != 3) throw new IllegalArgumentException("Existing codec qualification required");
        return new Registration(this, minimumToneFrames, true);
    }

    /** Cheap nonwaiting lookup; Off invalidates only PCM results, not encoded metadata. */
    public CompletedToneSequence lookupCompleted(AudioCallSnapshot snapshot)
    {
        if(snapshot == null) return null;
        CachedOutcome cached = mCompleted.get(snapshot.callLegId());
        return cached != null && System.currentTimeMillis() - cached.created < COMPLETED_AGE_MILLISECONDS &&
            cachedCurrent(cached) && cached.sequence.matches(snapshot) ? cached.sequence : null;
    }

    /** The logical aggregate's times are not native-audio times; only the winning native snapshot may match. */
    public CompletedToneSequence lookupCompleted(CompletedAudioCall call)
    {
        return call != null ? lookupCompleted(call.nativeAudioSnapshot()) : null;
    }

    /** Recording worker only. A decoder/UI callback must use nonwaiting lookup instead. */
    public CompletedToneSequence awaitCompleted(AudioCallSnapshot snapshot, long milliseconds)
    {
        if(milliseconds < 0 || milliseconds > 500) throw new IllegalArgumentException("Recording tone wait bound is 500 ms");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds);
        CompletedToneSequence available = lookupCompleted(snapshot);
        if(available != null || snapshot == null || milliseconds == 0) return available;
        var decoder = snapshot.callLegSource().decoderType();
        boolean pcm = decoder == io.github.dsheirer.module.decode.DecoderType.P25_PHASE1 ||
            decoder == io.github.dsheirer.module.decode.DecoderType.P25_CONVENTIONAL;
        boolean encoded = decoder == io.github.dsheirer.module.decode.DecoderType.P25_PHASE2 ||
            decoder == io.github.dsheirer.module.decode.DecoderType.DMR || decoder == io.github.dsheirer.module.decode.DecoderType.NXDN;
        if(!snapshot.complete() || !pcm && !encoded || pcm && !isEnabled()) return null;
        Pending pending = mPending.get(snapshot.callLegId());
        try
        {
            if(pending == null)
            {
                if(mWorker == null) return null; //A manual test worker cannot execute a FIFO barrier by itself.
                //The recording worker can beat the tone worker to CALL_CREATED. The accepted completed marker
                //precedes this FIFO barrier; no decoder waits and the total bound remains500 ms.
                if(!awaitWorkerDrain(Math.max(0,deadline - System.nanoTime()),TimeUnit.NANOSECONDS)) return null;
                return lookupCompleted(snapshot);
            }
            if(!pending.matches(snapshot) || !globalCurrent(pending.registration,pending.binding,pending.epoch,pending.seal)) return null;
            CompletedToneSequence completed = pending.future.get(Math.max(0,deadline - System.nanoTime()),TimeUnit.NANOSECONDS);
            return completed != null && lookupCompleted(snapshot) == completed ? completed : null;
        }
        catch(ExecutionException | TimeoutException unavailable) { return null; }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); return null; }
    }

    public CompletedToneSequence awaitCompleted(AudioCallSnapshot snapshot,long timeout,TimeUnit unit)
    {
        java.util.Objects.requireNonNull(unit);
        if(timeout < 0 || unit.toNanos(timeout) > TimeUnit.MILLISECONDS.toNanos(500))
            throw new IllegalArgumentException("Recording tone wait bound is 500 ms");
        return awaitCompleted(snapshot,unit.toMillis(timeout));
    }

    /** Application-owned listeners only, not one per browser. Close never waits for a claimed callback. */
    public synchronized AutoCloseable addCompletedToneListener(Listener<CompletedToneSequence> listener)
    {
        if(mCompletedListeners.size() >= 8) throw new IllegalStateException("Completed-tone listener bound reached");
        CompletedListener registration = new CompletedListener(java.util.Objects.requireNonNull(listener));
        mCompletedListeners.add(registration);
        return () -> { registration.state.set(2); mCompletedListeners.remove(registration); };
    }

    /** Shutdown owner only: quiesce audio producers first. Never call from a decoder callback. */
    public boolean awaitWorkerDrain(long timeout, TimeUnit unit) throws InterruptedException
    {
        CountDownLatch barrier = new CountDownLatch(1);
        if(mQueuedEvents.incrementAndGet() > mMaximumQueuedEvents)
        {
            mQueuedEvents.decrementAndGet(); return false;
        }
        mQueue.offer(new QueuedEvent(null,null,null,null,false,0,null,barrier,null));
        wakeWorker();
        return barrier.await(Math.max(0,timeout),unit);
    }

    public boolean isEnabled() { return mRuntime.get().enabled; }

    /** Runtime default is On. A real transition invalidates every prior analysis epoch without waiting for work. */
    public void setEnabled(boolean enabled)
    {
        RuntimeEpoch previous;
        RuntimeEpoch next;
        do
        {
            previous = mRuntime.get();
            if(previous.enabled == enabled) return;
            next = new RuntimeEpoch(enabled, previous.generation + 1L);
        }
        while(!mRuntime.compareAndSet(previous, next));
        previous.invalidate();
        wakeWorker();
    }

    /** Token for independent raw-PCM analysis; obtain before work and require the same enabled epoch to publish. */
    public long generation() { return mRuntime.get().generation; }

    public boolean isCurrentGeneration(long generation)
    {
        RuntimeEpoch epoch = mRuntime.get();
        return epoch.enabled && epoch.valid() && epoch.generation == generation;
    }

    public long droppedEvents() { return mDroppedEvents.get(); }

    int queuedEvents() { return mQueuedEvents.get(); }

    long retainedBytes() { return mRetainedBytes.get(); }

    long workerThreadId() { return mWorker != null ? mWorker.threadId() : -1L; }

    long workerWakeCount() { return mWorkerWakeCount.get(); }

    long analyzedCalls() { return mAnalyzedCalls.get(); }

    int wakeBatchThreshold() { return mWakeBatchThreshold; }

    /** Isolated instances only: callers must quiesce producers before stopping their benchmark/test worker. */
    void stopWorker()
    {
        setEnabled(false);
        mEncodedEpoch.invalidate();
        mStopped.set(true);
        if(mWorker != null) LockSupport.unpark(mWorker);
        else drain();
    }

    boolean awaitWorkerStopped(long milliseconds) throws InterruptedException
    {
        if(mWorker == null) return true;
        mWorker.join(milliseconds);
        return !mWorker.isAlive();
    }

    private boolean current(RuntimeEpoch epoch)
    {
        return epoch != null && epoch.enabled && epoch.valid() && (mRuntime.get() == epoch || epoch == mEncodedEpoch);
    }

    private boolean globalCurrent(Registration registration,Binding binding,RuntimeEpoch epoch,CallSeal seal)
    {
        return current(epoch) && binding != null &&
            (seal != null ? seal.valid() && (seal.sealed() || !binding.dataLost.get() && registration.current(binding)) :
                !binding.dataLost.get() && registration.current(binding));
    }

    private boolean cachedCurrent(CachedOutcome cached)
    {
        return current(cached.epoch) && cached.sealState.get() == 1;
    }

    private void wakeWorker()
    {
        if(mWorker != null && mWakeRequested.compareAndSet(false, true)) LockSupport.unpark(mWorker);
    }

    private void work()
    {
        while(!mStopped.get())
        {
            while(!mWakeRequested.get() && !mStopped.get())
            {
                LockSupport.park(this);
                Thread.interrupted(); //A stray interruption must not turn an empty worker into a polling loop.
            }
            if(mStopped.get()) break;
            mWakeRequested.set(false);
            mWorkerWakeCount.incrementAndGet();
            drain();
            //A bounded drain can leave work under concurrent load. It must not depend on a future frame/timer.
            if(!mQueue.isEmpty()) wakeWorker();
        }
        drain();
    }

    private boolean offer(Registration registration, AudioCallEvent event, boolean reset, CallSeal seal)
    {
        RuntimeEpoch epoch = registration.encoded() ? mEncodedEpoch : mRuntime.get();
        if(!epoch.enabled || !epoch.valid()) return false;
        Binding binding = registration.mBinding.get();
        if(binding == null || !binding.valid()) return false;
        if(!reset && event.eventType() != AudioCallEventType.CALL_CREATED &&
            event.eventType() != AudioCallEventType.AUDIO_FRAME &&
            event.eventType() != AudioCallEventType.CALL_COMPLETED) return false;
        int samples = !reset ? event.audioFrameSampleCount() : 0;
        if(samples != 0 && samples != 160)
        {
            lose(registration, binding, seal);
            return false;
        }
        //Both modes retain the already-owned event while queued. Encoded processing releases this reservation
        //immediately; it neither copies the PCM nor transfers it into an active context.
        long bytes = samples * (long)Float.BYTES;
        if(mRetainedBytes.addAndGet(bytes) > mMaximumRetainedBytes)
        {
            mRetainedBytes.addAndGet(-bytes);
            lose(registration, binding, seal);
            return false;
        }
        int reservedQueueCount = mQueuedEvents.incrementAndGet();
        if(reservedQueueCount > mMaximumQueuedEvents)
        {
            mQueuedEvents.decrementAndGet();
            mRetainedBytes.addAndGet(-bytes);
            lose(registration, binding, seal);
            return false;
        }
        if(!current(epoch) || !registration.current(binding))
        {
            mQueuedEvents.decrementAndGet();
            mRetainedBytes.addAndGet(-bytes);
            wakeWorker();
            return false;
        }
        mQueue.offer(new QueuedEvent(registration, binding, epoch, event, reset, bytes,null,null,seal));
        //The final epoch check also wakes cleanup for an old offer that raced Off after the reservation check.
        if(reset || event.eventType() != AudioCallEventType.AUDIO_FRAME ||
            reservedQueueCount >= mWakeBatchThreshold || mQueuedEvents.get() >= mWakeBatchThreshold ||
            !current(epoch) || !registration.current(binding))
        {
            wakeWorker();
        }
        return true;
    }

    private void offerEncoded(Registration registration, AudioCallSnapshot snapshot, AmbeTone tone)
    {
        Binding binding = registration.mBinding.get();
        if(!registration.encoded() || snapshot == null || binding == null || !binding.valid() || mStopped.get()) return;
        CallSeal seal = registration.mCallSeal.get();
        if(seal == null || !seal.callId.equals(snapshot.callId())) { lose(registration,binding,seal); return; }
        int reservedQueueCount = mQueuedEvents.incrementAndGet();
        if(reservedQueueCount > mMaximumQueuedEvents)
        {
            mQueuedEvents.decrementAndGet(); lose(registration,binding,seal); return;
        }
        if(!registration.current(binding)) { mQueuedEvents.decrementAndGet(); wakeWorker(); return; }
        mQueue.offer(new QueuedEvent(registration,binding,mEncodedEpoch,null,false,0,new EncodedFrame(snapshot,tone),null,seal));
        if(reservedQueueCount >= mWakeBatchThreshold || mQueuedEvents.get() >= mWakeBatchThreshold ||
            !registration.current(binding) || !current(mEncodedEpoch)) wakeWorker();
    }

    private void lose(Registration registration, Binding binding, CallSeal seal)
    {
        mDroppedEvents.incrementAndGet();
        if(seal != null) seal.invalidate();
        registration.invalidate(binding);
    }

    /** All work here, including observer callbacks, is serialized on the service worker. */
    void drain()
    {
        if(!mDraining.compareAndSet(false, true)) return;
        try
        {
            mCompletedOrder.removeIf(cached -> {
                if(!cachedCurrent(cached))
                {
                    mCompleted.remove(cached.sequence.callLegId(),cached); return true;
                }
                return false;
            });
            mPending.entrySet().removeIf(entry -> {
                Pending pending = entry.getValue();
                if(!globalCurrent(pending.registration,pending.binding,pending.epoch,pending.seal))
                { pending.future.complete(null); return true; }
                return false;
            });
            Iterator<Map.Entry<CallKey, Context>> contexts = mContexts.entrySet().iterator();
            while(contexts.hasNext())
            {
                Map.Entry<CallKey, Context> context = contexts.next();
                if(!globalCurrent(context.getKey().registration(),context.getKey().binding(),context.getKey().epoch(),
                    context.getValue().pending.seal))
                {
                    release(context.getValue());
                    contexts.remove();
                }
            }
            for(int processed = 0; processed < mMaximumQueuedEvents; processed++)
            {
                QueuedEvent queued = mQueue.poll();
                if(queued == null) break;
                mQueuedEvents.decrementAndGet();
                boolean retained = false;
                try
                {
                    if(queued.barrier() != null) { queued.barrier().countDown(); continue; }
                    if(globalCurrent(queued.registration(),queued.binding(),queued.epoch(),queued.seal()))
                        retained = process(queued);
                }
                catch(RuntimeException ignored)
                {
                    lose(queued.registration(), queued.binding(), queued.seal());
                }
                finally
                {
                    if(!retained) mRetainedBytes.addAndGet(-queued.bytes());
                    if(queued.seal() != null && (queued.event() != null &&
                        queued.event().eventType() == AudioCallEventType.CALL_COMPLETED || !queued.seal().valid()))
                        queued.seal().clearLegacy();
                }
            }
        }
        finally
        {
            mDraining.set(false);
        }
    }

    private boolean process(QueuedEvent queued)
    {
        if(queued.reset())
        {
            Iterator<Map.Entry<CallKey, Context>> contexts = mContexts.entrySet().iterator();
            while(contexts.hasNext())
            {
                var context = contexts.next();
                if(context.getKey().registration() == queued.registration() && !context.getValue().pending.seal.sealed())
                {
                    release(context.getValue());
                    contexts.remove();
                }
            }
            return false;
        }
        if(queued.encodedFrame() != null)
        {
            EncodedFrame frame = queued.encodedFrame();
            CallKey key = new CallKey(queued.registration(),queued.binding(),queued.epoch(),frame.snapshot().callId());
            Context context = mContexts.get(key);
            if(context == null || context.samples == 0) return false;
            if(context.samples / 160 != context.encodedFrames + 1 ||
                !context.start.callLegId().equals(frame.snapshot().callLegId()))
                throw new IllegalArgumentException("Encoded metadata/frame context incomplete");
            context.encodedFrames++;
            AmbeTone tone = frame.tone();
            if(tone == AmbeTone.INVALID) throw new IllegalArgumentException("Conflicting encoded metadata");
            if(tone == null)
            {
                finishEncodedRun(context,queued.registration().minimumToneFrames);
            }
            else
            {
                if(context.encodedRun == null || context.encodedRun.tone != tone)
                {
                    finishEncodedRun(context,queued.registration().minimumToneFrames);
                    context.encodedRun = new EncodedRun(tone,(context.samples - 160) / 8L);
                }
                context.encodedRun.frames++;
            }
            return false;
        }
        AudioCallEvent event = queued.event();
        CallKey key = new CallKey(queued.registration(), queued.binding(), queued.epoch(), event.callId());
        if(event.eventType() == AudioCallEventType.CALL_CREATED)
        {
            //Each P1 module owns one unfinished call; a new call cannot inherit abandoned PCM.
            Iterator<Map.Entry<CallKey, Context>> existing = mContexts.entrySet().iterator();
            while(existing.hasNext())
            {
                var previous = existing.next();
                if(previous.getKey().registration() == queued.registration())
                {
                    release(previous.getValue());
                    existing.remove();
                }
            }
            if(mContexts.size() >= mMaximumActiveCalls)
            {
                lose(queued.registration(), queued.binding(), queued.seal());
                return false;
            }
            Pending pending = new Pending(event.snapshot(),queued.registration(),queued.binding(),queued.epoch(),queued.seal());
            mPending.put(event.snapshot().callLegId(),pending);
            mContexts.put(key, new Context(event.snapshot(),pending));
        }
        else if(event.eventType() == AudioCallEventType.AUDIO_FRAME)
        {
            Context context = mContexts.get(key);
            if(context == null) return false;
            long frameDelta = event.voiceFrameTimestamp() - context.lastFrameTimestamp;
            if(event.audioFrameSampleCount() != 160 || context.samples > P25PagingToneDetector.MAXIMUM_SAMPLES - 160 ||
                (!queued.registration().encoded() && context.lastFrameTimestamp != 0 &&
                    (frameDelta < MINIMUM_PCM_FRAME_DELTA_MILLISECONDS ||
                        frameDelta > MAXIMUM_PCM_FRAME_DELTA_MILLISECONDS)))
            {
                mContexts.remove(key);
                release(context);
                lose(queued.registration(), queued.binding(), queued.seal());
                return false;
            }
            if(queued.registration().encoded())
            {
                if(context.firstFrameTimestamp == 0) context.firstFrameTimestamp = event.voiceFrameTimestamp();
                context.lastFrameTimestamp = event.voiceFrameTimestamp();
                context.samples += 160;
                return false; //Encoded contexts never copy/store PCM; release the queued-event reservation now.
            }
            float[] audio = event.audioFrame(); //the producer's event owns the first PCM copy
            for(float sample: audio)
            {
                if(!Float.isFinite(sample))
                {
                    mContexts.remove(key);
                    release(context);
                    lose(queued.registration(), queued.binding(), queued.seal());
                    return false;
                }
            }
            if(context.firstFrameTimestamp == 0) context.firstFrameTimestamp = event.voiceFrameTimestamp();
            context.lastFrameTimestamp = event.voiceFrameTimestamp();
            context.buffers.add(audio);
            context.samples += audio.length;
            context.bytes += queued.bytes();
            return true; //retained-byte reservation now belongs to the active context
        }
        else if(event.eventType() == AudioCallEventType.CALL_COMPLETED)
        {
            Context context = mContexts.remove(key);
            if(context == null) return false;
            if(queued.registration().encoded())
            {
                if(context.samples / 160 != context.encodedFrames)
                    throw new IllegalArgumentException("Encoded call ended with missing frame metadata");
                finishEncodedRun(context,queued.registration().minimumToneFrames);
                CompletedToneSequence sequence = new CompletedToneSequence(event.snapshot(),
                    queued.registration().minimumToneFrames == 3 ? Math.max(0,context.firstFrameTimestamp - 20) : 0,
                    CompletedToneSequence.Source.ENCODED,
                    CompletedToneSequence.Method.AMBE_TONE_V1,context.encodedTones);
                publishCompleted(queued,sequence);
                return false;
            }
            List<P25PagingToneDetector.DetectedTone> tones;
            try
            {
                if(!current(queued.epoch())) return false;
                tones = mDetector.apply(context.buffers);
                mAnalyzedCalls.incrementAndGet();
            }
            finally
            {
                releasePcm(context);
            }
            List<CompletedToneSequence.Element> elements = tones.stream().map(tone -> new CompletedToneSequence.Element(
                List.of((int)Math.round(tone.frequencyHz() * 1000)),null,tone.offsetMilliseconds(),
                tone.durationMilliseconds(),tone.confidence())).toList();
            publishCompleted(queued,new CompletedToneSequence(event.snapshot(),Math.max(0,context.firstFrameTimestamp - 20),
                CompletedToneSequence.Source.DECODED_AUDIO,CompletedToneSequence.Method.P25_PCM_TONE_V1,elements));
            Binding legacy = queued.registration().legacyBinding(queued);
            if(!tones.isEmpty() && current(queued.epoch()) && queued.registration().legacyCurrent(legacy) &&
                queued.epoch().claim())
            {
                try
                {
                    if(legacy.claim())
                    {
                        try
                        {
                            AudioCallSnapshot snapshot = event.snapshot();
                            legacy.listener.receive(new DecodedPagingToneMessage(snapshot.callId(),
                                snapshot.callLegId(), snapshot.callLegSource(), context.start.startTimestamp(),
                                context.firstFrameTimestamp, snapshot.lastActivityTimestamp(),
                                new DetectedPagingToneIdentifier(tones)));
                        }
                        finally
                        {
                            legacy.finish();
                        }
                    }
                }
                finally
                {
                    queued.epoch().finish();
                }
            }
        }
        return false;
    }

    private static void finishEncodedRun(Context context, int minimumFrames)
    {
        EncodedRun run = context.encodedRun;
        context.encodedRun = null;
        if(run != null && run.frames >= minimumFrames)
        {
            if(context.encodedTones.size() >= CompletedToneSequence.MAXIMUM_TONES)
                throw new IllegalArgumentException("Incomplete encoded tone sequence exceeds bound");
            context.encodedTones.add(new CompletedToneSequence.Element(EncodedToneMetadata.frequencies(run.tone),
                run.tone.name(),run.offsetMilliseconds,run.frames * 20L,null));
        }
    }

    private void publishCompleted(QueuedEvent queued, CompletedToneSequence sequence)
    {
        if(!globalCurrent(queued.registration(),queued.binding(),queued.epoch(),queued.seal()) || !queued.epoch().claim()) return;
        try
        {
            {
                long now = System.currentTimeMillis();
                CachedOutcome cached = new CachedOutcome(sequence,queued.epoch(),queued.seal().state,now);
                mCompleted.put(sequence.callLegId(),cached); mCompletedOrder.addLast(cached);
                while(!mCompletedOrder.isEmpty() && (mCompletedOrder.size() > MAXIMUM_COMPLETED ||
                    now - mCompletedOrder.getFirst().created >= COMPLETED_AGE_MILLISECONDS))
                {
                    CachedOutcome expired = mCompletedOrder.removeFirst();
                    mCompleted.remove(expired.sequence.callLegId(),expired);
                }
                Pending pending = mPending.remove(sequence.callLegId());
                if(pending != null) pending.future.complete(sequence);
                for(CompletedListener listener: mCompletedListeners)
                {
                    if(!globalCurrent(queued.registration(),queued.binding(),queued.epoch(),queued.seal())) break;
                    if(listener.state.compareAndSet(0,1))
                    {
                        try { listener.listener.receive(sequence); }
                        catch(RuntimeException ignored) { /*One optional observer cannot suppress other outcomes.*/ }
                        finally { listener.state.compareAndSet(1,0); }
                    }
                }
            }
        }
        finally { queued.epoch().finish(); }
    }

    private void release(Context context)
    {
        mPending.remove(context.start.callLegId(),context.pending);
        context.pending.future.complete(null);
        context.pending.seal.clearLegacy();
        releasePcm(context);
    }

    private void releasePcm(Context context)
    {
        mRetainedBytes.addAndGet(-context.bytes);
        context.buffers.clear();
        context.bytes = 0L;
    }

    private record QueuedEvent(Registration registration, Binding binding, RuntimeEpoch epoch, AudioCallEvent event,
                                boolean reset, long bytes, EncodedFrame encodedFrame, CountDownLatch barrier, CallSeal seal) {}
    private record EncodedFrame(AudioCallSnapshot snapshot, AmbeTone tone) {}
    //No module/registration/listener graph is retained by the completed cache, even during a slow callback.
    private record CachedOutcome(CompletedToneSequence sequence, RuntimeEpoch epoch, AtomicInteger sealState, long created) {}
    private static final class CallSeal
    {
        private final AudioCallId callId;
        private volatile Binding legacyBinding;
        private final AtomicInteger state = new AtomicInteger(); //0 unfinished,1 accepted completion,2 invalid
        private CallSeal(AudioCallId id,Binding legacy) { callId = id; legacyBinding = legacy; }
        private void complete() { state.compareAndSet(0,1); }
        private boolean sealed() { return state.get() == 1; }
        private boolean valid() { return state.get() != 2; }
        private void discardUnfinished() { if(state.compareAndSet(0,2)) clearLegacy(); }
        private void invalidate() { state.set(2); clearLegacy(); }
        private void clearLegacy() { legacyBinding = null; }
    }
    private static final class Pending
    {
        private final AudioCallSnapshot start;
        private final Registration registration;
        private final Binding binding;
        private final RuntimeEpoch epoch;
        private final CallSeal seal;
        private final CompletableFuture<CompletedToneSequence> future = new CompletableFuture<>();
        private Pending(AudioCallSnapshot snapshot,Registration owner,Binding scope,RuntimeEpoch generation,CallSeal completion)
        { start = snapshot; registration = owner; binding = scope; epoch = generation; seal = completion; }
        private boolean matches(AudioCallSnapshot snapshot)
        {
            return snapshot.complete() && start.callId().equals(snapshot.callId()) && start.callLegId().equals(snapshot.callLegId()) &&
                start.startTimestamp() == snapshot.startTimestamp() && start.callLegSource().equals(snapshot.callLegSource());
        }
    }
    private static final class CompletedListener
    {
        private final Listener<CompletedToneSequence> listener;
        private final AtomicInteger state = new AtomicInteger();
        private CompletedListener(Listener<CompletedToneSequence> consumer) { listener = consumer; }
    }
    private record CallKey(Registration registration, Binding binding, RuntimeEpoch epoch, AudioCallId callId) {}

    private static final class RuntimeEpoch
    {
        private final boolean enabled;
        private final long generation;
        private final AtomicInteger deliveryState = new AtomicInteger(); //0 available, 1 claimed callback, 2 invalid
        private RuntimeEpoch(boolean enabled, long generation) { this.enabled = enabled; this.generation = generation; }
        private boolean valid() { return deliveryState.get() != 2; }
        private boolean claim() { return deliveryState.compareAndSet(0, 1); }
        private void finish() { deliveryState.compareAndSet(1, 0); }
        private void invalidate() { deliveryState.set(2); }
    }

    private static final class Context
    {
        private final AudioCallSnapshot start;
        private final Pending pending;
        private final List<float[]> buffers = new ArrayList<>();
        private long firstFrameTimestamp;
        private long lastFrameTimestamp;
        private int samples;
        private long bytes;
        private final List<CompletedToneSequence.Element> encodedTones = new ArrayList<>();
        private EncodedRun encodedRun;
        private int encodedFrames;
        private Context(AudioCallSnapshot snapshot,Pending completion) { start = snapshot; pending = completion; }
    }

    private static final class EncodedRun
    {
        private final AmbeTone tone;
        private final long offsetMilliseconds;
        private int frames;
        private EncodedRun(AmbeTone value,long offset) { tone = value; offsetMilliseconds = offset; }
    }

    private static final class Binding
    {
        private final Listener<IMessage> listener;
        private final AtomicBoolean dataLost = new AtomicBoolean();
        private final AtomicInteger state = new AtomicInteger(); //0 available, 1 claimed callback, 2 invalid
        private Binding(Listener<IMessage> listener) { this.listener = listener; }
        private boolean valid() { return state.get() != 2; }
        private boolean claim() { return state.compareAndSet(0, 1); }
        private void finish() { state.compareAndSet(1, 0); }
        private void invalidate() { state.set(2); }
    }

    public static final class Registration implements AutoCloseable
    {
        private final P25PagingToneService service;
        private final int minimumToneFrames;
        private final boolean globalLifetime;
        private final AtomicReference<Binding> mLegacyBinding = new AtomicReference<>();
        private final AtomicReference<Binding> mBinding = new AtomicReference<>();
        private final AtomicBoolean mClosed = new AtomicBoolean();
        private final AtomicReference<CallSeal> mCallSeal = new AtomicReference<>();
        private Registration(P25PagingToneService owner,int minimumFrames,boolean global)
        {
            service = owner; minimumToneFrames = minimumFrames; globalLifetime = global;
            if(global) mBinding.set(new Binding(message -> {}));
        }
        private boolean encoded() { return minimumToneFrames != 0; }

        /** Lifecycle bind; replaces the epoch without waiting for an already-claimed observer callback. */
        public void setMessageListener(Listener<IMessage> listener)
        {
            if(globalLifetime) { setLegacyMessageListener(listener); return; }
            Binding next = listener != null && !mClosed.get() ? new Binding(listener) : null;
            Binding previous = mBinding.getAndSet(next);
            if(previous != null) previous.invalidate();
            CallSeal active = mCallSeal.get();
            if(active != null) active.discardUnfinished();
            if(mClosed.get())
            {
                previous = mBinding.getAndSet(null);
                if(previous != null) previous.invalidate();
            }
            service.wakeWorker();
        }

        /** Optional receiver-message binding. Changes never disable global call observation. */
        public void setLegacyMessageListener(Listener<IMessage> listener)
        {
            if(!globalLifetime) { setMessageListener(listener); return; }
            Binding next = listener != null && !mClosed.get() ? new Binding(listener) : null;
            Binding previous = mLegacyBinding.getAndSet(next);
            if(previous != null) previous.invalidate();
            if(mClosed.get())
            {
                previous = mLegacyBinding.getAndSet(null);
                if(previous != null) previous.invalidate();
            }
        }

        private Binding legacyBinding(QueuedEvent queued)
        {
            return globalLifetime ? queued.seal().legacyBinding : queued.binding();
        }

        private boolean legacyCurrent(Binding binding)
        {
            return binding != null && !mClosed.get() && binding.valid() &&
                (globalLifetime ? mLegacyBinding.get() == binding : current(binding));
        }

        public void receive(AudioCallEvent event)
        {
            if(event == null || mClosed.get()) return;
            if(event.eventType() != AudioCallEventType.CALL_CREATED && event.eventType() != AudioCallEventType.AUDIO_FRAME &&
                event.eventType() != AudioCallEventType.CALL_COMPLETED) return;
            if(!encoded() && !service.isEnabled()) return;
            CallSeal seal = mCallSeal.get();
            if(event.eventType() == AudioCallEventType.CALL_CREATED)
            {
                CallSeal fresh = new CallSeal(event.callId(),globalLifetime ? mLegacyBinding.get() : mBinding.get());
                CallSeal previous = mCallSeal.getAndSet(fresh);
                if(previous != null) previous.discardUnfinished();
                seal = fresh;
            }
            if(seal == null || !seal.callId.equals(event.callId())) return;
            if(event.eventType() == AudioCallEventType.CALL_COMPLETED) seal.complete();
            if(!service.offer(this,event,false,seal)) seal.invalidate();
        }

        /** Producer-owned call snapshot and one native-frame symbol; no PCM copy/projection/FFT. */
        public void receiveEncodedFrame(AudioCallSnapshot snapshot,AmbeTone tone)
        {
            service.offerEncoded(this,snapshot,tone);
        }

        /** FIFO discard of unfinished context; completed messages already queued retain their original epoch. */
        public void resetUnfinishedCall()
        {
            CallSeal active = mCallSeal.get();
            if(active != null) active.discardUnfinished();
            service.offer(this,null,true,null);
        }

        private boolean current(Binding binding) { return mBinding.get() == binding && binding.valid(); }

        private void invalidate(Binding failed)
        {
            failed.dataLost.set(true);
            Binding next = !mClosed.get() ? new Binding(failed.listener) : null;
            mBinding.compareAndSet(failed, next);
            failed.invalidate();
            service.wakeWorker();
        }

        @Override
        public void close()
        {
            mClosed.set(true);
            CallSeal active = mCallSeal.get();
            if(active != null) active.discardUnfinished();
            Binding previous = mBinding.getAndSet(null);
            if(previous != null) previous.invalidate();
            previous = mLegacyBinding.getAndSet(null);
            if(previous != null) previous.invalidate();
            service.wakeWorker();
        }
    }
}
