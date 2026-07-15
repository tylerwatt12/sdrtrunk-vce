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
package io.github.dsheirer.channel.metadata;

import com.jidesoft.swing.JideSplitPane;
import com.jidesoft.swing.JideTabbedPane;
import io.github.dsheirer.channel.details.ChannelDetailPanel;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityPanel;
import io.github.dsheirer.gui.SplitPaneDividerHelper;
import io.github.dsheirer.gui.channel.ChannelSpectrumPanel;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.module.decode.event.DecodeEventPanel;
import io.github.dsheirer.module.decode.event.MessageActivityPanel;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.settings.SettingsManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.function.Consumer;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.JToggleButton;

/**
 * Swing panel for the Systems table and optional lower activity viewers.
 */
public class NowPlayingPanel extends JPanel
{
    private static final String SPLIT_PANE_DIVIDER_IDENTIFIER = "now.playing.split.pane.divider";
    private static final int CHANNEL_ACTIVITY_MINIMUM_HEIGHT = 120;
    private static final int LOWER_TABS_MINIMUM_HEIGHT = 120;
    private final ConfigurationManager mConfigurationManager;
    private final IconModel mIconModel;
    private final SettingsManager mSettingsManager;
    private final ChannelActivityPanel mChannelActivityPanel;
    private final UserPreferences mUserPreferences;
    private ChannelDetailPanel mChannelDetailPanel;
    private DecodeEventPanel mDecodeEventPanel;
    private MessageActivityPanel mMessageActivityPanel;
    private ChannelSpectrumPanel mChannelSpectrumSquelchPanel;
    private JideTabbedPane mTabbedPane;
    private JideSplitPane mSplitPane;
    private boolean mRequestedLowerTabsVisible;
    private boolean mSystemsActive = true;
    private boolean mLowerTabsAttached;
    private boolean mSplitPaneDividerRestored;
    private JToggleButton mLowerTabsToggleButton;
    private final Consumer<Boolean> mLowerTabsVisibilityListener;

    /**
     * GUI panel that combines the Systems activity table and optional messages, events, and spectral viewers.
     */
    public NowPlayingPanel(ConfigurationManager configurationManager, IconModel iconModel, UserPreferences userPreferences,
                           SettingsManager settingsManager, boolean lowerViewsVisible,
                           Consumer<Boolean> lowerViewsVisibilityListener)
    {
        mConfigurationManager = configurationManager;
        mIconModel = iconModel;
        mSettingsManager = settingsManager;
        mUserPreferences = userPreferences;
        mChannelActivityPanel = new ChannelActivityPanel(configurationManager, iconModel, userPreferences);
        mRequestedLowerTabsVisible = lowerViewsVisible;
        mLowerTabsVisibilityListener = lowerViewsVisibilityListener;

        init();
    }

    public void dispose()
    {
        detachLowerTabs();
        mChannelActivityPanel.dispose();
    }

    /**
     * Change the visibility of the lower activity viewer tabs.
     * @param visible true to show or false to hide.
     */
    public void setLowerViewsVisible(boolean visible)
    {
        setLowerTabsVisible(visible, true);
    }

    public void setSystemsActive(boolean active)
    {
        if(mSystemsActive != active)
        {
            mSystemsActive = active;

            if(active)
            {
                mChannelActivityPanel.setActive(true);
                updateLowerTabs();
            }
            else
            {
                detachLowerTabs();
                mChannelActivityPanel.setActive(false);
            }
        }
    }

    private JideTabbedPane getTabbedPane()
    {
        if(mTabbedPane == null)
        {
            mTabbedPane = new JideTabbedPane();
            ensureLowerPanels();
            mTabbedPane.addTab("Details", mChannelDetailPanel);
            mTabbedPane.addTab("Events", mDecodeEventPanel);
            mTabbedPane.addTab("Messages", mMessageActivityPanel);
            mTabbedPane.addTab("Channel", mChannelSpectrumSquelchPanel);
            mTabbedPane.setFont(this.getFont());
            mTabbedPane.setForeground(Color.BLACK);
            mTabbedPane.setMinimumSize(new Dimension(0, LOWER_TABS_MINIMUM_HEIGHT));
            //Register state change listener to toggle visibility state for channel tab to turn-on/off FFT processing
            mTabbedPane.addChangeListener(e -> mChannelSpectrumSquelchPanel.setPanelVisible(getTabbedPane().getSelectedIndex() == getTabbedPane()
                    .indexOfComponent(mChannelSpectrumSquelchPanel)));
        }

        return mTabbedPane;
    }

