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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Shared bounded raw-audio observer. Producer handoffs retain an already-owned immutable event and perform only
 * primitive atomic accounting plus a lock-free offer. FFT, PCM copies, projection and listener callbacks run on the
 * dedicated worker. Loss invalidates that registration's epoch; incomplete audio never produces tone evidence.
 *
 * Dispose/rebind invalidates queued deliveries without waiting for slow listeners. An already-claimed callback may
 * finish. Ordinary call completion/reset is FIFO and does not invalidate already-completed work. The detector never
 * updates decoder identifiers or call routing. A slow observer can lose evidence but cannot backpressure reception.
 */
public final class P25PagingToneService
{
    private static final P25PagingToneService INSTANCE = new P25PagingToneService();
    private final ConcurrentLinkedQueue<QueuedEvent> mQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger mQueuedEvents = new AtomicInteger();
    private final AtomicLong mRetainedBytes = new AtomicLong();
    private final AtomicLong mDroppedEvents = new AtomicLong();
    private final AtomicBoolean mDraining = new AtomicBoolean();
    private final Map<CallKey, Context> mContexts = new HashMap<>(); //worker-owned
    private final int mMaximumQueuedEvents;
    private final long mMaximumRetainedBytes;
    private final int mMaximumActiveCalls;
    private final Function<List<float[]>, List<P25PagingToneDetector.DetectedTone>> mDetector;

    private P25PagingToneService()
    {
        this(4096, 16L * 1024L * 1024L, 64, new P25PagingToneDetector()::detect);
        ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "p25-paging-tone-observer");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        worker.scheduleWithFixedDelay(this::drain, 20L, 20L, TimeUnit.MILLISECONDS);
    }

    /** Isolated manual-worker constructor for bounded observer tests. */
    P25PagingToneService(int maximumQueuedEvents, long maximumRetainedBytes, int maximumActiveCalls,
                          Function<List<float[]>, List<P25PagingToneDetector.DetectedTone>> detector)
    {
        if(maximumQueuedEvents <= 0 || maximumRetainedBytes <= 0 || maximumActiveCalls <= 0)
            throw new IllegalArgumentException("Positive observer bounds are required");
        mMaximumQueuedEvents = maximumQueuedEvents;
        mMaximumRetainedBytes = maximumRetainedBytes;
        mMaximumActiveCalls = maximumActiveCalls;
        mDetector = java.util.Objects.requireNonNull(detector);
    }

    public static P25PagingToneService getInstance() { return INSTANCE; }

    public Registration register() { return new Registration(this); }

    public long droppedEvents() { return mDroppedEvents.get(); }

    int queuedEvents() { return mQueuedEvents.get(); }

    long retainedBytes() { return mRetainedBytes.get(); }

    private void offer(Registration registration, AudioCallEvent event, boolean reset)
    {
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
        if(mQueuedEvents.incrementAndGet() > mMaximumQueuedEvents)
        {
            mQueuedEvents.decrementAndGet();
            mRetainedBytes.addAndGet(-bytes);
            lose(registration, binding);
            return;
        }
        mQueue.offer(new QueuedEvent(registration, binding, event, reset, bytes));
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
                if(!context.getKey().registration().current(context.getKey().binding()))
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
                    if(queued.registration().current(queued.binding())) retained = process(queued);
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
        CallKey key = new CallKey(queued.registration(), queued.binding(), event.callId());
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
                tones = mDetector.apply(context.buffers);
            }
            finally
            {
                release(context);
            }
            if(!tones.isEmpty() && queued.registration().current(queued.binding()) && queued.binding().claim())
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
        return false;
    }

    private void release(Context context)
    {
        mRetainedBytes.addAndGet(-context.bytes);
        context.buffers.clear();
        context.bytes = 0L;
    }

    private record QueuedEvent(Registration registration, Binding binding, AudioCallEvent event,
                                boolean reset, long bytes) {}
    private record CallKey(Registration registration, Binding binding, AudioCallId callId) {}

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
        }

        @Override
        public void close()
        {
            mClosed.set(true);
            Binding previous = mBinding.getAndSet(null);
            if(previous != null) previous.invalidate();
        }
    }
}
