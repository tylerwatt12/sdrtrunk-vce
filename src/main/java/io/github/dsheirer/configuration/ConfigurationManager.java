/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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
package io.github.dsheirer.configuration;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.alias.AliasConfigurationSnapshot;
import io.github.dsheirer.alias.AliasConfigurationEquality;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.BroadcastModel;
import io.github.dsheirer.audio.broadcast.StreamingAdministrationService;
import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelConfigurationChangeNotification;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.ChannelModel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.configuration.ConfigurationRepository;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.log.EventLogManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryException;
import io.github.dsheirer.service.radioreference.RadioReferenceImportService;
import io.github.dsheirer.scanlist.ScanListModel;
import io.github.dsheirer.remote.RemoteLinkSettingsStore;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.function.Function;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages channel, stream, alias, and supporting configuration state.
 */
public class ConfigurationManager implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(ConfigurationManager.class);
    private AliasModel mAliasModel;

    private BroadcastModel mBroadcastModel;
    private ChannelModel mChannelModel;
    private ChannelProcessingManager mChannelProcessingManager;
    private TunerManager mTunerManager;
    private UserPreferences mUserPreferences;
    private final RadioReferenceDirectoryService mRadioReferenceDirectoryService;
    private final RadioReferenceImportService mRadioReferenceImportService;
    private final ConfigurationRepository mConfigurationRepository;
    private final AliasAdministrationService mAliasAdministrationService;
    private final ChannelAdministrationService mChannelAdministrationService;
    private final ScanListModel mScanListModel;
    private final AtomicLong mAliasConfigurationRevision = new AtomicLong();
    //Retained across observer failures so recovery replays live metadata notifications after refreshing the names.
    private final Set<String> mAliasListNameRefreshPending = new HashSet<>();
    private final AtomicLong mChannelConfigurationRevision = new AtomicLong();
    private final AtomicLong mStreamingConfigurationRevision = new AtomicLong();
    private StreamingAdministrationService mStreamingAdministrationService;
    private final ConfigurationExecutor mConfigurationExecutor = new ConfigurationExecutor();
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final Consumer<StatsDatabaseMaintenanceRequest> mMaintenanceDispatcher;
    private final Object mRadioReferenceLoginLock = new Object();
    private volatile boolean mInitialized = false;
    //Owned only by ConfigurationExecutor. A catalog is published once the complete outer command succeeds.
    private int mConfigurationTaskDepth;
    private boolean mChannelCatalogDirty = true;
    private volatile boolean mExternalConfigurationOperation = false;

    /**
     * Manages channel configurations, streams, and alias lists backed by the global SQLite database.
     *
     * Commands commit detached configuration before publishing it to receiver models.
     *
     * @param userPreferences for user settings
     * @param tunerManager for access to tuner model
     * @param aliasModel for aliases
     * @param eventLogManager for event logging
     */
    public ConfigurationManager(UserPreferences userPreferences, TunerManager tunerManager, AliasModel aliasModel,
                           EventLogManager eventLogManager)
    {
        this(userPreferences, tunerManager, aliasModel, eventLogManager, request ->
            request.result().completeExceptionally(new IllegalStateException("Statistics maintenance is unavailable")));
    }

    public ConfigurationManager(UserPreferences userPreferences, TunerManager tunerManager, AliasModel aliasModel,
                                EventLogManager eventLogManager,
                                Consumer<StatsDatabaseMaintenanceRequest> maintenanceDispatcher)
    {
        mMaintenanceDispatcher = Objects.requireNonNull(maintenanceDispatcher);
        mUserPreferences = userPreferences;
        mTunerManager = tunerManager;
        mAliasModel = aliasModel;
        mConfigurationRepository =
            new ConfigurationRepository(SdrTrunkDatabasePath.getDatabasePath(userPreferences));
        mScanListModel = new ScanListModel();
        mAliasAdministrationService = new AliasAdministrationService(this);

        mBroadcastModel = new BroadcastModel(mAliasModel, userPreferences);
        mRadioReferenceDirectoryService = new RadioReferenceDirectoryService();

        mChannelModel = new ChannelModel();
        mChannelProcessingManager = new ChannelProcessingManager(eventLogManager, mTunerManager, mAliasModel,
            mUserPreferences);
        mChannelAdministrationService = new ChannelAdministrationService(this);
        mStreamingAdministrationService = new StreamingAdministrationService(this);
        mRadioReferenceImportService = new RadioReferenceImportService(mRadioReferenceDirectoryService, this);

        //Register the channel processing manager to receive global channel stop processing requests so that it can
        //respond to tuner shutdown (ie error) events
        MyEventBus.getGlobalEventBus().register(mChannelProcessingManager);

        mChannelModel.addListener(mChannelProcessingManager);
        mChannelProcessingManager.addChannelEventListener(mChannelModel);
    }

    /** All configuration clients share this owner; windows never supply its execution thread. */
    public <T> T executeConfigurationTask(Supplier<T> operation)
    {
        return mConfigurationExecutor.call(() -> completeConfigurationTask(operation));
    }

    public CompletableFuture<Void> submitConfigurationTask(Runnable operation)
    {
        return mConfigurationExecutor.submit(() -> completeConfigurationTask(() -> { operation.run(); return null; }));
    }

    private <T> T completeConfigurationTask(Supplier<T> operation)
    {
        mConfigurationTaskDepth++;
        boolean completed = false;
        try
        {
            T result = operation.get();
            completed = true;
            return result;
        }
        finally
        {
            if(--mConfigurationTaskDepth == 0)
            {
                if(completed && mInitialized && mChannelCatalogDirty)
                    mChannelAdministrationService.publishCommittedCatalog();
                mChannelCatalogDirty = false;
            }
        }
    }

    /** Learners submit detached commands without putting database work on metadata or decoder callbacks. */
    public void submitLearnedChannelUpdate(SiteMetadataEvent event, Function<Channel,Boolean> update)
    {
        submitConfigurationTask(() ->
        {
            if(event == null || !event.isUseful() || !event.matchesCurrentChannel())
            {
                update.apply(null);
                return;
            }
            Channel live = event.channel();
            if(mExternalConfigurationOperation || !mChannelModel.getChannels().contains(live)) return;
            Channel candidate = live.copyOfPreservingIdentity();
            if(!update.apply(candidate)) return;

            List<Channel> channels = mChannelModel.getChannels().stream()
                .map(channel -> channel == live ? candidate : channel).toList();
            ChannelConfigurationSnapshot committed;
            try
            {
                committed = mConfigurationRepository.commitChannelConfiguration(new ChannelConfigurationSnapshot(channels));
            }
            catch(Exception failure)
            {
                throw new ConfigurationCommitException("Unable to save learned channel settings", failure);
            }
            Channel saved = committed.channels().stream()
                .filter(channel -> channel.getConfigurationId().equals(live.getConfigurationId())).findFirst().orElseThrow();
            //Keep the active chain's Channel and configuration objects; only learning-owned fields change.
            live.setP25SiteIdentity(saved.getP25SiteIdentity());
            if(live.getDecodeConfiguration() instanceof DecodeConfigP25 decoder &&
                saved.getDecodeConfiguration() instanceof DecodeConfigP25 learned)
                decoder.setLearnedControlFrequencies(learned.getLearnedControlFrequencies());
            if(live.getSourceConfiguration() instanceof SourceConfigTunerMultipleFrequency source &&
                saved.getSourceConfiguration() instanceof SourceConfigTunerMultipleFrequency learned)
            {
                boolean frequenciesChanged = !source.getFrequencies().equals(learned.getFrequencies());
                if(frequenciesChanged) source.setFrequencies(learned.getFrequencies());
                source.setPreferredFrequency(learned.getPreferredFrequency());
                if(frequenciesChanged) live.setSourceConfiguration(source);
            }
            mChannelConfigurationRevision.incrementAndGet();
            mChannelCatalogDirty = true;
        }).exceptionally(failure ->
        {
            mLog.error("Unable to apply learned channel configuration", failure);
            return null;
        });
    }

    public void dispatchMaintenance(StatsDatabaseMaintenanceRequest request)
    {
        mChannelAdministrationService.prepareStatisticsMaintenance(request);
        if(request.deletionTarget() instanceof StatsDatabaseMaintenanceRequest.TalkerAlias target)
        {
            mChannelProcessingManager.forgetTalkerAlias(target.radioSystemKey(),
                io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey.parse(target.identityKey()),
                target.expectedAlias());
        }
        mMaintenanceDispatcher.accept(request);
    }

    public void quiesceAdministration()
    {
        mConfigurationExecutor.quiesceCalls();
    }

    @Override
    public void close()
    {
        if(mClosed.get()) return;
        mConfigurationExecutor.close();
        if(mClosed.compareAndSet(false, true)) MyEventBus.getGlobalEventBus().unregister(mChannelProcessingManager);
    }

    /**
     * Channel model managed by this configuration manager.
     */
    public ChannelModel getChannelModel()
    {
        return mChannelModel;
    }

    /** Adds a saved channel after assigning or validating its required compatible Alias List. */
    public void addChannel(Channel channel)
    {
        executeConfigurationTask(() ->
        {
            mAliasModel.requireAliasListAssignment(channel);
            List<Channel> channels = new ArrayList<>(mChannelModel.getChannels());
            channels.add(channel);
            commitAndPublishChannelConfigurationOnOwner(new ChannelConfigurationSnapshot(channels),
                Set.of(channel.getConfigurationId()), false);
            return null;
        });
    }

    /**
     * Channel processing manager
     */
    public ChannelProcessingManager getChannelProcessingManager()
    {
        return mChannelProcessingManager;
    }

    /** Web-first channel configuration and lifecycle command boundary. */
    public ChannelAdministrationService getChannelAdministrationService()
    {
        return mChannelAdministrationService;
    }

    /**
     * Tuner manager managed by this configuration manager.
     */
    public TunerManager getTunerManager()
    {
        return mTunerManager;
    }

    /**
     * Alias model managed by this configuration manager.
     */
    public AliasModel getAliasModel()
    {
        return mAliasModel;
    }

    /**
     * Shared mutation boundary used by desktop and web Alias administration clients.
     */
    public AliasAdministrationService getAliasAdministrationService()
    {
        return mAliasAdministrationService;
    }

    /**
     * Runtime scan-list catalog and immutable reverse membership indexes.
     */
    public ScanListModel getScanListModel()
    {
        return mScanListModel;
    }

    /** Shared web-first RadioReference account and directory boundary. */
    public RadioReferenceDirectoryService getRadioReferenceDirectoryService()
    {
        return mRadioReferenceDirectoryService;
    }

    /** Shared web-first RadioReference preview and import boundary. */
    public RadioReferenceImportService getRadioReferenceImportService()
    {
        return mRadioReferenceImportService;
    }

    /** Signs in from saved credentials for non-web consumers such as Broadcastify feed setup. */
    public RadioReferenceDirectoryService.AccountStatus ensureStoredRadioReferenceSession()
        throws RadioReferenceDirectoryException
    {
        if(!canRetryStoredRadioReferenceLogin())
        {
            return mRadioReferenceDirectoryService.status();
        }

        synchronized(mRadioReferenceLoginLock)
        {
            if(!canRetryStoredRadioReferenceLogin())
            {
                return mRadioReferenceDirectoryService.status();
            }

            String userName = mUserPreferences.getRadioReferencePreference().getUserName();
            String password = mUserPreferences.getRadioReferencePreference().getPassword();

            if(userName != null && !userName.isBlank() && password != null && !password.isEmpty())
            {
                return mRadioReferenceDirectoryService.login(userName, password.toCharArray());
            }

            return mRadioReferenceDirectoryService.status();
        }
    }

    private boolean canRetryStoredRadioReferenceLogin()
    {
        RadioReferenceDirectoryService.AccountState state = mRadioReferenceDirectoryService.status().state();
        return state == RadioReferenceDirectoryService.AccountState.SIGNED_OUT ||
            state == RadioReferenceDirectoryService.AccountState.UNAVAILABLE;
    }

    /**
     * Audio Broadcast (streaming) Model
     */
    public BroadcastModel getBroadcastModel()
    {
        return mBroadcastModel;
    }

    /**
     * Loads configuration state from the global SDRTrunk database.
     */
    public void init()
    {
        executeConfigurationTask(() -> {
            transferStateToModels();
            mInitialized = true;
            mChannelCatalogDirty = true;
            return null;
        });
    }

    /**
     * Indicates that the persisted configuration has been loaded into the runtime models.
     */
    public boolean isInitialized()
    {
        return mInitialized;
    }

    /**
     * Monotonic version for optimistic alias administration updates.
     */
    public long getAliasConfigurationRevision()
    {
        return mAliasConfigurationRevision.get();
    }

    /** Monotonic version for optimistic channel-administration updates. */
    public long getChannelConfigurationRevision()
    {
        return mChannelConfigurationRevision.get();
    }

    /** Barrier for previously admitted configuration commands; every mutation is committed before publication. */
    public void flushConfiguration()
    {
        executeConfigurationTask(() ->
        {
            if(mExternalConfigurationOperation)
                throw new ConfigurationPublicationException("Configuration is unavailable until VCE restarts");
            return null;
        });
    }

    /** Executes one detached configuration command on the receiver's configuration owner. */
    public <T> T applyConfigurationMutation(Supplier<T> task)
    {
        return executeConfigurationTask(Objects.requireNonNull(task, "Configuration mutation cannot be null"));
    }

    /**
     * Path to the SQLite database containing this configuration.
     */
    public Path getDatabasePath()
    {
        return mConfigurationRepository.getDatabasePath();
    }

    public List<Long> nextAliasIds(List<Long> candidateIds, int count)
    {
        return mConfigurationRepository.nextAliasIds(candidateIds, count);
    }

    public List<Long> nextAliasListIds(List<Long> candidateIds, int count)
    {
        return mConfigurationRepository.nextAliasListIds(candidateIds, count);
    }

    public List<Long> nextScanListIds(List<Long> candidateIds, int count)
    {
        return mConfigurationRepository.nextScanListIds(candidateIds, count);
    }

    /** Commits one detached channel configuration and publishes only the explicitly changed channel identities. */
    public ChannelConfigurationSnapshot commitAndPublishChannelConfiguration(ChannelConfigurationSnapshot proposed, Set<String> changedConfigurationIds, boolean autoStartOnly)
    {
        return executeConfigurationTask(() -> commitAndPublishChannelConfigurationOnOwner(proposed, changedConfigurationIds, autoStartOnly));
    }

    private ChannelConfigurationSnapshot commitAndPublishChannelConfigurationOnOwner(
        ChannelConfigurationSnapshot proposed, Set<String> changedConfigurationIds, boolean autoStartOnly)
    {
        if(mExternalConfigurationOperation)
        {
            throw new ConfigurationPublicationException(
                "Configuration saves are suspended until VCE restarts");
        }
        Objects.requireNonNull(proposed, "Proposed channel configuration cannot be null");
        Set<String> changed = Set.copyOf(Objects.requireNonNull(changedConfigurationIds,
            "Changed channel IDs cannot be null"));
        if(!autoStartOnly && changed.stream().anyMatch(configurationId ->
            !mChannelProcessingManager.getProcessingChainsByConfiguration(configurationId, null).isEmpty()))
        {
            throw new IllegalStateException("Stop running channels before changing their configuration");
        }

        ChannelConfigurationSnapshot committed;
        try
        {
            committed = mConfigurationRepository.commitChannelConfiguration(proposed);
        }
        catch(Exception exception)
        {
            throw new ConfigurationCommitException("Unable to commit channel configuration", exception);
        }

        return publishChannelConfigurationAfterCommit(committed, changed, autoStartOnly);
    }

    /** Atomic sender/channel removal; the callback exposes the committed credential state before publication. */
    public ChannelConfigurationSnapshot commitAndPublishRemoteSenderRemoval(ChannelConfigurationSnapshot proposed, Set<String> changedConfigurationIds, String senderId, RemoteLinkSettingsStore.Settings nextSettings, Runnable afterCommit)
    {
        return executeConfigurationTask(() -> commitAndPublishRemoteSenderRemovalOnOwner(proposed, changedConfigurationIds, senderId, nextSettings, afterCommit));
    }

    private ChannelConfigurationSnapshot commitAndPublishRemoteSenderRemovalOnOwner(
        ChannelConfigurationSnapshot proposed, Set<String> changedConfigurationIds, String senderId,
        RemoteLinkSettingsStore.Settings nextSettings, Runnable afterCommit)
    {
        if(mExternalConfigurationOperation)
            throw new ConfigurationPublicationException("Configuration saves are suspended until VCE restarts");
        Set<String> changed = Set.copyOf(changedConfigurationIds);
        if(changed.stream().anyMatch(id -> !mChannelProcessingManager.getProcessingChainsByConfiguration(id, null).isEmpty()))
            throw new IllegalStateException("Stop running channels before changing their configuration");
        AtomicBoolean committed = new AtomicBoolean();
        ChannelConfigurationSnapshot saved;
        try
        {
            saved = mConfigurationRepository.commitRemoteSenderRemoval(proposed, senderId, nextSettings, () ->
            {
                committed.set(true);
                afterCommit.run();
            });
        }
        catch(Exception exception)
        {
            if(committed.get())
            {
                mExternalConfigurationOperation = true;
                throw new ConfigurationPublicationException("Remote sender removal committed; restart VCE", exception);
            }
            throw new ConfigurationCommitException("Unable to remove remote sender", exception);
        }
        return changed.isEmpty() ? saved : publishChannelConfigurationAfterCommit(saved, changed, false);
    }

    private ChannelConfigurationSnapshot publishChannelConfigurationAfterCommit(ChannelConfigurationSnapshot committed,
        Set<String> changed, boolean autoStartOnly)
    {
        Map<String,Long> previousAssignments = mChannelModel.getChannels().stream().collect(
            java.util.stream.Collectors.toMap(Channel::getConfigurationId, Channel::getAliasListId));
        Map<String,Long> committedAssignments = committed.channels().stream().collect(
            java.util.stream.Collectors.toMap(Channel::getConfigurationId, Channel::getAliasListId));
        boolean assignmentsChanged = !autoStartOnly && changed.stream().anyMatch(id ->
            !Objects.equals(previousAssignments.get(id), committedAssignments.get(id)));
        try
        {
            publishCommittedChannelConfiguration(committed, changed, autoStartOnly);
            if(assignmentsChanged) mAliasConfigurationRevision.incrementAndGet();
            return committed;
        }
        catch(RuntimeException | Error publicationFailure)
        {
            try
            {
                ChannelConfigurationSnapshot reloaded = mConfigurationRepository.loadChannelConfiguration();
                publishCommittedChannelConfiguration(reloaded, changed, autoStartOnly);
                if(assignmentsChanged) mAliasConfigurationRevision.incrementAndGet();
                mLog.error("Channel configuration committed, but initial publication failed; reloaded committed state",
                    publicationFailure);
                return reloaded;
            }
            catch(Exception recoveryFailure)
            {
                publicationFailure.addSuppressed(recoveryFailure);
                mExternalConfigurationOperation = true;
                throw new ConfigurationPublicationException(
                    "Channel configuration committed but could not be published; restart VCE",
                    publicationFailure);
            }
        }
    }

    /** One persist-before-publish boundary for spectrum channel creation, including listening defaults. */
    public void commitAndPublishDiscoveredChannels(AliasConfigurationSnapshot aliases, ChannelConfigurationSnapshot channels, Set<String> configurationIds)
    {
        executeConfigurationTask(() -> { commitAndPublishDiscoveredChannelsOnOwner(aliases, channels, configurationIds); return null; });
    }

    private void commitAndPublishDiscoveredChannelsOnOwner(AliasConfigurationSnapshot aliases,
        ChannelConfigurationSnapshot channels, Set<String> configurationIds)
    {
        if(mExternalConfigurationOperation)
            throw new ConfigurationPublicationException("Configuration saves are suspended until VCE restarts");
        ConfigurationSnapshot committed;
        try
        {
            committed = mConfigurationRepository.commitDiscoveredChannels(aliases, channels);
        }
        catch(Exception exception)
        {
            throw new ConfigurationCommitException("Unable to save discovered channel", exception);
        }
        try
        {
            publishDiscoveredChannels(committed, configurationIds);
        }
        catch(RuntimeException | Error failure)
        {
            try
            {
                publishDiscoveredChannels(mConfigurationRepository.load(), configurationIds);
            }
            catch(Exception recovery)
            {
                failure.addSuppressed(recovery);
                mExternalConfigurationOperation = true;
                throw new ConfigurationPublicationException("Channel saved but could not be published; restart VCE",
                    failure);
            }
        }
    }

    private void publishDiscoveredChannels(ConfigurationSnapshot snapshot, Set<String> configurationIds)
    {
        publishCommittedAliasConfiguration(snapshot.aliasConfiguration(),
            new AliasConfigurationPublication(Set.of(), true, true, true));
        publishCommittedChannelConfiguration(new ChannelConfigurationSnapshot(snapshot.channels()),
            configurationIds, false);
    }

    private void publishCommittedChannelConfiguration(ChannelConfigurationSnapshot committed,
                                                      Set<String> changedConfigurationIds, boolean autoStartOnly)
    {
        Map<String,Channel> current = new java.util.LinkedHashMap<>();
        mChannelModel.getChannels().forEach(channel -> current.put(channel.getConfigurationId(), channel));
        Map<String,Channel> saved = new java.util.LinkedHashMap<>();
        committed.channels().forEach(channel -> saved.put(channel.getConfigurationId(), channel));

        {
            for(String configurationId: changedConfigurationIds)
            {
                Channel live = current.get(configurationId);
                Channel replacement = saved.get(configurationId);
                if(live == null && replacement != null)
                {
                    mChannelModel.addChannel(replacement);
                }
                else if(live != null && replacement == null)
                {
                    mChannelModel.removeChannel(live);
                }
                else if(live != null)
                {
                    if(!mChannelProcessingManager.getProcessingChainsByConfiguration(configurationId, null).isEmpty())
                    {
                        if(!autoStartOnly)
                        {
                            throw new IllegalStateException(
                                "A channel started before its committed configuration could be published");
                        }
                        //Reordering autostart never requires a receiver restart and must retain the map key.
                        live.setAutoStart(replacement.getAutoStart());
                        live.setAutoStartOrder(replacement.getAutoStartOrder());
                        mChannelModel.receive(new ChannelEvent(live,
                            ChannelEvent.Event.NOTIFICATION_CONFIGURATION_CHANGE));
                    }
                    else
                    {
                        mChannelModel.replaceChannel(live, replacement);
                    }
                }
            }
        }
        mChannelConfigurationRevision.incrementAndGet();
        mChannelCatalogDirty = true;
    }

    public StreamingAdministrationService getStreamingAdministrationService()
    {
        return mStreamingAdministrationService;
    }

    public long getStreamingConfigurationRevision()
    {
        return mStreamingConfigurationRevision.get();
    }

    /** Saves a detached stream candidate before replacing only the affected sender. */
    public void commitAndPublishStreamingConfiguration(List<BroadcastConfiguration> proposed, String changedId)
    {
        executeConfigurationTask(() -> { commitAndPublishStreamingConfigurationOnOwner(proposed, changedId); return null; });
    }

    private void commitAndPublishStreamingConfigurationOnOwner(List<BroadcastConfiguration> proposed, String changedId)
    {
        if(mExternalConfigurationOperation)
            throw new ConfigurationPublicationException("Configuration saves are suspended until VCE restarts");
        List<BroadcastConfiguration> committed;
        try
        {
            committed = mConfigurationRepository.commitStreamingConfiguration(proposed);
        }
        catch(Exception exception)
        {
            throw new ConfigurationCommitException("Unable to commit streaming configuration", exception);
        }
        try
        {
            publishStreamingConfiguration(committed, changedId);
        }
        catch(RuntimeException publicationFailure)
        {
            try
            {
                publishStreamingConfiguration(mConfigurationRepository.load().broadcastConfigurations(), changedId);
            }
            catch(Exception recoveryFailure)
            {
                mExternalConfigurationOperation = true;
                throw new ConfigurationPublicationException(
                    "Streaming configuration saved but could not be applied; restart VCE", recoveryFailure);
            }
        }
    }

    private void publishStreamingConfiguration(List<BroadcastConfiguration> committed, String changedId)
    {
        BroadcastConfiguration previous = mBroadcastModel.getBroadcastConfiguration(changedId);
        if(previous != null) mBroadcastModel.removeBroadcastConfiguration(previous);
        committed.stream().filter(configuration -> changedId.equals(configuration.getConfigurationId()))
            .findFirst().ifPresent(mBroadcastModel::addBroadcastConfiguration);
        mStreamingConfigurationRevision.incrementAndGet();
        // Alias dialogs include the destination catalog; invalidate stale pickers on rename/add/delete.
        mAliasConfigurationRevision.incrementAndGet();
        mChannelCatalogDirty = true;
    }

    /** Creates an isolated Alias-only candidate; channel and broadcast state is deliberately out of scope. */
    public AliasConfigurationSnapshot createDetachedAliasConfigurationSnapshot()
    {
        return executeConfigurationTask(() -> createDetachedAliasConfigurationSnapshotOnOwner());
    }

    private AliasConfigurationSnapshot createDetachedAliasConfigurationSnapshotOnOwner()
    {
        return AliasConfigurationSnapshot.detachedCopyOf(mAliasModel.aliasListDefinitions(),
            mAliasModel.getAliases(), mScanListModel.configuration());
    }

    /** Database hook kept protected so failure-path tests can inject a failed commit. */
    protected AliasConfigurationSnapshot commitAliasConfiguration(AliasConfigurationSnapshot proposed,
        AliasConfigurationPublication publication)
    {
        if(mExternalConfigurationOperation)
        {
            throw new ConfigurationPublicationException(
                "Configuration saves are suspended until VCE restarts");
        }

        Objects.requireNonNull(proposed, "Proposed Alias configuration cannot be null");
        Objects.requireNonNull(publication, "Alias publication cannot be null");

        try
        {
            return mConfigurationRepository.commitAliasConfiguration(proposed);
        }
        catch(Exception exception)
        {
            throw new ConfigurationCommitException("Unable to commit alias configuration", exception);
        }
    }

    /**
     * Owns the complete persist-then-publish boundary. A failed database commit leaves active models untouched. If an
     * observer fails after commit, the committed Alias state is reloaded before this command returns.
     */
    public AliasConfigurationSnapshot commitAndPublishAliasConfiguration(AliasConfigurationSnapshot proposed, AliasConfigurationPublication publication)
    {
        return executeConfigurationTask(() -> commitAndPublishAliasConfigurationOnOwner(proposed, publication));
    }

    private AliasConfigurationSnapshot commitAndPublishAliasConfigurationOnOwner(AliasConfigurationSnapshot proposed,
        AliasConfigurationPublication publication)
    {
        AliasConfigurationPublication requested = Objects.requireNonNull(publication,
            "Alias publication cannot be null");
        Objects.requireNonNull(proposed, "Proposed Alias configuration cannot be null");
        if(mExternalConfigurationOperation)
        {
            throw new ConfigurationPublicationException("Configuration saves are suspended until VCE restarts");
        }
        AliasConfigurationSnapshot current = new AliasConfigurationSnapshot(mAliasModel.aliasListDefinitions(),
            mAliasModel.getAliases(), mScanListModel.configuration());
        if(AliasConfigurationEquality.sameConfiguration(current, proposed))
        {
            return current;
        }
        AliasConfigurationSnapshot committed = commitAliasConfiguration(proposed, requested);

        try
        {
            publishCommittedAliasConfiguration(committed, requested);
            return committed;
        }
        catch(RuntimeException | Error publicationFailure)
        {
            try
            {
                AliasConfigurationSnapshot reloaded = loadCommittedAliasConfiguration();
                Set<Long> allAliasIds = new HashSet<>(requested.changedAliasIds());
                mAliasModel.getAliases().stream().map(Alias::getId).forEach(allAliasIds::add);
                reloaded.aliases().stream().map(Alias::getId).forEach(allAliasIds::add);
                publishCommittedAliasConfiguration(reloaded,
                    new AliasConfigurationPublication(allAliasIds, true, true, true));
                mLog.error("Alias configuration committed, but initial publication failed; reloaded committed state",
                    publicationFailure);
                return reloaded;
            }
            catch(RuntimeException | Error recoveryFailure)
            {
                publicationFailure.addSuppressed(recoveryFailure);
                mExternalConfigurationOperation = true;
                throw new ConfigurationPublicationException(
                    "Alias configuration committed but could not be published; restart VCE", publicationFailure);
            }
        }
    }

    protected void publishCommittedAliasConfiguration(AliasConfigurationSnapshot committed,
                                                       AliasConfigurationPublication publication)
    {
        Objects.requireNonNull(committed, "Committed Alias configuration cannot be null");

        if(publication.scanListsChanged() && publication.scanListsFirst())
        {
            mScanListModel.replaceConfiguration(committed.scanLists());
        }

        if(publication.definitionsChanged() || !publication.changedAliasIds().isEmpty())
        {
            mAliasModel.publishCommittedConfiguration(committed.definitions(), committed.aliases(),
                publication.changedAliasIds(), publication.definitionsChanged());
        }

        if(publication.definitionsChanged())
        {
            refreshAliasListChannelNames();
        }

        if(publication.scanListsChanged() && !publication.scanListsFirst())
        {
            mScanListModel.replaceConfiguration(committed.scanLists());
        }

        mAliasConfigurationRevision.incrementAndGet();
        mChannelCatalogDirty = true;
    }

    /** Refreshes presentation metadata in place; active decoding chains keep their current channel and source. */
    private void refreshAliasListChannelNames()
    {
        for(Channel saved: mChannelModel.getChannels())
        {
            AliasListDefinition definition = mAliasModel.getAliasListDefinition(saved.getAliasListId());
            if(definition == null)
            {
                continue;
            }

            boolean savedNameChanged = !Objects.equals(saved.getAliasListName(), definition.getName());
            if(savedNameChanged)
            {
                mAliasListNameRefreshPending.add(saved.getConfigurationId());
                saved.setAliasListDefinition(definition);
            }
            boolean refreshPending = mAliasListNameRefreshPending.contains(saved.getConfigurationId());
            for(ProcessingChain chain: mChannelProcessingManager.getProcessingChainsByConfiguration(
                saved.getConfigurationId(), null))
            {
                Channel active = chain.getCurrentChannel();
                if(active != null && active.getAliasListId() == definition.getId() &&
                    (refreshPending || !Objects.equals(active.getAliasListName(), definition.getName())))
                {
                    mAliasListNameRefreshPending.add(saved.getConfigurationId());
                    active.setAliasListDefinition(definition);
                    chain.channelConfigurationChanged(new ChannelConfigurationChangeNotification(active));
                }
            }
            if(mAliasListNameRefreshPending.contains(saved.getConfigurationId()))
            {
                mChannelModel.receive(new ChannelEvent(saved, ChannelEvent.Event.NOTIFICATION_CONFIGURATION_CHANGE));
                mAliasListNameRefreshPending.remove(saved.getConfigurationId());
            }
        }
    }

    private AliasConfigurationSnapshot loadCommittedAliasConfiguration()
    {
        try
        {
            return mConfigurationRepository.loadAliasConfiguration();
        }
        catch(Exception exception)
        {
            throw new ConfigurationLoadException("Unable to reload committed Alias configuration", exception);
        }
    }

    public record AliasConfigurationPublication(Set<Long> changedAliasIds, boolean definitionsChanged,
                                                boolean scanListsChanged, boolean scanListsFirst)
    {
        public AliasConfigurationPublication
        {
            changedAliasIds = changedAliasIds != null ? Set.copyOf(changedAliasIds) : Set.of();
        }
    }

    public static final class ConfigurationCommitException extends RuntimeException
    {
        public ConfigurationCommitException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    public static final class ConfigurationPublicationException extends RuntimeException
    {
        private final String mCommittedConfigurationId;
        private final Long mCommittedAliasListId;

        public ConfigurationPublicationException(String message)
        {
            this(message, null);
        }

        public ConfigurationPublicationException(String message, Throwable cause)
        {
            super(message, cause);
            mCommittedConfigurationId = null;
            mCommittedAliasListId = null;
        }

        /** Definitive identity for a discovered channel whose database commit preceded publication failure. */
        public ConfigurationPublicationException(String message, Throwable cause, String configurationId, long aliasListId)
        {
            super(message, cause);
            mCommittedConfigurationId = Objects.requireNonNull(configurationId);
            mCommittedAliasListId = aliasListId;
        }

        public String committedConfigurationId() { return mCommittedConfigurationId; }
        public Long committedAliasListId() { return mCommittedAliasListId; }
    }

    private void clearModels()
    {
        mChannelProcessingManager.shutdown();
        mChannelModel.clear();
        mBroadcastModel.clear();
        mAliasModel.clear();
    }

    /**
     * Transfers persisted configuration state into system models.
     */
    private void transferStateToModels()
    {
        ConfigurationSnapshot snapshot = loadConfigurationSnapshot();

        clearModels();

        mScanListModel.replaceConfiguration(snapshot.scanListConfiguration());
        mAliasModel.replaceCommittedConfiguration(snapshot.aliasListDefinitions(), snapshot.aliases());
        mBroadcastModel.addBroadcastConfigurations(snapshot.broadcastConfigurations());
        mChannelModel.addChannels(snapshot.channels());
    }

    private ConfigurationSnapshot loadConfigurationSnapshot()
    {
        try
        {
            ConfigurationSnapshot loaded = mConfigurationRepository.loadForStartup();
            ConfigurationSnapshotValidator.validateForStartup(loaded);
            mLog.debug("Loaded configuration alias lists [{}], aliases [{}], scan lists [{}], channels [{}], " +
                    "and streams [{}] from SQLite [{}]", loaded.aliasListDefinitions().size(),
                loaded.aliases().size(), loaded.scanListConfiguration().scanLists().size(), loaded.channels().size(),
                loaded.broadcastConfigurations().size(), mConfigurationRepository.getDatabasePath());
            return loaded;
        }
        catch(Exception e)
        {
            mLog.error("Error loading configuration state from SQLite database [" +
                mConfigurationRepository.getDatabasePath() + "]", e);
            throw new ConfigurationLoadException("Unable to load validated configuration state from SQLite", e);
        }
    }

    private static final class ConfigurationLoadException extends RuntimeException
    {
        private ConfigurationLoadException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

}
