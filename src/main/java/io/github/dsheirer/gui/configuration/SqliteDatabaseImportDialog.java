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
import io.github.dsheirer.database.upgrade.DatabaseMigrationEffect;
import io.github.dsheirer.database.upgrade.MigrationContent;
import io.github.dsheirer.gui.CopyableErrorDialog;
import io.github.dsheirer.gui.setup.MigrationContentChoices;
import io.github.dsheirer.gui.setup.SetupPage;
import io.github.dsheirer.gui.setup.SetupText;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Window;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
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
    static final String WARNING_TEXT = "This will replace this installation’s saved settings.";
    private static final String TITLE = "Import saved settings";

    private SqliteDatabaseImportDialog()
    {
    }

    /** Inspects a selected file without changing either database; callers can review it inline. */
    public static PreparedImport inspect(Path source, Path activeDatabase) throws IOException, SQLException
    {
        Path selected = source.toAbsolutePath().normalize();
        Path active = activeDatabase.toAbsolutePath().normalize();
        if(Files.exists(active) && Files.isSameFile(selected, active))
            throw new IOException("Choose a different file, not this installation’s current database.");
        return new PreparedImport(selected, ApplicationMigrationService.readMigrationApproval(selected, active.getParent()));
    }

    public static PreparedImport choose(Component parent, Path activeDatabase, Path initialDirectory)
    {
        JFileChooser chooser = new JFileChooser(initialDirectory != null ? initialDirectory.toFile() : null);
        chooser.setDialogTitle(TITLE);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setFileFilter(new FileNameExtensionFilter("VCE SQLite Database (*.sqlite, *.db)",
            "sqlite", "db"));

        if(chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION)
        {
            return null;
        }

        Path source = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();

        try
        {
            Window owner = parent instanceof Window parentWindow ? parentWindow :
                (parent == null ? null : SwingUtilities.getWindowAncestor(parent));
            PreparedImport inspected =
                ApplicationMigrationProgressDialog.run(owner, TITLE,
                progress -> {
                    progress.update("Checking the selected settings");
                    return inspect(source, activeDatabase);
                });
            var approval = inspected.approval();
            ImportReview review = confirmationPanel(source, activeDatabase, approval.plan());
            Object[] options = {"Replace settings and restart", "Cancel"};
            int choice = JOptionPane.showOptionDialog(parent,
                review.panel(), TITLE,
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
            return choice == 0 ? new PreparedImport(source, approval.withContent(review.content())) : null;
        }
        catch(Exception e)
        {
            CopyableErrorDialog.show(parent, "Settings could not be imported",
                "The selected file cannot be imported. Your current settings are unchanged.", message(e));
            return null;
        }
    }

    public static ImportReview confirmationPanel(Path source, Path activeDatabase,
                                         DatabaseMigrationChain.PreflightReport plan)
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JTextArea warning = warningNotice();
        panel.add(warning);
        panel.add(Box.createVerticalStrut(12));

        JTextArea details = new SetupText(confirmationText(source, activeDatabase, plan));
        details.setAlignmentX(Component.LEFT_ALIGNMENT);
        details.setEditable(false);
        details.setOpaque(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setRows(9);
        details.setColumns(68);
        details.setCaretPosition(0);
        JPanel body = new SetupPage();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        MigrationContentChoices choices = null;
        if(MigrationContentChoices.isAvailable(plan))
        {
            choices = new MigrationContentChoices(source, MigrationContent.FULL, ignored -> { });
            body.add(choices);
            body.add(Box.createVerticalStrut(12));
        }
        body.add(details);
        JScrollPane scroll = new JScrollPane(body);
        Dimension size = details.getPreferredSize();
        scroll.setPreferredSize(new Dimension(Math.min(720, size.width + 24), Math.min(480, size.height + 24)));
        panel.add(scroll);
        return new ImportReview(panel, choices);
    }

    private static String confirmationText(Path source, Path activeDatabase,
                                           DatabaseMigrationChain.PreflightReport plan)
    {
        boolean resets = plan.steps().stream().flatMap(step -> step.effects().stream())
            .anyMatch(effect -> effect.kind() == DatabaseMigrationEffect.Kind.RESET);
        return "From: " + source + "\nReplacing: " + activeDatabase.toAbsolutePath().normalize() +
            "\n\nA backup of your current settings is kept first. Channels, aliases, accounts, and settings " +
            "will then come from this file." + (resets ? " Some older data may start fresh." : "") +
            "\n\nOnly this database is imported. Recordings, vaults, digital audio tools, and other files " +
            "are not copied. Existing files and saved output folders stay in place." +
            "\n\nVCE restarts setup for review. You may need a new administrator password.";
    }

    public record ImportReview(JPanel panel, MigrationContentChoices choices)
    {
        public MigrationContent content() { return choices == null ? MigrationContent.FULL : choices.selectedContent(); }
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

    public record PreparedImport(Path sourceDatabase, ApplicationMigrationService.ApprovedMigrationPlan approval)
    {
    }
}
