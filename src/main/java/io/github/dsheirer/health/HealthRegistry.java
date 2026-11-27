/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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

package io.github.dsheirer.health;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.manager.ChannelSourceManager;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.Predicate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps how well each standard channel and tuner is heard, for node health reports, and every 10 seconds a snapshot
 * of the totals (HealthHistory), from which a report for any window of the last half hour is the difference of two.
 *
 * One per application: channels' HealthMonitors and traffic channel managers write to it, and services' reporters
 * (the Broadcastify Calls broadcaster) read from it. Snapshot sampling runs on its own low-priority worker only
 * while at least one enabled provider node retains it.
 */
public class HealthRegistry
{
    private static final Logger LOGGER = LoggerFactory.getLogger(HealthRegistry.class);
    private static final HealthRegistry INSTANCE = new HealthRegistry();

    /** How often the totals are kept. */
    public static final long SNAPSHOT_INTERVAL_MS = 10_000;
    /** A tuner counts as running for at most this long between two snapshots (a stall isn't running time). */
    private static final long MAX_SNAPSHOT_GAP_MS = 15_000;

    private final long mSince = System.currentTimeMillis();
    private final Map<Integer,ChannelHealth> mChannels = new ConcurrentHashMap<>();
    private final Object mRegistrationLock = new Object();
    private final Map<String,Double> mTunerSeconds = new HashMap<>();
    private final HealthHistory mHistory = new HealthHistory();
    private Supplier<List<DiscoveredTuner>> mAvailableTuners;
    private Supplier<List<Channel>> mConfiguredChannelsSupplier;
    private ScheduledFuture<?> mSnapshotFuture;
    private int mUnscopedReaders;
    private final Map<Object,Supplier<Predicate<ChannelHealth.ChannelInfo>>> mScopes = new HashMap<>();
    private long mLastSnapshot;
    private final ScheduledExecutorService mSnapshotExecutor = Executors.newSingleThreadScheduledExecutor(task ->
    {
        Thread worker = new Thread(task, "sdrtrunk-node-health-snapshots");
        worker.setDaemon(true);
        worker.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return worker;
    });

    /** Package visibility permits isolated lifecycle checks without the application singleton. */
    HealthRegistry()
    {
    }

    /** Worker-side inventory injection avoids constructing hardware/persistence owners in snapshot checks. */
    HealthRegistry(Supplier<List<DiscoveredTuner>> availableTuners)
    {
        mAvailableTuners = availableTuners;
    }

    public static HealthRegistry getInstance()
    {
        return INSTANCE;
    }

    /**
     * Tuners to report on (their running time and frequency error).
     */
    public synchronized void setTunerManager(TunerManager tunerManager)
    {
        mAvailableTuners = tunerManager != null ? tunerManager::getAvailableTuners : null;
    }

    /** Worker-side saved configuration view, used to release removed/replaced runtime registrations. */
    public synchronized void setConfiguredChannelsSupplier(Supplier<List<Channel>> supplier)
    {
        mConfiguredChannelsSupplier = supplier;
    }

    /** Non-registering lookup for delayed metadata; an obsolete channel must never revive its registration. */
    public ChannelHealth existingChannel(Channel channel)
    {
        return channel != null ? mChannels.get(channel.getChannelID()) : null;
    }

    /**
     * Registers a standard channel during monitor/traffic-manager construction. Existing receiver-side lookups are
     * lock-free. A saved configuration's replacement runtime supersedes its old totals, even if the old one stopped.
     */
    public ChannelHealth channel(Channel channel)
    {
        ChannelHealth existing = existingChannel(channel);
        return existing != null ? existing : registerChannel(channel);
    }

