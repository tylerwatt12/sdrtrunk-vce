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

package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultPath;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultSchema;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.P25ActivityLogSchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.sqlite.SQLiteConfig;

/**
 * Stages, validates, and promotes portable data accepted by the current sdrtrunk-vce build.
 *
 * <p>The Application Migrator always runs in a child process and only receives a staged database. Normal startup
 * services remain validation-only for an existing SQLite schema. Numbered release preparation may temporarily add
 * the immediately preceding public release as a supported schema source.</p>
 */
public final class ApplicationMigrationService
{
    public static final int ALPHA_7_P25_VERSION = 21;
    public static final int ALPHA_7_ALIAS_VERSION = 3;
    public static final Set<Integer> SUPPORTED_P25_VERSIONS =
        Set.of(ALPHA_7_P25_VERSION, P25ActivityLogSchema.SCHEMA_VERSION);
    public static final Set<Integer> SUPPORTED_ALIAS_VERSIONS = Set.of(ALPHA_7_ALIAS_VERSION, 4);
    public static final int CURRENT_P25_VERSION = P25ActivityLogSchema.SCHEMA_VERSION;
    public static final int CURRENT_ALIAS_VERSION = 4;
    public static final int CURRENT_DMR_VERSION = DmrActivitySchema.SCHEMA_VERSION;

    private static final String P25_VERSION_KEY = "p25_activity_schema_version";
    private static final String ALIAS_VERSION_KEY = "alias_schema_version";
    private static final long FREE_SPACE_MARGIN_BYTES = 64L * 1024L * 1024L;
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final List<String> COPIED_DIRECTORIES = List.of("jmbe", "modules");

    private final Snapshotter mSnapshotter;
    private final MigrationRunner mMigrationRunner;

    public ApplicationMigrationService()
    {
        this(SqliteDatabaseSnapshot::create, ApplicationMigratorLauncher::run);
    }

    ApplicationMigrationService(Snapshotter snapshotter, MigrationRunner migrationRunner)
    {
        mSnapshotter = Objects.requireNonNull(snapshotter);
        mMigrationRunner = Objects.requireNonNull(migrationRunner);
    }

    /**
     * Imports a previous portable profile into a data root that does not yet have a database.
     */
    public MigrationResult importPrevious(Path sourceDataRoot, Path targetDataRoot, ProgressListener progress)
        throws IOException, SQLException, InterruptedException
    {
        ProgressListener listener = progress == null ? ignored -> { } : progress;
        Path sourceRoot = sourceDataRoot.toAbsolutePath().normalize();
        Path targetRoot = targetDataRoot.toAbsolutePath().normalize();
        requireSeparatePhysicalRoots(sourceRoot, targetRoot);
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        Path targetDatabase = SdrTrunkDatabasePath.getDatabasePath(targetRoot);

        if(Files.exists(targetDatabase))
        {
            throw new IOException("The current portable data folder already has a database: " + targetDatabase);
        }
        SqliteDatabaseSnapshot.requireSourceUsable(sourceDatabase);

        listener.update("Checking previous data");
        MigrationState sourceState = requireSupportedState(sourceDatabase);
        requireEmptyOrMissing(targetRoot);
        FileAccessAttributeSnapshot targetRootAttributes =
            Files.exists(targetRoot, LinkOption.NOFOLLOW_LINKS) ? FileAccessAttributeSnapshot.capture(targetRoot) :
                null;
        Path targetParent = targetRoot.getParent();

        if(targetParent == null)
        {
            throw new IOException("The current portable data folder has no parent: " + targetRoot);
        }

        Files.createDirectories(targetParent);
        ensureFreeSpace(targetParent, requiredImportSpace(sourceRoot, sourceState));
        Path stageRoot = targetParent.resolve("." + targetRoot.getFileName() + ".migration-" + UUID.randomUUID());
        boolean promoted = false;

        try
        {
            Files.createDirectory(stageRoot);
            listener.update("Copying setup");
            Path stagedDatabase = SdrTrunkDatabasePath.getDatabasePath(stageRoot);
            mSnapshotter.create(sourceDatabase, stagedDatabase);
            copyOptionalProfileData(sourceRoot, stageRoot);

            listener.update("Creating safety backup");
            copyVaultSnapshot(sourceRoot, stageRoot);

            listener.update("Updating database");
            String helperOutput = mMigrationRunner.run(stagedDatabase, sourceRoot, targetRoot);

            listener.update("Checking updated data");
            validateGlobalDatabase(stagedDatabase);
            validateVaultIfPresent(stageRoot);

            listener.update("Finishing");
            requireNoSidecars(stagedDatabase,
                "The staged database still has SQLite sidecar files and cannot be installed safely.");
            if(targetRootAttributes != null)
            {
                targetRootAttributes.applyTo(stageRoot);
            }
            removeEmptyTreeIfPresent(targetRoot);
            moveAtomically(stageRoot, targetRoot);
            promoted = true;
            return new MigrationResult(true, null, sourceState, helperOutput);
        }
        finally
        {
            if(!promoted)
            {
                deleteTreeIfExists(stageRoot);
            }
        }
    }

