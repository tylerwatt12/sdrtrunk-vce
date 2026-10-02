package io.github.dsheirer.source.tuner.manager;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.controller.channel.ChannelProcessingManager.TunerChannelAssignment;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
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
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.LongSupplier;
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
    private final Supplier<List<DiscoveredTuner>> mInventory;
    private final LongSupplier mClock;
    private final ChannelProcessingManager mChannelProcessingManager;
    private final ThreadPoolExecutor mExecutor;
    private final Map<DiscoveredTuner,String> mTransitions = new ConcurrentHashMap<>();
    private final Set<DiscoveredTuner> mLifecycleReservations = ConcurrentHashMap.newKeySet();
    private final Map<DiscoveredTuner,List<RememberedChannel>> mStoppedChannels = new ConcurrentHashMap<>();
    private final Map<DiscoveredTuner,RestoreResult> mRestoreResults = new ConcurrentHashMap<>();
    private final Map<DiscoveredTuner,String> mErrors = new ConcurrentHashMap<>();
    private final Map<DiscoveredTuner,BrowseSession> mBrowseOwners = new HashMap<>();
    private final Set<CompletableFuture<?>> mBrowseRequests = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService mBrowseExpiry;
    private static final long BROWSE_LIFETIME_MS = 30_000;
    private static final int MAXIMUM_REMEMBERED_CHANNELS = 64;
    /** Serializes one-shot setting writes with close; decoder and channel allocation paths never take this lock. */
    private final Object mLifecycleLock = new Object();
    private volatile boolean mClosing;
    private volatile boolean mClosed;

    public TunerSettingsService(TunerManager manager)
    {
        this(manager, null);
    }

    public TunerSettingsService(TunerManager manager, ChannelProcessingManager channelProcessingManager)
    {
        this(manager.getTunerConfigurationManager()::saveConfigurations,
            manager.getDiscoveredTunerRegistry()::contains, manager.getDiscoveredTunerRegistry()::find,
            channelProcessingManager, manager.getDiscoveredTunerRegistry()::snapshot);
    }

    TunerSettingsService(Runnable persist, Predicate<DiscoveredTuner> contains,
                         Function<String,DiscoveredTuner> find)
    {
        this(persist, contains, find, null);
    }

    TunerSettingsService(Runnable persist, Predicate<DiscoveredTuner> contains,
                         Function<String,DiscoveredTuner> find, ChannelProcessingManager channelProcessingManager)
    {
        this(persist, contains, find, channelProcessingManager, List::of);
    }

    TunerSettingsService(Runnable persist, Predicate<DiscoveredTuner> contains,
                         Function<String,DiscoveredTuner> find, ChannelProcessingManager channelProcessingManager,
                         Supplier<List<DiscoveredTuner>> inventory)
    {
        this(persist, contains, find, channelProcessingManager, inventory, System::currentTimeMillis);
    }

    TunerSettingsService(Runnable persist, Predicate<DiscoveredTuner> contains,
                         Function<String,DiscoveredTuner> find, ChannelProcessingManager channelProcessingManager,
                         Supplier<List<DiscoveredTuner>> inventory, LongSupplier clock)
    {
        mPersist = Objects.requireNonNull(persist);
        mContains = Objects.requireNonNull(contains);
        mFind = Objects.requireNonNull(find);
        mInventory = Objects.requireNonNull(inventory);
        mClock = Objects.requireNonNull(clock);
        mChannelProcessingManager = channelProcessingManager;
        mExecutor = new ThreadPoolExecutor(LIFECYCLE_WORKER_COUNT, LIFECYCLE_WORKER_COUNT, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(LIFECYCLE_QUEUE_CAPACITY), task ->
            {
                Thread thread = new Thread(task,
                    "tuner-lifecycle-" + LIFECYCLE_THREAD_SEQUENCE.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        mBrowseExpiry = Executors.newSingleThreadScheduledExecutor(task ->
        {
            Thread thread = new Thread(task, "tuner-browse-expiry");
            thread.setDaemon(true);
            return thread;
        });
        mBrowseExpiry.scheduleWithFixedDelay(this::expireBrowsing, 5, 5, TimeUnit.SECONDS);
    }

    public List<TunerSettingCatalog.SettingDescriptor> describe(DiscoveredTuner tuner)
    {
        List<TunerSettingCatalog.SettingDescriptor> descriptors = TunerSettingCatalog.describe(tuner);

        if(!isIdle(tuner))
        {
            descriptors = descriptors.stream().map(descriptor -> "frequency_mhz".equals(descriptor.id()) ?
                unavailable(descriptor, "Channels are using this tuner") : descriptor).toList();
        }

        if(tuner instanceof DiscoveredRspDuoTuner1 && rspDuoSibling(tuner) instanceof DiscoveredTuner slave &&
            !isFullyDisabled(slave))
        {
            descriptors = descriptors.stream().map(descriptor -> "sample_rate".equals(descriptor.id()) ?
                unavailable(descriptor, "Disable tuner 2") : descriptor).toList();
        }

        if(!TunerSettingCatalog.isRspDuoSlave(tuner))
        {
            return projectBrowseSettings(tuner, descriptors);
        }

        DiscoveredTuner master = rspDuoSibling(tuner);
        if(master == null || master.getTunerConfiguration() == null)
        {
            return projectBrowseSettings(tuner, descriptors);
        }

        Map<String,TunerSettingCatalog.SettingDescriptor> shared = new HashMap<>();
        for(TunerSettingCatalog.SettingDescriptor descriptor: TunerSettingCatalog.describe(master))
        {
            if("device".equals(descriptor.scope()))
            {
                shared.put(descriptor.id(), descriptor);
            }
        }

        descriptors = descriptors.stream().map(descriptor ->
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
        return projectBrowseSettings(tuner, descriptors);
    }

    /** Project the temporary runtime unlock without mutating the persisted tuner configuration. */
    private List<TunerSettingCatalog.SettingDescriptor> projectBrowseSettings(DiscoveredTuner tuner,
        List<TunerSettingCatalog.SettingDescriptor> descriptors)
    {
        boolean takeover;
        synchronized(mLifecycleLock)
        {
            BrowseSession owner = mBrowseOwners.get(tuner);
            takeover = owner != null && owner.takeover;
        }
        if(!takeover) return descriptors;

        return descriptors.stream().map(descriptor ->
        {
            if("center_frequency_locked".equals(descriptor.id()))
            {
                return new TunerSettingCatalog.SettingDescriptor(descriptor.id(), descriptor.label(),
                    descriptor.group(), descriptor.kind(), false, descriptor.options(), descriptor.minimum(),
                    descriptor.maximum(), descriptor.step(), descriptor.unit(), descriptor.scope(), false,
                    descriptor.availability(), "Resume channels before changing center lock",
                    descriptor.dependencies());
            }
            if("frequency_mhz".equals(descriptor.id()) && "Unlock center".equals(descriptor.unavailableReason()))
            {
                return new TunerSettingCatalog.SettingDescriptor(descriptor.id(), descriptor.label(),
                    descriptor.group(), descriptor.kind(), descriptor.value(), descriptor.options(),
                    descriptor.minimum(), descriptor.maximum(), descriptor.step(), descriptor.unit(),
                    descriptor.scope(), true, descriptor.availability(), null, descriptor.dependencies());
            }
            return descriptor;
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
        return set(tuner, settingId, submittedValue, null);
    }

    public MutationResult set(DiscoveredTuner tuner, String settingId, Object submittedValue, String leaseId)
    {
        synchronized(mLifecycleLock)
        {
            ensureOpen();
            ensurePresent(tuner);
            if(lifecycleGroup(tuner).stream().anyMatch(DiscoveredTuner::isDiscoveryHeld))
            {
                throw new SettingUnavailableException("Signal identification is using this tuner");
            }
            BrowseSession owner = mBrowseOwners.get(tuner);
            if(owner != null && !Objects.equals(owner.id, leaseId))
            {
                throw new SettingUnavailableException("Spectrum is using this tuner");
            }
            if(owner != null && owner.takeover && "center_frequency_locked".equals(settingId))
            {
                throw new SettingUnavailableException("Finish Spectrum search before changing the center lock");
            }
            if(leaseId != null && !verifyBrowse(tuner, leaseId))
            {
                throw new SettingUnavailableException("Spectrum tuner session expired; reopen Spectrum");
            }
            if("frequency_mhz".equals(settingId) && !isIdle(tuner))
            {
                throw new SettingUnavailableException("Channels are using this tuner");
            }
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
            Object value = TunerSettingCatalog.validate(tuner, settingId, submittedValue,
                owner != null && owner.takeover && "frequency_mhz".equals(settingId));
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
            if(lifecycleGroup(tuner).stream().anyMatch(DiscoveredTuner::isDiscoveryHeld))
            {
                throw new SettingUnavailableException("Signal identification is using this tuner");
            }
            if(takeoverOwner(tuner) != null)
            {
                throw new SettingUnavailableException("Finish Spectrum search before changing this tuner");
            }
            abandonBrowse(tuner);
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
            if(lifecycleGroup(tuner).stream().anyMatch(DiscoveredTuner::isDiscoveryHeld))
            {
                throw new SettingUnavailableException("Signal identification is using this tuner");
            }
            if(takeoverOwner(tuner) != null)
            {
                throw new SettingUnavailableException("Finish Spectrum search before resuming channels");
            }
            abandonBrowse(tuner);
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

    /** Borrows hardware for Spectrum without saving an operator mode. */
    public CompletableFuture<BrowseLease> browse(DiscoveredTuner tuner, String leaseId)
    {
        return browse(tuner, leaseId, false);
    }

    /**
     * Borrows hardware for Spectrum.  An explicit takeover temporarily stops the channels using the allocation group,
     * unlocks its centers, and records the exact successful stops for receiver-owned restoration.
     */
    public CompletableFuture<BrowseLease> browse(DiscoveredTuner tuner, String leaseId, boolean takeover)
    {
        synchronized(mLifecycleLock)
        {
            ensureOpen();
            ensurePresent(tuner);
            if(leaseId != null)
            {
                if(takeover)
                    throw new IllegalArgumentException("takeover is only valid when browsing begins");
                if(!verifyBrowse(tuner, leaseId))
                    throw new SettingUnavailableException("Spectrum tuner session expired; reopen Spectrum");
                BrowseSession owner = mBrowseOwners.get(tuner);
                owner.expiresAt = mClock.getAsLong() + BROWSE_LIFETIME_MS;
                return CompletableFuture.completedFuture(owner.lease());
            }
            if(tuner.getTunerClass() == TunerClass.RECORDING_TUNER ||
                tuner.getTunerStatus() == TunerStatus.ERROR || tuner.getTunerStatus() == TunerStatus.REMOVED)
                throw new SettingUnavailableException("Selected tuner is unavailable for live Spectrum");
            List<DiscoveredTuner> group = lifecycleGroup(tuner);
            if(group.stream().anyMatch(mBrowseOwners::containsKey))
                throw new SettingUnavailableException("Another Spectrum session is using this tuner");
            if(group.stream().anyMatch(DiscoveredTuner::isDiscoveryHeld))
                throw new SettingUnavailableException("Signal identification is using this tuner");
            reserveTransition(tuner, group, "starting_browse");
            return submitBrowse(tuner, group, () ->
            {
                List<DiscoveredTuner> locked = lockAllocationGroup(group);
                Map<DiscoveredTuner,DiscoveredTuner.OperatorState> previous = new HashMap<>();
                Map<DiscoveredTuner,List<RememberedChannel>> stopped = new HashMap<>();
                Map<DiscoveredTuner,CenterLockState> centerLocks = new HashMap<>();
                try
                {
                    boolean idle = group.stream().allMatch(TunerSettingsService::isIdle);
                    if(!idle && !takeover && (!tuner.isAvailable() || !tuner.hasTuner() || isIdle(tuner)))
                        throw new SettingUnavailableException("Channels are using this tuner's paired hardware");
                    if(idle || takeover)
                    {
                        for(DiscoveredTuner member: group)
                            previous.put(member, member.getOperatorState());
                        if(takeover && !idle)
                        {
                            stopLiveChannels(group, previous, stopped);
                            if(!group.stream().allMatch(TunerSettingsService::isIdle))
                                throw new SettingUnavailableException("Some channels could not stop; the receiver was restored");
                        }
                        for(DiscoveredTuner member: group)
                        {
                            member.prepareForSetup();
                        }
                        for(DiscoveredTuner member: group)
                        {
                            if(!member.enterSetup()) throw new IllegalStateException("Tuner unavailable");
                        }
                    }
                    if(!tuner.isAvailable() || !tuner.hasTuner())
                        throw new IllegalStateException("Tuner unavailable");
                    if(takeover)
                    {
                        for(DiscoveredTuner member: group)
                        {
                            if(member.hasTuner() && member.getTunerConfiguration() != null)
                            {
                                TunerController controller = member.getTuner().getTunerController();
                                centerLocks.put(member, new CenterLockState(
                                    member.getTunerConfiguration().isCenterFrequencyLocked(),
                                    controller.isCenterFrequencyLocked()));
                                controller.setCenterFrequencyLocked(false);
                            }
                        }
                    }
                    BrowseSession session = new BrowseSession(tuner, group, previous, idle || takeover,
                        takeover, stopped, centerLocks);
                    synchronized(mLifecycleLock)
                    {
                        ensureOpen();
                        group.forEach(member -> mBrowseOwners.put(member, session));
                    }
                    return session.lease();
                }
                catch(RuntimeException exception)
                {
                    restoreFailedTakeover(previous, stopped, centerLocks, exception);
                    throw exception;
                }
                finally { unlockAllocationGroup(locked); }
            });
        }
    }

    public boolean verifyBrowse(DiscoveredTuner tuner, String leaseId)
    {
        synchronized(mLifecycleLock)
        {
            BrowseSession owner = mBrowseOwners.get(tuner);
            return !mClosing && !mClosed && owner != null && owner.target == tuner && Objects.equals(owner.id, leaseId) &&
                owner.expiresAt > mClock.getAsLong() && owner.ownsModes();
        }
    }

    /** Explicit handoff leaves owned setup hardware Live; ordinary close restores only this session's changes. */
    public CompletableFuture<Void> releaseBrowse(DiscoveredTuner tuner, String leaseId, boolean handoff)
    {
        return endBrowse(tuner, leaseId, handoff, null, false);
    }

    /** Starts the saved channel on the lifecycle worker before competing allocation can change this center. */
    public <T> CompletableFuture<T> handoffBrowse(DiscoveredTuner tuner, String leaseId, Supplier<T> start)
    {
        return endBrowse(tuner, leaseId, true, Objects.requireNonNull(start), false);
    }

    private <T> CompletableFuture<T> endBrowse(DiscoveredTuner tuner, String leaseId, boolean handoff,
                                             Supplier<T> start, boolean closingRestore)
    {
        synchronized(mLifecycleLock)
        {
            if(closingRestore)
            {
                if(mClosed) return CompletableFuture.completedFuture(null);
            }
            else ensureOpen();
            BrowseSession owner = mBrowseOwners.get(tuner);
            if(owner == null)
            {
                if(start != null) throw new SettingUnavailableException("Spectrum tuner session expired");
                return CompletableFuture.completedFuture(null);
            }
            if(owner.target != tuner || !Objects.equals(owner.id, leaseId))
                throw new SettingUnavailableException("Spectrum tuner session belongs to another browser");
            if(owner.group.stream().anyMatch(DiscoveredTuner::isDiscoveryHeld))
                throw new SettingUnavailableException("Stop signal identification before releasing the tuner");
            reserveTransition(tuner, owner.group, "ending_browse");
            return submitBrowse(tuner, owner.group, () ->
            {
                List<DiscoveredTuner> locked = lockAllocationGroup(owner.group);
                try
                {
                    boolean ownsModes = owner.ownsModes();
                    RuntimeException failure = null;
                    T value = null;
                    try
                    {
                        if(start != null && !ownsModes)
                            throw new SettingUnavailableException("The tuner changed; retry signal identification");
                        if(closingRestore && owner.takeover && !ownsModes)
                            throw new SettingUnavailableException("The tuner changed before its channels were restored");
                        if(ownsModes && owner.canTune)
                        {
                            List<DiscoveredTuner> restoring = owner.takeover ? owner.pendingModeMembers() : owner.group;
                            if(!restoring.stream().allMatch(TunerSettingsService::isIdle))
                                throw new SettingUnavailableException("A sample source is still using this tuner");
                            if(!owner.takeover)
                                restoreBrowseModes(handoff ? owner.currentModes() : owner.previous, handoff);
                        }
                    }
                    catch(RuntimeException exception)
                    {
                        failure = exception;
                    }
                    if(owner.takeover)
                    {
                        try
                        {
                            if(ownsModes && failure == null) restoreTakeover(owner, handoff);
                            else retainStoppedChannels(owner);
                        }
                        catch(RuntimeException restoreFailure)
                        {
                            if(failure == null) failure = restoreFailure;
                            else failure.addSuppressed(restoreFailure);
                        }
                    }
                    if(failure == null)
                    {
                        synchronized(mLifecycleLock) { abandonBrowse(tuner); }
                    }
                    if(failure == null && start != null)
                    {
                        try
                        {
                            owner.group.forEach(member -> member.setDiscoveryHeld(true));
                            value = start.get();
                        }
                        catch(RuntimeException exception)
                        {
                            failure = exception;
                        }
                        finally
                        {
                            owner.group.forEach(member -> member.setDiscoveryHeld(false));
                        }
                    }
                    if(failure != null) throw failure;
                    return value;
                }
                finally { unlockAllocationGroup(locked); }
            }, closingRestore);
        }
    }

    /** Stop only channels that were live when takeover began; traffic channels are recorded but never restarted. */
    private void stopLiveChannels(List<DiscoveredTuner> group,
                                  Map<DiscoveredTuner,DiscoveredTuner.OperatorState> previous,
                                  Map<DiscoveredTuner,List<RememberedChannel>> stopped)
    {
        List<DiscoveredTuner> live = group.stream().filter(member ->
            previous.get(member) == DiscoveredTuner.OperatorState.LIVE).toList();
        for(DiscoveredTuner member: live)
        {
            if(!member.holdForSetup()) throw new IllegalStateException("Tuner unavailable");
        }
        Map<DiscoveredTuner,List<TunerChannelAssignment>> plans = planChannelStops(live);
        for(DiscoveredTuner member: live)
        {
            List<RememberedChannel> remembered = new ArrayList<>();
            stopped.put(member, remembered);
            stopChannels(remembered, plans.get(member));
        }
    }

    /** A failed acquisition must leave the exact pre-takeover channels and center locks restored. */
    private void restoreFailedTakeover(Map<DiscoveredTuner,DiscoveredTuner.OperatorState> previous,
                                       Map<DiscoveredTuner,List<RememberedChannel>> stopped,
                                       Map<DiscoveredTuner,CenterLockState> centerLocks,
                                       RuntimeException original)
    {
        Set<DiscoveredTuner> modeFailures = new java.util.HashSet<>();
        for(var entry: previous.entrySet())
        {
            try { restoreBrowseMode(entry.getKey(), entry.getValue(), false); }
            catch(RuntimeException restoreFailure)
            {
                modeFailures.add(entry.getKey());
                original.addSuppressed(restoreFailure);
            }
        }
        for(var entry: stopped.entrySet())
        {
            if(modeFailures.contains(entry.getKey()))
            {
                mergeStoppedChannels(entry.getKey(), entry.getValue());
                continue;
            }
            try
            {
                RestoreResult result = restartRemembered(entry.getValue());
                mRestoreResults.put(entry.getKey(), result);
                retainFailedChannels(entry.getKey(), entry.getValue(), result.failed());
            }
            catch(RuntimeException restartFailure)
            {
                mergeStoppedChannels(entry.getKey(), entry.getValue());
                original.addSuppressed(restartFailure);
            }
        }
        try { restoreCenterLocks(centerLocks); }
        catch(RuntimeException restoreFailure) { original.addSuppressed(restoreFailure); }
    }

    /** Restore each paired member independently, then restart only channels whose member mode was recovered. */
    private void restoreTakeover(BrowseSession owner, boolean handoff)
    {
        RuntimeException failure = null;
        Set<DiscoveredTuner> modeFailures = new java.util.HashSet<>();
        Map<DiscoveredTuner,DiscoveredTuner.OperatorState> modes = handoff ? owner.currentModes() : owner.previous;
        List<DiscoveredTuner> pendingModes = owner.pendingModeMembers();
        try
        {
            for(DiscoveredTuner member: pendingModes)
            {
                try
                {
                    restoreBrowseMode(member, modes.getOrDefault(member, member.getOperatorState()), handoff);
                    owner.markTakeoverModeRestored(member);
                }
                catch(RuntimeException modeFailure)
                {
                    modeFailures.add(member);
                    failure = appendFailure(failure, modeFailure);
                }
                finally { owner.adoptOperatorGeneration(member); }
            }
            for(DiscoveredTuner member: owner.pendingTakeoverMembers())
            {
                if(modeFailures.contains(member) || owner.takeoverModePending(member))
                {
                    mergeStoppedChannels(member, owner.pendingTakeoverChannels(member));
                    continue;
                }
                List<RememberedChannel> remembered = owner.pendingTakeoverChannels(member);
                try
                {
                    RestoreResult result = restartRemembered(remembered);
                    mRestoreResults.put(member, result);
                    retainFailedChannels(member, remembered, result.failed());
                    owner.retainFailedTakeoverChannels(member, remembered, result.failed());
                    if(!result.failed().isEmpty())
                    {
                        failure = appendFailure(failure,
                            new IllegalStateException("Some channels could not resume"));
                    }
                }
                catch(RuntimeException restartFailure)
                {
                    mergeStoppedChannels(member, remembered);
                    failure = appendFailure(failure, restartFailure);
                }
            }
        }
        finally
        {
            for(DiscoveredTuner member: owner.pendingCenterLockMembers())
            {
                try
                {
                    restoreCenterLock(member, owner.centerLocks.get(member));
                    owner.markCenterLockRestored(member);
                }
                catch(RuntimeException lockFailure)
                {
                    failure = appendFailure(failure, lockFailure);
                }
            }
        }
        if(failure != null) throw failure;
    }

    private static RuntimeException appendFailure(RuntimeException existing, RuntimeException added)
    {
        if(existing == null) return added;
        existing.addSuppressed(added);
        return existing;
    }

    /** Preserve channel recovery without overwriting settings owned by a later operator action. */
    private void retainStoppedChannels(BrowseSession owner)
    {
        owner.pendingTakeoverChannels().forEach(this::mergeStoppedChannels);
    }

    private void retainFailedChannels(DiscoveredTuner tuner, List<RememberedChannel> remembered,
                                      List<ChannelInfo> failed)
    {
        Set<String> failedIds = failed.stream().map(ChannelInfo::id).collect(java.util.stream.Collectors.toSet());
        List<RememberedChannel> retained = remembered.stream()
            .filter(channel -> failedIds.contains(channel.info().id())).toList();
        Set<String> ownedIds = remembered.stream().map(channel -> channel.info().id())
            .collect(java.util.stream.Collectors.toSet());
        mStoppedChannels.compute(tuner, (_, existing) ->
        {
            List<RememberedChannel> merged = new ArrayList<>();
            if(existing != null)
            {
                existing.stream().filter(channel -> !ownedIds.contains(channel.info().id())).forEach(merged::add);
            }
            mergeRemembered(merged, retained);
            return merged.isEmpty() ? null : List.copyOf(merged);
        });
    }

    private void mergeStoppedChannels(DiscoveredTuner tuner, List<RememberedChannel> remembered)
    {
        if(remembered == null || remembered.isEmpty()) return;
        mStoppedChannels.compute(tuner, (_, existing) ->
        {
            List<RememberedChannel> merged = new ArrayList<>();
            if(existing != null) merged.addAll(existing);
            mergeRemembered(merged, remembered);
            return List.copyOf(merged);
        });
    }

    private static void mergeRemembered(List<RememberedChannel> target, List<RememberedChannel> added)
    {
        Set<String> ids = target.stream().map(channel -> channel.info().id())
            .collect(java.util.stream.Collectors.toSet());
        for(RememberedChannel channel: added)
        {
            if(target.size() >= MAXIMUM_REMEMBERED_CHANNELS) break;
            if(ids.add(channel.info().id())) target.add(channel);
        }
    }

    private static void restoreCenterLocks(Map<DiscoveredTuner,CenterLockState> locks)
    {
        RuntimeException failure = null;
        for(var entry: locks.entrySet())
        {
            try
            {
                restoreCenterLock(entry.getKey(), entry.getValue());
            }
            catch(RuntimeException restoreFailure)
            {
                if(failure == null) failure = restoreFailure;
                else failure.addSuppressed(restoreFailure);
            }
        }
        if(failure != null) throw failure;
    }

    private static void restoreCenterLock(DiscoveredTuner member, CenterLockState state)
    {
        RuntimeException failure = null;
        try
        {
            if(member.getTunerConfiguration() != null)
                member.getTunerConfiguration().setCenterFrequencyLocked(state.configurationLocked());
        }
        catch(RuntimeException restoreFailure)
        {
            failure = restoreFailure;
        }
        try
        {
            if(member.hasTuner())
                member.getTuner().getTunerController().setCenterFrequencyLocked(state.runtimeLocked());
        }
        catch(RuntimeException restoreFailure)
        {
            if(failure == null) failure = restoreFailure;
            else failure.addSuppressed(restoreFailure);
        }
        if(failure != null) throw failure;
    }

    /** Freezes center allocation, while existing decoders and within-window source allocations continue. */
    public ProbeHold holdForProbe(Tuner runtimeTuner)
    {
        DiscoveredTuner tuner = mInventory.get().stream().filter(candidate -> candidate.getTuner() == runtimeTuner)
            .findFirst().orElseThrow(() -> new SettingUnavailableException("Selected tuner is unavailable"));
        return holdForProbe(tuner);
    }

    public ProbeHold holdForProbe(DiscoveredTuner tuner)
    {
        synchronized(mLifecycleLock)
        {
            ensureOpen();
            ensurePresent(tuner);
            List<DiscoveredTuner> group = lifecycleGroup(tuner);
            if(group.stream().anyMatch(member -> mLifecycleReservations.contains(member) || member.isDiscoveryHeld()))
                throw new SettingUnavailableException("Tuner busy");
            List<DiscoveredTuner> locked = lockAllocationGroup(group);
            try
            {
                if(!tuner.isAvailable() || !tuner.hasTuner())
                    throw new SettingUnavailableException("Selected tuner is unavailable");
                ProbeHold hold = new ProbeHold(tuner, group);
                group.forEach(member -> member.setDiscoveryHeld(true));
                return hold;
            }
            finally { unlockAllocationGroup(locked); }
        }
    }

    /** Reserves idle Spectrum hardware for temporary search tuning. No saved tuner setting is changed. */
    public ProbeHold holdForSearch(DiscoveredTuner tuner, String leaseId)
    {
        synchronized(mLifecycleLock)
        {
            if(!verifyBrowse(tuner, leaseId) || !mBrowseOwners.get(tuner).canTune ||
                !lifecycleGroup(tuner).stream().allMatch(TunerSettingsService::isIdle))
                throw new SettingUnavailableException("Choose an idle receiver for the search");
            if(tuner.getTuner().getTunerController().isCenterFrequencyLocked())
                throw new SettingUnavailableException("Unlock the receiver's center frequency before searching");
            ProbeHold hold = holdForProbe(tuner);
            hold.searchLeaseId = leaseId;
            return hold;
        }
    }

    private <T> CompletableFuture<T> submitBrowse(DiscoveredTuner tuner, List<DiscoveredTuner> group,
                                                 Supplier<T> operation)
    {
        return submitBrowse(tuner, group, operation, false);
    }

    private <T> CompletableFuture<T> submitBrowse(DiscoveredTuner tuner, List<DiscoveredTuner> group,
                                                  Supplier<T> operation, boolean allowClosing)
    {
        CompletableFuture<T> result = new CompletableFuture<>();
        mBrowseRequests.add(result);
        try
        {
            mExecutor.execute(() ->
            {
                T value = null;
                RuntimeException failure = null;
                try
                {
                    if(mClosed || (mClosing && !allowClosing) || !mContains.test(tuner))
                        throw new IllegalStateException("Tuner unavailable");
                    value = operation.get();
                }
                catch(RuntimeException exception) { failure = exception; }
                finally
                {
                    finishTransition(tuner, group);
                    mBrowseRequests.remove(result);
                }
                if(failure == null) result.complete(value);
                else result.completeExceptionally(failure);
            });
        }
        catch(RejectedExecutionException exception)
        {
            finishTransition(tuner, group);
            mBrowseRequests.remove(result);
            result.completeExceptionally(new IllegalStateException("Tuner maintenance is unavailable"));
        }
        return result;
    }

    private static List<DiscoveredTuner> lockAllocationGroup(List<DiscoveredTuner> group)
    {
        List<DiscoveredTuner> locked = new ArrayList<>();
        for(DiscoveredTuner member: group)
        {
            if(!member.tryAcquireForAllocation())
            {
                unlockAllocationGroup(locked);
                throw new SettingUnavailableException("Tuner busy");
            }
            locked.add(member);
        }
        return locked;
    }

    private static void unlockAllocationGroup(List<DiscoveredTuner> locked)
    {
        for(int index = locked.size() - 1; index >= 0; index--) locked.get(index).releaseAfterAllocation();
    }

    private static void restoreBrowseModes(Map<DiscoveredTuner,DiscoveredTuner.OperatorState> modes,
                                           boolean handoff)
    {
        for(var entry: modes.entrySet())
        {
            restoreBrowseMode(entry.getKey(), entry.getValue(), handoff);
        }
    }

    private static void restoreBrowseMode(DiscoveredTuner tuner, DiscoveredTuner.OperatorState mode,
                                          boolean handoff)
    {
        if(handoff || mode == DiscoveredTuner.OperatorState.LIVE)
        {
            if(!tuner.enterLive()) throw new IllegalStateException("Tuner unavailable");
        }
        else if(mode == DiscoveredTuner.OperatorState.DISABLED) tuner.setEnabled(false);
    }

    private void abandonBrowse(DiscoveredTuner tuner)
    {
        BrowseSession owner = mBrowseOwners.get(tuner);
        if(owner != null) owner.group.forEach(member -> mBrowseOwners.remove(member, owner));
    }

    private BrowseSession takeoverOwner(DiscoveredTuner tuner)
    {
        BrowseSession owner = mBrowseOwners.get(tuner);
        return owner != null && owner.takeover ? owner : null;
    }

    void expireBrowsing()
    {
        synchronized(mLifecycleLock)
        {
            if(mClosing || mClosed) return;
            for(BrowseSession owner: List.copyOf(mBrowseOwners.values()).stream().distinct().toList())
            {
                if(owner.expiresAt > mClock.getAsLong()) continue;
                try { releaseBrowse(owner.target, owner.id, false); }
                catch(RuntimeException ignored) { /* An active probe or finite hardware transition will retry. */ }
            }
        }
    }

    public final class ProbeHold implements AutoCloseable
    {
        private final DiscoveredTuner tuner;
        private final List<DiscoveredTuner> group;
        private final Tuner runtime;
        private long center;
        private final long originalCenter;
        private final double rate;
        private final Map<DiscoveredTuner,Long> generations = new ConcurrentHashMap<>();
        private boolean closed;
        private String searchLeaseId;

        private ProbeHold(DiscoveredTuner tuner, List<DiscoveredTuner> group)
        {
            this.tuner = tuner;
            this.group = group;
            runtime = tuner.getTuner();
            center = runtime.getTunerController().getFrequency();
            originalCenter = center;
            rate = runtime.getTunerController().getSampleRate();
            group.forEach(member -> generations.put(member, member.operatorGeneration()));
        }

        public boolean valid()
        {
            synchronized(mLifecycleLock)
            {
                return !closed && !mClosing && !mClosed && mContains.test(tuner) && tuner.getTuner() == runtime &&
                    tuner.isAvailable() && runtime.getTunerController().getFrequency() == center &&
                    runtime.getTunerController().getSampleRate() == rate && group.stream().allMatch(member ->
                        mContains.test(member) && member.operatorGeneration() == generations.get(member));
            }
        }

        /** Search worker only. Allocation and hardware locks are attempted once; occupied hardware is never tuned. */
        public void tune(long frequencyHz)
        {
            tune(frequencyHz, false);
        }

        /** Expired searches may restore only their still-owned idle hardware, never a later owner's settings. */
        public void restoreSearchCenter()
        {
            tune(originalCenter, true);
        }

        private void tune(long frequencyHz, boolean restoring)
        {
            synchronized(mLifecycleLock)
            {
                BrowseSession owner = mBrowseOwners.get(tuner);
                if(searchLeaseId == null || !valid() || owner == null || !owner.id.equals(searchLeaseId) ||
                    !owner.ownsModes() || !restoring && !verifyBrowse(tuner, searchLeaseId))
                    throw new SettingUnavailableException("The search receiver changed; begin again");
                List<DiscoveredTuner> locked = lockAllocationGroup(group);
                TunerController controller = runtime.getTunerController();
                try
                {
                    if(!group.stream().allMatch(TunerSettingsService::isIdle))
                        throw new SettingUnavailableException("Channels are using this receiver");
                    if(!controller.getLock().tryLock())
                        throw new SettingUnavailableException("Receiver settings are changing; retry the search");
                    try
                    {
                        if(controller.isCenterFrequencyLocked())
                            throw new SettingUnavailableException("The receiver's center frequency is locked");
                        if(frequencyHz < controller.getMinimumFrequency() || frequencyHz > controller.getMaximumFrequency())
                            throw new IllegalArgumentException("This frequency is outside the receiver's tuning range");
                        controller.setFrequency(frequencyHz);
                        center = controller.getFrequency();
                    }
                    catch(io.github.dsheirer.source.SourceException exception)
                    {
                        throw new SettingUnavailableException("The receiver could not tune to this frequency");
                    }
                    finally { controller.getLock().unlock(); }
                }
                finally { unlockAllocationGroup(locked); }
            }
        }

        @Override public void close()
        {
            synchronized(mLifecycleLock)
            {
                if(!closed) group.forEach(member -> member.setDiscoveryHeld(false));
                closed = true;
            }
        }
    }

    public record BrowseLease(String leaseId, long expiresAtEpochMs, boolean canTune, boolean takeover,
                              List<ChannelInfo> stoppedChannels) { }

    private final class BrowseSession
    {
        private final String id = UUID.randomUUID().toString();
        private final DiscoveredTuner target;
        private final List<DiscoveredTuner> group;
        private final Map<DiscoveredTuner,DiscoveredTuner.OperatorState> previous;
        private final Map<DiscoveredTuner,Long> generations = new HashMap<>();
        private final boolean canTune;
        private final boolean takeover;
        private final Map<DiscoveredTuner,List<RememberedChannel>> stopped;
        private final Map<DiscoveredTuner,CenterLockState> centerLocks;
        private final Set<DiscoveredTuner> pendingTakeoverModes = ConcurrentHashMap.newKeySet();
        private final Map<DiscoveredTuner,List<RememberedChannel>> pendingTakeoverChannels =
            new ConcurrentHashMap<>();
        private final Set<DiscoveredTuner> pendingCenterLocks = ConcurrentHashMap.newKeySet();
        private long expiresAt = mClock.getAsLong() + BROWSE_LIFETIME_MS;

        private BrowseSession(DiscoveredTuner target, List<DiscoveredTuner> group,
                              Map<DiscoveredTuner,DiscoveredTuner.OperatorState> previous, boolean canTune,
                              boolean takeover, Map<DiscoveredTuner,List<RememberedChannel>> stopped,
                              Map<DiscoveredTuner,CenterLockState> centerLocks)
        {
            this.target = target;
            this.group = group;
            this.previous = Map.copyOf(previous);
            this.canTune = canTune;
            this.takeover = takeover;
            Map<DiscoveredTuner,List<RememberedChannel>> stoppedCopy = new HashMap<>();
            stopped.forEach((member, channels) -> stoppedCopy.put(member, List.copyOf(channels)));
            this.stopped = Map.copyOf(stoppedCopy);
            this.pendingTakeoverChannels.putAll(stoppedCopy);
            this.centerLocks = Map.copyOf(centerLocks);
            this.pendingCenterLocks.addAll(centerLocks.keySet());
            group.forEach(member -> generations.put(member, member.operatorGeneration()));
            if(takeover) pendingTakeoverModes.addAll(group);
        }

        private boolean ownsModes()
        {
            List<DiscoveredTuner> owned = takeover ? pendingOwnershipMembers() : group;
            return owned.stream().allMatch(member -> mContains.test(member) &&
                member.operatorGeneration() == generations.get(member));
        }

        private List<DiscoveredTuner> pendingOwnershipMembers()
        {
            return group.stream().filter(member -> pendingTakeoverModes.contains(member) ||
                pendingTakeoverChannels.containsKey(member) || pendingCenterLocks.contains(member)).toList();
        }

        private List<DiscoveredTuner> pendingTakeoverMembers()
        {
            return group.stream().filter(member -> pendingTakeoverModes.contains(member) ||
                pendingTakeoverChannels.containsKey(member)).toList();
        }

        private List<DiscoveredTuner> pendingModeMembers()
        {
            return group.stream().filter(pendingTakeoverModes::contains).toList();
        }

        private boolean takeoverModePending(DiscoveredTuner member)
        {
            return pendingTakeoverModes.contains(member);
        }

        private void markTakeoverModeRestored(DiscoveredTuner member)
        {
            pendingTakeoverModes.remove(member);
        }

        private void adoptOperatorGeneration(DiscoveredTuner member)
        {
            generations.put(member, member.operatorGeneration());
        }

        private List<DiscoveredTuner> pendingCenterLockMembers()
        {
            return group.stream().filter(pendingCenterLocks::contains).toList();
        }

        private void markCenterLockRestored(DiscoveredTuner member)
        {
            pendingCenterLocks.remove(member);
        }

        private List<RememberedChannel> pendingTakeoverChannels(DiscoveredTuner member)
        {
            return pendingTakeoverChannels.getOrDefault(member, List.of());
        }

        private Map<DiscoveredTuner,List<RememberedChannel>> pendingTakeoverChannels()
        {
            return Map.copyOf(pendingTakeoverChannels);
        }

        private void retainFailedTakeoverChannels(DiscoveredTuner member, List<RememberedChannel> remembered,
                                                  List<ChannelInfo> failed)
        {
            Set<String> failedIds = failed.stream().map(ChannelInfo::id)
                .collect(java.util.stream.Collectors.toSet());
            List<RememberedChannel> retained = remembered.stream()
                .filter(channel -> failedIds.contains(channel.info().id())).toList();
            if(retained.isEmpty()) pendingTakeoverChannels.remove(member);
            else pendingTakeoverChannels.put(member, retained);
        }

        private Map<DiscoveredTuner,DiscoveredTuner.OperatorState> currentModes()
        {
            Map<DiscoveredTuner,DiscoveredTuner.OperatorState> modes = new HashMap<>();
            group.forEach(member -> modes.put(member, member.getOperatorState()));
            return modes;
        }

        private BrowseLease lease()
        {
            List<ChannelInfo> channels = stopped.values().stream().flatMap(List::stream)
                .map(RememberedChannel::info).toList();
            return new BrowseLease(id, expiresAt, canTune, takeover, channels);
        }
    }

    private record CenterLockState(boolean configurationLocked, boolean runtimeLocked) { }

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
                    finishTransition(tuner, group);
                }
            });
        }
        catch(RejectedExecutionException e)
        {
            finishTransition(tuner, group);
            throw new IllegalStateException("Tuner maintenance is unavailable", e);
        }
    }

    /** Publish completion only after the entire group is free, without clearing a newer request's transition. */
    private void finishTransition(DiscoveredTuner tuner, List<DiscoveredTuner> group)
    {
        synchronized(mLifecycleLock)
        {
            mLifecycleReservations.removeAll(group);
            mTransitions.remove(tuner);
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
        Map<DiscoveredTuner,List<RememberedChannel>> stopped = new HashMap<>();
        try
        {
            for(DiscoveredTuner member: live)
            {
                if(!member.holdForSetup()) throw new IllegalStateException("Tuner unavailable");
            }
            Map<DiscoveredTuner,List<TunerChannelAssignment>> plans = planChannelStops(live);
            for(DiscoveredTuner member: live)
            {
                List<RememberedChannel> remembered = new ArrayList<>();
                stopped.put(member, remembered);
                stopChannels(remembered, plans.get(member));
            }
            if(!live.stream().allMatch(TunerSettingsService::isIdle))
                throw new IllegalStateException("Some channels could not stop");
            stopped.forEach(this::mergeStoppedChannels);
        }
        catch(RuntimeException failure)
        {
            rollbackStateChange(live, stopped, failure);
            throw failure;
        }
    }

    /** Capture every stop plan after allocation is gated, and reject the group before the first stop if recovery is full. */
    private Map<DiscoveredTuner,List<TunerChannelAssignment>> planChannelStops(List<DiscoveredTuner> tuners)
    {
        Map<DiscoveredTuner,List<TunerChannelAssignment>> plans = new HashMap<>();
        for(DiscoveredTuner tuner: tuners)
        {
            List<TunerChannelAssignment> assignments = mChannelProcessingManager == null ? new ArrayList<>() :
                new ArrayList<>(mChannelProcessingManager.getChannelsUsingTuner(tuner.getId()));
            if(assignments.size() > remainingRecoveryCapacity(tuner))
            {
                throw new SettingUnavailableException(
                    "Too many channels are using this tuner to enter Setup safely");
            }
            assignments.sort((left, right) -> Boolean.compare(right.restorable(), left.restorable()));
            plans.put(tuner, List.copyOf(assignments));
        }
        return plans;
    }

    private void stopChannels(List<RememberedChannel> remembered, List<TunerChannelAssignment> assignments)
    {
        for(TunerChannelAssignment assignment: assignments)
        {
            try
            {
                ChannelProcessingManager.StopCurrentResult result =
                    mChannelProcessingManager.stopIfCurrent(assignment);
                if(result.claimed())
                {
                    remembered.add(new RememberedChannel(new ChannelInfo(assignment.id(), assignment.name(),
                        assignment.parentId(), assignment.kind(), assignment.frequencyHz(),
                        assignment.system(), assignment.site()),
                        assignment.channel(), assignment.restorable()));
                }
                if(result == ChannelProcessingManager.StopCurrentResult.CLAIMED_WITH_CLEANUP_FAILURE)
                {
                    throw new IllegalStateException("Channel stopped with incomplete cleanup");
                }
            }
            catch(ChannelException e)
            {
                mLog.warn("Unable to stop channel [{}] for tuner setup", assignment.id(), e);
            }
        }
    }

    private void rollbackStateChange(List<DiscoveredTuner> live,
                                     Map<DiscoveredTuner,List<RememberedChannel>> stopped,
                                     RuntimeException original)
    {
        Set<DiscoveredTuner> modeFailures = new java.util.HashSet<>();
        for(DiscoveredTuner member: live)
        {
            try
            {
                if(!member.enterLive()) throw new IllegalStateException("Tuner unavailable");
            }
            catch(RuntimeException failure)
            {
                modeFailures.add(member);
                original.addSuppressed(failure);
            }
        }
        for(var entry: stopped.entrySet())
        {
            if(modeFailures.contains(entry.getKey()))
            {
                mergeStoppedChannels(entry.getKey(), entry.getValue());
                continue;
            }
            try
            {
                RestoreResult result = restartRemembered(entry.getValue());
                mRestoreResults.put(entry.getKey(), result);
                retainFailedChannels(entry.getKey(), entry.getValue(), result.failed());
                if(!result.failed().isEmpty())
                    original.addSuppressed(new IllegalStateException("Some channels could not resume"));
            }
            catch(RuntimeException failure)
            {
                mergeStoppedChannels(entry.getKey(), entry.getValue());
                original.addSuppressed(failure);
            }
        }
    }

    private int remainingRecoveryCapacity(DiscoveredTuner tuner)
    {
        return Math.max(0, MAXIMUM_REMEMBERED_CHANNELS -
            mStoppedChannels.getOrDefault(tuner, List.of()).size());
    }

    private void restoreOnce(DiscoveredTuner tuner)
    {
        List<RememberedChannel> remembered = mStoppedChannels.getOrDefault(tuner, List.of());
        tuner.beginRestoreAllocation();
        RestoreResult result;
        try
        {
            result = restartRemembered(remembered);
        }
        finally
        {
            tuner.endRestoreAllocation();
        }
        mRestoreResults.put(tuner, result);
        retainFailedChannels(tuner, remembered, result.failed());
        if(!result.failed().isEmpty())
        {
            return;
        }
        if(!tuner.enterLive())
        {
            throw new IllegalStateException("Tuner unavailable");
        }
    }

    private RestoreResult restartRemembered(List<RememberedChannel> remembered)
    {
        List<ChannelInfo> failed = new ArrayList<>();
        for(RememberedChannel channel: remembered)
        {
            if(!channel.restorable()) continue;
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
        return new RestoreResult(List.copyOf(failed));
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
                    if((TunerSettingCatalog.requiresSetup(configuration, settingId) ||
                        "frequency_mhz".equals(settingId)) && !isIdle(tuner))
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
        if(mClosing || mClosed)
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

        long deadline = System.nanoTime() + unit.toNanos(timeout);
        List<CompletableFuture<?>> accepted;
        boolean alreadyClosed;
        synchronized(mLifecycleLock)
        {
            alreadyClosed = mClosed;
            if(!alreadyClosed)
            {
                if(mClosing) throw new IllegalStateException("Tuner settings service is already stopping");
                mClosing = true;
                accepted = List.copyOf(mBrowseRequests);
            }
            else accepted = List.of();
        }

        try
        {
            if(!alreadyClosed)
            {
                //Requests accepted before the closing gate either finish or fail their install before owners are read.
                for(CompletableFuture<?> request: accepted) awaitBrowseRequest(request, deadline, false);

                List<BrowseSession> sessions;
                synchronized(mLifecycleLock)
                {
                    sessions = List.copyOf(mBrowseOwners.values()).stream().distinct()
                        .filter(session -> session.takeover).toList();
                }
                for(BrowseSession session: sessions)
                {
                    awaitBrowseRequest(endBrowse(session.target, session.id, false, null, true), deadline, false);
                }

                synchronized(mLifecycleLock)
                {
                    if(mStoppedChannels.values().stream().anyMatch(channels -> !channels.isEmpty()))
                    {
                        throw new IllegalStateException(
                            "Stopped channels remain; restore them before stopping tuner settings");
                    }
                    mClosed = true;
                    mBrowseExpiry.shutdownNow();
                    mBrowseOwners.clear();
                    mBrowseRequests.forEach(request -> request.completeExceptionally(
                        new IllegalStateException("Tuner settings service is stopping")));
                    mBrowseRequests.clear();
                    mTransitions.clear();
                    mLifecycleReservations.clear();
                    mRestoreResults.clear();
                    mExecutor.getQueue().clear();
                    // Do not interrupt a USB/native setter in progress. Its completion is awaited below.
                    mExecutor.shutdown();
                }
            }

            long remaining = Math.max(0, deadline - System.nanoTime());
            if(!mExecutor.awaitTermination(remaining, TimeUnit.NANOSECONDS))
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
        catch(RuntimeException exception)
        {
            synchronized(mLifecycleLock)
            {
                if(!mClosed) mClosing = false;
            }
            throw exception;
        }
    }

    private static void awaitBrowseRequest(CompletableFuture<?> request, long deadline, boolean ignoreFailure)
    {
        long remaining = deadline - System.nanoTime();
        if(remaining <= 0)
            throw new IllegalStateException("Tuner settings worker did not stop; Spectrum is still restoring a tuner");
        try
        {
            request.get(remaining, TimeUnit.NANOSECONDS);
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while restoring a Spectrum tuner", exception);
        }
        catch(java.util.concurrent.ExecutionException exception)
        {
            if(!ignoreFailure)
                throw new IllegalStateException("Spectrum could not restore its tuner before shutdown", exception);
        }
        catch(java.util.concurrent.TimeoutException exception)
        {
            throw new IllegalStateException("Spectrum could not restore its tuner before shutdown", exception);
        }
    }

    public record MutationResult(String status, String message)
    {
    }

    public record ChannelInfo(String id, String name, @JsonProperty("parent_id") String parentId,
                              String kind, @JsonProperty("frequency_hz") Long frequencyHz,
                              String system, String site)
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
