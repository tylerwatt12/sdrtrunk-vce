/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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

package io.github.dsheirer.source.tuner.configuration;

import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.source.tuner.TunerFactory;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.IDiscoveredTunerStatusListener;
import io.github.dsheirer.source.tuner.manager.TunerStatus;
import io.github.dsheirer.util.ThreadPool;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages settings and configurations for all tuner types.
 */
public class TunerConfigurationManager implements IDiscoveredTunerStatusListener
{
    private static final Logger mLog = LoggerFactory.getLogger(TunerConfigurationManager.class);
    private final ApplicationSettingsStore mSettingsStore;
    private final Executor mFrequencyUpdateExecutor;
    private final Map<TunerFrequencyKey,Long> mPendingFrequencyUpdates = new ConcurrentHashMap<>();
    private final AtomicBoolean mFrequencyUpdateWorkerScheduled = new AtomicBoolean();
    private List<DisabledTuner> mDisabledTunerList = new ArrayList<>();
    private List<TunerConfiguration> mTunerConfigurations = new ArrayList<>();
    private Lock mLock = new ReentrantLock();

    /**
     * Constructs an instance and loads the save configuration state.
     *
     */
    public TunerConfigurationManager()
    {
        this(new ApplicationSettingsStore(SdrTrunkDatabasePath.getDatabasePath()), ThreadPool.CACHED);
    }

    /**
     * Constructs an instance with injected persistence dependencies for deterministic tests.
     */
    TunerConfigurationManager(ApplicationSettingsStore settingsStore, Executor frequencyUpdateExecutor)
    {
        mSettingsStore = Objects.requireNonNull(settingsStore);
        mFrequencyUpdateExecutor = Objects.requireNonNull(frequencyUpdateExecutor);
        load();
    }

    /**
     * Loads settings from the persisted tuner state file
     */
    private void load()
    {
        try
        {
            TunerSettings settings = mSettingsStore.load(ApplicationSettingsStore.TUNER_SETTINGS, TunerSettings.class)
                .orElseGet(TunerSettings::new);
            mDisabledTunerList.addAll(settings.getDisabledTuners());
            mTunerConfigurations.addAll(settings.getTunerConfigurations());

            if(settings.getIgnoredEntryCount() > 0)
            {
                mLog.warn("Removed [{}] unsupported or malformed tuner settings entries",
                    settings.getIgnoredEntryCount());
                saveConfigurations();
            }
        }
        catch(Exception e)
        {
            mLog.error("Error loading tuner settings from SQLite [{}]", mSettingsStore.getDatabasePath(), e);
        }
    }

    public void saveConfigurations()
    {
        TunerSettings settings = new TunerSettings();

        mLock.lock();

        try
        {
            settings.setDisabledTuners(new ArrayList<>(mDisabledTunerList));
            settings.setTunerConfigurations(new ArrayList<>(mTunerConfigurations));
        }
        finally
        {
            mLock.unlock();
        }

        try
        {
            mSettingsStore.saveLater(ApplicationSettingsStore.TUNER_SETTINGS, settings);
        }
        catch(IOException e)
        {
            mLog.error("Error serializing tuner settings for SQLite [{}]", mSettingsStore.getDatabasePath(), e);
        }
    }

    /**
     * Monitors discovered tuner enabled status and applies configurations or updates disable state of tuners.
     * @param discoveredTuner that has a status change.
     * @param previous tuner status
     * @param current tuner status
     */
    @Override
    public void tunerStatusUpdated(DiscoveredTuner discoveredTuner, TunerStatus previous, TunerStatus current)
    {
        if(current == TunerStatus.DISABLED)
        {
            if(!discoveredTuner.hasTunerConfiguration())
            {
                findTunerConfiguration(discoveredTuner.getId()).ifPresent(discoveredTuner::setTunerConfiguration);
            }

            addDisabledTuner(discoveredTuner);
        }
        else if(current == TunerStatus.ENABLED)
        {
            removeDisabledTuner(discoveredTuner);

            if(discoveredTuner.hasTuner())
            {
                TunerType tunerType = discoveredTuner.getTuner().getTunerType();

                if(tunerType != TunerType.RECORDING)
                {
                    TunerConfiguration tunerConfiguration = getTunerConfiguration(tunerType, discoveredTuner.getId());

                    if(tunerConfiguration != null &&
                            tunerConfiguration != discoveredTuner.getTunerConfiguration())
                    {
                        discoveredTuner.setTunerConfiguration(tunerConfiguration);
                    }

                    saveConfigurations();
                }
            }
        }
    }

    /**
     * Updates the tuner configuration with the current tuner PPM setting so that the value can be stored across
     * sessions.
     * @param discoveredTuner that has an updated PPM value.
     */
    public void updateTunerPPM(DiscoveredTuner discoveredTuner)
    {
        if(discoveredTuner != null)
        {
            TunerType tunerType = discoveredTuner.getTuner().getTunerType();

            if(tunerType != TunerType.RECORDING)
            {
                TunerConfiguration tunerConfiguration = getTunerConfiguration(tunerType, discoveredTuner.getId());

                if(tunerConfiguration != null)
                {
                    tunerConfiguration.setFrequencyCorrection(discoveredTuner.getTuner().getTunerController().getFrequencyCorrection());
                    saveConfigurations();
                }
            }
        }
    }

