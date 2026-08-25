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
import io.github.dsheirer.database.upgrade.SqliteDatabaseSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * Previews and safely merges a legacy XML playlist into the active SQLite configuration.
 *
 * <p>The source XML is read-only. Existing configuration is retained, imported name conflicts are renamed, a
 * validated SQLite backup is created first, and aliases, list definitions, channels, and streams are committed in one
 * transaction. Desktop callers execute the prepared import through
 * {@link io.github.dsheirer.configuration.ConfigurationManager#applyExternalConfigurationSnapshot} so ordinary
 * configuration saves cannot interleave between the preview check and commit.</p>
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
        Path source = requireSource(sourceXml);
        LoadedImport loadedImport = loadImported(source);
        ConfigurationRepository.FingerprintedSnapshot current =
            new ConfigurationRepository(mDatabasePath).loadWithFingerprint();
        LegacyConfigurationState imported = loadedImport.configuration();
        LegacyConfigurationState currentState = LegacyConfigurationState.from(current.snapshot());

        Preview preview = LegacyXmlConfigurationMerger.preview(currentState, imported);
        requireSupportedContent(preview);
        MergeResult mergeResult = LegacyXmlConfigurationMerger.merge(currentState, imported);
        return new PreparedImport(source, preview, loadedImport.fingerprint(), current.fingerprint(), mergeResult);
    }

    public ImportResult execute(PreparedImport preparedImport) throws IOException, SQLException
    {
        if(preparedImport == null)
        {
            throw new IllegalArgumentException("Prepared import cannot be null");
        }

        Path source = requireSource(preparedImport.sourceXml());

        if(!preparedImport.sourceFingerprint().equals(fileFingerprint(source)))
        {
            throw new IOException("The playlist changed after the preview. Preview it again.");
        }

        if(!preparedImport.configurationFingerprint().equals(new ConfigurationRepository(mDatabasePath).fingerprint()))
        {
            throw new IOException("The active configuration changed after the preview. Preview it again.");
        }

        Path backup = createBackup();
        LegacyConfigurationState merged = preparedImport.mergeResult().configurationState();
        ConfigurationRepository repository = new ConfigurationRepository(mDatabasePath);
        repository.replace(merged.toConfigurationSnapshot(repository));
        return new ImportResult(source, backup, preparedImport.preview(),
            preparedImport.mergeResult().summary());
    }

    private Path requireSource(Path sourceXml) throws IOException
    {
        if(sourceXml == null)
        {
            throw new IOException("Select an SDRTrunk playlist XML file.");
        }

        Path source = sourceXml.toAbsolutePath().normalize();

        if(!Files.isRegularFile(source))
        {
            throw new IOException("Legacy SDRTrunk playlist XML does not exist: " + source);
        }

        return source;
    }

    private static void requireSupportedContent(Preview preview) throws IOException
    {
        if(preview.aliasListCount() == 0 && preview.aliasCount() == 0 &&
            preview.channelCount() == 0 && preview.streamCount() == 0)
        {
            throw new IOException("The playlist contains no supported aliases, channels, or streaming configurations.");
        }
    }

    private LoadedImport loadImported(Path sourceXml) throws IOException
    {
        MessageDigest digest = sha256();
        LegacyConfigurationState imported;
        try(DigestInputStream inputStream = new DigestInputStream(Files.newInputStream(sourceXml), digest))
        {
            imported = LegacyXmlConfigurationImporter.readConfigurationState(inputStream);
            byte[] buffer = new byte[8192];
            while(inputStream.read(buffer) >= 0)
            {
                //Drain any parser lookahead remainder so the token covers the complete captured source.
            }
        }
        LegacyXmlConfigurationImporter.convertLikelyConventionalP25Channels(imported);
        return new LoadedImport(imported, HexFormat.of().formatHex(digest.digest()));
    }

    private static String fileFingerprint(Path source) throws IOException
    {
        MessageDigest digest = sha256();

        try(InputStream inputStream = Files.newInputStream(source))
        {
            byte[] buffer = new byte[8192];
            int length;

            while((length = inputStream.read(buffer)) >= 0)
            {
                if(length > 0)
                {
                    digest.update(buffer, 0, length);
                }
            }
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256()
    {
        try
        {
            return MessageDigest.getInstance("SHA-256");
        }
        catch(NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 is unavailable", e);
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
        private final Path mSourceXml;
        private final Preview mPreview;
        private final String mSourceFingerprint;
        private final String mConfigurationFingerprint;
        private final MergeResult mMergeResult;

        private PreparedImport(Path sourceXml, Preview preview, String sourceFingerprint,
                               String configurationFingerprint, MergeResult mergeResult)
        {
            mSourceXml = sourceXml;
            mPreview = preview;
            mSourceFingerprint = sourceFingerprint;
            mConfigurationFingerprint = configurationFingerprint;
            mMergeResult = mergeResult;
        }

        public Path sourceXml()
        {
            return mSourceXml;
        }

        public Preview preview()
        {
            return mPreview;
        }

        private String sourceFingerprint()
        {
            return mSourceFingerprint;
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

    private record LoadedImport(LegacyConfigurationState configuration, String fingerprint)
    {
    }
}
