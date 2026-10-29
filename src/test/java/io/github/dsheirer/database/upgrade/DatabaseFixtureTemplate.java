/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Set;

/**
 * Reuses expensive monolithic fixture construction, never a mutable database or an open SQLite connection.
 * Callers own disposable target directories. As with the original builders, a failure while writing a copy
 * leaves that private fixture available for inspection; it does not roll back or remove caller files.
 */
final class DatabaseFixtureTemplate
{
    @FunctionalInterface
    interface Factory
    {
        void create(Path database) throws Exception;
    }

    private final int mVersion;
    private final Factory mFactory;
    private byte[] mTemplate;

    DatabaseFixtureTemplate(int version, Factory factory)
    {
        mVersion = version;
        mFactory = factory;
    }

    Path create(Path target) throws Exception
    {
        Path database = target.toAbsolutePath().normalize();
        requireAbsent(database);
        byte[] template = template();
        Files.createDirectories(database.getParent());
        Files.write(database, template, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return database;
    }

    private synchronized byte[] template() throws Exception
    {
        if(mTemplate != null)
        {
            return mTemplate;
        }

        Path directory = Files.createTempDirectory("sdrtrunk-format-" + mVersion + "-fixture-");
        try
        {
            Path database = directory.resolve("fixture.sqlite");
            mFactory.create(database);
            try(var files = Files.list(directory))
            {
                Set<Path> actual = Set.copyOf(files.toList());
                if(!actual.equals(Set.of(database)))
                {
                    throw new IOException("Fixture must close SQLite and finalize all journals before caching: " +
                        actual.stream().map(file -> file.getFileName().toString()).sorted().toList());
                }
            }
            if(Files.isSymbolicLink(database) || !Files.isRegularFile(database))
            {
                throw new IOException("Fixture template must contain an independent regular database file");
            }
            validateFormat(database);
            mTemplate = Files.readAllBytes(database);
            return mTemplate;
        }
        finally
        {
            try(var files = Files.walk(directory))
            {
                for(Path file: files.sorted(Comparator.reverseOrder()).toList())
                {
                    Files.delete(file);
                }
            }
        }
    }

    private void validateFormat(Path database) throws Exception
    {
        try(var connection = ConfigurationHistoryDatabase.openReadOnly(database))
        {
            if(DatabaseFormatCatalog.inspect(connection).version() != mVersion)
            {
                throw new IllegalStateException("Fixture template has the wrong database format");
            }
        }
    }

    private static void requireAbsent(Path database) throws IOException
    {
        for(String suffix: new String[]{"", "-wal", "-shm", "-journal"})
        {
            if(Files.exists(database.resolveSibling(database.getFileName() + suffix), LinkOption.NOFOLLOW_LINKS))
            {
                throw new FileAlreadyExistsException("Fixture cannot replace an existing database or journal");
            }
        }
    }
}