    /**
     * Migrates a supported earlier database already in the current portable data root and retains a safety backup.
     */
    public MigrationResult migrateCurrent(Path dataRoot, ProgressListener progress)
        throws IOException, SQLException, InterruptedException
    {
        ProgressListener listener = progress == null ? ignored -> { } : progress;
        Path normalizedRoot = dataRoot.toAbsolutePath().normalize();
        Path database = SdrTrunkDatabasePath.getDatabasePath(normalizedRoot);
        SqliteDatabaseSnapshot.requireSourceUsable(database);
        FileAccessAttributeSnapshot liveDatabaseAttributes = FileAccessAttributeSnapshot.capture(database);

        listener.update("Checking previous data");
        MigrationState sourceState = requireSupportedState(database);

        Path databaseDirectory = database.getParent();
        int databaseCopies = sourceState.alpha7() ? 4 : 2;
        long requiredDatabaseSpace = sourceState.alpha7() ?
            alpha7DatabaseWorkingSpace(database, databaseCopies) :
            safeMultiply(sqliteFootprint(database), databaseCopies);
        ensureFreeSpace(databaseDirectory, safeAdd(requiredDatabaseSpace, FREE_SPACE_MARGIN_BYTES));
        Path backupDirectory = databaseDirectory.resolve("backups");
        Files.createDirectories(backupDirectory);
        String identity = BACKUP_TIME.format(LocalDateTime.now()) + "-" +
            UUID.randomUUID().toString().substring(0, 8);
        Path backup = backupDirectory.resolve("sdrtrunk-before-application-migration-" + identity + ".sqlite");
        Path stagedBackup = backupDirectory.resolve("." + backup.getFileName() + ".incomplete-" + UUID.randomUUID());
        Path staged = databaseDirectory.resolve("." + SdrTrunkDatabasePath.DATABASE_FILENAME + ".migration-" +
            UUID.randomUUID());

        Throwable primaryFailure = null;
        try
        {
            listener.update("Creating safety backup");
            mSnapshotter.create(database, stagedBackup);
            liveDatabaseAttributes.applyTo(stagedBackup);
            MigrationState backupState = requireSupportedState(stagedBackup);
            if(!sourceState.equals(backupState))
            {
                throw new IOException("The safety backup schema state differs from the source database.");
            }
            finalizeStandaloneSnapshot(stagedBackup);
            requireNoSidecars(stagedBackup,
                "The safety backup still has SQLite sidecar files and cannot be published safely.");
            liveDatabaseAttributes.applyTo(stagedBackup);
            moveAtomically(stagedBackup, backup);
            Files.copy(backup, staged, StandardCopyOption.COPY_ATTRIBUTES);

            listener.update("Updating database");
            String helperOutput = mMigrationRunner.run(staged, normalizedRoot, normalizedRoot);

            listener.update("Checking updated data");
            validateGlobalDatabase(staged);
            requireNoSidecars(staged,
                "The staged database still has SQLite sidecar files and cannot replace the live database safely.");
            liveDatabaseAttributes.applyTo(staged);

            listener.update("Finishing");
            prepareLiveDatabaseForReplacement(database);
            moveAtomicallyReplacing(staged, database);

            try
            {
                validateGlobalDatabase(database);
                liveDatabaseAttributes.applyTo(database);
            }
            catch(IOException | SQLException validationFailure)
            {
                try
                {
                    restoreBackup(backup, database, liveDatabaseAttributes);
                }
                catch(IOException restoreFailure)
                {
                    validationFailure.addSuppressed(restoreFailure);
                }

                throw validationFailure;
            }

            return new MigrationResult(false, backup, sourceState, helperOutput);
        }
        catch(IOException | SQLException | InterruptedException | RuntimeException e)
        {
            primaryFailure = e;
            throw e;
        }
        catch(Error e)
        {
            primaryFailure = e;
            throw e;
        }
        finally
        {
            IOException cleanupFailure = null;
            try
            {
                deleteDatabaseAndSidecarsIfExists(staged);
            }
            catch(IOException e)
            {
                cleanupFailure = e;
            }
            try
            {
                deleteDatabaseAndSidecarsIfExists(stagedBackup);
            }
            catch(IOException e)
            {
                if(cleanupFailure == null)
                {
                    cleanupFailure = e;
                }
                else
                {
                    cleanupFailure.addSuppressed(e);
                }
            }
            if(cleanupFailure != null)
            {
                if(primaryFailure != null)
                {
                    primaryFailure.addSuppressed(cleanupFailure);
                }
                else
                {
                    throw cleanupFailure;
                }
            }
        }
    }

