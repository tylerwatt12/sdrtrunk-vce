/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.channel.TunerChannelSource;
import io.github.dsheirer.source.tuner.configuration.TunerConfigurationManager;
import io.github.dsheirer.source.tuner.manager.DiscoveredRecordingTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.recording.RecordingTunerConfiguration;
import io.github.dsheirer.source.tuner.recording.RecordingTunerController;
import io.github.dsheirer.source.wave.ComplexWaveSource;
import io.github.dsheirer.web.http.ApiHttpResponse;
import io.github.dsheirer.web.tuner.TunerAdministrationService;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the ordinary scheduled WAV producer, never an injected replay or synthetic diagnostic target. */
class TunerDiagnosticRecordingPlaybackTest
{
    private static final long CENTER = 851_000_000L;
    @TempDir Path mTemporaryDirectory;

    @Test
    void continuouslyPlayingRecordingIsVisibleWithoutAllocatedChannelsAndRebindsAfterRestart() throws Exception
    {
        Path wave = wave(2_400_000);
        byte[] before = Files.readAllBytes(wave);
        TunerManager manager = manager();
        DiscoveredRecordingTuner recording = recording(manager, wave);
        try(DiagnosticFftScheduler scheduler = new DiagnosticFftScheduler();
            TunerDiagnosticService diagnostics = new TunerDiagnosticService(manager, scheduler))
        {
            recording.setEnabled(true);
            Constructor<TunerAdministrationService> inventoryConstructor = TunerAdministrationService.class
                .getDeclaredConstructor(java.util.function.Supplier.class, java.util.function.Function.class);
            inventoryConstructor.setAccessible(true);
            TunerAdministrationService inventory = inventoryConstructor.newInstance(
                (java.util.function.Supplier<?>)manager.getDiscoveredTunerRegistry()::snapshot,
                (java.util.function.Function<io.github.dsheirer.source.tuner.Tuner,String>)diagnostics::targetIdFor);
            TunerAdministrationService.Item item = inventory.item(recording);
            assertEquals(0, item.channelCount());
            assertTrue(item.spectrumAvailable(), "The WAV producer runs even without allocated channels");
            assertNotNull(item.spectrumTargetId());
            TunerDiagnosticService.Session first = diagnostics.tryOpen(item.spectrumTargetId()).session();
            assertNotNull(first);
            assertFrame(first);
            assertFrame(first); // The short file crosses its ordinary EOF/auto-replay boundary between frames.
            assertEquals("live", first.state().state());
            assertEquals(0, first.state().activeChannelCount());

            recording.setEnabled(false);
            assertEquals("unavailable", first.state().state());
            assertFalse(inventory.item(recording).spectrumAvailable());
            assertTrue(diagnostics.targets().isEmpty());
            assertEquals(0, scheduler.activeTaskCount());
            first.close();

            recording.setEnabled(true);
            String target = inventory.item(recording).spectrumTargetId();
            assertNotNull(target);
            assertNotEquals(item.spectrumTargetId(), target, "Restart must bind the new tuner identity");
            try(TunerDiagnosticService.Session restarted = diagnostics.tryOpen(target).session())
            {
                assertNotNull(restarted);
                assertFrame(restarted);
            }
            assertEquals(0, diagnostics.activeProducerCount());
            assertEquals(0, scheduler.activeTaskCount());
        }
        finally
        {
            manager.getDiscoveredTunerRegistry().release();
        }
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(wave)), "Playback must preserve the WAV");
    }

    @Test
    void spectrumSharesOrdinaryWidebandPlaybackWithARealChannelConsumerAcrossEof() throws Exception
    {
        sharedChannelPlayback(2_400_000);
    }

    @Test
    void spectrumSharesOrdinaryPassThroughPlaybackWithARealChannelConsumer() throws Exception
    {
        sharedChannelPlayback(48_000);
    }

    @Test
    void recordingStateTracksRealSampleArrivalAndAStalledProducerInsteadOfClaimingLive() throws Exception
    {
        TunerManager manager = manager();
        DiscoveredRecordingTuner recording = recording(manager, wave(2_400_000));
        try(DiagnosticFftScheduler scheduler = new DiagnosticFftScheduler();
            TunerDiagnosticService diagnostics = new TunerDiagnosticService(manager, scheduler))
        {
            recording.setEnabled(true);
            RecordingTunerController controller = (RecordingTunerController)recording.getTuner().getTunerController();
            Field sourceField = RecordingTunerController.class.getDeclaredField("mComplexWaveSource");
            sourceField.setAccessible(true);
            ComplexWaveSource source = (ComplexWaveSource)sourceField.get(controller);
            source.stop(); // Model an enabled tuner whose ordinary producer has stopped delivering samples.
            try(TunerDiagnosticService.Session session = diagnostics.tryOpen(diagnostics.targetIdFor(recording.getTuner())).session())
            {
                assertNotNull(session);
                assertEquals("waiting", session.state().state(), "A new tap has not received samples yet");
                long before = controller.getPlaybackStatus().sampleBufferCount();
                source.start();
                assertFrame(session);
                assertEquals("live", session.state().state());
                assertEquals("playing", controller.getPlaybackStatus().state());
                assertTrue(controller.getPlaybackStatus().sampleBufferCount() > before);
                assertTrue(controller.getPlaybackStatus().lastSampleAtEpochMs() > 0);
                source.stop();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while(!"waiting".equals(session.state().state()) && System.nanoTime() < deadline) Thread.sleep(10);
                assertEquals("waiting", session.state().state());
                assertEquals("Recording playback has no recent samples.", session.state().reason());
                assertEquals("waiting", controller.getPlaybackStatus().state());
            }
            recording.setEnabled(false);
            assertEquals("stopped", controller.getPlaybackStatus().state());
        }
        finally { manager.getDiscoveredTunerRegistry().release(); }
    }

    @Test
    void emptyRecordingHasAWaitingTargetButNeverFabricatesSpectrumSamples() throws Exception
    {
        TunerManager manager = manager();
        DiscoveredRecordingTuner recording = recording(manager, wave(2_400_000, 0));
        try(DiagnosticFftScheduler scheduler = new DiagnosticFftScheduler();
            TunerDiagnosticService diagnostics = new TunerDiagnosticService(manager, scheduler))
        {
            recording.setEnabled(true);
            RecordingTunerController controller = (RecordingTunerController)recording.getTuner().getTunerController();
            assertEquals("waiting", controller.getPlaybackStatus().state());
            assertEquals(0, controller.getPlaybackStatus().sampleBufferCount());
            try(TunerDiagnosticService.Session session = diagnostics.tryOpen(diagnostics.targetIdFor(recording.getTuner())).session())
            {
                assertNotNull(session);
                assertEquals("waiting", session.state().state());
                assertEquals(null, session.poll(Duration.ofMillis(250)));
            }
            Constructor<TunerAdministrationService> constructor = TunerAdministrationService.class
                .getDeclaredConstructor(java.util.function.Supplier.class, java.util.function.Function.class);
            constructor.setAccessible(true);
            TunerAdministrationService inventory = constructor.newInstance(
                (java.util.function.Supplier<?>)manager.getDiscoveredTunerRegistry()::snapshot,
                (java.util.function.Function<io.github.dsheirer.source.tuner.Tuner,String>)diagnostics::targetIdFor);
            var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(
                ApiHttpResponse.normalizePayload(inventory.item(recording)));
            assertEquals("waiting", json.path("recording_playback").path("state").asText());
            assertEquals(0, json.path("recording_playback").path("sample_buffer_count").asLong());
            assertEquals(1_500, json.path("recording_playback").path("stale_after_milliseconds").asLong());
            assertFalse(json.toString().contains(mTemporaryDirectory.toString()));
            recording.setEnabled(false);
            assertEquals("stopped", inventory.item(recording).recordingPlayback().state());
        }
        finally { manager.getDiscoveredTunerRegistry().release(); }
    }

    private void sharedChannelPlayback(int rate) throws Exception
    {
        TunerManager manager = manager();
        DiscoveredRecordingTuner recording = recording(manager, wave(rate));
        TunerChannelSource channel = null;
        try(DiagnosticFftScheduler scheduler = new DiagnosticFftScheduler();
            TunerDiagnosticService diagnostics = new TunerDiagnosticService(manager, scheduler))
        {
            recording.setEnabled(true);
            AtomicLong samples = new AtomicLong();
            CountDownLatch received = new CountDownLatch(1);
            channel = recording.getTuner().getChannelSourceManager().getSource(new TunerChannel(CENTER, 12_500),
                new ChannelSpecification(25_000, 12_500, 5_000, 6_250), "recording spectrum test channel");
            assertNotNull(channel);
            channel.setListener(buffer ->
            {
                samples.addAndGet(buffer.i().length);
                received.countDown();
            });
            channel.start();
            assertTrue(received.await(5, TimeUnit.SECONDS), "The real channel must consume scheduled WAV samples");
            assertEquals(1, recording.getTuner().getChannelSourceManager().getTunerChannelCount());
            assertEquals(1, diagnostics.targets().size());
            String target = diagnostics.targetIdFor(recording.getTuner());
            try(TunerDiagnosticService.Session session = diagnostics.tryOpen(target).session())
            {
                assertNotNull(session);
                assertFrame(session);
                long before = samples.get();
                assertFrame(session);
                awaitProgress(samples, before);
                assertEquals(CENTER, session.state().centerFrequencyHz());
                assertEquals(rate, session.state().sampleRateHz());
                assertEquals(1, session.state().activeChannelCount());
            }
            assertEquals(0, scheduler.activeTaskCount());
            awaitProgress(samples, samples.get());
            channel.stop();
            channel = null;
            assertEquals(0, recording.getTuner().getChannelSourceManager().getTunerChannelCount());
            assertEquals(1, diagnostics.targets().size(), "WAV playback continues when its last channel stops");
        }
        finally
        {
            if(channel != null) channel.stop();
            manager.getDiscoveredTunerRegistry().release();
        }
    }

    private static void awaitProgress(AtomicLong samples, long before) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while(samples.get() <= before && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(samples.get() > before, "The existing channel must continue receiving samples with and after Spectrum");
    }

    private static void assertFrame(TunerDiagnosticService.Session session) throws Exception
    {
        DiagnosticStreamFrame frame = session.poll(Duration.ofSeconds(5));
        assertNotNull(frame, "Ordinary WAV playback must reach the bounded FFT worker");
        assertEquals(DiagnosticStreamFrame.TYPE_TUNER_FFT, frame.type());
        assertEquals(CENTER, frame.centerFrequencyHz());
        assertEquals(8_192, frame.valueCount());
    }

    private DiscoveredRecordingTuner recording(TunerManager manager, Path wave)
    {
        RecordingTunerConfiguration configuration = RecordingTunerConfiguration.createWithUniqueId();
        configuration.setPath(wave.toString());
        configuration.setFrequency(CENTER);
        DiscoveredRecordingTuner recording = new DiscoveredRecordingTuner(configuration);
        manager.getDiscoveredTunerRegistry().add(recording);
        return recording;
    }

    private TunerManager manager() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("settings-" + java.util.UUID.randomUUID() + ".sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        Constructor<TunerConfigurationManager> configurationsConstructor = TunerConfigurationManager.class
            .getDeclaredConstructor(ApplicationSettingsStore.class, Executor.class);
        configurationsConstructor.setAccessible(true);
        TunerConfigurationManager configurations = configurationsConstructor.newInstance(
            new ApplicationSettingsStore(database), (Executor)Runnable::run);
        Constructor<TunerManager> managerConstructor = TunerManager.class
            .getDeclaredConstructor(UserPreferences.class, TunerConfigurationManager.class);
        managerConstructor.setAccessible(true);
        return managerConstructor.newInstance(null, configurations);
    }

    private Path wave(int rate) throws Exception
    {
        return wave(rate, 65_536 + 2_048);
    }

    private Path wave(int rate, int frames) throws Exception
    {
        ByteBuffer bytes = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(bytes.capacity() - 8);
        bytes.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1)
            .putShort((short)2).putInt(rate).putInt(rate * 4).putShort((short)4).putShort((short)16);
        bytes.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(frames * 4);
        for(int frame = 0; frame < frames; frame++)
        {
            double angle = 2 * Math.PI * 1_000 * frame / rate;
            bytes.putShort((short)(12_000 * Math.cos(angle))).putShort((short)(12_000 * Math.sin(angle)));
        }
        Path wave = mTemporaryDirectory.resolve("ordinary-" + rate + ".wav");
        Files.write(wave, bytes.array());
        return wave;
    }
}
