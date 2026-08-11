/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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
package io.github.dsheirer.audio.playback;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.AudioEvent;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.preference.PreferenceEditorType;
import io.github.dsheirer.gui.preference.ViewUserPreferenceEditorRequest;
import io.github.dsheirer.gui.theme.ThemeManager;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.settings.ColorSetting.ColorSettingName;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.settings.SettingsManager;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import javax.sound.sampled.FloatControl;
import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.JToggleButton;

/**
 * Audio playback panel
 */
public class AudioPanel extends JPanel implements Listener<AudioEvent>
{
    private static final ImageIcon MUTED_ICON = IconModel.getScaledIcon("images/audio_muted.png", 20);
    private static final ImageIcon UNMUTED_ICON = IconModel.getScaledIcon("images/audio_unmuted.png", 20);
    private final AliasModel mAliasModel;
    private final AudioPlaybackManager mAudioPlaybackManager;
    private final IconModel mIconModel;
    private final SettingsManager mSettingsManager;
    private final UserPreferences mUserPreferences;
    private AudioChannelsPanel mAudioChannelsPanel;
    private MuteButton mMuteButton;
    private PlaybackFilterPanel mPlaybackFilterPanel;
    private QueuedCallCountPanel mQueuedCallCountPanel;
    private final Listener<AudioPlaybackState> mPlaybackStateListener = state ->
        EventQueue.invokeLater(() -> updatePlaybackState(state));

    /**
     * Constructs an instance
     * @param iconModel for icon lookup
     * @param userPreferences for preference lookup
     * @param settingsManager to monitor for changes
     * @param audioPlaybackManager for accessing the audio output
     * @param aliasModel for alias lookup
     */
    public AudioPanel(IconModel iconModel, UserPreferences userPreferences, SettingsManager settingsManager,
                      AudioPlaybackManager audioPlaybackManager, AliasModel aliasModel)
    {
        mIconModel = iconModel;
        mSettingsManager = settingsManager;
        mAudioPlaybackManager = audioPlaybackManager;
        mAliasModel = aliasModel;
        mUserPreferences = userPreferences;
        mAudioPlaybackManager.addAudioEventListener(this);
        init();
        mAudioPlaybackManager.addPlaybackStateListener(mPlaybackStateListener);
    }

    public void dispose()
    {
        mAudioPlaybackManager.removeAudioEventListener(this);
        mAudioPlaybackManager.removePlaybackStateListener(mPlaybackStateListener);

        if(mAudioChannelsPanel != null)
        {
            mAudioChannelsPanel.dispose();
        }
    }

    private void updatePlaybackState(AudioPlaybackState state)
    {
        if(state != null)
        {
            getMuteButton().updateMuted(state.localMuted());
            getPlaybackFilterPanel().update(state);
            getQueuedCallCountPanel().update(state.queuedCallCount());
        }
    }

    /**
     * Initialize the display
     */
    private void init()
    {
        setLayout(new MigLayout("insets 0 0 0 0", "[]0[]0[grow,fill][]", "[fill]"));
        setBackground(Color.BLACK);
        getMuteButton().setBackground(getBackground());
        add(getMuteButton(), "cell 0 0");
        add(getPlaybackFilterPanel(), "cell 1 0,aligny center,gapleft 3,gapright 4");
        mAudioChannelsPanel = new AudioChannelsPanel(mIconModel, mUserPreferences, mSettingsManager, mAudioPlaybackManager, mAliasModel);
        add(mAudioChannelsPanel, "cell 2 0,grow");
        add(getQueuedCallCountPanel(), "cell 3 0,gapleft 8,gapright 8");
        addMouseListener(new MouseSelectionListener());
    }

    /**
     * Receive audio event notifications from the audio playback controller
     */
    @Override
    public void receive(AudioEvent event)
    {
        switch(event.getType())
        {
            case AUDIO_CONFIGURATION_CHANGE_STARTED:
                break;
            case AUDIO_CONFIGURATION_CHANGE_COMPLETE:
                EventQueue.invokeLater(() -> {
                    remove(mAudioChannelsPanel);
                    mAudioChannelsPanel.dispose();
                    mAudioChannelsPanel = new AudioChannelsPanel(mIconModel, mUserPreferences, mSettingsManager, mAudioPlaybackManager, mAliasModel);
                    add(mAudioChannelsPanel, "cell 2 0,grow");
                    getMuteButton().updateMuted(mAudioPlaybackManager.isMuted());
                    mAudioChannelsPanel.repaint();
                    revalidate();
                    repaint();
                });
                break;
            default:
                break;
        }
    }

