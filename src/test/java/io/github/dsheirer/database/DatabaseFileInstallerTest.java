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
        assertFalse(Files.exists(mTemporaryFolder.resolve("sdrtrunk.sqlite.creating")));
    }

    @Test
    void removesIncompleteDatabaseWhenBuilderFails()
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");

        assertThrows(IOException.class, () -> DatabaseFileInstaller.install(database, temporary ->
        {
            Files.writeString(temporary, "incomplete");
            throw new IOException("expected failure");
        }));

        assertFalse(Files.exists(database));
        assertFalse(Files.exists(mTemporaryFolder.resolve("sdrtrunk.sqlite.creating")));
    }

    @Test
    void refusesToReplaceExistingDatabase() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        Files.writeString(database, "existing");

        assertThrows(IOException.class, () -> DatabaseFileInstaller.install(database, ignored -> { }));
        assertTrue(Files.isRegularFile(database));
    }
}
