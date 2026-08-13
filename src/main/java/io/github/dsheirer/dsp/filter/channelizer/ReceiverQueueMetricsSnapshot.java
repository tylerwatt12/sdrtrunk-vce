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
package io.github.dsheirer.dsp.filter.channelizer;

import io.github.dsheirer.util.Dispatcher;
import java.util.List;

/**
 * Immutable, lock-free readout of the receiver queues for one tuner.  Values can move independently while a
 * snapshot is assembled, so callers should treat this as a diagnostic observation rather than accounting data.
 */
public record ReceiverQueueMetricsSnapshot(long capturedNanos, NativeBufferMetrics rawInput, QueueMetrics ifft,
                                           List<ChannelQueueMetrics> channels)
{
    public ReceiverQueueMetricsSnapshot
    {
        channels = channels == null ? List.of() : List.copyOf(channels);
    }

    /**
     * Snapshot returned after the owning tuner manager has been disposed.
     */
    public static ReceiverQueueMetricsSnapshot unavailable()
    {
        return new ReceiverQueueMetricsSnapshot(System.nanoTime(), null, null, List.of());
    }

    /**
     * Native tuner IQ queue metrics.  A configured limit of zero means that the queue is unbounded.
     */
    public record NativeBufferMetrics(String name, double sampleRate, long configuredLimitMilliseconds,
                                      long configuredLimitSamples, int waitingBuffers, long waitingSamples,
                                      long waitingMilliseconds, int inFlightBuffers, long inFlightSamples,
                                      long inFlightMilliseconds, int highWaterWaitingBuffers,
                                      long highWaterWaitingSamples, long highWaterWaitingMilliseconds,
                                      long receivedBuffers, long receivedSamples, long receivedMilliseconds,
                                      long processedBuffers, long processedSamples, long processedMilliseconds,
                                      long droppedBuffers, long droppedSamples, long droppedMilliseconds,
                                      long cleanupBuffers, long cleanupSamples, long cleanupMilliseconds,
                                      long lastIngressNanos, long lastCompletionNanos, long activeSinceNanos,
                                      boolean running, boolean disposed)
    {
        public boolean active()
        {
            return inFlightBuffers > 0;
        }

        public boolean unbounded()
        {
            return configuredLimitMilliseconds == 0;
        }
    }

    /**
     * Dispatcher metrics used by the tuner-wide IFFT stage and each individual channel-output stage.  A configured
     * limit of zero means that the queue is unbounded.  In-flight elements have already left the visible queue but
     * are still retained by, or executing in, the dispatcher callback.
     */
    public record QueueMetrics(String name, int configuredLimit, int waiting, int inFlight, boolean callbackActive,
                               long received, long accepted, long processed, long discarded, long dropped,
                               int highWaterOutstanding, long lastIngressNanos, long lastCompletionNanos,
                               long activeSinceNanos, long capturedNanos, boolean running)
    {
        public static QueueMetrics from(Dispatcher.Metrics metrics)
        {
            if(metrics == null)
            {
                return null;
            }

            return new QueueMetrics(metrics.name(), metrics.maximumQueueSize(), metrics.waitingCount(),
                metrics.inFlightCount(), metrics.callbackActive(), metrics.receivedCount(), metrics.acceptedCount(),
                metrics.processedCount(), metrics.discardedCount(), metrics.droppedCount(), metrics.highWaterCount(),
                metrics.lastIngressNanos(), metrics.lastCompletionNanos(), metrics.callbackStartedNanos(),
                metrics.snapshotNanos(), metrics.running());
        }

        public boolean unbounded()
        {
            return configuredLimit == 0;
        }

        public int outstanding()
        {
            return waiting + inFlight;
        }
    }

    /**
     * Identifies one active channel and its downstream output queue.
     */
    public record ChannelQueueMetrics(long requestedFrequency, double sampleRate, QueueMetrics output)
    {
    }
}
