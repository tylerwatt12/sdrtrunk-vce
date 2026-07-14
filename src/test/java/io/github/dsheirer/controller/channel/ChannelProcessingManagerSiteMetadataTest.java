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

package io.github.dsheirer.controller.channel;

import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.preference.UserPreferences;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ChannelProcessingManagerSiteMetadataTest
{
    @Test
    public void siteMetadataListenersDoNotBlockTheCallingThread() throws Exception
    {
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, null, null,
            new UserPreferences());
        CountDownLatch listenerStarted = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        AtomicReference<Thread> listenerThread = new AtomicReference<>();
        AtomicReference<Thread> callerThread = new AtomicReference<>();
        ExecutorService caller = Executors.newSingleThreadExecutor();

        manager.addSiteMetadataListener(event -> {
            listenerThread.set(Thread.currentThread());
            listenerStarted.countDown();

            try
            {
                releaseListener.await(5, TimeUnit.SECONDS);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        });

        try
        {
            Future<?> submitted = caller.submit(() -> {
                callerThread.set(Thread.currentThread());
                manager.process((SiteMetadataEvent)null);
            });

            assertTrue(listenerStarted.await(5, TimeUnit.SECONDS));
            submitted.get(1, TimeUnit.SECONDS);
            assertNotEquals(callerThread.get(), listenerThread.get());
        }
        finally
        {
            releaseListener.countDown();
            caller.shutdownNow();
        }
    }
}
