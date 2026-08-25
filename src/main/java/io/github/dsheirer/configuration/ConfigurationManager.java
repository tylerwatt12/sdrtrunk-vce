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
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.BroadcastModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.ChannelModel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.configuration.ConfigurationRepository;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.configuration.IAliasListRefreshListener;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.module.log.EventLogManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.service.radioreference.RadioReference;
import io.github.dsheirer.scanlist.ScanListModel;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.util.ThreadPool;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages channel, stream, alias, and supporting configuration state.
 */
public class ConfigurationManager implements Listener<ChannelEvent>
{
    private static final Logger mLog = LoggerFactory.getLogger(ConfigurationManager.class);
    private AliasModel mAliasModel;
    private IconModel mIconModel;

    private BroadcastModel mBroadcastModel;
    private ChannelModel mChannelModel;
    private ChannelProcessingManager mChannelProcessingManager;
    private TunerManager mTunerManager;
    private UserPreferences mUserPreferences;
    private RadioReference mRadioReference;
    private final ConfigurationRepository mConfigurationRepository;
    private final AliasAdministrationService mAliasAdministrationService;
    private final ScanListModel mScanListModel;
    private AtomicBoolean mConfigurationSavePending = new AtomicBoolean();
    private AtomicBoolean mConfigurationDirty = new AtomicBoolean();
    private final AtomicLong mAliasConfigurationRevision = new AtomicLong();
    private final Object mHeadlessWebConfigurationLock = new Object();
    private ScheduledFuture<?> mConfigurationSaveFuture;
    private boolean mConfigurationLoading = false;
    private volatile boolean mInitialized = false;
    private volatile boolean mExternalConfigurationOperation = false;
    private List<IAliasListRefreshListener> mAliasListRefreshListeners = new ArrayList<>();

    /**
     * Manages channel configurations, streams, and alias lists backed by the global SQLite database.
     *
     * Monitors configuration changes to automatically save them after they occur.
     *
     * @param userPreferences for user settings
     * @param tunerManager for access to tuner model
     * @param aliasModel for aliases
     * @param eventLogManager for event logging
     * @param iconModel for icons
     */
    public ConfigurationManager(UserPreferences userPreferences, TunerManager tunerManager, AliasModel aliasModel,
                           EventLogManager eventLogManager, IconModel iconModel)
    {
        mUserPreferences = userPreferences;
        mTunerManager = tunerManager;
        mAliasModel = aliasModel;
        mIconModel = iconModel;
        mConfigurationRepository =
            new ConfigurationRepository(SdrTrunkDatabasePath.getDatabasePath(userPreferences));
        mScanListModel = new ScanListModel();
        mAliasAdministrationService = new AliasAdministrationService(this);

        mBroadcastModel = new BroadcastModel(mAliasModel, mIconModel, userPreferences);
        mRadioReference = new RadioReference();

        mChannelModel = new ChannelModel();
        mChannelProcessingManager = new ChannelProcessingManager(eventLogManager, mTunerManager, mAliasModel,
            mUserPreferences);

        //Register the channel processing manager to receive global channel stop processing requests so that it can
        //respond to tuner shutdown (ie error) events
        MyEventBus.getGlobalEventBus().register(mChannelProcessingManager);

        mChannelModel.addListener(mChannelProcessingManager);
        mChannelProcessingManager.addChannelEventListener(mChannelModel);

        //Channel and broadcast editors retain their delayed saver. Alias administration commits detached candidates
        //through its own transaction boundary before publishing them.
        mChannelModel.addListener(this);

        mBroadcastModel.addListener(broadcastEvent -> {
            switch(broadcastEvent.getEvent())
            {
                case CONFIGURATION_ADD:
                case CONFIGURATION_CHANGE:
                case CONFIGURATION_DELETE:
                    scheduleConfigurationSave();
                    break;
                default:
                    //Do nothing
                    break;
            }
        });
    }

    /**
     * Serializes headless web configuration-model access shared by the channel and scan-list administrator
     * services.  Desktop builds instead use the JavaFX application thread.  This lock is never acquired by tuner,
     * sample, decoder, recorder, broadcaster, or browser-audio delivery paths.
     */
    public void runHeadlessWebConfigurationTask(Runnable task)
    {
        Objects.requireNonNull(task, "Configuration task cannot be null");

        synchronized(mHeadlessWebConfigurationLock)
        {
            task.run();
        }
    }

    /**
     * Adds the listener to be notified when an alias list refresh operation is about to take place.  The listener
     * should clear any selected or editing item to prepare for the list of alias list names to be updated so that
     * alias list combo boxes won't trigger an editor modified event when the list contents changes.
     * @param listener for alias list refresh event.
     */
    public void addAliasListRefreshListener(IAliasListRefreshListener listener)
    {
        mAliasListRefreshListeners.add(listener);
    }