    private MuteButton getMuteButton()
    {
        if(mMuteButton == null)
        {
            mMuteButton = new MuteButton();
        }

        return mMuteButton;
    }

    private PlaybackFilterPanel getPlaybackFilterPanel()
    {
        if(mPlaybackFilterPanel == null)
        {
            mPlaybackFilterPanel = new PlaybackFilterPanel();
        }

        return mPlaybackFilterPanel;
    }

    private QueuedCallCountPanel getQueuedCallCountPanel()
    {
        if(mQueuedCallCountPanel == null)
        {
            mQueuedCallCountPanel = new QueuedCallCountPanel();
        }

        return mQueuedCallCountPanel;
    }

    /**
     * Audio output mute control menu item.
     */
    public static class AudioOutputMuteItem extends JMenuItem
    {
        private final AudioOutput mAudioOutput;

        /**
         * Constructs an instance
         * @param audioOutput to mute/unmute
         */
        public AudioOutputMuteItem(AudioOutput audioOutput)
        {
            super(audioOutput.isMuted() ? "Unmute" : "Mute");
            mAudioOutput = audioOutput;
            addActionListener(e -> mAudioOutput.setMuted(!mAudioOutput.isMuted()));
        }
    }

    /**
     * Mouse listener
     */
    public class MouseSelectionListener extends MouseAdapter
    {
        @Override
        public void mouseClicked(MouseEvent event)
        {
            if(SwingUtilities.isRightMouseButton(event))
            {
                JPopupMenu popup = new JPopupMenu();
                JMenuItem outputMenu = new JMenuItem("Audio Playback Device ...");
                Icon icon = IconFontSwing.buildIcon(FontAwesome.COG, 14);
                outputMenu.setIcon(icon);
                outputMenu.addActionListener(e -> MyEventBus.getGlobalEventBus()
                        .post(new ViewUserPreferenceEditorRequest(PreferenceEditorType.AUDIO_OUTPUT)));
                popup.add(outputMenu);

                //Add optional gain controller for th eaudio output
                if(mAudioPlaybackManager.getAudioOutput() != null && mAudioPlaybackManager.getAudioOutput().hasGainControl())
                {
                    popup.add(new JPopupMenu.Separator());
                    JMenuItem volume = new JMenuItem("Audio Volume");
                    volume.setEnabled(false);
                    Icon volumeIcon = IconFontSwing.buildIcon(FontAwesome.VOLUME_UP, 14);
                    volume.setIcon(volumeIcon);
                    popup.add(volume);
                    popup.add(new VolumeSlider(mAudioPlaybackManager.getAudioOutput().getGainControl()));
                }

                ThemeManager.getInstance().preparePopupMenu(popup);
                popup.show(event.getComponent(), event.getX(), event.getY());
            }
        }
    }

    /**
     * Audio volume (gain) adjustment slider control
     */
    public static class VolumeSlider extends JSlider
    {
        private final transient FloatControl mFloatControl;

        /**
         * Constructs an instance
         * @param control to be controlled
         */
        public VolumeSlider(FloatControl control)
        {
            super(0, 100, 0);

            setMajorTickSpacing(25);
            setMinorTickSpacing(5);
            setPaintTicks(true);
            setPaintLabels(true);
            mFloatControl = control;
            setValue(getIntegerValue(mFloatControl.getValue()));
            addChangeListener(event -> mFloatControl.shift(mFloatControl.getValue(),
                getFloatValue(VolumeSlider.this.getValue()),
                1000));

            addMouseListener(new MouseAdapter()
            {
                @Override
                public void mouseClicked(MouseEvent event)
                {
                    if(event.getClickCount() == 2)
                    {
                        VolumeSlider.this.setValue(50);
                    }
                }
            });
        }

        /**
         * Converts the integer value to a floating point value to use in the
         * float control.  Assumes an integer value of 50 is the 0.0 dB mid
         * point (ie no gain ) value.
         */
        private int getIntegerValue(float value)
        {
            if(value == 0.0f)
            {
                return 50;
            }
            else if(value < 0.0f)
            {
                float ratio = value / mFloatControl.getMinimum();

                return 50 - (int) (ratio * 50.0f);
            }
            else
            {
                float ratio = value / mFloatControl.getMaximum();

                return 50 + (int) (ratio * 50.0f);
            }
        }

        private float getFloatValue(int value)
        {
            if(value == 50)
            {
                return 0.0f;
            }
            else if(value < 50)
            {
                return (50 - value) / 50.0f * mFloatControl.getMinimum();
            }
            else
            {
                return (value - 50) / 50.0f * mFloatControl.getMaximum();
            }
        }
    }

