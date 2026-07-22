/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.startup;

import io.github.dsheirer.controller.channel.AutoStartChannelModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.gui.whatsnew.ReleaseNotes;
import io.github.dsheirer.gui.whatsnew.WhatsNewDialog;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultException;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultService;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.vector.calibrate.Calibration;
import io.github.dsheirer.vector.calibrate.CalibrationManager;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Presents all eligible post-launch actions in one owned modal window and advances through them in a fixed order.
 */
public class CoordinatedStartupDialog extends JDialog
{
    private static final Logger mLog = LoggerFactory.getLogger(CoordinatedStartupDialog.class);
    private static final int STARTUP_STEP_TIMEOUT_SECONDS = 30;

    private final UserPreferences mUserPreferences;
    private final Optional<ReleaseNotes> mReleaseNotes;
    private final EncryptionKeyVaultService mVaultService;
    private final List<Channel> mAutoStartChannels;
    private final Listener<ChannelEvent> mChannelEventListener;
    private final StartupSequence mSequence;
    private final CardLayout mCardLayout = new CardLayout();
    private final JPanel mCards = new JPanel(mCardLayout);
    private final JPanel mButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    private final JLabel mStepLabel = new JLabel();
    private final JLabel mHeaderLabel = new JLabel();
    private final AtomicBoolean mChannelsHandled = new AtomicBoolean();

    private JLabel mWhatsNewCountdownLabel;
    private JLabel mCalibrationCountdownLabel;
    private JButton mPassiveStepPauseButton;
    private Timer mPassiveStepTimer;
    private StartupCountdown mPassiveStepCountdown;
    private JLabel mPassiveStepCountdownLabel;
    private IntFunction<String> mPassiveStepLabelText;
    private JCheckBox mHideCalibrationCheckBox;
    private JLabel mCalibrationStatusLabel;
    private JProgressBar mCalibrationProgressBar;
    private boolean mCalibrationRunning;
    private JPasswordField mVaultPasswordField;
    private JCheckBox mSaveVaultPasswordCheckBox;
    private JLabel mVaultStatusLabel;
    private JLabel mVaultCountdownLabel;
    private Timer mVaultTimer;
    private StartupCountdown mVaultCountdown;
    private boolean mVaultUnlockRunning;
    private JLabel mAutoStartCountdownLabel;
    private Timer mAutoStartTimer;
    private StartupCountdown mAutoStartCountdown;

    public CoordinatedStartupDialog(Frame owner, UserPreferences userPreferences,
                                    Optional<ReleaseNotes> releaseNotes, boolean calibrationRequired,
                                    EncryptionKeyVaultService vaultService, List<Channel> autoStartChannels,
                                    Listener<ChannelEvent> channelEventListener)
    {
        super(owner, "sdrtrunk-vce Startup", ModalityType.APPLICATION_MODAL);
        mUserPreferences = userPreferences;
        mReleaseNotes = releaseNotes;
        mVaultService = vaultService;
        mAutoStartChannels = List.copyOf(autoStartChannels);
        mChannelEventListener = channelEventListener;
        mSequence = new StartupSequence(releaseNotes.isPresent(), calibrationRequired, vaultService != null,
            !autoStartChannels.isEmpty());

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter()
        {
            @Override
            public void windowClosing(WindowEvent event)
            {
                cancelRemainingStartup();
            }

            @Override
            public void windowClosed(WindowEvent event)
            {
                stopTimers();
            }
        });

        if(owner != null)
        {
            setIconImages(owner.getIconImages());
        }

