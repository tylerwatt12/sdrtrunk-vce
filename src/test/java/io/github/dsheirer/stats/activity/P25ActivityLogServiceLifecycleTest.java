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

package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.channel.quality.ControlChannelQualitySnapshot;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.configuration.ChannelConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.DecoderTypeConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SiteGuidConfigurationIdentifier;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataEvent;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.identifier.channel.StandardChannel;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.module.decode.p25.reference.VoiceServiceOptions;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P25ActivityLogServiceLifecycleTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void preferenceNotificationAfterDisposeCannotRestartTheWriter() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        P25ActivityLogService service = new P25ActivityLogService(userPreferences);

        service.dispose();
        service.preferenceUpdated(PreferenceType.APPLICATION);

        assertEquals(P25ActivityLogStatus.State.STOPPED, service.getStatus().state());
        assertFalse(service.getStatus().summaryActive());
    }

    @Test
    void blockedStatisticsProjectionNeverBlocksTheDecoderCallback() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        P25ActivityLogService service = new P25ActivityLogService(userPreferences);
        Channel channel = new Channel("Observer isolation", Channel.ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigNBFM());
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        AtomicReference<Thread> projectionThread = new AtomicReference<>();
        DecodeEvent blocked = new DecodeEvent(DecodeEventType.CALL, System.currentTimeMillis())
        {
            @Override
            public DecodeEventType getEventType()
            {
                projectionThread.compareAndSet(null, Thread.currentThread());
                projectionEntered.countDown();

                try
                {
                    releaseProjection.await(3, TimeUnit.SECONDS);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }

                return super.getEventType();
            }
        };
        DecodeEvent ordinary = DecodeEvent.builder(DecodeEventType.CALL, System.currentTimeMillis()).build();
        Thread decoderThread = Thread.currentThread();

        try
        {
            service.getDecodeEventListener().accept(channel, blocked);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));
            long started = System.nanoTime();

            for(int x = 0; x < P25ActivityLogService.OBSERVATION_QUEUE_SIZE + 16; x++)
            {
                service.getDecodeEventListener().accept(channel, ordinary);
            }

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMs < 500, "bounded offers took " + elapsedMs + " ms");
            assertTrue(service.getObservationDropCount() > 0);
            assertFalse(decoderThread == projectionThread.get());
        }
        finally
        {
            releaseProjection.countDown();
            service.dispose();
        }
    }

    @Test
    void blockedProjectionDisposeLeavesQueuedCleanupToWorkerAndSuppressesCommitCallbacks() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        P25ActivityLogService service = new P25ActivityLogService(userPreferences, 25, TimeUnit.MILLISECONDS);
        Channel channel = new Channel("Observer shutdown", Channel.ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigNBFM());
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        DecodeEvent blocked = new DecodeEvent(DecodeEventType.CALL, System.currentTimeMillis())
        {
            @Override
            public DecodeEventType getEventType()
            {
                projectionEntered.countDown();

                try
                {
                    releaseProjection.await();
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }

                return super.getEventType();
            }
        };
        DecodeEvent queued = DecodeEvent.builder(DecodeEventType.CALL, System.currentTimeMillis() + 1).build();
        service.addActivityCommitListener(rowIds -> callbacks.incrementAndGet());

        try
        {
            service.getDecodeEventListener().accept(channel, blocked);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));
            service.getDecodeEventListener().accept(channel, queued);
            assertEquals(1, service.getPendingObservationCount());
            var retiredEpoch = service.getObservationIngressForTest();

            service.dispose();
            assertFalse(service.isObservationWorkerTerminated());
            assertEquals(0, service.getPendingObservationCount());
            assertEquals(1, retiredEpoch.size(),
                "dispose must abandon the old epoch instead of consuming it on the caller thread");

            releaseProjection.countDown();
            awaitWorkerTermination(service);
            assertEquals(0, service.getPendingObservationCount());
            assertEquals(0, callbacks.get());
            service.getDecodeEventListener().accept(channel, queued);
            assertEquals(0, service.getPendingObservationCount());
        }
        finally
        {
            releaseProjection.countDown();
            service.dispose();
        }
    }

    @Test
    void callbackPausedAcrossDisableAndReenableCannotEnterTheNewObservationEpoch() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        CountDownLatch activeEpochCaptured = new CountDownLatch(1);
        CountDownLatch disabledEpochCaptured = new CountDownLatch(1);
        CountDownLatch releaseActiveProducer = new CountDownLatch(1);
        CountDownLatch releaseDisabledProducer = new CountDownLatch(1);
        AtomicInteger producerSnapshot = new AtomicInteger();
        Runnable pauseAfterSnapshot = () -> {
            int snapshot = producerSnapshot.incrementAndGet();
            CountDownLatch captured = snapshot == 1 ? activeEpochCaptured :
                snapshot == 2 ? disabledEpochCaptured : null;
            CountDownLatch release = snapshot == 1 ? releaseActiveProducer :
                snapshot == 2 ? releaseDisabledProducer : null;

            if(captured != null)
            {
                captured.countDown();

                try
                {
                    release.await();
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }
        };
        P25ActivityLogService service = new P25ActivityLogService(userPreferences, 2, TimeUnit.SECONDS,
            pauseAfterSnapshot);
        Channel channel = new Channel("Observation epochs", Channel.ChannelType.STANDARD);
        channel.setRadresGuid("00000000-0000-0000-0000-000000000305");
        channel.setDecodeConfiguration(new DecodeConfigNBFM());
        long oldTimestamp = System.currentTimeMillis();
        long disabledTimestamp = oldTimestamp + 5_000L;
        long newTimestamp = oldTimestamp + 10_000L;
        DecodeEvent staleActive = DecodeEvent.builder(DecodeEventType.CALL, oldTimestamp)
            .channel(new StandardChannel(154_310_000L))
            .identifiers(new IdentifierCollection())
            .build();
        DecodeEvent staleDisabled = DecodeEvent.builder(DecodeEventType.CALL, disabledTimestamp)
            .channel(new StandardChannel(154_310_000L))
            .identifiers(new IdentifierCollection())
            .build();
        DecodeEvent current = DecodeEvent.builder(DecodeEventType.CALL, newTimestamp)
            .channel(new StandardChannel(154_310_000L))
            .identifiers(new IdentifierCollection())
            .build();
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        Thread staleActiveProducer = new Thread(() -> {
            try
            {
                service.getDecodeEventListener().accept(channel, staleActive);
            }
            catch(Throwable throwable)
            {
                producerFailure.set(throwable);
            }
        }, "stale active-epoch producer");
        Thread staleDisabledProducer = new Thread(() -> {
            try
            {
                service.getDecodeEventListener().accept(channel, staleDisabled);
            }
            catch(Throwable throwable)
            {
                producerFailure.compareAndSet(null, throwable);
            }
        }, "stale disabled-epoch producer");

        try
        {
            staleActiveProducer.start();
            assertTrue(activeEpochCaptured.await(2, TimeUnit.SECONDS));

            applicationPreference.setCollectionEnabled(false);
            service.preferenceUpdated(PreferenceType.APPLICATION);
            staleDisabledProducer.start();
            assertTrue(disabledEpochCaptured.await(2, TimeUnit.SECONDS));
            applicationPreference.setCollectionEnabled(true);
            service.preferenceUpdated(PreferenceType.APPLICATION);

            releaseActiveProducer.countDown();
            releaseDisabledProducer.countDown();
            staleActiveProducer.join(TimeUnit.SECONDS.toMillis(2));
            staleDisabledProducer.join(TimeUnit.SECONDS.toMillis(2));
            assertFalse(staleActiveProducer.isAlive());
            assertFalse(staleDisabledProducer.isAlive());
            assertEquals(null, producerFailure.get());

            service.getDecodeEventListener().accept(channel, current);
            awaitCount(database, "p25_activity_event", 1);
            assertEquals(newTimestamp, scalar(database,
                "SELECT observed_at_ms FROM p25_activity_event"));
        }
        finally
        {
            releaseActiveProducer.countDown();
            releaseDisabledProducer.countDown();
            staleActiveProducer.join(TimeUnit.SECONDS.toMillis(2));
            staleDisabledProducer.join(TimeUnit.SECONDS.toMillis(2));
            service.dispose();
        }
    }

    @Test
    void completedCallPausedAcrossDisableAndReenableCannotWriteIntoTheNewEpoch() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        CountDownLatch oldEpochCaptured = new CountDownLatch(1);
        CountDownLatch releaseOldOutput = new CountDownLatch(1);
        AtomicBoolean pauseNextOutput = new AtomicBoolean();
        Runnable pauseAfterSnapshot = () -> {
            if(pauseNextOutput.compareAndSet(true, false))
            {
                oldEpochCaptured.countDown();

                try
                {
                    releaseOldOutput.await();
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }
        };
        P25ActivityLogService service = new P25ActivityLogService(userPreferences, 2, TimeUnit.SECONDS,
            pauseAfterSnapshot);
        Channel channel = new Channel("Completed-call epochs", Channel.ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigNBFM());
        long frequency = 154_310_000L;
        long start = System.currentTimeMillis();
        DecodeEvent context = DecodeEvent.builder(DecodeEventType.CALL, start)
            .channel(new StandardChannel(frequency))
            .identifiers(new IdentifierCollection())
            .build();
        CompletedAudioCall stale = conventionalCompletedCall(1, channel.getConfigurationId(),
            channel.getRadresGuid(), frequency, start + 1_000L);
        CompletedAudioCall current = conventionalCompletedCall(2, channel.getConfigurationId(),
            channel.getRadresGuid(), frequency, start + 2_000L);
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        Thread staleProducer = new Thread(() -> {
            try
            {
                service.receiveRecordedCall(stale);
            }
            catch(Throwable throwable)
            {
                producerFailure.set(throwable);
            }
        }, "stale completed-call producer");

        try
        {
            service.getDecodeEventListener().accept(channel, context);
            awaitCount(database, "p25_activity_event", 1);
            assertEquals(new P25ActivityLogMapper().mapCompletedCallOutput(current.snapshot(),
                    P25ActivityLogRecords.CallOutput.RECORDED).contextKey(),
                scalarText(database, "SELECT context_key FROM receiver_context LIMIT 1"));

            var retiredIngress = service.getObservationIngressForTest();
            pauseNextOutput.set(true);
            staleProducer.start();
            assertTrue(oldEpochCaptured.await(2, TimeUnit.SECONDS));

            applicationPreference.setCollectionEnabled(false);
            service.preferenceUpdated(PreferenceType.APPLICATION);
            applicationPreference.setCollectionEnabled(true);
            service.preferenceUpdated(PreferenceType.APPLICATION);

            releaseOldOutput.countDown();
            staleProducer.join(TimeUnit.SECONDS.toMillis(2));
            assertFalse(staleProducer.isAlive());
            assertEquals(null, producerFailure.get());
            var retiredOutput = retiredIngress.poll();
            assertTrue(retiredOutput != null && retiredOutput.first() instanceof AudioCallSnapshot,
                "the bounded statistics queue must retain only compact call metadata");
            assertFalse(retiredOutput.first() instanceof CompletedAudioCall,
                "completed-call audio buffers must never be retained by statistics ingress");

            service.receiveRecordedCall(current);
            awaitScalar(database,
                "SELECT COALESCE(SUM(recorded_count), 0) FROM conventional_activity_summary", 1);
            assertEquals(1, scalar(database,
                "SELECT COALESCE(SUM(recorded_count), 0) FROM conventional_activity_summary"));
        }
        finally
        {
            releaseOldOutput.countDown();
            staleProducer.join(TimeUnit.SECONDS.toMillis(2));
            service.dispose();
        }
    }

    @Test
    void writerReplacementUsesDistinctInactiveAndActiveEpochs() throws Exception
    {
        Path firstDatabase = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        Path secondRoot = mTemporaryFolder.resolve("replacement");
        Path secondDatabase = SdrTrunkDatabasePath.getDatabasePath(secondRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(firstDatabase);
        SdrTrunkDatabaseStartup.createGlobalDatabase(secondDatabase);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestDirectoryPreference directoryPreference = new TestDirectoryPreference(mTemporaryFolder);
        TestUserPreferences userPreferences = new TestUserPreferences(applicationPreference, directoryPreference);
        CountDownLatch oldActiveCaptured = new CountDownLatch(1);
        CountDownLatch inactiveCaptured = new CountDownLatch(1);
        CountDownLatch releaseOldActive = new CountDownLatch(1);
        CountDownLatch releaseInactive = new CountDownLatch(1);
        CountDownLatch writerReadyToActivate = new CountDownLatch(1);
        CountDownLatch allowWriterActivation = new CountDownLatch(1);
        AtomicInteger producerSnapshot = new AtomicInteger();
        Runnable pauseAfterSnapshot = () -> {
            int snapshot = producerSnapshot.incrementAndGet();
            CountDownLatch captured = snapshot == 1 ? oldActiveCaptured : snapshot == 2 ? inactiveCaptured : null;
            CountDownLatch release = snapshot == 1 ? releaseOldActive : snapshot == 2 ? releaseInactive : null;

            if(captured != null)
            {
                captured.countDown();

                try
                {
                    release.await();
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }
        };
        Runnable pauseBeforeActivation = () -> {
            writerReadyToActivate.countDown();

            try
            {
                allowWriterActivation.await();
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        };
        P25ActivityLogService service = new P25ActivityLogService(userPreferences, 2, TimeUnit.SECONDS,
            pauseAfterSnapshot, pauseBeforeActivation);
        Channel channel = new Channel("Writer transition epochs", Channel.ChannelType.STANDARD);
        channel.setRadresGuid("00000000-0000-0000-0000-000000000306");
        channel.setDecodeConfiguration(new DecodeConfigNBFM());
        long start = System.currentTimeMillis();
        DecodeEvent oldActive = conventionalEvent(start);
        DecodeEvent inactive = conventionalEvent(start + 5_000L);
        DecodeEvent current = conventionalEvent(start + 10_000L);
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        Thread oldActiveProducer = observationProducer(service, channel, oldActive, producerFailure,
            "old writer epoch producer");
        Thread inactiveProducer = observationProducer(service, channel, inactive, producerFailure,
            "inactive writer epoch producer");

        try
        {
            oldActiveProducer.start();
            assertTrue(oldActiveCaptured.await(2, TimeUnit.SECONDS));
            directoryPreference.setRoot(secondRoot);
            service.preferenceUpdated(PreferenceType.DIRECTORY);
            assertTrue(writerReadyToActivate.await(8, TimeUnit.SECONDS));

            inactiveProducer.start();
            assertTrue(inactiveCaptured.await(2, TimeUnit.SECONDS));
            allowWriterActivation.countDown();
            awaitWriterTransition(service, secondDatabase);

            releaseOldActive.countDown();
            releaseInactive.countDown();
            oldActiveProducer.join(TimeUnit.SECONDS.toMillis(2));
            inactiveProducer.join(TimeUnit.SECONDS.toMillis(2));
            assertFalse(oldActiveProducer.isAlive());
            assertFalse(inactiveProducer.isAlive());
            assertEquals(null, producerFailure.get());

            service.getDecodeEventListener().accept(channel, current);
            awaitCount(secondDatabase, "p25_activity_event", 1);
            assertEquals(start + 10_000L, scalar(secondDatabase,
                "SELECT observed_at_ms FROM p25_activity_event"));
            assertEquals(0, count(firstDatabase, "p25_activity_event"));
        }
        finally
        {
            releaseOldActive.countDown();
            releaseInactive.countDown();
            allowWriterActivation.countDown();
            oldActiveProducer.join(TimeUnit.SECONDS.toMillis(2));
            inactiveProducer.join(TimeUnit.SECONDS.toMillis(2));
            service.dispose();
        }
    }

    private static DecodeEvent conventionalEvent(long timestamp)
    {
        return DecodeEvent.builder(DecodeEventType.CALL, timestamp)
            .channel(new StandardChannel(154_310_000L))
            .identifiers(new IdentifierCollection())
            .build();
    }

    private static CompletedAudioCall conventionalCompletedCall(long sequence, String configurationId, String guid,
                                                                 long frequency, long timestamp)
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(ChannelConfigurationIdentifier.create(configurationId));
        identifiers.update(SiteGuidConfigurationIdentifier.create(guid));
        identifiers.update(FrequencyConfigurationIdentifier.create(frequency));
        identifiers.update(DecoderTypeConfigurationIdentifier.create(DecoderType.NBFM));
        AudioCallSnapshot snapshot = new AudioCallSnapshot(new AudioCallId(sequence, sequence + 1, 0), null, null,
            identifiers, Set.of(), timestamp, timestamp + 100L, 1, 1, timestamp, timestamp + 100L,
            false, true, false, true, false);
        return new CompletedAudioCall(snapshot, List.of(new float[800]));
    }

    private static Thread observationProducer(P25ActivityLogService service, Channel channel, DecodeEvent event,
                                              AtomicReference<Throwable> failure, String name)
    {
        return new Thread(() -> {
            try
            {
                service.getDecodeEventListener().accept(channel, event);
            }
            catch(Throwable throwable)
            {
                failure.compareAndSet(null, throwable);
            }
        }, name);
    }

    private static void awaitWriterTransition(P25ActivityLogService service, Path expectedPath)
        throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);

        while((service.isWriterTransitionActiveForTest() ||
            !expectedPath.equals(service.getCurrentDatabasePathForTest())) && System.nanoTime() < deadline)
        {
            Thread.sleep(5);
        }

        assertFalse(service.isWriterTransitionActiveForTest(), "writer transition did not finish");
        assertEquals(expectedPath, service.getCurrentDatabasePathForTest());
    }

    private static void awaitWorkerTermination(P25ActivityLogService service) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);

        while(!service.isObservationWorkerTerminated() && System.nanoTime() < deadline)
        {
            Thread.sleep(5);
        }

        assertTrue(service.isObservationWorkerTerminated(), "statistics observer did not terminate");
    }

    @Test
    void countsOneConventionalP25StartNotItsMutableTrackerUpdates() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        P25ActivityLogService service = new P25ActivityLogService(userPreferences);
        Channel channel = new Channel("LorainCountySO", Channel.ChannelType.STANDARD);
        channel.setRadresGuid("00000000-0000-0000-0000-000000000302");
        channel.setDecodeConfiguration(new DecodeConfigP25Conventional());
        P25TrafficChannelManager manager = new P25TrafficChannelManager(channel);
        manager.addDecodeEventListener(event -> service.getDecodeEventListener().accept(channel, event));
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(1_201));
        identifiers.update(APCO25RadioIdentifier.createFrom(1_234_567));
        long frequency = 154_875_000L;
        long start = System.currentTimeMillis();

        try
        {
            manager.processP1TrafficCurrentUser(frequency, null, DecodeEventType.CALL_GROUP,
                VoiceServiceOptions.createUnencrypted(), identifiers, start, null);
            manager.processP1TrafficCurrentUser(frequency, new StandardChannel(frequency),
                DecodeEventType.CALL_GROUP, VoiceServiceOptions.createUnencrypted(), identifiers, start + 100L, null);
            manager.processP1TrafficCallEnd(frequency, start + 200L);

            awaitCount(database, "p25_activity_event", 1);
            assertEquals(1, scalar(database,
                "SELECT call_count FROM conventional_activity_summary"));
            assertEquals(1, scalar(database,
                "SELECT call_count FROM conventional_activity_bucket"));
            assertEquals(0, scalar(database,
                "SELECT active_count FROM conventional_activity_summary"));
        }
        finally
        {
            service.dispose();
        }
    }

    @Test
    void countsBackToBackNbfmCallsAndStoresOptionalHistory() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        P25ActivityLogService service = new P25ActivityLogService(userPreferences);
        Channel channel = new Channel("County Fire", Channel.ChannelType.STANDARD);
        channel.setRadresGuid("00000000-0000-0000-0000-000000000301");
        channel.setDecodeConfiguration(new DecodeConfigNBFM());
        long frequency = 154_310_000L;
        long start = System.currentTimeMillis();

        try
        {
            DecodeEvent first = DecodeEvent.builder(DecodeEventType.CALL, start)
                .channel(new StandardChannel(frequency))
                .identifiers(new IdentifierCollection())
                .build();
            service.getDecodeEventListener().accept(channel, first);
            first.update(start + 100L);
            service.getDecodeEventListener().accept(channel, first);
            first.end(start + 200L);
            service.getDecodeEventListener().accept(channel, first);

            DecodeEvent second = DecodeEvent.builder(DecodeEventType.CALL, start + 500L)
                .channel(new StandardChannel(frequency))
                .identifiers(new IdentifierCollection())
                .build();
            service.getDecodeEventListener().accept(channel, second);

            awaitCount(database, "p25_activity_event", 2);
            assertEquals(2, scalar(database,
                "SELECT call_count FROM conventional_activity_summary"));
            assertEquals(2, scalar(database,
                "SELECT call_count FROM conventional_activity_bucket"));
        }
        finally
        {
            service.dispose();
        }
    }

    @Test
    void lowersRetentionAndRunsMaintenanceWhileCollectionIsDisabled() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        long now = System.currentTimeMillis();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.recordActivity(connection,
                activity(now - TimeUnit.DAYS.toMillis(40)), true);
            P25ActivityLogSchema.recordActivity(connection,
                activity(now - TimeUnit.DAYS.toMillis(2)), true);

            try(var statement = connection.prepareStatement("""
                INSERT INTO trunked_site_snapshot (
                    guid, snapshot_hash, protocol_code, variant_code, identity_domain_code,
                    first_seen_ms, last_seen_ms, observation_count
                ) VALUES (?, ?, ?, 1, 1, ?, ?, 1)
                """))
            {
                insertTrunkedSite(statement, "expired-dmr", TrunkedSiteSchema.PROTOCOL_DMR,
                    now - TimeUnit.DAYS.toMillis(40));
                insertTrunkedSite(statement, "current-dmr", TrunkedSiteSchema.PROTOCOL_DMR,
                    now - TimeUnit.DAYS.toMillis(2));
                insertTrunkedSite(statement, "expired-nxdn", TrunkedSiteSchema.PROTOCOL_NXDN,
                    now - TimeUnit.DAYS.toMillis(40));
                insertTrunkedSite(statement, "current-nxdn", TrunkedSiteSchema.PROTOCOL_NXDN,
                    now - TimeUnit.DAYS.toMillis(2));
            }
        }

        TestApplicationPreference applicationPreference = new TestApplicationPreference(false, 30);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        P25ActivityLogService service = new P25ActivityLogService(userPreferences);

        try
        {
            StatsDatabaseMaintenanceRequest initialCheck =
                StatsDatabaseMaintenanceRequest.forOperation(P25ActivityLogMaintenance.Operation.CHECK);
            service.receiveMaintenanceRequest(initialCheck);
            assertTrue(initialCheck.result().get(5, TimeUnit.SECONDS).checkOk());
            assertEquals(P25ActivityLogStatus.State.DISABLED, service.getStatus().state());
            //Startup maintenance used the 30-day setting even though collection was disabled.
            assertEquals(1, count(database, "p25_activity_event"));
            assertEquals(2, count(database, "trunked_site_snapshot"));
            assertEquals(1, countProtocol(database, TrunkedSiteSchema.PROTOCOL_DMR));
            assertEquals(1, countProtocol(database, TrunkedSiteSchema.PROTOCOL_NXDN));

            applicationPreference.setRetentionDays(1);
            service.preferenceUpdated(PreferenceType.APPLICATION);

            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
            int remainingP25 = 1;
            int remainingTrunked = 2;

            while((remainingP25 != 0 || remainingTrunked != 0) && System.currentTimeMillis() < deadline)
            {
                remainingP25 = count(database, "p25_activity_event");
                remainingTrunked = count(database, "trunked_site_snapshot");

                if(remainingP25 != 0 || remainingTrunked != 0)
                {
                    Thread.sleep(25);
                }
            }

            assertEquals(0, remainingP25);
            assertEquals(0, remainingTrunked);

            StatsDatabaseMaintenanceRequest finalCheck =
                StatsDatabaseMaintenanceRequest.forOperation(P25ActivityLogMaintenance.Operation.CHECK);
            service.receiveMaintenanceRequest(finalCheck);
            assertTrue(finalCheck.result().get(5, TimeUnit.SECONDS).checkOk());
            assertEquals(P25ActivityLogStatus.State.DISABLED, service.getStatus().state());

            Channel channel = new Channel("Disabled collection", Channel.ChannelType.STANDARD);
            channel.setRadresGuid("00000000-0000-0000-0000-000000000102");
            service.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(channel,
                new DMRNetworkConfigurationSnapshot("DMR", "TIER_III", 1, 2, null, null, null, null,
                    null, null, List.of(), List.of()),
                System.currentTimeMillis()));
            Thread.sleep(1_100);
            assertEquals(0, count(database, "trunked_site_snapshot"));
        }
        finally
        {
            service.dispose();
        }
    }

    @Test
    void exposesMaintenanceWriterFailureWhileCollectionIsDisabled() throws Exception
    {
        TestApplicationPreference applicationPreference = new TestApplicationPreference(false, 30);
        TestUserPreferences userPreferences = new TestUserPreferences(applicationPreference,
            new TestDirectoryPreference(mTemporaryFolder.resolve("missing-portable-data")));
        P25ActivityLogService service = new P25ActivityLogService(userPreferences);

        try
        {
            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);

            while(service.getStatus().state() != P25ActivityLogStatus.State.FAILED &&
                System.currentTimeMillis() < deadline)
            {
                Thread.sleep(25);
            }

            assertEquals(P25ActivityLogStatus.State.FAILED, service.getStatus().state());
            assertTrue(service.getStatus().lastError() != null && !service.getStatus().lastError().isBlank());
        }
        finally
        {
            service.dispose();
        }
    }

    @Test
    void persistsExplicitTrunkedDmrQualityWithoutPromotingConventionalDmr() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        P25ActivityLogService service = new P25ActivityLogService(userPreferences);

        try
        {
            long now = System.currentTimeMillis();
            Channel trunked = dmrChannel("00000000-0000-0000-0000-000000000201", DMRChannelMode.TRUNKED);
            service.getControlChannelQualityListener().receive(quality(trunked, now));
            awaitCount(database, "p25_control_channel_quality", 1);

            service.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(trunked,
                new DMRNetworkConfigurationSnapshot("DMR", null, 10, 20, null, null, null, null,
                    1, 2, List.of(), List.of()), System.currentTimeMillis()));
            service.getControlChannelQualityListener().receive(quality(trunked, now + 10_000L));
            awaitCount(database, "p25_control_channel_quality", 2);
            assertEquals(0, count(database, "trunked_site_snapshot"));

            service.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(trunked,
                new DMRNetworkConfigurationSnapshot("DMR", "TIER_III", 10, 20, "Tier III Trunking",
                    "SMALL", null, "Control", 1, 2, List.of(), List.of()), System.currentTimeMillis()));
            service.getControlChannelQualityListener().receive(quality(trunked, now + 20_000L));
            awaitCount(database, "p25_control_channel_quality", 3);
            assertEquals(1, count(database, "trunked_site_snapshot"));

            applicationPreference.setCollectionEnabled(false);
            service.preferenceUpdated(PreferenceType.APPLICATION);
            applicationPreference.setCollectionEnabled(true);
            service.preferenceUpdated(PreferenceType.APPLICATION);
            service.getControlChannelQualityListener().receive(quality(trunked, now + 40_000L));

            service.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(trunked,
                new DMRNetworkConfigurationSnapshot("DMR", "TIER_III", 10, 20, "Tier III Trunking",
                    "SMALL", null, "Control", 1, 2, List.of(), List.of()), System.currentTimeMillis()));
            Channel reusedGuid = dmrChannel(trunked.getRadresGuid(), DMRChannelMode.TRUNKED);
            service.getControlChannelQualityListener().receive(quality(reusedGuid, now + 60_000L));

            service.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(trunked,
                new DMRNetworkConfigurationSnapshot("DMR", "TIER_III", 10, 20, "Tier III Trunking",
                    "SMALL", null, "Control", 1, 2, List.of(), List.of()), System.currentTimeMillis()));
            service.getControlChannelQualityListener().receive(quality(trunked, now + 600_000L));
            awaitCount(database, "p25_control_channel_quality", 6);

            service.getControlChannelQualityListener().receive(quality(trunked, now + 610_000L, false));
            service.getControlChannelQualityListener().receive(quality(trunked, now + 620_000L));

            service.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(trunked,
                new DMRNetworkConfigurationSnapshot("DMR", "TIER_III", 10, 20, "Tier III Trunking",
                    "SMALL", null, "Control", 1, 2, List.of(), List.of()), System.currentTimeMillis()));
            service.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(trunked,
                new DMRNetworkConfigurationSnapshot("DMR", null, 10, 20, null, null, null, null,
                    1, 2, List.of(), List.of()), System.currentTimeMillis()));
            service.getControlChannelQualityListener().receive(quality(trunked, now + 640_000L));

            Channel conventional = dmrChannel("00000000-0000-0000-0000-000000000202");
            service.getControlChannelQualityListener().receive(quality(conventional, now + 660_000L));
            awaitCount(database, "p25_control_channel_quality", 8);
        }
        finally
        {
            service.dispose();
        }
    }

    private static int count(Path database, String table) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table))
        {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static int countProtocol(Path database, int protocol) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM trunked_site_snapshot WHERE protocol_code = ?"))
        {
            statement.setInt(1, protocol);

            try(ResultSet resultSet = statement.executeQuery())
            {
                assertTrue(resultSet.next());
                return resultSet.getInt(1);
            }
        }
    }

    private static long scalar(Path database, String sql) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private static String scalarText(Path database, String sql) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static void awaitCount(Path database, String table, int expected) throws Exception
    {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        int actual = count(database, table);

        while(actual != expected && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(25);
            actual = count(database, table);
        }

        assertEquals(expected, actual);
    }

    private static void awaitScalar(Path database, String sql, long expected) throws Exception
    {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        long actual = scalar(database, sql);

        while(actual != expected && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(25);
            actual = scalar(database, sql);
        }

        assertEquals(expected, actual);
    }

    private static Channel dmrChannel(String guid)
    {
        return dmrChannel(guid, DMRChannelMode.CONVENTIONAL);
    }

    private static Channel dmrChannel(String guid, DMRChannelMode mode)
    {
        Channel channel = new Channel("DMR", Channel.ChannelType.STANDARD);
        channel.setRadresGuid(guid);
        DecodeConfigDMR configuration = new DecodeConfigDMR();
        configuration.setChannelMode(mode);
        channel.setDecodeConfiguration(configuration);
        return channel;
    }

    private static ControlChannelQualitySnapshot quality(Channel channel, long observedAt)
    {
        return quality(channel, observedAt, true);
    }

    private static ControlChannelQualitySnapshot quality(Channel channel, long observedAt, boolean active)
    {
        return new ControlChannelQualitySnapshot(channel, channel.getRadresGuid(), 451_012_500L, observedAt,
            active, -20.0, -21.0, -25.0, -18.0, 95.0, 100, 2, 1, 0, 0, observedAt);
    }

    private static void insertTrunkedSite(java.sql.PreparedStatement statement, String guid, int protocol,
                                          long observedAt) throws Exception
    {
        statement.setString(1, guid);
        statement.setString(2, "hash-" + guid);
        statement.setInt(3, protocol);
        statement.setLong(4, observedAt);
        statement.setLong(5, observedAt);
        statement.executeUpdate();
    }

    private static P25ActivityLogRecords.ActivityEvent activity(long timestamp)
    {
        String guid = "123e4567-e89b-12d3-a456-426614174000";
        return new P25ActivityLogRecords.ActivityEvent(timestamp, "GUID:" + guid, guid,
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "APCO25", P25ActivityLogRecords.Action.GRANT,
            "CALL_GROUP", "1811524", "56138", "TALKGROUP", 854_187_500L, "00-0509", 1, false,
            null, null, 0xBEE00, 0x348, 0x348, 2, 1, "Example Site", null, null, false, null, null);
    }

    private static class TestUserPreferences extends UserPreferences
    {
        private final ApplicationPreference mApplicationPreference;
        private final DirectoryPreference mDirectoryPreference;

        private TestUserPreferences(ApplicationPreference applicationPreference,
                                    DirectoryPreference directoryPreference)
        {
            mApplicationPreference = applicationPreference;
            mDirectoryPreference = directoryPreference;
        }

        @Override
        public ApplicationPreference getApplicationPreference()
        {
            return mApplicationPreference;
        }

        @Override
        public DirectoryPreference getDirectoryPreference()
        {
            return mDirectoryPreference;
        }
    }

    private static class TestApplicationPreference extends ApplicationPreference
    {
        private boolean mCollectionEnabled;
        private int mRetentionDays;
        private final boolean mDetailedHistoryEnabled;

        private TestApplicationPreference(boolean collectionEnabled, int retentionDays)
        {
            this(collectionEnabled, retentionDays, false);
        }

        private TestApplicationPreference(boolean collectionEnabled, int retentionDays,
                                          boolean detailedHistoryEnabled)
        {
            super(preferenceType -> {});
            mCollectionEnabled = collectionEnabled;
            mRetentionDays = retentionDays;
            mDetailedHistoryEnabled = detailedHistoryEnabled;
        }

        @Override
        public boolean isStatsLoggingEnabled()
        {
            return mCollectionEnabled;
        }

        private void setCollectionEnabled(boolean collectionEnabled)
        {
            mCollectionEnabled = collectionEnabled;
        }

        @Override
        public boolean isStatsDetailedHistoryEnabled()
        {
            return mDetailedHistoryEnabled;
        }

        @Override
        public int getStatsLoggingRetentionDays()
        {
            return mRetentionDays;
        }

        private void setRetentionDays(int retentionDays)
        {
            mRetentionDays = retentionDays;
        }
    }

    private static class TestDirectoryPreference extends DirectoryPreference
    {
        private Path mRoot;

        private TestDirectoryPreference(Path root)
        {
            super(preferenceType -> {});
            mRoot = root;
        }

        @Override
        public Path getDirectoryApplicationRoot()
        {
            return mRoot;
        }

        private void setRoot(Path root)
        {
            mRoot = root;
        }
    }
}
