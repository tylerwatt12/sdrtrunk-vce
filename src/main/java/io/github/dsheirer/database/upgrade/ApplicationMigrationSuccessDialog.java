/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.database.upgrade;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

/**
 * Non-blocking-for-the-operator completion report for Application Migrator workflows.
 *
 * <p>The dialog remains modal only for a short, visible countdown. The operator can continue immediately or copy the
 * complete immutable report without stopping the automatic continuation.</p>
 */
public final class ApplicationMigrationSuccessDialog
{
    static final int AUTO_ACCEPT_SECONDS = 10;

    private ApplicationMigrationSuccessDialog()
    {
    }

    public static void show(Component owner, String title, String report)
    {
        Objects.requireNonNull(title, "Dialog title cannot be null");
        Objects.requireNonNull(report, "Migration report cannot be null");
        Runnable display = () -> showOnEventThread(owner, title, report, AUTO_ACCEPT_SECONDS,
            value -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(value), null));

        try
        {
            if(SwingUtilities.isEventDispatchThread())
            {
                display.run();
            }
            else
            {
                SwingUtilities.invokeAndWait(display);
            }
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
            System.err.println("Database migration succeeded, but its completion report was interrupted.");
        }
        catch(InvocationTargetException e)
        {
            System.err.println("Database migration succeeded, but its completion report could not be shown: " +
                message(e.getCause()));
        }
        catch(RuntimeException e)
        {
            System.err.println("Database migration succeeded, but its completion report could not be shown: " +
                message(e));
        }
    }

    private static void showOnEventThread(Component owner, String title, String report, int seconds,
                                          ClipboardWriter clipboardWriter)
    {
        Window window = owner instanceof Window ownerWindow ? ownerWindow :
            (owner == null ? null : SwingUtilities.getWindowAncestor(owner));
        JDialog dialog = new JDialog(window, title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        Timer[] timer = new Timer[1];
        CompletionPanel content = new CompletionPanel(report, seconds, clipboardWriter, () -> {
            if(timer[0] != null)
            {
                timer[0].stop();
            }
            dialog.dispose();
        });
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(content.okButton());
        dialog.addWindowListener(new WindowAdapter()
        {
            @Override
            public void windowOpened(WindowEvent event)
            {
                content.okButton().requestFocusInWindow();
            }
        });
        dialog.pack();
        dialog.setLocationRelativeTo(window);

        timer[0] = new Timer(1000, event -> content.tick());

        if(seconds <= 0)
        {
            content.accept();
            return;
        }

        timer[0].start();

        try
        {
            dialog.setVisible(true);
        }
        finally
        {
            timer[0].stop();
            dialog.dispose();
        }
    }

    private static String message(Throwable throwable)
    {
        return throwable != null && throwable.getMessage() != null && !throwable.getMessage().isBlank() ?
            throwable.getMessage() : (throwable == null ? "unknown error" : throwable.getClass().getSimpleName());
    }

    public static String currentDatabaseReport(ApplicationMigrationService.MigrationResult migration)
    {
        return "Your database was migrated successfully.\n\n" + migration.helperOutput() +
            "\n\nSafety backup:\n" + migration.safetyBackup();
    }

    public static String previousImportReport(ApplicationMigrationService.MigrationResult migration)
    {
        return migration.inputScope() == PreviousBuildLocator.InputScope.PORTABLE_PROFILE ?
            "Migration complete. The portable profile was copied and its stored paths were remapped. Your " +
                "previous installation and its data were left unchanged.\n\n" + migration.helperOutput() :
            "Migration complete. Only the selected SQLite database was imported. The source database and its " +
                "neighboring files were left unchanged.\n\n" + migration.helperOutput();
    }

    public static String replacementImportReport(ApplicationMigrationService.MigrationResult migration,
                                                 Path sourceDatabase)
    {
        return "SQLite database import complete. The selected database replaced the active database after staged " +
            "migration and validation. The selected source file and its neighboring files were left unchanged.\n\n" +
            "Stored portable paths were not remapped. If the imported database did not contain an administrator, " +
            "setup will request a new administrator password after restart.\n\n" + migration.helperOutput() +
            "\n\nSelected source:\n" + sourceDatabase.toAbsolutePath().normalize() +
            "\n\nPrevious active database backup:\n" + migration.safetyBackup() +
            "\n\nSDRTrunk will restart automatically.";
    }

    /** Lightweight view kept separate from the top-level window so button and countdown wiring is testable headless. */
    static final class CompletionPanel extends JPanel
    {
        private final JLabel mCountdownStatus = new JLabel();
        private final JLabel mCopyStatus = new JLabel(" ");
        private final JButton mCopyButton = new JButton("Copy Message");
        private final JButton mOkButton = new JButton("OK");
        private final CountdownController mController;

        CompletionPanel(String report, int seconds, ClipboardWriter clipboardWriter, Runnable acceptAction)
        {
            super(new BorderLayout(0, 12));
            setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));

            JTextArea message = new JTextArea(report);
            message.setEditable(false);
            message.setLineWrap(true);
            message.setWrapStyleWord(true);
            message.setCaretPosition(0);
            JScrollPane scrollPane = new JScrollPane(message);
            scrollPane.setPreferredSize(new Dimension(690, 300));
            add(scrollPane, BorderLayout.CENTER);

            mController = new CountdownController(report, seconds, clipboardWriter, acceptAction);
            mOkButton.addActionListener(event -> mController.accept());
            mCopyButton.addActionListener(event -> copy());

            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            actions.add(mCopyButton);
            actions.add(mOkButton);
            JPanel status = new JPanel(new BorderLayout(0, 2));
            status.add(mCountdownStatus, BorderLayout.NORTH);
            status.add(mCopyStatus, BorderLayout.SOUTH);
            JPanel footer = new JPanel(new BorderLayout(10, 0));
            footer.add(status, BorderLayout.CENTER);
            footer.add(actions, BorderLayout.EAST);
            add(footer, BorderLayout.SOUTH);
            updateCountdownStatus();
        }

        void tick()
        {
            mController.tick();
            updateCountdownStatus();
        }

        void accept()
        {
            mController.accept();
        }

        JButton copyButton()
        {
            return mCopyButton;
        }

        JButton okButton()
        {
            return mOkButton;
        }

        String countdownStatus()
        {
            return mCountdownStatus.getText();
        }

        String copyStatus()
        {
            return mCopyStatus.getText();
        }

        private void copy()
        {
            try
            {
                mController.copy();
                mCopyStatus.setText("Full message copied.");
                mCopyButton.setText("Copied");
            }
            catch(Exception e)
            {
                Toolkit.getDefaultToolkit().beep();
                mCopyStatus.setText("The message could not be copied. Try again.");
                mCopyButton.setText("Copy Message");
            }
        }

        private void updateCountdownStatus()
        {
            int seconds = mController.secondsRemaining();
            mCountdownStatus.setText(seconds == 0 ? "Continuing automatically now." :
                "Continuing automatically in " + seconds + (seconds == 1 ? " second." : " seconds."));
        }
    }

    @FunctionalInterface
    interface ClipboardWriter
    {
        void write(String value) throws Exception;
    }

    static final class CountdownController
    {
        private final String mReport;
        private final ClipboardWriter mClipboardWriter;
        private final Runnable mAcceptAction;
        private int mSecondsRemaining;
        private boolean mAccepted;

        CountdownController(String report, int seconds, ClipboardWriter clipboardWriter, Runnable acceptAction)
        {
            mReport = Objects.requireNonNull(report);
            mSecondsRemaining = Math.max(0, seconds);
            mClipboardWriter = Objects.requireNonNull(clipboardWriter);
            mAcceptAction = Objects.requireNonNull(acceptAction);
        }

        int secondsRemaining()
        {
            return mSecondsRemaining;
        }

        boolean isAccepted()
        {
            return mAccepted;
        }

        void tick()
        {
            if(mAccepted)
            {
                return;
            }

            if(mSecondsRemaining > 0)
            {
                mSecondsRemaining--;
            }

            if(mSecondsRemaining == 0)
            {
                accept();
            }
        }

        void copy() throws Exception
        {
            mClipboardWriter.write(mReport);
        }

        void accept()
        {
            if(!mAccepted)
            {
                mAccepted = true;
                mAcceptAction.run();
            }
        }
    }
}
