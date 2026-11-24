/* Copyright (C) 2026. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.controller.channel;

import io.github.dsheirer.controller.channel.ChannelProcessingManager.TunerChannelAssignment;
import io.github.dsheirer.source.tuner.channel.TunerChannelSource;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.DiscoveredUSBTuner;
import io.github.dsheirer.source.tuner.manager.UsbTunerRecoveryListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * One process-local run-intent record per configured USB channel. Repeated failures update that record rather than
 * queueing starts or retaining old processing chains. Hardware recovery invokes this on its lifecycle worker;
 * source-stop notifications only mark an existing record and never start hardware.
 */
final class UsbChannelRecoverySupport implements UsbTunerRecoveryListener
{
    interface Operations
    {
        DiscoveredUSBTuner usbTuner(String identity);
        DiscoveredUSBTuner currentUsbTuner(Channel channel);
        List<TunerChannelAssignment> assignments(String identity);
        boolean isProcessing(Channel channel);
        boolean isStopping(Channel channel);
        void stopInterrupted(TunerChannelAssignment assignment) throws ChannelException;
        void startRecovered(Channel channel, DiscoveredUSBTuner tuner) throws ChannelException;
        boolean isClosed();
    }

    private final Object mLifecycle;
    private final Operations mOperations;
    private final LongSupplier mClock;
    private final Map<String,Intent> mIntents = new ConcurrentHashMap<>();

    UsbChannelRecoverySupport(Object lifecycle, Operations operations)
    {
        this(lifecycle, operations, System::nanoTime);
    }

    UsbChannelRecoverySupport(Object lifecycle, Operations operations, LongSupplier clock)
    {
        mLifecycle = lifecycle;
        mOperations = operations;
        mClock = clock;
    }

    /** Called only after a configured channel has actually started, while holding the normal start monitor. */
    void channelStarted(Channel channel, TunerChannelSource source, long incarnation)
    {
        if(!channel.isStandardChannel() || incarnation == 0) return;
        DiscoveredUSBTuner tuner = mOperations.usbTuner(source.getTunerIdentity());
        if(tuner == null)
        {
            mIntents.remove(channel.getConfigurationId());
            return;
        }
        mIntents.put(channel.getConfigurationId(), new Intent(channel, tuner, incarnation,
            tuner.getRecoveryCancellationGeneration(), false, 0, 0));
    }

    /** Constant-size bookkeeping at automatic source teardown; a prior explicit Stop cannot be reinstated here. */
    void sourceInterrupted(Channel channel, TunerChannelSource source, long incarnation)
    {
        if(channel == null || source == null || !channel.isStandardChannel()) return;
        mIntents.computeIfPresent(channel.getConfigurationId(), (_, intent) ->
        {
            if(intent.channel() != channel || intent.incarnation() != incarnation || intent.interrupted())
                return intent;
            DiscoveredUSBTuner tuner = mOperations.usbTuner(source.getTunerIdentity());
            if(tuner == null) return null;
            if(tuner == intent.tuner() && !current(intent)) return null;
            return new Intent(channel, tuner, incarnation, tuner.getRecoveryCancellationGeneration(), true, 0, 0);
        });
    }

    /** Rotation updates existing intent before a former owner can cancel it. No lifecycle monitor or hardware work. */
    void sourceAssigned(Channel channel, String identity, long incarnation)
    {
        mIntents.computeIfPresent(channel.getConfigurationId(), (_, intent) ->
        {
            if(intent.channel() != channel || intent.incarnation() != incarnation || intent.interrupted()) return intent;
            DiscoveredUSBTuner tuner = mOperations.usbTuner(identity);
            if(tuner == null || !tuner.isEnabled() || tuner.getOperatorState() != DiscoveredTuner.OperatorState.LIVE)
                return null;
            return tuner == intent.tuner() ? intent : new Intent(channel, tuner, incarnation,
                tuner.getRecoveryCancellationGeneration(), false, 0, 0);
        });
    }

    /** The monitor fences an already-starting recovery before the caller checks whether a Stop is needed. */
    void cancel(String configurationId)
    {
        if(configurationId == null) return;
        synchronized(mLifecycle) { mIntents.remove(configurationId); }
    }

    /** A validated edit supersedes a waiting restart, without changing ordinary running-channel edit behavior. */
    void cancelPending(String configurationId)
    {
        if(configurationId == null) return;
        synchronized(mLifecycle)
        {
            mIntents.computeIfPresent(configurationId, (_, intent) -> intent.interrupted() ? null : intent);
        }
    }

    void cancelIfIncarnation(String configurationId, long incarnation)
    {
        synchronized(mLifecycle)
        {
            mIntents.computeIfPresent(configurationId, (_, intent) ->
                intent.incarnation() == incarnation ? null : intent);
        }
    }

    boolean isPending(String configurationId)
    {
        Intent intent = mIntents.get(configurationId);
        return intent != null && intent.interrupted() && current(intent);
    }

    /** A replacement object must never make a retained old Channel eligible for a later restart. */
    void configurationChanged(Channel channel)
    {
        synchronized(mLifecycle)
        {
            mIntents.computeIfPresent(channel.getConfigurationId(), (_, intent) ->
                intent.channel() == channel ? intent : null);
        }
    }

    void clear()
    {
        synchronized(mLifecycle) { mIntents.clear(); }
    }

