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
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.channel.details.ChannelDetailPanel;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityPanel;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.SplitPaneDividerHelper;
import io.github.dsheirer.gui.channel.ChannelSpectrumPanel;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.module.decode.event.DecodeEventPanel;
import io.github.dsheirer.module.decode.event.MessageActivityPanel;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.preference.PreferenceType;
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
 * Swing panel for Now Playing channels table and channel details tab set.
 */
public class NowPlayingPanel extends JPanel
{
    private static final String SPLIT_PANE_DIVIDER_IDENTIFIER = "now.playing.split.pane.divider";
    private static final int CHANNEL_ACTIVITY_MINIMUM_HEIGHT = 120;
    private static final int DETAIL_TABS_MINIMUM_HEIGHT = 120;
    private final ConfigurationManager mConfigurationManager;
    private final IconModel mIconModel;
    private final SettingsManager mSettingsManager;
    private final ChannelActivityPanel mChannelActivityPanel;
    private final UserPreferences mUserPreferences;
    private ChannelDetailPanel mChannelDetailPanel;
    private DecodeEventPanel mDecodeEventPanel;
    private MessageActivityPanel mMessageActivityPanel;
    private ChannelSpectrumPanel mChannelSpectrumSquelchPanel;
    private RadioResolveMetadataPanel mRadioResolveMetadataPanel;
    private JideTabbedPane mTabbedPane;
    private JideSplitPane mSplitPane;
    private boolean mRequestedDetailTabsVisible;
    private boolean mSystemsActive = true;
    private boolean mDetailTabsAttached;
    private boolean mSplitPaneDividerRestored;
    private boolean mRegisteredForPreferences;
    private JToggleButton mDetailTabsToggleButton;
    private final Consumer<Boolean> mDetailTabsVisibilityListener;

    /**
     * GUI panel that combines the currently decoding channels metadata table and viewers for channel details,
     * messages, events, and spectral view.
     */
    public NowPlayingPanel(ConfigurationManager configurationManager, IconModel iconModel, UserPreferences userPreferences,
                           SettingsManager settingsManager, boolean detailTabsVisible,
                           Consumer<Boolean> detailTabsVisibilityListener)
    {
        mConfigurationManager = configurationManager;
        mIconModel = iconModel;
        mSettingsManager = settingsManager;
        mUserPreferences = userPreferences;
        mChannelActivityPanel = new ChannelActivityPanel(configurationManager, iconModel, userPreferences);
        mRequestedDetailTabsVisible = detailTabsVisible;
        mDetailTabsVisibilityListener = detailTabsVisibilityListener;

        registerForPreferences();
        init();
    }

    @Override
    public void addNotify()
    {
        super.addNotify();
        registerForPreferences();
    }

    public void dispose()
    {
        detachDetailTabs();
        unregisterForPreferences();
        mChannelActivityPanel.dispose();
    }

    private void registerForPreferences()
    {
        if(!mRegisteredForPreferences)
        {
            MyEventBus.getGlobalEventBus().register(this);
            mRegisteredForPreferences = true;
        }
    }

    private void unregisterForPreferences()
    {
        if(mRegisteredForPreferences)
        {
            MyEventBus.getGlobalEventBus().unregister(this);
            mRegisteredForPreferences = false;
        }
    }

    /**
     * Change the visibility of the channel details tabs panel.
     * @param visible true to show or false to hide.
     */
    public void setDetailTabsVisible(boolean visible)
    {
        setDetailTabsVisible(visible, true);
    }

    public void setSystemsActive(boolean active)
    {
        if(mSystemsActive != active)
        {
            mSystemsActive = active;

            if(active)
            {
                updateDetailTabs();
            }
            else
            {
                detachDetailTabs();
                mChannelActivityPanel.resetTables();
            }
        }
    }

