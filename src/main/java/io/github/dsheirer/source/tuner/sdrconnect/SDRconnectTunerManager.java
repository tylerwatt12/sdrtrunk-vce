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

package io.github.dsheirer.source.tuner.sdrconnect;

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.source.ChannelizerType;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.configuration.TunerConfigurationManager;
import io.github.dsheirer.source.tuner.manager.IDiscoveredTunerStatusListener;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.ui.DiscoveredTunerModel;
import io.github.dsheirer.util.ThreadPool;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates SDRconnect tuner discovery and startup while delegating endpoint/bootstrap handling and device
 * assignment policy to SDRconnect-specific helpers.
 */
public class SDRconnectTunerManager
{
    private static final Logger mLog = LoggerFactory.getLogger(SDRconnectTunerManager.class);

    private final UserPreferences mUserPreferences;
    private final DiscoveredTunerModel mDiscoveredTunerModel;
    private final TunerConfigurationManager mTunerConfigurationManager;
    private final IDiscoveredTunerStatusListener mTunerStatusListener;
    private final SDRconnectEndpointMonitor mEndpointMonitor;
    private final SDRconnectDeviceAssignmentResolver mDeviceAssignmentResolver;

    public SDRconnectTunerManager(UserPreferences userPreferences, DiscoveredTunerModel discoveredTunerModel,
                                  TunerConfigurationManager tunerConfigurationManager,
                                  IDiscoveredTunerStatusListener tunerStatusListener)
    {
        mUserPreferences = userPreferences;
        mDiscoveredTunerModel = discoveredTunerModel;
        mTunerConfigurationManager = tunerConfigurationManager;
        mTunerStatusListener = tunerStatusListener;
        mEndpointMonitor = new SDRconnectEndpointMonitor(userPreferences, mLog);
        mDeviceAssignmentResolver = new SDRconnectDeviceAssignmentResolver();
    }

    public void discoverConfiguredTuners()
    {
        ChannelizerType channelizerType = getChannelizerType();
        List<TunerConfiguration> tunerConfigurations = mTunerConfigurationManager.getTunerConfigurations(TunerType.SDRCONNECT);
        Map<String, SDRconnectEndpointReadiness> readinessByEndpoint =
            mEndpointMonitor.prepareConfiguredEndpoints(tunerConfigurations);
        Map<String, String> runtimeDeviceAssignments =
            mDeviceAssignmentResolver.resolveConfiguredDeviceAssignments(tunerConfigurations, readinessByEndpoint);

        if(!tunerConfigurations.isEmpty())
        {
            mLog.info("Discovered [{}] SDRconnect tuners from saved configurations", tunerConfigurations.size());
        }

        for(TunerConfiguration tunerConfiguration: tunerConfigurations)
        {
            if(tunerConfiguration instanceof SDRconnectTunerConfiguration sdrconnectConfig)
            {
                DiscoveredSDRconnectTuner discoveredTuner =
                    createConfiguredDiscoveredTuner(sdrconnectConfig, channelizerType, runtimeDeviceAssignments);
                boolean disabled = mTunerConfigurationManager.isDisabled(discoveredTuner);
                boolean available = isEndpointAvailable(readinessByEndpoint, sdrconnectConfig);

                updateConfiguredTunerAvailability(discoveredTuner, sdrconnectConfig, disabled, available);

                mLog.info("SDRconnect Tuner Added: {}", discoveredTuner);
                mDiscoveredTunerModel.addDiscoveredTuner(discoveredTuner);

                handleConfiguredTunerStartup(discoveredTuner, sdrconnectConfig, disabled, available);
            }
        }
    }

