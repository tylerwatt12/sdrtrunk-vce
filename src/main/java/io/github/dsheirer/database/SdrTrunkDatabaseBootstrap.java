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
import io.github.dsheirer.database.upgrade.ApplicationMigrationProgressDialog;
import io.github.dsheirer.database.upgrade.ApplicationMigrationService;
import io.github.dsheirer.database.upgrade.DatabaseMigrationChain;
import io.github.dsheirer.database.upgrade.PreviousBuildLocator;
import io.github.dsheirer.portable.PortableApplicationPaths;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultPath;
import io.github.dsheirer.web.auth.Pbkdf2PasswordHasher;
import io.github.dsheirer.web.auth.WebAccessService;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Single startup workflow for creating, importing, upgrading, and validating portable SDRTrunk databases.
 */
public final class SdrTrunkDatabaseBootstrap
{
    private static final String TITLE = "sdrtrunk-vce Setup";
    private static final String MIGRATOR_TITLE = "sdrtrunk-vce Application Migrator";
    private SdrTrunkDatabaseBootstrap()
    {
    }

    public static BootstrapResult run(String[] args) throws IOException, SQLException, InterruptedException
    {
        return run(args, PortableApplicationPaths.getDataRoot(), GraphicsEnvironment.isHeadless());
    }

    static BootstrapResult run(String[] args, Path dataRoot, boolean headless)
        throws IOException, SQLException, InterruptedException
    {
        Path normalizedDataRoot = dataRoot.toAbsolutePath().normalize();
        Path databasePath = SdrTrunkDatabasePath.getDatabasePath(normalizedDataRoot);
        Options options = Options.parse(args);

        if(Files.isRegularFile(databasePath))
        {
            if(options.upgradeData() != null)
            {
                throw new IOException("The current portable data folder already has a database. Remove " +
                    "--upgrade-data or choose a new data folder.");
            }

            DatabaseMigrationChain.PreflightReport migrationPlan =
                ApplicationMigrationService.readMigrationPlan(databasePath);
            String existingAdminSetupState = InitialAdminSetup.readState(databasePath);

            if(migrationPlan.source().requiresMigration())
            {
                if(headless && !options.upgradeCurrent())
                {
                    throw new IOException("The portable database requires these changes: " +
                        ApplicationMigrationService.describePlan(migrationPlan) +
                        ". Start once with --upgrade-current to let the Application Migrator create a safety " +
                        "backup and update it.");
                }

                if(!options.upgradeCurrent() && !confirmCurrentMigration(databasePath, migrationPlan))
                {
                    return BootstrapResult.cancelled();
                }

                ApplicationMigrationService service = new ApplicationMigrationService();
                ApplicationMigrationService.MigrationResult result;

                if(headless)
                {
                    result = service.migrateCurrent(normalizedDataRoot, migrationPlan, System.out::println);
                    if(!result.helperOutput().isBlank())
                    {
                        System.out.println(result.helperOutput());
                    }
                }
                else
                {
                    try
                    {
                        result = ApplicationMigrationProgressDialog.run(null, MIGRATOR_TITLE,
                            progress -> service.migrateCurrent(normalizedDataRoot, migrationPlan, progress));
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
                        throw new IOException("Database migration failed: " + message(e), e);
                    }

                    JOptionPane.showMessageDialog(null,
                        "Your database was migrated successfully.\n\n" + result.helperOutput() +
                            "\n\nSafety backup:\n" + result.safetyBackup(),
                        MIGRATOR_TITLE, JOptionPane.INFORMATION_MESSAGE);
                }
            }

            SdrTrunkDatabaseStartup.validateGlobalDatabase(databasePath);

            if(migrationPlan.source().requiresMigration())
            {
                InitialAdminSetup.restoreExistingProfileState(databasePath, existingAdminSetupState);
            }

            if(!completeInitialAdminSetup(databasePath, options, headless))
            {
                return BootstrapResult.cancelled();
            }

            prepareVault(normalizedDataRoot);
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
            PreviousBuildLocator.Selection source =
                PreviousBuildLocator.resolveSelection(options.upgradeData()).orElseThrow(() ->
                new IOException("The selected location does not contain portable sdrtrunk-vce data: " +
                    options.upgradeData()));
            ApplicationMigrationService.MigrationResult migration =
                new ApplicationMigrationService().importPrevious(source, normalizedDataRoot, System.out::println);
            if(!migration.helperOutput().isBlank())
            {
                System.out.println(migration.helperOutput());
            }
            result = BootstrapResult.existingProfile();
        }
        else if(headless)
        {
            throw new IOException("No portable SDRTrunk database exists at " + databasePath +
                ". Start once with --fresh, --import-xml <path>, or --upgrade-data " +
                "<previous-folder-or-sqlite-file>. New " +
                "headless installations also require --admin-password-file <path>.");
        }
        else
        {
            result = runInteractive(databasePath, normalizedDataRoot);

            if(!result.startApplication())
            {
                return result;
            }
        }

        InitialAdminSetup.initializeNewProfile(databasePath);

        if(!completeInitialAdminSetup(databasePath, options, headless))
        {
            return BootstrapResult.cancelled();
        }

        prepareVault(normalizedDataRoot);
        return result;
    }

