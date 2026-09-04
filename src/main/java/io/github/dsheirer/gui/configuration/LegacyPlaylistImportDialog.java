/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.gui.configuration;

import io.github.dsheirer.database.importer.LegacyPlaylistImportService;
import io.github.dsheirer.database.importer.LegacyPlaylistImportService.ImportResult;
import io.github.dsheirer.database.importer.LegacyPlaylistImportService.PreparedImport;
import io.github.dsheirer.database.importer.LegacyXmlConfigurationMerger.Preview;
import io.github.dsheirer.database.importer.LegacyXmlConfigurationMerger.Summary;
import io.github.dsheirer.database.upgrade.ApplicationMigrationProgressDialog;
import java.awt.Component;
import java.nio.file.Path;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Swing workflow for safely merging a legacy playlist XML file into the active configuration.
 */
public final class LegacyPlaylistImportDialog
{
    private LegacyPlaylistImportDialog()
    {
    }

    /** Pre-receiver entry point: preview only; the wizard applies the merge while no configuration service exists. */
    public static PreparedImport choose(Component parent, Path database, Path initialDirectory)
    {
        JFileChooser chooser = new JFileChooser(initialDirectory != null ? initialDirectory.toFile() : null);
        chooser.setDialogTitle("Import Legacy Playlist XML");
        chooser.setFileFilter(new FileNameExtensionFilter("SDRTrunk Playlist XML (*.xml)", "xml"));
        if(chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return null;
        try
        {
            java.awt.Window owner = parent instanceof java.awt.Window window ? window :
                javax.swing.SwingUtilities.getWindowAncestor(parent);
            var prepared = ApplicationMigrationProgressDialog.run(owner,
                "Review playlist import", progress -> new LegacyPlaylistImportService(database)
                    .prepare(chooser.getSelectedFile().toPath()));
            Object[] options = {"Import playlist", "Cancel"};
            return JOptionPane.showOptionDialog(parent, previewPanel(prepared.preview()), "Review playlist import",
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[1]) == 0 ? prepared : null;
        }
        catch(Exception e)
        {
            JOptionPane.showMessageDialog(parent, "The playlist could not be checked. No changes were made.\n\n" +
                message(rootCause(e)), "Playlist Import Refused", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    private static JScrollPane previewPanel(Preview preview)
    {
        JTextArea summary = new JTextArea(previewMessage(preview));
        summary.setEditable(false);
        summary.setOpaque(false);
        summary.setRows(16);
        summary.setColumns(58);
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);
        return new JScrollPane(summary);
    }

    private static String previewMessage(Preview preview)
    {
        return "Supported playlist contents:\n" +
            "  Alias lists: " + preview.aliasListCount() + '\n' +
            "  Aliases: " + preview.aliasCount() + '\n' +
            "  Channels: " + preview.channelCount() + '\n' +
            "  Streaming configurations: " + preview.streamCount() + "\n\n" +
            "Imported name conflicts that will be kept and renamed:\n" +
            "  Alias lists: " + preview.aliasListConflicts() + '\n' +
            "  Channels (same system, site, and name): " + preview.channelConflicts() + '\n' +
            "  Streaming configurations: " + preview.streamConflicts() + "\n\n" +
            "Existing configuration will not be replaced. Unsupported legacy entries are omitted. A timestamped " +
            "database backup will be created before the import. Receiving will stay stopped until you finish setup.";
    }

    public static String resultMessage(ImportResult result)
    {
        Summary summary = result.summary();
        return "The playlist import completed.\n\n" +
            "Imported alias lists: " + summary.aliasListCount() + '\n' +
            "Imported aliases: " + summary.aliasCount() + '\n' +
            "Imported channels: " + summary.channelCount() + '\n' +
            "Imported streaming configurations: " + summary.streamCount() + "\n\n" +
            "Renamed conflicts: " + summary.totalRenamed() + "\n\n" +
            "Backup: " + result.backupPath();
    }

    private static Throwable rootCause(Throwable throwable)
    {
        Throwable cause = throwable;

        while(cause.getCause() != null)
        {
            cause = cause.getCause();
        }

        return cause;
    }

    private static String message(Throwable throwable)
    {
        return throwable.getMessage() != null && !throwable.getMessage().isBlank() ?
            throwable.getMessage() : throwable.getClass().getSimpleName();
    }
}
