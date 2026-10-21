/* Copyright (C) 2026 Dennis Sheirer */
package io.github.dsheirer.receiver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.preference.portable.SqlitePreferencesFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReceiverRuntimeTest
{
    @Test
    void compositionPrecedesExplicitOnceOnlyActivationEvenWithoutChannelAutostart()
    {
        List<String> calls = new ArrayList<>();
        ReceiverRuntime runtime = new ReceiverRuntime(() -> calls.add("compose and bind"),
            () -> calls.add("hardware and outputs"), () -> { calls.add("receiver drain"); return true; },
            () -> calls.add("unlock"), () -> calls.add("preferences close"), false);
        assertThrows(IllegalStateException.class, runtime::activate);
        runtime.start();
        assertEquals(List.of("compose and bind"), calls);
        assertThrows(IllegalStateException.class, () -> runtime.startConfiguredChannels(true));
        runtime.activate();
        runtime.activate();
        assertEquals(List.of("compose and bind", "hardware and outputs"), calls);
        runtime.close();
        assertEquals(List.of("compose and bind", "hardware and outputs", "receiver drain", "preferences close", "unlock"), calls);
        assertThrows(IllegalStateException.class, runtime::activate);
    }

    @Test
    void failedActivationCleansThePartiallyStartedGraphBeforeUnlocking()
    {
        List<String> calls = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("hardware startup failed");
        ReceiverRuntime runtime = new ReceiverRuntime(() -> calls.add("compose"),
            () -> { calls.add("partial activation"); throw failure; },
            () -> { calls.add("receiver drain"); return true; }, () -> calls.add("unlock"),
            () -> calls.add("preferences close"), false);
        runtime.start();
        assertSame(failure, assertThrows(IllegalStateException.class, runtime::activate));
        runtime.close();
        assertEquals(List.of("compose", "partial activation", "receiver drain", "preferences close", "unlock"), calls);
        assertFalse(runtime.isRunning());
    }

    @Test
    void closingPreparedRuntimeDoesNotActivateHardware()
    {
        AtomicInteger activations = new AtomicInteger();
        ReceiverRuntime runtime = new ReceiverRuntime(() -> {}, activations::incrementAndGet,
            () -> true, () -> {}, () -> {}, false);
        runtime.start();
        runtime.close();
        assertEquals(0, activations.get());
    }

    @Test
    void ownsStartupAndExactlyOnceOrderedShutdown()
    {
        List<String> calls = new ArrayList<>();
        ReceiverRuntime runtime = new ReceiverRuntime(() -> calls.add("start"),
            () -> { calls.add("receiver drain"); return true; }, () -> calls.add("unlock"),
            () -> calls.add("preferences close"), false);
        assertFalse(runtime.isRunning());
        runtime.start();
        assertTrue(runtime.isRunning());
        runtime.setBeforeShutdown(() -> calls.add("shell close"));
        runtime.close();
        runtime.close();
        assertFalse(runtime.isRunning());
        assertEquals(List.of("start", "shell close", "receiver drain", "preferences close", "unlock"), calls);
        assertThrows(IllegalStateException.class, runtime::start);
    }

    @Test
    void failedStartupCleansPartiallyCreatedServicesBeforeUnlocking()
    {
        List<String> calls = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("service initialization failed");
        ReceiverRuntime runtime = new ReceiverRuntime(() -> { calls.add("partial start"); throw failure; },
            () -> { calls.add("partial cleanup"); return true; }, () -> calls.add("unlock"),
            () -> calls.add("preferences close"), false);
        assertSame(failure, assertThrows(IllegalStateException.class, runtime::start));
        runtime.close();
        assertEquals(List.of("partial start", "partial cleanup", "preferences close", "unlock"), calls);
        assertFalse(runtime.isRunning());
    }

    @Test
    void failedQuiescenceRetainsLockAndCanRetryWithoutRepeatingShellCleanup()
    {
        List<String> calls = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        ReceiverRuntime runtime = new ReceiverRuntime(() -> {}, () -> {
            calls.add("quiesce");
            if(attempts.getAndIncrement() == 0) throw new IllegalStateException("command still running");
            return true;
        }, () -> calls.add("unlock"), () -> calls.add("preferences close"), false);
        runtime.start();
        runtime.setBeforeShutdown(() -> calls.add("shell close"));
        assertThrows(IllegalStateException.class, runtime::closeForDatabaseReplacement);
        assertEquals(List.of("shell close", "quiesce"), calls);
        runtime.closeForDatabaseReplacement();
        assertEquals(List.of("shell close", "quiesce", "quiesce", "preferences close"), calls);
        runtime.releaseDataRootLock();
        assertEquals("unlock", calls.getLast());
    }

    @Test
    void databaseReplacementKeepsLockUntilExplicitHandoff()
    {
        List<String> calls = new ArrayList<>();
        ReceiverRuntime runtime = new ReceiverRuntime(() -> {}, () -> { calls.add("receiver drain"); return true; },
            () -> calls.add("unlock"), () -> calls.add("preferences close"), false);
        runtime.start();
        runtime.closeForDatabaseReplacement();
        assertEquals(List.of("receiver drain", "preferences close"), calls);
        runtime.releaseDataRootLock();
        runtime.releaseDataRootLock();
        assertEquals(List.of("receiver drain", "preferences close", "unlock"), calls);
    }

    @Test
    void incompleteDrainNeverAllowsDatabaseReplacementOrUnlock()
    {
        List<String> calls = new ArrayList<>();
        ReceiverRuntime runtime = new ReceiverRuntime(() -> {}, () -> { calls.add("incomplete drain"); return false; },
            () -> calls.add("unlock"), () -> calls.add("preferences close"), false);
        runtime.start();
        assertThrows(IllegalStateException.class, runtime::closeForDatabaseReplacement);
        assertEquals(List.of("incomplete drain"), calls);
        runtime.close();
        assertEquals(List.of("incomplete drain", "preferences close"), calls);
        assertThrows(IllegalStateException.class, runtime::releaseDataRootLock);
    }

    @Test
    void shellFailureDoesNotPreventReceiverCleanup()
    {
        List<String> calls = new ArrayList<>();
        ReceiverRuntime runtime = new ReceiverRuntime(() -> {}, () -> { calls.add("receiver drain"); return true; },
            () -> calls.add("unlock"), () -> calls.add("preferences close"), false);
        runtime.start();
        runtime.setBeforeShutdown(() -> { throw new IllegalStateException("window save failed"); });
        runtime.close();
        assertEquals(List.of("receiver drain", "preferences close"), calls);
    }

    @Test
    void quiescenceRetryDoesNotForgetAnEarlierShellCleanupFailure()
    {
        AtomicInteger attempts = new AtomicInteger();
        List<String> calls = new ArrayList<>();
        ReceiverRuntime runtime = new ReceiverRuntime(() -> {}, () -> {
            if(attempts.getAndIncrement() == 0) throw new IllegalStateException("command still running");
            return true;
        }, () -> calls.add("unlock"), () -> calls.add("preferences close"), false);
        runtime.start();
        runtime.setBeforeShutdown(() -> { throw new IllegalStateException("window save failed"); });
        assertThrows(IllegalStateException.class, runtime::closeForDatabaseReplacement);
        assertThrows(IllegalStateException.class, runtime::closeForDatabaseReplacement);
        runtime.close();
        assertEquals(List.of("preferences close"), calls);
        assertThrows(IllegalStateException.class, runtime::releaseDataRootLock);
    }

    @Test
    void receiverOwnedJvmHookFinishesBeforePortablePreferencesClose(@TempDir Path temporaryFolder) throws Exception
    {
        Path database = temporaryFolder.resolve("runtime-shutdown.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
            ShutdownProcess.class.getName(), database.toString()).redirectErrorStream(true).start();
        boolean exited = process.waitFor(20, TimeUnit.SECONDS);
        if(!exited) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(exited, output);
        assertEquals(0, process.waitFor(), output);
        try(var connection = io.github.dsheirer.database.SdrTrunkDatabase.open(database);
            var statement = connection.prepareStatement("SELECT settings_json FROM application_settings WHERE key IN (?, ?)"))
        {
            statement.setString(1, "portable_java_preferences_v1");
            statement.setString(2, "runtime-shutdown-test");
            try(var result = statement.executeQuery())
            {
                List<String> rows = new ArrayList<>();
                while(result.next()) rows.add(result.getString(1));
                assertEquals(2, rows.size());
                assertTrue(rows.stream().anyMatch(row -> row.contains("receiver-first")));
                assertTrue(rows.stream().anyMatch(row -> row.contains("lock-last")));
            }
        }
    }

    public static class ShutdownProcess
    {
        public static void main(String[] arguments) throws Exception
        {
            SqlitePreferencesFactory.install(Path.of(arguments[0]));
            Preferences preferences = Preferences.userRoot().node("/portable/runtime");
            ReceiverRuntime runtime = new ReceiverRuntime(() -> {}, () -> {
                preferences.put("shutdownOrder", "receiver-first");
                return true;
            }, () -> {
                //The writer has closed: this read must still observe the receiver's final flushed value.
                if(!"receiver-first".equals(preferences.get("shutdownOrder", null)))
                    throw new IllegalStateException("Receiver did not finish before lock release");
                try(var connection = io.github.dsheirer.database.SdrTrunkDatabase.open(Path.of(arguments[0]));
                    var statement = connection.prepareStatement("INSERT INTO application_settings (key, settings_json, updated_at_ms) VALUES (?, ?, ?)"))
                {
                    statement.setString(1, "runtime-shutdown-test");
                    statement.setString(2, "\"lock-last\"");
                    statement.setLong(3, 1);
                    statement.executeUpdate();
                }
            }, SqlitePreferencesFactory::shutdown, true);
            runtime.start();
        }
    }
}
