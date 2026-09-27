package io.github.dsheirer.source.tuner.manager;

import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.controller.channel.ChannelProcessingManager.TunerChannelAssignment;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admin tuner settings and operator lifecycle. Settings are one-shot: they apply now or fail now. A dedicated worker
 * handles finite hardware state transitions, but never retains a setting until the tuner becomes idle.
 */
public final class TunerSettingsService implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(TunerSettingsService.class);
    private static final long SHUTDOWN_WAIT_SECONDS = 8;
    private static final int LIFECYCLE_WORKER_COUNT = 2;
    private static final int LIFECYCLE_QUEUE_CAPACITY = 32;
    private static final AtomicInteger LIFECYCLE_THREAD_SEQUENCE = new AtomicInteger();
    private final Runnable mPersist;
    private final Predicate<DiscoveredTuner> mContains;
    private final Function<String,DiscoveredTuner> mFind;
    private final ChannelProcessingManager mChannelProcessingManager;
    private final ThreadPoolExecutor mExecutor;
    private final Map<DiscoveredTuner,String> mTransitions = new ConcurrentHashMap<>();
    private final Set<DiscoveredTuner> mLifecycleReservations = ConcurrentHashMap.newKeySet();
    private final Map<DiscoveredTuner,List<RememberedChannel>> mStoppedChannels = new ConcurrentHashMap<>();
    private final Map<DiscoveredTuner,RestoreResult> mRestoreResults = new ConcurrentHashMap<>();
    private final Map<DiscoveredTuner,String> mErrors = new ConcurrentHashMap<>();
    private static final int MAXIMUM_REMEMBERED_CHANNELS = 64;
    /** Serializes one-shot setting writes with close; decoder and channel allocation paths never take this lock. */
    private final Object mLifecycleLock = new Object();
    private volatile boolean mClosed;

    public TunerSettingsService(TunerManager manager)
    {
        this(manager, null);
    }

    public TunerSettingsService(TunerManager manager, ChannelProcessingManager channelProcessingManager)
    {
        this(manager.getTunerConfigurationManager()::saveConfigurations,
            manager.getDiscoveredTunerRegistry()::contains, manager.getDiscoveredTunerRegistry()::find,
            channelProcessingManager);
    }

    TunerSettingsService(Runnable persist, Predicate<DiscoveredTuner> contains,
                         Function<String,DiscoveredTuner> find)
    {
        this(persist, contains, find, null);
    }

    TunerSettingsService(Runnable persist, Predicate<DiscoveredTuner> contains,
                         Function<String,DiscoveredTuner> find, ChannelProcessingManager channelProcessingManager)
    {
        mPersist = Objects.requireNonNull(persist);
        mContains = Objects.requireNonNull(contains);
        mFind = Objects.requireNonNull(find);
        mChannelProcessingManager = channelProcessingManager;
        mExecutor = new ThreadPoolExecutor(LIFECYCLE_WORKER_COUNT, LIFECYCLE_WORKER_COUNT, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(LIFECYCLE_QUEUE_CAPACITY), task ->
            {
                Thread thread = new Thread(task,
                    "tuner-lifecycle-" + LIFECYCLE_THREAD_SEQUENCE.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    public List<TunerSettingCatalog.SettingDescriptor> describe(DiscoveredTuner tuner)
    {
        List<TunerSettingCatalog.SettingDescriptor> descriptors = TunerSettingCatalog.describe(tuner);

        if(tuner instanceof DiscoveredRspDuoTuner1 && rspDuoSibling(tuner) instanceof DiscoveredTuner slave &&
            !isFullyDisabled(slave))
        {
            descriptors = descriptors.stream().map(descriptor -> "sample_rate".equals(descriptor.id()) ?
                unavailable(descriptor, "Disable tuner 2") : descriptor).toList();
        }

        if(!TunerSettingCatalog.isRspDuoSlave(tuner))
        {
            return descriptors;
        }

        DiscoveredTuner master = rspDuoSibling(tuner);
        if(master == null || master.getTunerConfiguration() == null)
        {
            return descriptors;
        }

        Map<String,TunerSettingCatalog.SettingDescriptor> shared = new HashMap<>();
        for(TunerSettingCatalog.SettingDescriptor descriptor: TunerSettingCatalog.describe(master))
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
                masterDescriptor.value(), descriptor.options(), descriptor.minimum(), descriptor.maximum(),
                descriptor.step(), descriptor.unit(), descriptor.scope(), false, descriptor.availability(),
                "Tuner 1", descriptor.dependencies());
        }).toList();
    }

    private static TunerSettingCatalog.SettingDescriptor unavailable(
        TunerSettingCatalog.SettingDescriptor descriptor, String reason)
    {
        return new TunerSettingCatalog.SettingDescriptor(descriptor.id(), descriptor.label(), descriptor.group(),
            descriptor.kind(), descriptor.value(), descriptor.options(), descriptor.minimum(), descriptor.maximum(),
            descriptor.step(), descriptor.unit(), descriptor.scope(), false, descriptor.availability(), reason,
            descriptor.dependencies());
    }

    /**
     * Validates and applies one setting once. A busy or incompatible setting fails and is never retained for retry.
     */
    public MutationResult set(DiscoveredTuner tuner, String settingId, Object submittedValue)
    {
        synchronized(mLifecycleLock)
        {
            ensureOpen();
            ensurePresent(tuner);
            if(lifecycleGroup(tuner).stream().anyMatch(mLifecycleReservations::contains))
            {
                throw new SettingUnavailableException("Tuner busy");
            }
            TunerConfiguration configuration = configuration(tuner);
            if("device".equals(TunerSettingCatalog.scope(configuration, settingId)) &&
                TunerSettingCatalog.isRspDuoSlave(tuner))
            {
                throw new IllegalArgumentException("Shared RSPduo settings are edited from tuner 1");
            }
            Object value = TunerSettingCatalog.validate(tuner, settingId, submittedValue);
            if(TunerSettingCatalog.requiresSetup(configuration, settingId) &&
                tuner.getOperatorState() == DiscoveredTuner.OperatorState.LIVE)
            {
                throw new SettingUnavailableException("Use Setup");
            }
            if("sample_rate".equals(settingId) && tuner instanceof DiscoveredRspDuoTuner1 &&
                rspDuoSibling(tuner) instanceof DiscoveredTuner slave && !isFullyDisabled(slave))
            {
                throw new SettingUnavailableException("Disable tuner 2");
            }
            ApplyResult result = TunerSettingCatalog.isFrequencyExtent(settingId) ?
                applyFrequencyExtents(tuner, Map.of(settingId, value)) : apply(tuner, settingId, value);
            if(result == ApplyResult.RETRY)
            {
                throw new SettingUnavailableException("Tuner busy");
            }
            if(result != ApplyResult.APPLIED)
            {
                throw new IllegalArgumentException(Objects.requireNonNullElse(mErrors.get(tuner), "Change failed"));
            }
            try
            {
                mPersist.run();
            }
            catch(Exception e)
            {
                mErrors.put(tuner, "Saved value unavailable");
                mLog.warn("Unable to save tuner settings for [{}]", tuner.getId(), e);
            }
        }
        return new MutationResult("applied", null);
    }

    public MutationResult requestState(DiscoveredTuner tuner, DiscoveredTuner.OperatorState state)
    {
        synchronized(mLifecycleLock)
        {
            ensureOpen();
            ensurePresent(tuner);
            Objects.requireNonNull(state);
            String transition = switch(state)
            {
                case DISABLED -> "disabling";
                case SETUP -> "starting_setup";
                case LIVE -> "going_live";
            };
            List<DiscoveredTuner> group = lifecycleGroup(tuner);
            reserveTransition(tuner, group, transition);
            mErrors.remove(tuner);
            mRestoreResults.remove(tuner);
            submitLifecycle(tuner, group, () -> changeState(tuner, state));
        }
        return new MutationResult("accepted", null);
    }

    public MutationResult restoreStoppedChannels(DiscoveredTuner tuner)
    {
        synchronized(mLifecycleLock)
        {
            ensureOpen();
            ensurePresent(tuner);
            if(tuner.getOperatorState() != DiscoveredTuner.OperatorState.SETUP)
            {
                throw new SettingUnavailableException("Use Setup");
            }
            List<DiscoveredTuner> group = lifecycleGroup(tuner);
            reserveTransition(tuner, group, "restoring");
            mErrors.remove(tuner);
            mRestoreResults.remove(tuner);
            submitLifecycle(tuner, group, () -> restoreOnce(tuner));
        }
        return new MutationResult("accepted", null);
    }

    public String error(DiscoveredTuner tuner)
    {
        return mErrors.get(tuner);
    }

    public String transition(DiscoveredTuner tuner)
    {
        return mTransitions.get(tuner);
    }

    public List<ChannelInfo> stoppedChannels(DiscoveredTuner tuner)
    {
        return mStoppedChannels.getOrDefault(tuner, List.of()).stream().map(RememberedChannel::info).toList();
    }

    public RestoreResult restoreResult(DiscoveredTuner tuner)
    {
        return mRestoreResults.get(tuner);
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

    private void reserveTransition(DiscoveredTuner target, List<DiscoveredTuner> tuners, String transition)
    {
        if(tuners.stream().anyMatch(mLifecycleReservations::contains))
        {
            throw new SettingUnavailableException("Tuner busy");
        }
        mLifecycleReservations.addAll(tuners);
        mTransitions.put(target, transition);
    }

    private void submitLifecycle(DiscoveredTuner tuner, List<DiscoveredTuner> group, Runnable operation)
    {
        try
        {
            mExecutor.execute(() ->
            {
                try
                {
                    if(!mClosed && mContains.test(tuner))
                    {
                        operation.run();
                    }
                }
                catch(Exception e)
                {
                    String message = e instanceof IllegalStateException &&
                        ("Tuner unavailable".equals(e.getMessage()) || "Use Setup".equals(e.getMessage())) ?
                        e.getMessage() : "Change failed";
                    mErrors.put(tuner, message);
                    mLog.warn("Unable to change tuner state for [{}]", tuner.getId(), e);
                }
                finally
                {
                    mTransitions.remove(tuner);
                    mLifecycleReservations.removeAll(group);
                }
            });
        }
        catch(RejectedExecutionException e)
        {
            mTransitions.remove(tuner);
            mLifecycleReservations.removeAll(group);
            throw new IllegalStateException("Tuner maintenance is unavailable", e);
        }
    }

    private void changeState(DiscoveredTuner tuner, DiscoveredTuner.OperatorState requested)
    {
        DiscoveredTuner.OperatorState current = tuner.getOperatorState();
        DiscoveredTuner pairedSlave = tuner instanceof DiscoveredRspDuoTuner1 ? rspDuoSibling(tuner) : null;
        switch(requested)
        {
            case DISABLED ->
            {
                leaveLiveGroup(pairedSlave == null ? List.of(tuner) : List.of(tuner, pairedSlave));
                tuner.setEnabled(false);
                if(pairedSlave != null)
                {
                    // The manager normally cascades this from tuner 1. Explicitly align it for error paths and tests.
                    pairedSlave.setEnabled(false);
                }
            }
            case SETUP ->
            {
                if(pairedSlave == null)
                {
                    if(current == DiscoveredTuner.OperatorState.LIVE)
                    {
                        leaveLive(tuner);
                    }
                    if(!tuner.enterSetup())
                    {
                        throw new IllegalStateException("Tuner unavailable");
                    }
                }
                else
                {
                    List<DiscoveredTuner> group = List.of(tuner, pairedSlave);
                    leaveLiveGroup(group);
                    group.forEach(DiscoveredTuner::prepareForSetup);
                    if(!tuner.enterSetup() || !pairedSlave.enterSetup())
                    {
                        tuner.setEnabled(false);
                        pairedSlave.setEnabled(false);
                        throw new IllegalStateException("Tuner unavailable");
                    }
                }
            }
            case LIVE ->
            {
                if(current == DiscoveredTuner.OperatorState.DISABLED)
                {
                    throw new IllegalStateException("Use Setup");
                }
                if(!tuner.enterLive())
                {
                    throw new IllegalStateException("Tuner unavailable");
                }
                mStoppedChannels.remove(tuner);
            }
        }
    }

    private void leaveLive(DiscoveredTuner tuner)
    {
        leaveLiveGroup(List.of(tuner));
    }

    /** Gate every member before the first channel snapshot or paired-device hardware cascade. */
    private void leaveLiveGroup(List<DiscoveredTuner> tuners)
    {
        List<DiscoveredTuner> live = tuners.stream()
            .filter(member -> member.getOperatorState() == DiscoveredTuner.OperatorState.LIVE).toList();
        for(DiscoveredTuner member: live)
        {
            if(!member.holdForSetup())
            {
                throw new IllegalStateException("Tuner unavailable");
            }
        }
        live.forEach(this::snapshotAndStopChannels);
    }

    private void snapshotAndStopChannels(DiscoveredTuner tuner)
    {
        if(mChannelProcessingManager == null)
        {
            mStoppedChannels.put(tuner, List.of());
            return;
        }
        List<TunerChannelAssignment> assignments = new ArrayList<>(
            mChannelProcessingManager.getChannelsUsingTuner(tuner.getId()));
        assignments.sort((left, right) -> Boolean.compare(right.restorable(), left.restorable()));
        List<RememberedChannel> remembered = new ArrayList<>();
        for(TunerChannelAssignment assignment: assignments)
        {
            try
            {
                mChannelProcessingManager.stop(assignment.channel());
                if(remembered.size() < MAXIMUM_REMEMBERED_CHANNELS)
                {
                    remembered.add(new RememberedChannel(new ChannelInfo(assignment.id(), assignment.name()),
                        assignment.channel(), assignment.restorable()));
                }
            }
            catch(ChannelException e)
            {
                mLog.warn("Unable to stop channel [{}] for tuner setup", assignment.id(), e);
            }
        }
        mStoppedChannels.put(tuner, List.copyOf(remembered));
    }

    private void restoreOnce(DiscoveredTuner tuner)
    {
        List<RememberedChannel> remembered = mStoppedChannels.getOrDefault(tuner, List.of());
        List<ChannelInfo> failed = new ArrayList<>();
        tuner.beginRestoreAllocation();
        try
        {
            for(RememberedChannel channel: remembered)
            {
                if(!channel.restorable())
                {
                    continue;
                }
                if(mChannelProcessingManager == null)
                {
                    failed.add(channel.info());
                    continue;
                }
                try
                {
                    mChannelProcessingManager.start(channel.channel());
                }
                catch(ChannelException | RuntimeException e)
                {
                    failed.add(channel.info());
                }
            }
        }
        finally
        {
            tuner.endRestoreAllocation();
        }
        if(!tuner.enterLive())
        {
            throw new IllegalStateException("Tuner unavailable");
        }
        mStoppedChannels.remove(tuner);
        mRestoreResults.put(tuner, new RestoreResult(List.copyOf(failed)));
    }

    private static boolean isIdle(DiscoveredTuner tuner)
    {
        if(!tuner.hasTuner())
        {
            return true;
        }

        // A channelizer can still hold the sample-rate lock after its last channel count reaches zero. Hardware rate
        // setters may update the USB device before FrequencyController silently rejects the locked new rate.
        TunerController controller = tuner.getTuner().getTunerController();
        return tuner.getTuner().getChannelSourceManager() != null &&
            tuner.getTuner().getChannelSourceManager().getTunerChannelCount() == 0 &&
            !controller.isLockedSampleRate();
    }

    private ApplyResult apply(DiscoveredTuner tuner, String settingId, Object value)
    {
        TunerConfiguration configuration = configuration(tuner);
        DiscoveredTuner sibling = "device".equals(TunerSettingCatalog.scope(configuration, settingId)) ?
            rspDuoSibling(tuner) : null;
        boolean tunerReserved = false;

        if("frequency_mhz".equals(settingId))
        {
            if(!tuner.tryAcquireForAllocation())
            {
                return ApplyResult.RETRY;
            }
            tunerReserved = true;
        }

        try
        {
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

                if(sibling != null && "sample_rate".equals(settingId) && !isFullyDisabled(sibling))
                {
                    mErrors.put(tuner, "Disable tuner 2");
                    return ApplyResult.CONSUMED;
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
        finally
        {
            if(tunerReserved)
            {
                tuner.releaseAfterAllocation();
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
                        throw new IllegalArgumentException("Center is outside limits");
                    }
                    ChannelSourceManager channels = tuner.getTuner().getChannelSourceManager();
                    if(channels == null)
                    {
                        throw new IllegalArgumentException("Channels unavailable");
                    }
                    var allocated = channels.getTunerChannels();
                    if(channels.getTunerChannelCount() > 0 && allocated.isEmpty())
                    {
                        throw new IllegalArgumentException("Channels unavailable");
                    }
                    for(TunerChannel channel: allocated)
                    {
                        if(channel.getMinFrequency() < extents.minimumHz() ||
                            channel.getMaxFrequency() > extents.maximumHz())
                        {
                            throw new IllegalArgumentException("Channel is outside limits");
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
                mErrors.put(tuner, "Frequency limit change failed");
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
        if(tuner instanceof DiscoveredRspDuoTuner1 master &&
            master.getDeviceInfo().getDeviceSelectionMode().isMasterMode())
        {
            DiscoveredTuner sibling = mFind.apply(master.getSlaveId());
            return sibling instanceof DiscoveredRspDuoTuner2 slave &&
                slave.getDeviceInfo().getDeviceSelectionMode().isSlaveMode() ? sibling : null;
        }
        if(tuner instanceof DiscoveredRspDuoTuner2 slave &&
            slave.getDeviceInfo().getDeviceSelectionMode().isSlaveMode())
        {
            DiscoveredTuner sibling = mFind.apply(slave.getMasterId());
            return sibling instanceof DiscoveredRspDuoTuner1 master &&
                master.getDeviceInfo().getDeviceSelectionMode().isMasterMode() ? sibling : null;
        }
        return null;
    }

    private List<DiscoveredTuner> lifecycleGroup(DiscoveredTuner tuner)
    {
        DiscoveredTuner sibling = rspDuoSibling(tuner);
        if(sibling == null)
        {
            return List.of(tuner);
        }
        return tuner instanceof DiscoveredRspDuoTuner1 ? List.of(tuner, sibling) : List.of(sibling, tuner);
    }

    private static boolean isFullyDisabled(DiscoveredTuner tuner)
    {
        return tuner.getOperatorState() == DiscoveredTuner.OperatorState.DISABLED && !tuner.isEnabled();
    }

    private ApplyResult applyWithGroupReserved(DiscoveredTuner tuner, String settingId, Object value)
    {
        TunerConfiguration configuration = configuration(tuner);

        if(!TunerSettingCatalog.isEditableInCurrentMode(configuration, settingId))
        {
            mErrors.put(tuner, "Setting unavailable");
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

                // A one-shot request never waits behind an in-flight hardware change.
                if(!controller.getLock().tryLock())
                {
                    return ApplyResult.RETRY;
                }

                try
                {
                    if(TunerSettingCatalog.requiresSetup(configuration, settingId) && !isIdle(tuner))
                    {
                        return ApplyResult.RETRY;
                    }

                    if("lna".equals(settingId) && configuration instanceof RspTunerConfiguration &&
                        controller instanceof RspTunerController<?> rsp)
                    {
                        // The valid RSP LNA range depends on the current tuned frequency (and for RSPduo, AM port).
                        // It may have narrowed since validation. Check again while holding the controller lock.
                        int requested = ((Number)value).intValue();
                        int maximum = rsp.getControlRsp().getMaximumLNASetting();

                        if(requested < 0 || requested > maximum)
                        {
                            mErrors.put(tuner, "LNA range is 0-" + maximum);
                            return ApplyResult.CONSUMED;
                        }
                    }

                    if("frequency_mhz".equals(settingId))
                    {
                        validateCenterFrequency(tuner, controller, ((Number)value).longValue());
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
            if(written && !applied && hardwareAccepted(tuner, configuration, settingId))
            {
                // The hardware write completed and only its observer notification failed. Keep the actual live value
                // and persist it so a restart cannot silently restore the stale value.
                mErrors.put(tuner, "Applied; notification failed");
                mLog.warn("Tuner setting [{}] applied for [{}], but notification failed", settingId, tuner.getId(), e);
                return ApplyResult.APPLIED;
            }
            // Do not claim the old value after a successful hardware write.  Persistence/sibling synchronization
            // can still fail, in which case the live value must remain visible and the admin must see the error.
            if(written && !applied)
            {
                TunerSettingCatalog.write(configuration, settingId, previous);
            }
            String reason = e instanceof IllegalArgumentException && e.getMessage() != null ? e.getMessage() : null;
            mErrors.put(tuner, applied ? "Applied; sync failed" :
                Objects.requireNonNullElse(reason, "Change failed"));
            mLog.warn("Unable to apply tuner setting [{}] for [{}]", settingId, tuner.getId(), e);
            return applied ? ApplyResult.APPLIED : ApplyResult.CONSUMED;
        }
    }

    private static boolean hardwareAccepted(DiscoveredTuner tuner, TunerConfiguration configuration,
                                             String settingId)
    {
        if(!tuner.hasTuner())
        {
            return false;
        }
        TunerController controller = tuner.getTuner().getTunerController();
        return switch(settingId)
        {
            case "frequency_correction_ppm" ->
                Double.compare(controller.getFrequencyCorrection(), configuration.getFrequencyCorrection()) == 0;
            case "frequency_mhz" -> controller.getFrequency() == configuration.getFrequency();
            default -> false;
        };
    }

    private enum ApplyResult
    {
        RETRY,
        CONSUMED,
        APPLIED
    }

    private static void validateCenterFrequency(DiscoveredTuner tuner, TunerController controller, long center)
    {
        if(tuner.getTuner() == null || tuner.getTuner().getChannelSourceManager() == null)
        {
            return;
        }
        ChannelSourceManager manager = tuner.getTuner().getChannelSourceManager();
        int count = manager.getTunerChannelCount();
        if(count <= 0)
        {
            return;
        }
        var channels = manager.getTunerChannels();
        if(channels.isEmpty())
        {
            throw new IllegalArgumentException("Channels unavailable");
        }
        long minimum = center - controller.getUsableHalfBandwidth();
        long maximum = center + controller.getUsableHalfBandwidth();
        long avoidMinimum = center - controller.getMiddleUnusableHalfBandwidth();
        long avoidMaximum = center + controller.getMiddleUnusableHalfBandwidth();
        for(TunerChannel channel: channels)
        {
            if(channel.getMinFrequency() < minimum || channel.getMaxFrequency() > maximum ||
                (controller.hasMiddleUnusableBandwidth() && channel.overlaps(avoidMinimum, avoidMaximum)))
            {
                throw new IllegalArgumentException("Channels won't fit");
            }
        }
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
        if("automatic_ppm".equals(settingId))
        {
            controller.getTunerFrequencyErrorManager().setEnabled(configuration.getAutoPPMCorrectionEnabled());
            return;
        }

        if(!TunerSettingCatalog.requiresSetup(configuration, settingId))
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
                    case "bias_t" -> hydra.setBiasT(config.isBiasT());
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
                if("bias_t".equals(settingId))
                {
                    rtl.setBiasT(((RTL2832TunerConfiguration)configuration).isBiasT());
                    return;
                }
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
                throw new IllegalArgumentException("Unsupported live RTL setting");
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
                mTransitions.clear();
                mLifecycleReservations.clear();
                mStoppedChannels.clear();
                mRestoreResults.clear();
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

    public record ChannelInfo(String id, String name)
    {
    }

    public record RestoreResult(List<ChannelInfo> failed)
    {
    }

    private record RememberedChannel(ChannelInfo info, io.github.dsheirer.controller.channel.Channel channel,
                                     boolean restorable)
    {
    }

    public static final class SettingUnavailableException extends IllegalStateException
    {
        public SettingUnavailableException(String message)
        {
            super(message);
        }
    }
}
