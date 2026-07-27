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
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasMatchRegistry;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.broadcast.BroadcastModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.ChannelModel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.database.configuration.ConfigurationSnapshotDatabaseStore;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private AliasModel mAliasModel;
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
    private AtomicBoolean mConfigurationDirty = new AtomicBoolean();
    private final Object mHeadlessWebConfigurationLock = new Object();
    private ScheduledFuture<?> mConfigurationSaveFuture;
    private boolean mConfigurationLoading = false;
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
        mAliasDatabaseStore = new AliasDatabaseStore(SdrTrunkDatabasePath.getDatabasePath(userPreferences));
        mConfigurationDatabaseStore = new ConfigurationDatabaseStore(SdrTrunkDatabasePath.getDatabasePath(userPreferences));

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

        //Register for alias and channel events so that we can save configuration changes.
        mChannelModel.addListener(this);

        mAliasModel.aliasList().addListener((ListChangeListener.Change<? extends Alias> c) -> scheduleAliasSave());
        mAliasModel.aliasListDefinitions().addListener(
            (ListChangeListener.Change<? extends AliasListDefinition> c) -> scheduleAliasSave());

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
     * Loads configuration state from the global SDRTrunk database.
     */
    public void init()
    {
        transferStateToModels();
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

        if(mConfigurationSavePending.getAndSet(false) || mConfigurationDirty.get())
        {
            save();
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
        ConfigurationState configurationState = loadConfigurationState();
        AliasSnapshot aliasSnapshot = loadAliases();
        validateAliasListAssignments(configurationState, aliasSnapshot.definitions());

        clearModels();

        mConfigurationLoading = true;

        try
        {
            mAliasModel.setAliasListDefinitions(aliasSnapshot.definitions());
            mAliasModel.addAliases(aliasSnapshot.aliases());
            mBroadcastModel.addBroadcastConfigurations(configurationState.getBroadcastConfigurations());

            //Channel model has to be loaded last since it will auto-start channels that are enabled
            mChannelModel.addChannels(configurationState.getChannels());
        }
        finally
        {
            mConfigurationLoading = false;
        }
    }

    /**
     * Normal startup validates persisted channel/list references without repairing or synthesizing definitions.
     */
    static void validateAliasListAssignments(ConfigurationState state, List<AliasListDefinition> definitions)
    {
        Map<String,AliasListDefinition> definitionsByName = new HashMap<>();

        if(definitions != null)
        {
            for(AliasListDefinition definition: definitions)
            {
                if(definition != null && definition.getName() != null)
                {
                    definitionsByName.put(definition.getName().trim().toLowerCase(Locale.US), definition);
                }
            }
        }

        if(state == null || state.getChannels() == null)
        {
            return;
        }

        for(Channel channel: state.getChannels())
        {
            String aliasListName = channel != null ? channel.getAliasListName() : null;

            if(aliasListName == null || aliasListName.isBlank())
            {
                continue;
            }

            AliasListDefinition definition =
                definitionsByName.get(aliasListName.trim().toLowerCase(Locale.US));

            if(definition == null || !aliasListName.equals(definition.getName()) ||
                channel.getSystem() == null || definition.getSystemName() == null ||
                !channel.getSystem().trim().equalsIgnoreCase(definition.getSystemName().trim()) ||
                channel.getDecodeConfiguration() == null ||
                !AliasMatchRegistry.isChannelCompatible(definition,
                    channel.getDecodeConfiguration().getDecoderType()))
            {
                throw new ConfigurationStateValidationException("Channel [" +
                    (channel != null ? channel.getName() : null) + "] references incompatible alias list [" +
                    aliasListName + "]");
            }
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
        if(!mConfigurationDirty.getAndSet(false))
        {
            return;
        }

        if(!saveConfigurationSnapshotToDatabase())
        {
            mConfigurationDirty.set(true);
        }
    }

    private ConfigurationState loadConfigurationState()
    {
        try
        {
            ConfigurationState loaded = mConfigurationDatabaseStore.loadConfigurationState();
            validateConfigurationIdentities(loaded);
            mLog.debug("Loaded configuration channels [{}] and streams [{}] from SQLite [{}]",
                loaded.getChannels().size(), loaded.getBroadcastConfigurations().size(),
                mConfigurationDatabaseStore.getDatabasePath());
            return loaded;
        }
        catch(Exception e)
        {
            mLog.error("Error loading configuration state from SQLite database [" +
                mConfigurationDatabaseStore.getDatabasePath() + "]", e);
            throw new ConfigurationLoadException("Unable to load validated configuration state from SQLite", e);
        }
    }

    /**
     * Normal startup is validation-only. Missing, malformed, or duplicate persisted identities must be repaired by
     * the staged Application Migrator (or assigned while a legacy import is still being constructed).
     */
    static void validateConfigurationIdentities(ConfigurationState state)
    {
        Set<String> channelIdentities = new HashSet<>();

        if(state != null && state.getChannels() != null)
        {
            for(Channel channel: state.getChannels())
            {
                if(channel != null)
                {
                    String identity = channel.getConfigurationId();

                    if(channel.isConfigurationIdPersistenceRequired() || !channelIdentities.add(identity))
                    {
                        throw new ConfigurationIdentityValidationException(
                            "Saved channel configuration identities require the Application Migrator");
                    }
                }
            }
        }

        Set<String> providerIdentities = new HashSet<>();
        if(state != null && state.getBroadcastConfigurations() != null)
        {
            for(io.github.dsheirer.audio.broadcast.BroadcastConfiguration configuration:
                state.getBroadcastConfigurations())
            {
                if(configuration != null)
                {
                    String identity = configuration.getConfigurationId();

                    if(configuration.isConfigurationIdPersistenceRequired() ||
                        !providerIdentities.add(identity))
                    {
                        throw new ConfigurationIdentityValidationException(
                            "Saved broadcast configuration identities require the Application Migrator");
                    }
                }
            }
        }
    }

    private static final class ConfigurationIdentityValidationException extends RuntimeException
    {
        private ConfigurationIdentityValidationException(String message)
        {
            super(message);
        }
    }

    private static final class ConfigurationStateValidationException extends RuntimeException
    {
        private ConfigurationStateValidationException(String message)
        {
            super(message);
        }
    }

    private AliasSnapshot loadAliases()
    {
        try
        {
            List<AliasListDefinition> definitions = mAliasDatabaseStore.loadAliasListDefinitions();
            List<Alias> aliases = mAliasDatabaseStore.loadAliases(definitions);
            mLog.debug("Loaded [{}] aliases from SQLite [{}]", aliases.size(),
                mAliasDatabaseStore.getDatabasePath());
            return new AliasSnapshot(definitions, aliases);
        }
        catch(Exception e)
        {
            mLog.error("Error loading aliases from SQLite database [" + mAliasDatabaseStore.getDatabasePath() + "]", e);
            throw new ConfigurationLoadException("Unable to load validated alias configuration from SQLite", e);
        }
    }

    private boolean saveConfigurationSnapshotToDatabase()
    {
        try
        {
            ConfigurationState databaseState = new ConfigurationState();
            databaseState.setAliases(new ArrayList<>(mAliasModel.getAliases()));
            databaseState.setAliasListDefinitions(new ArrayList<>(mAliasModel.aliasListDefinitions()));
            databaseState.setBroadcastConfigurations(new ArrayList<>(mBroadcastModel.getBroadcastConfigurations()));
            databaseState.setChannels(new ArrayList<>(mChannelModel.getChannels()));
            validateAliasListAssignments(databaseState, databaseState.getAliasListDefinitions());
            new ConfigurationSnapshotDatabaseStore(mConfigurationDatabaseStore.getDatabasePath())
                .replace(databaseState);
            return true;
        }
        catch(Exception e)
        {
            mLog.error("Error saving complete configuration snapshot to SQLite database [" +
                mConfigurationDatabaseStore.getDatabasePath() + "]", e);
            return false;
        }
    }

    private record AliasSnapshot(List<AliasListDefinition> definitions, List<Alias> aliases)
    {
    }

    private static final class ConfigurationLoadException extends RuntimeException
    {
        private ConfigurationLoadException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    /**
     * Schedules an alias save task. Subsequent calls to this method are batched until the save event occurs.
     */
    public void scheduleAliasSave()
    {
        scheduleSave();
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