    /**
     * Split pane for the Systems table and lower activity viewer tabs.
     */
    private JideSplitPane getSplitPane()
    {
        if(mSplitPane == null)
        {
            mSplitPane = new JideSplitPane(JideSplitPane.VERTICAL_SPLIT);
            mSplitPane.setDividerSize(5);
            mSplitPane.setShowGripper(true);
            mSplitPane.addComponentListener(new ComponentAdapter()
            {
                @Override
                public void componentResized(ComponentEvent e)
                {
                    restoreSplitPaneDividerLocation();
                }
            });
        }

        return mSplitPane;
    }

    private void init()
    {
        setLayout(new MigLayout("insets 0 10 10 10", "[grow,fill]", "[grow,fill]"));
        mChannelActivityPanel.setMinimumSize(new Dimension(0, CHANNEL_ACTIVITY_MINIMUM_HEIGHT));
        getSplitPane().add(mChannelActivityPanel);

        updateLowerTabs();

        add(getSplitPane());
    }

    public JToggleButton getLowerViewsToggleButton()
    {
        if(mLowerTabsToggleButton == null)
        {
            mLowerTabsToggleButton = new JToggleButton("Views");
            mLowerTabsToggleButton.setFocusable(false);
            mLowerTabsToggleButton.addActionListener(event ->
                setLowerTabsVisible(mLowerTabsToggleButton.isSelected(), true));
            updateLowerTabsToggleButton();
        }

        return mLowerTabsToggleButton;
    }

    private void setLowerTabsVisible(boolean visible, boolean notify)
    {
        if(mRequestedLowerTabsVisible != visible)
        {
            mRequestedLowerTabsVisible = visible;
            updateLowerTabs();

            if(notify && mLowerTabsVisibilityListener != null)
            {
                mLowerTabsVisibilityListener.accept(visible);
            }
        }
        else
        {
            updateLowerTabsToggleButton();
        }
    }

    private boolean shouldAttachLowerTabs()
    {
        return mSystemsActive && mRequestedLowerTabsVisible;
    }

    private void updateLowerTabs()
    {
        if(shouldAttachLowerTabs())
        {
            attachLowerTabs();
        }
        else
        {
            detachLowerTabs();
        }

        updateLowerTabsToggleButton();
        revalidate();
        repaint();
    }

    private void updateLowerTabsToggleButton()
    {
        if(mLowerTabsToggleButton != null)
        {
            mLowerTabsToggleButton.setSelected(mRequestedLowerTabsVisible);
            mLowerTabsToggleButton.setIcon(IconFontSwing.buildIcon(mRequestedLowerTabsVisible ?
                FontAwesome.CHEVRON_DOWN : FontAwesome.CHEVRON_UP, 12));
            mLowerTabsToggleButton.setToolTipText(mRequestedLowerTabsVisible ?
                "Collapse Details, Events, Messages, and Channel tabs" :
                "Expand Details, Events, Messages, and Channel tabs");
        }
    }

    private void attachLowerTabs()
    {
        if(!mLowerTabsAttached)
        {
            ensureLowerPanels();
            mChannelActivityPanel.addSelectedFrequencyListener(mChannelDetailPanel);
            mChannelActivityPanel.addSelectedFrequencyListener(mDecodeEventPanel);
            mChannelActivityPanel.addSelectedFrequencyListener(mMessageActivityPanel);
            mChannelActivityPanel.addSelectedFrequencyListener(mChannelSpectrumSquelchPanel);
            mSplitPaneDividerRestored = false;
            getSplitPane().add(getTabbedPane());
            mLowerTabsAttached = true;
            restoreSplitPaneDividerLocation();
            SwingUtilities.invokeLater(this::restoreSplitPaneDividerLocation);
        }
    }

