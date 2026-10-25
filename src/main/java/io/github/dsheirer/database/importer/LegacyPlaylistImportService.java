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

import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.configuration.ConfigurationRepository;
import io.github.dsheirer.database.importer.LegacyXmlConfigurationMerger.MergeResult;
import io.github.dsheirer.database.importer.LegacyXmlConfigurationMerger.Preview;
import io.github.dsheirer.database.importer.LegacyXmlConfigurationMerger.Summary;
import io.github.dsheirer.database.importer.LegacyXmlConfigurationImporter.PreparedPlaylist;
import io.github.dsheirer.database.upgrade.SqliteDatabaseSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Previews and safely merges a legacy XML playlist into the active SQLite configuration.
 *
 * <p>The source XML is read-only. Existing configuration is retained, imported name conflicts are renamed, a
 * validated SQLite backup is created first, and aliases, list definitions, channels, and streams are committed in one
 * transaction. The setup wizard executes the prepared import before runtime configuration services exist, while
 * holding the portable-data lock.</p>
 */
public class LegacyPlaylistImportService
{
    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private final Path mDatabasePath;

    public LegacyPlaylistImportService(Path databasePath)
    {
        if(databasePath == null)
        {
            throw new IllegalArgumentException("Database path cannot be null");
        }

        mDatabasePath = databasePath.toAbsolutePath().normalize();
    }

    public PreparedImport prepare(Path sourceXml) throws IOException, SQLException
    {
        PreparedPlaylist source = LegacyXmlConfigurationImporter.prepare(sourceXml);
        ConfigurationRepository.FingerprintedSnapshot current =
            new ConfigurationRepository(mDatabasePath).loadWithFingerprint();
        LegacyConfigurationState imported = source.configuration();
        LegacyConfigurationState currentState = LegacyConfigurationState.from(current.snapshot());

        Preview preview = LegacyXmlConfigurationMerger.preview(currentState, imported);
        requireSupportedContent(preview);
        MergeResult mergeResult = LegacyXmlConfigurationMerger.merge(currentState, imported);
        return new PreparedImport(source, preview, current.fingerprint(), mergeResult);
    }

    public ImportResult execute(PreparedImport preparedImport) throws IOException, SQLException
    {
        if(preparedImport == null)
        {
            throw new IllegalArgumentException("Prepared import cannot be null");
        }

        preparedImport.mSource.requireUnchanged();

        if(!preparedImport.configurationFingerprint().equals(new ConfigurationRepository(mDatabasePath).fingerprint()))
        {
            throw new IOException("The active configuration changed after the preview. Preview it again.");
        }

        Path backup = createBackup();
        LegacyConfigurationState merged = preparedImport.mergeResult().configurationState();
        ConfigurationRepository repository = new ConfigurationRepository(mDatabasePath);
        repository.replace(merged.toConfigurationSnapshot(repository));
        return new ImportResult(preparedImport.sourceXml(), backup, preparedImport.preview(),
            preparedImport.mergeResult().summary());
    }

    private static void requireSupportedContent(Preview preview) throws IOException
    {
        if(preview.aliasListCount() == 0 && preview.aliasCount() == 0 &&
            preview.channelCount() == 0 && preview.streamCount() == 0)
        {
            throw new IOException("The playlist contains no supported aliases, channels, or streaming configurations.");
        }
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

        SqliteDatabaseSnapshot.create(mDatabasePath, backup);

        try
        {
            SdrTrunkDatabaseStartup.validateGlobalDatabase(backup);
            requireQuickCheck(backup);
        }
        catch(IOException | SQLException e)
        {
            try
            {
                Files.deleteIfExists(backup);
            }
            catch(IOException cleanupException)
            {
                e.addSuppressed(cleanupException);
            }

            throw e;
        }

        return backup;
    }

    private static void requireQuickCheck(Path database) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement())
        {
            try(ResultSet resultSet = statement.executeQuery("PRAGMA quick_check"))
            {
                if(!resultSet.next() || !"ok".equalsIgnoreCase(resultSet.getString(1)))
                {
                    throw new IOException("The pre-import database backup failed SQLite validation.");
                }
            }

            try(ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_check"))
            {
                if(resultSet.next())
                {
                    throw new IOException("The pre-import database backup has invalid references.");
                }
            }
        }
    }

    public static final class PreparedImport
    {
        private final PreparedPlaylist mSource;
        private final Preview mPreview;
        private final String mConfigurationFingerprint;
        private final MergeResult mMergeResult;

        private PreparedImport(PreparedPlaylist source, Preview preview,
                               String configurationFingerprint, MergeResult mergeResult)
        {
            mSource = source;
            mPreview = preview;
            mConfigurationFingerprint = configurationFingerprint;
            mMergeResult = mergeResult;
        }

        public Path sourceXml()
        {
            return mSource.sourceXml();
        }

        public Preview preview()
        {
            return mPreview;
        }

        private String configurationFingerprint()
        {
            return mConfigurationFingerprint;
        }

        private MergeResult mergeResult()
        {
            return mMergeResult;
        }
    }

    public record ImportResult(Path sourceXml, Path backupPath, Preview preview, Summary summary)
    {
    }

}
