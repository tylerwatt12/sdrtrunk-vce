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
import io.github.dsheirer.database.upgrade.PreviousBuildLocator;
import io.github.dsheirer.database.upgrade.PreviousBuildUpgradeService;
import io.github.dsheirer.database.upgrade.UpgradeProgressDialog;
import io.github.dsheirer.portable.PortableApplicationPaths;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultPath;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Single startup workflow for creating, importing, upgrading, and validating portable SDRTrunk databases.
 */
public final class SdrTrunkDatabaseBootstrap
{
    private static final String TITLE = "sdrtrunk-vce Setup";
    private static final String UPGRADE_TITLE = "sdrtrunk-vce Upgrade Assistant";

    private SdrTrunkDatabaseBootstrap()
    {
    }

    public static BootstrapResult run(String[] args) throws IOException, SQLException, InterruptedException
    {
        Path dataRoot = PortableApplicationPaths.getDataRoot();
        Path databasePath = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Options options = Options.parse(args);

        if(Files.isRegularFile(databasePath))
        {
            if(options.upgradeData() != null)
            {
                throw new IOException("The current portable data folder already has a database. Remove " +
                    "--upgrade-data or choose a new data folder.");
            }

            int version = PreviousBuildUpgradeService.readP25ActivitySchemaVersion(databasePath);
            boolean upgradeRequired =
                PreviousBuildUpgradeService.requiresCurrentUpgrade(databasePath, version);

            if(upgradeRequired)
            {
                if(GraphicsEnvironment.isHeadless() && !options.upgradeCurrent())
                {
                    throw new IOException("The portable database requires a staged schema update. Start once with " +
                        "--upgrade-current to create a safety backup, update a copied database, and install it only " +
                        "after validation.");
                }

                if(!options.upgradeCurrent() && !confirmCurrentUpgrade(databasePath))
                {
                    return BootstrapResult.cancelled();
                }

                PreviousBuildUpgradeService service = new PreviousBuildUpgradeService();
                PreviousBuildUpgradeService.UpgradeResult result;

                if(GraphicsEnvironment.isHeadless())
                {
                    result = service.upgradeCurrent(dataRoot, System.out::println);
                }
                else
                {
                    try
                    {
                        result = UpgradeProgressDialog.run(null, UPGRADE_TITLE,
                            progress -> service.upgradeCurrent(dataRoot, progress));
                    }
                    catch(InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    catch(IOException | SQLException e)
                    {
                        throw e;
                    }
                    catch(Exception e)
                    {
                        throw new IOException("Database upgrade failed: " + message(e), e);
                    }

                    JOptionPane.showMessageDialog(null,
                        "Your database was upgraded successfully.\n\nSafety backup:\n" + result.safetyBackup(),
                        UPGRADE_TITLE, JOptionPane.INFORMATION_MESSAGE);
                }
            }
            else if(options.upgradeCurrent())
            {
                throw new IOException("--upgrade-current supports P25 activity database " +
                    PreviousBuildUpgradeService.supportedSourceVersionsLabel() +
                    ", or v21 when its recorded-call catalog is wholly absent. Found v" + version +
                    " with no supported pending upgrade.");
            }

            SdrTrunkDatabaseStartup.validateGlobalDatabase(databasePath);
            prepareVault(dataRoot);
            return BootstrapResult.existingProfile();
        }

        if(options.upgradeCurrent())
        {
            throw new IOException("--upgrade-current requires an existing portable database at " + databasePath);
        }

        BootstrapResult result;

        if(options.fresh())
        {
            createFresh(databasePath);
            result = BootstrapResult.newPreferences();
        }
        else if(options.importXml() != null)
        {
            LegacyXmlConfigurationImporter.importPlaylist(options.importXml(), databasePath);
            result = BootstrapResult.newPreferences();
        }
        else if(options.upgradeData() != null)
        {
            Path source = PreviousBuildLocator.resolveSelection(options.upgradeData()).orElseThrow(() ->
                new IOException("The selected location does not contain portable sdrtrunk-vce data: " +
                    options.upgradeData()));
            new PreviousBuildUpgradeService().importPrevious(source, dataRoot, System.out::println);
            result = BootstrapResult.existingProfile();
        }
        else if(GraphicsEnvironment.isHeadless())
        {
            throw new IOException("No portable SDRTrunk database exists at " + databasePath +
                ". Start once with --fresh, --import-xml <path>, or --upgrade-data <previous-folder>.");
        }
        else
        {
            result = runInteractive(databasePath, dataRoot);

            if(!result.startApplication())
            {
                return result;
            }
        }

        prepareVault(dataRoot);
        return result;
    }

    private static BootstrapResult runInteractive(Path databasePath, Path dataRoot)
    {
        List<Path> previousBuilds = PreviousBuildLocator.discover();
        Optional<Path> legacyXml = LegacyXmlConfigurationImporter.discoverPlaylist(
            PortableApplicationPaths.getLegacyApplicationRoot());

        while(true)
        {
            Choice choice = chooseSetup(previousBuilds, legacyXml);

            if(choice.action() == Action.QUIT)
            {
                return BootstrapResult.cancelled();
            }

            try
            {
                switch(choice.action())
                {
                    case USE_DISCOVERED_PREVIOUS ->
                    {
                        Path source = chooseDiscoveredPrevious(previousBuilds);

                        if(source == null)
                        {
                            continue;
                        }

                        if(!importPrevious(source, dataRoot))
                        {
                            continue;
                        }

                        return BootstrapResult.existingProfile();
                    }
                    case BROWSE_PREVIOUS ->
                    {
                        Path selected = browsePrevious(PortableApplicationPaths.getInstallRoot());

                        if(selected == null)
                        {
                            continue;
                        }

                        Optional<Path> source = PreviousBuildLocator.resolveSelection(selected);

                        if(source.isEmpty())
                        {
                            JOptionPane.showMessageDialog(null,
                                "That location does not contain database/" +
                                    SdrTrunkDatabasePath.DATABASE_FILENAME + ".\n\nChoose the previous app, " +
                                    "install folder, data folder, or SQLite database.",
                                UPGRADE_TITLE, JOptionPane.WARNING_MESSAGE);
                            continue;
                        }

                        if(!importPrevious(source.get(), dataRoot))
                        {
                            continue;
                        }

                        return BootstrapResult.existingProfile();
                    }
                    case IMPORT_DISCOVERED_XML ->
                    {
                        LegacyXmlConfigurationImporter.importPlaylist(legacyXml.orElseThrow(), databasePath);
                        return BootstrapResult.newPreferences();
                    }
                    case BROWSE_XML ->
                    {
                        Path selected = browseXml(legacyXml.orElse(PortableApplicationPaths.getLegacyApplicationRoot()));

                        if(selected == null)
                        {
                            continue;
                        }

                        LegacyXmlConfigurationImporter.importPlaylist(selected, databasePath);
                        return BootstrapResult.newPreferences();
                    }
                    case FRESH ->
                    {
                        createFresh(databasePath);
                        return BootstrapResult.newPreferences();
                    }
                    case QUIT -> { return BootstrapResult.cancelled(); }
                }
            }
            catch(Exception e)
            {
                JOptionPane.showMessageDialog(null,
                    "Setup could not finish. Previous data was left unchanged and the new app did not start.\n\n" +
                        message(e), TITLE, JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private static Choice chooseSetup(List<Path> previousBuilds, Optional<Path> legacyXml)
    {
        if(!previousBuilds.isEmpty())
        {
            Object[] buttons = {"Upgrade Using Previous Data", "Choose Another...", "Import Older XML...",
                "Set Up as New", "Quit"};
            String found = previousBuilds.size() == 1 ? previousBuilds.get(0).toString() :
                previousBuilds.size() + " nearby data folders";
            int result = JOptionPane.showOptionDialog(null,
                "We found data from a previous sdrtrunk-vce installation.\n\n" + found +
                    "\n\nWe can copy it, update the copy, and leave your previous installation unchanged.",
                TITLE, JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, buttons, buttons[0]);

            return switch(result)
            {
                case 0 -> new Choice(Action.USE_DISCOVERED_PREVIOUS);
                case 1 -> new Choice(Action.BROWSE_PREVIOUS);
                case 2 -> new Choice(legacyXml.isPresent() ? Action.IMPORT_DISCOVERED_XML : Action.BROWSE_XML);
                case 3 -> new Choice(Action.FRESH);
                default -> new Choice(Action.QUIT);
            };
        }

        if(legacyXml.isPresent())
        {
            Object[] buttons = {"Use Previous Data...", "Import Older XML", "Choose XML...", "Set Up as New",
                "Quit"};
            int result = JOptionPane.showOptionDialog(null,
                "No previous portable database was found nearby. An older XML setup is available:\n\n" +
                    legacyXml.get(), TITLE, JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, buttons,
                buttons[1]);

            return switch(result)
            {
                case 0 -> new Choice(Action.BROWSE_PREVIOUS);
                case 1 -> new Choice(Action.IMPORT_DISCOVERED_XML);
                case 2 -> new Choice(Action.BROWSE_XML);
                case 3 -> new Choice(Action.FRESH);
                default -> new Choice(Action.QUIT);
            };
        }

        Object[] buttons = {"Use Previous Data...", "Import Older XML...", "Set Up as New", "Quit"};
        int result = JOptionPane.showOptionDialog(null,
            "No previous sdrtrunk-vce data was found nearby.\n\nYou can choose another installation, import an " +
                "older XML playlist, or create a new setup.", TITLE, JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE, null, buttons, buttons[0]);

        return switch(result)
        {
            case 0 -> new Choice(Action.BROWSE_PREVIOUS);
            case 1 -> new Choice(Action.BROWSE_XML);
            case 2 -> new Choice(Action.FRESH);
            default -> new Choice(Action.QUIT);
        };
    }

    private static boolean importPrevious(Path source, Path target) throws Exception
    {
        int confirmation = JOptionPane.showConfirmDialog(null,
            "Close the previous sdrtrunk-vce app before continuing.\n\nThe Upgrade Assistant will copy its setup, " +
                "update the copied database, and leave the previous data unchanged.\n\nPrevious data:\n" + source +
                "\n\nContinue?", UPGRADE_TITLE, JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);

        if(confirmation != JOptionPane.OK_OPTION)
        {
            return false;
        }

        PreviousBuildUpgradeService service = new PreviousBuildUpgradeService();
        UpgradeProgressDialog.run(null, UPGRADE_TITLE,
            progress -> service.importPrevious(source, target, progress));
        JOptionPane.showMessageDialog(null,
            "Upgrade complete. Your previous installation and its data were left unchanged.", UPGRADE_TITLE,
            JOptionPane.INFORMATION_MESSAGE);
        return true;
    }

    private static boolean confirmCurrentUpgrade(Path database)
    {
        Object[] buttons = {"Upgrade and Start", "Quit"};
        int result = JOptionPane.showOptionDialog(null,
            "This database was created by an earlier alpha and must be updated before this build can start.\n\n" +
                "Close every other sdrtrunk-vce window first. A safety backup will be created before the staged " +
                "copy is updated.\n\nDatabase:\n" + database,
            UPGRADE_TITLE, JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, buttons, buttons[0]);
        return result == 0;
    }

    private static Path chooseDiscoveredPrevious(List<Path> previousBuilds)
    {
        if(previousBuilds.size() == 1)
        {
            return previousBuilds.get(0);
        }

        Object selected = JOptionPane.showInputDialog(null, "Choose the previous data you want to use:",
            UPGRADE_TITLE, JOptionPane.QUESTION_MESSAGE, null, previousBuilds.toArray(), previousBuilds.get(0));
        return selected instanceof Path path ? path : null;
    }

    private static Path browsePrevious(Path initialPath)
    {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Previous sdrtrunk-vce Data");
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setAcceptAllFileFilterUsed(true);
        Path initialDirectory = Files.isDirectory(initialPath) ? initialPath : initialPath.getParent();

        if(initialDirectory != null && Files.isDirectory(initialDirectory))
        {
            chooser.setCurrentDirectory(initialDirectory.toFile());
        }

        return chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().toPath() : null;
    }

    private static Path browseXml(Path initialPath)
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

    private static String message(Exception exception)
    {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private enum Action
    {
        USE_DISCOVERED_PREVIOUS,
        BROWSE_PREVIOUS,
        IMPORT_DISCOVERED_XML,
        BROWSE_XML,
        FRESH,
        QUIT
    }

    private record Choice(Action action) {}

    public record BootstrapResult(boolean startApplication, boolean initializeNewPreferences)
    {
        private static BootstrapResult cancelled()
        {
            return new BootstrapResult(false, false);
        }

        private static BootstrapResult existingProfile()
        {
            return new BootstrapResult(true, false);
        }

        private static BootstrapResult newPreferences()
        {
            return new BootstrapResult(true, true);
        }
    }

    private record Options(boolean fresh, Path importXml, Path upgradeData, boolean upgradeCurrent)
    {
        private static Options parse(String[] args)
        {
            boolean fresh = false;
            Path importXml = null;
            Path upgradeData = null;
            boolean upgradeCurrent = false;

            for(int x = 0; x < args.length; x++)
            {
                switch(args[x])
                {
                    case "--fresh" -> fresh = true;
                    case "--upgrade-current" -> upgradeCurrent = true;
                    case "--import-xml" ->
                    {
                        if(++x >= args.length)
                        {
                            throw new IllegalArgumentException("Missing XML path after --import-xml");
                        }

                        importXml = Path.of(args[x]);
                    }
                    case "--upgrade-data" ->
                    {
                        if(++x >= args.length)
                        {
                            throw new IllegalArgumentException("Missing previous data path after --upgrade-data");
                        }

                        upgradeData = Path.of(args[x]);
                    }
                    default -> { /* Other SDRTrunk arguments belong to their existing owners. */ }
                }
            }

            int modes = (fresh ? 1 : 0) + (importXml != null ? 1 : 0) + (upgradeData != null ? 1 : 0) +
                (upgradeCurrent ? 1 : 0);

            if(modes > 1)
            {
                throw new IllegalArgumentException("Use only one setup option: --fresh, --import-xml, " +
                    "--upgrade-data, or --upgrade-current");
            }

            return new Options(fresh, importXml, upgradeData, upgradeCurrent);
        }
    }
}