    @Override public void tunerUnavailable(DiscoveredUSBTuner tuner)
    {
        if(mOperations.isClosed()) return;
        List<TunerChannelAssignment> interrupted = new ArrayList<>();
        synchronized(mLifecycle)
        {
            for(TunerChannelAssignment assignment: mOperations.assignments(tuner.getId()))
            {
                Intent intent = mIntents.get(assignment.id());
                if(intent == null || intent.channel() != assignment.channel() ||
                    intent.incarnation() != assignment.processingIncarnation()) continue;
                DiscoveredUSBTuner actual = mOperations.currentUsbTuner(assignment.channel());
                if(actual != null && actual != tuner) continue;
                if(intent.tuner() == tuner && !current(intent))
                {
                    mIntents.remove(assignment.id(), intent);
                    continue;
                }
                // Rotating channels can have moved to another USB tuner since their initial start.
                Intent pending = new Intent(intent.channel(), tuner, intent.incarnation(),
                    tuner.getRecoveryCancellationGeneration(), true, 0, 0);
                if(mIntents.replace(assignment.id(), intent, pending)) interrupted.add(assignment);
            }
        }
        // Never hold the channel start monitor while P25/NXDN stop callbacks notify their traffic managers.
        stopInterrupted(interrupted);
    }

    @Override public void tunerAvailable(DiscoveredUSBTuner tuner)
    {
        if(mOperations.isClosed() || !tuner.isEnabled() || !tuner.isAvailable() ||
            tuner.getOperatorState() != DiscoveredTuner.OperatorState.LIVE) return;

        // Clear any stale RUNNING shell before trying to start its replacement. Compare-stopping protects a manual
        // replacement that won the race after this snapshot.
        List<TunerChannelAssignment> stale = new ArrayList<>();
        for(TunerChannelAssignment assignment: mOperations.assignments(tuner.getId()))
        {
            Intent intent = mIntents.get(assignment.id());
            if(intent != null && intent.interrupted() && current(intent) &&
                intent.channel() == assignment.channel() && intent.incarnation() == assignment.processingIncarnation())
                stale.add(assignment);
        }
        stopInterrupted(stale);

        synchronized(mLifecycle)
        {
            for(Intent intent: List.copyOf(mIntents.values()))
            {
                String id = intent.channel().getConfigurationId();
                if(mOperations.isClosed()) return;
                if(mIntents.get(id) != intent || intent.tuner() != tuner || !intent.interrupted()) continue;
                if(!current(intent))
                {
                    mIntents.remove(id, intent);
                    continue;
                }
                long now = mClock.getAsLong();
                if(intent.retryAfter() != 0 && now - intent.retryAfter() < 0) continue;
                if(mOperations.isProcessing(intent.channel()) || mOperations.isStopping(intent.channel())) continue;
                try
                {
                    mOperations.startRecovered(intent.channel(), tuner);
                    if(mOperations.isProcessing(intent.channel()))
                    {
                        // Real starts replace this record through channelStarted(); the fallback also supports a
                        // lifecycle adapter that completed the start without emitting another observer notification.
                        mIntents.computeIfPresent(id, (_, existing) -> existing == intent ?
                            new Intent(intent.channel(), tuner, intent.channel().getProcessingIncarnation(),
                                tuner.getRecoveryCancellationGeneration(), false, 0, 0) : existing);
                        continue;
                    }
                }
                catch(ChannelException | RuntimeException ignored)
                {
                    // Keep one pending record and retry later; never spin or reset healthy hardware for a channel.
                }
                int failures = Math.min(5, intent.failures() + 1);
                long retryAfter = now + TimeUnit.SECONDS.toNanos(Math.min(30, 1L << failures));
                mIntents.replace(id, intent, new Intent(intent.channel(), tuner, intent.incarnation(),
                    intent.cancellationGeneration(), true, failures, retryAfter));
            }
        }
    }

    @Override public void tunerDisabled(DiscoveredUSBTuner tuner)
    {
        synchronized(mLifecycle)
        {
            for(Intent intent: List.copyOf(mIntents.values()))
            {
                if(intent.tuner() != tuner) continue;
                // A rotating source publishes its new identity just before the assignment callback. Reconcile that
                // tiny window before applying a former owner's Disable/Setup cancellation to the wrong radio.
                DiscoveredUSBTuner actual = mOperations.currentUsbTuner(intent.channel());
                if(actual != null && actual != tuner && actual.isEnabled() &&
                    actual.getOperatorState() == DiscoveredTuner.OperatorState.LIVE)
                    mIntents.replace(intent.channel().getConfigurationId(), intent, new Intent(intent.channel(),
                        actual, intent.incarnation(), actual.getRecoveryCancellationGeneration(),
                        intent.interrupted(), intent.failures(), intent.retryAfter()));
                else mIntents.remove(intent.channel().getConfigurationId(), intent);
            }
        }
    }

    @Override public boolean hasInterruptedChannels(String tunerIdentity)
    {
        return mIntents.values().stream().anyMatch(intent -> intent.interrupted() &&
            intent.tuner().getId().equals(tunerIdentity) && current(intent));
    }

    private void stopInterrupted(List<TunerChannelAssignment> assignments)
    {
        for(TunerChannelAssignment assignment: assignments)
        {
            Intent intent = mIntents.get(assignment.id());
            if(intent == null || !intent.interrupted() || !current(intent) ||
                intent.incarnation() != assignment.processingIncarnation()) continue;
            try { mOperations.stopInterrupted(assignment); }
            catch(ChannelException | RuntimeException ignored)
            {
                // The processing map is authoritative; a later pass can compare-stop any retained stale mapping.
            }
        }
    }

    private static boolean current(Intent intent)
    {
        return intent.tuner().getRecoveryCancellationGeneration() == intent.cancellationGeneration();
    }

    private record Intent(Channel channel, DiscoveredUSBTuner tuner, long incarnation, long cancellationGeneration,
                          boolean interrupted, int failures, long retryAfter) { }
}
