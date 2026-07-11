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

package io.github.dsheirer.database;

import io.github.dsheirer.database.importer.LegacyXmlConfigurationImporter;
import io.github.dsheirer.portable.PortableApplicationPaths;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultPath;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Single startup workflow for creating, importing, and validating portable SDRTrunk databases.
 */
public final class SdrTrunkDatabaseBootstrap
{
    private static final String TITLE = "sdrtrunk-vce Setup";

    private SdrTrunkDatabaseBootstrap()
    {
    }

    public static boolean run(String[] args) throws IOException, SQLException
    {
        Path dataRoot = PortableApplicationPaths.getDataRoot();
        Path databasePath = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Options options = Options.parse(args);

        if(Files.isRegularFile(databasePath))
        {
            SdrTrunkDatabaseStartup.validateGlobalDatabase(databasePath);
            prepareVault(dataRoot);
            return true;
        }

        if(options.fresh())
        {
            createFresh(databasePath);
        }
        else if(options.importXml() != null)
        {
            LegacyXmlConfigurationImporter.importPlaylist(options.importXml(), databasePath);
        }
        else if(GraphicsEnvironment.isHeadless())
        {
            throw new IOException("No portable SDRTrunk database exists at " + databasePath +
                ". Start once with --fresh or --import-xml <path>.");
        }
        else if(!runInteractive(databasePath))
        {
            return false;
        }

        prepareVault(dataRoot);
        return true;
    }

    private static boolean runInteractive(Path databasePath)
    {
        Optional<Path> discovered = LegacyXmlConfigurationImporter.discoverPlaylist(
            PortableApplicationPaths.getLegacyApplicationRoot());

        while(true)
        {
            Choice choice = discovered.isPresent() ? chooseWithLegacy(discovered.get()) : chooseWithoutLegacy();

            if(choice.action() == Action.QUIT)
            {
                return false;
            }

            try
            {
                switch(choice.action())
                {
                    case FRESH -> createFresh(databasePath);
                    case IMPORT -> LegacyXmlConfigurationImporter.importPlaylist(choice.xml(), databasePath);
                    case BROWSE ->
                    {
                        Path selected = browse(discovered.orElse(PortableApplicationPaths.getLegacyApplicationRoot()));

                        if(selected == null)
                        {
                            continue;
                        }

                        LegacyXmlConfigurationImporter.importPlaylist(selected, databasePath);
                    }
                    case QUIT -> { return false; }
                }

                return true;
            }
            catch(Exception e)
            {
                JOptionPane.showMessageDialog(null,
                    "The SDRTrunk database could not be created.\n\n" + e.getMessage(), TITLE,
                    JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private static Choice chooseWithLegacy(Path xml)
    {
        Object[] buttons = {"Upgrade", "Start Fresh", "Browse...", "Quit"};
        int result = JOptionPane.showOptionDialog(null,
            "A previous SDRTrunk installation was found. Would you like to import it into sdrtrunk-vce?\n\n" + xml,
            TITLE, JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, buttons, buttons[0]);

        return switch(result)
        {
            case 0 -> new Choice(Action.IMPORT, xml);
            case 1 -> new Choice(Action.FRESH, null);
            case 2 -> new Choice(Action.BROWSE, null);
            default -> new Choice(Action.QUIT, null);
        };
    }

    private static Choice chooseWithoutLegacy()
    {
        Object[] buttons = {"Start Fresh", "Browse...", "Quit"};
        int result = JOptionPane.showOptionDialog(null,
            "No previous SDRTrunk installation was found. Would you like to create a fresh sdrtrunk-vce database?",
            TITLE, JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, buttons, buttons[0]);

        return switch(result)
        {
            case 0 -> new Choice(Action.FRESH, null);
            case 1 -> new Choice(Action.BROWSE, null);
            default -> new Choice(Action.QUIT, null);
        };
    }

    private static Path browse(Path initialPath)
    {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select an SDRTrunk playlist XML file");
        chooser.setFileFilter(new FileNameExtensionFilter("SDRTrunk XML playlist (*.xml)", "xml"));
        Path initialDirectory = Files.isDirectory(initialPath) ? initialPath : initialPath.getParent();

        if(initialDirectory != null && Files.isDirectory(initialDirectory))
        {
            chooser.setCurrentDirectory(initialDirectory.toFile());
        }

        return chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().toPath() : null;
    }

    private static void createFresh(Path databasePath) throws IOException, SQLException
    {
        DatabaseFileInstaller.install(databasePath, SdrTrunkDatabaseStartup::createGlobalDatabase);
    }

    private static void prepareVault(Path dataRoot) throws IOException, SQLException
    {
        Path vault = EncryptionKeyVaultPath.getVaultPath(dataRoot);

        if(Files.isRegularFile(vault))
        {
            SdrTrunkDatabaseStartup.validateVaultDatabase(vault);
        }
        else
        {
            DatabaseFileInstaller.install(vault, SdrTrunkDatabaseStartup::createVaultDatabase);
        }
    }

    private enum Action { FRESH, IMPORT, BROWSE, QUIT }

    private record Choice(Action action, Path xml) {}

    private record Options(boolean fresh, Path importXml)
    {
        private static Options parse(String[] args)
        {
            boolean fresh = false;
            Path importXml = null;

            for(int x = 0; x < args.length; x++)
            {
                switch(args[x])
                {
                    case "--fresh" -> fresh = true;
                    case "--import-xml" ->
                    {
                        if(++x >= args.length)
                        {
                            throw new IllegalArgumentException("Missing XML path after --import-xml");
                        }

                        importXml = Path.of(args[x]);
                    }
                    default -> { /* Other SDRTrunk arguments belong to their existing owners. */ }
                }
            }

            if(fresh && importXml != null)
            {
                throw new IllegalArgumentException("Use --fresh or --import-xml, not both");
            }

            return new Options(fresh, importXml);
        }
    }
}