    private ChannelHealth registerChannel(Channel channel)
    {
        //Lifecycle registration must never wait for the reporter's snapshot/history/tuner projection monitor.
        //Only other lifecycle registrations use this lock; snapshots iterate the concurrent map independently.
        synchronized(mRegistrationLock)
        {
            ChannelHealth existing = existingChannel(channel);
            if(existing != null)
            {
                return existing;
            }
            int id = channel.getChannelID();
            String configurationId = channel.getPersistedConfigurationId();
            String mode = channel.getDecodeConfiguration() != null ?
                HealthMonitor.conventionalMode(channel.getDecodeConfiguration().getDecoderType()) : null;
            ChannelHealth health = new ChannelHealth(new ChannelHealth.ChannelInfo(id,
                configurationId, channel.getAliasListId(), channel.getName(), channel.getSystem(),
                channel.getSite(), channel.getAliasListName(), mode,
                channel.getDecodeConfiguration() != null ?
                    HealthMonitor.symbolRate(channel.getDecodeConfiguration().getDecoderType()) : null), false);
            if(channel.getSourceConfiguration() instanceof SourceConfigTuner tuner)
            {
                health.setFrequency(tuner.getFrequency());
            }
            else if(channel.getSourceConfiguration() instanceof SourceConfigTunerMultipleFrequency multiple &&
                multiple.getFrequencies() != null && !multiple.getFrequencies().isEmpty())
            {
                health.setFrequency(multiple.getFrequencies().getFirst());
            }
            if(configurationId != null)
            {
                mChannels.values().removeIf(previous -> configurationId.equals(previous.getInfo().configurationId()));
            }
            mChannels.put(id, health);
            return health;
        }
    }

    /** Releases only this runtime registration, including while reporting and its sampler are disabled. */
    public void retire(Channel channel)
    {
        if(channel != null)
        {
            mChannels.remove(channel.getChannelID());
        }
    }

    /**
     * Calls announced by a standard channel's control channel, and their outcomes.  Asking for them says they're
     * counted: the channel's sites are reported from now on.
     */
    public CallTally calls(Channel channel)
    {
        ChannelHealth health = channel(channel);
        health.setCallsCounted();
        return health.getCalls();
    }

    /** Current bounded history; a read does not start reporting or background sampling. */
    public HealthHistory getHistory()
    {
        return mHistory;
    }

    /** Compatibility retain for an unscoped measurement consumer. */
    public synchronized HealthHistory retainHistory()
    {
        mUnscopedReaders++;
        startSampling();
        return mHistory;
    }

    /** Provider demand is evaluated only on the measurement worker, never on a decoder callback. */
    public synchronized HealthHistory retainHistory(Object owner, Predicate<ChannelHealth.ChannelInfo> feeds)
    {
        return retainScope(owner, () -> feeds);
    }

    /** Prepare a provider's membership once per sampling pass, rather than once per channel. */
    public synchronized HealthHistory retainScope(Object owner, Supplier<Predicate<ChannelHealth.ChannelInfo>> scope)
    {
        mScopes.put(owner, scope);
        startSampling();
        return mHistory;
    }

