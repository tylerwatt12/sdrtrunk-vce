/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/** Fixed-size error surface with optional details and a complete clipboard report. */
public final class CopyableErrorDialog
{
    private CopyableErrorDialog()
    {
    }

    public static void show(Component owner, String title, String summary, String details)
    {
        Objects.requireNonNull(title, "Error title cannot be null");
        Objects.requireNonNull(summary, "Error summary cannot be null");
        String safeDetails = details == null || details.isBlank() ? summary : details;
        Runnable display = () -> showOnEventThread(owner, title, summary, safeDetails,
            value -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null));
        if(SwingUtilities.isEventDispatchThread())
        {
            display.run();
            return;
        }
        try
        {
            SwingUtilities.invokeAndWait(display);
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        catch(InvocationTargetException | RuntimeException e)
        {
            System.err.println(title + ": " + safeDetails);
        }
    }

    private static void showOnEventThread(Component owner, String title, String summary, String details,
                                          ClipboardWriter clipboardWriter)
    {
        Window window = owner instanceof Window ownerWindow ? ownerWindow :
            (owner == null ? null : SwingUtilities.getWindowAncestor(owner));
        JDialog dialog = new JDialog(window, title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        ErrorPanel panel = new ErrorPanel(summary, details, clipboardWriter, dialog::dispose, () -> {
            dialog.pack();
            dialog.setLocationRelativeTo(window);
        });
        dialog.setContentPane(panel);
        dialog.getRootPane().setDefaultButton(panel.closeButton());
        dialog.pack();
        dialog.setLocationRelativeTo(window);
        dialog.setVisible(true);
    }

    public static String message(Throwable throwable)
    {
        Throwable cause = throwable;
        while(cause != null && cause.getCause() != null)
        {
            cause = cause.getCause();
        }
        if(cause == null)
        {
            return "Unknown error";
        }
        return cause.getMessage() != null && !cause.getMessage().isBlank() ? cause.getMessage() :
            cause.getClass().getSimpleName();
    }

    /** Separate panel keeps sizing, disclosure, and clipboard behavior testable without a display. */
    static final class ErrorPanel extends JPanel
    {
        private final String mReport;
        private final JButton mDetailsButton = new JButton("Show details");
        private final JButton mCopyButton = new JButton("Copy error");
        private final JButton mCloseButton = new JButton("Close");
        private final JLabel mCopyStatus = new JLabel(" ");
        private final JScrollPane mDetails;

        ErrorPanel(String summary, String details, ClipboardWriter clipboardWriter, Runnable closeAction,
                   Runnable resizeAction)
        {
            super(new BorderLayout(0, 12));
            setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
            mReport = summary + (details.equals(summary) ? "" : "\n\nTechnical details:\n" + details);

            JTextArea summaryArea = text(summary);
            summaryArea.setRows(3);
            summaryArea.setColumns(58);
            summaryArea.setFocusable(false);
            add(summaryArea, BorderLayout.NORTH);

            JTextArea detailArea = text(details);
            detailArea.setRows(8);
            detailArea.setColumns(72);
            detailArea.setCaretPosition(0);
            mDetails = new JScrollPane(detailArea);
            mDetails.setPreferredSize(new Dimension(640, 170));
            mDetails.setVisible(false);
            add(mDetails, BorderLayout.CENTER);

            mDetailsButton.addActionListener(event -> {
                mDetails.setVisible(!mDetails.isVisible());
                mDetailsButton.setText(mDetails.isVisible() ? "Hide details" : "Show details");
                resizeAction.run();
            });
            mCopyButton.addActionListener(event -> {
                try
                {
                    clipboardWriter.write(mReport);
                    mCopyButton.setText("Copied");
                    mCopyStatus.setText("Full error copied.");
                }
                catch(Exception e)
                {
                    Toolkit.getDefaultToolkit().beep();
                    mCopyButton.setText("Copy error");
                    mCopyStatus.setText("The error could not be copied. Try again.");
                }
            });
            mCloseButton.addActionListener(event -> closeAction.run());

            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            actions.add(mDetailsButton);
            actions.add(mCopyButton);
            actions.add(mCloseButton);
            JPanel footer = new JPanel(new BorderLayout(8, 0));
            footer.add(mCopyStatus, BorderLayout.CENTER);
            footer.add(actions, BorderLayout.EAST);
            add(footer, BorderLayout.SOUTH);
        }

        private static JTextArea text(String value)
        {
            JTextArea area = new JTextArea(value);
            area.setEditable(false);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setOpaque(false);
            return area;
        }

        JButton detailsButton() { return mDetailsButton; }
        JButton copyButton() { return mCopyButton; }
        JButton closeButton() { return mCloseButton; }
        boolean detailsVisible() { return mDetails.isVisible(); }
        String report() { return mReport; }
        String copyStatus() { return mCopyStatus.getText(); }
    }

    @FunctionalInterface
    interface ClipboardWriter
    {
        void write(String value) throws Exception;
    }
}
