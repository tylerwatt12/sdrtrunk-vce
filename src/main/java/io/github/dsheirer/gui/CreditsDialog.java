/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.gui;

import io.github.dsheirer.application.ApplicationInfo;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.event.HyperlinkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Project credits and software license dialog.
 */
public class CreditsDialog extends JDialog
{
    private static final Logger mLog = LoggerFactory.getLogger(CreditsDialog.class);

    public CreditsDialog(Window owner)
    {
        super(owner, "Credits & Licensing", ModalityType.APPLICATION_MODAL);
        setLayout(new BorderLayout(0, 8));
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Credits", new JScrollPane(createCreditsPane()));
        tabs.addTab("GNU GPL v3", new JScrollPane(createLicenseArea()));
        add(tabs, BorderLayout.CENTER);

        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(close);
        add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(close);
        setPreferredSize(new Dimension(760, 560));
        pack();
        setLocationRelativeTo(owner);
    }

    private JEditorPane createCreditsPane()
    {
        String applicationName = escape(ApplicationInfo.getDisplayName());
        JEditorPane pane = new JEditorPane("text/html", """
            <html><body style="font-family:sans-serif;margin:12px;color:#18212a">
            <h1 style="font-size:20px">%s</h1>
            <p><b>Copyright &copy; 2014-2026 Dennis Sheirer and respective contributors.</b></p>
            <p>sdrtrunk-vce is a modified version of <a href="https://github.com/DSheirer/sdrtrunk">SDRTrunk</a>,
            created by Dennis Sheirer. It includes work from SDRTrunk contributors and optimization and platform work
            associated with the <a href="https://github.com/bazineta/sdrtrunk">W6BAZ experimental fork</a>.</p>
            <h2 style="font-size:16px">License</h2>
            <p>This program is free software licensed under the
            <a href="https://www.gnu.org/licenses/gpl-3.0.html">GNU General Public License, version 3 or later</a>.
            It is provided without warranty. The complete license appears on the GNU GPL v3 tab.</p>
            <h2 style="font-size:16px">Open-source components</h2>
            <p>This application builds on many open-source projects, including JMBE, JavaFX, SQLite JDBC, Jackson,
            Guava, JTransforms, usb4java, ControlsFX, JIDE OSS, LAME, and the Java ecosystem around them. Each project
            remains copyright its respective authors and is distributed under its respective license.</p>
            <p>Protocol and decoder work also benefits from the broader open-source radio community, including
            <a href="https://github.com/boatbod/op25">OP25</a> and
            <a href="https://github.com/lwvmobile/dsd-fme">DSD-FME</a>.</p>
            </body></html>
            """.formatted(applicationName));
        pane.setEditable(false);
        pane.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        pane.addHyperlinkListener(event -> {
            if(event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && event.getURL() != null)
            {
                open(event.getURL().toString());
            }
        });
        pane.setCaretPosition(0);
        return pane;
    }

    private JTextArea createLicenseArea()
    {
        JTextArea area = new JTextArea(loadLicense());
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);
        area.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        return area;
    }

    private String loadLicense()
    {
        try(InputStream inputStream = CreditsDialog.class.getResourceAsStream("/GPL-3.0.txt"))
        {
            if(inputStream != null)
            {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        catch(IOException e)
        {
            mLog.warn("Unable to load bundled GNU GPL license", e);
        }

        return "GNU General Public License version 3 or later. See https://www.gnu.org/licenses/gpl-3.0.html";
    }

    private void open(String target)
    {
        try
        {
            if(Desktop.isDesktopSupported())
            {
                Desktop.getDesktop().browse(URI.create(target));
            }
        }
        catch(Exception e)
        {
            mLog.warn("Unable to open credits link [{}]", target, e);
        }
    }

    private String escape(String value)
    {
        return value == null ? "sdrtrunk-vce" : value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
