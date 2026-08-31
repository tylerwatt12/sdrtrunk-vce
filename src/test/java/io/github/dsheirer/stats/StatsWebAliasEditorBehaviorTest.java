/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Executes DOM-independent Alias editor parity behavior. */
class StatsWebAliasEditorBehaviorTest
{
    private static final Path TEST_SCRIPT = Path.of("src", "test", "js", "stats-web",
        "alias-editor.test.js");
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");

    @Test
    void keepsTheCompleteCatalogToneOrderAndFullSetMembershipContracts() throws Exception
    {
        assertTrue(Files.isRegularFile(TEST_SCRIPT), () -> "Missing " + TEST_SCRIPT.toAbsolutePath());
        assertTrue(Files.isRegularFile(APP_JAVASCRIPT), () -> "Missing " + APP_JAVASCRIPT.toAbsolutePath());
        String node = System.getenv().getOrDefault("NODE_BINARY", "node");
        boolean available = nodeAvailable(node);
        if(Boolean.parseBoolean(System.getenv().getOrDefault("CI", "false")))
        {
            assertTrue(available, "Node.js is required in CI for the Alias editor behavior contract");
        }
        else
        {
            assumeTrue(available, "Node.js is not available; skipping the local JavaScript behavior contract");
        }

        Process process = new ProcessBuilder(node, TEST_SCRIPT.toAbsolutePath().toString(),
            APP_JAVASCRIPT.toAbsolutePath().toString()).redirectErrorStream(true).start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if(!finished)
        {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(finished, () -> "Alias editor behavior test timed out:\n" + output);
        assertEquals(0, process.exitValue(), () -> "Alias editor behavior test failed:\n" + output);
    }

    private static boolean nodeAvailable(String node)
    {
        try
        {
            Process process = new ProcessBuilder(node, "--version").redirectErrorStream(true).start();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        }
        catch(IOException e)
        {
            return false;
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
