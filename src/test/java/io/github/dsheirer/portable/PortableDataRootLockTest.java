/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.portable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortableDataRootLockTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void rejectsASecondOwnerAndAllowsReacquisitionAfterClose() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("data");
        PortableDataRootLock first = PortableDataRootLock.acquire(dataRoot);

        try
        {
            assertEquals(dataRoot.toAbsolutePath().normalize(), first.getDataRoot());
            assertTrue(Files.isRegularFile(first.getLockFile()));
            IOException exception = assertThrows(IOException.class, () -> PortableDataRootLock.acquire(dataRoot));
            assertTrue(exception.getMessage().contains("already in use"));
        }
        finally
        {
            first.close();
        }

        try(PortableDataRootLock second = PortableDataRootLock.acquire(dataRoot))
        {
            assertEquals(dataRoot.toAbsolutePath().normalize(), second.getDataRoot());
        }
    }

    @Test
    void closeIsIdempotent() throws Exception
    {
        PortableDataRootLock lock = PortableDataRootLock.acquire(mTemporaryFolder.resolve("data"));
        lock.close();
        lock.close();
    }

    @Test
    void rejectsLockHeldByAnotherProcess() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("cross-process-data");
        Path java = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        Process process = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
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
