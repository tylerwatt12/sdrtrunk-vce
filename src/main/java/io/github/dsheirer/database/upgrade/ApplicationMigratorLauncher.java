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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * Runs the compiled application database migrator in a child Java process.
 */
public final class ApplicationMigratorLauncher
{
    private static final String APPLICATION_MIGRATOR_CLASS = ApplicationDatabaseMigrator.class.getName();
    private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(30);
    private static final int MAX_CAPTURED_OUTPUT_BYTES = 1024 * 1024;
    private static final byte[] TRUNCATION_NOTICE =
        "\n[Application Migrator output truncated; final diagnostics follow]\n".getBytes(StandardCharsets.UTF_8);
    private static final int HEAD_CAPTURED_OUTPUT_BYTES =
        (MAX_CAPTURED_OUTPUT_BYTES - TRUNCATION_NOTICE.length) / 2;
    private static final int TAIL_CAPTURED_OUTPUT_BYTES =
        MAX_CAPTURED_OUTPUT_BYTES - TRUNCATION_NOTICE.length - HEAD_CAPTURED_OUTPUT_BYTES;

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
        CompletableFuture<byte[]> outputReader = CompletableFuture.supplyAsync(() ->
        {
            try
            {
                return readBounded(process.getInputStream());
            }
            catch(IOException e)
            {
                throw new CompletionException(e);
            }
        });
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

        String output;

        try
        {
            output = new String(outputReader.join(), StandardCharsets.UTF_8).trim();
        }
        catch(CompletionException e)
        {
            Throwable cause = e.getCause();
            if(cause instanceof IOException ioException)
            {
                throw ioException;
            }
            throw e;
        }

        if(process.exitValue() != 0)
        {
            throw new IOException("The application database migrator failed with exit code " +
                process.exitValue() + (output.isBlank() ? "." : ":\n" + output));
        }

        return output;
    }

    /**
     * Drains child output while it runs so a detailed multi-step report cannot fill the process pipe and deadlock.
     * Output beyond the diagnostic limit is discarded while the stream continues to be drained.
     */
    static byte[] readBounded(InputStream input) throws IOException
    {
        byte[] head = new byte[HEAD_CAPTURED_OUTPUT_BYTES];
        byte[] tail = new byte[TAIL_CAPTURED_OUTPUT_BYTES];
        byte[] buffer = new byte[8192];
        int headBytes = 0;
        int tailBytes = 0;
        int tailWrite = 0;
        long totalBytes = 0;
        int read;

        while((read = input.read(buffer)) >= 0)
        {
            totalBytes += read;
            int offset = 0;

            if(headBytes < head.length)
            {
                int copy = Math.min(read, head.length - headBytes);
                System.arraycopy(buffer, 0, head, headBytes, copy);
                headBytes += copy;
                offset += copy;
            }

            while(offset < read)
            {
                int copy = Math.min(read - offset, tail.length - tailWrite);
                System.arraycopy(buffer, offset, tail, tailWrite, copy);
                tailWrite = (tailWrite + copy) % tail.length;
                tailBytes = Math.min(tail.length, tailBytes + copy);
                offset += copy;
            }
        }

        boolean truncated = totalBytes > (long)headBytes + tailBytes;
        int resultLength = headBytes + tailBytes + (truncated ? TRUNCATION_NOTICE.length : 0);
        byte[] result = new byte[resultLength];
        System.arraycopy(head, 0, result, 0, headBytes);
        int resultOffset = headBytes;

        if(truncated)
        {
            System.arraycopy(TRUNCATION_NOTICE, 0, result, resultOffset, TRUNCATION_NOTICE.length);
            resultOffset += TRUNCATION_NOTICE.length;
        }

        int tailStart = tailBytes == tail.length ? tailWrite : 0;
        int firstTailPart = Math.min(tailBytes, tail.length - tailStart);
        System.arraycopy(tail, tailStart, result, resultOffset, firstTailPart);
        if(firstTailPart < tailBytes)
        {
            System.arraycopy(tail, 0, result, resultOffset + firstTailPart, tailBytes - firstTailPart);
        }

        return result;
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
