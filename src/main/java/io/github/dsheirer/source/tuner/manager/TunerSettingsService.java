package io.github.dsheirer.source.tuner.manager;

import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerConfiguration;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerController;
import io.github.dsheirer.source.tuner.airspy.hf.AirspyHfTunerConfiguration;
import io.github.dsheirer.source.tuner.airspy.hf.AirspyHfTunerController;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.hackrf.HackRFTunerConfiguration;
import io.github.dsheirer.source.tuner.hackrf.HackRFTunerController;
import io.github.dsheirer.source.tuner.hydrasdr.HydraSdrTunerConfiguration;
import io.github.dsheirer.source.tuner.hydrasdr.HydraSdrTunerController;
import io.github.dsheirer.source.tuner.rtl.RTL2832TunerConfiguration;
import io.github.dsheirer.source.tuner.rtl.RTL2832TunerController;
import io.github.dsheirer.source.tuner.rtl.e4k.E4KEmbeddedTuner;
import io.github.dsheirer.source.tuner.rtl.e4k.E4KTunerConfiguration;
import io.github.dsheirer.source.tuner.rtl.fc0013.FC0013EmbeddedTuner;
import io.github.dsheirer.source.tuner.rtl.fc0013.FC0013TunerConfiguration;
import io.github.dsheirer.source.tuner.rtl.r8x.R8xEmbeddedTuner;
import io.github.dsheirer.source.tuner.rtl.r8x.R8xTunerConfiguration;
import io.github.dsheirer.source.tuner.sdrplay.RspTunerConfiguration;
import io.github.dsheirer.source.tuner.sdrplay.RspTunerController;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner1;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner2;
import java.util.HashMap;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admin-initiated tuner settings and enable/disable actions.  Requests update a bounded latest-value map and return
 * immediately.  A single dedicated worker serializes hardware and persistence work.  Sample-rate and center-retune
 * settings wait until idle; designated gain, calibration, lock and safe frequency-limit controls can apply live.
 */
