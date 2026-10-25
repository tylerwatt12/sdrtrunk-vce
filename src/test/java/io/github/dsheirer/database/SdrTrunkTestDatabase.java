/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Creates isolated current-format profiles from one clean, fully checkpointed database pair.
 */
public final class SdrTrunkTestDatabase
{
    private static Template sTemplate;

    private SdrTrunkTestDatabase()
    {
    }

    public static Path create(Path target) throws IOException, SQLException
    {
        Path database = target.toAbsolutePath().normalize();
        Path activity = ActivityDatabasePath.forConfiguration(database);
        if(Files.exists(database) || Files.exists(activity))
        {
            throw new FileAlreadyExistsException("An isolated test profile cannot replace an existing database pair");
        }
        Files.createDirectories(database.getParent());
        Template template = template();
        Files.write(database, template.configuration(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.write(activity, template.activity(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        String pairId = UUID.randomUUID().toString();
        try(Connection configuration = DriverManager.getConnection("jdbc:sqlite:" + database);
            Connection history = DriverManager.getConnection("jdbc:sqlite:" + activity))
        {
            ConfigurationHistorySchema.setIdentity(configuration, ConfigurationHistorySchema.CONFIGURATION_ROLE, pairId);
            ConfigurationHistorySchema.setIdentity(history, ConfigurationHistorySchema.HISTORY_ROLE, pairId);
        }
        ConfigurationHistoryDatabase.validatePair(database);
        return database;
    }

    private static synchronized Template template() throws IOException, SQLException
    {
        if(sTemplate == null)
        {
            Path directory = Files.createTempDirectory("sdrtrunk-test-database-");
            Template template;
            try
            {
                Path database = directory.resolve("sdrtrunk.sqlite");
                DatabaseFileInstaller.install(database, SdrTrunkDatabaseStartup::createGlobalDatabase);
                template = new Template(Files.readAllBytes(database),
                    Files.readAllBytes(ActivityDatabasePath.forConfiguration(database)));
            }
            finally
            {
                Path[] files;
                try(var paths = Files.list(directory))
                {
                    files = paths.toArray(Path[]::new);
                }

                for(Path file : files)
                {
                    Files.deleteIfExists(file);
                }
                Files.deleteIfExists(directory);
            }
            sTemplate = template;
        }

        return sTemplate;
    }

    private record Template(byte[] configuration, byte[] activity) {}
}