    /**
     * Queues a center-frequency update after an allocator-driven tuner retune.  The caller supplies only immutable
     * values so that configuration lookup, mutation, snapshot creation, and serialization all occur off the decoder
     * path.  Repeated updates for the same tuner are coalesced to the latest frequency.
     *
     * @param tunerType type of tuner that moved
     * @param uniqueID stable tuner identifier
     * @param frequency new center frequency
     */
    public void updateTunerFrequency(TunerType tunerType, String uniqueID, long frequency)
    {
        if(tunerType == null || tunerType == TunerType.RECORDING || uniqueID == null || uniqueID.isBlank())
        {
            return;
        }

        mPendingFrequencyUpdates.put(new TunerFrequencyKey(tunerType, uniqueID.toLowerCase(Locale.ROOT)), frequency);
        scheduleFrequencyUpdateWorker();
    }

    /**
     * Schedules at most one drain worker.  Updates remain bounded to one map entry per physical tuner instead of one
     * queued task per retune.
     */
    private void scheduleFrequencyUpdateWorker()
    {
        if(mFrequencyUpdateWorkerScheduled.compareAndSet(false, true))
        {
            try
            {
                mFrequencyUpdateExecutor.execute(this::processPendingFrequencyUpdates);
            }
            catch(RuntimeException ignored)
            {
                //Persistence is best effort during shutdown.  Leave the coalesced values in place so that a later
                //update can retry, but do not log or run persistence work on the decoder caller.
                mFrequencyUpdateWorkerScheduled.set(false);
            }
        }
    }

    /**
     * Drains immutable frequency updates on the persistence worker.  Conditional removal preserves a newer value
     * that races with a worker snapshot, and the ownership handoff closes the empty-check/worker-exit race.
     */
    private void processPendingFrequencyUpdates()
    {
        try
        {
            while(true)
            {
                Map<TunerFrequencyKey,Long> updates = takePendingFrequencyUpdates();

                if(!updates.isEmpty())
                {
                    applyFrequencyUpdates(updates);
                    continue;
                }

                mFrequencyUpdateWorkerScheduled.set(false);

                //An update that arrived before ownership was released saw the worker as scheduled.  Reclaim
                //ownership and continue draining it here.  If a later arrival already scheduled a new worker, this
                //CAS fails and that worker owns the pending values.
                if(mPendingFrequencyUpdates.isEmpty() ||
                    !mFrequencyUpdateWorkerScheduled.compareAndSet(false, true))
                {
                    return;
                }
            }
        }
        catch(RuntimeException e)
        {
            mLog.error("Error persisting tuner center-frequency updates", e);
            mFrequencyUpdateWorkerScheduled.set(false);

            if(!mPendingFrequencyUpdates.isEmpty())
            {
                scheduleFrequencyUpdateWorker();
            }
        }
    }

    /**
     * Takes a coalesced snapshot without removing values that changed after the snapshot was made.
     */
    private Map<TunerFrequencyKey,Long> takePendingFrequencyUpdates()
    {
        Map<TunerFrequencyKey,Long> snapshot = Map.copyOf(mPendingFrequencyUpdates);
        Map<TunerFrequencyKey,Long> updates = new HashMap<>();

        for(Map.Entry<TunerFrequencyKey,Long> entry: snapshot.entrySet())
        {
            if(mPendingFrequencyUpdates.remove(entry.getKey(), entry.getValue()))
            {
                updates.put(entry.getKey(), entry.getValue());
            }
        }

        return updates;
    }

    /**
     * Applies one coalesced batch and serializes a single settings snapshot when at least one saved tuner changed.
     */
    private void applyFrequencyUpdates(Map<TunerFrequencyKey,Long> updates)
    {
        boolean changed = false;
        mLock.lock();

        try
        {
            for(Map.Entry<TunerFrequencyKey,Long> entry: updates.entrySet())
            {
                TunerFrequencyKey key = entry.getKey();
                Optional<TunerConfiguration> configuration = mTunerConfigurations.stream()
                    .filter(candidate -> candidate.getTunerType() == key.tunerType() &&
                        candidate.getUniqueID() != null &&
                        candidate.getUniqueID().equalsIgnoreCase(key.normalizedUniqueID()))
                    .findFirst();

                if(configuration.isPresent() && configuration.get().getFrequency() != entry.getValue())
                {
                    configuration.get().setFrequency(entry.getValue());
                    changed = true;
                }
            }
        }
        finally
        {
            mLock.unlock();
        }

        if(changed)
        {
            saveConfigurations();
        }
    }

    private record TunerFrequencyKey(TunerType tunerType, String normalizedUniqueID)
    {
    }

