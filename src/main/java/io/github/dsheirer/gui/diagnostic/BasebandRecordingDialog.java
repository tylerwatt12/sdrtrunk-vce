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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
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
    private final JButton mCloseButton = new JButton("Close");
    private final JLabel mStatus = new JLabel("No recording active");
    private final JLabel mSize = new JLabel("Size: —");
    private final JLabel mFileLabel = new JLabel("Local file:");
    private final JTextField mFile = new JTextField();
    private final AtomicReference<RecordingProgress> mLatestStatus = new AtomicReference<>();
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
        setLayout(new MigLayout("insets 12, fillx", "[][grow,fill]", ""));
        add(new JLabel("Running physical tuner:"), "span 2, wrap");
        add(mTuners, "span 2, growx, wrap");
        add(mStatus, "span 2, wrap");
        add(mSize, "span 2, wrap");
        add(mFileLabel, "span 2, wrap");
        mFile.setEditable(false);
        mFile.setFocusable(true);
        add(mFile, "span 2, growx, wrap");
        add(new JLabel("Close stops capture; WAV finalization may continue briefly."),
            "span 2, wrap, gapbottom 8");
        add(mRecordButton, "span 2, split 2");
        add(mCloseButton, "wrap");
        mRecordButton.addActionListener(event -> toggleRecording());
        mCloseButton.addActionListener(event -> dispose());
        refreshTuners();
        pack();
        setSize(Math.max(520, getWidth()), getHeight());
        setMinimumSize(new java.awt.Dimension(440, getHeight()));
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
            mStatus.setText("Starting recording…");
            mSize.setText("Size: waiting for first update");
            mFileLabel.setText("Local file:");
            mFile.setText("");
            mFile.setToolTipText(null);
            controller.startRecorder(mUserPreferences, mRecordingStatusListener, selected.getTunerClass().name());
            if(!controller.isRecording() || mLatestStatus.get() == null)
            {
                controller.stopRecorder();
                controller.removeRecordingStatusListener(mRecordingStatusListener);
                mStatus.setText("Recording could not be started.");
                mSize.setText("Size: —");
                return;
            }
            mOwnedController = controller;
            mTuners.setEnabled(false);
            mRecordButton.setText("Stop Recording");
            mCloseButton.setText("Stop & Close");
            mStatus.setText("Recording locally…");
            showProgress(mLatestStatus.get());
        }
        catch(RuntimeException exception)
        {
            controller.stopRecorder();
            controller.removeRecordingStatusListener(mRecordingStatusListener);
            mSize.setText("Size: —");
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
        mCloseButton.setText("Close");
        if(controller != null)
        {
            mStatus.setText("Stopped. WAV finalization may continue briefly.");
            mFileLabel.setText("Last local file:");
            RecordingProgress progress = mLatestStatus.get();
            mSize.setText(progress == null ? "Last reported size: —" :
                "Last reported size: " + readableSize(progress.size()) + " (" + progress.size() + " bytes)");
        }
    }

    private void recordingStatusUpdated(int fileCount, String file, long size)
    {
        mLatestStatus.set(new RecordingProgress(fileCount, file, size));
        if(mStatusUpdatePending.compareAndSet(false, true))
        {
            EventQueue.invokeLater(() -> {
                mStatusUpdatePending.set(false);
                if(mOwnedController != null && isDisplayable())
                {
                    showProgress(mLatestStatus.get());
                }
            });
        }
    }

    private void showProgress(RecordingProgress progress)
    {
        if(progress != null)
        {
            mStatus.setText("Recording file " + progress.fileCount());
            mSize.setText("Size: " + readableSize(progress.size()) + " (" + progress.size() + " bytes)");
            mFile.setText(progress.file());
            mFile.setCaretPosition(0);
            mFile.setToolTipText(progress.file());
        }
    }

    private static String readableSize(long bytes)
    {
        if(bytes < 1_024)
        {
            return bytes + " B";
        }

        double value = bytes;
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int unit = 0;
        while(value >= 1_024 && unit < units.length - 1)
        {
            value /= 1_024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    @Override
    public void dispose()
    {
        stopOwnedRecording();
        super.dispose();
    }

    private record RecordingProgress(int fileCount, String file, long size) {}

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
