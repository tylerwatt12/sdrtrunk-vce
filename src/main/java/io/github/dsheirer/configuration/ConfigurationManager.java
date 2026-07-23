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
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.broadcast.BroadcastModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.ChannelModel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.controller.channel.map.ChannelMap;
import io.github.dsheirer.controller.channel.map.ChannelMapModel;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.configuration.IAliasListRefreshListener;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.module.log.EventLogManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.service.radioreference.RadioReference;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.util.ThreadPool;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.collections.ListChangeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages channel, stream, alias, and supporting configuration state.
 */
public class ConfigurationManager implements Listener<ChannelEvent>
{
    private static final Logger mLog = LoggerFactory.getLogger(ConfigurationManager.class);
    public static final int CONFIGURATION_CURRENT_VERSION = 4;

    private AliasModel mAliasModel;
    private ChannelMapModel mChannelMapModel = new ChannelMapModel();
    private IconModel mIconModel;

    private BroadcastModel mBroadcastModel;
    private ChannelModel mChannelModel;
    private ChannelProcessingManager mChannelProcessingManager;
    private TunerManager mTunerManager;
    private UserPreferences mUserPreferences;
    private RadioReference mRadioReference;
    private AliasDatabaseStore mAliasDatabaseStore;
    private ConfigurationDatabaseStore mConfigurationDatabaseStore;
    private AtomicBoolean mConfigurationSavePending = new AtomicBoolean();
    private AtomicBoolean mAliasesDirty = new AtomicBoolean();
    private AtomicBoolean mConfigurationStateDirty = new AtomicBoolean();
    private final Object mHeadlessWebConfigurationLock = new Object();
    private ScheduledFuture<?> mConfigurationSaveFuture;
    private boolean mConfigurationLoading = false;
    private List<IAliasListRefreshListener> mAliasListRefreshListeners = new ArrayList<>();

    /**
     * Manages channel configurations, channel maps, streams, and alias lists backed by the global SQLite database.
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
        mAliasDatabaseStore = new AliasDatabaseStore(SdrTrunkDatabasePath.getDatabasePath(userPreferences));
        mConfigurationDatabaseStore = new ConfigurationDatabaseStore(SdrTrunkDatabasePath.getDatabasePath(userPreferences));

        mBroadcastModel = new BroadcastModel(mAliasModel, mIconModel, userPreferences);
        mRadioReference = new RadioReference();

        mChannelModel = new ChannelModel(mAliasModel);
        mChannelProcessingManager = new ChannelProcessingManager(mChannelMapModel, eventLogManager, mTunerManager,
            mAliasModel, mUserPreferences);

        //Register the channel processing manager to receive global channel stop processing requests so that it can
        //respond to tuner shutdown (ie error) events
        MyEventBus.getGlobalEventBus().register(mChannelProcessingManager);

        mChannelModel.addListener(mChannelProcessingManager);
        mChannelProcessingManager.addChannelEventListener(mChannelModel);

        //Register for alias, channel and channel map events so that we can save configuration changes.
        mChannelModel.addListener(this);

        mAliasModel.aliasList().addListener((ListChangeListener.Change<? extends Alias> c) -> scheduleAliasSave());

        mChannelMapModel.getChannelMaps().addListener((ListChangeListener.Change<? extends ChannelMap> c) -> scheduleConfigurationSave());

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
     * Serializes headless web configuration-model access shared by the channel and Listen-list administrator
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
    private void prepareForAliasListRefresh()
    {
        for(IAliasListRefreshListener editor : mAliasListRefreshListeners)
        {
            editor.prepareForAliasListRefresh();
        }
    }

    /**
     * Refresh the alias list names after a rename or delete operation.
     */
    private void refreshAliasListNames()
    {
        //Do a refresh from the aliases
        getAliasModel().refreshAliasListNames();
        //Add in the alias list names referred to by the channels.
        getAliasModel().addAliasListNames(mChannelModel.getAliasListNames());
    }

