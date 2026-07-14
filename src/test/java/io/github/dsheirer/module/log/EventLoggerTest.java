/*
 * ****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.module.log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EventLoggerTest
{
    @TempDir
    Path mTemporaryDirectory;

    @Test
    public void writesQueuedEntriesInOrderAndClosesTheFile() throws Exception
    {
        TestEventLogger logger = new TestEventLogger(mTemporaryDirectory, "ordered.log");
        logger.start();
        logger.log("one");
        logger.log("two");
        logger.stop();
        EventLogger.flushPendingWrites();

        List<Path> files;

        try(Stream<Path> paths = Files.list(mTemporaryDirectory))
        {
            files = paths.toList();
        }

        assertEquals(1, files.size());
        assertEquals("HEADER\none\ntwo\n", Files.readString(files.getFirst()));
    }

    @Test
    public void blockedFileIoDoesNotBlockEventSubmission() throws Exception
    {
        CountDownLatch headerStarted = new CountDownLatch(1);
        CountDownLatch releaseHeader = new CountDownLatch(1);
        BlockingHeaderEventLogger logger = new BlockingHeaderEventLogger(mTemporaryDirectory, headerStarted,
            releaseHeader);
        ExecutorService caller = Executors.newSingleThreadExecutor();

        try
        {
            Future<?> startCall = caller.submit(logger::start);
            assertTrue(headerStarted.await(5, TimeUnit.SECONDS));
            startCall.get(1, TimeUnit.SECONDS);

            Future<?> eventCall = caller.submit(() -> {
                logger.log("queued");
                logger.stop();
            });
            eventCall.get(1, TimeUnit.SECONDS);
        }
        finally
        {
            releaseHeader.countDown();
            EventLogger.flushPendingWrites();
            caller.shutdownNow();
        }
    }

    private static class TestEventLogger extends EventLogger
    {
        private TestEventLogger(Path directory, String suffix)
        {
            super(directory, suffix, 851_000_000L);
        }

        @Override
        public String getHeader()
        {
            return "HEADER";
        }

        @Override
        public void reset()
        {
        }

        protected void log(String entry)
        {
            write(entry);
        }
    }

    private static class BlockingHeaderEventLogger extends TestEventLogger
    {
        private final CountDownLatch mHeaderStarted;
        private final CountDownLatch mReleaseHeader;

        private BlockingHeaderEventLogger(Path directory, CountDownLatch headerStarted, CountDownLatch releaseHeader)
        {
            super(directory, "blocking.log");
            mHeaderStarted = headerStarted;
            mReleaseHeader = releaseHeader;
        }

        @Override
        public String getHeader()
        {
            mHeaderStarted.countDown();

            try
            {
                mReleaseHeader.await(5, TimeUnit.SECONDS);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }

            return super.getHeader();
        }
    }
}
