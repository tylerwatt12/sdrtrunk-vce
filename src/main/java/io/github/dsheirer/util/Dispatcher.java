/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.util;

import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.heartbeat.HeartbeatManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Threaded scheduled processor for receiving elements from a separate producer thread and forwarding those buffers to a
 * registered listener on this consumer/dispatcher thread.
 *
 * Instances that use the shared-pool constructor share a fixed-size daemon thread pool rather than allocating one
 * thread per dispatcher.  Per-instance ordering is preserved by a non-blocking consumer lock that prevents
 * concurrent re-entry on the same dispatcher.  Shutdown cancels the per-instance ScheduledFuture without touching
 * the shared pool.
 *
 * Instances that use private periodic scheduling (recorders and other I/O-heavy users) get their own executor so
 * that slow I/O cannot starve the shared channel-dispatch pool.  Private arrival scheduling instead uses one
 * long-lived worker that producers wake after a bounded queue offer.
 */
public class Dispatcher<E> implements Listener<E>
{
    public enum ExecutorType { SHARED, PRIVATE }
    public enum Scheduling { PERIODIC, ON_ARRIVAL }

    private static final Logger mLog = LoggerFactory.getLogger(Dispatcher.class);
    //Daemon threads: the pool must not prevent JVM shutdown.
    private static final ScheduledExecutorService SHARED_POOL =
        Executors.newScheduledThreadPool(8, new ThreadFactory()
        {
            private final AtomicInteger mCount = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r)
            {
                Thread t = new Thread(r, "sdrtrunk dispatcher thread " + mCount.getAndIncrement());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY);
                return t;
            }
        });
    private final String mThreadName;
    private final List<E> mDrainBuffer = new ArrayList<>();
    private final Consumer<E> mDiscardHandler;
    private final int mMaximumQueueSize;
    private final AtomicLong mDroppedElementCount = new AtomicLong();
    private final AtomicInteger mHighWaterQueueSize = new AtomicInteger();
    private Listener<E> mListener;
    private final ReentrantLock mProcessingLock = new ReentrantLock();
    private final AtomicReference<LifecycleState> mLifecycleState;
    private final ExecutorType mExecutorType;
    private final Scheduling mScheduling;
    private final long mInterval;
    private HeartbeatManager mHeartbeatManager;

    /**
     * Constructs an instance that uses the shared dispatcher pool.  Use for channel sources and channel output
     * processors where thread-per-instance overhead is the primary concern.
     * @param threadName used for diagnostics
     * @param interval for processing each batch in milliseconds.
     * @param heartbeatManager to receive a heartbeat command at each processing interval.
     */
    public Dispatcher(String threadName, long interval, HeartbeatManager heartbeatManager)
    {
        this(threadName, interval, ExecutorType.SHARED);
        mHeartbeatManager = heartbeatManager;
    }

    /**
     * Constructs a bounded dispatcher that uses the shared dispatcher pool and broadcasts heartbeats.  When full, the
     * oldest queued element is discarded and supplied to the discard handler.
     *
     * @param threadName used for diagnostics
     * @param interval for processing each batch in milliseconds
     * @param heartbeatManager to receive a heartbeat command at each processing interval
     * @param maximumQueueSize maximum queued elements
     * @param discardHandler cleanup callback for discarded elements
     */
    public Dispatcher(String threadName, long interval, HeartbeatManager heartbeatManager, int maximumQueueSize,
                      Consumer<E> discardHandler)
    {
        this(threadName, interval, ExecutorType.SHARED, maximumQueueSize, discardHandler);
        mHeartbeatManager = heartbeatManager;
    }

    /**
     * Constructs an instance that uses the shared dispatcher pool.  Use for channel sources and channel output
     * processors where thread-per-instance overhead is the primary concern.
     * @param threadName used for diagnostics
     * @param interval for processing each batch in milliseconds.
     */
    public Dispatcher(String threadName, long interval)
    {
        this(threadName, interval, ExecutorType.SHARED);
    }

    /**
     * Constructs an instance with the specified executor type.  Use {@link ExecutorType#PRIVATE} for I/O-bound
     * recorders and other users where slow tasks must not starve the shared channel-dispatch pool.
     * @param threadName used for private executor threads and diagnostics
     * @param interval for processing each batch in milliseconds.
     * @param executorType whether to use the shared pool or a private single-thread executor.
     */
    public Dispatcher(String threadName, long interval, ExecutorType executorType)
    {
        this(threadName, interval, executorType, 0, null);
    }

    /**
     * Constructs an optionally bounded dispatcher.  When full, the oldest queued element is discarded before the
     * new element is accepted.  Supply a discard handler when queued elements require explicit release or recycling.
     *
     * @param threadName name used for private executor threads and overflow diagnostics
     * @param interval processing interval in milliseconds
     * @param executorType shared or private executor
     * @param maximumQueueSize maximum queued elements, or zero for unbounded
     * @param discardHandler optional cleanup callback for discarded elements
     */
    public Dispatcher(String threadName, long interval, ExecutorType executorType, int maximumQueueSize,
                      Consumer<E> discardHandler)
    {
        this(threadName, interval, executorType, maximumQueueSize, discardHandler, Scheduling.PERIODIC);
    }

    /**
     * Constructs an optionally bounded dispatcher with periodic or arrival-driven processing.  Arrival-driven
     * processing is restricted to a private worker so an incoming element can wake exactly one isolated consumer
     * without submitting receiver work to an executor.
     */
    public Dispatcher(String threadName, long interval, ExecutorType executorType, int maximumQueueSize,
                      Consumer<E> discardHandler, Scheduling scheduling)
    {
        if(interval <= 0)
        {
            throw new IllegalArgumentException("Dispatcher interval must be greater than zero");
        }

        if(maximumQueueSize < 0)
        {
            throw new IllegalArgumentException("Dispatcher queue limit cannot be negative");
        }

        if(scheduling == null)
        {
            throw new IllegalArgumentException("Dispatcher scheduling cannot be null");
        }

        if(scheduling == Scheduling.ON_ARRIVAL && executorType != ExecutorType.PRIVATE)
        {
            throw new IllegalArgumentException("Arrival-driven dispatchers require a private worker");
        }

        mThreadName = threadName != null && !threadName.isBlank() ? threadName : "sdrtrunk dispatcher";
        mInterval = interval;
        mExecutorType = executorType;
        mScheduling = scheduling;
        mMaximumQueueSize = maximumQueueSize;
        mDiscardHandler = discardHandler;
        mLifecycleState = new AtomicReference<>(new LifecycleState(0, LifecyclePhase.STOPPED, createQueue(), null));
    }

    /**
     * Sets or changes the listener to receive buffers from this processor.
     * @param listener to receive buffers
     */
    public void setListener(Listener<E> listener)
    {
        mListener = listener;
    }

    /**
     * Primary input method for adding buffers to this processor.  Incoming elements received while stopped are passed
     * to the discard handler, when configured.  You must invoke start() to queue elements and initiate processing.
     *
     * @param e to enqueue for distribution to a registered listener
     */
    public void receive(E e)
    {
        LifecycleState lifecycle = mLifecycleState.get();

        if(lifecycle.phase != LifecyclePhase.RUNNING)
        {
            discard(e, false);
            return;
        }

        boolean accepted = offer(lifecycle, e);

        if(!accepted)
        {
            E dropped = poll(lifecycle);

            if(dropped != null)
            {
                discard(dropped, true);
            }

            //Another producer can claim the freed slot.  Never block a real-time producer if that happens.
            accepted = offer(lifecycle, e);

            if(!accepted)
            {
                discard(e, true);
            }
        }

        //Shutdown can race the initial running-state check.  Remove and clean up this element if stop() already
        //drained the queue before this producer completed its offer.
        LifecycleState current = mLifecycleState.get();

        if(accepted && (current.phase != LifecyclePhase.RUNNING || current.generation != lifecycle.generation) &&
            remove(lifecycle, e))
        {
            discard(e, false);
            accepted = false;
        }

        if(accepted && mScheduling == Scheduling.ON_ARRIVAL)
        {
            current = mLifecycleState.get();

            if(current.phase == LifecyclePhase.RUNNING && current.generation == lifecycle.generation &&
                current.queue == lifecycle.queue && current.task != null)
            {
                current.task.wake();
            }
        }
    }

    /**
     * Starts this buffer processor and allows queuing of incoming buffers.
     */
    public void start()
    {
        LifecycleState starting;

        while(true)
        {
            LifecycleState current = mLifecycleState.get();

            if(current.phase != LifecyclePhase.STOPPED)
            {
                return;
            }

            starting = new LifecycleState(current.generation + 1, LifecyclePhase.RUNNING, createQueue(), null);

            if(mLifecycleState.compareAndSet(current, starting))
            {
                break;
            }
        }

        long generation = starting.generation;
        BlockingQueue<E> queue = starting.queue;
        ScheduledExecutorService privateExecutor = null;
        ScheduledFuture<?> future = null;
        Thread arrivalThread = null;

        if(mScheduling == Scheduling.ON_ARRIVAL)
        {
            arrivalThread = new NamingThreadFactory(mThreadName).newThread(new ArrivalProcessor(generation, queue));
        }
        else
        {
            privateExecutor = mExecutorType == ExecutorType.PRIVATE ?
                Executors.newSingleThreadScheduledExecutor(new NamingThreadFactory(mThreadName)) : null;
            ScheduledExecutorService executor = privateExecutor != null ? privateExecutor : SHARED_POOL;
            Runnable processor = (mHeartbeatManager != null ? new ProcessorWithHeartbeat(generation, queue) :
                new Processor(generation, queue));
            Runnable guardedProcessor = () ->
            {
                LifecycleState current = mLifecycleState.get();

                if(current.phase == LifecyclePhase.RUNNING && current.generation == generation)
                {
                    processor.run();
                }
            };
            future = executor.scheduleAtFixedRate(guardedProcessor, 0, mInterval, TimeUnit.MILLISECONDS);
        }

        ProcessingTask task = new ProcessingTask(future, privateExecutor, arrivalThread);
        LifecycleState scheduled = starting.withTask(task);

        //The lifecycle state atomically owns this task.  A stop/restart that overtakes setup changes the state first,
        //so this stale starter can cancel only its own resources and cannot replace or stop the newer task.
        if(!mLifecycleState.compareAndSet(starting, scheduled))
        {
            task.stop();
        }
        else if(arrivalThread != null)
        {
            //The worker is created before publication but started afterward.  Any producer arrival in that window is
            //already visible in the bounded queue and will be drained before the worker parks.
            arrivalThread.start();
        }
    }

    /**
     * Stops this buffer processor and releases any queued elements.
     */
    public void stop()
    {
        while(true)
        {
            LifecycleState current = mLifecycleState.get();

            if(current.phase == LifecyclePhase.STOPPED || current.phase == LifecyclePhase.FLUSHING)
            {
                return;
            }

            LifecycleState stopped = new LifecycleState(current.generation + 1, LifecyclePhase.STOPPED,
                createQueue(), null);

            if(mLifecycleState.compareAndSet(current, stopped))
            {
                if(current.task != null)
                {
                    current.task.stop();
                }

                discardQueuedElements(current);
                return;
            }
        }
    }

    /**
     * Stops this buffer processor and flushes the queue to the listener
     */
    public void flushAndStop()
    {
        LifecycleState flushing;

        while(true)
        {
            LifecycleState current = mLifecycleState.get();

            if(current.phase == LifecyclePhase.FLUSHING)
            {
                return;
            }

            flushing = new LifecycleState(current.generation + 1, LifecyclePhase.FLUSHING, current.queue, null);

            if(mLifecycleState.compareAndSet(current, flushing))
            {
                if(current.task != null)
                {
                    current.task.stop();
                }

                break;
            }
        }

        List<E> elements = new ArrayList<>();
        mProcessingLock.lock();

        try
        {
            drainTo(flushing, elements);

            for(E element: elements)
            {
                if(mListener != null)
                {
                    try
                    {
                        mListener.receive(element);
                    }
                    catch(Throwable t)
                    {
                        mLog.error("Error while flusing and dispatching element [" + element.getClass() + "] to listener [" +
                                mListener.getClass() + "]", t);
                    }
                }
                else
                {
                    discard(element, false);
                }
            }
        }
        finally
        {
            mProcessingLock.unlock();
            mLifecycleState.compareAndSet(flushing, new LifecycleState(flushing.generation,
                LifecyclePhase.STOPPED, createQueue(), null));
        }
    }

    /**
     * Indicates if this processor is currently running
     */
    public boolean isRunning()
    {
        return mLifecycleState.get().phase == LifecyclePhase.RUNNING;
    }

    /**
     * Current number of queued elements awaiting processing.
     */
    public int getQueueSize()
    {
        int queued = Math.max(0, mLifecycleState.get().queuedElementCount.get());
        return mMaximumQueueSize > 0 ? Math.min(queued, mMaximumQueueSize) : queued;
    }

    /**
     * Total number of elements discarded because this dispatcher's bounded queue was full.
     */
    public long getDroppedElementCount()
    {
        return mDroppedElementCount.get();
    }

    /**
     * Configured queue capacity, or zero when this dispatcher uses an unbounded queue.
     */
    public int getMaximumQueueSize()
    {
        return mMaximumQueueSize;
    }

    /**
     * Largest approximate queue depth observed since this dispatcher was created.
     */
    public int getHighWaterQueueSize()
    {
        return mHighWaterQueueSize.get();
    }

    private void updateHighWaterQueueSize(int queueSize)
    {
        int current = mHighWaterQueueSize.get();

        while(queueSize > current && !mHighWaterQueueSize.compareAndSet(current, queueSize))
        {
            current = mHighWaterQueueSize.get();
        }
    }

    /**
     * Tracks approximate queue depth without calling BlockingQueue.size(), which can acquire the bounded queue's
     * internal lock or traverse an unbounded linked queue.  The count is reserved before offer so a consumer can never
     * remove an element before it is represented; concurrent producer reservations may briefly overstate depth.
     */
    private BlockingQueue<E> createQueue()
    {
        return mMaximumQueueSize > 0 ? new ArrayBlockingQueue<>(mMaximumQueueSize) : new LinkedTransferQueue<>();
    }

    private boolean offer(LifecycleState lifecycle, E element)
    {
        int queued = lifecycle.queuedElementCount.incrementAndGet();

        if(lifecycle.queue.offer(element))
        {
            updateHighWaterQueueSize(mMaximumQueueSize > 0 ? Math.min(queued, mMaximumQueueSize) : queued);
            return true;
        }

        lifecycle.queuedElementCount.decrementAndGet();
        return false;
    }

    private E poll(LifecycleState lifecycle)
    {
        E element = lifecycle.queue.poll();

        if(element != null)
        {
            lifecycle.queuedElementCount.decrementAndGet();
        }

        return element;
    }

    private boolean remove(LifecycleState lifecycle, E element)
    {
        if(lifecycle.queue.remove(element))
        {
            lifecycle.queuedElementCount.decrementAndGet();
            return true;
        }

        return false;
    }

    private void drainTo(LifecycleState lifecycle, List<E> target)
    {
        int drained = lifecycle.queue.drainTo(target);

        if(drained > 0)
        {
            lifecycle.queuedElementCount.addAndGet(-drained);
        }
    }

    private void discardQueuedElements(LifecycleState lifecycle)
    {
        E element = poll(lifecycle);

        while(element != null)
        {
            discard(element, false);
            element = poll(lifecycle);
        }
    }

    private void discard(E element, boolean queueOverflow)
    {
        if(element == null)
        {
            return;
        }

        if(mDiscardHandler != null)
        {
            try
            {
                mDiscardHandler.accept(element);
            }
            catch(Throwable throwable)
            {
                mLog.error("Error discarding queued element for dispatcher [{}]", mThreadName, throwable);
            }
        }

        if(queueOverflow)
        {
            //This method runs on real-time producers for the bounded IFFT and channel-result queues.  Keep overflow
            //reporting to a primitive counter; the receiver-health observer projects and alerts on it off-thread.
            mDroppedElementCount.incrementAndGet();
        }
    }

    /**
     * Processes elements from the queue.  Note: this should only be invoked on the Processor thread.
     */
    private void process(long generation, BlockingQueue<E> queue)
    {
        LifecycleState lifecycle = mLifecycleState.get();

        if(lifecycle.generation != generation || lifecycle.queue != queue)
        {
            return;
        }

        drainTo(lifecycle, mDrainBuffer);

        try
        {
            for(E element: mDrainBuffer)
            {
                LifecycleState current = mLifecycleState.get();

                if(current.phase == LifecyclePhase.RUNNING && current.generation == generation &&
                    current.queue == queue && mListener != null)
                {
                    try
                    {
                        mListener.receive(element);
                    }
                    catch(Throwable throwable)
                    {
                        mLog.error("Error while dispatching element [" + element.getClass() + "] to listener [" +
                                mListener.getClass() + "]", throwable);
                    }
                }
                else
                {
                    discard(element, false);
                }
            }
        }
        finally
        {
            mDrainBuffer.clear();
        }
    }

    /**
     * Processor to service the buffer queue and distribute the buffers to the registered listener
     */
    class Processor implements Runnable
    {
        private final long mGeneration;
        private final BlockingQueue<E> mQueue;

        private Processor(long generation, BlockingQueue<E> queue)
        {
            mGeneration = generation;
            mQueue = queue;
        }

        @Override
        public void run()
        {
            if(mProcessingLock.tryLock())
            {
                try
                {
                    process(mGeneration, mQueue);
                }
                finally
                {
                    mProcessingLock.unlock();
                }
            }
        }
    }

    /**
     * Processor to service the buffer queue and distribute the buffers to the registered listener.  Includes a
     * support for commanding a heart beat with each processing interval.
     */
    class ProcessorWithHeartbeat implements Runnable
    {
        private final long mGeneration;
        private final BlockingQueue<E> mQueue;

        private ProcessorWithHeartbeat(long generation, BlockingQueue<E> queue)
        {
            mGeneration = generation;
            mQueue = queue;
        }

        @Override
        public void run()
        {
            if(mProcessingLock.tryLock())
            {
                try
                {
                    process(mGeneration, mQueue);

                    LifecycleState current = mLifecycleState.get();

                    if(current.phase == LifecyclePhase.RUNNING && current.generation == mGeneration &&
                        current.queue == mQueue)
                    {
                        mHeartbeatManager.broadcast();
                    }
                }
                catch(Throwable throwable)
                {
                    mLog.error("Error broadcasting heartbeat during Dispatcher processing interval", throwable);
                }
                finally
                {
                    mProcessingLock.unlock();
                }
            }
        }
    }

    /** Long-lived arrival-driven private consumer.  Producers wake it without allocating or submitting a task. */
    private class ArrivalProcessor implements Runnable
    {
        private final long mGeneration;
        private final BlockingQueue<E> mQueue;

        private ArrivalProcessor(long generation, BlockingQueue<E> queue)
        {
            mGeneration = generation;
            mQueue = queue;
        }

        @Override
        public void run()
        {
            while(true)
            {
                LifecycleState current = mLifecycleState.get();

                if(current.phase != LifecyclePhase.RUNNING || current.generation != mGeneration ||
                    current.queue != mQueue)
                {
                    return;
                }

                if(current.queuedElementCount.get() <= 0)
                {
                    //An unpark that races this check supplies a permit, so parking cannot lose a producer wakeup.
                    LockSupport.park(this);
                    continue;
                }

                //Only worker generations can wait here.  The receiver producer never acquires this lifecycle lock.
                mProcessingLock.lock();

                try
                {
                    process(mGeneration, mQueue);
                }
                finally
                {
                    mProcessingLock.unlock();
                }
            }
        }
    }

    private enum LifecyclePhase { STOPPED, RUNNING, FLUSHING }

    /** Atomically published ownership for one queue and processing generation. */
    private final class LifecycleState
    {
        private final long generation;
        private final LifecyclePhase phase;
        private final BlockingQueue<E> queue;
        private final AtomicInteger queuedElementCount;
        private final ProcessingTask task;

        private LifecycleState(long generation, LifecyclePhase phase, BlockingQueue<E> queue, ProcessingTask task)
        {
            this(generation, phase, queue, new AtomicInteger(), task);
        }

        private LifecycleState(long generation, LifecyclePhase phase, BlockingQueue<E> queue,
                               AtomicInteger queuedElementCount, ProcessingTask task)
        {
            this.generation = generation;
            this.phase = phase;
            this.queue = queue;
            this.queuedElementCount = queuedElementCount;
            this.task = task;
        }

        private LifecycleState withTask(ProcessingTask processingTask)
        {
            return new LifecycleState(generation, phase, queue, queuedElementCount, processingTask);
        }
    }

    /** Resources for one processing lifecycle generation. */
    private record ProcessingTask(ScheduledFuture<?> future, ScheduledExecutorService privateExecutor,
                                  Thread arrivalThread)
    {
        private void wake()
        {
            if(arrivalThread != null)
            {
                LockSupport.unpark(arrivalThread);
            }
        }

        private void stop()
        {
            //False is required because downstream code may hold locks that must be released normally.
            if(future != null)
            {
                future.cancel(false);
            }

            if(privateExecutor != null)
            {
                privateExecutor.shutdown();
            }

            wake();
        }
    }
}
