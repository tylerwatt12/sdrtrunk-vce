/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.preference.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.stats.WebCallConfiguration;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;

class ApplicationPreferenceWebCallConfigurationTest
{
    private static final String MAXIMUM_LISTENERS = "stats.web.call.maximum.listeners";
    private static final String MAXIMUM_SELECTED_SCAN_LISTS = "stats.web.call.maximum.selected.scan.lists";
    private static final String WAITING_CALLS_PER_LISTENER = "stats.web.call.maximum.browser.queue.calls";
    private static final String MAXIMUM_CACHED_CALLS = "stats.web.call.maximum.cached.calls";
    private static final String MAXIMUM_CACHED_AUDIO_MIB = "stats.web.call.maximum.cached.audio.mib";

    @Test
    void persistsAllWebCallCapacityFieldsAndPublishesOnePreferenceChange() throws Exception
    {
        Preferences node = Preferences.userRoot().node("/sdrtrunk-vce-tests/web-call-" + UUID.randomUUID());
        Preferences parent = node.parent();

        try
        {
            AtomicInteger changes = new AtomicInteger();
            ApplicationPreference preference = new ApplicationPreference(ignored -> changes.incrementAndGet());
            usePreferences(preference, node);
            assertEquals(WebCallConfiguration.defaults(), preference.getWebCallConfiguration());

            WebCallConfiguration configured = new WebCallConfiguration(48, 12, 80, 768, 256);
            preference.setWebCallConfiguration(configured);
            node.flush();
            assertEquals(1, changes.get());

            AtomicInteger reloadedChanges = new AtomicInteger();
            ApplicationPreference reloaded = new ApplicationPreference(
                ignored -> reloadedChanges.incrementAndGet());
            usePreferences(reloaded, node);
            assertEquals(configured, reloaded.getWebCallConfiguration());

            reloaded.setWebCallConfiguration(null);
            assertEquals(WebCallConfiguration.defaults(), reloaded.getWebCallConfiguration());
            assertEquals(1, reloadedChanges.get());
        }
        finally
        {
            node.removeNode();
            parent.flush();
        }
    }

