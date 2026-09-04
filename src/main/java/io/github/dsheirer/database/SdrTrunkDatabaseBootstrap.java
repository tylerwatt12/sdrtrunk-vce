/*
 * Copyright (C) 2026 Dennis Sheirer
 * Distributed under the GNU General Public License, version 3 or later.
 */
package io.github.dsheirer.database;

import io.github.dsheirer.database.importer.LegacyXmlConfigurationImporter;
import io.github.dsheirer.database.upgrade.ApplicationMigrationService;
import io.github.dsheirer.database.upgrade.PreviousBuildLocator;
import io.github.dsheirer.portable.PortableApplicationPaths;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultPath;
import io.github.dsheirer.web.auth.Pbkdf2PasswordHasher;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;

/** Noninteractive bootstrap. Graphical orchestration belongs exclusively to SetupWizard. */
public final class SdrTrunkDatabaseBootstrap
{
    private SdrTrunkDatabaseBootstrap() {}

    public static BootstrapResult run(String[] args) throws IOException, SQLException, InterruptedException
    {
        return run(args, PortableApplicationPaths.getDataRoot(), true);
    }

    static BootstrapResult run(String[] args, Path dataRoot, boolean headless)
        throws IOException, SQLException, InterruptedException
    {
        if(!headless) throw new IOException("Use SetupWizard for graphical setup");
        if(Arrays.asList(args).contains("--setup-wizard"))
            throw new IOException("--setup-wizard requires a graphical desktop");
        Path normalized = dataRoot.toAbsolutePath().normalize();
        Path database = SdrTrunkDatabasePath.getDatabasePath(normalized);
        Options options = Options.parse(args);
        boolean freshPreferences = false;
        if(Files.isRegularFile(database))
        {
            if(options.upgradeData() != null) throw new IOException("The current portable data folder already has a database");
            var plan = ApplicationMigrationService.readMigrationPlan(database);
            String adminState = InitialAdminSetup.readState(database);
            if(plan.source().requiresMigration())
            {
                if(!options.upgradeCurrent()) throw new IOException("The portable database requires these changes: " +
                    ApplicationMigrationService.describePlan(plan) +
                    ". Start once with --upgrade-current to create a safety backup and migrate it.");
                var result = new ApplicationMigrationService().migrateCurrent(normalized, plan, System.out::println);
                if(!result.helperOutput().isBlank()) System.out.println(result.helperOutput());
                InitialAdminSetup.restoreExistingProfileState(database, adminState);
            }
            SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
        }
        else
        {
            if(options.upgradeCurrent()) throw new IOException("--upgrade-current requires an existing portable database at " + database);
            if(options.fresh())
            {
                createFresh(database); freshPreferences = true;
            }
            else if(options.importXml() != null)
            {
                LegacyXmlConfigurationImporter.importPlaylist(options.importXml(), database); freshPreferences = true;
            }
            else if(options.upgradeData() != null)
            {
                var source = PreviousBuildLocator.resolveSelection(options.upgradeData()).orElseThrow(() ->
                    new IOException("The selected location does not contain portable sdrtrunk-vce data: " + options.upgradeData()));
                var result = new ApplicationMigrationService().importPrevious(source, normalized, System.out::println);
                if(!result.helperOutput().isBlank()) System.out.println(result.helperOutput());
            }
            else throw new IOException("No portable SDRTrunk database exists at " + database +
                ". Start once with --fresh, --import-xml <path>, or --upgrade-data <previous-folder-or-sqlite-file>. " +
                "New headless installations also require --admin-password-file <path>.");
            InitialAdminSetup.initializeNewProfile(database);
        }
        if(InitialAdminSetup.isPasswordRequired(database))
        {
            if(options.adminPasswordFile() == null) throw new IOException("This new installation requires an administrator password. " +
                "Start again with --admin-password-file <path>. Secure or remove the file after setup.");
            char[] password = readPasswordFile(options.adminPasswordFile());
            try { InitialAdminSetup.provision(database, password); }
            finally { Arrays.fill(password, (char)0); }
        }
        prepareVault(normalized);
        return new BootstrapResult(true, freshPreferences);
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

    public static void createFresh(Path databasePath) throws IOException, SQLException
    {
        DatabaseFileInstaller.install(databasePath, SdrTrunkDatabaseStartup::createGlobalDatabase);
    }

    public static void prepareVault(Path dataRoot) throws IOException, SQLException
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

    public record Options(boolean fresh, Path importXml, Path upgradeData, boolean upgradeCurrent,
                           Path adminPasswordFile)
    {
        public static Options parse(String[] args)
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
