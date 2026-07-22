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

package io.github.dsheirer.portable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortableDataRootLockTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void createsOnlyDataRootAndLockFile() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("data");

        try(PortableDataRootLock lock = PortableDataRootLock.acquire(dataRoot))
        {
            assertEquals(dataRoot.toAbsolutePath().normalize(), lock.getDataRoot());
            assertEquals(dataRoot.resolve(PortableDataRootLock.LOCK_FILE_NAME).toAbsolutePath().normalize(),
                lock.getLockFile());

            try(var children = Files.list(dataRoot))
            {
                assertEquals(List.of(lock.getLockFile()), children.toList());
            }
        }

        assertTrue(Files.isRegularFile(dataRoot.resolve(PortableDataRootLock.LOCK_FILE_NAME)));
    }

    @Test
    void rejectsOverlappingLockWithClearMessage() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("data");

        try(PortableDataRootLock ignored = PortableDataRootLock.acquire(dataRoot))
        {
            IOException exception = assertThrows(IOException.class, () -> PortableDataRootLock.acquire(dataRoot));

            assertTrue(exception.getMessage().contains("already in use"));
            assertTrue(exception.getMessage().contains(dataRoot.toAbsolutePath().normalize().toString()));
            assertTrue(exception.getCause() instanceof java.nio.channels.OverlappingFileLockException);
        }
    }

    @Test
    void closeIsIdempotentAndAllowsReacquisition() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("data");
        PortableDataRootLock first = PortableDataRootLock.acquire(dataRoot);

        first.close();
        first.close();

        try(PortableDataRootLock second = PortableDataRootLock.acquire(dataRoot))
        {
            assertEquals(dataRoot.toAbsolutePath().normalize(), second.getDataRoot());
        }
    }

    @Test
    void locksAreScopedToTheExactDataRoot() throws Exception
    {
        try(PortableDataRootLock first = PortableDataRootLock.acquire(mTemporaryFolder.resolve("first"));
            PortableDataRootLock second = PortableDataRootLock.acquire(mTemporaryFolder.resolve("second")))
        {
            assertTrue(first.getLockFile().startsWith(first.getDataRoot()));
            assertTrue(second.getLockFile().startsWith(second.getDataRoot()));
        }
    }

    @Test
    void rejectsLockOwnedByAnotherProcess() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("cross-process-data");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"),
            LockHolderProcess.class.getName(), dataRoot.toString()).redirectErrorStream(true).start();

        try(BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(),
            StandardCharsets.UTF_8)))
        {
            assertEquals("LOCKED", output.readLine());
            IOException exception = assertThrows(IOException.class, () -> PortableDataRootLock.acquire(dataRoot));
            assertTrue(exception.getMessage().contains("already in use"));

            process.getOutputStream().write('\n');
            process.getOutputStream().flush();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
        }
        finally
        {
            process.destroyForcibly();
        }

        try(PortableDataRootLock ignored = PortableDataRootLock.acquire(dataRoot))
        {
            //The operating system released the child process lock.
        }
    }

    public static class LockHolderProcess
    {
        public static void main(String[] arguments) throws Exception
        {
            try(PortableDataRootLock ignored = PortableDataRootLock.acquire(Path.of(arguments[0])))
            {
                System.out.println("LOCKED");
                System.out.flush();
                System.in.read();
            }
        }
    }
}