    @Test
    void concurrentLazyReadsWaitForOneCompleteConfiguration() throws Exception
    {
        WebCallConfiguration expected = new WebCallConfiguration(48, 12, 80, 768, 256);
        BlockingReadPreferences preferences = new BlockingReadPreferences();
        preferences.store(expected);
        ApplicationPreference preference = new ApplicationPreference(ignored -> {});
        usePreferences(preference, preferences);
        AtomicReference<WebCallConfiguration> firstResult = new AtomicReference<>();
        AtomicReference<WebCallConfiguration> secondResult = new AtomicReference<>();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        CountDownLatch secondStarted = new CountDownLatch(1);
        Thread first = capturingThread("web-call-preference-first-reader", firstFailure,
            () -> firstResult.set(preference.getWebCallConfiguration()));
        Thread second = capturingThread("web-call-preference-second-reader", secondFailure, () -> {
            secondStarted.countDown();
            secondResult.set(preference.getWebCallConfiguration());
        });

        try
        {
            first.start();
            assertTrue(preferences.awaitBlockedRead(), "The initial load did not reach its second preference key");
            second.start();
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS), "The concurrent reader did not start");
            assertTrue(awaitState(second, Thread.State.BLOCKED),
                "A concurrent reader must wait for the complete aggregate instead of observing partial fields");
        }
        finally
        {
            preferences.releaseRead();
            join(first);
            join(second);
        }

        assertNull(firstFailure.get());
        assertNull(secondFailure.get());
        assertEquals(expected, firstResult.get());
        assertEquals(expected, secondResult.get());
    }

    @Test
    void concurrentUpdatesSerializePersistenceAndPublishWholeSnapshots() throws Exception
    {
        BlockingWritePreferences preferences = new BlockingWritePreferences();
        AtomicInteger changes = new AtomicInteger();
        ApplicationPreference preference = new ApplicationPreference(ignored -> changes.incrementAndGet());
        usePreferences(preference, preferences);
        WebCallConfiguration baseline = preference.getWebCallConfiguration();
        WebCallConfiguration firstConfiguration = new WebCallConfiguration(40, 10, 70, 640, 192);
        WebCallConfiguration secondConfiguration = new WebCallConfiguration(56, 20, 140, 1024, 384);
        preferences.blockFirstWrite();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        CountDownLatch secondStarted = new CountDownLatch(1);
        Thread first = capturingThread("web-call-preference-first-writer", firstFailure,
            () -> preference.setWebCallConfiguration(firstConfiguration));
        Thread second = capturingThread("web-call-preference-second-writer", secondFailure, () -> {
            secondStarted.countDown();
            preference.setWebCallConfiguration(secondConfiguration);
        });

        try
        {
            first.start();
            assertTrue(preferences.awaitBlockedWrite(), "The initial update did not reach persistent storage");
            second.start();
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS), "The concurrent writer did not start");
            assertTrue(awaitState(second, Thread.State.BLOCKED),
                "Concurrent updates must not interleave their portable preference keys");
            assertEquals(baseline, preference.getWebCallConfiguration(),
                "Readers must retain the last complete snapshot until persistence finishes");
        }
        finally
        {
            preferences.releaseWrite();
            join(first);
            join(second);
        }

        assertNull(firstFailure.get());
        assertNull(secondFailure.get());
        assertEquals(secondConfiguration, preference.getWebCallConfiguration());
        assertEquals(secondConfiguration, preferences.storedConfiguration());
        assertEquals(2, changes.get());
    }

    private static void usePreferences(ApplicationPreference preference, Preferences preferences)
        throws ReflectiveOperationException
    {
        Field field = ApplicationPreference.class.getDeclaredField("mPreferences");
        field.setAccessible(true);
        field.set(preference, preferences);
    }

    private static Thread capturingThread(String name, AtomicReference<Throwable> failure, Runnable runnable)
    {
        return new Thread(() -> {
            try
            {
                runnable.run();
            }
            catch(Throwable throwable)
            {
                failure.set(throwable);
            }
        }, name);
    }

    private static boolean awaitState(Thread thread, Thread.State state) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while(thread.isAlive() && System.nanoTime() < deadline)
        {
            if(thread.getState() == state)
            {
                return true;
            }

            Thread.sleep(1);
        }

        return thread.getState() == state;
    }

    private static void join(Thread thread) throws InterruptedException
    {
        if(thread.getState() == Thread.State.NEW)
        {
            return;
        }

        thread.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(thread.isAlive(), () -> thread.getName() + " did not finish");
    }

    private static class IntegerPreferences extends AbstractPreferences
    {
        private final Map<String,Integer> mValues = new ConcurrentHashMap<>();

        private IntegerPreferences()
        {
            super(null, "");
        }

        private IntegerPreferences(AbstractPreferences parent, String name)
        {
            super(parent, name);
        }

        @Override
        public void putInt(String key, int value)
        {
            mValues.put(key, value);
        }

        @Override
        public int getInt(String key, int defaultValue)
        {
            return mValues.getOrDefault(key, defaultValue);
        }

        void store(WebCallConfiguration configuration)
        {
            mValues.put(MAXIMUM_LISTENERS, configuration.maximumListeners());
            mValues.put(MAXIMUM_SELECTED_SCAN_LISTS, configuration.maximumSelectedScanLists());
            mValues.put(WAITING_CALLS_PER_LISTENER, configuration.waitingCallsPerListener());
            mValues.put(MAXIMUM_CACHED_CALLS, configuration.maximumCachedCalls());
            mValues.put(MAXIMUM_CACHED_AUDIO_MIB, configuration.maximumCachedAudioMiB());
        }

        WebCallConfiguration storedConfiguration()
        {
            return new WebCallConfiguration(getInt(MAXIMUM_LISTENERS, -1),
                getInt(MAXIMUM_SELECTED_SCAN_LISTS, -1), getInt(WAITING_CALLS_PER_LISTENER, -1),
                getInt(MAXIMUM_CACHED_CALLS, -1), getInt(MAXIMUM_CACHED_AUDIO_MIB, -1));
        }

        @Override
        protected void putSpi(String key, String value)
        {
            mValues.put(key, Integer.parseInt(value));
        }

        @Override
        protected String getSpi(String key)
        {
            Integer value = mValues.get(key);
            return value != null ? value.toString() : null;
        }

        @Override
        protected void removeSpi(String key)
        {
            mValues.remove(key);
        }

        @Override
        protected void removeNodeSpi()
        {
            mValues.clear();
        }

        @Override
        protected String[] keysSpi()
        {
            return mValues.keySet().toArray(String[]::new);
        }

        @Override
        protected String[] childrenNamesSpi()
        {
            return new String[0];
        }

        @Override
        protected AbstractPreferences childSpi(String name)
        {
            return new IntegerPreferences(this, name);
        }

        @Override
        protected void syncSpi() throws BackingStoreException
        {
        }

        @Override
        protected void flushSpi() throws BackingStoreException
        {
        }
    }

    private static final class BlockingReadPreferences extends IntegerPreferences
    {
        private final AtomicInteger mReads = new AtomicInteger();
        private final CountDownLatch mBlockedRead = new CountDownLatch(1);
        private final CountDownLatch mReleaseRead = new CountDownLatch(1);

        @Override
        public int getInt(String key, int defaultValue)
        {
            if(mReads.incrementAndGet() == 2)
            {
                mBlockedRead.countDown();
                awaitUninterruptibly(mReleaseRead);
            }

            return super.getInt(key, defaultValue);
        }

        boolean awaitBlockedRead() throws InterruptedException
        {
            return mBlockedRead.await(5, TimeUnit.SECONDS);
        }

        void releaseRead()
        {
            mReleaseRead.countDown();
        }
    }

    private static final class BlockingWritePreferences extends IntegerPreferences
    {
        private final AtomicInteger mWrites = new AtomicInteger();
        private final CountDownLatch mBlockedWrite = new CountDownLatch(1);
        private final CountDownLatch mReleaseWrite = new CountDownLatch(1);
        private volatile boolean mBlockFirstWrite;

        @Override
        public void putInt(String key, int value)
        {
            super.putInt(key, value);

            if(mBlockFirstWrite && mWrites.incrementAndGet() == 1)
            {
                mBlockedWrite.countDown();
                awaitUninterruptibly(mReleaseWrite);
            }
        }

        void blockFirstWrite()
        {
            mBlockFirstWrite = true;
        }

        boolean awaitBlockedWrite() throws InterruptedException
        {
            return mBlockedWrite.await(5, TimeUnit.SECONDS);
        }

        void releaseWrite()
        {
            mReleaseWrite.countDown();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch)
    {
        boolean interrupted = false;

        while(true)
        {
            try
            {
                latch.await();
                break;
            }
            catch(InterruptedException exception)
            {
                interrupted = true;
            }
        }

        if(interrupted)
        {
            Thread.currentThread().interrupt();
        }
    }
}
