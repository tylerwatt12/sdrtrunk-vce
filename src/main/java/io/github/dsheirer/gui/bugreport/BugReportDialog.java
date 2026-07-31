/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.bugreport;

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fixed-scope consent, screenshot selection, submission, and offline export dialog.
 */
public class BugReportDialog extends JDialog
{
    private static final Logger mLog = LoggerFactory.getLogger(BugReportDialog.class);
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private final JFrame mOwner;
    private final UserPreferences mUserPreferences;
    private final TunerManager mTunerManager;
    private final JTextField mSummaryField = new JTextField();
    private final JTextArea mDescriptionArea = textArea(4);
    private final JTextArea mStepsArea = textArea(3);
    private final DefaultListModel<SelectedScreenshot> mScreenshotModel = new DefaultListModel<>();
    private final JList<SelectedScreenshot> mScreenshotList = new JList<>(mScreenshotModel);
    private final JButton mAddScreenshotsButton = new JButton("Add Screenshots...");
    private final JButton mRemoveScreenshotsButton = new JButton("Remove Selected");
    private final JCheckBox mConsentCheckBox = new JCheckBox(BugReportConstants.CONSENT_LABEL);
    private final JButton mSubmitButton = new JButton("Collect and Submit Report");
    private final JButton mManualSaveButton = new JButton("Save ZIP for Manual Upload...");
    private final JButton mCancelButton = new JButton("Cancel");
    private final JProgressBar mProgressBar = new JProgressBar();
    private final JLabel mStatusLabel = new JLabel(" ");
    private boolean mBusy;

