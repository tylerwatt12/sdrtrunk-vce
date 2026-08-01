/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseFileInstallerTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void installsCompletedDatabase() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");

        DatabaseFileInstaller.install(database, temporary ->
        {
            try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + temporary);
                Statement statement = connection.createStatement())
            {
                statement.execute("CREATE TABLE installed (id INTEGER PRIMARY KEY)");
            }
        });

        assertTrue(Files.isRegularFile(database));
        assertNoCreatingFiles();
    }

    @Test
    void removesIncompleteDatabaseWhenBuilderFails() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");

        assertThrows(IOException.class, () -> DatabaseFileInstaller.install(database, temporary ->
        {
            Files.writeString(temporary, "incomplete");
            throw new IOException("expected failure");
        }));

        assertFalse(Files.exists(database));
        assertNoCreatingFiles();
    }

    @Test
    void refusesToReplaceExistingDatabase() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        Files.writeString(database, "existing");

        assertThrows(IOException.class, () -> DatabaseFileInstaller.install(database, ignored -> { }));
        assertTrue(Files.isRegularFile(database));
    }

    @Test
    void concurrentInstallCannotDeleteOrReplaceAnotherInstallStage() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        CountDownLatch builderEntered = new CountDownLatch(1);
        CountDownLatch releaseBuilder = new CountDownLatch(1);
        try(var executor = Executors.newSingleThreadExecutor())
        {
            var first = executor.submit(() ->
            {
                DatabaseFileInstaller.install(database, temporary ->
                {
                    builderEntered.countDown();
                    try
                    {
                        if(!releaseBuilder.await(10, TimeUnit.SECONDS))
                        {
                            throw new IOException("Timed out waiting to finish the first test install");
                        }
                    }
                    catch(InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while testing concurrent install", e);
                    }
                    try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + temporary);
                        Statement statement = connection.createStatement())
                    {
                        statement.execute("CREATE TABLE installed (id INTEGER PRIMARY KEY)");
                    }
                });
                return null;
            });

            assertTrue(builderEntered.await(10, TimeUnit.SECONDS));
            IOException competing = assertThrows(IOException.class,
                () -> DatabaseFileInstaller.install(database, ignored -> { }));
            assertTrue(competing.getMessage().contains("already in progress"));
            releaseBuilder.countDown();
            first.get(10, TimeUnit.SECONDS);
        }

        assertTrue(Files.isRegularFile(database));
        assertNoCreatingFiles();
    }

    private void assertNoCreatingFiles() throws Exception
    {
        try(var paths = Files.list(mTemporaryFolder))
        {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().contains(".creating-")));
        }
    }
}
