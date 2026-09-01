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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationDatabaseMigratorTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void reportsHelpWithoutTouchingAFile()
    {
        CommandResult result = run("--help");
        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode());
        assertTrue(result.output().contains("ApplicationDatabaseMigrator"));
    }

    @Test
    void refusesANewerKnownFormatMarkerExplicitly() throws Exception
    {
        Path database = Format2TestDatabase.create(newStagedDatabase());
        setMarker(database, 3);

        CommandResult result = run(database.toString());

        assertEquals(ApplicationDatabaseMigrator.EXIT_UNSUPPORTED_VERSION, result.exitCode());
        assertTrue(result.error().contains("newer than this build supports"));
    }

    @Test
    void refusesANewerMarkerBeforeLookingUpAnUnknownFingerprint() throws Exception
    {
        Path database = Format2TestDatabase.create(newStagedDatabase());
        setMarker(database, 3);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE TABLE future_format_object(id INTEGER PRIMARY KEY)");
        }

        CommandResult result = run(database.toString());

        assertEquals(ApplicationDatabaseMigrator.EXIT_UNSUPPORTED_VERSION, result.exitCode());
        assertTrue(result.error().contains("newer than this build supports"));
    }

    @Test
    void refusesAPathThatIsNotAnApplicationStage() throws Exception
    {
        Path database = Format2TestDatabase.create(mTemporaryFolder.resolve("direct.sqlite"));

        CommandResult result = run(database.toString());

        assertEquals(ApplicationDatabaseMigrator.EXIT_INPUT, result.exitCode());
        assertTrue(result.error().contains("application-created staged database"));
    }

    private Path newStagedDatabase()
    {
        return mTemporaryFolder.resolve(".sdrtrunk.sqlite.migration-" + UUID.randomUUID());
    }

    private static void setMarker(Path database, int version) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.prepareStatement("""
                INSERT INTO database_metadata(key, value, updated_at_ms)
                VALUES ('database_format_version', ?, 1)
                ON CONFLICT(key) DO UPDATE SET value=excluded.value
                """))
        {
            statement.setString(1, Integer.toString(version));
            statement.executeUpdate();
        }
    }

    private static CommandResult run(String... arguments)
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int exitCode = ApplicationDatabaseMigrator.run(arguments, new PrintStream(output), new PrintStream(error));
        return new CommandResult(exitCode, output.toString(StandardCharsets.UTF_8),
            error.toString(StandardCharsets.UTF_8));
    }

    private record CommandResult(int exitCode, String output, String error)
    {
    }
}