    private void detachLowerTabs()
    {
        if(mLowerTabsAttached)
        {
            saveSplitPaneDividerLocation();
            mChannelActivityPanel.removeSelectedFrequencyListener(mChannelDetailPanel);
            mChannelActivityPanel.removeSelectedFrequencyListener(mDecodeEventPanel);
            mChannelActivityPanel.removeSelectedFrequencyListener(mMessageActivityPanel);
            mChannelActivityPanel.removeSelectedFrequencyListener(mChannelSpectrumSquelchPanel);

            mChannelDetailPanel.receive(io.github.dsheirer.channel.metadata.activity.SelectedFrequencyContext.clear());
            mDecodeEventPanel.receive(io.github.dsheirer.channel.metadata.activity.SelectedFrequencyContext.clear());
            mMessageActivityPanel.receive(io.github.dsheirer.channel.metadata.activity.SelectedFrequencyContext.clear());
            mChannelSpectrumSquelchPanel.receive(io.github.dsheirer.channel.metadata.activity.SelectedFrequencyContext.clear());
            mChannelSpectrumSquelchPanel.setPanelVisible(false);
            mChannelActivityPanel.clearSelectedFrequencyContext();
            getSplitPane().remove(getTabbedPane());
            mLowerTabsAttached = false;
        }

        disposeLowerPanels();
    }

    private void ensureLowerPanels()
    {
        if(mChannelDetailPanel == null)
        {
            mChannelDetailPanel = new ChannelDetailPanel(mConfigurationManager.getChannelProcessingManager());
        }

        if(mDecodeEventPanel == null)
        {
            mDecodeEventPanel = new DecodeEventPanel(mIconModel, mUserPreferences, mConfigurationManager.getAliasModel());
        }

        if(mMessageActivityPanel == null)
        {
            mMessageActivityPanel = new MessageActivityPanel(mUserPreferences);
        }

        if(mChannelSpectrumSquelchPanel == null)
        {
            mChannelSpectrumSquelchPanel = new ChannelSpectrumPanel(mConfigurationManager, mSettingsManager, mUserPreferences);
        }
    }

    private void disposeLowerPanels()
    {
        if(mDecodeEventPanel != null)
        {
            mDecodeEventPanel.dispose();
        }

        if(mMessageActivityPanel != null)
        {
            mMessageActivityPanel.dispose();
        }

        if(mChannelSpectrumSquelchPanel != null)
        {
            mChannelSpectrumSquelchPanel.dispose();
        }

        mTabbedPane = null;
        mChannelDetailPanel = null;
        mDecodeEventPanel = null;
        mMessageActivityPanel = null;
        mChannelSpectrumSquelchPanel = null;
    }

    private void restoreSplitPaneDividerLocation()
    {
        if(mSplitPaneDividerRestored || mSplitPane == null || mSplitPane.getPaneCount() < 2)
        {
            return;
        }

        int location = mUserPreferences.getSwingPreference().getInt(SPLIT_PANE_DIVIDER_IDENTIFIER, 250);
        mSplitPaneDividerRestored = SplitPaneDividerHelper.restore(mSplitPane, 0, location,
            Math.min(CHANNEL_ACTIVITY_MINIMUM_HEIGHT, LOWER_TABS_MINIMUM_HEIGHT), true);
    }

    private void saveSplitPaneDividerLocation()
    {
        int savedLocation = mUserPreferences.getSwingPreference().getInt(SPLIT_PANE_DIVIDER_IDENTIFIER, 250);
        mUserPreferences.getSwingPreference().setInt(SPLIT_PANE_DIVIDER_IDENTIFIER,
            SplitPaneDividerHelper.getDividerLocationOrDefault(mSplitPane, 0, savedLocation,
                Math.min(CHANNEL_ACTIVITY_MINIMUM_HEIGHT, LOWER_TABS_MINIMUM_HEIGHT), true));
    }

    public int getSplitPaneDividerLocation()
    {
        int savedLocation = mUserPreferences.getSwingPreference().getInt(SPLIT_PANE_DIVIDER_IDENTIFIER, 250);
        return SplitPaneDividerHelper.getDividerLocationOrDefault(mSplitPane, 0, savedLocation,
            Math.min(CHANNEL_ACTIVITY_MINIMUM_HEIGHT, LOWER_TABS_MINIMUM_HEIGHT), true);
    }

    public int getChannelSpectrumPanelDividerLocation()
    {
        return mChannelSpectrumSquelchPanel != null ? mChannelSpectrumSquelchPanel.getSplitPaneDividerLocation() : 700;
    }
}