    /**
     * Notifies listeners that the alias list will be refreshed
     */
    public void prepareForAliasListRefresh()
    {
        for(IAliasListRefreshListener editor : mAliasListRefreshListeners)
        {
            editor.prepareForAliasListRefresh();
        }
    }

    /**
     * Channel model managed by this configuration manager.
     */
    public ChannelModel getChannelModel()
    {
        return mChannelModel;
    }

    /**
     * Channel processing manager
     */
    public ChannelProcessingManager getChannelProcessingManager()
    {
        return mChannelProcessingManager;
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

    /**
     * Icon manager
     */
    public IconModel getIconModel()
    {
        return mIconModel;
    }

    /**
     * Radio Reference service interface
     */
    public RadioReference getRadioReference()
    {
        return mRadioReference;
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
        transferStateToModels();
        mInitialized = true;
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

    /**
     * Completes any pending save before an external configuration operation accesses the SQLite database.
     * This method should be invoked on the JavaFX application thread.
     */
    public void flushConfiguration()
    {
        if(mExternalConfigurationOperation)
        {
            throw new ConfigurationPublicationException(
                "Configuration saves are suspended until SDRTrunk restarts");
        }

        saveNow();

        if(hasDirtyConfiguration())
        {
            throw new IllegalStateException("Unable to save the current configuration");
        }
    }

    /** Serializes one detached Alias command against delayed channel and broadcast saves. */
    public synchronized <T> T applyConfigurationMutation(Supplier<T> task)
    {
        return Objects.requireNonNull(task, "Configuration mutation cannot be null").get();
    }

    /**
     * Applies one externally prepared configuration snapshot without allowing an ordinary delayed save to interleave.
     *
     * <p>This method must run on the JavaFX application thread so configuration editors cannot mutate the live models
     * while the operation is in progress. Running channels are stopped, pending changes are saved, the external
     * operation atomically commits, and the live models are reloaded before delayed saves are enabled again. The
     * supplied operation must leave the database unchanged when it throws.</p>
     */
    public synchronized <T> T applyExternalConfigurationSnapshot(Callable<T> operation) throws Exception
    {
        Objects.requireNonNull(operation, "External configuration operation cannot be null");

        if(!javafx.application.Platform.isFxApplicationThread())
        {
            throw new IllegalStateException("External configuration changes must run on the JavaFX application thread");
        }

        if(mExternalConfigurationOperation)
        {
            throw new IllegalStateException("Another external configuration operation is already running");
        }

        mExternalConfigurationOperation = true;
        boolean databaseOperationCompleted = false;
        boolean configurationReady = false;

        try
        {
            mChannelProcessingManager.shutdown();
            saveNow(true);

            if(hasDirtyConfiguration())
            {
                throw new IllegalStateException(
                    "Unable to save the current configuration before applying the external configuration");
            }

            T result = operation.call();
            databaseOperationCompleted = true;
            transferStateToModels();
            mAliasConfigurationRevision.incrementAndGet();
            mConfigurationDirty.set(false);
            configurationReady = true;
            return result;
        }
        catch(Exception | Error e)
        {
            if(!databaseOperationCompleted)
            {
                configurationReady = true;
            }

            if(databaseOperationCompleted && !configurationReady)
            {
                throw new ExternalConfigurationReloadException(
                    "The configuration was committed but could not be reloaded", e);
            }

            throw e;
        }
        finally
        {
            if(configurationReady)
            {
                mExternalConfigurationOperation = false;

                if(hasDirtyConfiguration())
                {
                    scheduleSave();
                }
            }
        }
    }

    public static class ExternalConfigurationReloadException extends RuntimeException
    {
        public ExternalConfigurationReloadException(String message, Throwable cause)
        {
            super(message, cause);
        }
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

    /** Creates an isolated Alias-only candidate; channel and broadcast state is deliberately out of scope. */
    public synchronized AliasConfigurationSnapshot createDetachedAliasConfigurationSnapshot()
    {
        return AliasConfigurationSnapshot.detachedCopyOf(mAliasModel.aliasListDefinitions(),
            mAliasModel.getAliases(), mScanListModel.configuration());
    }

    /** Database hook kept protected so failure-path tests can inject a failed commit. */
    protected synchronized AliasConfigurationSnapshot commitAliasConfiguration(AliasConfigurationSnapshot proposed,
        AliasConfigurationPublication publication, BroadcastConfigurationRename broadcastRename)
    {
        if(mExternalConfigurationOperation)
        {
            throw new ConfigurationPublicationException(
                "Configuration saves are suspended until SDRTrunk restarts");
        }

        Objects.requireNonNull(proposed, "Proposed Alias configuration cannot be null");
        Objects.requireNonNull(publication, "Alias publication cannot be null");

        try
        {
            List<BroadcastConfiguration> broadcastConfigurations = broadcastRename != null ?
                new ArrayList<>(mBroadcastModel.getBroadcastConfigurations()) : null;
            return broadcastConfigurations != null ?
                mConfigurationRepository.commitAliasConfigurationWithBroadcastRename(proposed,
                publication.clearedChannelAliasListNames(), broadcastConfigurations,
                broadcastRename.previousName(), broadcastRename.updatedName()) :
                mConfigurationRepository.commitAliasConfiguration(proposed,
                    publication.clearedChannelAliasListNames());
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
    public synchronized AliasConfigurationSnapshot commitAndPublishAliasConfiguration(AliasConfigurationSnapshot proposed,
        AliasConfigurationPublication publication, Runnable beforePublication,
        BroadcastConfigurationRename broadcastRename)
    {
        AliasConfigurationPublication requested = Objects.requireNonNull(publication,
            "Alias publication cannot be null");
        AliasConfigurationSnapshot committed = commitAliasConfiguration(proposed, requested, broadcastRename);

        try
        {
            publishBroadcastConfigurationRename(broadcastRename);
            if(beforePublication != null)
            {
                beforePublication.run();
            }
            publishCommittedAliasConfiguration(committed, requested);
            return committed;
        }
        catch(RuntimeException | Error publicationFailure)
        {
            try
            {
                AliasConfigurationSnapshot reloaded = loadCommittedAliasConfiguration();
                publishBroadcastConfigurationRename(broadcastRename);
                Set<Long> allAliasIds = new HashSet<>(requested.changedAliasIds());
                mAliasModel.getAliases().stream().map(Alias::getId).forEach(allAliasIds::add);
                reloaded.aliases().stream().map(Alias::getId).forEach(allAliasIds::add);
                publishCommittedAliasConfiguration(reloaded,
                    new AliasConfigurationPublication(allAliasIds, true, true, true,
                        requested.clearedChannelAliasListNames()));
                mLog.error("Alias configuration committed, but initial publication failed; reloaded committed state",
                    publicationFailure);
                return reloaded;
            }
            catch(RuntimeException | Error recoveryFailure)
            {
                publicationFailure.addSuppressed(recoveryFailure);
                mExternalConfigurationOperation = true;
                throw new ConfigurationPublicationException(
                    "Alias configuration committed but could not be published; restart SDRTrunk", publicationFailure);
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

        if(publication.scanListsChanged() && !publication.scanListsFirst())
        {
            mScanListModel.replaceConfiguration(committed.scanLists());
        }

        if(!publication.clearedChannelAliasListNames().isEmpty())
        {
            for(Channel channel: mChannelModel.getChannels())
            {
                if(channel.getAliasListName() != null && publication.clearedChannelAliasListNames().stream()
                    .anyMatch(name -> name.equalsIgnoreCase(channel.getAliasListName())))
                {
                    channel.setAliasListName(null);
                }
            }
        }

        mAliasConfigurationRevision.incrementAndGet();
    }

    private void publishBroadcastConfigurationRename(BroadcastConfigurationRename rename)
    {
        if(rename == null)
        {
            return;
        }

        List<BroadcastConfiguration> previousMatches = mBroadcastModel.getBroadcastConfigurations().stream()
            .filter(configuration -> rename.previousName().equals(configuration.getName())).toList();
        if(previousMatches.size() == 1)
        {
            previousMatches.getFirst().setName(rename.updatedName());
            return;
        }

        long updatedMatches = mBroadcastModel.getBroadcastConfigurations().stream()
            .filter(configuration -> rename.updatedName().equals(configuration.getName())).count();
        if(previousMatches.isEmpty() && updatedMatches == 1)
        {
            return;
        }

        throw new IllegalStateException("Unable to publish committed broadcast stream rename");
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
                                                boolean scanListsChanged, boolean scanListsFirst,
                                                Set<String> clearedChannelAliasListNames)
    {
        public AliasConfigurationPublication
        {
            changedAliasIds = changedAliasIds != null ? Set.copyOf(changedAliasIds) : Set.of();
            clearedChannelAliasListNames = clearedChannelAliasListNames != null ?
                Set.copyOf(clearedChannelAliasListNames) : Set.of();
        }
    }

    /** One stream-name change that must commit and publish with its Alias references. */
    public record BroadcastConfigurationRename(String previousName, String updatedName)
    {
        public BroadcastConfigurationRename
        {
            if(previousName == null || previousName.isBlank() || updatedName == null || updatedName.isBlank())
            {
                throw new IllegalArgumentException("Broadcast rename names must be nonblank");
            }
            previousName = previousName.strip();
            updatedName = updatedName.strip();
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
        public ConfigurationPublicationException(String message)
        {
            super(message);
        }

        public ConfigurationPublicationException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    private void clearModels()
    {
        mConfigurationLoading = true;
        try
        {
            //Shutdown any running channels
            mChannelProcessingManager.shutdown();
            mChannelModel.clear();
            mBroadcastModel.clear();
            mAliasModel.clear();
        }
        finally
        {
            mConfigurationLoading = false;
        }
    }

    private synchronized void saveNow()
    {
        saveNow(false);
    }

    private void saveNow(boolean allowDuringExternalOperation)
    {
        //Complete any pending configuration save.
        if(mConfigurationSaveFuture != null)
        {
            try
            {
                mConfigurationSaveFuture.cancel(true);
            }
            catch(Exception e)
            {
                mLog.error("Error trying to cancel pending configuration save");
            }

            mConfigurationSaveFuture = null;
        }

        if(mConfigurationSavePending.getAndSet(false) || mConfigurationDirty.get())
        {
            save(allowDuringExternalOperation);
        }

        if(hasDirtyConfiguration())
        {
            scheduleSave();
        }
    }

    /**
     * Transfers persisted configuration state into system models.
     */
    private void transferStateToModels()
    {
        ConfigurationSnapshot snapshot = loadConfigurationSnapshot();

        clearModels();

        mConfigurationLoading = true;

        try
        {
            mScanListModel.replaceConfiguration(snapshot.scanListConfiguration());
            mAliasModel.replaceCommittedConfiguration(snapshot.aliasListDefinitions(), snapshot.aliases());
            mBroadcastModel.addBroadcastConfigurations(snapshot.broadcastConfigurations());

            //Channel model has to be loaded last since it will auto-start channels that are enabled
            mChannelModel.addChannels(snapshot.channels());
        }
        finally
        {
            mConfigurationLoading = false;
        }
    }

    /**
     * Channel event listener method. Monitors channel events for changes that should be persisted to the database.
     */
    @Override
    public void receive(ChannelEvent event)
    {
        if(mConfigurationLoading)
        {
            return;
        }

        //Only save configuration changes for standard channels (not traffic channels).
        if(event.getChannel().getChannelType() == ChannelType.STANDARD)
        {
            switch(event.getEvent())
            {
                case NOTIFICATION_ADD:
                case NOTIFICATION_CONFIGURATION_CHANGE:
                case NOTIFICATION_DELETE:
                    mAliasConfigurationRevision.incrementAndGet();
                    scheduleConfigurationSave();
                    break;
            }
        }
    }

    /** Saves delayed channel and broadcast edits without rewriting Alias-owned rows. */
    private synchronized void save()
    {
        save(false);
    }

    private void save(boolean allowDuringExternalOperation)
    {
        if(mExternalConfigurationOperation && !allowDuringExternalOperation)
        {
            return;
        }

        if(!mConfigurationDirty.getAndSet(false))
        {
            return;
        }

        if(!saveChannelAndBroadcastConfigurationToDatabase())
        {
            mConfigurationDirty.set(true);
        }
    }

    private ConfigurationSnapshot loadConfigurationSnapshot()
    {
        try
        {
            ConfigurationSnapshot loaded = mConfigurationRepository.load();
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

    boolean saveChannelAndBroadcastConfigurationToDatabase()
    {
        try
        {
            mConfigurationRepository.replaceChannelAndBroadcastConfiguration(
                new ArrayList<>(mChannelModel.getChannels()),
                new ArrayList<>(mBroadcastModel.getBroadcastConfigurations()));
            return true;
        }
        catch(Exception e)
        {
            mLog.error("Error saving channel and broadcast configuration to SQLite database [" +
                mConfigurationRepository.getDatabasePath() + "]", e);
            return false;
        }
    }

    private static final class ConfigurationLoadException extends RuntimeException
    {
        private ConfigurationLoadException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    /**
     * Schedules a configuration state save task. Subsequent calls to this method are batched until the save event occurs.
     */
    public void scheduleConfigurationSave()
    {
        scheduleSave();
    }

    private void scheduleSave()
    {
        if(mConfigurationLoading)
        {
            return;
        }

        mConfigurationDirty.set(true);

        if(mExternalConfigurationOperation)
        {
            return;
        }

        if(mConfigurationSavePending.compareAndSet(false, true))
        {
            mConfigurationSaveFuture = ThreadPool.SCHEDULED.schedule(new ConfigurationSaveTask(), 2, TimeUnit.SECONDS);
        }
    }

    private boolean hasDirtyConfiguration()
    {
        return mConfigurationDirty.get();
    }

    /**
     * Resets the configuration save pending flag to false and saves the current configuration state.
     */
    public class ConfigurationSaveTask implements Runnable
    {
        @Override
        public void run()
        {
            try
            {
                save();
            }
            finally
            {
                mConfigurationSaveFuture = null;
                mConfigurationSavePending.set(false);

                if(hasDirtyConfiguration())
                {
                    scheduleSave();
                }
            }
        }
    }
}
