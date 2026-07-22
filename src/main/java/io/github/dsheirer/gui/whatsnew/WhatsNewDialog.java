/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.whatsnew;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Window;
import java.net.URI;
import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Displays approved rich-text release notes automatically once per public version and on demand from the Help menu.
 */
public class WhatsNewDialog extends JDialog
{
    private static final Logger mLog = LoggerFactory.getLogger(WhatsNewDialog.class);
    private static final String LAST_SHOWN_VERSION = "whats.new.last.shown.version";
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(WhatsNewDialog.class);

    private WhatsNewDialog(Window owner, ReleaseNotes releaseNotes)
    {
        super(owner, "What's New - " + releaseNotes.title(), ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setContentPane(createContent(releaseNotes));
        setPreferredSize(new Dimension(760, 680));
        setMinimumSize(new Dimension(560, 440));
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Returns the approved release notes when they have not yet been shown automatically for this version.
     */
    public static Optional<ReleaseNotes> getPendingReleaseNotes()
    {
        return ReleaseNotes.currentApproved().filter(notes ->
            ReleaseNotes.shouldShow(notes.version(), PREFERENCES.get(LAST_SHOWN_VERSION, null)));
    }

    /**
     * Records that the supplied release notes have been presented by the automatic startup experience.
     */
    public static void markShown(ReleaseNotes notes)
    {
        PREFERENCES.put(LAST_SHOWN_VERSION, notes.version());

        try
        {
            PREFERENCES.flush();
        }
        catch(BackingStoreException e)
        {
            mLog.warn("Unable to immediately save the displayed What's New version [{}]", notes.version(), e);
        }

    }

    /**
     * Shows the current approved notes without changing the first-launch marker.
     */
    public static void showCurrent(Frame owner)
    {
        ReleaseNotes.currentApproved().ifPresent(notes -> new WhatsNewDialog(owner, notes).setVisible(true));
    }

    /**
     * Indicates whether a current approved note is available for the Help menu.
     */
    public static boolean hasCurrent()
    {
        return ReleaseNotes.currentApproved().isPresent();
    }

    private JPanel createContent(ReleaseNotes releaseNotes)
    {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        panel.add(createReleaseNotesView(releaseNotes), BorderLayout.CENTER);

        JButton close = new JButton("Continue");
        close.addActionListener(event -> dispose());
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(close, BorderLayout.EAST);
        panel.add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(close);
        SwingUtilities.invokeLater(close::requestFocusInWindow);
        return panel;
    }

    /**
     * Creates the reusable release-note document used by both the Help dialog and coordinated startup experience.
     */
    public static JComponent createReleaseNotesView(ReleaseNotes releaseNotes)
    {
        JEditorPane document = new JEditorPane();
        document.setEditable(false);
        document.setContentType("text/html");
        document.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        document.setFont(UIManager.getFont("Label.font"));
        document.setEditorKit(createEditorKit(document.getFont()));
        document.setText("<html><body>" + releaseNotes.html() + "</body></html>");
        document.setCaretPosition(0);
        document.addHyperlinkListener(event -> {
            if(event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && event.getURL() != null)
            {
                openLink(document, event.getURL().toString());
            }
        });
        return new JScrollPane(document);
    }

    private static HTMLEditorKit createEditorKit(Font font)
    {
        HTMLEditorKit editorKit = new HTMLEditorKit();
        StyleSheet styleSheet = editorKit.getStyleSheet();
        String family = font != null ? font.getFamily() : "SansSerif";
        int size = font != null ? Math.max(12, font.getSize()) : 12;
        styleSheet.addRule("body { font-family: '" + family + "'; font-size: " + size + "pt; margin: 12px; }");
        styleSheet.addRule("h1 { font-size: 22pt; margin-bottom: 10px; color: #1f4f78; }");
        styleSheet.addRule("h2 { font-size: 15pt; margin-top: 18px; margin-bottom: 6px; color: #2f5f82; }");
        styleSheet.addRule("p { margin-top: 6px; margin-bottom: 10px; }");
        styleSheet.addRule("li { margin-bottom: 8px; }");
        styleSheet.addRule("code { font-family: monospace; background-color: #eeeeee; }");
        return editorKit;
    }

    private static void openLink(JComponent owner, String value)
    {
        try
        {
            if(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
            {
                Desktop.getDesktop().browse(URI.create(value));
            }
        }
        catch(Exception e)
        {
            mLog.error("Unable to open What's New link [{}]", value, e);
            JOptionPane.showMessageDialog(owner, "Unable to open this link:\n" + value, "Can't Open Link",
                JOptionPane.ERROR_MESSAGE);
        }
    }
}
