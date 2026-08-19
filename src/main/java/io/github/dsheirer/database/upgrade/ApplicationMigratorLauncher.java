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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Runs the compiled application database migrator in a child Java process.
 */
public final class ApplicationMigratorLauncher
{
    private static final String APPLICATION_MIGRATOR_CLASS = ApplicationDatabaseMigrator.class.getName();
    private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(30);

    private ApplicationMigratorLauncher()
    {
    }

    /**
     * Migrates and validates a staged database. The live database must never be passed to this method.
     *
     * @return the Application Migrator's diagnostic output
     */
    public static String run(Path stagedDatabase) throws IOException, InterruptedException
    {
        return run(stagedDatabase, null, null);
    }

    /**
     * Migrates a staged database and rebases portable paths that point inside the previous data root.
     */
    public static String run(Path stagedDatabase, Path sourceDataRoot, Path targetDataRoot)
        throws IOException, InterruptedException
    {
        Path normalized = stagedDatabase.toAbsolutePath().normalize();

        if(!Files.isRegularFile(normalized))
        {
            throw new IOException("Staged SQLite database does not exist: " + normalized);
        }

        return runMigrator(command(normalized, sourceDataRoot, targetDataRoot));
    }

    private static String runMigrator(List<String> command) throws IOException, InterruptedException
    {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean completed;

        try
        {
            completed = process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch(InterruptedException e)
        {
            try
            {
                destroyAndWait(process);
            }
            catch(InterruptedException cleanupInterruption)
            {
                e.addSuppressed(cleanupInterruption);
            }
            Thread.currentThread().interrupt();
            throw e;
        }

        if(!completed)
        {
            destroyAndWait(process);
            throw new IOException("The application database migrator did not finish within " +
                PROCESS_TIMEOUT.toMinutes() + " minutes.");
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();

        if(process.exitValue() != 0)
        {
            throw new IOException("The application database migrator failed with exit code " +
                process.exitValue() + (output.isBlank() ? "." : ":\n" + output));
        }

        return output;
    }

    private static void destroyAndWait(Process process) throws InterruptedException
    {
        process.destroyForcibly();
        InterruptedException interruption = null;
        while(process.isAlive())
        {
            try
            {
                process.waitFor();
            }
            catch(InterruptedException e)
            {
                if(interruption == null)
                {
                    interruption = e;
                }
                else
                {
                    interruption.addSuppressed(e);
                }
            }
        }

        if(interruption != null)
        {
            Thread.currentThread().interrupt();
            throw interruption;
        }
    }

    static List<String> command(Path stagedDatabase) throws IOException
    {
        return command(stagedDatabase, null, null);
    }

    static List<String> command(Path stagedDatabase, Path sourceDataRoot, Path targetDataRoot) throws IOException
    {
        if((sourceDataRoot == null) != (targetDataRoot == null))
        {
            throw new IOException("Both source and target data roots are required for portable path relocation.");
        }

        Path javaHome = Path.of(System.getProperty("java.home", "")).toAbsolutePath().normalize();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path java = javaHome.resolve("bin").resolve(windows ? "java.exe" : "java");

        if(!Files.isRegularFile(java))
        {
            throw new IOException("The packaged Java executable was not found: " + java);
        }

        String classPath = System.getProperty("java.class.path");

        if(classPath == null || classPath.isBlank())
        {
            throw new IOException("The Java class path is unavailable for the Application Migrator.");
        }

        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-cp");
        command.add(classPath);
        command.add(APPLICATION_MIGRATOR_CLASS);

        command.add(stagedDatabase.toAbsolutePath().normalize().toString());

        if(sourceDataRoot != null)
        {
            command.add(sourceDataRoot.toAbsolutePath().normalize().toString());
            command.add(targetDataRoot.toAbsolutePath().normalize().toString());
        }

        return List.copyOf(command);
    }
}