    /**
     * Mute button to mute all audio output channels exposed by the audio
     * controller
     */
    public class MuteButton extends JButton
    {
        private boolean mMuted;

        public MuteButton()
        {
            mMuted = mAudioPlaybackManager.isMuted();
            updateIcon();
            setBorderPainted(false);
            setFocusable(false);
            addActionListener(e -> mAudioPlaybackManager.setMuted(!mMuted));
        }

        private void updateMuted(boolean muted)
        {
            mMuted = muted;
            updateIcon();
        }

        private void updateIcon()
        {
            setIcon(mMuted ? MUTED_ICON : UNMUTED_ICON);
            getAccessibleContext().setAccessibleName(mMuted ? "Unmute" : "Mute");
            setToolTipText(mMuted ? "Unmute Audio Playback" : "Mute Audio Playback");
        }
    }

    public class PlaybackFilterPanel extends JPanel
    {
        private final Font mFont = new Font(Font.MONOSPACED, Font.BOLD, 13);
        private final JToggleButton mHoldButton = new JToggleButton("Hold");
        private final JButton mAvoidButton = new JButton("Avoid");
        private final JButton mClearAvoidsButton = new JButton("Clear");

        public PlaybackFilterPanel()
        {
            setLayout(new MigLayout("insets 0 0 0 0, aligny center", "[]3[]3[]", "[center]"));
            setBackground(Color.BLACK);
            configureButton(mHoldButton);
            configureButton(mAvoidButton);
            configureButton(mClearAvoidsButton);

            mHoldButton.addActionListener(event -> mAudioPlaybackManager.toggleHoldOnCurrentCall());
            mAvoidButton.addActionListener(event -> mAudioPlaybackManager.avoidCurrentCall());
            mClearAvoidsButton.addActionListener(event -> mAudioPlaybackManager.clearAvoids());

            add(mHoldButton, "aligny center");
            add(mAvoidButton, "aligny center");
            add(mClearAvoidsButton, "aligny center");
            update(mAudioPlaybackManager.getPlaybackState());
        }

        private void configureButton(AbstractButton button)
        {
            button.setFont(mFont);
            button.setFocusable(false);
            button.setMargin(new Insets(1, 6, 1, 6));
            button.setVerticalAlignment(SwingConstants.CENTER);
        }

        private void update(AudioPlaybackState state)
        {
            String currentTarget = state != null ? state.currentTarget() : null;
            String holdTarget = state != null ? state.holdTarget() : null;
            int avoidCount = state != null ? state.avoidedTargets().size() : 0;
            boolean holdActive = holdTarget != null;

            mHoldButton.setSelected(holdActive);
            mHoldButton.setEnabled(holdActive || currentTarget != null);
            mHoldButton.setToolTipText(holdActive ? "Release hold: " + holdTarget :
                currentTarget != null ? "Hold: " + currentTarget : "Hold current call");
            mAvoidButton.setEnabled(currentTarget != null);
            mAvoidButton.setToolTipText(currentTarget != null ? "Avoid: " + currentTarget : "Avoid current call");
            mClearAvoidsButton.setEnabled(avoidCount > 0);
            mClearAvoidsButton.setToolTipText("Clear temporary avoids" +
                (avoidCount > 0 ? " (" + avoidCount + ")" : ""));
        }
    }

    public class QueuedCallCountPanel extends JPanel
    {
        private final Font mFont = new Font(Font.MONOSPACED, Font.PLAIN, 16);
        private final JLabel mLabel = new JLabel("Queued");
        private final JLabel mValue = new JLabel("0");

        public QueuedCallCountPanel()
        {
            setLayout(new MigLayout("insets 2 0 0 0", "[]4[]", "[center]"));
            Color background = mSettingsManager.getColorSetting(ColorSettingName.AUDIO_CHANNEL_BACKGROUND).getColor();
            setBackground(background);
            mLabel.setFont(mFont);
            mLabel.setForeground(mSettingsManager.getColorSetting(ColorSettingName.AUDIO_CHANNEL_LABEL).getColor());
            mValue.setFont(mFont);
            mValue.setForeground(mSettingsManager.getColorSetting(ColorSettingName.AUDIO_CHANNEL_VALUE).getColor());
            add(mLabel);
            add(mValue);
            update(mAudioPlaybackManager.getPlaybackState().queuedCallCount());
        }

        private void update(int queuedCallCount)
        {
            mValue.setText(String.valueOf(queuedCallCount));
        }
    }
}
