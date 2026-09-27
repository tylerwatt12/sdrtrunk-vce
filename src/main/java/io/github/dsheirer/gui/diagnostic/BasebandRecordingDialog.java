/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.diagnostic;

import io.github.dsheirer.gui.ApplicationIcon;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.record.wave.IRecordingStatusListener;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.manager.DiscoveredRecordingTuner;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.awt.EventQueue;
import java.awt.Frame;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import net.miginfocom.swing.MigLayout;

/**
 * Local debugging control for recording an already-running physical tuner's I/Q stream. This deliberately does not
 * start tuners, change their settings, stop channels, or expose recordings through the web service.
 */
public final class BasebandRecordingDialog extends JDialog
{
    private final UserPreferences mUserPreferences;
    private final TunerManager mTunerManager;
    private final JComboBox<TunerChoice> mTuners = new JComboBox<>();
    private final JButton mRecordButton = new JButton("Start Recording");
    private final JLabel mStatus = new JLabel("No recording active");
    private final AtomicReference<String> mLatestStatus = new AtomicReference<>();
    private final AtomicBoolean mStatusUpdatePending = new AtomicBoolean();
    private final IRecordingStatusListener mRecordingStatusListener = new IRecordingStatusListener()
    {
        @Override
        public void update(int fileCount, String file, long size)
        {
            recordingStatusUpdated(fileCount, file, size);
        }

        @Override
        public void failed(String reason)
        {
            EventQueue.invokeLater(() -> {
                if(isDisplayable())
                {
                    stopOwnedRecording();
                    mStatus.setText(reason);
                }
            });
        }
    };
    private TunerController mOwnedController;

    public BasebandRecordingDialog(Frame owner, TunerManager tunerManager, UserPreferences userPreferences)
    {
        super(owner, "Baseband Recording (Debug)", false);
        mTunerManager = tunerManager;
        mUserPreferences = userPreferences;
        ApplicationIcon.apply(this);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new MigLayout("insets 12, fillx", "[][grow,fill]", "[][][]"));
        add(new JLabel("Running physical tuner:"));
        add(mTuners, "wrap");
        add(mRecordButton, "span 2, split 2");
        JButton closeButton = new JButton("Close");
        add(closeButton, "wrap");
        add(mStatus, "span 2, growx");
        mStatus.setToolTipText("The recording is saved in the configured local recordings directory.");
        mRecordButton.addActionListener(event -> toggleRecording());
        closeButton.addActionListener(event -> dispose());
        refreshTuners();
        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(owner);
    }

    private void refreshTuners()
    {
        mTuners.removeAllItems();
        for(DiscoveredTuner tuner: mTunerManager.getDiscoveredTunerRegistry().availableTuners())
        {
            if(!(tuner instanceof DiscoveredRecordingTuner))
            {
                mTuners.addItem(new TunerChoice(tuner));
            }
        }
        mRecordButton.setEnabled(mTuners.getItemCount() > 0);
        if(mTuners.getItemCount() == 0)
        {
            mStatus.setText("No running physical tuner is available.");
        }
    }

    private void toggleRecording()
    {
        if(mOwnedController != null)
        {
            stopOwnedRecording();
            return;
        }

        TunerChoice choice = (TunerChoice)mTuners.getSelectedItem();
        DiscoveredTuner selected = choice == null ? null : choice.tuner();
        Tuner tuner = selected == null ? null : selected.getTuner();
        if(selected == null || !selected.isAvailable() || tuner == null)
        {
            mStatus.setText("The selected tuner is no longer available.");
            refreshTuners();
            return;
        }

        TunerController controller = tuner.getTunerController();
        if(controller.isRecording())
        {
            mStatus.setText("This tuner is already recording elsewhere.");
            return;
        }

        try
        {
            mLatestStatus.set(null);
            controller.startRecorder(mUserPreferences, mRecordingStatusListener, selected.getTunerClass().name());
            if(!controller.isRecording() || mLatestStatus.get() == null)
            {
                controller.stopRecorder();
                controller.removeRecordingStatusListener(mRecordingStatusListener);
                mStatus.setText("Recording could not be started.");
                return;
            }
            mOwnedController = controller;
            mTuners.setEnabled(false);
            mRecordButton.setText("Stop Recording");
            mStatus.setText("Recording locally…");
        }
        catch(RuntimeException exception)
        {
            controller.stopRecorder();
            controller.removeRecordingStatusListener(mRecordingStatusListener);
            JOptionPane.showMessageDialog(this, "Unable to start baseband recording: " +
                exception.getClass().getSimpleName(), "Baseband Recording", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void stopOwnedRecording()
    {
        TunerController controller = mOwnedController;
        mOwnedController = null;
        if(controller != null)
        {
            try
            {
                controller.stopRecorder();
            }
            finally
            {
                controller.removeRecordingStatusListener(mRecordingStatusListener);
            }
        }
        mTuners.setEnabled(true);
        mRecordButton.setText("Start Recording");
        mStatus.setText("No recording active");
    }

    private void recordingStatusUpdated(int fileCount, String file, long size)
    {
        mLatestStatus.set("File " + fileCount + ": " + file + " (" + size + " bytes)");
        if(mStatusUpdatePending.compareAndSet(false, true))
        {
            EventQueue.invokeLater(() -> {
                mStatusUpdatePending.set(false);
                if(mOwnedController != null && isDisplayable())
                {
                    mStatus.setText(mLatestStatus.get());
                }
            });
        }
    }

    @Override
    public void dispose()
    {
        stopOwnedRecording();
        super.dispose();
    }

    private record TunerChoice(DiscoveredTuner tuner)
    {
        @Override
        public String toString()
        {
            Tuner active = tuner.getTuner();
            String type = active == null ? "Disconnected" : active.getTunerType().getLabel();
            return tuner.getTunerClass() + " · " + type + " · " + tuner.getId();
        }
    }
}