    /**
     * Renames alias list references across aliases and channels from the old name to the new name.
     *
     * Note: this method should be invoked on the JavaFX thread since it will touch observable alias and channel lists.
     * @param oldName that is currently used by channels and aliases
     * @param newName to apply to channels and aliases
     */
    public void renameAliasList(String oldName, String newName)
    {
        prepareForAliasListRefresh();
        getAliasModel().renameAliasList(oldName, newName);
        getChannelModel().renameAliasList(oldName, newName);
        refreshAliasListNames();
    }

    /**
     * Deletes all aliases that have the alias list name and removes the alias list name from all channels.
     *
     * Note: this method should be invoked on the JavaFX thread since it will touch observable alias and channel lists.
     * @param aliasListName to delete
     */
    public void deleteAliasList(String aliasListName)
    {
        prepareForAliasListRefresh();
        getAliasModel().deleteAliasList(aliasListName);
        getChannelModel().deleteAliasList(aliasListName);
        refreshAliasListNames();
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
     * Channel map model managed by this configuration manager.
     */
    public ChannelMapModel getChannelMapModel()
    {
        return mChannelMapModel;
    }

    /**
     * Loads configuration state from the global SDRTrunk database.
     */
    public void init()
    {
        transferStateToModels(load());
    }

    /**
     * Completes any pending save before an external configuration operation accesses the SQLite database.
     * This method should be invoked on the JavaFX application thread.
     */
    public void flushConfiguration()
    {
        saveNow();
    }

    /**
     * Path to the SQLite database containing this configuration.
     */
    public Path getDatabasePath()
    {
        return mConfigurationDatabaseStore.getDatabasePath();
    }

    private void clearModels()
    {
        mConfigurationLoading = true;

        //Shutdown any running channels
        mChannelProcessingManager.shutdown();

        mChannelModel.clear();
        mChannelMapModel.clear();
        mBroadcastModel.clear();
        mAliasModel.clear();

        mConfigurationLoading = false;
    }

    private synchronized void saveNow()
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

        if(mConfigurationSavePending.getAndSet(false) || mAliasesDirty.get() || mConfigurationStateDirty.get())
        {
            save();
        }

        if(hasDirtyConfiguration())
        {
            scheduleSave(false, false);
        }
    }

    /**
     * Transfers persisted configuration state into system models.
     */
    private void transferStateToModels(ConfigurationState databaseState)
    {
        if(databaseState != null)
        {
            ConfigurationState configurationState = loadConfigurationState(databaseState);

            clearModels();

            mConfigurationLoading = true;

            mAliasModel.addAliases(loadAliases(databaseState));


            mBroadcastModel.addBroadcastConfigurations(configurationState.getBroadcastConfigurations());

            mChannelMapModel.addChannelMaps(configurationState.getChannelMaps());

            //Channel model has to be loaded last since it will auto-start channels that are enabled
            mChannelModel.addChannels(configurationState.getChannels());

            mConfigurationLoading = false;
        }
    }

    /**
     * Channel event listener method. Monitors channel events for changes that should be persisted to the database.
     */
    @Override
    public void receive(ChannelEvent event)
    {
        //Only save configuration changes for standard channels (not traffic channels).
        if(event.getChannel().getChannelType() == ChannelType.STANDARD)
        {
            switch(event.getEvent())
            {
                case NOTIFICATION_ADD:
                case NOTIFICATION_CONFIGURATION_CHANGE:
                case NOTIFICATION_DELETE:
                    scheduleConfigurationSave();
                    break;
            }
        }
    }

    /**
     * Saves the current runtime configuration state to the global SDRTrunk database.
     */
    private synchronized void save()
    {
        boolean saveAliases = mAliasesDirty.getAndSet(false);
        boolean saveConfigurationState = mConfigurationStateDirty.getAndSet(false);

        if(!saveAliases && !saveConfigurationState)
        {
            return;
        }

        boolean aliasesSavedToDatabase = !saveAliases || saveAliasesToDatabase();
        boolean configurationSavedToDatabase = !saveConfigurationState || saveConfigurationStateToDatabase();

        if(!aliasesSavedToDatabase)
        {
            mAliasesDirty.set(true);
        }

        if(!configurationSavedToDatabase)
        {
            mConfigurationStateDirty.set(true);
        }

        if(!aliasesSavedToDatabase || !configurationSavedToDatabase)
        {
            mLog.error("Configuration state was not fully saved to SQLite [{}]",
                mConfigurationDatabaseStore.getDatabasePath());
        }
    }

