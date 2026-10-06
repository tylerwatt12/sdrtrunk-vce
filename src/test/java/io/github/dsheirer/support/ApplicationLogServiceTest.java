/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationLogServiceTest
{
    @TempDir
    Path mDirectory;
    private final AtomicLong mClock = new AtomicLong();

    @Test
    void preservesMultilineErrorsAndWaitsForCompleteUtf8Lines() throws Exception
    {
        Path file = current();
        String first = header("ERROR", "Could not start") +
            "java.lang.IllegalStateException: 原因\n\tat example.Service.start(Service.java:12)\n";
        Files.writeString(file, first, StandardCharsets.UTF_8);
        byte[] continuation = "Caused by: café\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, java.util.Arrays.copyOf(continuation, continuation.length - 2), StandardOpenOption.APPEND);

        try(ApplicationLogService service = service())
        {
            ApplicationLogService.Snapshot before = service.snapshot("current", null);
            assertEquals(1, before.entries().size());
            ApplicationLogService.Entry error = before.entries().getFirst();
            assertEquals("ERROR", error.level());
            assertEquals("example.Service", error.source());
            assertTrue(error.time().endsWith("Z"));
            assertTrue(error.details().contains("原因"));
            assertTrue(error.details().contains("Service.java:12"));
            assertFalse(error.details().contains("Caused by"));
            assertFalse(error.text().contains("\uFFFD"));

            Files.write(file, java.util.Arrays.copyOfRange(continuation, continuation.length - 2, continuation.length),
                StandardOpenOption.APPEND);
            advance();
            ApplicationLogService.Snapshot after = service.snapshot("current", before.latestId());
            assertFalse(after.gap());
            assertEquals(before.latestId(), after.latestId());
            assertTrue(after.entries().getFirst().details().contains("Caused by: café"));
            assertFalse(after.entries().getFirst().text().contains("\uFFFD"));
        }
    }

    @Test
    void capsEntryCountAndReportsEvictedCursors() throws Exception
    {
        Files.writeString(current(), header("INFO", "first"));
        try(ApplicationLogService service = service())
        {
            ApplicationLogService.Snapshot first = service.snapshot("current", null);
            StringBuilder more = new StringBuilder();
            for(int index = 0; index < 501; index++) more.append(header("INFO", "message " + index));
            Files.writeString(current(), more, StandardOpenOption.APPEND);
            advance();
            ApplicationLogService.Snapshot snapshot = service.snapshot("current", first.latestId());
            assertEquals(500, snapshot.entries().size());
            assertEquals("message 1", snapshot.entries().getFirst().message());
            assertEquals("message 500", snapshot.entries().getLast().message());
            assertTrue(snapshot.truncated());
            assertTrue(snapshot.gap());
            assertEquals("history_limit", snapshot.changeReason());
        }
    }

    @Test
    void revisionPollsReturnOnlyAppendAndGrowingLastEntryAndResetOnRotation() throws Exception
    {
        Files.writeString(current(), header("INFO", "first") + header("ERROR", "failure") + "initial trace\n");
        try(ApplicationLogService service = service())
        {
            var initial = service.snapshot("current", null, null);
            var unchanged = service.snapshot("current", initial.latestId(), initial.revision());
            assertTrue(unchanged.incremental());
            assertTrue(unchanged.entries().isEmpty());
            Files.writeString(current(), "continued trace\n" + header("INFO", "new"), StandardOpenOption.APPEND);
            advance();
            var appended = service.snapshot("current", initial.latestId(), initial.revision());
            assertTrue(appended.incremental());
            assertEquals(2, appended.entries().size());
            assertEquals(initial.latestId(), appended.entries().getFirst().id());
            assertTrue(appended.entries().getFirst().details().contains("continued trace"));
            assertEquals("new", appended.entries().getLast().message());
            assertEquals(initial.entries().getFirst().id(), appended.firstId());
            Files.writeString(current(), header("INFO", "replacement"));
            advance();
            var rotated = service.snapshot("current", appended.latestId(), appended.revision());
            assertFalse(rotated.incremental());
            assertTrue(rotated.gap());
            assertEquals("replacement", rotated.entries().getFirst().message());
        }
    }

    @Test
    void parsesPaddedAndNestedReceiverThreadNames() throws Exception
    {
        Files.writeString(current(), "20261002 105431.012 [sdrtrunk USB tuner - bus [2] port [1.4]]    WARN  " +
            "example.Service - USB warning\n\tat example.Service.read(Service.java:42)\n");
        try(ApplicationLogService service = service())
        {
            ApplicationLogService.Entry entry = service.snapshot("current", null).entries().getFirst();
            assertEquals("WARN", entry.level());
            assertEquals("USB warning", entry.message());
            assertTrue(entry.details().contains("Service.java:42"));
        }
    }

    @Test
    void byteTailDoesNotPublishOrphanedStackTracesAndHugeEntriesAreLabelled() throws Exception
    {
        String largePrefix = "\tat example.Service.old(Service.java:1)\n".repeat(40_000);
        Files.writeString(current(), largePrefix + header("ERROR", "new error") +
            "java.lang.Exception: complete\n\tat example.Service.now(Service.java:2)\n");
        try(ApplicationLogService service = service())
        {
            ApplicationLogService.Snapshot bounded = service.snapshot("current", null);
            assertEquals(1, bounded.entries().size());
            assertTrue(bounded.truncated());
            assertFalse(bounded.entries().getFirst().text().contains("Service.java:1"));
            assertTrue(bounded.entries().getFirst().details().contains("Service.java:2"));

            Files.writeString(current(), header("ERROR", "x".repeat(100_000)) + "\tat example.Service.now(Service.java:2)\n");
            advance();
            ApplicationLogService.Entry huge = service.snapshot("current", null).entries().getFirst();
            assertTrue(huge.truncated());
            assertTrue(huge.text().endsWith("[message truncated]"));
            assertTrue(huge.text().length() <= ApplicationLogService.MAXIMUM_ENTRY_CHARACTERS);
        }
    }

    @Test
    void redactsSecretsEndpointsAndUserPathsBeforeAllExposedEntryFields() throws Exception
    {
        String message = "password=sample-secret {\"api_key\":\"json-secret with spaces\"} " +
            "Authorization: Bearer auth-secret proxy-authorization=Basic proxy-secret " +
            "https://user:pass@receiver.example.invalid:8090/api?api%5Fkey=url-secret api%5Fkey=encoded-secret " +
            "127.0.0.1:8090 [fd00::123]:8090 ::1 receiver.local host.example.invalid:8090 " +
            "\"/Users/sample-user/My Files/config.xml\"\n" +
            "java.lang.Exception: C:\\Users\\sample-user\\config.xml\n" +
            "\tat example.Service.start(Service.java:12)\n";
        Files.writeString(current(), header("ERROR", message));
        try(ApplicationLogService service = service())
        {
            ApplicationLogService.Entry entry = service.snapshot("current", null).entries().getFirst();
            String all = entry.message() + entry.details() + entry.text() + entry.source();
            for(String privateText: java.util.List.of("sample-secret", "json-secret", "auth-secret", "proxy-secret",
                "url-secret", "encoded-secret", "receiver.example", "127.0.0.1", "fd00", "::1", "receiver.local",
                "host.example", "sample-user"))
            {
                assertFalse(all.contains(privateText), privateText);
            }
            assertTrue(entry.message().contains("[removed]"));
            assertTrue(entry.message().contains("[endpoint hidden]"));
            assertTrue(entry.details().contains("[path hidden]"));
            assertTrue(entry.details().contains("Service.java:12"));
        }
        String adversarial = "[".repeat(100_000) + "password=\"" + "z".repeat(100_000);
        assertFalse(ApplicationLogService.redact(adversarial).contains("z"));
    }

    @Test
    void distinguishesRotationInPlaceReplacementAndConfiguredSourceChanges() throws Exception
    {
        Files.writeString(current(), header("INFO", "old"));
        AtomicReference<Path> directory = new AtomicReference<>(mDirectory);
        try(ApplicationLogService service = new ApplicationLogService(directory::get, mClock::get, () -> 42))
        {
            ApplicationLogService.Snapshot old = service.snapshot("current", null);
            Files.move(current(), mDirectory.resolve("20261001_sdrtrunk_app.log"));
            Files.writeString(current(), header("INFO", "new"));
            advance();
            ApplicationLogService.Snapshot rotated = service.snapshot("current", old.latestId());
            assertTrue(rotated.gap());
            assertEquals("rotation", rotated.changeReason());
            assertEquals("old", service.snapshot("previous", null).entries().getFirst().message());

            Files.writeString(current(), header("INFO", "alt"));
            advance();
            ApplicationLogService.Snapshot replaced = service.snapshot("current", rotated.latestId());
            assertTrue(replaced.gap());
            assertEquals("rotation", replaced.changeReason());
            assertNotEquals(rotated.latestId(), replaced.latestId());

            Path other = Files.createDirectory(mDirectory.resolve("other"));
            Files.writeString(other.resolve("sdrtrunk_app.log"), header("INFO", "second source"));
            directory.set(other);
            advance();
            assertEquals("source_changed", service.snapshot("current", replaced.latestId()).changeReason());
            advance();
            assertEquals("source_changed", service.snapshot("current", replaced.latestId()).changeReason());
        }
    }

    @Test
    void detectsSameSizeRewriteBeyondUnchangedPrefix() throws Exception
    {
        String prefix = header("INFO", "p".repeat(2_000));
        Files.writeString(current(), prefix + header("INFO", "old ending"));
        try(ApplicationLogService service = service())
        {
            ApplicationLogService.Snapshot old = service.snapshot("current", null);
            Files.writeString(current(), prefix + header("INFO", "new ending"));
            advance();
            ApplicationLogService.Snapshot replaced = service.snapshot("current", old.latestId());
            assertTrue(replaced.gap());
            assertEquals("rotation", replaced.changeReason());
            assertNotEquals(old.latestId(), replaced.latestId());
        }
    }

    @Test
    void selectsOnlyDatedRegularArchivesAndRejectsLinkedFiles() throws Exception
    {
        Files.writeString(mDirectory.resolve("20260930_sdrtrunk_app.log"), header("INFO", "older"));
        Files.writeString(mDirectory.resolve("20261001_sdrtrunk_app.log"), header("INFO", "newer"));
        Files.writeString(mDirectory.resolve("99999999_sdrtrunk_app.log"), header("INFO", "invalid date"));
        Path privateFile = mDirectory.resolve("private.txt");
        Files.writeString(privateFile, "should never be read\n");
        try
        {
            Files.createSymbolicLink(current(), privateFile);
        }
        catch(UnsupportedOperationException | java.io.IOException | SecurityException exception)
        {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable on this filesystem");
        }
        Files.createSymbolicLink(mDirectory.resolve("20261002_sdrtrunk_app.log"), privateFile);
        try(ApplicationLogService service = service())
        {
            assertFalse(service.snapshot("current", null).available());
            ApplicationLogService.Snapshot previous = service.snapshot("previous", null);
            assertEquals("20261001_sdrtrunk_app.log", previous.fileName());
            assertEquals("newer", previous.entries().getFirst().message());
        }
        Path alias = mDirectory.resolve("directory-alias");
        Files.createSymbolicLink(alias, mDirectory);
        try(ApplicationLogService service = new ApplicationLogService(alias))
        {
            assertEquals("newer", service.snapshot("previous", null).entries().getFirst().message());
        }
    }

    @Test
    void limitsArchiveScansAndAllowsNoClientSelectedPath() throws Exception
    {
        for(int index = 0; index <= ApplicationLogService.MAXIMUM_DIRECTORY_ENTRIES; index++)
        {
            Files.writeString(mDirectory.resolve("unrelated-" + index), "");
        }
        try(ApplicationLogService service = service())
        {
            assertThrows(java.io.IOException.class, () -> service.snapshot("previous", null));
            assertThrows(IllegalArgumentException.class, () -> service.snapshot("../../private", null));
            assertThrows(IllegalArgumentException.class, () -> service.snapshot("current", "/private"));
        }
    }

    @Test
    void sharesFreshSnapshotsAndRejectsBusyReadersWithoutWaiting() throws Exception
    {
        Files.writeString(current(), header("INFO", "cached"));
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Boolean> block = new AtomicReference<>(false);
        try(ApplicationLogService service = new ApplicationLogService(() -> {
            reads.incrementAndGet();
            if(block.get())
            {
                reading.countDown();
                try
                {
                    if(!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Reader not released");
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
            return mDirectory;
        }, mClock::get, () -> 42); var executor = Executors.newSingleThreadExecutor())
        {
            service.snapshot("current", null);
            service.snapshot("current", null);
            assertEquals(1, reads.get());
            block.set(true);
            var worker = executor.submit(() -> service.snapshot("previous", null));
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            // A fresh shared current snapshot is still immediately available while the other source is reading.
            assertTrue(service.snapshot("current", null).available());
            assertThrows(ApplicationLogService.BusyException.class, () -> service.snapshot("previous", null));
            release.countDown();
            worker.get(2, TimeUnit.SECONDS);
            service.close();
            assertThrows(ApplicationLogService.BusyException.class, () -> service.snapshot("current", null));
        }
        finally
        {
            release.countDown();
        }
    }

    private ApplicationLogService service()
    {
        return new ApplicationLogService(() -> mDirectory, mClock::get, () -> 42);
    }

    private Path current()
    {
        return mDirectory.resolve("sdrtrunk_app.log");
    }

    private void advance()
    {
        mClock.addAndGet(1_000_000_001L);
    }

    private static String header(String level, String message)
    {
        return "20261002 105431.012 [main] " + level + "  example.Service - " + message + "\n";
    }
}
