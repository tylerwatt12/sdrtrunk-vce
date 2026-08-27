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

package io.github.dsheirer.gui.configuration;

import io.github.dsheirer.database.upgrade.ApplicationMigrationService;
import io.github.dsheirer.database.upgrade.ApplicationMigrationProgressDialog;
import io.github.dsheirer.database.upgrade.DatabaseMigrationChain;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;

/** Selects, preflights, and confirms replacement of the active profile from a standalone SQLite database. */
public final class SqliteDatabaseImportDialog
{
    static final String WARNING_TEXT =
        "!!! WARNING: IMPORTING THIS SQLITE FILE WILL REPLACE THE ACTIVE DATABASE !!!";
    private static final String TITLE = "Import SQLite Database";

    private SqliteDatabaseImportDialog()
    {
    }

    public static PreparedImport choose(Component parent, Path activeDatabase, Path initialDirectory)
    {
        JFileChooser chooser = new JFileChooser(initialDirectory != null ? initialDirectory.toFile() : null);
        chooser.setDialogTitle(TITLE);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setFileFilter(new FileNameExtensionFilter("SDRTrunk SQLite Database (*.sqlite, *.db)",
            "sqlite", "db"));

        if(chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION)
        {
            return null;
        }

        Path source = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();

        try
        {
            if(Files.exists(activeDatabase) && Files.isSameFile(source, activeDatabase))
            {
                throw new IllegalArgumentException("Choose an older SQLite file, not the active database.");
            }

            Window owner = parent instanceof Window parentWindow ? parentWindow :
                (parent == null ? null : SwingUtilities.getWindowAncestor(parent));
            DatabaseMigrationChain.PreflightReport plan = ApplicationMigrationProgressDialog.run(owner, TITLE,
                progress -> {
                    progress.update("Checking the selected SQLite database");
                    return ApplicationMigrationService.readMigrationPlan(source);
                });
            Object[] options = {"Replace Database and Restart", "Cancel"};
            int choice = JOptionPane.showOptionDialog(parent, confirmationPanel(source, activeDatabase, plan), TITLE,
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
            return choice == 0 ? new PreparedImport(source, plan) : null;
        }
        catch(Exception e)
        {
            JOptionPane.showMessageDialog(parent,
                "The selected SQLite database cannot be imported.\n\n" + message(e),
                "SQLite Database Import Refused", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    private static JPanel confirmationPanel(Path source, Path activeDatabase,
                                            DatabaseMigrationChain.PreflightReport plan)
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JTextArea warning = warningNotice();
        panel.add(warning);
        panel.add(Box.createVerticalStrut(12));

        JTextArea details = new JTextArea(
            "The active database will be completely replaced after the selected file is copied, migrated, and " +
                "validated. This includes channels, aliases, preferences, and activity data stored in SQLite. " +
                "The current database will first be retained as a " +
                "timestamped safety backup.\n\n" +
                "Only data inside the selected SQLite file will be imported. Its neighboring vault, JMBE library, " +
                "optional modules, and other files will not be copied. Existing non-database files in the active " +
                "portable data folder will remain in place. Stored portable paths in a database-only import are " +
                "not remapped.\n\n" +
                "SDRTrunk will stop all channels and services, perform the replacement, and restart automatically." +
                "\n\nSelected SQLite database:\n" + source +
                "\n\nActive database to replace:\n" + activeDatabase.toAbsolutePath().normalize() +
                "\n\nRequired database changes:\n" + ApplicationMigrationService.describePlan(plan));
        details.setEditable(false);
        details.setOpaque(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setRows(17);
        details.setColumns(68);
        details.setCaretPosition(0);
        panel.add(new JScrollPane(details));
        return panel;
    }

    static JTextArea warningNotice()
    {
        JTextArea warning = new JTextArea(WARNING_TEXT, 2, 68);
        warning.setEditable(false);
        warning.setFocusable(false);
        warning.setOpaque(false);
        warning.setLineWrap(true);
        warning.setWrapStyleWord(true);
        warning.setForeground(errorForeground());
        warning.setFont(warning.getFont().deriveFont(Font.BOLD));
        warning.setAlignmentX(Component.LEFT_ALIGNMENT);
        return warning;
    }

    private static Color errorForeground()
    {
        Color background = UIManager.getColor("Panel.background");
        boolean dark = background != null &&
            background.getRed() * 0.2126 + background.getGreen() * 0.7152 + background.getBlue() * 0.0722 < 128;
        return dark ? new Color(255, 115, 115) : Color.RED.darker();
    }

    private static String message(Throwable throwable)
    {
        Throwable cause = throwable;

        while(cause.getCause() != null)
        {
            cause = cause.getCause();
        }

        return cause.getMessage() != null && !cause.getMessage().isBlank() ?
            cause.getMessage() : cause.getClass().getSimpleName();
    }

    public record PreparedImport(Path sourceDatabase, DatabaseMigrationChain.PreflightReport plan)
    {
    }
}