    private ConfigurationState loadConfigurationState(ConfigurationState databaseState)
    {
        try
        {
            if(mConfigurationDatabaseStore.isInitialized())
            {
                ConfigurationState loaded = mConfigurationDatabaseStore.loadConfigurationState();
                persistGeneratedConfigurationIds(loaded);
                mLog.debug("Loaded configuration channels [{}], channel maps [{}], and streams [{}] from SQLite [{}]",
                    loaded.getChannels().size(), loaded.getChannelMaps().size(),
                    loaded.getBroadcastConfigurations().size(), mConfigurationDatabaseStore.getDatabasePath());
                return loaded;
            }

            if(databaseState == null)
            {
                databaseState = new ConfigurationState();
            }

            mConfigurationDatabaseStore.replaceConfigurationState(databaseState);

            mLog.debug("Initialized configuration channels [{}], channel maps [{}], and streams [{}] in SQLite [{}]",
                databaseState.getChannels().size(), databaseState.getChannelMaps().size(),
                databaseState.getBroadcastConfigurations().size(), mConfigurationDatabaseStore.getDatabasePath());

            return mConfigurationDatabaseStore.loadConfigurationState();
        }
        catch(ChannelConfigurationIdentityPersistenceException e)
        {
            throw e;
        }
        catch(Exception e)
        {
            mLog.error("Error loading configuration state from SQLite database [" +
                mConfigurationDatabaseStore.getDatabasePath() + "]", e);
        }

        return databaseState;
    }

    /**
     * Persists identities generated while deserializing legacy channel JSON.  This one-time configuration-data upgrade
     * runs before channels are added to the model or auto-started and does not change the database schema.
     */
    private void persistGeneratedConfigurationIds(ConfigurationState state)
    {
        if(ensureUniqueChannelConfigurationIds(state) || ensureUniqueBroadcastConfigurationIds(state))
        {
            try
            {
                mConfigurationDatabaseStore.replaceConfigurationState(state);
                mLog.info("Assigned persistent internal identities to legacy saved configurations");
            }
            catch(Exception e)
            {
                mLog.error("Unable to persist generated internal configuration identities in SQLite [{}]",
                    mConfigurationDatabaseStore.getDatabasePath(), e);
                throw new ChannelConfigurationIdentityPersistenceException(
                    "Stable configuration identities could not be saved before startup", e);
            }
        }
    }

    static boolean ensureUniqueChannelConfigurationIds(ConfigurationState state)
    {
        if(state == null || state.getChannels() == null)
        {
            return false;
        }

        boolean persistenceRequired = false;
        Set<String> identities = new HashSet<>();

        for(Channel channel: state.getChannels())
        {
            if(channel == null)
            {
                continue;
            }

            String identity = channel.getConfigurationId();

            while(!identities.add(identity))
            {
                channel.regenerateConfigurationId();
                identity = channel.getConfigurationId();
            }

            persistenceRequired |= channel.isConfigurationIdPersistenceRequired();
        }

        return persistenceRequired;
    }

    static boolean ensureUniqueBroadcastConfigurationIds(ConfigurationState state)
    {
        if(state == null || state.getBroadcastConfigurations() == null)
        {
            return false;
        }

        boolean persistenceRequired = false;
        Set<String> identities = new HashSet<>();

        for(io.github.dsheirer.audio.broadcast.BroadcastConfiguration configuration:
            state.getBroadcastConfigurations())
        {
            if(configuration == null)
            {
                continue;
            }

            String identity = configuration.getConfigurationId();

            while(!identities.add(identity))
            {
                configuration.regenerateConfigurationId();
                identity = configuration.getConfigurationId();
            }

            persistenceRequired |= configuration.isConfigurationIdPersistenceRequired();
        }

        return persistenceRequired;
    }

