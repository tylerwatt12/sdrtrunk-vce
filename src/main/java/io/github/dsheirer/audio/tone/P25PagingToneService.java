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
import io.github.dsheirer.identifier.tone.DetectedPagingToneIdentifier;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

/**
 * Shared bounded raw-audio observer. Producer handoffs retain an already-owned immutable event and perform only
 * primitive atomic accounting plus a lock-free offer. FFT, PCM copies, projection and listener callbacks run on the
 * dedicated worker. Loss invalidates that registration's epoch; incomplete audio never produces tone evidence.
 *
 * Dispose/rebind invalidates queued deliveries without waiting for slow listeners. An already-claimed callback may
 * finish. Ordinary call completion/reset is FIFO and does not invalidate already-completed work. The detector never
 * updates decoder identifiers or call routing. A slow observer can lose evidence but cannot backpressure reception.
 * The worker parks indefinitely when idle. Native frames wake it in bounded batches; call/control events and lifecycle
 * cleanup wake it promptly. Off rejects new handoffs before retention or PCM inspection and changes the runtime epoch
 * so an old queued/in-flight result cannot become current again after re-enable.
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

    public Registration register() { return new Registration(this); }

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
        return mRuntime.get() == epoch && epoch.enabled && epoch.valid();
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

    private void offer(Registration registration, AudioCallEvent event, boolean reset)
    {
        RuntimeEpoch epoch = mRuntime.get();
        if(!epoch.enabled || !epoch.valid()) return;
        Binding binding = registration.mBinding.get();
        if(binding == null || !binding.valid()) return;
        if(!reset && event.eventType() != AudioCallEventType.CALL_CREATED &&
            event.eventType() != AudioCallEventType.AUDIO_FRAME &&
            event.eventType() != AudioCallEventType.CALL_COMPLETED) return;
        int samples = !reset ? event.audioFrameSampleCount() : 0;
        if(samples != 0 && samples != 160)
        {
            lose(registration, binding);
            return;
        }
        long bytes = samples * (long)Float.BYTES;
        if(mRetainedBytes.addAndGet(bytes) > mMaximumRetainedBytes)
        {
            mRetainedBytes.addAndGet(-bytes);
            lose(registration, binding);
            return;
        }
        int reservedQueueCount = mQueuedEvents.incrementAndGet();
        if(reservedQueueCount > mMaximumQueuedEvents)
        {
            mQueuedEvents.decrementAndGet();
            mRetainedBytes.addAndGet(-bytes);
            lose(registration, binding);
            return;
        }
        if(!current(epoch) || !registration.current(binding))
        {
            mQueuedEvents.decrementAndGet();
            mRetainedBytes.addAndGet(-bytes);
            wakeWorker();
            return;
        }
        mQueue.offer(new QueuedEvent(registration, binding, epoch, event, reset, bytes));
        //The final epoch check also wakes cleanup for an old offer that raced Off after the reservation check.
        if(reset || event.eventType() != AudioCallEventType.AUDIO_FRAME ||
            reservedQueueCount >= mWakeBatchThreshold || mQueuedEvents.get() >= mWakeBatchThreshold ||
            !current(epoch) || !registration.current(binding))
        {
            wakeWorker();
        }
    }

    private void lose(Registration registration, Binding binding)
    {
        mDroppedEvents.incrementAndGet();
        registration.invalidate(binding);
    }

    /** All work here, including observer callbacks, is serialized on the service worker. */
    void drain()
    {
        if(!mDraining.compareAndSet(false, true)) return;
        try
        {
            Iterator<Map.Entry<CallKey, Context>> contexts = mContexts.entrySet().iterator();
            while(contexts.hasNext())
            {
                Map.Entry<CallKey, Context> context = contexts.next();
                if(!current(context.getKey().epoch()) ||
                    !context.getKey().registration().current(context.getKey().binding()))
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
                    if(current(queued.epoch()) && queued.registration().current(queued.binding()))
                        retained = process(queued);
                }
                catch(RuntimeException ignored)
                {
                    lose(queued.registration(), queued.binding());
                }
                finally
                {
                    if(!retained) mRetainedBytes.addAndGet(-queued.bytes());
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
                if(context.getKey().registration() == queued.registration())
                {
                    release(context.getValue());
                    contexts.remove();
                }
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
                lose(queued.registration(), queued.binding());
                return false;
            }
            mContexts.put(key, new Context(event.snapshot()));
        }
        else if(event.eventType() == AudioCallEventType.AUDIO_FRAME)
        {
            Context context = mContexts.get(key);
            if(context == null) return false;
            if(event.audioFrameSampleCount() != 160 || context.samples > P25PagingToneDetector.MAXIMUM_SAMPLES - 160 ||
                (context.lastFrameTimestamp != 0 && event.voiceFrameTimestamp() != context.lastFrameTimestamp + 20))
            {
                mContexts.remove(key);
                release(context);
                lose(queued.registration(), queued.binding());
                return false;
            }
            float[] audio = event.audioFrame(); //the producer's event owns the first PCM copy
            for(float sample: audio)
            {
                if(!Float.isFinite(sample))
                {
                    mContexts.remove(key);
                    release(context);
                    lose(queued.registration(), queued.binding());
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
            List<P25PagingToneDetector.DetectedTone> tones;
            try
            {
                if(!current(queued.epoch())) return false;
                tones = mDetector.apply(context.buffers);
                mAnalyzedCalls.incrementAndGet();
            }
            finally
            {
                release(context);
            }
            if(!tones.isEmpty() && current(queued.epoch()) && queued.registration().current(queued.binding()) &&
                queued.epoch().claim())
            {
                try
                {
                    if(queued.binding().claim())
                    {
                        try
                        {
                            AudioCallSnapshot snapshot = event.snapshot();
                            queued.binding().listener.receive(new DecodedPagingToneMessage(snapshot.callId(),
                                snapshot.callLegId(), snapshot.callLegSource(), context.start.startTimestamp(),
                                context.firstFrameTimestamp, snapshot.lastActivityTimestamp(),
                                new DetectedPagingToneIdentifier(tones)));
                        }
                        finally
                        {
                            queued.binding().finish();
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

    private void release(Context context)
    {
        mRetainedBytes.addAndGet(-context.bytes);
        context.buffers.clear();
        context.bytes = 0L;
    }

    private record QueuedEvent(Registration registration, Binding binding, RuntimeEpoch epoch, AudioCallEvent event,
                                boolean reset, long bytes) {}
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
        private final List<float[]> buffers = new ArrayList<>();
        private long firstFrameTimestamp;
        private long lastFrameTimestamp;
        private int samples;
        private long bytes;
        private Context(AudioCallSnapshot snapshot) { start = snapshot; }
    }

    private static final class Binding
    {
        private final Listener<IMessage> listener;
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
        private final AtomicReference<Binding> mBinding = new AtomicReference<>();
        private final AtomicBoolean mClosed = new AtomicBoolean();
        private Registration(P25PagingToneService owner) { service = owner; }

        /** Lifecycle bind; replaces the epoch without waiting for an already-claimed observer callback. */
        public void setMessageListener(Listener<IMessage> listener)
        {
            Binding next = listener != null && !mClosed.get() ? new Binding(listener) : null;
            Binding previous = mBinding.getAndSet(next);
            if(previous != null) previous.invalidate();
            if(mClosed.get())
            {
                previous = mBinding.getAndSet(null);
                if(previous != null) previous.invalidate();
            }
            service.wakeWorker();
        }

        public void receive(AudioCallEvent event)
        {
            if(event != null) service.offer(this, event, false);
        }

        /** FIFO discard of unfinished context; completed messages already queued retain their original epoch. */
        public void resetUnfinishedCall() { service.offer(this, null, true); }

        private boolean current(Binding binding) { return mBinding.get() == binding && binding.valid(); }

        private void invalidate(Binding failed)
        {
            Binding next = !mClosed.get() ? new Binding(failed.listener) : null;
            mBinding.compareAndSet(failed, next);
            failed.invalidate();
            service.wakeWorker();
        }

        @Override
        public void close()
        {
            mClosed.set(true);
            Binding previous = mBinding.getAndSet(null);
            if(previous != null) previous.invalidate();
            service.wakeWorker();
        }
    }
}