    public BugReportDialog(JFrame owner, UserPreferences userPreferences, TunerManager tunerManager)
    {
        super(owner, "Submit Bug Report", true);
        mOwner = owner;
        mUserPreferences = userPreferences;
        mTunerManager = tunerManager;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter()
        {
            @Override
            public void windowClosing(WindowEvent event)
            {
                if(!mBusy)
                {
                    dispose();
                }
            }
        });
        setContentPane(createContent());
        setMinimumSize(new Dimension(780, 820));
        setPreferredSize(new Dimension(860, 900));
        pack();
        setLocationRelativeTo(owner);
        installListeners();
        updateActionState();
    }

    private JPanel createContent()
    {
        JPanel panel = new JPanel(new MigLayout("insets 16, fillx, wrap 1", "[grow,fill]"));
        panel.add(new JLabel("Describe the problem, review the screenshots, and submit or save the diagnostic ZIP."));

        panel.add(new JLabel("Short summary"));
        panel.add(mSummaryField, "growx");
        panel.add(new JLabel("What happened?"));
        panel.add(new JScrollPane(mDescriptionArea), "growx");
        panel.add(new JLabel("Steps to reproduce (enter \"Unknown\" if they are not known)"));
        panel.add(new JScrollPane(mStepsArea), "growx");

        JPanel screenshots = new JPanel(new MigLayout("insets 10, fillx, wrap 1", "[grow,fill]"));
        screenshots.setBorder(BorderFactory.createTitledBorder("Application screenshots"));
        screenshots.add(new JLabel("A current screenshot of the SDRTrunk-VCE window is always included."));
        screenshots.add(new JLabel("Add up to " + BugReportConstants.MAX_ADDITIONAL_SCREENSHOTS +
            " PNG or JPEG screenshots (15 MB each)."));
        mScreenshotList.setVisibleRowCount(3);
        mScreenshotList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        screenshots.add(new JScrollPane(mScreenshotList), "growx, h 80::");
        JPanel screenshotButtons = new JPanel(new MigLayout("insets 0", "[][]push"));
        screenshotButtons.add(mAddScreenshotsButton);
        screenshotButtons.add(mRemoveScreenshotsButton);
        screenshots.add(screenshotButtons, "growx");

        JTextArea screenshotWarning = noticeArea(BugReportConstants.SCREENSHOT_WARNING);
        screenshotWarning.setOpaque(true);
        screenshotWarning.setBackground(new Color(255, 248, 225));
        screenshotWarning.setForeground(new Color(92, 63, 12));
        screenshotWarning.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(176, 125, 32)),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        screenshots.add(screenshotWarning, "growx");
        panel.add(screenshots, "growx");

        JPanel disclosure = new JPanel(new MigLayout("insets 10, fillx, wrap 1", "[grow,fill]"));
        disclosure.setBorder(BorderFactory.createTitledBorder("Data collection and consent"));
        disclosure.add(noticeArea(BugReportConstants.DISCLOSURE), "growx");

        JLabel exclusion = new JLabel("<html><b>" + BugReportConstants.EXCLUSION_NOTICE + "</b></html>");
        exclusion.setOpaque(true);
        exclusion.setBackground(new Color(239, 247, 255));
        exclusion.setForeground(new Color(35, 70, 105));
        exclusion.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(80, 120, 160)),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        disclosure.add(exclusion, "growx");
        disclosure.add(noticeArea(BugReportConstants.RETENTION_NOTICE), "growx");
        disclosure.add(mConsentCheckBox, "growx");
        panel.add(disclosure, "growx");

        mProgressBar.setIndeterminate(true);
        mProgressBar.setVisible(false);
        panel.add(mProgressBar, "growx");
        panel.add(mStatusLabel, "growx");

        JPanel buttons = new JPanel(new MigLayout("insets 0", "push[][][]"));
        buttons.add(mCancelButton);
        buttons.add(mManualSaveButton);
        buttons.add(mSubmitButton);
        panel.add(buttons, "growx");
        return panel;
    }

    private void installListeners()
    {
        DocumentListener requiredFieldListener = new DocumentListener()
        {
            @Override
            public void insertUpdate(DocumentEvent event)
            {
                updateActionState();
            }

            @Override
            public void removeUpdate(DocumentEvent event)
            {
                updateActionState();
            }

            @Override
            public void changedUpdate(DocumentEvent event)
            {
                updateActionState();
            }
        };
        mSummaryField.getDocument().addDocumentListener(requiredFieldListener);
        mDescriptionArea.getDocument().addDocumentListener(requiredFieldListener);
        mStepsArea.getDocument().addDocumentListener(requiredFieldListener);
        mConsentCheckBox.addActionListener(event -> updateActionState());
        mScreenshotList.addListSelectionListener(event -> updateActionState());
        mAddScreenshotsButton.addActionListener(event -> addScreenshots());
        mRemoveScreenshotsButton.addActionListener(event -> removeSelectedScreenshots());
        mCancelButton.addActionListener(event -> dispose());
        mManualSaveButton.addActionListener(event -> saveForManualUpload());
        mSubmitButton.addActionListener(event -> submit());
    }

    private void updateActionState()
    {
        boolean complete = !mBusy && !mSummaryField.getText().isBlank() &&
            !mDescriptionArea.getText().isBlank() && !mStepsArea.getText().isBlank() &&
            mConsentCheckBox.isSelected();
        mSubmitButton.setEnabled(complete);
        mManualSaveButton.setEnabled(complete);
        mAddScreenshotsButton.setEnabled(!mBusy &&
            mScreenshotModel.size() < BugReportConstants.MAX_ADDITIONAL_SCREENSHOTS);
        mRemoveScreenshotsButton.setEnabled(!mBusy && !mScreenshotList.isSelectionEmpty());
    }

    private void addScreenshots()
    {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Add Screenshots");
        chooser.setMultiSelectionEnabled(true);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("PNG and JPEG images", "png", "jpg", "jpeg"));

        if(chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
        {
            return;
        }

        Set<Path> existing = new HashSet<>();

        for(int x = 0; x < mScreenshotModel.size(); x++)
        {
            existing.add(mScreenshotModel.get(x).path());
        }

        List<String> errors = new ArrayList<>();

        for(java.io.File selected: chooser.getSelectedFiles())
        {
            if(mScreenshotModel.size() >= BugReportConstants.MAX_ADDITIONAL_SCREENSHOTS)
            {
                errors.add("Only " + BugReportConstants.MAX_ADDITIONAL_SCREENSHOTS +
                    " additional screenshots can be included.");
                break;
            }

            Path path = selected.toPath().toAbsolutePath().normalize();

            if(existing.contains(path))
            {
                continue;
            }

            try
            {
                BufferedImage image = BugReportScreenshotLoader.load(path);

                if(additionalScreenshotPixels() + (long)image.getWidth() * image.getHeight() >
                    BugReportConstants.MAX_ADDITIONAL_SCREENSHOT_PIXELS)
                {
                    errors.add(path.getFileName() + ": the combined screenshot dimensions are too large.");
                    continue;
                }

                mScreenshotModel.addElement(new SelectedScreenshot(path, Files.size(path), image));
                existing.add(path);
            }
            catch(Exception e)
            {
                errors.add(path.getFileName() + ": " + e.getMessage());
            }
        }

        updateActionState();

        if(!errors.isEmpty())
        {
            JOptionPane.showMessageDialog(this, String.join("\n", errors), "Some Screenshots Were Not Added",
                JOptionPane.WARNING_MESSAGE);
        }
    }

    private long additionalScreenshotPixels()
    {
        long pixels = 0L;

        for(int x = 0; x < mScreenshotModel.size(); x++)
        {
            BufferedImage image = mScreenshotModel.get(x).image();
            pixels += (long)image.getWidth() * image.getHeight();
        }

        return pixels;
    }

    private void removeSelectedScreenshots()
    {
        int[] selected = mScreenshotList.getSelectedIndices();

        for(int x = selected.length - 1; x >= 0; x--)
        {
            mScreenshotModel.remove(selected[x]);
        }

        updateActionState();
    }

    private void submit()
    {
        BugReportRequest request = createRequest();

        if(request == null)
        {
            return;
        }

        setBusy(true, "Collecting and sanitizing diagnostic data ...");
        SwingWorker<BugReportSubmission,String> worker = new SwingWorker<>()
        {
            private BugReportBundle mBundle;

            @Override
            protected BugReportSubmission doInBackground() throws Exception
            {
                publish("Building diagnostic package ...");
                mBundle = new BugReportBundleBuilder(mUserPreferences, mTunerManager).build(request);
                publish("Uploading " + formatMegabytes(mBundle.sizeBytes()) + " MB to " +
                    BugReportConstants.DESTINATION + " ...");

                try
                {
                    return new BugReportUploader().upload(mBundle);
                }
                finally
                {
                    deleteTemporaryBundle(mBundle);
                }
            }

            @Override
            protected void process(List<String> messages)
            {
                showLatestStatus(messages);
            }

            @Override
            protected void done()
            {
                try
                {
                    BugReportSubmission submission = get();
                    showSuccess(submission);
                    dispose();
                }
                catch(InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    showSubmissionError("Bug report submission was interrupted.");
                }
                catch(ExecutionException e)
                {
                    Throwable cause = rootCause(e);
                    mLog.error("Bug report submission failed", cause);
                    showSubmissionError(errorMessage(cause));
                }
                finally
                {
                    setBusy(false, " ");
                }
            }
        };
        worker.execute();
    }

    private void saveForManualUpload()
    {
        Path destination = chooseManualDestination();

        if(destination == null)
        {
            return;
        }

        BugReportRequest request = createRequest();

        if(request == null)
        {
            return;
        }

        setBusy(true, "Collecting and sanitizing diagnostic data ...");
        SwingWorker<Path,String> worker = new SwingWorker<>()
        {
            private BugReportBundle mBundle;

            @Override
            protected Path doInBackground() throws Exception
            {
                publish("Building diagnostic package ...");
                mBundle = new BugReportBundleBuilder(mUserPreferences, mTunerManager).build(request);
                publish("Saving " + formatMegabytes(mBundle.sizeBytes()) + " MB diagnostic ZIP ...");

                try
                {
                    saveBundleAtomically(mBundle.path(), destination);
                    return destination;
                }
                finally
                {
                    deleteTemporaryBundle(mBundle);
                }
            }

            @Override
            protected void process(List<String> messages)
            {
                showLatestStatus(messages);
            }

            @Override
            protected void done()
            {
                try
                {
                    showManualSaveSuccess(get());
                    dispose();
                }
                catch(InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    showManualSaveError("Diagnostic ZIP creation was interrupted.");
                }
                catch(ExecutionException e)
                {
                    Throwable cause = rootCause(e);
                    mLog.error("Manual bug report ZIP creation failed", cause);
                    showManualSaveError(errorMessage(cause));
                }
                finally
                {
                    setBusy(false, " ");
                }
            }
        };
        worker.execute();
    }

    private BugReportRequest createRequest()
    {
        BufferedImage applicationScreenshot;

        try
        {
            applicationScreenshot = captureApplicationWindow();
        }
        catch(Exception e)
        {
            JOptionPane.showMessageDialog(this,
                "The application screenshot could not be captured: " + e.getMessage(),
                "Screenshot Capture Failed", JOptionPane.ERROR_MESSAGE);
            return null;
        }

        List<BufferedImage> additionalScreenshots = new ArrayList<>();

        for(int x = 0; x < mScreenshotModel.size(); x++)
        {
            additionalScreenshots.add(mScreenshotModel.get(x).image());
        }

        return new BugReportRequest(mSummaryField.getText(), mDescriptionArea.getText(), mStepsArea.getText(),
            ZonedDateTime.now(), applicationScreenshot, additionalScreenshots);
    }

    private Path chooseManualDestination()
    {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save Diagnostic ZIP for Manual Upload");
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("ZIP archives", "zip"));
        chooser.setSelectedFile(new java.io.File("sdrtrunk-vce-bug-report-" +
            FILE_TIMESTAMP.format(ZonedDateTime.now()) + ".zip"));

        if(chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
        {
            return null;
        }

        Path destination = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();

        if(!destination.getFileName().toString().toLowerCase().endsWith(".zip"))
        {
            destination = destination.resolveSibling(destination.getFileName() + ".zip");
        }

        if(Files.exists(destination))
        {
            int choice = JOptionPane.showConfirmDialog(this,
                "Replace the existing file?\n" + destination,
                "Replace Diagnostic ZIP", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

            if(choice != JOptionPane.YES_OPTION)
            {
                return null;
            }
        }

        return destination;
    }

    private BufferedImage captureApplicationWindow()
    {
        if(mOwner == null || mOwner.getWidth() <= 0 || mOwner.getHeight() <= 0)
        {
            throw new IllegalStateException("The main application window is unavailable.");
        }

        BufferedImage screenshot = new BufferedImage(mOwner.getWidth(), mOwner.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = screenshot.createGraphics();

        try
        {
            mOwner.printAll(graphics);
        }
        finally
        {
            graphics.dispose();
        }

        return screenshot;
    }

    private void setBusy(boolean busy, String status)
    {
        mBusy = busy;
        mProgressBar.setVisible(busy);
        mStatusLabel.setText(status);
        mCancelButton.setEnabled(!busy);
        mSummaryField.setEnabled(!busy);
        mDescriptionArea.setEnabled(!busy);
        mStepsArea.setEnabled(!busy);
        mScreenshotList.setEnabled(!busy);
        mConsentCheckBox.setEnabled(!busy);
        updateActionState();
    }

    private void showLatestStatus(List<String> messages)
    {
        if(!messages.isEmpty())
        {
            mStatusLabel.setText(messages.getLast());
        }
    }

    private void showSuccess(BugReportSubmission submission)
    {
        Object[] message = {
            "Your diagnostic report was received.",
            "Report code: " + submission.reportCode(),
            "Share this code with the SDRTrunk-VCE developer."
        };
        Object[] options = {"Copy Code", "Close"};
        int selection = JOptionPane.showOptionDialog(this, message, "Bug Report Submitted",
            JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);

        if(selection == 0)
        {
            copyToClipboard(submission.reportCode());
        }
    }

    private void showManualSaveSuccess(Path destination)
    {
        Object[] message = {
            "The diagnostic ZIP was saved:",
            destination.toString(),
            "Move it to a computer with internet access and upload it at:",
            BugReportConstants.MANUAL_UPLOAD_DESTINATION,
            "The website will validate the ZIP and return the report code."
        };
        Object[] options = {"Copy Upload Address", "Close"};
        int selection = JOptionPane.showOptionDialog(this, message, "Diagnostic ZIP Saved",
            JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);

        if(selection == 0)
        {
            copyToClipboard(BugReportConstants.MANUAL_UPLOAD_DESTINATION);
        }
    }

    private void showSubmissionError(String message)
    {
        JOptionPane.showMessageDialog(this,
            "The diagnostic report was not submitted.\n\n" + message +
                "\n\nNo diagnostic ZIP was retained. Use \"Save ZIP for Manual Upload\" if this computer " +
                "does not have internet access.",
            "Bug Report Submission Failed", JOptionPane.ERROR_MESSAGE);
    }

    private void showManualSaveError(String message)
    {
        JOptionPane.showMessageDialog(this,
            "The diagnostic ZIP could not be saved.\n\n" + message,
            "Diagnostic ZIP Save Failed", JOptionPane.ERROR_MESSAGE);
    }

    static void saveBundleAtomically(Path source, Path destination) throws IOException
    {
        Path parent = destination.getParent();

        if(parent == null || !Files.isDirectory(parent))
        {
            throw new IOException("The selected destination directory is unavailable.");
        }

        Path staged = Files.createTempFile(parent, ".sdrtrunk-vce-bug-report-", ".tmp");

        try
        {
            Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);

            try
            {
                Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            }
            catch(java.nio.file.AtomicMoveNotSupportedException e)
            {
                Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally
        {
            Files.deleteIfExists(staged);
        }
    }

    private static void deleteTemporaryBundle(BugReportBundle bundle)
    {
        if(bundle == null)
        {
            return;
        }

        try
        {
            Files.deleteIfExists(bundle.path());
        }
        catch(Exception e)
        {
            bundle.path().toFile().deleteOnExit();
            mLog.warn("Unable to immediately remove temporary bug report bundle [{}]", bundle.path(), e);
        }
    }

    private static Throwable rootCause(ExecutionException exception)
    {
        return exception.getCause() != null ? exception.getCause() : exception;
    }

    private static String errorMessage(Throwable cause)
    {
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    private static void copyToClipboard(String value)
    {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null);
    }

    private static JTextArea textArea(int rows)
    {
        JTextArea area = new JTextArea(rows, 60);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }

    private static JTextArea noticeArea(String text)
    {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFocusable(false);
        return area;
    }

    private static String formatMegabytes(long bytes)
    {
        return String.format("%.1f", bytes / (1024.0 * 1024.0));
    }

    private record SelectedScreenshot(Path path, long sourceBytes, BufferedImage image)
    {
        @Override
        public String toString()
        {
            return path.getFileName() + " — " + image.getWidth() + " × " + image.getHeight() + " — " +
                formatMegabytes(sourceBytes) + " MB";
        }
    }
}
