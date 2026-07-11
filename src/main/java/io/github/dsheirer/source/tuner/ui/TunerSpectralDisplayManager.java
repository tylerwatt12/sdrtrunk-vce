/*
 * *****************************************************************************
 * Copyright (C) 2014-2023 Dennis Sheirer
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
package io.github.dsheirer.source.tuner.ui;

import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.settings.SettingsManager;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerEvent;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.spectrum.SpectralDisplayPanel;
import io.github.dsheirer.spectrum.SpectrumFrame;
import io.github.dsheirer.util.SwingUtils;
import io.github.dsheirer.util.ThreadPool;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

/**
 * Spectral display manager for displaying tuner spectral content.
 */
public class TunerSpectralDisplayManager implements Listener<TunerEvent>
{

    private SpectralDisplayPanel mSpectralDisplayPanel;
    private ConfigurationManager mConfigurationManager;
    private SettingsManager mSettingsManager;
    private DiscoveredTunerModel mDiscoveredTunerModel;
    private UserPreferences mUserPreferences;
    private final AtomicReference<ScheduledFuture<?>> mInitialSelectionFuture = new AtomicReference<>();
    private volatile boolean mInitialSelectionComplete;

    /**
     * Constructs an instance
     * @param panel to manage
     * @param configurationManager for channel updates
     * @param settingsManager for settings
     * @param discoveredTunerModel to access tuners
     */
    public TunerSpectralDisplayManager(SpectralDisplayPanel panel, ConfigurationManager configurationManager,
                                       SettingsManager settingsManager, DiscoveredTunerModel discoveredTunerModel,
                                       UserPreferences userPreferences)
    {
        mSpectralDisplayPanel = panel;
        mConfigurationManager = configurationManager;
        mSettingsManager = settingsManager;
        mDiscoveredTunerModel = discoveredTunerModel;
        mUserPreferences = userPreferences;
    }

    /**
     * Shows the first available tuner from the discovered tuner model
     */
    public Tuner showFirstTuner()
    {
        if(isSpectralDisplayEnabled())
        {
            Tuner tuner = getFirstAvailableTuner();

            if(tuner != null)
            {
                mInitialSelectionComplete = true;
                mSpectralDisplayPanel.showTuner(tuner);
                return tuner;
            }
        }

        return null;
    }

    private Tuner getFirstAvailableTuner()
    {
        List<DiscoveredTuner> availableTuners = mDiscoveredTunerModel.getAvailableTuners();

        for(DiscoveredTuner discoveredTuner: availableTuners)
        {
            if(discoveredTuner.hasTuner())
            {
                return discoveredTuner.getTuner();
            }
        }

        return null;
    }

    /**
     * Retries initial tuner selection for a limited time so that delayed tuner startup does not leave the main
     * spectral display empty at application launch.
     */
    public void retryShowFirstTuner(long interval, TimeUnit unit, int maxAttempts)
    {
        if(mInitialSelectionComplete || !isSpectralDisplayEnabled())
        {
            return;
        }

        synchronized(mInitialSelectionFuture)
        {
            ScheduledFuture<?> existing = mInitialSelectionFuture.get();

            if(existing != null && !existing.isDone())
            {
                return;
            }
        }

        final int[] attempts = new int[] {0};
        ScheduledFuture<?> scheduledFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(() -> {
            if(mInitialSelectionComplete || attempts[0] >= maxAttempts || !isSpectralDisplayEnabled())
            {
                cancelInitialSelectionRetry();
                return;
            }

            attempts[0]++;
            Tuner tuner = getFirstAvailableTuner();

            if(tuner != null)
            {
                mDiscoveredTunerModel.broadcast(new TunerEvent(tuner, TunerEvent.Event.REQUEST_MAIN_SPECTRAL_DISPLAY));
                cancelInitialSelectionRetry();
            }
        }, interval, interval, unit);

        synchronized(mInitialSelectionFuture)
        {
            ScheduledFuture<?> existing = mInitialSelectionFuture.get();

            if(existing != null && !existing.isDone())
            {
                scheduledFuture.cancel(false);
            }
            else
            {
                mInitialSelectionFuture.set(scheduledFuture);
            }
        }
    }

    private boolean isSpectralDisplayEnabled()
    {
        return mUserPreferences.getSpectrumPreference().isDisplayEnabled();
    }

    private void cancelInitialSelectionRetry()
    {
        ScheduledFuture<?> existing = mInitialSelectionFuture.getAndSet(null);

        if(existing != null)
        {
            existing.cancel(false);
        }
    }

    @Override
    public void receive(TunerEvent event)
    {
        switch(event.getEvent())
        {
            case REQUEST_CLEAR_MAIN_SPECTRAL_DISPLAY:
                mInitialSelectionComplete = false;
                SwingUtils.run(() -> mSpectralDisplayPanel.clearTuner());
                break;
            case REQUEST_MAIN_SPECTRAL_DISPLAY:
                mInitialSelectionComplete = true;
                if(isSpectralDisplayEnabled())
                {
                    SwingUtils.run(() -> mSpectralDisplayPanel.showTuner(event.getTuner()));
                }
                break;
            case REQUEST_NEW_SPECTRAL_DISPLAY:
                final SpectrumFrame frame = new SpectrumFrame(mConfigurationManager, mSettingsManager,
                    mDiscoveredTunerModel, event.getTuner(), mUserPreferences);
                SwingUtils.run(() -> frame.setVisible(true));
                break;
            case NOTIFICATION_ERROR_STATE:
            case NOTIFICATION_SHUTTING_DOWN:
                if(event.getTuner().equals(mSpectralDisplayPanel.getTuner()))
                {
                    SwingUtils.run(() ->
                    {
                        mInitialSelectionComplete = false;
                        mSpectralDisplayPanel.clearTuner();
                        ThreadPool.SCHEDULED.schedule(() -> SwingUtils.run(this::showFirstTuner), 1, TimeUnit.SECONDS);
                    });
                }
                break;
            default:
                break;
        }
    }
}