    private static boolean completeInitialAdminSetup(Path databasePath, Options options, boolean headless)
        throws IOException, SQLException
    {
        if(!InitialAdminSetup.isPasswordRequired(databasePath))
        {
            return true;
        }

        char[] password;

        if(options.adminPasswordFile() != null)
        {
            password = readPasswordFile(options.adminPasswordFile());
        }
        else if(headless)
        {
            throw new IOException("This new installation requires an administrator password. Start again with " +
                "--admin-password-file <path>. The file must contain only the password and must be removed or " +
                "secured after setup.");
        }
        else
        {
            Optional<char[]> prompted = requestInitialAdminPassword();

            if(prompted.isEmpty())
            {
                return false;
            }

            password = prompted.get();
        }

        try
        {
            InitialAdminSetup.provision(databasePath, password);
            return true;
        }
        finally
        {
            Arrays.fill(password, '\u0000');
        }
    }

    private static Optional<char[]> requestInitialAdminPassword()
    {
        JPasswordField passwordField = new JPasswordField(28);
        JPasswordField confirmationField = new JPasswordField(28);
        JPanel fields = new JPanel();
        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
        fields.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        fields.add(new JLabel("<html><b>Create the administrator password</b><br><br>" +
            "The web interface is enabled by default. Every new installation requires a password for the fixed " +
            "administrator account <b>" + WebAccessService.PRIMARY_ADMIN_USERNAME + "</b> before it can start." +
            "</html>"));
        fields.add(Box.createVerticalStrut(14));
        fields.add(new JLabel("Password (" + Pbkdf2PasswordHasher.MINIMUM_PASSWORD_CHARACTERS + "-" +
            Pbkdf2PasswordHasher.MAXIMUM_PASSWORD_CHARACTERS + " characters)"));
        fields.add(passwordField);
        fields.add(Box.createVerticalStrut(8));
        fields.add(new JLabel("Confirm password"));
        fields.add(confirmationField);
        Object[] buttons = {"Save and Continue", "Quit"};

        try
        {
            while(true)
            {
                int selected = JOptionPane.showOptionDialog(null, fields, TITLE, JOptionPane.DEFAULT_OPTION,
                    JOptionPane.PLAIN_MESSAGE, null, buttons, buttons[0]);

                if(selected != 0)
                {
                    return Optional.empty();
                }

                char[] password = passwordField.getPassword();
                char[] confirmation = confirmationField.getPassword();
                boolean accepted = false;

                try
                {
                    if(password.length < Pbkdf2PasswordHasher.MINIMUM_PASSWORD_CHARACTERS ||
                        password.length > Pbkdf2PasswordHasher.MAXIMUM_PASSWORD_CHARACTERS)
                    {
                        JOptionPane.showMessageDialog(null,
                            "Password must contain " + Pbkdf2PasswordHasher.MINIMUM_PASSWORD_CHARACTERS + "-" +
                                Pbkdf2PasswordHasher.MAXIMUM_PASSWORD_CHARACTERS + " characters.",
                            TITLE, JOptionPane.WARNING_MESSAGE);
                        continue;
                    }

                    if(!Arrays.equals(password, confirmation))
                    {
                        JOptionPane.showMessageDialog(null, "Passwords do not match.", TITLE,
                            JOptionPane.WARNING_MESSAGE);
                        continue;
                    }

                    passwordField.setText("");
                    confirmationField.setText("");
                    accepted = true;
                    return Optional.of(password);
                }
                finally
                {
                    Arrays.fill(confirmation, '\u0000');

                    if(!accepted)
                    {
                        Arrays.fill(password, '\u0000');
                    }
                }
            }
        }
        finally
        {
            passwordField.setText("");
            confirmationField.setText("");
        }
    }

