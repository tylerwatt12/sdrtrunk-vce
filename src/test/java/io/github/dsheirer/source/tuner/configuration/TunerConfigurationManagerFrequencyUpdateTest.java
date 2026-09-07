/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.source.tuner.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerConfiguration;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class TunerConfigurationManagerFrequencyUpdateTest
{
    @Test
    void sameTunerUpdatesCoalesceToLatestFrequency()
    {
        AirspyTunerConfiguration configuration = configuration("airspy-1", 850_000_000L);
        ManualExecutor executor = new ManualExecutor();
        RecordingSettingsStore store = new RecordingSettingsStore(settings(configuration));
        TunerConfigurationManager manager = new TunerConfigurationManager(store, executor);

        manager.updateTunerFrequency(TunerType.AIRSPY_R820T, "airspy-1", 851_000_000L);
        manager.updateTunerFrequency(TunerType.AIRSPY_R820T, "AIRSPY-1", 852_000_000L);

        assertEquals(1, executor.getSubmissionCount(), "Only one drain worker should be queued");
        assertEquals(850_000_000L, configuration.getFrequency(),
            "The caller must not mutate tuner configuration state");

        executor.runNext();

        assertEquals(852_000_000L, configuration.getFrequency());
        assertEquals(1, store.getSavedFrequencies().size());
        assertEquals(852_000_000L, store.getSavedFrequencies().getFirst().get("airspy-1"));
    }

    @Test
    void updatesForTwoTunersBothSurviveCoalescing()
    {
        AirspyTunerConfiguration first = configuration("airspy-1", 850_000_000L);
        AirspyTunerConfiguration second = configuration("airspy-2", 860_000_000L);
        ManualExecutor executor = new ManualExecutor();
        RecordingSettingsStore store = new RecordingSettingsStore(settings(first, second));
        TunerConfigurationManager manager = new TunerConfigurationManager(store, executor);

        manager.updateTunerFrequency(TunerType.AIRSPY_R820T, "airspy-1", 851_000_000L);
        manager.updateTunerFrequency(TunerType.AIRSPY_R820T, "airspy-2", 861_000_000L);
        executor.runNext();

        assertEquals(851_000_000L, first.getFrequency());
        assertEquals(861_000_000L, second.getFrequency());
        assertEquals(Map.of("airspy-1", 851_000_000L, "airspy-2", 861_000_000L),
            store.getSavedFrequencies().getFirst());
    }

    @Test
    void configurationMutationAndSerializationRunOnWorkerThread() throws Exception
    {
        ThreadRecordingConfiguration configuration = new ThreadRecordingConfiguration("airspy-1");
        configuration.setFrequency(850_000_000L);
        configuration.clearMutationThread();
        ManualExecutor executor = new ManualExecutor();
        RecordingSettingsStore store = new RecordingSettingsStore(settings(configuration));
        TunerConfigurationManager manager = new TunerConfigurationManager(store, executor);
        String callerThread = Thread.currentThread().getName();

        manager.updateTunerFrequency(TunerType.AIRSPY_R820T, "airspy-1", 852_000_000L);
        executor.runNextOn("test tuner-frequency persistence worker");

        assertEquals("test tuner-frequency persistence worker", configuration.getMutationThread());
        assertEquals("test tuner-frequency persistence worker", store.getSaveThread());
        assertNotEquals(callerThread, configuration.getMutationThread());
    }

    @Test
    void missingConfigurationIsHarmless()
    {
        ManualExecutor executor = new ManualExecutor();
        RecordingSettingsStore store = new RecordingSettingsStore(settings());
        TunerConfigurationManager manager = new TunerConfigurationManager(store, executor);

        manager.updateTunerFrequency(TunerType.AIRSPY_R820T, "not-saved", 852_000_000L);
        executor.runNext();

        assertTrue(store.getSavedFrequencies().isEmpty());
        assertTrue(manager.getTunerConfigurations(TunerType.AIRSPY_R820T).isEmpty());
    }

    @Test
    void newerUpdateArrivingAfterSnapshotRemovalIsAppliedAndSaved() throws Exception
    {
        BlockingFrequencyConfiguration configuration = new BlockingFrequencyConfiguration("airspy-1");
        configuration.setFrequency(850_000_000L);
        ManualExecutor executor = new ManualExecutor();
        RecordingSettingsStore store = new RecordingSettingsStore(settings(configuration));
        TunerConfigurationManager manager = new TunerConfigurationManager(store, executor);
        configuration.blockNextMutation();

        manager.updateTunerFrequency(TunerType.AIRSPY_R820T, "airspy-1", 851_000_000L);
        RunningTask worker = start(executor.removeNext(), "blocked tuner-frequency persistence worker");

        try
        {
            assertTrue(configuration.awaitBlockedMutation(),
                "The worker should remove its snapshot and enter the first configuration update");
            manager.updateTunerFrequency(TunerType.AIRSPY_R820T, "airspy-1", 852_000_000L);
        }
        finally
        {
            configuration.releaseMutation();
        }

        worker.awaitCompletion();

        assertEquals(1, executor.getSubmissionCount(),
            "An arrival while the drain worker owns the queue must not submit another worker");
        assertEquals(852_000_000L, configuration.getFrequency());
        assertEquals(2, store.getSavedFrequencies().size());
        assertEquals(851_000_000L, store.getSavedFrequencies().get(0).get("airspy-1"));
        assertEquals(852_000_000L, store.getSavedFrequencies().get(1).get("airspy-1"));
    }

    @Test
    void arrivalDuringEmptyCheckOwnershipHandoffIsDrainedWithoutAnotherUpdate() throws Exception
    {
        AirspyTunerConfiguration configuration = configuration("airspy-1", 850_000_000L);
        ManualExecutor executor = new ManualExecutor();
        RecordingSettingsStore store = new RecordingSettingsStore(settings(configuration));
        TunerConfigurationManager manager = new TunerConfigurationManager(store, executor);
        AtomicBoolean workerScheduled = getWorkerScheduledFlag(manager);
        BlockingEmptyCheckMap pendingUpdates = new BlockingEmptyCheckMap(() -> workerScheduled.get());
        replacePendingFrequencyUpdates(manager, pendingUpdates);

        manager.updateTunerFrequency(TunerType.AIRSPY_R820T, "airspy-1", 851_000_000L);
        RunningTask firstWorker = start(executor.removeNext(), "handoff tuner-frequency persistence worker");

        try
        {
            assertTrue(pendingUpdates.awaitOwnershipHandoff(),
                "The first worker should reach the empty-check after releasing ownership");
            manager.updateTunerFrequency(TunerType.AIRSPY_R820T, "airspy-1", 852_000_000L);
        }
        finally
        {
            pendingUpdates.releaseEmptyCheck();
        }

        firstWorker.awaitCompletion();
        assertEquals(2, executor.getSubmissionCount(),
            "The arrival should claim released ownership and schedule its own drain worker");

        executor.runNext();

        assertEquals(852_000_000L, configuration.getFrequency());
        assertEquals(2, store.getSavedFrequencies().size());
        assertEquals(852_000_000L, store.getSavedFrequencies().getLast().get("airspy-1"));
    }

    private static AirspyTunerConfiguration configuration(String id, long frequency)
    {
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration(id);
        configuration.setFrequency(frequency);
        return configuration;
    }

    private static TunerSettings settings(TunerConfiguration... configurations)
    {
        TunerSettings settings = new TunerSettings();
        settings.setTunerConfigurations(List.of(configurations));
        return settings;
    }

    private static RunningTask start(Runnable task, String threadName)
    {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try
            {
                task.run();
            }
            catch(Throwable t)
            {
                failure.set(t);
            }
        }, threadName);
        thread.start();
        return new RunningTask(thread, failure);
    }

    private static AtomicBoolean getWorkerScheduledFlag(TunerConfigurationManager manager) throws Exception
    {
        Field field = TunerConfigurationManager.class.getDeclaredField("mFrequencyUpdateWorkerScheduled");
        field.setAccessible(true);
        return (AtomicBoolean)field.get(manager);
    }

    private static void replacePendingFrequencyUpdates(TunerConfigurationManager manager,
                                                       Map<Object,Long> pendingUpdates) throws Exception
    {
        Field field = TunerConfigurationManager.class.getDeclaredField("mPendingFrequencyUpdates");
        field.setAccessible(true);
        field.set(manager, pendingUpdates);
    }

    private static class ManualExecutor implements Executor
    {
        private final Queue<Runnable> mTasks = new ConcurrentLinkedQueue<>();
        private final AtomicInteger mSubmissionCount = new AtomicInteger();

        @Override
        public void execute(Runnable command)
        {
            mSubmissionCount.incrementAndGet();
            mTasks.add(command);
        }

        private int getSubmissionCount()
        {
            return mSubmissionCount.get();
        }

        private Runnable removeNext()
        {
            return mTasks.remove();
        }

        private void runNext()
        {
            Runnable task = removeNext();
            task.run();
        }

        private void runNextOn(String threadName) throws Exception
        {
            Runnable task = removeNext();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try
                {
                    task.run();
                }
                catch(Throwable t)
                {
                    failure.set(t);
                }
            }, threadName);
            worker.start();
            worker.join(2_000L);

            if(worker.isAlive())
            {
                throw new IllegalStateException("Persistence worker did not finish");
            }

            if(failure.get() != null)
            {
                throw new AssertionError(failure.get());
            }
        }
    }

    private record RunningTask(Thread thread, AtomicReference<Throwable> failure)
    {
        private void awaitCompletion() throws Exception
        {
            thread.join(2_000L);

            if(thread.isAlive())
            {
                throw new IllegalStateException("Persistence worker did not finish");
            }

            if(failure.get() != null)
            {
                throw new AssertionError(failure.get());
            }
        }
    }

    private static class RecordingSettingsStore extends ApplicationSettingsStore
    {
        private final TunerSettings mLoadedSettings;
        private final List<Map<String,Long>> mSavedFrequencies = new ArrayList<>();
        private String mSaveThread;

        private RecordingSettingsStore(TunerSettings loadedSettings)
        {
            super(Path.of("tuner-frequency-persistence-test.sqlite"));
            mLoadedSettings = loadedSettings;
        }

        @Override
        public <T> Optional<T> load(String key, Class<T> type)
        {
            return Optional.of(type.cast(mLoadedSettings));
        }

        @Override
        public void saveLater(String key, Object value)
        {
            mSaveThread = Thread.currentThread().getName();
            TunerSettings settings = (TunerSettings)value;
            Map<String,Long> frequencies = new HashMap<>();

            for(TunerConfiguration configuration: settings.getTunerConfigurations())
            {
                frequencies.put(configuration.getUniqueID(), configuration.getFrequency());
            }

            mSavedFrequencies.add(Map.copyOf(frequencies));
        }

        private List<Map<String,Long>> getSavedFrequencies()
        {
            return mSavedFrequencies;
        }

        private String getSaveThread()
        {
            return mSaveThread;
        }
    }

    private static class ThreadRecordingConfiguration extends AirspyTunerConfiguration
    {
        private String mMutationThread;

        private ThreadRecordingConfiguration(String uniqueID)
        {
            super(uniqueID);
        }

        @Override
        public void setFrequency(long frequency)
        {
            mMutationThread = Thread.currentThread().getName();
            super.setFrequency(frequency);
        }

        private void clearMutationThread()
        {
            mMutationThread = null;
        }

        private String getMutationThread()
        {
            return mMutationThread;
        }
    }

    private static class BlockingFrequencyConfiguration extends AirspyTunerConfiguration
    {
        private final AtomicBoolean mBlockNextMutation = new AtomicBoolean();
        private final CountDownLatch mMutationEntered = new CountDownLatch(1);
        private final CountDownLatch mReleaseMutation = new CountDownLatch(1);

        private BlockingFrequencyConfiguration(String uniqueID)
        {
            super(uniqueID);
        }

        @Override
        public void setFrequency(long frequency)
        {
            if(mBlockNextMutation.compareAndSet(true, false))
            {
                mMutationEntered.countDown();
                await(mReleaseMutation);
            }

            super.setFrequency(frequency);
        }

        private void blockNextMutation()
        {
            mBlockNextMutation.set(true);
        }

        private boolean awaitBlockedMutation() throws InterruptedException
        {
            return mMutationEntered.await(2, TimeUnit.SECONDS);
        }

        private void releaseMutation()
        {
            mReleaseMutation.countDown();
        }
    }

    private static class BlockingEmptyCheckMap extends ConcurrentHashMap<Object,Long>
    {
        private final BooleanSupplier mWorkerScheduled;
        private final AtomicBoolean mBlockOnce = new AtomicBoolean(true);
        private final CountDownLatch mOwnershipHandoffEntered = new CountDownLatch(1);
        private final CountDownLatch mReleaseEmptyCheck = new CountDownLatch(1);

        private BlockingEmptyCheckMap(BooleanSupplier workerScheduled)
        {
            mWorkerScheduled = workerScheduled;
        }

        @Override
        public boolean isEmpty()
        {
            if(!mWorkerScheduled.getAsBoolean() && mBlockOnce.compareAndSet(true, false))
            {
                mOwnershipHandoffEntered.countDown();
                await(mReleaseEmptyCheck);
            }

            return super.isEmpty();
        }

        private boolean awaitOwnershipHandoff() throws InterruptedException
        {
            return mOwnershipHandoffEntered.await(2, TimeUnit.SECONDS);
        }

        private void releaseEmptyCheck()
        {
            mReleaseEmptyCheck.countDown();
        }
    }

    private static void await(CountDownLatch latch)
    {
        try
        {
            if(!latch.await(2, TimeUnit.SECONDS))
            {
                throw new IllegalStateException("Timed out waiting for test coordination");
            }
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