        setContentPane(createContent());
        setPreferredSize(new Dimension(780, 700));
        setMinimumSize(new Dimension(600, 480));
        pack();
        setLocationRelativeTo(owner);
    }

    public boolean hasSteps()
    {
        return mSequence.size() > 0;
    }

    /**
     * Shows the first eligible page. Must be invoked on the Swing event-dispatch thread.
     */
    public void showExperience()
    {
        if(!SwingUtilities.isEventDispatchThread())
        {
            throw new IllegalStateException("The coordinated startup experience must run on the Swing EDT");
        }

        Optional<StartupStep> firstStep = mSequence.start();

        if(firstStep.isPresent())
        {
            showStep(firstStep.get());

            // A zero-second auto-start can complete and dispose the dialog synchronously from showStep().
            if(mSequence.current().isPresent())
            {
                setVisible(true);
            }
        }
    }

    private JPanel createContent()
    {
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JPanel heading = new JPanel(new BorderLayout(0, 4));
        mStepLabel.setForeground(Color.DARK_GRAY);
        mHeaderLabel.setFont(mHeaderLabel.getFont().deriveFont(Font.BOLD, 20.0f));
        heading.add(mStepLabel, BorderLayout.NORTH);
        heading.add(mHeaderLabel, BorderLayout.CENTER);
        root.add(heading, BorderLayout.NORTH);

        mReleaseNotes.ifPresent(notes -> mCards.add(createWhatsNewPanel(notes), StartupStep.WHATS_NEW.name()));
        if(mSequence.getSteps().contains(StartupStep.CPU_CALIBRATION))
        {
            mCards.add(createCalibrationPanel(), StartupStep.CPU_CALIBRATION.name());
        }
        if(mVaultService != null)
        {
            mCards.add(createVaultPanel(), StartupStep.ENCRYPTION_VAULT.name());
        }
        if(!mAutoStartChannels.isEmpty())
        {
            mCards.add(createAutoStartPanel(), StartupStep.AUTO_START_CHANNELS.name());
        }

        root.add(mCards, BorderLayout.CENTER);
        root.add(mButtons, BorderLayout.SOUTH);
        return root;
    }

    private JPanel createWhatsNewPanel(ReleaseNotes notes)
    {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(WhatsNewDialog.createReleaseNotesView(notes), BorderLayout.CENTER);
        mWhatsNewCountdownLabel = new JLabel();
        panel.add(mWhatsNewCountdownLabel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createCalibrationPanel()
    {
        JPanel panel = new JPanel(new BorderLayout(0, 18));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 8, 8, 8));
        panel.add(wrappedText("sdrtrunk can run more efficiently by benchmarking the scalar and vector/SIMD " +
            "implementations available on this CPU. Calibration can take several minutes. Startup will continue " +
            "after it finishes, and the new selections will be used after the next restart."), BorderLayout.NORTH);

        JPanel statusPanel = new JPanel(new MigLayout("insets 20 0 0 0", "[grow,fill]", "[][][]"));
        mCalibrationProgressBar = new JProgressBar(0, 100);
        mCalibrationProgressBar.setStringPainted(true);
        mCalibrationStatusLabel = new JLabel("Calibration has not been run for all available operations.");
        mCalibrationCountdownLabel = new JLabel();
        mHideCalibrationCheckBox = new JCheckBox("Do not ask again");
        mHideCalibrationCheckBox.setSelected(
            mUserPreferences.getVectorCalibrationPreference().isHideCalibrationDialog());
        mHideCalibrationCheckBox.addActionListener(event ->
            mUserPreferences.getVectorCalibrationPreference().setHideCalibrationDialog(
                mHideCalibrationCheckBox.isSelected()));
        statusPanel.add(mCalibrationStatusLabel, "wrap");
        statusPanel.add(mCalibrationProgressBar, "growx,wrap");
        statusPanel.add(mHideCalibrationCheckBox, "wrap");
        statusPanel.add(mCalibrationCountdownLabel);
        panel.add(statusPanel, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createVaultPanel()
    {
        JPanel panel = new JPanel(new BorderLayout(0, 18));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 8, 8, 8));
        panel.add(wrappedText("Unlock the encryption key vault before channels start, or leave decryption disabled " +
            "for this run."), BorderLayout.NORTH);

        JPanel form = new JPanel(new MigLayout("insets 20 0 0 0", "[right][grow,fill]", "[][][][]"));
        mVaultPasswordField = new JPasswordField(24);
        mSaveVaultPasswordCheckBox = new JCheckBox("Save password (Warning! Unsafe!)");
        mVaultStatusLabel = new JLabel(" ");
        mVaultCountdownLabel = new JLabel();
        form.add(new JLabel("Password:"), "cell 0 0");
        form.add(mVaultPasswordField, "cell 1 0,growx");
        form.add(mSaveVaultPasswordCheckBox, "cell 1 1");
        form.add(mVaultStatusLabel, "cell 0 2 2 1");
        form.add(mVaultCountdownLabel, "cell 0 3 2 1");
        panel.add(form, BorderLayout.CENTER);

        mVaultPasswordField.getDocument().addDocumentListener(new DocumentListener()
        {
            @Override
            public void insertUpdate(DocumentEvent event)
            {
                resetVaultCountdown();
            }

            @Override
            public void removeUpdate(DocumentEvent event)
            {
                resetVaultCountdown();
            }

            @Override
            public void changedUpdate(DocumentEvent event)
            {
                resetVaultCountdown();
            }
        });
        mSaveVaultPasswordCheckBox.addActionListener(event -> resetVaultCountdown());
        return panel;
    }

    private JPanel createAutoStartPanel()
    {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 8, 8, 8));
        mAutoStartCountdownLabel = new JLabel();
        panel.add(mAutoStartCountdownLabel, BorderLayout.NORTH);
        panel.add(new JScrollPane(new JTable(new AutoStartChannelModel(mAutoStartChannels))), BorderLayout.CENTER);
        return panel;
    }

    private JTextArea wrappedText(String text)
    {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setRows(4);
        area.setColumns(60);
        area.setFont(new JLabel().getFont());
        area.setBorder(null);
        return area;
    }

    private void showStep(StartupStep step)
    {
        stopTimers();
        mStepLabel.setText("Step " + mSequence.getCurrentNumber() + " of " + mSequence.size());
        mHeaderLabel.setText(getTitle(step));
        mCardLayout.show(mCards, step.name());

        switch(step)
        {
            case WHATS_NEW ->
            {
                mReleaseNotes.ifPresent(WhatsNewDialog::markShown);
                mPassiveStepPauseButton = button("Pause Countdown", this::togglePassiveStepCountdown, false);
                setButtons(mPassiveStepPauseButton, button("Continue", this::advance, true));
                startPassiveStepTimer(mWhatsNewCountdownLabel,
                    seconds -> "Startup will continue automatically in " + seconds + " seconds.");
            }
            case CPU_CALIBRATION ->
            {
                setButtons(button("Calibrate", this::startCalibration, true),
                    button("Later", this::advance, false));
                startPassiveStepTimer(mCalibrationCountdownLabel,
                    seconds -> "Calibration will be deferred automatically in " + seconds + " seconds.");
            }
            case ENCRYPTION_VAULT ->
            {
                mVaultStatusLabel.setText(" ");
                setVaultControlsEnabled(true);
                setButtons(button("Unlock", this::unlockVault, true),
                    button("Disable for This Run", this::disableVaultForRun, false));
                startVaultTimer();
                SwingUtilities.invokeLater(mVaultPasswordField::requestFocusInWindow);
            }
            case AUTO_START_CHANNELS ->
            {
                setButtons(button("Start Now", this::startChannels, true),
                    button("Cancel", this::cancelAutoStart, false));
                startAutoStartTimer();
            }
        }
    }

    private String getTitle(StartupStep step)
    {
        return switch(step)
        {
            case WHATS_NEW -> "What's New";
            case CPU_CALIBRATION -> "CPU Calibration";
            case ENCRYPTION_VAULT -> "Encryption Key Vault";
            case AUTO_START_CHANNELS -> "Auto-Start Channels";
        };
    }

    private JButton button(String text, Runnable action, boolean defaultButton)
    {
        JButton button = new JButton(text);
        button.addActionListener(event -> action.run());
        button.putClientProperty("startup.default", defaultButton);
        return button;
    }

    private void setButtons(JButton... buttons)
    {
        mButtons.removeAll();
        getRootPane().setDefaultButton(null);

        for(JButton button: buttons)
        {
            mButtons.add(button);
            if(Boolean.TRUE.equals(button.getClientProperty("startup.default")))
            {
                getRootPane().setDefaultButton(button);
            }
        }

        mButtons.revalidate();
        mButtons.repaint();
    }

    private void advance()
    {
        stopTimers();
        Optional<StartupStep> next = mSequence.advance();

        if(next.isPresent())
        {
            showStep(next.get());
        }
        else
        {
            dispose();
        }
    }

    private void startCalibration()
    {
        stopPassiveStepTimer();
        mCalibrationCountdownLabel.setText("Calibration is running. Startup will continue when it finishes.");
        mCalibrationRunning = true;
        mHideCalibrationCheckBox.setEnabled(false);
        mCalibrationProgressBar.setValue(0);
        mCalibrationStatusLabel.setForeground(Color.DARK_GRAY);
        mCalibrationStatusLabel.setText("Preparing calibration...");
        setButtons();

        SwingWorker<Integer, CalibrationProgress> worker = new SwingWorker<>()
        {
            @Override
            protected Integer doInBackground()
            {
                List<Calibration> calibrations = CalibrationManager.getInstance().getUncalibrated();
                int failures = 0;

                for(int x = 0; x < calibrations.size(); x++)
                {
                    Calibration calibration = calibrations.get(x);
                    publish(new CalibrationProgress(x + 1, calibrations.size(), calibration.getType().toString()));

                    try
                    {
                        calibration.calibrate();
                    }
                    catch(Exception e)
                    {
                        failures++;
                        mLog.error("CPU calibration failed for [{}]", calibration.getType(), e);
                    }

                    setProgress((int)Math.round(((double)(x + 1) / (double)calibrations.size()) * 100.0));
                }

                return failures;
            }

            @Override
            protected void process(List<CalibrationProgress> updates)
            {
                CalibrationProgress progress = updates.get(updates.size() - 1);
                mCalibrationStatusLabel.setText("Calibrating " + progress.name() + " (" + progress.current() +
                    " of " + progress.total() + ")...");
            }

            @Override
            protected void done()
            {
                mCalibrationRunning = false;
                mHideCalibrationCheckBox.setEnabled(true);

                try
                {
                    int failures = get();
                    mCalibrationProgressBar.setValue(100);
                    if(failures == 0)
                    {
                        mCalibrationStatusLabel.setForeground(new Color(0, 110, 0));
                        mCalibrationStatusLabel.setText(
                            "Calibration complete. The selected implementations will be used after restart.");
                    }
                    else
                    {
                        mCalibrationStatusLabel.setForeground(new Color(160, 80, 0));
                        mCalibrationStatusLabel.setText(failures + " calibration" + (failures == 1 ? "" : "s") +
                            " failed. You can retry later from User Preferences.");
                    }
                }
                catch(InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    showCalibrationFailure(e);
                }
                catch(ExecutionException e)
                {
                    showCalibrationFailure(e.getCause());
                }

                setButtons(button("Continue", CoordinatedStartupDialog.this::advance, true));
            }
        };
        worker.addPropertyChangeListener(event -> {
            if("progress".equals(event.getPropertyName()))
            {
                mCalibrationProgressBar.setValue((Integer)event.getNewValue());
            }
        });
        worker.execute();
    }

    private void showCalibrationFailure(Throwable throwable)
    {
        mLog.error("CPU calibration failed", throwable);
        mCalibrationStatusLabel.setForeground(Color.RED.darker());
        mCalibrationStatusLabel.setText("Calibration could not finish. You can retry later from User Preferences.");
    }

    private void startVaultTimer()
    {
        mVaultCountdown = new StartupCountdown(STARTUP_STEP_TIMEOUT_SECONDS);
        mVaultTimer = startCountdown(mVaultCountdown, countdown -> updateVaultCountdown(),
            this::disableVaultForRun);
    }

    private void resetVaultCountdown()
    {
        if(mVaultTimer != null && mVaultTimer.isRunning())
        {
            mVaultCountdown.reset();
            updateVaultCountdown();
        }
    }

    private void updateVaultCountdown()
    {
        mVaultCountdownLabel.setText("Decryption will stay disabled in " +
            mVaultCountdown.getSecondsRemaining() + " seconds.");
    }

    private void unlockVault()
    {
        stopVaultTimer();
        char[] password = mVaultPasswordField.getPassword();
        boolean savePassword = mSaveVaultPasswordCheckBox.isSelected();
        mVaultUnlockRunning = true;
        setVaultControlsEnabled(false);
        mVaultStatusLabel.setForeground(Color.DARK_GRAY);
        mVaultStatusLabel.setText("Unlocking vault...");
        setButtons();

        new SwingWorker<Void, Void>()
        {
            @Override
            protected Void doInBackground() throws EncryptionKeyVaultException
            {
                try
                {
                    mVaultService.unlock(password, savePassword);
                    return null;
                }
                finally
                {
                    Arrays.fill(password, '\0');
                }
            }

            @Override
            protected void done()
            {
                mVaultUnlockRunning = false;

                try
                {
                    get();
                    advance();
                }
                catch(InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    showVaultFailure(e);
                }
                catch(ExecutionException e)
                {
                    showVaultFailure(e.getCause());
                }
            }
        }.execute();
    }

    private void showVaultFailure(Throwable throwable)
    {
        mLog.warn("Encryption vault unlock failed", throwable);
        mVaultPasswordField.setText("");
        mVaultStatusLabel.setForeground(Color.RED.darker());
        mVaultStatusLabel.setText(throwable != null && throwable.getMessage() != null ? throwable.getMessage() :
            "The encryption key vault could not be unlocked.");
        setVaultControlsEnabled(true);
        setButtons(button("Unlock", this::unlockVault, true),
            button("Disable for This Run", this::disableVaultForRun, false));
        startVaultTimer();
        mVaultPasswordField.requestFocusInWindow();
    }

    private void setVaultControlsEnabled(boolean enabled)
    {
        mVaultPasswordField.setEnabled(enabled);
        mSaveVaultPasswordCheckBox.setEnabled(enabled);
    }

    private void disableVaultForRun()
    {
        stopVaultTimer();
        mVaultService.disableForRun();
        advance();
    }

    private void startAutoStartTimer()
    {
        int seconds = Math.clamp(mUserPreferences.getApplicationPreference().getChannelAutoStartTimeout(), 0,
            STARTUP_STEP_TIMEOUT_SECONDS);
        mAutoStartCountdown = new StartupCountdown(seconds);
        mAutoStartTimer = startCountdown(mAutoStartCountdown, countdown -> updateAutoStartCountdown(),
            this::startChannels);
    }

    private void updateAutoStartCountdown()
    {
        mAutoStartCountdownLabel.setText("The following channels will start in " +
            mAutoStartCountdown.getSecondsRemaining() + " seconds.");
    }

    private void startPassiveStepTimer(JLabel label, IntFunction<String> labelText)
    {
        mPassiveStepCountdownLabel = label;
        mPassiveStepLabelText = labelText;
        mPassiveStepCountdown = new StartupCountdown(STARTUP_STEP_TIMEOUT_SECONDS);
        mPassiveStepTimer = startCountdown(mPassiveStepCountdown,
            countdown -> label.setText(labelText.apply(countdown.getSecondsRemaining())), this::advance);
    }

    private void togglePassiveStepCountdown()
    {
        if(mPassiveStepTimer != null && mPassiveStepTimer.isRunning())
        {
            mPassiveStepTimer.stop();
            mPassiveStepTimer = null;
            mPassiveStepPauseButton.setText("Resume Countdown");
            mPassiveStepCountdownLabel.setText("Automatic startup is paused with " +
                mPassiveStepCountdown.getSecondsRemaining() + " seconds remaining.");
        }
        else if(mPassiveStepCountdown != null && !mPassiveStepCountdown.isExpired())
        {
            mPassiveStepPauseButton.setText("Pause Countdown");
            mPassiveStepTimer = startCountdown(mPassiveStepCountdown,
                countdown -> mPassiveStepCountdownLabel.setText(
                    mPassiveStepLabelText.apply(countdown.getSecondsRemaining())), this::advance);
        }
    }

    private Timer startCountdown(StartupCountdown countdown, Consumer<StartupCountdown> updateLabel,
                                 Runnable expirationAction)
    {
        updateLabel.accept(countdown);

        if(countdown.isExpired())
        {
            expirationAction.run();
            return null;
        }

        Timer timer = new Timer(1000, event -> {
            boolean expired = countdown.tick();
            updateLabel.accept(countdown);

            if(expired)
            {
                expirationAction.run();
            }
        });
        timer.start();
        return timer;
    }

    private void startChannels()
    {
        stopAutoStartTimer();

        if(mChannelsHandled.compareAndSet(false, true))
        {
            for(Channel channel: mAutoStartChannels)
            {
                try
                {
                    mChannelEventListener.receive(ChannelEvent.requestEnable(channel));
                }
                catch(RuntimeException e)
                {
                    mLog.error("Unable to auto-start channel [{}]", channel.getName(), e);
                }
            }
        }

        advance();
    }

    private void cancelAutoStart()
    {
        stopAutoStartTimer();
        mChannelsHandled.set(true);
        advance();
    }

    private void cancelRemainingStartup()
    {
        if(mCalibrationRunning || mVaultUnlockRunning)
        {
            return;
        }

        stopTimers();

        if(mVaultService != null && !mVaultService.isUnlocked())
        {
            mVaultService.disableForRun();
        }

        mChannelsHandled.set(true);
        dispose();
    }

    private void stopTimers()
    {
        stopPassiveStepTimer();
        stopVaultTimer();
        stopAutoStartTimer();
    }

    private void stopPassiveStepTimer()
    {
        if(mPassiveStepTimer != null)
        {
            mPassiveStepTimer.stop();
            mPassiveStepTimer = null;
        }

        mPassiveStepPauseButton = null;
        mPassiveStepCountdown = null;
        mPassiveStepCountdownLabel = null;
        mPassiveStepLabelText = null;
    }

    private void stopVaultTimer()
    {
        if(mVaultTimer != null)
        {
            mVaultTimer.stop();
            mVaultTimer = null;
        }
    }

    private void stopAutoStartTimer()
    {
        if(mAutoStartTimer != null)
        {
            mAutoStartTimer.stop();
            mAutoStartTimer = null;
        }
    }

    private record CalibrationProgress(int current, int total, String name) {}
}