    private static char[] readPasswordFile(Path path) throws IOException
    {
        Path normalized = path.toAbsolutePath().normalize();
        char[] buffer = new char[Pbkdf2PasswordHasher.MAXIMUM_PASSWORD_CHARACTERS + 3];
        int length = 0;

        try
        {
            try(Reader reader = Files.newBufferedReader(normalized, StandardCharsets.UTF_8))
            {
                while(length < buffer.length)
                {
                    int count = reader.read(buffer, length, buffer.length - length);

                    if(count < 0)
                    {
                        break;
                    }

                    length += count;
                }

                if(length == buffer.length || reader.read() >= 0)
                {
                    throw new IOException("Administrator password file is too large: " + normalized);
                }
            }

            if(length > 0 && buffer[length - 1] == '\n')
            {
                length--;

                if(length > 0 && buffer[length - 1] == '\r')
                {
                    length--;
                }
            }

            if(length < Pbkdf2PasswordHasher.MINIMUM_PASSWORD_CHARACTERS ||
                length > Pbkdf2PasswordHasher.MAXIMUM_PASSWORD_CHARACTERS)
            {
                throw new IOException("Administrator password file must contain " +
                    Pbkdf2PasswordHasher.MINIMUM_PASSWORD_CHARACTERS + "-" +
                    Pbkdf2PasswordHasher.MAXIMUM_PASSWORD_CHARACTERS + " characters");
            }

            return Arrays.copyOf(buffer, length);
        }
        finally
        {
            Arrays.fill(buffer, '\u0000');
        }
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

                        PreviousBuildLocator.Selection selection = new PreviousBuildLocator.Selection(source,
                            PreviousBuildLocator.InputScope.PORTABLE_PROFILE);

                        if(!importPrevious(selection, dataRoot))
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

                        Optional<PreviousBuildLocator.Selection> source =
                            PreviousBuildLocator.resolveSelection(selected);

                        if(source.isEmpty())
                        {
                            JOptionPane.showMessageDialog(null,
                                "That location does not contain database/" +
                                    SdrTrunkDatabasePath.DATABASE_FILENAME + ".\n\nChoose the previous app, " +
                                    "install folder, data folder, or SQLite database.",
                                    MIGRATOR_TITLE, JOptionPane.WARNING_MESSAGE);
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
            String xmlButton = legacyXml.isPresent() ? "Use Found XML" : "Choose XML...";
            Object[] buttons = {"Migrate Existing", "Choose Install...", xmlButton, "Start Fresh", "Quit"};
            String found = previousBuilds.size() == 1 ? previousBuilds.get(0).toString() :
                previousBuilds.size() + " nearby data folders";
            String migrateExplanation = previousBuilds.size() == 1 ?
                "Migrate Existing: Copy and upgrade the found profile; the original is unchanged." :
                "Migrate Existing: Choose a found profile to copy and upgrade; the originals are unchanged.";
            String xmlExplanation = legacyXml.map(path ->
                "Use Found XML: Import playlist configuration from the detected XML.").orElse(
                "Choose XML: Import playlist configuration from a legacy XML file.");
            String detectedXml = legacyXml.map(path -> "\nDetected XML:\n" + path).orElse("");
            int result = JOptionPane.showOptionDialog(null,
                "Choose how to set up this installation.\n\n" + migrateExplanation +
                    "\nChoose Install: Select a different portable profile to copy and upgrade." +
                    "\n" + xmlExplanation +
                    "\nStart Fresh: Create a new empty profile.\n\nDetected portable data:\n" + found +
                    detectedXml,
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
            Object[] buttons = {"Migrate Existing", "Use Found XML", "Choose Other XML...", "Start Fresh", "Quit"};
            int result = JOptionPane.showOptionDialog(null,
                "Choose how to set up this installation.\n\n" +
                    "Migrate Existing: Copy and upgrade a portable profile; the original is unchanged.\n" +
                    "Use Found XML: Import playlist configuration from the detected XML.\n" +
                    "Choose Other XML: Import playlist configuration from a different XML file.\n" +
                    "Start Fresh: Create a new empty profile.\n\nDetected XML:\n" + legacyXml.get(), TITLE,
                    JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, buttons, buttons[1]);

            return switch(result)
            {
                case 0 -> new Choice(Action.BROWSE_PREVIOUS);
                case 1 -> new Choice(Action.IMPORT_DISCOVERED_XML);
                case 2 -> new Choice(Action.BROWSE_XML);
                case 3 -> new Choice(Action.FRESH);
                default -> new Choice(Action.QUIT);
            };
        }

        Object[] buttons = {"Migrate Existing", "Choose XML...", "Start Fresh", "Quit"};
        int result = JOptionPane.showOptionDialog(null,
            "Choose how to set up this installation.\n\n" +
                "Migrate Existing: Copy and upgrade a portable profile; the original is unchanged.\n" +
                "Choose XML: Import playlist configuration from a legacy XML file.\n" +
                "Start Fresh: Create a new empty profile.", TITLE, JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, buttons, buttons[0]);

        return switch(result)
        {
            case 0 -> new Choice(Action.BROWSE_PREVIOUS);
            case 1 -> new Choice(Action.BROWSE_XML);
            case 2 -> new Choice(Action.FRESH);
            default -> new Choice(Action.QUIT);
        };
    }

    private static boolean importPrevious(PreviousBuildLocator.Selection source, Path target) throws Exception
    {
        boolean portableProfile = source.scope() == PreviousBuildLocator.InputScope.PORTABLE_PROFILE;
        DatabaseMigrationChain.PreflightReport migrationPlan =
            ApplicationMigrationService.readMigrationPlan(source.database());
        String selectedKind = portableProfile ?
            "copy its portable profile, upgrade the copied database, and leave the original installation unchanged" :
            "copy and upgrade only the selected SQLite database. Its neighboring vault, JMBE library, optional " +
                "modules, and other files will not be copied, and stored paths will not be remapped";
        int confirmation = JOptionPane.showConfirmDialog(null,
            "Close the sdrtrunk-vce installation you're migrating before continuing.\n\nThe Application " +
                "Migrator will " + selectedKind + ".\n\nSelected " +
                (portableProfile ? "portable profile" : "SQLite database") + ":\n" + source.path() +
                "\n\nRequired database changes:\n" + ApplicationMigrationService.describePlan(migrationPlan) +
                "\n\nContinue?", MIGRATOR_TITLE,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);

        if(confirmation != JOptionPane.OK_OPTION)
        {
            return false;
        }

        ApplicationMigrationService service = new ApplicationMigrationService();
        ApplicationMigrationService.MigrationResult migration = ApplicationMigrationProgressDialog.run(null,
            MIGRATOR_TITLE,
            progress -> service.importPrevious(source, target, migrationPlan, progress));
        JOptionPane.showMessageDialog(null,
            portableProfile ?
                "Migration complete. The portable profile was copied and its stored paths were remapped. Your " +
                    "previous installation and its data were left unchanged.\n\n" + migration.helperOutput() :
                "Migration complete. Only the selected SQLite database was imported. The source database and its " +
                    "neighboring files were left unchanged.\n\n" + migration.helperOutput(), MIGRATOR_TITLE,
            JOptionPane.INFORMATION_MESSAGE);
        return true;
    }

    private static boolean confirmCurrentMigration(Path database,
                                                   DatabaseMigrationChain.PreflightReport migrationPlan)
    {
        Object[] buttons = {"Migrate and Start", "Quit"};
        int result = JOptionPane.showOptionDialog(null,
            "This database uses an earlier supported format and must be updated before this build can start.\n\n" +
                "Required changes: " + ApplicationMigrationService.describePlan(migrationPlan) + "\n\n" +
                "Close every other sdrtrunk-vce window first. A safety backup will be created before the staged " +
                "copy is updated.\n\nDatabase:\n" + database,
            MIGRATOR_TITLE, JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, buttons, buttons[0]);
        return result == 0;
    }

    private static Path chooseDiscoveredPrevious(List<Path> previousBuilds)
    {
        if(previousBuilds.size() == 1)
        {
            return previousBuilds.get(0);
        }

        Object selected = JOptionPane.showInputDialog(null, "Choose the existing installation you want to migrate:",
            MIGRATOR_TITLE, JOptionPane.QUESTION_MESSAGE, null, previousBuilds.toArray(), previousBuilds.get(0));
        return selected instanceof Path path ? path : null;
    }

    private static Path browsePrevious(Path initialPath)
    {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Existing sdrtrunk-vce Installation");
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

    private record Options(boolean fresh, Path importXml, Path upgradeData, boolean upgradeCurrent,
                           Path adminPasswordFile)
    {
        private static Options parse(String[] args)
        {
            boolean fresh = false;
            Path importXml = null;
            Path upgradeData = null;
            boolean upgradeCurrent = false;
            Path adminPasswordFile = null;

            for(int x = 0; x < args.length; x++)
            {
                switch(args[x])
                {
                    case "--fresh" -> fresh = true;
                    case "--upgrade-current" -> upgradeCurrent = true;
                    case "--admin-password-file" ->
                    {
                        if(++x >= args.length)
                        {
                            throw new IllegalArgumentException("Missing path after --admin-password-file");
                        }

                        adminPasswordFile = Path.of(args[x]);
                    }
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

            return new Options(fresh, importXml, upgradeData, upgradeCurrent, adminPasswordFile);
        }
    }
}