    private static final class ChannelConfigurationIdentityPersistenceException extends RuntimeException
    {
        private ChannelConfigurationIdentityPersistenceException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    private List<Alias> loadAliases(ConfigurationState databaseState)
    {
        try
        {
            if(mAliasDatabaseStore.isInitialized())
            {
                List<Alias> aliases = mAliasDatabaseStore.loadAliases();
                mLog.debug("Loaded [{}] aliases from SQLite [{}]", aliases.size(),
                    mAliasDatabaseStore.getDatabasePath());
                return aliases;
            }

            List<Alias> aliases = databaseState != null && databaseState.getAliases() != null ?
                databaseState.getAliases() : new ArrayList<>();
            mAliasDatabaseStore.replaceAliases(aliases);
            mLog.debug("Initialized [{}] aliases in SQLite [{}]", aliases.size(), mAliasDatabaseStore.getDatabasePath());
            return aliases;
        }
        catch(Exception e)
        {
            mLog.error("Error loading aliases from SQLite database [" + mAliasDatabaseStore.getDatabasePath() + "]", e);
            return databaseState != null && databaseState.getAliases() != null ? databaseState.getAliases() :
                new ArrayList<>();
        }
    }

    private boolean saveAliasesToDatabase()
    {
        try
        {
            mAliasDatabaseStore.replaceAliases(new ArrayList<>(mAliasModel.getAliases()));
            return true;
        }
        catch(Exception e)
        {
            mLog.error("Error saving aliases to SQLite database [" + mAliasDatabaseStore.getDatabasePath() + "]", e);
            return false;
        }
    }

    private boolean saveConfigurationStateToDatabase()
    {
        try
        {
            ConfigurationState databaseState = new ConfigurationState();
            databaseState.setBroadcastConfigurations(new ArrayList<>(mBroadcastModel.getBroadcastConfigurations()));
            databaseState.setChannels(new ArrayList<>(mChannelModel.getChannels()));
            databaseState.setChannelMaps(new ArrayList<>(mChannelMapModel.getChannelMaps()));
            databaseState.setVersion(CONFIGURATION_CURRENT_VERSION);
            mConfigurationDatabaseStore.replaceConfigurationState(databaseState);
            return true;
        }
        catch(Exception e)
        {
            mLog.error("Error saving configuration state to SQLite database [" +
                mConfigurationDatabaseStore.getDatabasePath() + "]", e);
            return false;
        }
    }

    /**
     * Creates an empty configuration state shell; data is loaded from SQLite by the configuration database store.
     */
    public ConfigurationState load()
    {
        mLog.debug("Loading configuration state from SQLite [{}]", mConfigurationDatabaseStore.getDatabasePath());
        return new ConfigurationState();
    }

    /**
     * Schedules an alias save task. Subsequent calls to this method are batched until the save event occurs.
     */
    public void scheduleAliasSave()
    {
        scheduleSave(true, false);
    }

    /**
     * Schedules a configuration state save task. Subsequent calls to this method are batched until the save event occurs.
     */
    public void scheduleConfigurationSave()
    {
        scheduleSave(false, true);
    }

    private void scheduleSave(boolean aliasesDirty, boolean configurationStateDirty)
    {
        if(mConfigurationLoading)
        {
            return;
        }

        if(aliasesDirty)
        {
            mAliasesDirty.set(true);
        }

        if(configurationStateDirty)
        {
            mConfigurationStateDirty.set(true);
        }

        if(mConfigurationSavePending.compareAndSet(false, true))
        {
            mConfigurationSaveFuture = ThreadPool.SCHEDULED.schedule(new ConfigurationSaveTask(), 2, TimeUnit.SECONDS);
        }
    }

    private boolean hasDirtyConfiguration()
    {
        return mAliasesDirty.get() || mConfigurationStateDirty.get();
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
                    scheduleSave(false, false);
                }
            }
        }
    }
}
