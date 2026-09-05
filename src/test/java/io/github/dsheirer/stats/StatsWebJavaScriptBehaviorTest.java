/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Executes every DOM-independent browser behavior contract with the Node.js runtime provisioned by CI. */
class StatsWebJavaScriptBehaviorTest
{
    private static final Path SCRIPT_ROOT = Path.of("src", "test", "js", "stats-web");
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");
    private static final Path CORE_MODULES = Path.of("stats-web", "assets", "core");
    private static final Path PAGE_LIFECYCLE = CORE_MODULES.resolve("page-lifecycle.js");
    private static final Path WEB_CALL_PLAYER = Path.of("stats-web", "assets", "web-call-player.js");
    private static final String NODE = System.getenv().getOrDefault("NODE_BINARY", "node");
    private static final int MAXIMUM_OUTPUT_LENGTH = 32_000;
    private static final List<Contract> CONTRACTS = List.of(
        contract("admin system status", "admin-system-status.test.js", APP_JAVASCRIPT),
        contract("Alias editor", "alias-editor.test.js", APP_JAVASCRIPT),
        contract("frontend foundation", "frontend-foundation.test.js", CORE_MODULES),
        contract("health alert settings", "health-alert-settings.test.js", APP_JAVASCRIPT),
        contract("Live detail filters", "live-detail-filters.test.js", APP_JAVASCRIPT),
        contract("Live presentation", "live-presentation.test.js", APP_JAVASCRIPT),
        contract("P25 band-plan override prefill", "p25-bandplan-override-prefill.test.js", APP_JAVASCRIPT),
        contract("page lifecycle", "page-lifecycle.test.js", PAGE_LIFECYCLE),
        contract("receiver-health alert catalog", "receiver-health-alerts.test.js", CORE_MODULES),
        contract("receiver-health pagination", "receiver-health-pagination.test.js", APP_JAVASCRIPT),
        contract("status availability", "status-availability.test.js", APP_JAVASCRIPT),
        contract("tuner idle markers", "tuner-idle-markers.test.js", APP_JAVASCRIPT),
        contract("web call player", "web-call-player.test.js", WEB_CALL_PLAYER));

    @TestFactory
    Stream<DynamicTest> executesEveryBrowserBehaviorContract()
    {
        boolean nodeAvailable = nodeAvailable();
        if(Boolean.parseBoolean(System.getenv().getOrDefault("CI", "false")))
        {
            assertTrue(nodeAvailable, "Node.js is required in CI for the browser behavior contracts");
        }
        else
        {
            assumeTrue(nodeAvailable, "Node.js is not available; skipping local browser behavior contracts");
        }

        return CONTRACTS.stream().map(contract -> dynamicTest(contract.name(), () -> run(contract)));
    }

    private static void run(Contract contract) throws Exception
    {
        assertTrue(Files.isRegularFile(contract.script()), () -> "Missing " + contract.script().toAbsolutePath());
        assertTrue(Files.exists(contract.resource()), () -> "Missing " + contract.resource().toAbsolutePath());
        Path outputFile = Files.createTempFile("stats-web-node-", ".log");

        try
        {
            Process process = new ProcessBuilder(NODE, contract.script().toAbsolutePath().toString(),
                contract.resource().toAbsolutePath().toString())
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);

            if(!finished)
            {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }

            String output = bounded(Files.readString(outputFile, StandardCharsets.UTF_8));
            assertTrue(finished, () -> contract.name() + " timed out:\n" + output);
            assertEquals(0, process.exitValue(), () -> contract.name() + " failed:\n" + output);
        }
        finally
        {
            Files.deleteIfExists(outputFile);
        }
    }

    private static boolean nodeAvailable()
    {
        try
        {
            Process process = new ProcessBuilder(NODE, "--version")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        }
        catch(IOException exception)
        {
            return false;
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String bounded(String output)
    {
        if(output == null || output.length() <= MAXIMUM_OUTPUT_LENGTH)
        {
            return output != null ? output : "";
        }

        return "… output truncated …\n" + output.substring(output.length() - MAXIMUM_OUTPUT_LENGTH);
    }

    private static Contract contract(String name, String script, Path resource)
    {
        return new Contract(name, SCRIPT_ROOT.resolve(script), resource);
    }

    private record Contract(String name, Path script, Path resource)
    {
    }
}
