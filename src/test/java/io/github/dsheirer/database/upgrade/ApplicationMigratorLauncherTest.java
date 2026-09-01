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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationMigratorLauncherTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void buildsClasspathChildProcessCommand() throws Exception
    {
        Path stagedDatabase = mTemporaryFolder.resolve("stage/database/sdrtrunk.sqlite");
        Path sourceDataRoot = mTemporaryFolder.resolve("old data");
        Path targetDataRoot = mTemporaryFolder.resolve("new data");
        String classPath = System.getProperty("java.class.path");
        Path javaHome = Path.of(System.getProperty("java.home", "")).toAbsolutePath().normalize();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path java = javaHome.resolve("bin").resolve(windows ? "java.exe" : "java");

        assertEquals(List.of(java.toString(), "--enable-native-access=ALL-UNNAMED", "-cp", classPath,
                ApplicationDatabaseMigrator.class.getName(), stagedDatabase.toAbsolutePath().normalize().toString(),
                sourceDataRoot.toAbsolutePath().normalize().toString(),
                targetDataRoot.toAbsolutePath().normalize().toString()),
            ApplicationMigratorLauncher.command(stagedDatabase, sourceDataRoot, targetDataRoot));
    }

    @Test
    void rejectsMissingClasspath() throws Exception
    {
        String originalClassPath = System.getProperty("java.class.path");

        try
        {
            System.setProperty("java.class.path", " ");
            assertThrows(IOException.class,
                () -> ApplicationMigratorLauncher.command(mTemporaryFolder.resolve("sdrtrunk.sqlite")));
        }
        finally
        {
            if(originalClassPath == null)
            {
                System.clearProperty("java.class.path");
            }
            else
            {
                System.setProperty("java.class.path", originalClassPath);
            }
        }
    }

    @Test
    void boundedOutputRetainsTheBeginningAndFinalFailure() throws Exception
    {
        String beginning = "migration-plan-beginning\n";
        String ending = "\nERROR: final migration failure";
        byte[] padding = new byte[2 * 1024 * 1024];
        java.util.Arrays.fill(padding, (byte)'x');
        var source = new java.io.ByteArrayOutputStream();
        source.write(beginning.getBytes(StandardCharsets.UTF_8));
        source.write(padding);
        source.write(ending.getBytes(StandardCharsets.UTF_8));

        byte[] captured = ApplicationMigratorLauncher.readBounded(
            new ByteArrayInputStream(source.toByteArray()));
        String output = new String(captured, StandardCharsets.UTF_8);

        assertEquals(1024 * 1024, captured.length);
        assertTrue(output.startsWith(beginning));
        assertTrue(output.contains("output truncated; final diagnostics follow"));
        assertTrue(output.endsWith(ending));
    }
}
