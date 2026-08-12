/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.metadata.ChannelMetadata;
import io.github.dsheirer.channel.metadata.ChannelMetadataField;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ChannelActivityIsolationTest
{
    @Test
    void corePublishesWhileSwingIsBlockedAndAdapterCatchesUp() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        ChannelActivityModel model = model(aliasModel);
        ChannelActivityTableModel adapter = new ChannelActivityTableModel(model.getConventionalTable());
        SwingUtilities.invokeAndWait(() -> {});
        CountDownLatch edtBlocked = new CountDownLatch(1);
        CountDownLatch releaseEdt = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            edtBlocked.countDown();

            try
            {
                releaseEdt.await(5, TimeUnit.SECONDS);
            }
            catch(InterruptedException interruptedException)
            {
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(edtBlocked.await(2, TimeUnit.SECONDS));

        try
        {
            model.channelStarted(channel(), List.of(new ChannelMetadata(aliasModel, 1)));
            assertTrue(model.awaitIdle(2, TimeUnit.SECONDS));
            assertEquals(1, model.getSnapshotSet().tables().getFirst().rows().size());
            assertEquals(0, adapter.getRowCount(), "blocked desktop renderer must not block core publication");
        }
        finally
        {
            releaseEdt.countDown();
        }

        SwingUtilities.invokeAndWait(() -> {});
        assertEquals(1, adapter.getRowCount());
        adapter.close();
        model.close();
    }

    @Test
    void saturatedIngressDropsWithoutRunningProjectionOnProducer() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        ChannelActivityModel model = model(aliasModel);
        Channel channel = channel();
        CountDownLatch projectionBlocked = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        AtomicBoolean blockOnce = new AtomicBoolean();
        AtomicReference<Thread> projectionThread = new AtomicReference<>();
        Thread producer = Thread.currentThread();
        model.addActivityListener(event -> {
            if(!event.snapshot().rows().isEmpty() && blockOnce.compareAndSet(false, true))
            {
                projectionThread.set(Thread.currentThread());
                projectionBlocked.countDown();

                try
                {
                    releaseProjection.await(5, TimeUnit.SECONDS);
                }
                catch(InterruptedException interruptedException)
                {
                    Thread.currentThread().interrupt();
                }
            }
        });
        model.channelStarted(channel, List.of(new ChannelMetadata(aliasModel, 1)));
        assertTrue(projectionBlocked.await(2, TimeUnit.SECONDS));
        ChannelMetadata unmapped = new ChannelMetadata(aliasModel, 1);

        try
        {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                for(int index = 0; index < model.getRegularIngressCapacity() * 2; index++)
                {
                    model.updated(unmapped, ChannelMetadataField.DECODER_STATE);
                }
            });
            assertTrue(model.getDroppedIngressCount() > 0);
            assertFalse(producer == projectionThread.get());
            assertTrue(projectionThread.get().getName().startsWith("channel activity"));
            model.channelStopped(channel);
        }
        finally
        {
            releaseProjection.countDown();
        }

        assertTrue(model.awaitIdle(5, TimeUnit.SECONDS));
        assertTrue(model.getSnapshotSet().tables().getFirst().rows().isEmpty());
        model.close();
    }

    @Test
    void brokenRendererCannotKillOrClearTheAuthoritativeWorker() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        ChannelActivityModel model = model(aliasModel);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        model.addActivityListener(event ->
        {
            if(!event.snapshot().rows().isEmpty() && failOnce.compareAndSet(true, false))
            {
                throw new AssertionError("simulated renderer failure");
            }
        });

        Channel channel = channel();
        model.channelStarted(channel, List.of(new ChannelMetadata(aliasModel, 1)));
        assertTrue(model.awaitIdle(2, TimeUnit.SECONDS));
        assertTrue(model.isWorkerAlive());
        assertEquals(1, model.getSnapshotSet().tables().getFirst().rows().size());

        model.channelConfigurationChanged(channel);
        assertTrue(model.awaitIdle(2, TimeUnit.SECONDS));
        assertTrue(model.isWorkerAlive());
        assertEquals(1, model.getSnapshotSet().tables().getFirst().rows().size());
        model.close();
    }

    @Test
    void droppedStartReconcilesAfterTheAuthoritativeChainIsPublished() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        ChannelActivityModel model = new ChannelActivityModel(aliasModel, new NowPlayingPreference(type -> {}),
            8, 2);
        AtomicReference<List<ChannelActivityModel.ActiveChannel>> activeChannels =
            new AtomicReference<>(List.of());
        model.setActiveChannelSupplier(activeChannels::get);
        assertTrue(model.awaitIdle(2, TimeUnit.SECONDS));

        CountDownLatch projectionBlocked = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        AtomicBoolean blockOnce = new AtomicBoolean();
        model.addActivityListener(event -> {
            if(!event.snapshot().rows().isEmpty() && blockOnce.compareAndSet(false, true))
            {
                projectionBlocked.countDown();

                try
                {
                    releaseProjection.await(5, TimeUnit.SECONDS);
                }
                catch(InterruptedException interruptedException)
                {
                    Thread.currentThread().interrupt();
                }
            }
        });

        Channel blocker = channel();
        model.channelStarted(blocker, List.of(new ChannelMetadata(aliasModel, 1)), new Object());
        assertTrue(projectionBlocked.await(2, TimeUnit.SECONDS));
        Channel target = channel();
        Object targetIncarnation = new Object();
        List<ChannelMetadata> targetMetadata = List.of(new ChannelMetadata(aliasModel, 1));
        CountDownLatch targetPublished = new CountDownLatch(1);
        String targetConfigurationId = target.getConfigurationId();
        model.addActivityListener(event -> {
            if(event.snapshot().rows().stream()
                .anyMatch(row -> targetConfigurationId.equals(row.configurationId())))
            {
                targetPublished.countDown();
            }
        });

        try
        {
            for(int index = 0; index < model.getRegularIngressCapacity(); index++)
            {
                model.channelConfigurationChanged(target);
            }

            model.channelStarted(channel(), List.of(), new Object());
            model.channelStarted(channel(), List.of(), new Object());
            activeChannels.set(List.of(new ChannelActivityModel.ActiveChannel(target, targetMetadata,
                targetIncarnation)));
            model.channelStarted(target, targetMetadata, targetIncarnation);
            assertTrue(model.getDroppedLifecycleCount() > 0);
        }
        finally
        {
            releaseProjection.countDown();
        }

        assertTrue(targetPublished.await(5, TimeUnit.SECONDS),
            "authoritative lifecycle reconciliation did not publish the target channel");
        assertTrue(model.awaitIdle(5, TimeUnit.SECONDS));
        assertEquals(1, model.getTrackedActiveChannelCount());
        assertEquals(1, model.getSnapshotSet().tables().getFirst().rows().size());
        model.close();
    }

    @Test
    void lifecycleCloseUsesReservedCapacityAfterRegularIngressSaturates() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        ChannelActivityModel model = new ChannelActivityModel(aliasModel, new NowPlayingPreference(type -> {}),
            8, 2);
        Channel parent = trunkedDmrChannel();
        Object parentIncarnation = new Object();
        AtomicReference<List<ChannelActivityModel.ActiveChannel>> activeChannels = new AtomicReference<>(List.of(
            new ChannelActivityModel.ActiveChannel(parent, List.of(), parentIncarnation)));
        model.setActiveChannelSupplier(activeChannels::get);
        CountDownLatch projectionBlocked = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        AtomicBoolean blockOnce = new AtomicBoolean();
        model.addActivityListener(event -> {
            if(!"conventional".equals(event.snapshot().tableId()) && !event.snapshot().rows().isEmpty() &&
                blockOnce.compareAndSet(false, true))
            {
                projectionBlocked.countDown();

                try
                {
                    releaseProjection.await(5, TimeUnit.SECONDS);
                }
                catch(InterruptedException interruptedException)
                {
                    Thread.currentThread().interrupt();
                }
            }
        });
        assertTrue(projectionBlocked.await(2, TimeUnit.SECONDS));
        ChannelActivityTableState trunked = model.getTables().stream()
            .filter(table -> table.getOwnerChannel() == parent).findFirst().orElseThrow();

        try
        {
            for(int index = 0; index < model.getRegularIngressCapacity() * 2; index++)
            {
                model.channelConfigurationChanged(parent);
            }

            assertTrue(model.getDroppedIngressCount() > 0);
            activeChannels.set(List.of());

            for(int index = 0; index < 4; index++)
            {
                model.channelStarted(channel(), List.of(), new Object());
            }

            model.channelStopped(parent);
            assertTrue(model.getDroppedLifecycleCount() > 0);
            model.close(trunked);
        }
        finally
        {
            releaseProjection.countDown();
        }

        assertTrue(model.awaitIdle(5, TimeUnit.SECONDS));
        assertEquals(1, model.getTables().size());
        assertEquals(0, model.getTrackedActiveChannelCount());
        assertFalse(model.getSnapshotSet().tables().stream()
            .anyMatch(snapshot -> trunked.getTableId().equals(snapshot.tableId())));
        model.close();
    }

    @Test
    void workerOwnsStateForModelLifetimeAndCloseStopsIt() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        ChannelActivityModel model = model(aliasModel);
        Channel channel = channel();
        ChannelMetadata metadata = new ChannelMetadata(aliasModel, 1);
        model.channelStarted(channel, List.of(metadata));
        assertTrue(model.awaitIdle(2, TimeUnit.SECONDS));
        assertEquals(1, model.getSnapshotSet().tables().getFirst().rows().size());
        assertTrue(model.isWorkerAlive());

        model.close();
        assertFalse(model.isWorkerAlive());
    }

    @Test
    void publishedSnapshotsAreDetachedFromWorkerState()
    {
        ChannelActivityTableState state = new ChannelActivityTableState("Conventional", null, false, null);
        ChannelActivityRow row = state.getOrCreate("row", null, ChannelActivityRow.Role.CONVENTIONAL,
            155_000_000L, null);
        state.refresh(row);
        ChannelActivitySnapshot before = state.getLatestSnapshot();
        row.setCallsign("WPFF205");

        assertEquals(null, before.rows().getFirst().callsign());
        state.refresh(row);
        assertEquals("WPFF205", state.getLatestSnapshot().rows().getFirst().callsign());
    }

    private static ChannelActivityModel model(AliasModel aliasModel)
    {
        return new ChannelActivityModel(aliasModel, new NowPlayingPreference(type -> {}));
    }

    private static Channel channel()
    {
        Channel channel = new Channel("Dispatch", Channel.ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigNBFM());
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(155_730_000L);
        channel.setSourceConfiguration(source);
        return channel;
    }

    private static Channel trunkedDmrChannel()
    {
        Channel channel = new Channel("Bus", Channel.ChannelType.STANDARD);
        DecodeConfigDMR configuration = new DecodeConfigDMR();
        configuration.setChannelMode(DMRChannelMode.TRUNKED);
        channel.setDecodeConfiguration(configuration);
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(451_000_000L);
        channel.setSourceConfiguration(source);
        return channel;
    }
}
