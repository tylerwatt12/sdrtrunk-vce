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
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.properties.SystemProperties;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.settings.SettingsManager;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import javax.sound.sampled.FloatControl;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

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
    private QueuedCallCountPanel mQueuedCallCountPanel;

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
    }

    /**
     * Initialize the display
     */
    private void init()
    {
        setLayout(new MigLayout("insets 0 0 0 0", "[]0[grow,fill][]", "[fill]"));
        setBackground(Color.BLACK);
        getMuteButton().setBackground(getBackground());
        add(getMuteButton(), "cell 0 0");
        mAudioChannelsPanel = new AudioChannelsPanel(mIconModel, mUserPreferences, mSettingsManager, mAudioPlaybackManager, mAliasModel);
        add(mAudioChannelsPanel, "cell 1 0,grow");
        add(getQueuedCallCountPanel(), "cell 2 0,gapleft 8,gapright 8");
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
                    add(mAudioChannelsPanel, "cell 1 0,grow");
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
            addActionListener(e -> {
                mMuted = !mMuted;
                mAudioPlaybackManager.setMuted(mMuted);
                EventQueue.invokeLater(() -> {
                    updateIcon();
                });
            });
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

    public class QueuedCallCountPanel extends JPanel
    {
        private final Font mFont = new Font(Font.MONOSPACED, Font.PLAIN, 16);
        private final JLabel mLabel = new JLabel("Queued");
        private final JLabel mValue = new JLabel("0");
        private final Timer mUpdateTimer = new Timer(500, event -> update());

        public QueuedCallCountPanel()
        {
            setLayout(new MigLayout("insets 2 0 0 0", "[]4[]", "[center]"));
            Color background = SystemProperties.getInstance().get(AudioChannelPanel.PROPERTY_COLOR_BACKGROUND,
                Color.BLACK);
            setBackground(background);
            mLabel.setFont(mFont);
            mLabel.setForeground(SystemProperties.getInstance().get(AudioChannelPanel.PROPERTY_COLOR_LABEL,
                Color.LIGHT_GRAY));
            mValue.setFont(mFont);
            mValue.setForeground(SystemProperties.getInstance().get(AudioChannelPanel.PROPERTY_COLOR_VALUE,
                Color.GREEN));
            add(mLabel);
            add(mValue);
            mUpdateTimer.start();
        }

        private void update()
        {
            mValue.setText(String.valueOf(mAudioPlaybackManager.getQueuedCallCount()));
        }
    }
}
