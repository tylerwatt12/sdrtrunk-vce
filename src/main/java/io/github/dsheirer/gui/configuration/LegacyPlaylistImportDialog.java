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
import io.github.dsheirer.database.importer.LegacyPlaylistImportService;
import io.github.dsheirer.database.importer.LegacyPlaylistImportService.ImportResult;
import io.github.dsheirer.database.importer.LegacyPlaylistImportService.PreparedImport;
import io.github.dsheirer.database.importer.LegacyXmlConfigurationMerger.ConflictPolicy;
import io.github.dsheirer.database.importer.LegacyXmlConfigurationMerger.Preview;
import io.github.dsheirer.database.importer.LegacyXmlConfigurationMerger.Summary;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import javafx.application.Platform;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Swing workflow for importing a legacy playlist XML file into the active SQLite configuration.
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
            runOnJavaFxThreadAndWait(configurationManager::flushConfiguration);
            LegacyPlaylistImportService service = new LegacyPlaylistImportService(configurationManager.getDatabasePath());
            PreparedImport preparedImport = service.prepare(chooser.getSelectedFile().toPath());
            JComboBox<ConflictPolicy> policySelector = new JComboBox<>(ConflictPolicy.values());
            policySelector.setSelectedItem(ConflictPolicy.SKIP);
            JPanel previewPanel = createPreviewPanel(preparedImport.preview(), policySelector);
            parent.setCursor(previousCursor);

            int choice = JOptionPane.showConfirmDialog(parent, previewPanel, "Import Legacy Playlist XML",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

            if(choice != JOptionPane.OK_OPTION)
            {
                return;
            }

            parent.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            ConflictPolicy policy = (ConflictPolicy)policySelector.getSelectedItem();
            ImportResult result = service.execute(preparedImport, policy);
            runOnJavaFxThreadAndWait(configurationManager::init);
            parent.setCursor(previousCursor);
            JOptionPane.showMessageDialog(parent, resultMessage(result), "Playlist Import Complete",
                JOptionPane.INFORMATION_MESSAGE);
        }
        catch(Exception | LinkageError e)
        {
            parent.setCursor(previousCursor);
            Throwable cause = rootCause(e);
            JOptionPane.showMessageDialog(parent, "The playlist was not imported.\n\n" + cause.getMessage(),
                "Playlist Import Failed", JOptionPane.ERROR_MESSAGE);
        }
        finally
        {
            parent.setCursor(previousCursor);
        }
    }

    private static JPanel createPreviewPanel(Preview preview, JComboBox<ConflictPolicy> policySelector)
    {
        JTextArea summary = new JTextArea(previewMessage(preview));
        summary.setEditable(false);
        summary.setOpaque(false);
        summary.setRows(10);
        summary.setColumns(54);
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);

        JPanel policyPanel = new JPanel(new BorderLayout(8, 0));
        policyPanel.add(new JLabel("When a name already exists:"), BorderLayout.WEST);
        policyPanel.add(policySelector, BorderLayout.CENTER);

        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.add(new JScrollPane(summary), BorderLayout.CENTER);
        panel.add(policyPanel, BorderLayout.SOUTH);
        return panel;
    }

    private static String previewMessage(Preview preview)
    {
        return "Playlist contents:\n" +
            "  Aliases: " + preview.aliasCount() + '\n' +
            "  Channels: " + preview.channelCount() + '\n' +
            "  Streaming configurations: " + preview.streamCount() + "\n\n" +
            "Existing-name conflicts:\n" +
            "  Alias lists: " + preview.aliasListConflicts() + '\n' +
            "  Channels (same system, site, and name): " + preview.channelConflicts() + '\n' +
            "  Streaming configurations: " + preview.streamConflicts() + "\n\n" +
            "A backup will be created before the import. Applying the import reloads configuration and stops " +
            "currently running channels.";
    }

    private static String resultMessage(ImportResult result)
    {
        Summary summary = result.summary();
        return "The playlist import completed.\n\n" +
            "Added: " + summary.added() + '\n' +
            "Renamed: " + summary.renamed() + '\n' +
            "Replaced: " + summary.replaced() + '\n' +
            "Skipped: " + summary.skipped() + "\n\n" +
            "Backup: " + result.backupPath();
    }

    private static void runOnJavaFxThreadAndWait(Runnable runnable)
        throws InvocationTargetException, InterruptedException, ExecutionException
    {
        if(Platform.isFxApplicationThread())
        {
            runnable.run();
            return;
        }

        FutureTask<Void> task = new FutureTask<>(runnable, null);
        Platform.runLater(task);
        task.get();
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
}
