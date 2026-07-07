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
package io.github.dsheirer.controller;

import com.jidesoft.swing.JideTabbedPane;
import io.github.dsheirer.audio.playback.AudioPanel;
import io.github.dsheirer.audio.playback.AudioPlaybackManager;
import io.github.dsheirer.channel.metadata.NowPlayingPanel;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.map.MapPanel;
import io.github.dsheirer.map.MapService;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.settings.SettingsManager;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.ui.TunerViewPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.util.function.Consumer;
import net.miginfocom.swing.MigLayout;

import javax.swing.JPanel;

public class ControllerPanel extends JPanel
{
    private static final long serialVersionUID = 1L;

    private AudioPanel mAudioPanel;
    private NowPlayingPanel mNowPlayingPanel;
    private MapPanel mMapPanel;
    private TunerViewPanel mTunerManagerPanel;
    private JideTabbedPane mTabbedPane;
    private ConfigurationManager mConfigurationManager;
    private boolean mSystemsVisible;

    public ControllerPanel(ConfigurationManager configurationManager, AudioPlaybackManager audioPlaybackManager,
                           IconModel iconModel, MapService mapService, SettingsManager settingsManager,
                           TunerManager tunerManager, UserPreferences userPreferences, boolean systemsVisible,
                           boolean detailTabsVisible,
                           Consumer<Boolean> detailTabsVisibilityListener)
    {
        mConfigurationManager = configurationManager;
        mSystemsVisible = systemsVisible;
        mAudioPanel = new AudioPanel(iconModel, userPreferences, settingsManager, audioPlaybackManager,
            configurationManager.getAliasModel());
        mNowPlayingPanel = new NowPlayingPanel(configurationManager, iconModel, userPreferences, settingsManager,
            detailTabsVisible, detailTabsVisibilityListener);
        mMapPanel = new MapPanel(mapService, configurationManager.getAliasModel(), iconModel, settingsManager);
        mTunerManagerPanel = new TunerViewPanel(tunerManager, userPreferences);

        init();
    }

    /**
     * Now playing panel.
     */
    public NowPlayingPanel getNowPlayingPanel()
    {
        return mNowPlayingPanel;
    }

    public void setSystemsVisible(boolean visible)
    {
        if(mSystemsVisible == visible)
        {
            return;
        }

        if(visible)
        {
            mTabbedPane.insertTab("Systems", null, mNowPlayingPanel, null, 0);
            mNowPlayingPanel.setSystemsActive(true);
            mConfigurationManager.getChannelProcessingManager().setChannelActivityEnabled(true);
            mTabbedPane.setSelectedComponent(mNowPlayingPanel);
        }
        else
        {
            mNowPlayingPanel.setSystemsActive(false);
            mConfigurationManager.getChannelProcessingManager().setChannelActivityEnabled(false);
            mTabbedPane.remove(mNowPlayingPanel);
        }

        mSystemsVisible = visible;
    }

    public void dispose()
    {
        mNowPlayingPanel.dispose();
    }

    private void init()
    {
        setLayout(new MigLayout("insets 0 0 0 0 ", "[grow,fill]", "[]0[grow,fill]0[]"));

        add(mAudioPanel, "wrap");

        mTabbedPane = new JideTabbedPane();
        mTabbedPane.setFont(this.getFont());
        mTabbedPane.setForeground(Color.BLACK);
        if(mSystemsVisible)
        {
            mTabbedPane.addTab("Systems", mNowPlayingPanel);
        }
        else
        {
            mNowPlayingPanel.setSystemsActive(false);
            mConfigurationManager.getChannelProcessingManager().setChannelActivityEnabled(false);
        }

        mTabbedPane.addTab("Map", mMapPanel);
        mTabbedPane.addTab("Tuners", mTunerManagerPanel);

        //Set preferred size to influence the split between these panels
        mTabbedPane.setPreferredSize(new Dimension(880, 500));

        add(mTabbedPane, "wrap");
    }
}