    private JideTabbedPane getTabbedPane()
    {
        if(mTabbedPane == null)
        {
            mTabbedPane = new JideTabbedPane();
            ensureDetailPanels();
            mTabbedPane.addTab("Details", mChannelDetailPanel);
            mTabbedPane.addTab("Events", mDecodeEventPanel);
            mTabbedPane.addTab("Messages", mMessageActivityPanel);
            mTabbedPane.addTab("Channel", mChannelSpectrumSquelchPanel);
            updateRadioResolveMetadataTab();
            mTabbedPane.setFont(this.getFont());
            mTabbedPane.setForeground(Color.BLACK);
            mTabbedPane.setMinimumSize(new Dimension(0, DETAIL_TABS_MINIMUM_HEIGHT));
            //Register state change listener to toggle visibility state for channel tab to turn-on/off FFT processing
            mTabbedPane.addChangeListener(e -> mChannelSpectrumSquelchPanel.setPanelVisible(getTabbedPane().getSelectedIndex() == getTabbedPane()
                    .indexOfComponent(mChannelSpectrumSquelchPanel)));
        }

        return mTabbedPane;
    }

    @Subscribe
    public void preferenceUpdated(PreferenceType preferenceType)
    {
        if(preferenceType == PreferenceType.NOW_PLAYING)
        {
            if(SwingUtilities.isEventDispatchThread())
            {
                updateRadioResolveMetadataTab();
            }
            else
            {
                SwingUtilities.invokeLater(this::updateRadioResolveMetadataTab);
            }
        }
    }

