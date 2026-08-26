/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.event;

import io.github.dsheirer.util.concurrent.BoundedMpscPairQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded nonblocking handoff from history producer threads to one Swing event-thread consumer.
 *
 * <p>Producer callbacks only perform a fixed-attempt queue offer.  A coalescing Swing Timer owned by the view invokes
 * {@link #drain()} on the EDT.  A full or contended queue drops the newest observation.  Projection, filtering, and
 * model changes remain in the EDT handler.</p>
 */
final class BoundedSwingHistoryHandoff<H,I>
{
    @FunctionalInterface
    interface Handler<H,I>
    {
        void accept(H history, I item, long attachmentGeneration);
    }

    private final BoundedMpscPairQueue<H,I> mQueue;
    private final Handler<H,I> mHandler;
    private final AtomicLong mDroppedItemCount = new AtomicLong();

    BoundedSwingHistoryHandoff(int capacity, Handler<H,I> handler)
    {
        mQueue = new BoundedMpscPairQueue<>(capacity);
        mHandler = handler;
    }

    /**
     * Offers one generation-stamped history item without blocking the producer.
     */
    boolean offer(H history, I item, long attachmentGeneration)
    {
        if(!mQueue.offer(history, item, attachmentGeneration))
        {
            mDroppedItemCount.incrementAndGet();
            return false;
        }

        return true;
    }

    int size()
    {
        return mQueue.size();
    }

    long getDroppedItemCount()
    {
        return mDroppedItemCount.get();
    }

    void clear()
    {
        mQueue.clear();
    }

    void drain()
    {
        int drained = 0;

        while(drained < mQueue.capacity())
        {
            BoundedMpscPairQueue.Entry<H,I> entry = mQueue.poll();

            if(entry == null)
            {
                break;
            }

            mHandler.accept(entry.first(), entry.second(), entry.stamp());
            drained++;
        }
    }
}
