/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;

/**
 * Creates isolated test databases from one clean, fully checkpointed SDRTrunk database image.
 */
public final class SdrTrunkTestDatabase
{
    private static byte[] sTemplate;

    private SdrTrunkTestDatabase()
    {
    }

    public static Path create(Path target) throws IOException, SQLException
    {
        Path database = target.toAbsolutePath().normalize();
        Files.createDirectories(database.getParent());
        Files.write(database, template(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return database;
    }

    private static synchronized byte[] template() throws IOException, SQLException
    {
        if(sTemplate == null)
        {
            Path directory = Files.createTempDirectory("sdrtrunk-test-database-");
            byte[] template;
            try
            {
                Path database = directory.resolve("sdrtrunk.sqlite");
                DatabaseFileInstaller.install(database, SdrTrunkDatabaseStartup::createGlobalDatabase);
                template = Files.readAllBytes(database);
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
}