    private void startSampling()
    {
        if(mSnapshotFuture == null)
        {
            mSnapshotFuture = mSnapshotExecutor.scheduleWithFixedDelay(this::snapshot, 0, SNAPSHOT_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void releaseHistory()
    {
        if(mUnscopedReaders > 0) mUnscopedReaders--;
        stopUnusedSampling();
    }

    public synchronized void releaseHistory(Object owner)
    {
        mScopes.remove(owner);
        stopUnusedSampling();
    }

    private void stopUnusedSampling()
    {
        if(mUnscopedReaders == 0 && mScopes.isEmpty())
        {
            if(mSnapshotFuture != null) mSnapshotFuture.cancel(false);
            mSnapshotFuture = null;
            long now = System.currentTimeMillis();
            for(ChannelHealth health: mChannels.values()) health.setCollecting(false, now);
        }
    }

    private boolean wanted(ChannelHealth.ChannelInfo info, List<Predicate<ChannelHealth.ChannelInfo>> scopes)
    {
        if(mUnscopedReaders > 0) return true;
        for(Predicate<ChannelHealth.ChannelInfo> feeds: scopes)
        {
            if(feeds.test(info)) return true;
        }
        return false;
    }

    synchronized boolean isSamplingForTest()
    {
        return mSnapshotFuture != null && !mSnapshotFuture.isCancelled();
    }

    /**
     * Takes a snapshot of every channel's and tuner's totals into the history.
     */
    synchronized void snapshot()
    {
        snapshot(System.currentTimeMillis());
    }

    /** Explicit sampling time keeps elapsed-time and lifecycle checks independent of scheduler timing. */
    synchronized void snapshot(long now)
    {
        try
        {
            List<ChannelHealth.Totals> channels = new ArrayList<>();
            if(mConfiguredChannelsSupplier != null)
            {
                Set<Integer> configured = new HashSet<>();
                for(Channel channel: mConfiguredChannelsSupplier.get())
                {
                    if(channel != null && channel.isStandardChannel())
                    {
                        configured.add(channel.getChannelID());
                    }
                }
                mChannels.keySet().removeIf(id -> !configured.contains(id));
            }

            List<Predicate<ChannelHealth.ChannelInfo>> scopes = new ArrayList<>(mScopes.size());
            for(Supplier<Predicate<ChannelHealth.ChannelInfo>> scope: mScopes.values()) scopes.add(scope.get());
            for(ChannelHealth health: mChannels.values())
            {
                health.setCollecting(wanted(health.getInfo(), scopes), now);
                if(health.isCollecting()) channels.add(health.totals(now));
            }

            mHistory.add(new HealthSnapshot(now, mSince, channels, radios(now)));
            mLastSnapshot = now;
        }
        catch(Throwable t)
        {
            LOGGER.error("Error taking a node health snapshot", t);
        }
    }

    /**
     * Tuners: name, kind, seconds with channels since the start, and frequency error left after correction.
     */
    private List<HealthSnapshot.RadioTotals> radios(long now)
    {
        List<HealthSnapshot.RadioTotals> radios = new ArrayList<>();

        if(mAvailableTuners == null)
        {
            return radios;
        }

        double elapsed = mLastSnapshot > 0 ? Math.min(now - mLastSnapshot, MAX_SNAPSHOT_GAP_MS) / 1000.0 : 0.0;

        for(DiscoveredTuner discovered: mAvailableTuners.get())
        {
            try
            {
                //Capture once: shutdown can clear the discovered tuner's runtime between observer reads.
                Tuner tuner = discovered.getTuner();
                if(tuner == null) continue;

                ChannelSourceManager sources = tuner.getChannelSourceManager();
                boolean inUse = sources != null && sources.getTunerChannelCount() > 0;
                TunerController controller = tuner.getTunerController();
                String name = tuner.getPreferredName();
                String kind = kind(tuner.getTunerClass());
                Double ppm = controller != null ?
                    ppm(controller.getMeasuredFrequencyError(), controller.getFrequency()) : null;
                double seconds = mTunerSeconds.merge(name, inUse ? elapsed : 0.0, Double::sum);
                radios.add(new HealthSnapshot.RadioTotals(name, kind, seconds, ppm));
            }
            catch(RuntimeException exception)
            {
                LOGGER.debug("Node health: tuner left out of this snapshot", exception);
            }
        }

        return radios;
    }

    /**
     * Frequency error left after correction, as decoders measured it, ppm to two places, or null when unmeasured.
     * @param errorHz measured error averaged over a second, 0 when nothing has measured it
     * @param frequency the tuner is tuned to
     */
    static Double ppm(int errorHz, long frequency)
    {
        if(errorHz == 0 || frequency <= 0)
        {
            return null;
        }

        return Math.round(errorHz / (frequency / 1E6) * 100.0) / 100.0;
    }

    /**
     * A tuner's kind, as a node health report names it.
     */
    static String kind(TunerClass tunerClass)
    {
        if(tunerClass == null)
        {
            return "unknown";
        }

        return switch(tunerClass)
        {
            case AIRSPY -> "airspy";
            case AIRSPY_HF -> "airspyhf";
            case HACKRF -> "hackrf";
            case HYDRASDR -> "hydrasdr";
            case RTL2832 -> "rtlsdr";
            case RSP -> "sdrplay";
            case RECORDING_TUNER -> "recording";
            default -> tunerClass.name().toLowerCase();
        };
    }
}
