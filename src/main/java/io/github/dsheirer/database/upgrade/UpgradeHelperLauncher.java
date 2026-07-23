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

import io.github.dsheirer.portable.PortableApplicationPaths;
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
 * Runs the compiled database upgrade helpers in child Java processes.
 */
public final class UpgradeHelperLauncher
{
    private static final String P25_HELPER_CLASS = P25ActivitySchemaUpgrade.class.getName();
    private static final String TRUNKED_SITE_HELPER_CLASS = TrunkedSiteSchemaInstaller.class.getName();
    private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(30);

    private UpgradeHelperLauncher()
    {
    }

    /**
     * Upgrades and validates a staged database. The live database must never be passed to this method.
     *
     * @return the helper's diagnostic output
     */
    public static String run(Path stagedDatabase) throws IOException, InterruptedException
    {
        return run(stagedDatabase, null, null);
    }

    /**
     * Upgrades a staged database and rebases portable paths that point inside the previous data root.
     */
    public static String run(Path stagedDatabase, Path sourceDataRoot, Path targetDataRoot)
        throws IOException, InterruptedException
    {
        Path normalized = stagedDatabase.toAbsolutePath().normalize();

        if(!Files.isRegularFile(normalized))
        {
            throw new IOException("Staged SQLite database does not exist: " + normalized);
        }

        String p25Output = runHelper(command(normalized, P25_HELPER_CLASS, sourceDataRoot, targetDataRoot),
            "P25 activity");
        String trunkedSiteOutput = runHelper(command(normalized, TRUNKED_SITE_HELPER_CLASS, null, null),
            "trunked-site");

        if(p25Output.isBlank())
        {
            return trunkedSiteOutput;
        }
        else if(trunkedSiteOutput.isBlank())
        {
            return p25Output;
        }

        return p25Output + System.lineSeparator() + trunkedSiteOutput;
    }

    private static String runHelper(List<String> command, String label) throws IOException, InterruptedException
    {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean completed;

        try
        {
            completed = process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch(InterruptedException e)
        {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        }

        if(!completed)
        {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            throw new IOException("The database upgrade helper did not finish the " + label + " upgrade within " +
                PROCESS_TIMEOUT.toMinutes() + " minutes.");
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();

        if(process.exitValue() != 0)
        {
            throw new IOException("The database upgrade helper failed for the " + label + " upgrade with exit code " +
                process.exitValue() + (output.isBlank() ? "." : ":\n" + output));
        }

        return output;
    }

    static List<String> command(Path stagedDatabase) throws IOException
    {
        return command(stagedDatabase, null, null);
    }

    static List<String> command(Path stagedDatabase, Path sourceDataRoot, Path targetDataRoot) throws IOException
    {
        return command(stagedDatabase, P25_HELPER_CLASS, sourceDataRoot, targetDataRoot);
    }

    private static List<String> command(Path stagedDatabase, String helperClass, Path sourceDataRoot,
                                        Path targetDataRoot) throws IOException
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

        Module module = P25ActivitySchemaUpgrade.class.getModule();
        List<String> command = new ArrayList<>();
        command.add(java.toString());

        if(module.isNamed())
        {
            String modulePath = System.getProperty("jdk.module.path");

            if(modulePath == null || modulePath.isBlank())
            {
                modulePath = PortableApplicationPaths.getInstallRoot().resolve("Contents/app/mods").toString();
            }

            command.add("--enable-native-access=" + module.getName() + ",org.xerial.sqlitejdbc");
            command.add("--module-path");
            command.add(modulePath);
            command.add("-m");
            command.add(module.getName() + "/" + helperClass);
        }
        else
        {
            String classPath = System.getProperty("java.class.path");

            if(classPath == null || classPath.isBlank())
            {
                throw new IOException("The Java class path is unavailable for the database upgrade helper.");
            }

            command.add("--enable-native-access=ALL-UNNAMED");
            command.add("-cp");
            command.add(classPath);
            command.add(helperClass);
        }

        command.add(stagedDatabase.toAbsolutePath().normalize().toString());

        if(sourceDataRoot != null)
        {
            command.add(sourceDataRoot.toAbsolutePath().normalize().toString());
            command.add(targetDataRoot.toAbsolutePath().normalize().toString());
        }

        return List.copyOf(command);
    }
}
