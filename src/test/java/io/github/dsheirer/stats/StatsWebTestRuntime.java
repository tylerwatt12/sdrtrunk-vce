/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.module.decode.event.DecodeEventViewService;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.stats.activity.ReceiverActivityService;

/** Test-owned receiver composition; production HTTP never constructs a partial receiver graph. */
final class StatsWebTestRuntime
{
    private StatsWebTestRuntime() { }

    static StatsWebServerService start(UserPreferences preferences)
    {
        ReceiverActivityService activity = new ReceiverActivityService(preferences);
        ConfigurationManager configuration = new ConfigurationManager(preferences, new TunerManager(preferences),
            new AliasModel(), null, activity::receiveMaintenanceRequest);
        configuration.init();
        return start(preferences, configuration, activity);
    }

    static StatsWebServerService start(UserPreferences preferences, ConfigurationManager configuration)
    {
        return start(preferences, configuration, new ReceiverActivityService(preferences));
    }

    private static StatsWebServerService start(UserPreferences preferences, ConfigurationManager configuration,
                                               ReceiverActivityService activity)
    {
        DecodeEventViewService events = new DecodeEventViewService(configuration.getChannelProcessingManager(),
            configuration.getAliasModel());
        ReceiverWebServices features = new ReceiverWebServices(preferences, configuration, activity, events, null);
        StatsWebServerService http = new StatsWebServerService(preferences, features)
        {
            private boolean cleaned;
            @Override public synchronized void close()
            {
                if(cleaned) return;
                super.close();
                features.quiesceAdministration();
                configuration.quiesceAdministration();
                configuration.getChannelProcessingManager().close();
                features.close();
                events.close();
                activity.dispose();
                configuration.getBroadcastModel().close();
                configuration.close();
                configuration.getRadioReferenceDirectoryService().close();
                configuration.getTunerManager().stop();
                cleaned = true;
            }
        };
        try
        {
            features.start();
            http.start();
            return http;
        }
        catch(RuntimeException exception)
        {
            http.close();
            throw exception;
        }
    }

    static ReceiverWebServices services(StatsWebServerService http) throws ReflectiveOperationException
    {
        var field = StatsWebServerService.class.getDeclaredField("mServices");
        field.setAccessible(true);
        return (ReceiverWebServices)field.get(http);
    }
}