    /**
     * Adds the discovered tuner to the list of disabled tuners
     */
    private void addDisabledTuner(DiscoveredTuner discoveredTuner)
    {
        if(!isDisabled(discoveredTuner))
        {
            mLock.lock();

            try
            {
                mDisabledTunerList.add(new DisabledTuner(discoveredTuner.getTunerClass(), discoveredTuner.getId()));
            }
            finally
            {
                mLock.unlock();
            }

            saveConfigurations();
        }
    }

    /**
     * Removes the tuner from the disabled tuners list
     * @param discoveredTuner to remove
     */
    private void removeDisabledTuner(DiscoveredTuner discoveredTuner)
    {
        mLock.lock();

        try
        {
            mDisabledTunerList.removeIf(tuner -> tuner.matches(discoveredTuner));
        }
        finally
        {
            mLock.unlock();
        }

        saveConfigurations();
    }

    /**
     * Indicates if the discovered tuner is disabled.  This method should only be used to determine the disabled state
     * of a tuner when it is added to the system for use, such as tuner discovery at application startup, or for
     * USB tuner hotplug device add notifications.
     *
     * @param discoveredTuner to check for disabled status.
     * @return true if the tuner is supposed to be disabled.
     */
    public boolean isDisabled(DiscoveredTuner discoveredTuner)
    {
        return findDisabledTuner(discoveredTuner) != null;
    }

    /**
     * Finds the disabled tuner that matches the discovered tuner.
     * @param discoveredTuner to search for
     * @return disabled tuner instance or null if the discovered tuner is not currently disabled.
     */
    private DisabledTuner findDisabledTuner(DiscoveredTuner discoveredTuner)
    {
        DisabledTuner found = null;

        mLock.lock();

        try
        {
            for(DisabledTuner disabledTuner: mDisabledTunerList)
            {
                if(disabledTuner.matches(discoveredTuner))
                {
                    found = disabledTuner;
                    break;
                }
            }
        }
        finally
        {
            mLock.unlock();
        }

        return found;
    }

    /**
     * Adds the tuner configuration if one doesn't exist that matches the tuner type and unique id.
     * @param tunerConfiguration to add
     */
    public void addTunerConfiguration(TunerConfiguration tunerConfiguration)
    {
        boolean added = false;
        mLock.lock();

        try
        {
            if(mTunerConfigurations.stream().noneMatch(config ->
                config.getTunerType().equals(tunerConfiguration.getTunerType()) &&
                    config.getUniqueID().equalsIgnoreCase(tunerConfiguration.getUniqueID())))
            {
                mTunerConfigurations.add(tunerConfiguration);
                added = true;
            }
        }
        finally
        {
            mLock.unlock();
        }

        if(added)
        {
            saveConfigurations();
        }
    }

    /**
     * Removes the tuner configuration from this manager.
     * @param tunerConfiguration to remove
     */
    public void removeTunerConfiguration(TunerConfiguration tunerConfiguration)
    {
        mLock.lock();

        try
        {
            mTunerConfigurations.remove(tunerConfiguration);
        }
        finally
        {
            mLock.unlock();
        }

        saveConfigurations();
    }

    /**
     * Provides an existing or creates a new tuner configuration for the specified tuner type and unique ID value.
     *
     */
    public TunerConfiguration getTunerConfiguration(TunerType type, String uniqueID )
    {
        TunerConfiguration tunerConfiguration;
        boolean created = false;
        mLock.lock();

        try
        {
            tunerConfiguration = mTunerConfigurations.stream()
                .filter(config -> config.getTunerType().equals(type) &&
                    config.getUniqueID().equalsIgnoreCase(uniqueID))
                .findFirst()
                .orElse(null);

            if(tunerConfiguration == null)
            {
                tunerConfiguration = TunerFactory.getTunerConfiguration(type, uniqueID);
                mTunerConfigurations.add(tunerConfiguration);
                created = true;
            }
        }
        finally
        {
            mLock.unlock();
        }

        if(created)
        {
            saveConfigurations();
        }

        return tunerConfiguration;
    }

    /**
     * Finds an existing configuration using the discovered tuner's stable identifier.  This lookup is used for a
     * tuner that starts disabled, before its hardware-specific tuner type can be queried.
     *
     * @param uniqueID stable discovered tuner identifier
     * @return matching saved configuration, if one exists
     */
    Optional<TunerConfiguration> findTunerConfiguration(String uniqueID)
    {
        mLock.lock();

        try
        {
            return mTunerConfigurations.stream()
                    .filter(config -> config.getUniqueID() != null && config.getUniqueID().equalsIgnoreCase(uniqueID))
                    .findFirst();
        }
        finally
        {
            mLock.unlock();
        }
    }

    /**
     * Get all tuner configurations that match the specified tuner type.
     * @param tunerType to match
     * @return list of all configurations.
     */
    public List<TunerConfiguration> getTunerConfigurations(TunerType tunerType)
    {
        mLock.lock();

        try
        {
            return mTunerConfigurations.stream().filter(tunerConfiguration -> tunerConfiguration.getTunerType()
                .equals(tunerType)).toList();
        }
        finally
        {
            mLock.unlock();
        }
    }

}
