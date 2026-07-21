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

package io.github.dsheirer.database.importer;

import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.database.importer.LegacyXmlConfigurationMerger.ConflictPolicy;
import io.github.dsheirer.database.importer.LegacyXmlConfigurationMerger.MergeResult;
import io.github.dsheirer.database.importer.LegacyXmlConfigurationMerger.Preview;
import io.github.dsheirer.database.importer.LegacyXmlConfigurationMerger.Summary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Previews and imports a legacy XML playlist into an existing SQLite configuration database.
 */
public class LegacyPlaylistImportService
{
    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private final Path mDatabasePath;

    public LegacyPlaylistImportService(Path databasePath)
    {
        mDatabasePath = databasePath.toAbsolutePath().normalize();
    }

    public PreparedImport prepare(Path sourceXml) throws IOException, SQLException
    {
        Path source = sourceXml.toAbsolutePath().normalize();
        ConfigurationState imported = loadImported(source);
        return new PreparedImport(source, LegacyXmlConfigurationMerger.preview(loadCurrent(), imported));
    }

    public ImportResult execute(PreparedImport preparedImport, ConflictPolicy policy) throws IOException, SQLException
    {
        ConfigurationState current = loadCurrent();
        ConfigurationState imported = loadImported(preparedImport.sourceXml());
        Preview preview = LegacyXmlConfigurationMerger.preview(current, imported);
        MergeResult mergeResult = LegacyXmlConfigurationMerger.merge(current, imported, policy);
        Path backup = createBackup();

        try
        {
            new AliasDatabaseStore(mDatabasePath).replaceAliases(mergeResult.configurationState().getAliases());
            new ConfigurationDatabaseStore(mDatabasePath)
                .replaceConfigurationState(mergeResult.configurationState());
        }
        catch(IOException | SQLException e)
        {
            try
            {
                restoreBackup(backup);
            }
            catch(IOException restoreException)
            {
                e.addSuppressed(restoreException);
            }

            throw e;
        }

        return new ImportResult(preparedImport.sourceXml(), backup, preview, mergeResult.summary());
    }

    private ConfigurationState loadImported(Path sourceXml) throws IOException
    {
        if(!Files.isRegularFile(sourceXml))
        {
            throw new IOException("Legacy SDRTrunk playlist XML does not exist: " + sourceXml);
        }

        ConfigurationState imported = LegacyXmlConfigurationImporter.readConfigurationState(sourceXml);
        LegacyXmlConfigurationImporter.convertLikelyConventionalP25Channels(imported);
        return imported;
    }

    private ConfigurationState loadCurrent() throws IOException, SQLException
    {
        ConfigurationState current = new ConfigurationDatabaseStore(mDatabasePath).loadConfigurationState();
        current.setAliases(new AliasDatabaseStore(mDatabasePath).loadAliases());
        return current;
    }

    private Path createBackup() throws IOException, SQLException
    {
        Path backupDirectory = mDatabasePath.getParent().resolve("backups");
        Files.createDirectories(backupDirectory);
        String stem = "sdrtrunk-before-playlist-import-" + BACKUP_TIMESTAMP.format(LocalDateTime.now());
        Path backup = backupDirectory.resolve(stem + ".sqlite");
        int suffix = 2;

        while(Files.exists(backup))
        {
            backup = backupDirectory.resolve(stem + '-' + suffix++ + ".sqlite");
        }

        String escapedPath = backup.toString().replace("'", "''");
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.execute("VACUUM INTO '" + escapedPath + "'");
        }

        return backup;
    }

    private void restoreBackup(Path backup) throws IOException
    {
        Files.deleteIfExists(Path.of(mDatabasePath + "-wal"));
        Files.deleteIfExists(Path.of(mDatabasePath + "-shm"));
        Files.copy(backup, mDatabasePath, StandardCopyOption.REPLACE_EXISTING);
    }

    public record PreparedImport(Path sourceXml, Preview preview)
    {
    }

    public record ImportResult(Path sourceXml, Path backupPath, Preview preview, Summary summary)
    {
    }
}
