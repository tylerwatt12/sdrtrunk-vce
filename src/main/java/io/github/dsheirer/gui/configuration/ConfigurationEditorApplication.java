/*
 * *****************************************************************************
 * Copyright (C) 2014-2022 Dennis Sheirer
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

package io.github.dsheirer.gui.configuration;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.gui.JavaFxWindowManager;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.module.log.EventLogManager;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Simple application wrapper for testing the configuration editor individually.
 */
public class ConfigurationEditorApplication extends Application
{
    private Parent mConfigurationEditor;
    private TunerManager mTunerManager;
    private UserPreferences mUserPreferences = new UserPreferences();
    private ConfigurationManager mConfigurationManager;

    public ConfigurationEditorApplication()
    {
        AliasModel aliasModel = new AliasModel();
        EventLogManager eventLogManager = new EventLogManager(aliasModel, mUserPreferences);
        mTunerManager = new TunerManager(mUserPreferences);
        mTunerManager.start();
        mConfigurationManager = new ConfigurationManager(mUserPreferences, mTunerManager, aliasModel, eventLogManager, new IconModel());

        mConfigurationManager.init();
        new JavaFxWindowManager(mUserPreferences, mTunerManager, mConfigurationManager);
    }

    @Override
    public void start(Stage primaryStage) throws Exception
    {
        primaryStage.setTitle("Configuration Editor");
        Scene scene = new Scene(getConfigurationEditor(), 1000, 750);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private Parent getConfigurationEditor()
    {
        if(mConfigurationEditor == null)
        {
            mConfigurationEditor = new ConfigurationEditor(mConfigurationManager, mTunerManager, mUserPreferences);
        }

        return mConfigurationEditor;
    }

    public static void main(String[] args)
    {
        launch(args);
    }
}