    /**
     * Reads all application-migrator schema versions without creating or repairing anything.
     */
    public static MigrationState readMigrationState(Path database) throws IOException, SQLException
    {
        Path normalized = database.toAbsolutePath().normalize();

        if(!Files.isRegularFile(normalized))
        {
            throw new IOException("SDRTrunk SQLite database does not exist: " + normalized);
        }

        try(Connection connection = openReadOnly(normalized))
        {
            int aliasVersion = readRequiredVersion(connection, ALIAS_VERSION_KEY, "Alias");
            int p25Version = readRequiredVersion(connection, P25_VERSION_KEY, "P25 activity");
            Integer trunkedSiteVersion = readOptionalVersion(connection, TrunkedSiteSchema.SCHEMA_VERSION_KEY,
                "trunked-site");
            Integer dmrVersion = readOptionalVersion(connection, DmrActivitySchema.SCHEMA_VERSION_KEY,
                "DMR activity");
            return new MigrationState(aliasVersion, p25Version, trunkedSiteVersion, dmrVersion);
        }
    }

    /**
     * Compatibility accessor for callers that only need the P25 component.
     */
    public static int readP25ActivitySchemaVersion(Path database) throws IOException, SQLException
    {
        return readMigrationState(database).p25Version();
    }

    private MigrationState requireSupportedState(Path database) throws IOException, SQLException
    {
        MigrationState state = readMigrationState(database);

        if(!state.supported())
        {
            throw new IOException("This release accepts only the complete Alpha 7 database (Alias v3, P25 " +
                "activity v21, trunked-site v2, and no DMR activity schema) or its complete current database " +
                "(Alias v4, P25 activity v" + CURRENT_P25_VERSION + ", trunked-site v2, and DMR activity v" +
                CURRENT_DMR_VERSION + "). Found " + state.description() + ".");
        }

        ApplicationDatabaseMigrator.validateAcceptedSource(database, state);

        return state;
    }