    /**
     * Split pane for channels table and channel details tabs.
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

        updateDetailTabs();

        add(getSplitPane());
    }

    public JToggleButton getDetailTabsToggleButton()
    {
        if(mDetailTabsToggleButton == null)
        {
            mDetailTabsToggleButton = new JToggleButton("Details");
            mDetailTabsToggleButton.setFocusable(false);
            mDetailTabsToggleButton.addActionListener(event ->
                setDetailTabsVisible(mDetailTabsToggleButton.isSelected(), true));
            updateDetailTabsToggleButton();
        }

        return mDetailTabsToggleButton;
    }

    private void setDetailTabsVisible(boolean visible, boolean notify)
    {
        if(mRequestedDetailTabsVisible != visible)
        {
            mRequestedDetailTabsVisible = visible;
            updateDetailTabs();

            if(notify && mDetailTabsVisibilityListener != null)
            {
                mDetailTabsVisibilityListener.accept(visible);
            }
        }
        else
        {
            updateDetailTabsToggleButton();
        }
    }

    private boolean shouldAttachDetailTabs()
    {
        return mSystemsActive && mRequestedDetailTabsVisible;
    }

    private void updateDetailTabs()
    {
        if(shouldAttachDetailTabs())
        {
            attachDetailTabs();
        }
        else
        {
            detachDetailTabs();
        }

        updateDetailTabsToggleButton();
        revalidate();
        repaint();
    }

    private void updateDetailTabsToggleButton()
    {
        if(mDetailTabsToggleButton != null)
        {
            mDetailTabsToggleButton.setSelected(mRequestedDetailTabsVisible);
            mDetailTabsToggleButton.setIcon(IconFontSwing.buildIcon(mRequestedDetailTabsVisible ?
                FontAwesome.CHEVRON_DOWN : FontAwesome.CHEVRON_UP, 12));
            mDetailTabsToggleButton.setToolTipText(mRequestedDetailTabsVisible ?
                "Collapse Details, Events, Messages, and Channel tabs" :
                "Expand Details, Events, Messages, and Channel tabs");
        }
    }

    private void attachDetailTabs()
    {
        if(!mDetailTabsAttached)
        {
            ensureDetailPanels();
            mChannelActivityPanel.addSelectedFrequencyListener(mChannelDetailPanel);
            mChannelActivityPanel.addSelectedFrequencyListener(mDecodeEventPanel);
            mChannelActivityPanel.addSelectedFrequencyListener(mMessageActivityPanel);
            mChannelActivityPanel.addSelectedFrequencyListener(mChannelSpectrumSquelchPanel);
            mSplitPaneDividerRestored = false;
            getSplitPane().add(getTabbedPane());
            mDetailTabsAttached = true;
            restoreSplitPaneDividerLocation();
            SwingUtilities.invokeLater(this::restoreSplitPaneDividerLocation);
        }
    }

    private void detachDetailTabs()
    {
        if(mDetailTabsAttached)
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
            mDetailTabsAttached = false;
        }

        disposeDetailPanels();
    }

    private void ensureDetailPanels()
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

    private void updateRadioResolveMetadataTab()
    {
        if(mTabbedPane == null)
        {
            return;
        }

        boolean enabled = mUserPreferences.getNowPlayingPreference().isRfMetadataDebugTabEnabled();
        int existingIndex = mRadioResolveMetadataPanel != null ? mTabbedPane.indexOfComponent(mRadioResolveMetadataPanel) : -1;

        if(enabled && mRadioResolveMetadataPanel == null)
        {
            mRadioResolveMetadataPanel = new RadioResolveMetadataPanel();
            mChannelActivityPanel.addSelectedOwnerChannelListener(mRadioResolveMetadataPanel);
            mRadioResolveMetadataPanel.receive(mChannelActivityPanel.getSelectedOwnerChannel());
            mTabbedPane.addTab("RF Metadata", mRadioResolveMetadataPanel);
        }
        else if(enabled && existingIndex < 0)
        {
            mTabbedPane.addTab("RF Metadata", mRadioResolveMetadataPanel);
        }
        else if(!enabled && mRadioResolveMetadataPanel != null)
        {
            if(existingIndex >= 0)
            {
                mTabbedPane.removeTabAt(existingIndex);
            }

            mChannelActivityPanel.removeSelectedOwnerChannelListener(mRadioResolveMetadataPanel);
            mRadioResolveMetadataPanel.dispose();
            mRadioResolveMetadataPanel = null;
        }
    }

    private void disposeDetailPanels()
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

        if(mRadioResolveMetadataPanel != null)
        {
            mChannelActivityPanel.removeSelectedOwnerChannelListener(mRadioResolveMetadataPanel);
            mRadioResolveMetadataPanel.dispose();
        }

        mTabbedPane = null;
        mChannelDetailPanel = null;
        mDecodeEventPanel = null;
        mMessageActivityPanel = null;
        mChannelSpectrumSquelchPanel = null;
        mRadioResolveMetadataPanel = null;
    }

    private void restoreSplitPaneDividerLocation()
    {
        if(mSplitPaneDividerRestored || mSplitPane == null || mSplitPane.getPaneCount() < 2)
        {
            return;
        }

        int location = mUserPreferences.getSwingPreference().getInt(SPLIT_PANE_DIVIDER_IDENTIFIER, 250);
        mSplitPaneDividerRestored = SplitPaneDividerHelper.restore(mSplitPane, 0, location,
            Math.min(CHANNEL_ACTIVITY_MINIMUM_HEIGHT, DETAIL_TABS_MINIMUM_HEIGHT), true);
    }

    private void saveSplitPaneDividerLocation()
    {
        int savedLocation = mUserPreferences.getSwingPreference().getInt(SPLIT_PANE_DIVIDER_IDENTIFIER, 250);
        mUserPreferences.getSwingPreference().setInt(SPLIT_PANE_DIVIDER_IDENTIFIER,
            SplitPaneDividerHelper.getDividerLocationOrDefault(mSplitPane, 0, savedLocation,
                Math.min(CHANNEL_ACTIVITY_MINIMUM_HEIGHT, DETAIL_TABS_MINIMUM_HEIGHT), true));
    }

    public int getSplitPaneDividerLocation()
    {
        int savedLocation = mUserPreferences.getSwingPreference().getInt(SPLIT_PANE_DIVIDER_IDENTIFIER, 250);
        return SplitPaneDividerHelper.getDividerLocationOrDefault(mSplitPane, 0, savedLocation,
            Math.min(CHANNEL_ACTIVITY_MINIMUM_HEIGHT, DETAIL_TABS_MINIMUM_HEIGHT), true);
    }

    public int getChannelSpectrumPanelDividerLocation()
    {
        return mChannelSpectrumSquelchPanel != null ? mChannelSpectrumSquelchPanel.getSplitPaneDividerLocation() : 700;
    }
}
