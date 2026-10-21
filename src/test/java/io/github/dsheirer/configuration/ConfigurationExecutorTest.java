/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.configuration;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ConfigurationExecutorTest
{
    @Test
    void commandsHaveOneOwnerAndNestedCommandsAreReentrant()
    {
        try(ConfigurationExecutor owner = new ConfigurationExecutor())
        {
            Thread thread = owner.call(Thread::currentThread);
            assertNotSame(Thread.currentThread(), thread);
            assertSame(thread, owner.call(() -> owner.call(Thread::currentThread)));
            assertThrows(IllegalArgumentException.class, () -> owner.call(() ->
            {
                throw new IllegalArgumentException("test failure");
            }));
            assertEquals("still running", owner.call(() -> "still running"));
        }
    }

    @Test
    void interruptedWaitingCommandCannotCommitLater() throws Exception
    {
        try(ConfigurationExecutor owner = new ConfigurationExecutor())
        {
            CountDownLatch active = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var first = owner.submit(() -> { active.countDown(); await(release); });
            assertTrue(active.await(3, TimeUnit.SECONDS));
            AtomicBoolean mutated = new AtomicBoolean();
            AtomicReference<Throwable> result = new AtomicReference<>();
            CountDownLatch waiting = new CountDownLatch(1);
            Thread caller = Thread.ofPlatform().start(() ->
            {
                waiting.countDown();
                try { owner.call(() -> { mutated.set(true); return null; }); }
                catch(Throwable failure) { result.set(failure); }
            });
            assertTrue(waiting.await(3, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(3_000);
            release.countDown();
            first.get(3, TimeUnit.SECONDS);
            owner.call(() -> null);
            assertFalse(caller.isAlive());
            assertInstanceOf(ConfigurationExecutor.BusyException.class, result.get());
            assertFalse(mutated.get());
        }
    }

    @Test
    void interruptedClaimedCommandReturnsItsDefinitiveResult() throws Exception
    {
        try(ConfigurationExecutor owner = new ConfigurationExecutor())
        {
            CountDownLatch active = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<String> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread caller = Thread.ofPlatform().start(() ->
            {
                try { result.set(owner.call(() -> { active.countDown(); await(release); return "committed"; })); }
                catch(Throwable error) { failure.set(error); }
            });
            assertTrue(active.await(3, TimeUnit.SECONDS));
            caller.interrupt();
            release.countDown();
            caller.join(3_000);
            assertFalse(caller.isAlive());
            assertNull(failure.get());
            assertEquals("committed", result.get());
        }
    }

    @Test
    void closedOwnerRejectsNewWorkWithoutRunningIt()
    {
        ConfigurationExecutor owner = new ConfigurationExecutor();
        owner.close();
        assertThrows(ConfigurationExecutor.BusyException.class, () -> owner.call(() -> null));
        assertTrue(owner.submit(() -> fail("Closed owner ran work")).isCompletedExceptionally());
    }

    @Test
    void shutdownQuiescesAdministrationButAllowsFinalObserverDrain() throws Exception
    {
        try(ConfigurationExecutor owner = new ConfigurationExecutor())
        {
            owner.quiesceCalls();
            assertThrows(ConfigurationExecutor.BusyException.class, () -> owner.call(() -> null));
            AtomicBoolean observed = new AtomicBoolean();
            owner.submit(() -> observed.set(true)).get(3, TimeUnit.SECONDS);
            assertTrue(observed.get());
        }
    }

    private static void await(CountDownLatch latch)
    {
        try { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
        catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