public final class TunerSettingsService implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(TunerSettingsService.class);
    private static final long SHUTDOWN_WAIT_SECONDS = 8;
    private final Runnable mPersist;
    private final Predicate<DiscoveredTuner> mContains;
    private final Function<String,DiscoveredTuner> mFind;
    private final ScheduledThreadPoolExecutor mExecutor;
    private final Map<DiscoveredTuner,Map<String,Object>> mPending = new ConcurrentHashMap<>();
    private final Map<DiscoveredTuner,Boolean> mPendingEnabled = new ConcurrentHashMap<>();
    private final Map<DiscoveredTuner,String> mErrors = new ConcurrentHashMap<>();
    private final AtomicBoolean mWorkerScheduled = new AtomicBoolean();
    /** Serializes admin queue updates with close; decoder and channel allocation paths never take this lock. */
    private final Object mLifecycleLock = new Object();
    private volatile boolean mClosed;

    public TunerSettingsService(TunerManager manager)
    {
        this(manager.getTunerConfigurationManager()::saveConfigurations,
            manager.getDiscoveredTunerRegistry()::contains, manager.getDiscoveredTunerRegistry()::find);
    }

    TunerSettingsService(Runnable persist, Predicate<DiscoveredTuner> contains,
                         Function<String,DiscoveredTuner> find)
    {
        mPersist = Objects.requireNonNull(persist);
        mContains = Objects.requireNonNull(contains);
        mFind = Objects.requireNonNull(find);
        mExecutor = new ScheduledThreadPoolExecutor(1, task ->
        {
            Thread thread = new Thread(task, "tuner-settings-worker");
            thread.setDaemon(true);
            return thread;
        });
        mExecutor.setRemoveOnCancelPolicy(true);
    }

    public List<TunerSettingCatalog.SettingDescriptor> describe(DiscoveredTuner tuner)
    {
        Map<String,Object> pending = mPending.get(tuner);
        List<TunerSettingCatalog.SettingDescriptor> descriptors = TunerSettingCatalog.describe(tuner,
            pending == null ? Map.of() : pending);

        if(!TunerSettingCatalog.isRspDuoSlave(tuner))
        {
            return descriptors;
        }

        DiscoveredTuner master = rspDuoSibling(tuner);
        if(master == null || master.getTunerConfiguration() == null)
        {
            return descriptors;
        }

        Map<String,Object> masterPending = mPending.get(master);
        Map<String,TunerSettingCatalog.SettingDescriptor> shared = new HashMap<>();
        for(TunerSettingCatalog.SettingDescriptor descriptor: TunerSettingCatalog.describe(master,
            masterPending == null ? Map.of() : masterPending))
        {
            if("device".equals(descriptor.scope()))
            {
                shared.put(descriptor.id(), descriptor);
            }
        }

        return descriptors.stream().map(descriptor ->
        {
            TunerSettingCatalog.SettingDescriptor masterDescriptor = shared.get(descriptor.id());
            if(masterDescriptor == null)
            {
                return descriptor;
            }
            return new TunerSettingCatalog.SettingDescriptor(descriptor.id(), descriptor.label(),
                descriptor.group(), descriptor.kind(),
                masterDescriptor.value(), masterDescriptor.pendingValue(), descriptor.options(), descriptor.minimum(),
                descriptor.maximum(), descriptor.step(), descriptor.unit(), descriptor.scope(),
                descriptor.requiresIdle(), false);
        }).toList();
    }

    /**
     * Validates a setting using its hardware family provider and keeps only the latest requested value per field.
     * The worker persists a value only after the applicable idle requirement and live controller have accepted it.
     */
    public MutationResult set(DiscoveredTuner tuner, String settingId, Object submittedValue)
    {
        synchronized(mLifecycleLock)
        {
            ensureOpen();
            ensurePresent(tuner);
            TunerConfiguration configuration = configuration(tuner);
            if("device".equals(TunerSettingCatalog.scope(configuration, settingId)) &&
                TunerSettingCatalog.isRspDuoSlave(tuner))
            {
                throw new IllegalArgumentException("Shared RSPduo settings are edited from tuner 1");
            }
            Object value = TunerSettingCatalog.validate(tuner, settingId, submittedValue);
            mPending.compute(tuner, (ignored, existing) ->
            {
                Map<String,Object> updated = new HashMap<>(existing == null ? Map.of() : existing);
                if(TunerSettingCatalog.RESET_FREQUENCY_EXTENTS.equals(settingId))
                {
                    updated.remove(TunerSettingCatalog.MINIMUM_FREQUENCY);
                    updated.remove(TunerSettingCatalog.MAXIMUM_FREQUENCY);
                }
                else if(TunerSettingCatalog.isFrequencyExtent(settingId))
                {
                    updated.remove(TunerSettingCatalog.RESET_FREQUENCY_EXTENTS);
                }
                updated.put(settingId, value);
                return Map.copyOf(updated);
            });
            mErrors.remove(tuner);
            schedule(false);
        }
        return new MutationResult("queued", null);
    }

    public MutationResult cancel(DiscoveredTuner tuner, String settingId)
    {
        ensureOpen();
        ensurePresent(tuner);
        synchronized(mLifecycleLock)
        {
            ensureOpen();
            mPending.computeIfPresent(tuner, (ignored, existing) ->
            {
                Map<String,Object> updated = new HashMap<>(existing);
                updated.remove(settingId);
                return updated.isEmpty() ? null : Map.copyOf(updated);
            });
        }

        return new MutationResult("cancelled", null);
    }

    /**
     * Enable/disable runs on the hardware worker.  Disabling is intentionally disruptive and does not attempt to
     * restart channels; callers must confirm the action with the administrator before invoking it.
     */
    public MutationResult requestEnabled(DiscoveredTuner tuner, boolean enabled)
    {
        ensureOpen();
        ensurePresent(tuner);
        synchronized(mLifecycleLock)
        {
            ensureOpen();
            mPendingEnabled.put(Objects.requireNonNull(tuner), enabled);
            mErrors.remove(tuner);
            schedule(false);
        }
        return new MutationResult("queued", null);
    }

    public String error(DiscoveredTuner tuner)
    {
        return mErrors.get(tuner);
    }

    public boolean hasPending(DiscoveredTuner tuner)
    {
        Map<String,Object> settings = mPending.get(tuner);
        return (settings != null && !settings.isEmpty()) || mPendingEnabled.containsKey(tuner);
    }

    private TunerConfiguration configuration(DiscoveredTuner tuner)
    {
        Objects.requireNonNull(tuner);
        TunerConfiguration configuration = tuner.getTunerConfiguration();

        if(configuration == null)
        {
            throw new IllegalArgumentException("Tuner configuration is unavailable");
        }

        return configuration;
    }

    private void schedule(boolean delayed)
    {
        if(!mClosed && mWorkerScheduled.compareAndSet(false, true))
        {
            try
            {
                if(delayed)
                {
                    mExecutor.schedule(this::drain, 1, TimeUnit.SECONDS);
                }
                else
                {
                    mExecutor.execute(this::drain);
                }
            }
            catch(RejectedExecutionException e)
            {
                mWorkerScheduled.set(false);
            }
        }
    }

    private void drain()
    {
        try
        {
            if(mClosed)
            {
                return;
            }
            for(DiscoveredTuner tuner: List.copyOf(mPendingEnabled.keySet()))
            {
                if(mClosed) break;
                process(tuner);
            }

            for(DiscoveredTuner tuner: List.copyOf(mPending.keySet()))
            {
                if(mClosed) break;
                process(tuner);
            }
        }
        finally
        {
            mWorkerScheduled.set(false);

            if(!mPending.isEmpty() || !mPendingEnabled.isEmpty())
            {
                schedule(true);
            }
        }
    }

    private void process(DiscoveredTuner tuner)
    {
        if(!mContains.test(tuner) || tuner.getTunerStatus() == TunerStatus.REMOVED)
        {
            discardRemoved(tuner);
            return;
        }

        boolean persistNeeded = false;
        Map<String,Object> requested = mPending.get(tuner);
        boolean pendingEnabledChange = mPendingEnabled.containsKey(tuner);
        boolean needsReservation = pendingEnabledChange ||
            (requested != null && requested.keySet().stream().anyMatch(settingId ->
                TunerSettingCatalog.requiresIdle(configuration(tuner), settingId)));

        // Only disruptive settings and enable/disable take the reservation shared with channel allocation.  Live
        // gain controls follow the desktop editor's direct-setter path and do not make allocations fail fast.
        // A queued setting for a busy tuner should not briefly reserve it on every retry. Recheck after acquiring
        // the reservation below because channel allocation can begin between these two observations.
        if(needsReservation && (pendingEnabledChange || isIdle(tuner)) && tuner.tryAcquireForAllocation())
        {
            try
            {
                if(!mContains.test(tuner) || tuner.getTunerStatus() == TunerStatus.REMOVED)
                {
                    discardRemoved(tuner);
                    return;
                }

                Boolean enabled = mPendingEnabled.get(tuner);

                if(Boolean.FALSE.equals(enabled))
                {
                    tuner.setEnabled(false);
                    mPendingEnabled.remove(tuner, false);
                }

                Map<String,Object> settings = mPending.get(tuner);

                if(settings != null && !settings.isEmpty())
                {
                    Map<String,Object> snapshot = new HashMap<>(settings);
                    for(Map.Entry<String,Object> entry: snapshot.entrySet())
                    {
                        if(TunerSettingCatalog.isFrequencyExtent(entry.getKey()))
                        {
                            continue;
                        }
                        if(!TunerSettingCatalog.requiresIdle(configuration(tuner), entry.getKey()) || !isIdle(tuner) ||
                            !stillPending(tuner, Map.of(entry.getKey(), entry.getValue())))
                        {
                            continue;
                        }

                        ApplyResult result = apply(tuner, entry.getKey(), entry.getValue());

                        if(result == ApplyResult.APPLIED)
                        {
                            persistNeeded = true;
                        }

                        if(result != ApplyResult.RETRY)
                        {
                            consumePending(tuner, entry);
                        }
                    }
                }

                if(Boolean.TRUE.equals(mPendingEnabled.get(tuner)) && !hasPendingSettings(tuner))
                {
                    if(tuner.getTunerStatus() == TunerStatus.ERROR)
                    {
                        tuner.restart();
                    }
                    else
                    {
                        tuner.setEnabled(true);
                    }

                    mPendingEnabled.remove(tuner, true);
                }
            }
            catch(Exception e)
            {
                mLog.warn("Unable to perform requested tuner maintenance for [{}]", tuner.getId(), e);
                mErrors.put(tuner, "Unable to perform tuner maintenance");
                mPendingEnabled.remove(tuner);
            }
            finally
            {
                tuner.releaseAfterAllocation();
            }
        }

        if(!Boolean.FALSE.equals(mPendingEnabled.get(tuner)))
        {
            persistNeeded |= processLiveSettings(tuner);
        }

        if(persistNeeded)
        {
            // The settings worker may serialize to SQLite, but must never hold the reservation that makes channel
            // allocation fail fast (or a controller lock) during that work.
            try
            {
                mPersist.run();
            }
            catch(Exception e)
            {
                mErrors.put(tuner, "Applied tuner settings but could not save them");
                mLog.warn("Unable to save tuner settings for [{}]", tuner.getId(), e);
            }
        }
    }

    private boolean processLiveSettings(DiscoveredTuner tuner)
    {
        if(!mContains.test(tuner) || tuner.getTunerStatus() == TunerStatus.REMOVED)
        {
            discardRemoved(tuner);
            return false;
        }

        Map<String,Object> settings = mPending.get(tuner);

        if(settings == null || settings.isEmpty())
        {
            return false;
        }

        boolean persistNeeded = false;
        Map<String,Object> snapshot = new HashMap<>(settings);
        Map<String,Object> extents = new HashMap<>();
        snapshot.forEach((settingId, value) ->
        {
            if(TunerSettingCatalog.isFrequencyExtent(settingId))
            {
                extents.put(settingId, value);
            }
        });

        for(Map.Entry<String,Object> entry: snapshot.entrySet())
        {
            if(TunerSettingCatalog.isFrequencyExtent(entry.getKey()) ||
                TunerSettingCatalog.requiresIdle(configuration(tuner), entry.getKey()))
            {
                continue;
            }

            if(!stillPending(tuner, Map.of(entry.getKey(), entry.getValue())))
            {
                continue;
            }

            ApplyResult result = applyLive(tuner, entry.getKey(), entry.getValue());

            if(result == ApplyResult.APPLIED)
            {
                persistNeeded = true;
            }

            if(result != ApplyResult.RETRY)
            {
                consumePending(tuner, entry);
            }
        }

        // A paired min/max request is one transaction, never two independently visible intermediate ranges.  Run
        // it last so a rejected active range remains visible even if another live field succeeded in this pass.
        if(!extents.isEmpty() && stillPending(tuner, extents))
        {
            ApplyResult result = applyFrequencyExtents(tuner, extents);
            if(result == ApplyResult.APPLIED)
            {
                persistNeeded = true;
            }
            if(result != ApplyResult.RETRY)
            {
                extents.entrySet().forEach(entry -> consumePending(tuner, entry));
            }
        }

        return persistNeeded;
    }

    private void consumePending(DiscoveredTuner tuner, Map.Entry<String,Object> entry)
    {
        mPending.computeIfPresent(tuner, (ignored, current) ->
        {
            if(!Objects.equals(current.get(entry.getKey()), entry.getValue()))
            {
                return current;
            }

            Map<String,Object> updated = new HashMap<>(current);
            updated.remove(entry.getKey());
            return updated.isEmpty() ? null : Map.copyOf(updated);
        });
    }

    private void discardRemoved(DiscoveredTuner tuner)
    {
        mPending.remove(tuner);
        mPendingEnabled.remove(tuner);
        mErrors.remove(tuner);
    }

    private boolean hasPendingSettings(DiscoveredTuner tuner)
    {
        Map<String,Object> settings = mPending.get(tuner);
        return settings != null && !settings.isEmpty();
    }

    /** Do not apply a worker snapshot after an administrator has cancelled or superseded its queued value. */
    private boolean stillPending(DiscoveredTuner tuner, Map<String,Object> expected)
    {
        Map<String,Object> current = mPending.get(tuner);
        return current != null && expected.entrySet().stream()
            .allMatch(entry -> Objects.equals(current.get(entry.getKey()), entry.getValue()));
    }

    private static boolean isIdle(DiscoveredTuner tuner)
    {
        if(!tuner.hasTuner())
        {
            return true;
        }

        // A channelizer can still hold the sample-rate lock after its last channel count reaches zero.  Hardware
        // rate setters may update the USB device before FrequencyController silently rejects the locked new rate.
        TunerController controller = tuner.getTuner().getTunerController();
        return tuner.getTuner().getChannelSourceManager() != null &&
            tuner.getTuner().getChannelSourceManager().getTunerChannelCount() == 0 &&
            !controller.isLockedSampleRate();
    }

    private ApplyResult applyLive(DiscoveredTuner tuner, String settingId, Object value)
    {
        if(!mContains.test(tuner) || tuner.getTunerStatus() == TunerStatus.REMOVED)
        {
            discardRemoved(tuner);
            return ApplyResult.CONSUMED;
        }

        TunerConfiguration configuration = configuration(tuner);

        if(!TunerSettingCatalog.isEditableInCurrentMode(configuration, settingId))
        {
            mErrors.put(tuner, "Setting " + settingId + " is no longer editable in the current tuner mode");
            return ApplyResult.CONSUMED;
        }

        // A stopped/disabled tuner has no hardware to touch; save the selected configuration for its next start.
        // An enabled tuner in transition waits for the next worker pass instead of using a half-started controller.
        if(tuner.isEnabled() && (tuner.getTunerStatus() != TunerStatus.ENABLED || !tuner.hasTuner()))
        {
            return ApplyResult.RETRY;
        }

        TunerController controller = tuner.hasTuner() && tuner.getTunerStatus() == TunerStatus.ENABLED ?
            tuner.getTuner().getTunerController() : null;
        Object previous = TunerSettingCatalog.readRaw(configuration, settingId);
        boolean written = false;
        boolean applied = false;
        boolean locked = false;

        try
        {
            if(controller != null && ("frequency_correction_ppm".equals(settingId) ||
                "center_frequency_locked".equals(settingId)))
            {
                // Both controls share state with allocator retunes and automatic correction.  If that lock is
                // currently held, retry from the worker instead of waiting on an HTTP or decoder thread.
                locked = controller.getLock().tryLock();
                if(!locked)
                {
                    return ApplyResult.RETRY;
                }
            }
            TunerSettingCatalog.write(configuration, settingId, value);
            written = true;

            if(controller != null)
            {
                // Use field-specific setters only.  Do not reserve the lifecycle or reapply the entire hardware
                // configuration; a manual PPM correction may briefly retune, but never changes sample rate/gain.
                applyToHardware(controller, configuration, settingId);
            }

            applied = true;
            mErrors.remove(tuner);
            return ApplyResult.APPLIED;
        }
        catch(Exception e)
        {
            if(written && !applied)
            {
                // A decoder notification may fail after the hardware accepted PPM.  Keep the saved value aligned
                // with the actual controller instead of claiming a rollback that never reached the device.
                applied = controller != null && "frequency_correction_ppm".equals(settingId) &&
                    Double.compare(controller.getFrequencyCorrection(), ((Number)value).doubleValue()) == 0;
                if(!applied)
                {
                    TunerSettingCatalog.write(configuration, settingId, previous);
                }
            }

            mErrors.put(tuner, applied ? "Applied " + settingId + " but a receiver notification failed" :
                "Unable to apply " + settingId + "; tuner may have disconnected");
            mLog.warn("Unable to apply live tuner setting [{}] for [{}]", settingId, tuner.getId(), e);
            return applied ? ApplyResult.APPLIED : ApplyResult.CONSUMED;
        }
        finally
        {
            if(locked)
            {
                controller.getLock().unlock();
            }
        }
    }

    private ApplyResult apply(DiscoveredTuner tuner, String settingId, Object value)
    {
        TunerConfiguration configuration = configuration(tuner);
        DiscoveredTuner sibling = "device".equals(TunerSettingCatalog.scope(configuration, settingId)) ?
            rspDuoSibling(tuner) : null;

        if(sibling != null && !sibling.tryAcquireForAllocation())
        {
            return ApplyResult.RETRY;
        }

        try
        {
            if(sibling != null && (!mContains.test(sibling) || !isIdle(sibling)))
            {
                return ApplyResult.RETRY;
            }

            if(sibling != null && "sample_rate".equals(settingId) && sibling.isEnabled())
            {
                // The RSPduo bridge swallows slave sample-rate errors.  Requiring the slave to be disabled avoids
                // reporting success while its frequency controller or native buffer factory still uses the old rate.
                mErrors.put(tuner, "Disable RSPduo tuner 2 before changing the shared sample rate");
                return ApplyResult.RETRY;
            }

            return applyWithGroupReserved(tuner, settingId, value);
        }
        finally
        {
            if(sibling != null)
            {
                sibling.releaseAfterAllocation();
            }
        }
    }

    /**
     * Apply frequency limits together on the settings worker.  With active channels the limits govern subsequent
     * admission only: never move the current center or invalidate an already allocated channel.  When idle, retain
     * the established behavior of clamping and retuning the center to a newly selected range.
     */
    private ApplyResult applyFrequencyExtents(DiscoveredTuner tuner, Map<String,Object> requested)
    {
        TunerConfiguration configuration = configuration(tuner);
        TunerController controller = tuner.hasTuner() ? tuner.getTuner().getTunerController() : null;
        if(tuner.isEnabled() && (tuner.getTunerStatus() != TunerStatus.ENABLED || controller == null))
        {
            return ApplyResult.RETRY;
        }
        // An idle retune must reserve the lifecycle against a new allocation.  An active limit change only adjusts
        // admission bounds, so it does not reserve a busy tuner and make decoder-originated allocations fail fast.
        boolean reserved = false;
        if(isIdle(tuner))
        {
            if(!tuner.tryAcquireForAllocation())
            {
                return ApplyResult.RETRY;
            }
            reserved = true;
        }
        boolean locked = false;
        long previousMinimum = 0;
        long previousMaximum = 0;
        long previousFrequency = 0;
        boolean hardwareChanged = false;

        try
        {
            if(controller != null)
            {
                locked = controller.getLock().tryLock();
                if(!locked)
                {
                    return ApplyResult.RETRY;
                }
                if(!reserved && isIdle(tuner))
                {
                    // It became idle after the initial observation.  Retry with the lifecycle reservation rather
                    // than racing a new channel allocation if the new range requires a center retune.
                    return ApplyResult.RETRY;
                }
            }

            long liveSampleRate = controller != null ? Math.round(controller.getSampleRate()) : 0;
            long sampleRate = liveSampleRate > 0 ? liveSampleRate : configuration.getConfiguredSampleRate();
            TunerSettingCatalog.FrequencyExtents extents = TunerSettingCatalog.resolveFrequencyExtents(
                configuration, requested, sampleRate);
            long frequency = controller != null ? controller.getFrequency() : configuration.getFrequency();
            long clampedFrequency = Math.max(extents.minimumHz(), Math.min(extents.maximumHz(), frequency));

            if(controller != null)
            {
                if(!isIdle(tuner))
                {
                    if(clampedFrequency != frequency)
                    {
                        throw new IllegalArgumentException("Frequency limits exclude the current center; " +
                            "stop its channels or choose limits containing the center");
                    }
                    ChannelSourceManager channels = tuner.getTuner().getChannelSourceManager();
                    if(channels == null)
                    {
                        throw new IllegalArgumentException("Active channels are unavailable for limit validation");
                    }
                    var allocated = channels.getTunerChannels();
                    if(channels.getTunerChannelCount() > 0 && allocated.isEmpty())
                    {
                        throw new IllegalArgumentException("Active channels are unavailable for limit validation");
                    }
                    for(TunerChannel channel: allocated)
                    {
                        if(channel.getMinFrequency() < extents.minimumHz() ||
                            channel.getMaxFrequency() > extents.maximumHz())
                        {
                            throw new IllegalArgumentException("Frequency limits exclude an active channel; " +
                                "stop that channel or choose wider limits");
                        }
                    }
                }
                previousMinimum = controller.getMinimumFrequency();
                previousMaximum = controller.getMaximumFrequency();
                previousFrequency = frequency;
                if(clampedFrequency != frequency)
                {
                    // Only an idle tuner can reach this branch.  First widen, retune, then narrow, keeping both
                    // centers valid throughout even when the old and new ranges do not overlap.
                    controller.setFrequencyExtents(Math.min(previousMinimum, extents.minimumHz()),
                        Math.max(previousMaximum, extents.maximumHz()));
                    hardwareChanged = true;
                    controller.setFrequency(clampedFrequency);
                }
                else
                {
                    hardwareChanged = true;
                }
                controller.setFrequencyExtents(extents.minimumHz(), extents.maximumHz());
            }

            configuration.setMinimumFrequency(extents.minimumHz());
            configuration.setMaximumFrequency(extents.maximumHz());
            configuration.setFrequency(clampedFrequency);
            mErrors.remove(tuner);
            return ApplyResult.APPLIED;
        }
        catch(Exception e)
        {
            if(controller != null && hardwareChanged)
            {
                try
                {
                    if(controller.getFrequency() != previousFrequency)
                    {
                        controller.setFrequencyExtents(Math.min(previousMinimum, controller.getMinimumFrequency()),
                            Math.max(previousMaximum, controller.getMaximumFrequency()));
                        controller.setFrequency(previousFrequency);
                    }
                    controller.setFrequencyExtents(previousMinimum, previousMaximum);
                }
                catch(Exception rollback)
                {
                    e.addSuppressed(rollback);
                }
            }
            if(e instanceof IllegalArgumentException && !hardwareChanged)
            {
                mErrors.put(tuner, e.getMessage());
            }
            else
            {
                mErrors.put(tuner, "Unable to apply frequency limits; tuner may have disconnected");
                mLog.warn("Unable to apply tuner frequency limits for [{}]", tuner.getId(), e);
            }
            return ApplyResult.CONSUMED;
        }
        finally
        {
            if(locked)
            {
                controller.getLock().unlock();
            }
            if(reserved)
            {
                tuner.releaseAfterAllocation();
            }
        }
    }

    private DiscoveredTuner rspDuoSibling(DiscoveredTuner tuner)
    {
        if(tuner instanceof DiscoveredRspDuoTuner1 master)
        {
            return mFind.apply(master.getSlaveId());
        }
        if(tuner instanceof DiscoveredRspDuoTuner2 slave)
        {
            return mFind.apply(slave.getMasterId());
        }
        return null;
    }

    private ApplyResult applyWithGroupReserved(DiscoveredTuner tuner, String settingId, Object value)
    {
        TunerConfiguration configuration = configuration(tuner);

        if(!TunerSettingCatalog.isEditableInCurrentMode(configuration, settingId))
        {
            // Another queued setting or the local editor changed the gain mode since submission.
            mErrors.put(tuner, "Setting " + settingId + " is no longer editable in the current tuner mode");
            return ApplyResult.CONSUMED;
        }

        if("sample_rate".equals(settingId))
        {
            try
            {
                TunerSettingCatalog.validateSampleRateSpan(configuration, value);
            }
            catch(IllegalArgumentException e)
            {
                mErrors.put(tuner, e.getMessage());
                return ApplyResult.CONSUMED;
            }
        }

        Object previous = TunerSettingCatalog.readRaw(configuration, settingId);
        boolean written = false;
        boolean applied = false;

        try
        {
            if(tuner.hasTuner())
            {
                TunerController controller = tuner.getTuner().getTunerController();

                // Do not contend with an in-flight retune or sample-rate update.  This worker will retry later.
                if(!controller.getLock().tryLock())
                {
                    return ApplyResult.RETRY;
                }

                try
                {
                    if(TunerSettingCatalog.requiresIdle(configuration, settingId) && !isIdle(tuner))
                    {
                        return ApplyResult.RETRY;
                    }

                    if("lna".equals(settingId) && configuration instanceof RspTunerConfiguration &&
                        controller instanceof RspTunerController<?> rsp)
                    {
                        // The valid RSP LNA range depends on the current tuned frequency (and for RSPduo, AM port).
                        // It may have narrowed since this request was validated on the HTTP thread.  Check again
                        // under the controller lock so ControlRsp.setGain cannot silently clamp the hardware value.
                        int requested = ((Number)value).intValue();
                        int maximum = rsp.getControlRsp().getMaximumLNASetting();

                        if(requested < 0 || requested > maximum)
                        {
                            mErrors.put(tuner, "LNA state " + requested + " is no longer valid at this frequency " +
                                "(maximum " + maximum + "); choose another value");
                            return ApplyResult.CONSUMED;
                        }
                    }

                    TunerSettingCatalog.write(configuration, settingId, value);
                    written = true;
                    applyToHardware(controller, configuration, settingId);
                    applied = true;
                }
                finally
                {
                    controller.getLock().unlock();
                }
            }
            else
            {
                TunerSettingCatalog.write(configuration, settingId, value);
                written = true;
                applied = true;
            }

            syncSharedRspDuoConfiguration(tuner, settingId);
            mErrors.remove(tuner);
            return ApplyResult.APPLIED;
        }
        catch(Exception e)
        {
            // Do not claim the old value after a successful hardware write.  Persistence/sibling synchronization
            // can still fail, in which case the live value must remain visible and the admin must see the error.
            if(written && !applied)
            {
                TunerSettingCatalog.write(configuration, settingId, previous);
            }
            mErrors.put(tuner, applied ?
                "Applied " + settingId + " but could not synchronize it" :
                "Unable to apply " + settingId);
            mLog.warn("Unable to apply tuner setting [{}] for [{}]", settingId, tuner.getId(), e);
            return applied ? ApplyResult.APPLIED : ApplyResult.CONSUMED;
        }
    }

    private enum ApplyResult
    {
        RETRY,
        CONSUMED,
        APPLIED
    }

    private void syncSharedRspDuoConfiguration(DiscoveredTuner tuner, String settingId)
    {
        if(!(tuner instanceof DiscoveredRspDuoTuner1) ||
            !"device".equals(TunerSettingCatalog.scope(configuration(tuner), settingId)))
        {
            return;
        }

        DiscoveredTuner sibling = rspDuoSibling(tuner);

        if(sibling != null && TunerSettingCatalog.isRspDuoSlave(sibling) &&
            sibling.getTunerConfiguration() != null)
        {
            Object value = TunerSettingCatalog.readRaw(configuration(tuner), settingId);
            TunerSettingCatalog.write(sibling.getTunerConfiguration(), settingId, value);
        }
    }

    /**
     * RSP controller.apply() deliberately logs and swallows several SDRplay API failures.  Use its field setters for
     * a web mutation so that a failed hardware call cannot be reported and persisted as an applied setting.
     */
    private static void applyToHardware(TunerController controller, TunerConfiguration configuration,
                                        String settingId) throws Exception
    {
        if("frequency_mhz".equals(settingId))
        {
            controller.setFrequency(configuration.getFrequency());
            return;
        }

        // These are common to all physical tuner families.  Never fall through to controller.apply(configuration)
        // while channels are running: that method also retunes and reapplies sample rate and gain controls.
        if("frequency_correction_ppm".equals(settingId))
        {
            controller.setFrequencyCorrection(configuration.getFrequencyCorrection());
            return;
        }
        if("center_frequency_locked".equals(settingId))
        {
            controller.setCenterFrequencyLocked(configuration.isCenterFrequencyLocked());
            return;
        }

        if(!TunerSettingCatalog.requiresIdle(configuration, settingId))
        {
            if(controller instanceof AirspyTunerController airspy &&
                configuration instanceof AirspyTunerConfiguration config)
            {
                switch(settingId)
                {
                    case "gain" -> airspy.setGain(config.getGain());
                    case "if_gain" -> airspy.setIFGain(config.getIFGain());
                    case "mixer_gain" -> airspy.setMixerGain(config.getMixerGain());
                    case "lna_gain" -> airspy.setLNAGain(config.getLNAGain());
                    case "mixer_agc" -> airspy.setMixerAGC(config.isMixerAGC());
                    case "lna_agc" -> airspy.setLNAAGC(config.isLNAAGC());
                    default -> throw new IllegalArgumentException("Unsupported live Airspy setting");
                }
                return;
            }
            if(controller instanceof HydraSdrTunerController hydra &&
                configuration instanceof HydraSdrTunerConfiguration config)
            {
                switch(settingId)
                {
                    case "gain" -> hydra.setGain(config.getGain());
                    case "if_gain" -> hydra.setIFGain(config.getIFGain());
                    case "mixer_gain" -> hydra.setMixerGain(config.getMixerGain());
                    case "lna_gain" -> hydra.setLNAGain(config.getLNAGain());
                    case "mixer_agc" -> hydra.setMixerAGC(config.isMixerAGC());
                    case "lna_agc" -> hydra.setLNAAGC(config.isLNAAGC());
                    default -> throw new IllegalArgumentException("Unsupported live HydraSDR setting");
                }
                return;
            }
            if(controller instanceof AirspyHfTunerController hf &&
                configuration instanceof AirspyHfTunerConfiguration config)
            {
                switch(settingId)
                {
                    case "agc" -> hf.setAgc(config.isAgc());
                    case "lna" -> hf.setLna(config.isLna());
                    case "attenuation" -> hf.setAttenuation(config.getAttenuation());
                    default -> throw new IllegalArgumentException("Unsupported live Airspy HF setting");
                }
                return;
            }
            if(controller instanceof HackRFTunerController hackrf &&
                configuration instanceof HackRFTunerConfiguration config)
            {
                switch(settingId)
                {
                    case "lna_gain" -> hackrf.setLNAGain(config.getLNAGain());
                    case "vga_gain" -> hackrf.setVGAGain(config.getVGAGain());
                    case "amplifier" -> hackrf.setAmplifierEnabled(config.getAmplifierEnabled());
                    default -> throw new IllegalArgumentException("Unsupported live HackRF setting");
                }
                return;
            }
            if(controller instanceof RTL2832TunerController rtl &&
                configuration instanceof RTL2832TunerConfiguration)
            {
                if(configuration instanceof E4KTunerConfiguration e4k &&
                    rtl.getEmbeddedTuner() instanceof E4KEmbeddedTuner embedded)
                {
                    switch(settingId)
                    {
                        case "master_gain" -> embedded.setGain(e4k.getMasterGain(), true);
                        case "mixer_gain" -> embedded.setMixerGain(e4k.getMixerGain(), true);
                        case "lna_gain" -> embedded.setLNAGain(e4k.getLNAGain(), true);
                        case "if_gain" -> embedded.setIFGain(e4k.getIFGain(), true);
                        default -> throw new IllegalArgumentException("Unsupported live E4000 setting");
                    }
                    return;
                }
                if(configuration instanceof FC0013TunerConfiguration fc0013 &&
                    rtl.getEmbeddedTuner() instanceof FC0013EmbeddedTuner embedded)
                {
                    embedded.setGain(fc0013.getAGC(), fc0013.getLnaGain());
                    return;
                }
                if(configuration instanceof R8xTunerConfiguration r8x &&
                    rtl.getEmbeddedTuner() instanceof R8xEmbeddedTuner embedded)
                {
                    switch(settingId)
                    {
                        case "master_gain" -> embedded.setGain(r8x.getMasterGain(), true);
                        case "mixer_gain" -> embedded.setMixerGain(r8x.getMixerGain(), true);
                        case "lna_gain" -> embedded.setLNAGain(r8x.getLNAGain(), true);
                        case "vga_gain" -> embedded.setVGAGain(r8x.getVGAGain(), true);
                        default -> throw new IllegalArgumentException("Unsupported live R8x setting");
                    }
                    return;
                }
            }
        }

        if(!(controller instanceof RspTunerController<?> rsp) ||
            !(configuration instanceof RspTunerConfiguration rspConfiguration))
        {
            controller.apply(configuration);
            return;
        }

        Object control = rsp.getControlRsp();

        switch(settingId)
        {
            case "automatic_ppm" -> controller.getTunerFrequencyErrorManager()
                .setEnabled(configuration.getAutoPPMCorrectionEnabled());
            case "sample_rate" -> rsp.setSampleRate(rspConfiguration.getSampleRate());
            case "lna" -> rsp.getControlRsp().setGain(rspConfiguration.getLNA(),
                rspConfiguration.getBasebandGainReduction());
            case "baseband_gain_reduction" -> rsp.getControlRsp().setGain(rsp.getControlRsp().getLNA(),
                rspConfiguration.getBasebandGainReduction());
            case "agc_mode" -> rsp.getControlRsp().setAgcMode(rspConfiguration.getAgcMode());
            default ->
            {
                String method = switch(settingId)
                {
                    case "rf_notch" -> "setRfNotch";
                    case "dab_notch" -> "setRfDabNotch";
                    case "bias_t" -> "setBiasT";
                    case "antenna" -> configuration.getTunerType() ==
                        io.github.dsheirer.source.tuner.TunerType.RSP_2 ? "setAntennaSelection" : "setAntenna";
                    case "external_reference" -> "setExternalReferenceOutput";
                    case "am_port" -> "setAmPort";
                    case "am_notch" -> "setAmNotch";
                    case "hdr_mode" -> "setHighDynamicRange";
                    case "hdr_bandwidth" -> "setHdrModeBandwidth";
                    default -> throw new IllegalArgumentException("Unsupported live RSP setting");
                };

                Object value = TunerSettingCatalog.readRaw(configuration, settingId);
                Method setter = control.getClass().getMethod(method, value instanceof Boolean ? boolean.class :
                    value.getClass());

                try
                {
                    setter.invoke(control, value);
                }
                catch(InvocationTargetException e)
                {
                    throw e.getCause() instanceof Exception exception ? exception : e;
                }
            }
        }
    }

    private void ensureOpen()
    {
        if(mClosed)
        {
            throw new IllegalStateException("Tuner settings service is stopping");
        }
    }

    private void ensurePresent(DiscoveredTuner tuner)
    {
        if(tuner == null || !mContains.test(tuner))
        {
            throw new IllegalArgumentException("Tuner is no longer available");
        }
    }

    @Override
    public void close()
    {
        closeWithin(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Stop future admin work, discard requests not yet claimed by the worker, and wait for a claimed hardware write
     * (including its save) to finish before tuner/channel teardown. A timeout is an explicit unsafe shutdown state:
     * the caller must not assume the worker is quiescent and proceed with tuner disposal.
     */
    void closeWithin(long timeout, TimeUnit unit)
    {
        if(timeout < 0 || unit == null)
        {
            throw new IllegalArgumentException("Shutdown timeout is invalid");
        }

        synchronized(mLifecycleLock)
        {
            if(!mClosed)
            {
                mClosed = true;
                mPending.clear();
                mPendingEnabled.clear();
                mExecutor.getQueue().clear();
                // Do not interrupt a USB/native setter in progress. Its completion is awaited below.
                mExecutor.shutdown();
            }
        }

        try
        {
            if(!mExecutor.awaitTermination(timeout, unit))
            {
                throw new IllegalStateException("Tuner settings worker did not stop; hardware maintenance may still " +
                    "be active");
            }
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for tuner settings worker to stop", exception);
        }
    }

    public record MutationResult(String status, String message)
    {
    }
}