    public void autoDiscoverTuners()
    {
        List<TunerConfiguration> existing = mTunerConfigurationManager.getTunerConfigurations(TunerType.SDRCONNECT);

        if(!existing.isEmpty())
        {
            return;
        }

        String defaultHost = "127.0.0.1";
        int defaultPort = 5454;

        if(mEndpointMonitor.probe(defaultHost, defaultPort))
        {
            mLog.info("Auto-discovered SDRconnect at {}:{}", defaultHost, defaultPort);

            ChannelizerType channelizerType = getChannelizerType();
            SDRconnectTunerConfiguration config = SDRconnectTunerConfiguration.create(defaultHost, defaultPort);
            mTunerConfigurationManager.addTunerConfiguration(config);

            DiscoveredSDRconnectTuner discoveredTuner = new DiscoveredSDRconnectTuner(defaultHost, defaultPort,
                channelizerType);
            discoveredTuner.setTunerConfiguration(config);
            discoveredTuner.addTunerStatusListener(mTunerStatusListener);

            mLog.info("SDRconnect auto-discovered and enabled: {}", discoveredTuner);
            mDiscoveredTunerModel.addDiscoveredTuner(discoveredTuner);

            ThreadPool.CACHED.execute(() -> startConfiguredTuner(discoveredTuner));
        }
    }

    public void stop()
    {
        mEndpointMonitor.stop();
    }

    private ChannelizerType getChannelizerType()
    {
        return mUserPreferences.getTunerPreference().getChannelizerType();
    }

    private DiscoveredSDRconnectTuner createConfiguredDiscoveredTuner(SDRconnectTunerConfiguration sdrconnectConfig,
                                                                      ChannelizerType channelizerType,
                                                                      Map<String, String> runtimeDeviceAssignments)
    {
        DiscoveredSDRconnectTuner discoveredTuner =
            new DiscoveredSDRconnectTuner(sdrconnectConfig.getHost(), sdrconnectConfig.getPort(),
                sdrconnectConfig.getDeviceName(), channelizerType);
        discoveredTuner.setTunerConfiguration(sdrconnectConfig);
        discoveredTuner.setRuntimeDeviceName(runtimeDeviceAssignments.get(sdrconnectConfig.getUniqueID()));
        discoveredTuner.addTunerStatusListener(mTunerStatusListener);
        return discoveredTuner;
    }

    private boolean isEndpointAvailable(Map<String, SDRconnectEndpointReadiness> readinessByEndpoint,
                                        SDRconnectTunerConfiguration sdrconnectConfig)
    {
        SDRconnectEndpointReadiness readiness =
            readinessByEndpoint.get(getEndpointKey(sdrconnectConfig.getHost(), sdrconnectConfig.getPort()));
        return readiness != null && readiness.isReady();
    }

    private void updateConfiguredTunerAvailability(DiscoveredSDRconnectTuner discoveredTuner,
                                                   SDRconnectTunerConfiguration sdrconnectConfig,
                                                   boolean disabled, boolean available)
    {
        if(disabled)
        {
            mLog.info("SDRconnect configured at {}:{} is disabled", sdrconnectConfig.getHost(),
                sdrconnectConfig.getPort());
            discoveredTuner.setEnabled(false);
        }
        else if(available)
        {
            mLog.info("SDRconnect detected at {}:{} - auto-starting tuner", sdrconnectConfig.getHost(),
                sdrconnectConfig.getPort());
        }
        else
        {
            mLog.warn("SDRconnect not available at {}:{} - tuner remains enabled and can be restarted from the UI",
                sdrconnectConfig.getHost(), sdrconnectConfig.getPort());
        }
    }

    private void handleConfiguredTunerStartup(DiscoveredSDRconnectTuner discoveredTuner,
                                              SDRconnectTunerConfiguration sdrconnectConfig,
                                              boolean disabled, boolean available)
    {
        if(!disabled && available)
        {
            ThreadPool.CACHED.execute(() -> startConfiguredTuner(discoveredTuner));
        }
        else if(!disabled)
        {
            discoveredTuner.setErrorMessage("SDRconnect is not available at " + sdrconnectConfig.getHost() +
                ":" + sdrconnectConfig.getPort());
        }
    }

    private void startConfiguredTuner(DiscoveredSDRconnectTuner discoveredTuner)
    {
        discoveredTuner.start();

        if(discoveredTuner.hasTuner())
        {
            mDiscoveredTunerModel.tunerBecameAvailable(discoveredTuner);
        }
    }

    private String getEndpointKey(String host, int port)
    {
        return host + ":" + port;
    }
}
