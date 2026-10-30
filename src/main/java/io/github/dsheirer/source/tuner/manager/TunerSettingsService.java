package io.github.dsheirer.source.tuner.manager;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.controller.channel.ChannelProcessingManager.TunerChannelAssignment;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner1;
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
    private final TunerSettingsApplication mApplication;
    private final Supplier<List<DiscoveredTuner>> mInventory;
    private final LongSupplier mClock;
    private final ChannelProcessingManager mChannelProcessingManager;
    private final ThreadPoolExecutor mExecutor;
    private final Map<DiscoveredTuner,OperatorSession> mSessions = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> mPendingOperations = ConcurrentHashMap.newKeySet();
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
        mApplication = new TunerSettingsApplication(contains, find);
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

    /** One receiver-owned record of an operator's work on a tuner, including recovery after a failed handoff. */
    private static final class OperatorSession
    {
        private volatile String transition;
        private volatile boolean reserved;
        private volatile String error;
        private volatile List<RememberedChannel> stoppedChannels = List.of();
        private volatile RestoreResult restoreResult;
        private volatile BrowseSession browse;
        private volatile OperationStatus operation;

        synchronized void updateStopped(java.util.function.UnaryOperator<List<RememberedChannel>> update)
        {
            List<RememberedChannel> next = update.apply(stoppedChannels);
            stoppedChannels = next == null ? List.of() : List.copyOf(next);
        }
    }

    private OperatorSession session(DiscoveredTuner tuner)
    {
        return mSessions.computeIfAbsent(tuner, _ -> new OperatorSession());
    }

    private boolean isReserved(DiscoveredTuner tuner)
    {
        OperatorSession existing = mSessions.get(tuner);
        return existing != null && existing.reserved;
    }

    private BrowseSession browseOwner(DiscoveredTuner tuner)
    {
        OperatorSession existing = mSessions.get(tuner);
        return existing != null ? existing.browse : null;
    }

    private void clearBrowse(DiscoveredTuner tuner, BrowseSession expected)
    {
        OperatorSession existing = mSessions.get(tuner);
        if(existing != null && existing.browse == expected)
        {
            existing.browse = null;
            if(tuner == expected.target) tuner.setSpectrumObserved(false);
        }
    }

    public List<TunerSettingCatalog.SettingDescriptor> describe(DiscoveredTuner tuner)
    {
        List<TunerSettingCatalog.SettingDescriptor> descriptors = TunerSettingCatalog.describe(tuner);

        if(!isIdle(tuner))
        {
            descriptors = descriptors.stream().map(descriptor -> "frequency_mhz".equals(descriptor.id()) ?
                unavailable(descriptor, "Channels are using this tuner") : descriptor).toList();
        }

        if(tuner instanceof DiscoveredRspDuoTuner1 && mApplication.sibling(tuner) instanceof DiscoveredTuner slave &&
            !isFullyDisabled(slave))
        {
            descriptors = descriptors.stream().map(descriptor -> "sample_rate".equals(descriptor.id()) ?
                unavailable(descriptor, "Disable tuner 2") : descriptor).toList();
        }

        if(!TunerSettingCatalog.isRspDuoSlave(tuner))
        {
            return projectBrowseSettings(tuner, descriptors);
        }

        DiscoveredTuner master = mApplication.sibling(tuner);
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
            BrowseSession owner = browseOwner(tuner);
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
            BrowseSession owner = browseOwner(tuner);
            if(owner != null && !Objects.equals(owner.id, leaseId))
            {
                if(leaseId != null) throw new BrowseOwnershipLostException();
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
            if(lifecycleGroup(tuner).stream().anyMatch(this::isReserved))
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
                mApplication.sibling(tuner) instanceof DiscoveredTuner slave && !isFullyDisabled(slave))
            {
                throw new SettingUnavailableException("Disable tuner 2");
            }
            TunerSettingsApplication.Result result = TunerSettingCatalog.isFrequencyExtent(settingId) ?
                mApplication.applyFrequencyExtents(tuner, Map.of(settingId, value)) :
                mApplication.apply(tuner, settingId, value);
            session(tuner).error = result.error();
            if(result.status() == TunerSettingsApplication.ApplyResult.RETRY)
            {
                throw new SettingUnavailableException("Tuner busy");
            }
            if(result.status() != TunerSettingsApplication.ApplyResult.APPLIED)
            {
                throw new IllegalArgumentException(Objects.requireNonNullElse(session(tuner).error, "Change failed"));
            }
            try
            {
                mPersist.run();
            }
            catch(Exception e)
            {
                session(tuner).error = "Saved value unavailable";
                mLog.warn("Unable to save tuner settings for [{}]", tuner.getId(), e);
            }
        }
        return new MutationResult("applied", null);
    }

    public MutationResult requestState(DiscoveredTuner tuner, DiscoveredTuner.OperatorState state)
    {
        return requestState(tuner, state, null);
    }

    /** A departing browser may act only on the operator decision it actually observed. */
    public MutationResult requestState(DiscoveredTuner tuner, DiscoveredTuner.OperatorState state,
                                       Long operatorGeneration)
    {
        synchronized(mLifecycleLock)
        {
            ensureOpen();
            ensurePresent(tuner);
            Objects.requireNonNull(state);
            if(operatorGeneration != null && tuner.operatorGeneration() != operatorGeneration)
                throw new SettingUnavailableException("The tuner mode changed. Review its current mode and try again.");
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
            session(tuner).error = null;
            session(tuner).restoreResult = null;
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
            session(tuner).error = null;
            session(tuner).restoreResult = null;
            submitLifecycle(tuner, group, () -> restoreOnce(tuner));
        }
        return new MutationResult("accepted", null);
    }

    public String error(DiscoveredTuner tuner)
    {
        OperatorSession existing = mSessions.get(tuner);
        return existing != null ? existing.error : null;
    }

    public String transition(DiscoveredTuner tuner)
    {
        OperatorSession existing = mSessions.get(tuner);
        return existing != null ? existing.transition : null;
    }

    public List<ChannelInfo> stoppedChannels(DiscoveredTuner tuner)
    {
        OperatorSession existing = mSessions.get(tuner);
        return existing != null ? existing.stoppedChannels.stream().map(RememberedChannel::info).toList() : List.of();
    }

    /** Includes prior channels still owned by a failed, retryable browse handoff. No saved state is changed. */
    public List<ChannelInfo> previousChannelsNotResumed(DiscoveredTuner tuner)
    {
        synchronized(mLifecycleLock)
        {
            OperatorSession existing = mSessions.get(tuner);
            if(existing == null) return List.of();
            BrowseSession owner = existing.browse;
            List<RememberedChannel> channels = new ArrayList<>();
            for(DiscoveredTuner member: owner != null ? owner.group : lifecycleGroup(tuner))
            {
                OperatorSession session = mSessions.get(member);
                if(session != null) mergeRemembered(channels, session.stoppedChannels);
                if(owner != null) mergeRemembered(channels, owner.pendingTakeoverChannels(member));
            }
            return channels.stream().filter(RememberedChannel::restorable).map(RememberedChannel::info).toList();
        }
    }

    public RestoreResult restoreResult(DiscoveredTuner tuner)
    {
        OperatorSession existing = mSessions.get(tuner);
        return existing != null ? existing.restoreResult : null;
    }

    /** Latest finite operation only; no operation history or retry queue. */
    public OperationStatus operation(DiscoveredTuner tuner)
    {
        OperatorSession existing = mSessions.get(tuner);
        return existing != null ? existing.operation : null;
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
                BrowseSession owner = browseOwner(tuner);
                if(owner != null && !Objects.equals(owner.id, leaseId))
                    throw new BrowseOwnershipLostException();
                if(!verifyBrowse(tuner, leaseId))
                    throw new SettingUnavailableException("Spectrum tuner session expired; reopen Spectrum");
                owner.expiresAt = mClock.getAsLong() + BROWSE_LIFETIME_MS;
                return CompletableFuture.completedFuture(owner.lease());
            }
            if(tuner.getTunerStatus() == TunerStatus.ERROR || tuner.getTunerStatus() == TunerStatus.REMOVED)
                throw new SettingUnavailableException("Selected tuner is unavailable for live Spectrum");
            if(tuner.getOperatorState() == DiscoveredTuner.OperatorState.DISABLED)
                throw new SettingUnavailableException("Start Setup to inspect this tuner");
            boolean recording = tuner.getTunerClass() == TunerClass.RECORDING_TUNER;
            if(recording && takeover)
                throw new SettingUnavailableException("Recording playback has a fixed capture window; tuning takeover is unavailable");
            List<DiscoveredTuner> group = lifecycleGroup(tuner);
            BrowseSession existing = group.stream().map(this::browseOwner).filter(Objects::nonNull)
                .findFirst().orElse(null);
            if(existing != null) throw browseConflict(tuner, existing);
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
                    if(recording)
                    {
                        // Observing a WAV must not start a stopped producer, stop its channels, or change its mode.
                        BrowseSession session = new BrowseSession(tuner, group, Map.of(), false,
                            false, Map.of(), Map.of());
                        synchronized(mLifecycleLock)
                        {
                            ensureOpen();
                            group.forEach(member -> session(member).browse = session);
                        }
                        return session.lease();
                    }
                    boolean idle = group.stream().allMatch(TunerSettingsService::isIdle);
                    if(!idle && !takeover && (!tuner.isAvailable() || !tuner.hasTuner() || isIdle(tuner)))
                        throw new SettingUnavailableException("Channels are using this tuner's paired hardware");
                    if(takeover)
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
                            if(previous.get(member) == DiscoveredTuner.OperatorState.DISABLED) continue;
                            member.prepareForSetup();
                        }
                        for(DiscoveredTuner member: group)
                        {
                            if(previous.get(member) == DiscoveredTuner.OperatorState.DISABLED) continue;
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
                    BrowseSession session = new BrowseSession(tuner, group, previous,
                        tuner.getOperatorState() == DiscoveredTuner.OperatorState.SETUP && (idle || takeover),
                        takeover, stopped, centerLocks);
                    synchronized(mLifecycleLock)
                    {
                        ensureOpen();
                        group.forEach(member -> session(member).browse = session);
                        tuner.setSpectrumObserved(true);
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

    /** Transfers browser ownership without ending the receiver-owned session or changing its restoration work. */
    public BrowseLease claimBrowse(DiscoveredTuner tuner, String sessionRevision)
    {
        synchronized(mLifecycleLock)
        {
            ensureOpen();
            ensurePresent(tuner);
            if(sessionRevision == null || sessionRevision.isBlank() || sessionRevision.length() > 64)
                throw new IllegalArgumentException("claim_session is invalid");
            BrowseSession owner = browseOwner(tuner);
            if(owner == null)
                throw new SettingUnavailableException("The Spectrum session ended. Retry browsing this tuner.");
            BrowseConflictException conflict = browseConflict(tuner, owner);
            if(!conflict.claimAllowed() || !Objects.equals(owner.revision, sessionRevision)) throw conflict;
            List<DiscoveredTuner> locked = lockAllocationGroup(owner.group);
            try
            {
                // Taking control is a newer operator decision even when the displayed mode stays Setup.
                // Fence a displaced page's pending navigation action as well as its former lease token.
                for(DiscoveredTuner member: owner.group)
                {
                    member.claimOperatorOwnership();
                    owner.adoptOperatorGeneration(member);
                }
                owner.id = UUID.randomUUID().toString();
                owner.revision = UUID.randomUUID().toString();
                owner.expiresAt = mClock.getAsLong() + BROWSE_LIFETIME_MS;
                return owner.lease();
            }
            finally { unlockAllocationGroup(locked); }
        }
    }

    /** The public revision identifies the observed owner but never authorizes tuning or releasing its lease. */
    private BrowseConflictException browseConflict(DiscoveredTuner tuner, BrowseSession owner)
    {
        boolean discovery = owner.group.stream().anyMatch(DiscoveredTuner::isDiscoveryHeld);
        boolean transition = owner.group.stream().anyMatch(this::isReserved);
        boolean available = owner.ownsModes() && tuner.getTunerStatus() != TunerStatus.ERROR &&
            tuner.getTunerStatus() != TunerStatus.REMOVED;
        boolean sameTarget = owner.target == tuner;
        String message = discovery ? "Signal identification is using this tuner. Finish it before taking over." :
            transition || !available ? "This Spectrum session is finishing a receiver operation. Retry shortly." :
            !sameTarget ? "Another Spectrum session is using this tuner's paired hardware. Select its tuner to take over." :
            "Another Spectrum session is using this tuner";
        return new BrowseConflictException(message, owner.revision,
            sameTarget && available && !discovery && !transition);
    }

    public boolean verifyBrowse(DiscoveredTuner tuner, String leaseId)
    {
        synchronized(mLifecycleLock)
        {
            BrowseSession owner = browseOwner(tuner);
            return ownsBrowse(tuner, leaseId) && owner.expiresAt > mClock.getAsLong();
        }
    }

    /** Exact ownership can outlive the heartbeat while its bounded restoration is still pending. */
    public boolean ownsBrowse(DiscoveredTuner tuner, String leaseId)
    {
        synchronized(mLifecycleLock)
        {
            BrowseSession owner = browseOwner(tuner);
            return !mClosing && !mClosed && owner != null && owner.target == tuner && Objects.equals(owner.id, leaseId) &&
                owner.ownsModes();
        }
    }

    /** Explicit handoff leaves owned setup hardware Live; ordinary close restores only this session's changes. */
    public CompletableFuture<Void> releaseBrowse(DiscoveredTuner tuner, String leaseId, boolean handoff)
    {
        return endBrowse(tuner, leaseId, handoff, null, null, false);
    }

    /** Ends observation while retaining the operator's explicit Setup choice and its paused channels. */
    public CompletableFuture<Void> releaseBrowse(DiscoveredTuner tuner, String leaseId, boolean handoff,
                                                boolean keepSetup)
    {
        if(handoff && keepSetup) throw new IllegalArgumentException("handoff and keep_setup cannot be combined");
        return endBrowse(tuner, leaseId, handoff, null, null, false, keepSetup);
    }

    /** Starts the saved channel on the lifecycle worker before competing allocation can change this center. */
    public <T> CompletableFuture<T> handoffBrowse(DiscoveredTuner tuner, String leaseId, Supplier<T> start)
    {
        return handoffBrowse(tuner, leaseId, null, start);
    }

    /** Transfers this exact probe reservation under the allocation gate, without an unprotected center change. */
    public <T> CompletableFuture<T> handoffBrowse(DiscoveredTuner tuner, String leaseId, ProbeHold hold,
                                                 Supplier<T> start)
    {
        return endBrowse(tuner, leaseId, true, Objects.requireNonNull(start), hold, false);
    }

    /** Saved-channel start remains usable after browsing ends; a current owner still requires its exact handoff. */
    public <T> CompletableFuture<T> startSavedChannel(DiscoveredTuner tuner, String leaseId,
                                                      Supplier<T> currentCenterStart, Supplier<T> normalStart)
    {
        Objects.requireNonNull(tuner);
        Objects.requireNonNull(leaseId);
        Objects.requireNonNull(currentCenterStart);
        Objects.requireNonNull(normalStart);
        synchronized(mLifecycleLock)
        {
            ensureOpen();
            if(browseOwner(tuner) != null) return handoffBrowse(tuner, leaseId, currentCenterStart);
        }
        // Ordinary allocation applies its own hardware gates, including any newer browse/probe ownership.
        return CompletableFuture.completedFuture(normalStart.get());
    }

    private <T> CompletableFuture<T> endBrowse(DiscoveredTuner tuner, String leaseId, boolean handoff,
                                             Supplier<T> start, ProbeHold handoffHold, boolean closingRestore)
    {
        return endBrowse(tuner, leaseId, handoff, start, handoffHold, closingRestore, false);
    }

    private <T> CompletableFuture<T> endBrowse(DiscoveredTuner tuner, String leaseId, boolean handoff,
                                             Supplier<T> start, ProbeHold handoffHold, boolean closingRestore,
                                             boolean keepSetup)
    {
        synchronized(mLifecycleLock)
        {
            if(closingRestore)
            {
                if(mClosed) return CompletableFuture.completedFuture(null);
            }
            else ensureOpen();
            BrowseSession owner = browseOwner(tuner);
            if(owner == null)
            {
                if(start != null) throw new SettingUnavailableException("Spectrum tuner session expired");
                return CompletableFuture.completedFuture(null);
            }
            if(owner.target != tuner || !Objects.equals(owner.id, leaseId))
                throw new BrowseOwnershipLostException();
            if(handoffHold != null && (!handoff || handoffHold.tuner != tuner ||
                !handoffHold.group.equals(owner.group) || !handoffHold.valid()))
                throw new SettingUnavailableException("The signal identification reservation changed; retry the saved channel");
            if(owner.group.stream().anyMatch(DiscoveredTuner::isDiscoveryHeld) && handoffHold == null)
                throw new SettingUnavailableException("Stop signal identification before releasing the tuner");
            reserveTransition(tuner, owner.group, "ending_browse");
            return submitBrowse(tuner, owner.group, () ->
            {
                List<DiscoveredTuner> locked = lockAllocationGroup(owner.group);
                try
                {
                    if(keepSetup)
                    {
                        if(!owner.ownsModes()) throw new BrowseOwnershipLostException();
                        retainStoppedChannels(owner);
                        for(DiscoveredTuner member: owner.pendingCenterLockMembers())
                        {
                            restoreCenterLock(member, owner.centerLocks.get(member));
                            owner.markCenterLockRestored(member);
                        }
                        synchronized(mLifecycleLock) { abandonBrowse(tuner); }
                        return null;
                    }
                    if(handoff) return completeBrowseHandoff(tuner, owner, handoffHold, start);
                    boolean ownsModes = owner.ownsModes();
                    RuntimeException failure = null;
                    try
                    {
                        if(closingRestore && owner.takeover && !ownsModes)
                            throw new SettingUnavailableException("The tuner changed before its channels were restored");
                        if(ownsModes && owner.canTune)
                        {
                            List<DiscoveredTuner> restoring = owner.takeover ? owner.pendingModeMembers() : owner.group;
                            if(!restoring.stream().allMatch(TunerSettingsService::isIdle))
                                throw new SettingUnavailableException("A sample source is still using this tuner");
                            if(!owner.takeover)
                                restoreBrowseModes(owner.previous, false);
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
                            if(ownsModes && failure == null) restoreTakeover(owner, false);
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
                    if(failure != null) throw failure;
                    return null;
                }
                finally { unlockAllocationGroup(locked); }
            }, closingRestore);
        }
    }

    /** Lifecycle worker only. The selected channel owns the current window before prior channels may resume. */
    private <T> T completeBrowseHandoff(DiscoveredTuner tuner, BrowseSession owner, ProbeHold hold,
                                        Supplier<T> start)
    {
        if(!owner.ownsModes())
            throw new SettingUnavailableException("The tuner changed; retry the saved channel");
        if(hold != null)
        {
            if(!hold.valid())
                throw new SettingUnavailableException("The signal identification reservation changed; retry the saved channel");
            hold.close();
        }
        owner.group.forEach(member -> member.setDiscoveryHeld(true));
        boolean prepared = false;
        try
        {
            if(owner.canTune)
            {
                List<DiscoveredTuner> restoring = owner.takeover ? owner.pendingModeMembers() : owner.group;
                if(!restoring.stream().allMatch(TunerSettingsService::isIdle))
                    throw new SettingUnavailableException("A sample source is still using this tuner. Stop live listening and retry.");
                for(DiscoveredTuner member: restoring)
                {
                    try
                    {
                        restoreBrowseMode(member, member.getOperatorState(), true);
                        if(owner.takeover) owner.markTakeoverModeRestored(member);
                    }
                    finally { synchronized(mLifecycleLock) { owner.adoptOperatorGeneration(member); } }
                }
                // Restore the existing lock policy before starting anything; a failure remains owned and retryable.
                for(DiscoveredTuner member: owner.pendingCenterLockMembers())
                {
                    restoreCenterLock(member, owner.centerLocks.get(member));
                    owner.markCenterLockRestored(member);
                }
            }
            prepared = true;
            try
            {
                return start != null ? start.get() : null;
            }
            finally
            {
                try { if(owner.takeover) resumeTakeoverAtCurrentCenter(owner); }
                finally { synchronized(mLifecycleLock) { abandonBrowse(tuner); } }
            }
        }
        finally
        {
            if(!prepared)
            {
                retainStoppedChannels(owner);
                synchronized(mLifecycleLock) { owner.expiresAt = mClock.getAsLong() + BROWSE_LIFETIME_MS; }
            }
            owner.group.forEach(member -> member.setDiscoveryHeld(false));
        }
    }

    /** An incompatible prior channel stays recoverable; it must never retune or invalidate a successful new start. */
    private void resumeTakeoverAtCurrentCenter(BrowseSession owner)
    {
        for(DiscoveredTuner member: owner.pendingTakeoverMembers())
        {
            List<RememberedChannel> remembered = owner.pendingTakeoverChannels(member);
            RestoreResult result = restartRemembered(remembered, member);
            session(member).restoreResult = result;
            retainFailedChannels(member, remembered, result.failed());
            owner.retainFailedTakeoverChannels(member, remembered, result.failed());
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
                session(entry.getKey()).restoreResult = result;
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
                    session(member).restoreResult = result;
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
        session(tuner).updateStopped(existing ->
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
        session(tuner).updateStopped(existing ->
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
            if(group.stream().anyMatch(member -> isReserved(member) || member.isDiscoveryHeld()))
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
            boolean recording = tuner.hasTuner() && tuner.getTunerClass() == TunerClass.RECORDING_TUNER;
            if(!verifyBrowse(tuner, leaseId) || !recording && !browseOwner(tuner).canTune ||
                !lifecycleGroup(tuner).stream().allMatch(TunerSettingsService::isIdle))
                throw new SettingUnavailableException("Choose an idle receiver for the search");
            if(!recording && tuner.getTuner().getTunerController().isCenterFrequencyLocked())
                throw new SettingUnavailableException("Unlock the receiver's center frequency before searching");
            ProbeHold hold = holdForProbe(tuner);
            hold.searchLeaseId = leaseId;
            return hold;
        }
    }

    private <T> CompletableFuture<T> submitBrowse(DiscoveredTuner tuner, List<DiscoveredTuner> group,
                                                 Supplier<T> operation)
    {
        return submitOperation(tuner, group, operation, false, false);
    }

    private <T> CompletableFuture<T> submitBrowse(DiscoveredTuner tuner, List<DiscoveredTuner> group,
                                                 Supplier<T> operation, boolean allowClosing)
    {
        return submitOperation(tuner, group, operation, allowClosing, false);
    }

    /** Every finite tuner command shares admission, progress, completion and shutdown ownership. */
    private <T> CompletableFuture<T> submitOperation(DiscoveredTuner tuner, List<DiscoveredTuner> group,
                                                     Supplier<T> command, boolean allowClosing,
                                                     boolean reportMaintenanceError)
    {
        CompletableFuture<T> result = new CompletableFuture<>();
        OperationStatus accepted = session(tuner).operation;
        mPendingOperations.add(result);
        try
        {
            mExecutor.execute(() ->
            {
                T value = null;
                Throwable failure = null;
                synchronized(mLifecycleLock)
                {
                    session(tuner).operation = accepted.running();
                }
                try
                {
                    if(mClosed || (mClosing && !allowClosing) || !mContains.test(tuner))
                        throw new IllegalStateException("Tuner unavailable");
                    value = command.get();
                }
                catch(Throwable exception)
                {
                    failure = exception;
                    if(reportMaintenanceError)
                    {
                        session(tuner).error = operationFailure(exception);
                        mLog.warn("Unable to change tuner state for [{}]", tuner.getId(), exception);
                    }
                }
                finally
                {
                    finishTransition(tuner, group, accepted, failure);
                    mPendingOperations.remove(result);
                }
                if(failure == null) result.complete(value);
                else result.completeExceptionally(failure);
            });
        }
        catch(RejectedExecutionException exception)
        {
            IllegalStateException failure = new IllegalStateException("Tuner maintenance is unavailable", exception);
            finishTransition(tuner, group, accepted, failure);
            mPendingOperations.remove(result);
            result.completeExceptionally(failure);
            if(reportMaintenanceError) throw failure;
        }
        return result;
    }

    private static String operationFailure(Throwable exception)
    {
        return exception instanceof SettingUnavailableException ||
            "Tuner unavailable".equals(exception.getMessage()) || "Use Setup".equals(exception.getMessage()) ?
            exception.getMessage() : "Change failed";
    }

    /** Public adapters can report a known receiver error without leaking arbitrary native exception text. */
    public static Throwable unwrapHandoffFailure(Throwable failure)
    {
        Throwable result = Objects.requireNonNull(failure);
        while((result instanceof java.util.concurrent.CompletionException ||
            result instanceof java.util.concurrent.ExecutionException) && result.getCause() != null &&
            result.getCause() != result) result = result.getCause();
        return result;
    }

    public static String handoffFailureMessage(Throwable failure)
    {
        Throwable cause = unwrapHandoffFailure(failure);
        // This type carries receiver-owned, operator-facing messages; arbitrary native exceptions do not.
        if(cause instanceof SettingUnavailableException) return cause.getMessage();
        String message = cause.getMessage();
        return Set.of("Tuner unavailable", "Tuner maintenance is unavailable", "Tuner settings service is stopping")
            .contains(message != null ? message : "") ? message : null;
    }

    /** Keeps causal classes and stack frames for private diagnosis, but excludes unknown exception messages. */
    public static Throwable handoffFailureForLogging(Throwable failure)
    {
        return handoffFailureForLogging(failure, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), 0);
    }

    private static Throwable handoffFailureForLogging(Throwable failure, Set<Throwable> visited, int depth)
    {
        if(failure == null || depth >= 16 || !visited.add(failure)) return null;
        String message = handoffFailureMessage(failure);
        RuntimeException safe = new RuntimeException(failure.getClass().getName() +
            (message != null ? ": " + message : ": exception detail omitted"));
        safe.setStackTrace(failure.getStackTrace());
        Throwable cause = handoffFailureForLogging(failure.getCause(), visited, depth + 1);
        if(cause != null) safe.initCause(cause);
        for(Throwable suppressed: failure.getSuppressed())
        {
            Throwable copy = handoffFailureForLogging(suppressed, visited, depth + 1);
            if(copy != null) safe.addSuppressed(copy);
        }
        return safe;
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
        if(mode == DiscoveredTuner.OperatorState.DISABLED)
        {
            tuner.setEnabled(false);
        }
        else if(handoff || mode == DiscoveredTuner.OperatorState.LIVE)
        {
            if(!tuner.enterLive()) throw new IllegalStateException("Tuner unavailable");
        }
    }

    private void abandonBrowse(DiscoveredTuner tuner)
    {
        BrowseSession owner = browseOwner(tuner);
        if(owner != null) owner.group.forEach(member -> clearBrowse(member, owner));
    }

    private BrowseSession takeoverOwner(DiscoveredTuner tuner)
    {
        BrowseSession owner = browseOwner(tuner);
        return owner != null && owner.takeover ? owner : null;
    }

    void expireBrowsing()
    {
        synchronized(mLifecycleLock)
        {
            if(mClosing || mClosed) return;
            // Retire only inert, absent devices. Recovery and all accepted commands remain receiver-owned.
            if(mPendingOperations.isEmpty())
                mSessions.entrySet().removeIf(entry -> !mContains.test(entry.getKey()) &&
                    !entry.getValue().reserved && entry.getValue().browse == null &&
                    entry.getValue().stoppedChannels.isEmpty());
            for(BrowseSession owner: mSessions.values().stream().map(session -> session.browse).filter(Objects::nonNull).distinct().toList())
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
                BrowseSession owner = browseOwner(tuner);
                if(searchLeaseId == null || !valid() || owner == null || !owner.id.equals(searchLeaseId) ||
                    !owner.ownsModes() || !restoring && !verifyBrowse(tuner, searchLeaseId))
                    throw new SettingUnavailableException("The search receiver changed; begin again");
                if(tuner.getTunerClass() == TunerClass.RECORDING_TUNER)
                {
                    if(frequencyHz != center) throw new SettingUnavailableException("Recording tuners have a fixed capture window");
                    return;
                }
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
        private String id = UUID.randomUUID().toString();
        private String revision = UUID.randomUUID().toString();
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
        if(tuners.stream().anyMatch(this::isReserved))
        {
            throw new SettingUnavailableException("Tuner busy");
        }
        tuners.forEach(member -> session(member).reserved = true);
        session(target).transition = transition;
        session(target).operation = new OperationStatus(UUID.randomUUID().toString(), transition, "queued",
            mClock.getAsLong(), null, null);
    }

    private void submitLifecycle(DiscoveredTuner tuner, List<DiscoveredTuner> group, Runnable operation)
    {
        submitOperation(tuner, group, () -> { operation.run(); return null; }, true, true);
    }

    /** Completion is published before releasing admission, so a newer command cannot be overwritten. */
    private void finishTransition(DiscoveredTuner tuner, List<DiscoveredTuner> group,
                                  OperationStatus accepted, Throwable failure)
    {
        synchronized(mLifecycleLock)
        {
            OperatorSession owner = session(tuner);
            if(owner.operation != null && owner.operation.id().equals(accepted.id()))
                owner.operation = new OperationStatus(accepted.id(), accepted.kind(),
                    failure == null ? "completed" : "failed", accepted.acceptedAtEpochMs(),
                    mClock.getAsLong(), failure == null ? null : operationFailure(failure));
            group.forEach(member -> session(member).reserved = false);
            owner.transition = null;
        }
    }

    private void changeState(DiscoveredTuner tuner, DiscoveredTuner.OperatorState requested)
    {
        DiscoveredTuner.OperatorState current = tuner.getOperatorState();
        DiscoveredTuner pairedSlave = tuner instanceof DiscoveredRspDuoTuner1 ? mApplication.sibling(tuner) : null;
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
                if(current == DiscoveredTuner.OperatorState.SETUP)
                {
                    restoreOnce(tuner, true);
                }
                else if(!tuner.enterLive())
                {
                    throw new IllegalStateException("Tuner unavailable");
                }
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
                session(entry.getKey()).restoreResult = result;
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
            session(tuner).stoppedChannels.size());
    }

    private void restoreOnce(DiscoveredTuner tuner)
    {
        restoreOnce(tuner, false);
    }

    private void restoreOnce(DiscoveredTuner tuner, boolean goLiveOnFailure)
    {
        List<RememberedChannel> remembered = session(tuner).stoppedChannels;
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
        session(tuner).restoreResult = result;
        retainFailedChannels(tuner, remembered, result.failed());
        if(!goLiveOnFailure && !result.failed().isEmpty())
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
        return restartRemembered(remembered, null);
    }

    private RestoreResult restartRemembered(List<RememberedChannel> remembered, DiscoveredTuner currentCenterTuner)
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
                if(currentCenterTuner != null)
                    mChannelProcessingManager.startAtCurrentCenter(channel.channel(), currentCenterTuner);
                else mChannelProcessingManager.start(channel.channel());
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

    private List<DiscoveredTuner> lifecycleGroup(DiscoveredTuner tuner)
    {
        DiscoveredTuner sibling = mApplication.sibling(tuner);
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
     * Whole-receiver teardown leaves saved channels stopped. Drain owned hardware work without resuming old channels
     * or retuning current reception; retain their recovery records until this receiver instance is discarded.
     */
    public void closeForReceiverShutdown()
    {
        closeForReceiverShutdownWithin(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
    }

    void closeForReceiverShutdownWithin(long timeout, TimeUnit unit)
    {
        closeWithin(timeout, unit, true);
    }

    /**
     * Stop future admin work, discard requests not yet claimed by the worker, and wait for a claimed hardware write
     * (including its save) to finish before tuner/channel teardown. A timeout is an explicit unsafe shutdown state:
     * the caller must not assume the worker is quiescent and proceed with tuner disposal.
     */
    void closeWithin(long timeout, TimeUnit unit)
    {
        closeWithin(timeout, unit, false);
    }

    private void closeWithin(long timeout, TimeUnit unit, boolean receiverShutdown)
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
                accepted = List.copyOf(mPendingOperations);
            }
            else accepted = List.of();
        }

        try
        {
            if(!alreadyClosed)
            {
                //Requests accepted before the closing gate either finish or fail their install before owners are read.
                for(CompletableFuture<?> request: accepted) awaitOperation(request, deadline, false);

                List<BrowseSession> sessions;
                synchronized(mLifecycleLock)
                {
                    sessions = List.copyOf(mSessions.values().stream().map(session -> session.browse).filter(Objects::nonNull).toList()).stream().distinct()
                        .filter(session -> session.takeover).toList();
                }
                for(BrowseSession session: sessions)
                {
                    if(receiverShutdown) retainStoppedChannels(session);
                    else awaitOperation(endBrowse(session.target, session.id, false, null, null, true), deadline, false);
                }

                synchronized(mLifecycleLock)
                {
                    if(!receiverShutdown && mSessions.values().stream().anyMatch(session -> !session.stoppedChannels.isEmpty()))
                    {
                        throw new IllegalStateException(
                            "Stopped channels remain; restore them before stopping tuner settings");
                    }
                    if(receiverShutdown)
                    {
                        long stopped = mSessions.values().stream().flatMap(session -> session.stoppedChannels.stream())
                            .filter(RememberedChannel::restorable).map(channel -> channel.info().id()).distinct().count();
                        if(stopped > 0) mLog.info("Receiver shutdown leaves [{}] saved channels stopped", stopped);
                    }
                    mClosed = true;
                    mBrowseExpiry.shutdownNow();
                    mSessions.forEach((tuner, session) -> { tuner.setSpectrumObserved(false); session.browse = null; });
                    mPendingOperations.forEach(request -> request.completeExceptionally(
                        new IllegalStateException("Tuner settings service is stopping")));
                    mPendingOperations.clear();
                    mSessions.values().forEach(session -> session.transition = null);
                    mSessions.values().forEach(session -> session.reserved = false);
                    if(!receiverShutdown) mSessions.values().forEach(session -> session.restoreResult = null);
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

    private static void awaitOperation(CompletableFuture<?> request, long deadline, boolean ignoreFailure)
    {
        long remaining = deadline - System.nanoTime();
        if(remaining <= 0)
            throw new IllegalStateException("Tuner settings worker did not stop; a tuner operation is still running");
        try
        {
            request.get(remaining, TimeUnit.NANOSECONDS);
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a tuner operation", exception);
        }
        catch(java.util.concurrent.ExecutionException exception)
        {
            if(!ignoreFailure)
                throw new IllegalStateException("Tuner operation did not complete before shutdown", exception);
        }
        catch(java.util.concurrent.TimeoutException exception)
        {
            throw new IllegalStateException("Tuner operation did not complete before shutdown", exception);
        }
    }

    public record OperationStatus(String id, String kind, String state,
                                  @JsonProperty("accepted_at_epoch_ms") long acceptedAtEpochMs,
                                  @JsonProperty("completed_at_epoch_ms") Long completedAtEpochMs,
                                  String error)
    {
        OperationStatus running()
        {
            return new OperationStatus(id, kind, "running", acceptedAtEpochMs, null, null);
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

    public static final class BrowseConflictException extends SettingUnavailableException
    {
        private final String mSessionRevision;
        private final boolean mClaimAllowed;

        private BrowseConflictException(String message, String sessionRevision, boolean claimAllowed)
        {
            super(message);
            mSessionRevision = sessionRevision;
            mClaimAllowed = claimAllowed;
        }

        public String sessionRevision() { return mSessionRevision; }
        public boolean claimAllowed() { return mClaimAllowed; }
    }

    public static final class BrowseOwnershipLostException extends SettingUnavailableException
    {
        private BrowseOwnershipLostException()
        {
            super("This Spectrum session was taken over in another tab or browser.");
        }
    }

    public static class SettingUnavailableException extends IllegalStateException
    {
        public SettingUnavailableException(String message)
        {
            super(message);
        }
    }
}
