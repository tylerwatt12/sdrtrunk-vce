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

import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.configuration.ConfigurationManager.ExternalConfigurationReloadException;
import io.github.dsheirer.database.importer.LegacyPlaylistImportService;
import io.github.dsheirer.database.importer.LegacyPlaylistImportService.ImportResult;
import io.github.dsheirer.database.importer.LegacyPlaylistImportService.PreparedImport;
import io.github.dsheirer.database.importer.LegacyXmlConfigurationMerger.Preview;
import io.github.dsheirer.database.importer.LegacyXmlConfigurationMerger.Summary;
import java.awt.Component;
import java.awt.Cursor;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import javafx.application.Platform;
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

    public static void show(Component parent, ConfigurationManager configurationManager, Path initialDirectory)
    {
        JFileChooser chooser = new JFileChooser(initialDirectory != null ? initialDirectory.toFile() : null);
        chooser.setDialogTitle("Import Legacy Playlist XML");
        chooser.setFileFilter(new FileNameExtensionFilter("SDRTrunk Playlist XML (*.xml)", "xml"));

        if(chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION)
        {
            return;
        }

        Cursor previousCursor = parent.getCursor();
        parent.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        try
        {
            callOnJavaFxThreadAndWait(() -> {
                configurationManager.flushConfiguration();
                return null;
            });
            LegacyPlaylistImportService service =
                new LegacyPlaylistImportService(configurationManager.getDatabasePath());
            PreparedImport preparedImport = service.prepare(chooser.getSelectedFile().toPath());
            parent.setCursor(previousCursor);

            Object[] options = {"Import", "Cancel"};
            int choice = JOptionPane.showOptionDialog(parent, previewPanel(preparedImport.preview()),
                "Import Legacy Playlist XML", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null,
                options, options[0]);

            if(choice != 0)
            {
                return;
            }

            parent.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            ImportResult result;

            try
            {
                result = callOnJavaFxThreadAndWait(() ->
                    configurationManager.applyExternalConfigurationSnapshot(() ->
                        service.execute(preparedImport)));
            }
            catch(ExternalConfigurationReloadException reloadError)
            {
                parent.setCursor(previousCursor);
                JOptionPane.showMessageDialog(parent,
                    "The playlist was imported, but the running interface could not reload it.\n\n" +
                        "Restart SDRTrunk before making configuration changes.\n\nBackups: " +
                        configurationManager.getDatabasePath().getParent().resolve("backups") +
                        "\n\n" + message(rootCause(reloadError)),
                    "Playlist Imported — Restart Required", JOptionPane.WARNING_MESSAGE);
                return;
            }

            parent.setCursor(previousCursor);
            JOptionPane.showMessageDialog(parent, resultMessage(result), "Playlist Import Complete",
                JOptionPane.INFORMATION_MESSAGE);
        }
        catch(Exception | LinkageError e)
        {
            parent.setCursor(previousCursor);
            JOptionPane.showMessageDialog(parent, "The playlist was not imported.\n\n" +
                message(rootCause(e)), "Playlist Import Failed", JOptionPane.ERROR_MESSAGE);
        }
        finally
        {
            parent.setCursor(previousCursor);
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
            "database backup will be created before the import. Applying the import reloads configuration and stops " +
            "currently running channels.";
    }

    private static String resultMessage(ImportResult result)
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

    private static <T> T callOnJavaFxThreadAndWait(Callable<T> callable)
        throws Exception
    {
        if(Platform.isFxApplicationThread())
        {
            return callable.call();
        }

        FutureTask<T> task = new FutureTask<>(callable);
        Platform.runLater(task);
        try
        {
            return task.get();
        }
        catch(ExecutionException e)
        {
            Throwable cause = e.getCause();

            if(cause instanceof Exception exception)
            {
                throw exception;
            }

            if(cause instanceof Error error)
            {
                throw error;
            }

            throw e;
        }
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
