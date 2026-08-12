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

import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerEvent;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.spectrum.SpectralDisplayPanel;
import io.github.dsheirer.util.SwingUtils;
import io.github.dsheirer.util.ThreadPool;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spectral display manager for displaying tuner spectral content.
 */
public class TunerSpectralDisplayManager implements Listener<TunerEvent>, AutoCloseable
{

    private final SpectralDisplayPanel mSpectralDisplayPanel;
    private final DiscoveredTunerModel mDiscoveredTunerModel;
    private final AtomicBoolean mDisposed = new AtomicBoolean();

    /**
     * Constructs an instance
     * @param panel to manage
     * @param discoveredTunerModel to access tuners
     */
    public TunerSpectralDisplayManager(SpectralDisplayPanel panel, DiscoveredTunerModel discoveredTunerModel)
    {
        mSpectralDisplayPanel = panel;
        mDiscoveredTunerModel = discoveredTunerModel;
    }

    /**
     * Shows the first available tuner from the discovered tuner model
     */
    public Tuner showFirstTuner()
    {
        if(!mDisposed.get())
        {
            Tuner tuner = getFirstAvailableTuner();

            if(tuner != null)
            {
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

    @Override
    public void receive(TunerEvent event)
    {
        if(mDisposed.get())
        {
            return;
        }

        switch(event.getEvent())
        {
            case REQUEST_CLEAR_MAIN_SPECTRAL_DISPLAY:
                SwingUtils.run(() -> mSpectralDisplayPanel.clearTuner());
                break;
            case REQUEST_MAIN_SPECTRAL_DISPLAY:
                SwingUtils.run(() -> mSpectralDisplayPanel.showTuner(event.getTuner()));
                break;
            case NOTIFICATION_ERROR_STATE:
            case NOTIFICATION_SHUTTING_DOWN:
                if(event.getTuner().equals(mSpectralDisplayPanel.getTuner()))
                {
                    SwingUtils.run(() ->
                    {
                        mSpectralDisplayPanel.clearTuner();
                        ThreadPool.SCHEDULED.schedule(() -> SwingUtils.run(this::showFirstTuner), 1, TimeUnit.SECONDS);
                    });
                }
                break;
            default:
                break;
        }
    }

    /**
     * Stops delayed tuner selection and prevents queued tuner events from touching a disposed display.
     */
    public void dispose()
    {
        mDisposed.set(true);
    }

    @Override
    public void close()
    {
        dispose();
    }
}