    private static int readRequiredVersion(Connection connection, String key, String label) throws SQLException
    {
        Integer version = readOptionalVersion(connection, key, label);

        if(version == null)
        {
            throw new SQLException("The database does not identify its " + label + " schema version.");
        }

        return version;
    }

    private static Integer readOptionalVersion(Connection connection, String key, String label) throws SQLException
    {
        try(var statement = connection.prepareStatement("SELECT value FROM database_metadata WHERE key=?"))
        {
            statement.setString(1, key);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(!resultSet.next())
                {
                    return null;
                }

                String value = resultSet.getString(1);

                try
                {
                    return Integer.parseInt(value);
                }
                catch(NumberFormatException e)
                {
                    throw new SQLException("Invalid " + label + " schema version: " + value, e);
                }
            }
        }
    }

    private static void validateGlobalDatabase(Path database) throws IOException, SQLException
    {
        if(!Files.isRegularFile(database))
        {
            throw new IOException("Staged SDRTrunk SQLite database does not exist: " + database);
        }

        try(Connection connection = openReadOnly(database))
        {
            SdrTrunkDatabaseStartup.requireMainTrackDatabase(connection);
            SdrTrunkDatabaseSchema.validate(connection);
            P25ActivityLogSchema.validate(connection);
            DmrActivitySchema.validate(connection);
            TrunkedSiteSchema.validate(connection);
            requireIntegrity(connection);
            requireForeignKeysValid(connection);
        }

        //The read-only checks above establish that it is safe to apply the main runtime's WAL configuration.
        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
    }

    private static void validateVaultIfPresent(Path dataRoot) throws SQLException
    {
        Path vault = EncryptionKeyVaultPath.getVaultPath(dataRoot);

        if(Files.isRegularFile(vault))
        {
            try(Connection connection = openReadOnly(vault))
            {
                EncryptionKeyVaultSchema.validate(connection);
                requireIntegrity(connection);
                requireForeignKeysValid(connection);
            }
        }
    }

    private void copyVaultSnapshot(Path sourceRoot, Path stageRoot) throws IOException, SQLException
    {
        Path sourceVault = EncryptionKeyVaultPath.getVaultPath(sourceRoot);

        if(Files.isRegularFile(sourceVault))
        {
            mSnapshotter.create(sourceVault, EncryptionKeyVaultPath.getVaultPath(stageRoot));
        }
    }

    private static void copyOptionalProfileData(Path sourceRoot, Path stageRoot) throws IOException
    {
        for(String directory : COPIED_DIRECTORIES)
        {
            Path source = sourceRoot.resolve(directory);

            if(Files.isDirectory(source))
            {
                copyDirectory(source, stageRoot.resolve(directory));
            }
        }
    }

    private static void copyDirectory(Path source, Path destination) throws IOException
    {
        Files.walkFileTree(source, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                throws IOException
            {
                if(Files.isSymbolicLink(directory))
                {
                    throw new IOException("Refusing to follow a symbolic link while copying previous data: " +
                        directory);
                }

                Files.createDirectories(destination.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
            {
                if(Files.isSymbolicLink(file))
                {
                    throw new IOException("Refusing to copy a symbolic link from previous data: " + file);
                }

                Files.copy(file, destination.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void prepareLiveDatabaseForReplacement(Path database) throws IOException, SQLException
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA busy_timeout=" + SdrTrunkDatabase.BUSY_TIMEOUT_MILLISECONDS);

            try(ResultSet resultSet = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
            {
                if(!resultSet.next() || resultSet.getInt(1) != 0)
                {
                    throw new IOException("The current database is in use. Close every other sdrtrunk-vce window " +
                        "and try again.");
                }
            }
        }

        Path wal = Path.of(database + "-wal");
        Path sharedMemory = Path.of(database + "-shm");
        Path rollbackJournal = Path.of(database + "-journal");

        if(Files.exists(wal) || Files.exists(sharedMemory) || Files.exists(rollbackJournal))
        {
            throw new IOException("The current database is still active. Close every other sdrtrunk-vce window " +
                "and try again.");
        }
    }

    private static void finalizeStandaloneSnapshot(Path database) throws IOException, SQLException
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA busy_timeout=" + SdrTrunkDatabase.BUSY_TIMEOUT_MILLISECONDS);
            try(ResultSet resultSet = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
            {
                if(!resultSet.next() || resultSet.getInt(1) != 0)
                {
                    throw new IOException("The safety backup could not be checkpointed.");
                }
            }
            try(ResultSet resultSet = statement.executeQuery("PRAGMA journal_mode=DELETE"))
            {
                if(!resultSet.next() || !"delete".equalsIgnoreCase(resultSet.getString(1)))
                {
                    throw new IOException("The safety backup could not be made standalone.");
                }
            }
        }
    }

    private static void restoreBackup(Path backup, Path database, FileAccessAttributeSnapshot accessAttributes)
        throws IOException
    {
        Path restore = database.resolveSibling("." + database.getFileName() + ".restore-" + UUID.randomUUID());

        try
        {
            Files.copy(backup, restore);
            accessAttributes.applyTo(restore);
            requireNoSidecars(database, "The migrated database is still active, so its safety backup cannot be " +
                "restored automatically.");
            moveAtomicallyReplacing(restore, database);
        }
        finally
        {
            Files.deleteIfExists(restore);
        }
    }

    private static void requireNoSidecars(Path database, String failureMessage) throws IOException
    {
        if(Files.exists(Path.of(database + "-wal")) || Files.exists(Path.of(database + "-shm")) ||
            Files.exists(Path.of(database + "-journal")))
        {
            throw new IOException(failureMessage);
        }
    }

    private static Connection openReadOnly(Path database) throws SQLException
    {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(SdrTrunkDatabase.BUSY_TIMEOUT_MILLISECONDS);
        config.enforceForeignKeys(true);
        return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath().normalize(),
            config.toProperties());
    }

    private static void requireIntegrity(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA integrity_check"))
        {
            boolean result = false;

            while(resultSet.next())
            {
                result = true;

                if(!"ok".equalsIgnoreCase(resultSet.getString(1)))
                {
                    throw new SQLException("SQLite integrity check failed: " + resultSet.getString(1));
                }
            }

            if(!result)
            {
                throw new SQLException("SQLite integrity check returned no result.");
            }
        }
    }

    private static void requireForeignKeysValid(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_check"))
        {
            if(resultSet.next())
            {
                throw new SQLException("SQLite foreign-key check failed for table " + resultSet.getString(1) + ".");
            }
        }
    }

    private static void requireEmptyOrMissing(Path directory) throws IOException
    {
        if(!Files.exists(directory))
        {
            return;
        }

        if(Files.isSymbolicLink(directory))
        {
            throw new IOException("The portable data location must not be a symbolic link: " + directory);
        }

        if(!Files.isDirectory(directory))
        {
            throw new IOException("The portable data location is not a folder: " + directory);
        }

        try(var paths = Files.list(directory))
        {
            if(paths.findAny().isPresent())
            {
                throw new IOException("The current portable data folder already contains data. Move or rename it " +
                    "before importing previous data: " + directory);
            }
        }
    }

    private static void removeEmptyTreeIfPresent(Path directory) throws IOException
    {
        if(Files.exists(directory))
        {
            requireEmptyOrMissing(directory);
            //A nonrecursive delete fails atomically if another first-run process populated this folder after the
            //emptiness check. Recursive deletion is reserved for this process's private UUID staging folder.
            Files.deleteIfExists(directory);
        }
    }

    static void deleteDatabaseAndSidecarsIfExists(Path database) throws IOException
    {
        IOException failure = null;
        for(String suffix: List.of("-journal", "-wal", "-shm", ""))
        {
            Path path = suffix.isEmpty() ? database : Path.of(database + suffix);
            try
            {
                Files.deleteIfExists(path);
            }
            catch(IOException e)
            {
                if(failure == null)
                {
                    failure = e;
                }
                else
                {
                    failure.addSuppressed(e);
                }
            }
        }
        if(failure != null)
        {
            throw failure;
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException
    {
        try
        {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch(AtomicMoveNotSupportedException e)
        {
            throw new IOException("This drive does not support the atomic folder move required for a safe import.",
                e);
        }
    }

    private static void moveAtomicallyReplacing(Path source, Path target) throws IOException
    {
        try
        {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch(AtomicMoveNotSupportedException e)
        {
            throw new IOException("This drive does not support the atomic database replacement required for a " +
                "safe migration.", e);
        }
    }

    private static long requiredImportSpace(Path sourceRoot, MigrationState sourceState)
        throws IOException, SQLException
    {
        long databaseFootprint = sqliteFootprint(SdrTrunkDatabasePath.getDatabasePath(sourceRoot));
        long required = sourceState.alpha7() ?
            alpha7DatabaseWorkingSpace(SdrTrunkDatabasePath.getDatabasePath(sourceRoot), 3) :
            databaseFootprint;
        Path vault = EncryptionKeyVaultPath.getVaultPath(sourceRoot);

        if(Files.isRegularFile(vault))
        {
            required = safeAdd(required, sqliteFootprint(vault));
        }

        for(String directory : COPIED_DIRECTORIES)
        {
            Path source = sourceRoot.resolve(directory);

            if(Files.isDirectory(source))
            {
                try(var paths = Files.walk(source))
                {
                    for(Path file : paths.filter(Files::isRegularFile).toList())
                    {
                        required = safeAdd(required, Files.size(file));
                    }
                }
            }
        }

        return safeAdd(required, FREE_SPACE_MARGIN_BYTES);
    }

    /**
     * Conservative extra working-space bound for Alias-v3's matcher-row and protocol-family split rewrite. The
     * estimate is driven by the actual duplicated text, matcher and route counts, and SQLite page size instead of
     * relying only on a fixed database-size multiplier.
     */
    static long alpha7AliasExpansionEstimate(Path database) throws SQLException
    {
        try(Connection connection = openReadOnly(database);
            Statement pageStatement = connection.createStatement();
            ResultSet pageResult = pageStatement.executeQuery("PRAGMA page_size"))
        {
            if(!pageResult.next())
            {
                throw new SQLException("Unable to read the Alpha 7 database page size");
            }
            long pageSize = pageResult.getLong(1);
            long estimate = 0L;
            try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery("""
                WITH matcher_payload AS (
                    SELECT alias_id, length(CAST(coalesce(protocol, '') AS BLOB)) AS bytes
                    FROM alias_talkgroup
                    UNION ALL
                    SELECT alias_id, length(CAST(coalesce(protocol, '') AS BLOB)) FROM alias_radio
                    UNION ALL
                    SELECT alias_id, 0 FROM alias_status
                    UNION ALL
                    SELECT alias_id, length(CAST(coalesce(tone_sequence, '') AS BLOB))
                    FROM alias_tone_sequence
                    UNION ALL
                    SELECT alias_id,
                           length(CAST(coalesce(identifier_type, '') AS BLOB)) +
                           length(CAST(coalesce(text_value, '') AS BLOB)) +
                           length(CAST(coalesce(text_value_2, '') AS BLOB))
                    FROM alias_text_identifier
                ), matcher AS (
                    SELECT alias_id, count(*) AS matcher_count, coalesce(sum(bytes), 0) AS matcher_bytes
                    FROM matcher_payload GROUP BY alias_id
                ), route AS (
                    SELECT alias_id, count(*) AS route_count,
                           coalesce(sum(length(CAST(coalesce(channel_name, '') AS BLOB))), 0) AS route_bytes
                    FROM alias_broadcast_channel GROUP BY alias_id
                )
                SELECT
                    length(CAST(coalesce(alias.name, '') AS BLOB)) +
                    length(CAST(coalesce(alias.description, '') AS BLOB)) +
                    length(CAST(coalesce(alias.alias_list_name, '') AS BLOB)) +
                    length(CAST(coalesce(alias.group_name, '') AS BLOB)) +
                    length(CAST(coalesce(alias.icon_name, '') AS BLOB)) AS alias_bytes,
                    coalesce(matcher.matcher_count, 0), coalesce(matcher.matcher_bytes, 0),
                    coalesce(route.route_count, 0), coalesce(route.route_bytes, 0)
                FROM alias
                LEFT JOIN matcher ON matcher.alias_id=alias.id
                LEFT JOIN route ON route.alias_id=alias.id
                """))
            {
                while(resultSet.next())
                {
                    //A crossing P25 range can split in two and each result can be preserved in up to four families.
                    long targetRows = safeMultiply(resultSet.getLong(2), 8L);
                    long aliasPayload = safeAdd(safeMultiply(targetRows, resultSet.getLong(1)),
                        safeMultiply(resultSet.getLong(3), 8L));
                    //One table page and up to one page in each of the four Alias-v4 matcher indexes per target row.
                    long aliasPages = safeMultiply(safeMultiply(targetRows, pageSize), 5L);
                    long targetRoutes = safeMultiply(targetRows, resultSet.getLong(4));
                    //The channel name is stored in the table, UNIQUE autoindex, and explicit name index.
                    long routePayload = safeMultiply(
                        safeMultiply(targetRows, resultSet.getLong(5)), 3L);
                    long routePages = safeMultiply(safeMultiply(targetRoutes, pageSize), 3L);
                    estimate = safeAdd(estimate,
                        safeAdd(safeAdd(aliasPayload, aliasPages), safeAdd(routePayload, routePages)));
                }
            }
            return estimate;
        }
    }

    static long alpha7AliasWorkingSpace(Path database) throws SQLException
    {
        //Expanded alias pages can coexist in the database and WAL until the migrator's final checkpoint.
        return safeMultiply(alpha7AliasExpansionEstimate(database), 2L);
    }

    static long alpha7DatabaseWorkingSpace(Path database, int databaseCopies) throws IOException, SQLException
    {
        long footprint = sqliteFootprint(database);
        return safeAdd(safeMultiply(footprint, databaseCopies), alpha7AliasWorkingSpace(database));
    }

    private static void requireSeparatePhysicalRoots(Path sourceRoot, Path targetRoot) throws IOException
    {
        Path physicalSource = sourceRoot.toRealPath();
        Path targetParent = targetRoot.getParent();
        if(targetParent == null || targetRoot.getFileName() == null)
        {
            throw new IOException("The current portable data folder has no parent: " + targetRoot);
        }
        //Resolve the install parent, but never follow an existing target symlink that promotion would replace.
        Path physicalTarget = effectivePhysicalPath(targetParent).resolve(targetRoot.getFileName()).normalize();
        if(physicalSource.startsWith(physicalTarget) || physicalTarget.startsWith(physicalSource))
        {
            throw new IOException("The previous and current portable data folders overlap.");
        }
    }

    private static Path effectivePhysicalPath(Path path) throws IOException
    {
        Path existing = path;
        Deque<Path> missing = new ArrayDeque<>();
        while(existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS))
        {
            missing.addFirst(existing.getFileName());
            existing = existing.getParent();
        }
        if(existing == null)
        {
            throw new IOException("The current portable data folder has no existing filesystem ancestor: " + path);
        }
        Path effective = existing.toRealPath();
        for(Path element: missing)
        {
            effective = effective.resolve(element);
        }
        return effective.normalize();
    }

    private static long sqliteFootprint(Path database) throws IOException
    {
        long size = Files.size(database);

        for(String suffix : List.of("-wal", "-journal"))
        {
            Path sidecar = Path.of(database + suffix);

            if(Files.isRegularFile(sidecar))
            {
                size = safeAdd(size, Files.size(sidecar));
            }
        }

        return size;
    }

    private static void ensureFreeSpace(Path targetDirectory, long required) throws IOException
    {
        FileStore store = Files.getFileStore(targetDirectory);
        long usable = store.getUsableSpace();

        if(usable < required)
        {
            throw new IOException("Not enough free space for a safe migration. Required approximately " +
                humanSize(required) + "; available " + humanSize(usable) + ".");
        }
    }

    private static long safeAdd(long left, long right)
    {
        if(left > Long.MAX_VALUE - right)
        {
            return Long.MAX_VALUE;
        }

        return left + right;
    }

    private static long safeMultiply(long value, int multiplier)
    {
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }

    private static long safeMultiply(long left, long right)
    {
        if(left < 0 || right < 0)
        {
            throw new IllegalArgumentException("Safe size multiplication requires non-negative values");
        }
        if(left == 0 || right == 0)
        {
            return 0;
        }
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    private static String humanSize(long bytes)
    {
        long mebibytes = Math.max(1, bytes / (1024L * 1024L));
        return mebibytes + " MiB";
    }

    private static void deleteTreeIfExists(Path root) throws IOException
    {
        if(!Files.exists(root))
        {
            return;
        }

        Files.walkFileTree(root, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
            {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException
            {
                if(failure != null)
                {
                    throw failure;
                }

                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @FunctionalInterface
    public interface ProgressListener
    {
        void update(String step);
    }

    @FunctionalInterface
    interface Snapshotter
    {
        void create(Path source, Path destination) throws IOException, SQLException;
    }

    @FunctionalInterface
    interface MigrationRunner
    {
        String run(Path stagedDatabase, Path sourceDataRoot, Path targetDataRoot)
            throws IOException, InterruptedException;
    }

    public record MigrationState(int aliasVersion, int p25Version, Integer trunkedSiteVersion, Integer dmrVersion)
    {
        public MigrationState(int aliasVersion, int p25Version, Integer trunkedSiteVersion)
        {
            this(aliasVersion, p25Version, trunkedSiteVersion, null);
        }

        public boolean supported()
        {
            return current() || alpha7();
        }

        public boolean current()
        {
            return aliasVersion == CURRENT_ALIAS_VERSION && p25Version == CURRENT_P25_VERSION &&
                Integer.valueOf(TrunkedSiteSchema.SCHEMA_VERSION).equals(trunkedSiteVersion) &&
                Integer.valueOf(CURRENT_DMR_VERSION).equals(dmrVersion);
        }

        public boolean alpha7()
        {
            return aliasVersion == ALPHA_7_ALIAS_VERSION && p25Version == ALPHA_7_P25_VERSION &&
                Integer.valueOf(TrunkedSiteSchema.SCHEMA_VERSION).equals(trunkedSiteVersion) && dmrVersion == null;
        }

        public boolean requiresMigration()
        {
            return alpha7();
        }

        public String description()
        {
            return "Alias v" + aliasVersion + ", P25 activity v" + p25Version + ", trunked-site " +
                (trunkedSiteVersion == null ? "not installed" : "v" + trunkedSiteVersion) +
                ", and DMR activity " + (dmrVersion == null ? "not installed" : "v" + dmrVersion);
        }

        public String requiredChanges()
        {
            if(current())
            {
                return "";
            }
            if(alpha7())
            {
                return "convert Alpha 7 channels and aliases and start activity/statistics history fresh";
            }
            return "no bundled transition exists for this schema combination";
        }
    }

    public record MigrationResult(boolean importedPreviousProfile, Path safetyBackup, MigrationState sourceState,
                                  String helperOutput)
    {
        public int sourceVersion()
        {
            return sourceState.p25Version();
        }
    }
}
