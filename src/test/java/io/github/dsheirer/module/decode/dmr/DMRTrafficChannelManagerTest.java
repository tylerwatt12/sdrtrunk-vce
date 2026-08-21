/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.dmr;

import com.google.common.eventbus.Subscribe;
import com.google.common.eventbus.EventBus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityModel;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityRow;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.alias.DmrTalkerAliasIdentifier;
import io.github.dsheirer.module.decode.dmr.channel.DMRTier3Channel;
import io.github.dsheirer.module.decode.dmr.channel.DmrRestLsn;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.dmr.event.DMRDecodeEvent;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.Opcode;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.traffic.TrunkedCallAttributionEvent;
import io.github.dsheirer.module.decode.traffic.TrunkedCallStartEvent;
import io.github.dsheirer.module.decode.traffic.TrunkedTalkerAliasEvent;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class DMRTrafficChannelManagerTest
{
    @Test
    void restHandoffCoalescesUntilTheOwnedRequestCompletes()
    {
        long currentFrequency = 451_000_000L;
        long firstRestFrequency = 452_000_000L;
        long secondRestFrequency = 453_000_000L;
        Channel parent = new Channel("Capacity Plus", Channel.ChannelType.STANDARD);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        config.setTrafficChannelPoolSize(1);
        parent.setDecodeConfiguration(config);
        SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
        source.setFrequencies(List.of(currentFrequency, firstRestFrequency, secondRestFrequency));
        source.setPreferredFrequency(currentFrequency);
        parent.setSourceConfiguration(source);
        DMRTrafficChannelManager manager = new DMRTrafficChannelManager(parent);
        RestHandoffSubscriber subscriber = new RestHandoffSubscriber();
        StartRequestSubscriber startSubscriber = new StartRequestSubscriber();
        EventBus eventBus = new EventBus();
        eventBus.register(subscriber);
        eventBus.register(startSubscriber);
        manager.setInterModuleEventBus(eventBus);
        manager.setCurrentControlFrequency(currentFrequency, parent);
        DmrRestLsn firstRest = restChannel(3, firstRestFrequency);
        DmrRestLsn secondRest = restChannel(5, secondRestFrequency);

        DMRNetworkConfigurationSnapshot snapshot = new DMRNetworkConfigurationSnapshot("DMR", "CAPACITY_PLUS",
            null, 7, "Motorola Capacity+", null, null, "Control", null, null, List.of(), List.of());
        manager.requestRestChannelHandoff(parent, currentFrequency, firstRest, snapshot);
        manager.requestRestChannelHandoff(parent, currentFrequency, firstRest);
        manager.requestRestChannelHandoff(parent, currentFrequency, secondRest);

        assertEquals(2, subscriber.requests.size());
        DMRRestChannelHandoffRequest firstRequest = subscriber.requests.getFirst();
        DMRRestChannelHandoffRequest secondRequest = subscriber.requests.getLast();
        assertFalse(manager.isPendingRestHandoff(firstRequest));
        assertTrue(manager.isPendingRestHandoff(secondRequest));
        assertNull(manager.prepareRestChannelHandoff(firstRequest));

        //The queued control move already owns the single pooled channel, so a grant on another RF cannot steal it.
        manager.processChannelGrant(channel(9, 1, 454_000_000L), identifiers(101, 91),
            Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 1_000L, false);
        assertTrue(startSubscriber.requests.isEmpty());
        DMRTrafficChannelManager.PreparedRestChannelHandoff prepared =
            manager.prepareRestChannelHandoff(secondRequest);
        assertNotNull(prepared);
        DMRRestChannelNetworkConfigurationPreloadData networkPreload = prepared.startRequest()
            .getPreloadDataContents().stream()
            .filter(DMRRestChannelNetworkConfigurationPreloadData.class::isInstance)
            .map(DMRRestChannelNetworkConfigurationPreloadData.class::cast)
            .findFirst().orElseThrow();
        assertSame(snapshot, networkPreload.getSnapshot());
        assertSame(manager, prepared.startRequest().getTrafficChannelManager());
        assertEquals(secondRest.toString(), prepared.startRequest().getChannelDescriptor().toString());
        assertTrue(manager.commitRestChannelHandoff(prepared));

        //A failed conversion releases exactly this reservation and restores the prior preferred frequency.
        manager.releaseRestChannelReservation(prepared);
        assertEquals(currentFrequency, source.getPreferredFrequency());
        manager.processChannelGrant(channel(1, 1, currentFrequency), identifiers(102, 92),
            Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 2_000L, false);
        assertTrue(startSubscriber.requests.isEmpty(),
            "failed conversion did not restore the live control-frequency allocation");
        manager.completeRestHandoff(secondRequest);
        assertFalse(manager.isPendingRestHandoff(secondRequest));

        manager.requestRestChannelHandoff(parent, currentFrequency, firstRest);
        assertEquals(3, subscriber.requests.size());
        assertEquals(firstRestFrequency, subscriber.requests.getLast().restDownlinkFrequency());
    }

    @Test
    void busyNewerRestTargetInvalidatesOlderQueuedTargetAndCanBeRetried()
    {
        long currentFrequency = 451_000_000L;
        long olderRestFrequency = 452_000_000L;
        long newerRestFrequency = 453_000_000L;
        Channel parent = new Channel("Capacity Plus", Channel.ChannelType.STANDARD);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        config.setTrafficChannelPoolSize(2);
        parent.setDecodeConfiguration(config);
        SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
        source.setFrequencies(List.of(currentFrequency, olderRestFrequency, newerRestFrequency));
        source.setPreferredFrequency(currentFrequency);
        parent.setSourceConfiguration(source);
        DMRTrafficChannelManager manager = new DMRTrafficChannelManager(parent);
        RestHandoffSubscriber handoffSubscriber = new RestHandoffSubscriber();
        StartRequestSubscriber startSubscriber = new StartRequestSubscriber();
        EventBus eventBus = new EventBus();
        eventBus.register(handoffSubscriber);
        eventBus.register(startSubscriber);
        manager.setInterModuleEventBus(eventBus);
        manager.setCurrentControlFrequency(currentFrequency, parent);

        //Allocate the newer target as live traffic before the older target is queued.
        manager.processChannelGrant(channel(5, 1, newerRestFrequency), identifiers(109, 99),
            Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 9_000L, false);
        assertEquals(1, startSubscriber.requests.size());
        Channel allocatedNewerTarget = startSubscriber.requests.getFirst().getChannel();
        assertSame(allocatedNewerTarget, manager.getAllocatedChannel(newerRestFrequency));

        manager.requestRestChannelHandoff(parent, currentFrequency, restChannel(3, olderRestFrequency));
        assertEquals(1, handoffSubscriber.requests.size());
        DMRRestChannelHandoffRequest olderRequest = handoffSubscriber.requests.getFirst();
        assertTrue(manager.isPendingRestHandoff(olderRequest));

        //The newer report is authoritative even though its RF cannot yet be reserved.
        manager.requestRestChannelHandoff(parent, currentFrequency, restChannel(5, newerRestFrequency));
        assertEquals(1, handoffSubscriber.requests.size());
        assertFalse(manager.isPendingRestHandoff(olderRequest));
        assertNull(manager.prepareRestChannelHandoff(olderRequest),
            "the lifecycle worker could still claim the stale older target");
        assertNull(manager.getAllocatedChannel(olderRestFrequency),
            "invalidating the older target did not release its exact reservation");

        manager.processTrafficChannelTeardown(allocatedNewerTarget);
        manager.requestRestChannelHandoff(parent, currentFrequency, restChannel(5, newerRestFrequency));
        assertEquals(2, handoffSubscriber.requests.size());
        DMRRestChannelHandoffRequest retriedRequest = handoffSubscriber.requests.getLast();
        assertEquals(newerRestFrequency, retriedRequest.restDownlinkFrequency());
        assertTrue(manager.isPendingRestHandoff(retriedRequest));
        DMRTrafficChannelManager.PreparedRestChannelHandoff prepared =
            manager.prepareRestChannelHandoff(retriedRequest);
        assertNotNull(prepared, "a later report could not retry the formerly busy target");
        manager.releaseRestChannelReservation(prepared);
        manager.completeRestHandoff(retriedRequest);
    }

    @Test
    void restReservationAfterGrantLookupCannotBeOverwrittenAndPolledChannelIsReusable()
    {
        long currentFrequency = 451_000_000L;
        long restFrequency = 452_000_000L;
        long firstTrafficFrequency = 453_000_000L;
        long secondTrafficFrequency = 454_000_000L;
        Channel parent = new Channel("Capacity Plus", Channel.ChannelType.STANDARD);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        config.setTrafficChannelPoolSize(2);
        parent.setDecodeConfiguration(config);
        SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
        source.setFrequencies(List.of(currentFrequency, restFrequency, firstTrafficFrequency,
            secondTrafficFrequency));
        source.setPreferredFrequency(currentFrequency);
        parent.setSourceConfiguration(source);
        DMRTrafficChannelManager manager = new DMRTrafficChannelManager(parent);
        RestHandoffSubscriber handoffSubscriber = new RestHandoffSubscriber();
        StartRequestSubscriber startSubscriber = new StartRequestSubscriber();
        EventBus eventBus = new EventBus();
        eventBus.register(handoffSubscriber);
        eventBus.register(startSubscriber);
        manager.setInterModuleEventBus(eventBus);
        manager.setCurrentControlFrequency(currentFrequency, parent);
        DmrRestLsn restChannel = restChannel(3, restFrequency);

        //Force the rest request into the exact gap after the grant's initial map lookup and before its allocation CAS.
        manager.processChannelGrant(channel(3, 1, restFrequency), identifiers(101, 91),
            Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 1_000L, false,
            () -> manager.requestRestChannelHandoff(parent, currentFrequency, restChannel));

        assertEquals(1, handoffSubscriber.requests.size());
        assertTrue(startSubscriber.requests.isEmpty(),
            "grant overwrote the rest-frequency reservation after its initial lookup");
        DMRRestChannelHandoffRequest request = handoffSubscriber.requests.getFirst();
        assertTrue(manager.isPendingRestHandoff(request));

        manager.completeRestHandoff(request);
        assertNull(manager.getAllocatedChannel(restFrequency));

        //Both pooled channels remain reusable: one was owned by the cancelled handoff and the other lost the grant CAS.
        manager.processChannelGrant(channel(5, 1, firstTrafficFrequency), identifiers(102, 92),
            Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 2_000L, false);
        manager.processChannelGrant(channel(7, 1, secondTrafficFrequency), identifiers(103, 93),
            Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 3_000L, false);

        assertEquals(2, startSubscriber.requests.size());
        assertNotSame(startSubscriber.requests.get(0).getChannel(), startSubscriber.requests.get(1).getChannel());
    }

    @Test
    void failedConversionAtomicallyRestoresCurrentFrequencyParentAllocation()
    {
        long currentFrequency = 451_000_000L;
        long restFrequency = 452_000_000L;
        Channel parent = new Channel("Capacity Plus", Channel.ChannelType.STANDARD);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        config.setTrafficChannelPoolSize(1);
        parent.setDecodeConfiguration(config);
        SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
        source.setFrequencies(List.of(currentFrequency, restFrequency));
        source.setPreferredFrequency(currentFrequency);
        parent.setSourceConfiguration(source);
        DMRTrafficChannelManager manager = new DMRTrafficChannelManager(parent);
        RestHandoffSubscriber handoffSubscriber = new RestHandoffSubscriber();
        StartRequestSubscriber startSubscriber = new StartRequestSubscriber();
        EventBus eventBus = new EventBus();
        eventBus.register(handoffSubscriber);
        eventBus.register(startSubscriber);
        manager.setInterModuleEventBus(eventBus);
        manager.setCurrentControlFrequency(currentFrequency, parent);
        manager.requestRestChannelHandoff(parent, currentFrequency, restChannel(3, restFrequency));
        DMRRestChannelHandoffRequest request = handoffSubscriber.requests.getFirst();
        DMRTrafficChannelManager.PreparedRestChannelHandoff prepared =
            manager.prepareRestChannelHandoff(request);

        assertNotNull(prepared);
        assertTrue(manager.commitRestChannelHandoff(prepared));
        assertSame(prepared.trafficChannel(), manager.getAllocatedChannel(currentFrequency));

        manager.releaseRestChannelReservation(prepared);

        assertSame(parent, manager.getAllocatedChannel(currentFrequency));
        assertEquals(currentFrequency, source.getPreferredFrequency());
        manager.processChannelGrant(channel(1, 1, currentFrequency), identifiers(104, 94),
            Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 4_000L, false);
        assertTrue(startSubscriber.requests.isEmpty(),
            "rollback exposed the current control frequency for traffic allocation");
        manager.completeRestHandoff(request);
    }

    @Test
    void sourceRotationOntoReservedTargetPreservesTheLiveControlAllocation()
    {
        long currentFrequency = 451_000_000L;
        long restFrequency = 452_000_000L;
        long laterRestFrequency = 453_000_000L;
        Channel parent = new Channel("Capacity Plus", Channel.ChannelType.STANDARD);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        config.setTrafficChannelPoolSize(1);
        parent.setDecodeConfiguration(config);
        SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
        source.setFrequencies(List.of(currentFrequency, restFrequency, laterRestFrequency));
        source.setPreferredFrequency(currentFrequency);
        parent.setSourceConfiguration(source);
        DMRTrafficChannelManager manager = new DMRTrafficChannelManager(parent);
        RestHandoffSubscriber subscriber = new RestHandoffSubscriber();
        StartRequestSubscriber startSubscriber = new StartRequestSubscriber();
        EventBus eventBus = new EventBus();
        eventBus.register(subscriber);
        eventBus.register(startSubscriber);
        manager.setInterModuleEventBus(eventBus);
        manager.setCurrentControlFrequency(currentFrequency, parent);
        manager.requestRestChannelHandoff(parent, currentFrequency, restChannel(3, restFrequency));
        DMRRestChannelHandoffRequest request = subscriber.requests.getFirst();

        //The ordinary source rotation wins before the worker claims the move and turns the reservation into the live
        //control allocation.  Rejecting the now-stale handoff must remove only its distinct reservation token.
        manager.setCurrentControlFrequency(restFrequency, parent);
        assertNull(manager.prepareRestChannelHandoff(request));
        manager.completeRestHandoff(request);

        assertSame(parent, manager.getAllocatedChannel(restFrequency));
        assertNull(manager.getAllocatedChannel(currentFrequency));
        manager.processChannelGrant(channel(3, 1, restFrequency), identifiers(105, 95),
            Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 5_000L, false);
        assertTrue(startSubscriber.requests.isEmpty(),
            "stale handoff cleanup removed the live rotated control allocation");

        manager.requestRestChannelHandoff(parent, restFrequency, restChannel(5, laterRestFrequency));
        assertEquals(2, subscriber.requests.size(), "the stale request did not return its pooled reservation");
    }

    @Test
    void rollbackDoesNotRestoreAControlFrequencyThatRotatedAfterCommit()
    {
        long currentFrequency = 451_000_000L;
        long restFrequency = 452_000_000L;
        Channel parent = new Channel("Capacity Plus", Channel.ChannelType.STANDARD);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        config.setTrafficChannelPoolSize(1);
        parent.setDecodeConfiguration(config);
        SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
        source.setFrequencies(List.of(currentFrequency, restFrequency));
        source.setPreferredFrequency(currentFrequency);
        parent.setSourceConfiguration(source);
        DMRTrafficChannelManager manager = new DMRTrafficChannelManager(parent);
        RestHandoffSubscriber subscriber = new RestHandoffSubscriber();
        EventBus eventBus = new EventBus();
        eventBus.register(subscriber);
        manager.setInterModuleEventBus(eventBus);
        manager.setCurrentControlFrequency(currentFrequency, parent);
        manager.requestRestChannelHandoff(parent, currentFrequency, restChannel(3, restFrequency));
        DMRRestChannelHandoffRequest request = subscriber.requests.getFirst();
        DMRTrafficChannelManager.PreparedRestChannelHandoff prepared =
            manager.prepareRestChannelHandoff(request);

        assertNotNull(prepared);
        assertTrue(manager.commitRestChannelHandoff(prepared));
        manager.setCurrentControlFrequency(restFrequency, parent);
        manager.releaseRestChannelReservation(prepared);
        manager.completeRestHandoff(request);

        assertNull(manager.getAllocatedChannel(currentFrequency));
        assertSame(parent, manager.getAllocatedChannel(restFrequency));
        assertEquals(restFrequency, source.getPreferredFrequency());
    }

    @Test
    void rollbackInterleavedAfterRotationReadLeavesNoStaleParentAllocation() throws Exception
    {
        long currentFrequency = 451_000_000L;
        long restFrequency = 452_000_000L;
        Channel parent = new Channel("Capacity Plus", Channel.ChannelType.STANDARD);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        config.setTrafficChannelPoolSize(1);
        parent.setDecodeConfiguration(config);
        SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
        source.setFrequencies(List.of(currentFrequency, restFrequency));
        source.setPreferredFrequency(currentFrequency);
        parent.setSourceConfiguration(source);
        DMRTrafficChannelManager manager = new DMRTrafficChannelManager(parent);
        RestHandoffSubscriber subscriber = new RestHandoffSubscriber();
        EventBus eventBus = new EventBus();
        eventBus.register(subscriber);
        manager.setInterModuleEventBus(eventBus);
        manager.setCurrentControlFrequency(currentFrequency, parent);
        manager.requestRestChannelHandoff(parent, currentFrequency, restChannel(3, restFrequency));
        DMRRestChannelHandoffRequest request = subscriber.requests.getFirst();
        DMRTrafficChannelManager.PreparedRestChannelHandoff prepared =
            manager.prepareRestChannelHandoff(request);
        assertNotNull(prepared);
        assertTrue(manager.commitRestChannelHandoff(prepared));
        CountDownLatch rotationReadOldAllocation = new CountDownLatch(1);
        CountDownLatch finishRotation = new CountDownLatch(1);
        manager.setControlFrequencyUpdateInterleaveForTest(() -> {
            rotationReadOldAllocation.countDown();
            await(finishRotation);
        });
        Thread rotation = new Thread(() -> manager.setCurrentControlFrequency(restFrequency, parent),
            "dmr-control-rotation-test");

        try
        {
            manager.releaseRestChannelReservation(prepared, () -> {
                rotation.start();
                await(rotationReadOldAllocation);
            });
        }
        finally
        {
            finishRotation.countDown();
        }

        rotation.join(2_000);
        assertFalse(rotation.isAlive());
        manager.completeRestHandoff(request);
        assertNull(manager.getAllocatedChannel(currentFrequency));
        assertSame(parent, manager.getAllocatedChannel(restFrequency));
        assertEquals(restFrequency, source.getPreferredFrequency());
    }

    @Test
    void controlUpdateAfterGrantClaimUnwindsTheClaimAndReusesThePooledChannel()
    {
        long currentFrequency = 451_000_000L;
        long claimedFrequency = 452_000_000L;
        long laterTrafficFrequency = 453_000_000L;
        Channel parent = trunkedParent(currentFrequency, claimedFrequency, laterTrafficFrequency);
        DMRTrafficChannelManager manager = new DMRTrafficChannelManager(parent);
        StartRequestSubscriber startSubscriber = new StartRequestSubscriber();
        EventBus eventBus = new EventBus();
        eventBus.register(startSubscriber);
        manager.setInterModuleEventBus(eventBus);
        manager.setCurrentControlFrequency(currentFrequency, parent);

        manager.processChannelGrant(channel(3, 1, claimedFrequency), identifiers(105, 95),
            Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 5_000L, false, null,
            () -> manager.setCurrentControlFrequency(claimedFrequency, parent));

        assertTrue(startSubscriber.requests.isEmpty());
        assertSame(parent, manager.getAllocatedChannel(claimedFrequency));
        manager.processChannelGrant(channel(5, 1, laterTrafficFrequency), identifiers(106, 96),
            Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 6_000L, false);
        assertEquals(1, startSubscriber.requests.size(), "the cancelled grant claim leaked its pooled channel");
    }

    @Test
    void controlUpdateDoesNotOverwriteACommittedTrafficAllocation()
    {
        long currentFrequency = 451_000_000L;
        long trafficFrequency = 452_000_000L;
        long laterTrafficFrequency = 453_000_000L;
        Channel parent = trunkedParent(currentFrequency, trafficFrequency, laterTrafficFrequency);
        DMRTrafficChannelManager manager = new DMRTrafficChannelManager(parent);
        StartRequestSubscriber startSubscriber = new StartRequestSubscriber();
        EventBus eventBus = new EventBus();
        eventBus.register(startSubscriber);
        manager.setInterModuleEventBus(eventBus);
        manager.setCurrentControlFrequency(currentFrequency, parent);

        manager.processChannelGrant(channel(3, 1, trafficFrequency), identifiers(107, 97),
            Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 7_000L, false);
        assertEquals(1, startSubscriber.requests.size());
        Channel trafficChannel = startSubscriber.requests.getFirst().getChannel();
        assertSame(trafficChannel, manager.getAllocatedChannel(trafficFrequency));

        manager.setCurrentControlFrequency(trafficFrequency, parent);
        assertSame(trafficChannel, manager.getAllocatedChannel(trafficFrequency),
            "control update erased the still-running traffic child from manager accounting");

        manager.processTrafficChannelTeardown(trafficChannel);
        assertSame(parent, manager.getAllocatedChannel(trafficFrequency));
        manager.processChannelGrant(channel(5, 1, laterTrafficFrequency), identifiers(108, 98),
            Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 8_000L, false);
        assertEquals(2, startSubscriber.requests.size(), "traffic teardown did not return the channel to its pool");
    }

    @Test
    void decoderOwnedTrafficEventIsNotRebroadcastThroughTheManager()
    {
        Channel parent = new Channel("DMR Site", Channel.ChannelType.STANDARD);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        parent.setDecodeConfiguration(config);
        DMRTrafficChannelManager manager = new DMRTrafficChannelManager(parent);
        AtomicInteger managerDeliveries = new AtomicInteger();
        manager.addDecodeEventListener(event -> managerDeliveries.incrementAndGet());
        DecodeEvent event = DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L)
            .channel(channel(12, 1, 451_012_500L))
            .identifiers(identifiers(101, 91))
            .timeslot(1)
            .build();

        manager.receiveTrafficChannelEvent(event);
        assertEquals(0, managerDeliveries.get());

        //Manager-owned grant/rejection events retain their explicit parent delivery route.
        manager.broadcast(event);
        assertEquals(1, managerDeliveries.get());
    }

    @Test
    void trafficStartsCarryRequestScopedGrantEvents()
    {
        Channel parent = new Channel("DMR Site", Channel.ChannelType.STANDARD);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        config.setTrafficChannelPoolSize(2);
        parent.setDecodeConfiguration(config);
        DMRTrafficChannelManager manager = new DMRTrafficChannelManager(parent);
        StartRequestSubscriber startSubscriber = new StartRequestSubscriber();
        EventBus eventBus = new EventBus();
        eventBus.register(startSubscriber);
        manager.setInterModuleEventBus(eventBus);
        List<DecodeEvent> grantEvents = new CopyOnWriteArrayList<>();
        manager.addDecodeEventListener(event -> grantEvents.add((DecodeEvent)event));

        manager.processChannelGrant(channel(12, 1, 451_012_500L), identifiers(101, 91),
            Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 1_000L, false);
        manager.processChannelGrant(channel(13, 2, 451_025_000L), identifiers(102, 92),
            Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 2_000L, false);

        assertEquals(2, startSubscriber.requests.size());
        assertEquals(2, grantEvents.size());
        ChannelStartProcessingRequest firstRequest = startSubscriber.requests.get(0);
        ChannelStartProcessingRequest secondRequest = startSubscriber.requests.get(1);
        DMRChannelGrantPreloadData firstPreload = grantPreload(firstRequest);
        DMRChannelGrantPreloadData secondPreload = grantPreload(secondRequest);

        assertSame(grantEvents.get(0), firstPreload.getChannelGrantEvent());
        assertSame(grantEvents.get(1), secondPreload.getChannelGrantEvent());
        assertNotSame(firstPreload.getChannelGrantEvent(), secondPreload.getChannelGrantEvent());
        assertSame(config, firstRequest.getChannel().getDecodeConfiguration());
        assertSame(config, secondRequest.getChannel().getDecodeConfiguration());
    }

    @Test
    void publishesOneCallStartPerTargetWithoutTrafficTuner()
    {
        Channel parent = new Channel("DMR Site", Channel.ChannelType.STANDARD);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        config.setTrafficChannelPoolSize(0);
        parent.setDecodeConfiguration(config);
        DMRTrafficChannelManager manager = new DMRTrafficChannelManager(parent);
        manager.setInterModuleEventBus(new EventBus());
        DMRTier3Channel channel = new DMRTier3Channel(12, 1);
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(12);
        mapping.setDownlinkFrequency(451_012_500L);
        channel.setTimeslotFrequency(mapping);
        CallStartSubscriber subscriber = new CallStartSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);

        try
        {
            manager.processChannelGrant(channel, identifiers(101, 91),
                Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 1_000L, false);
            manager.processChannelGrant(channel, identifiers(102, 91),
                Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 1_100L, false);
            manager.processChannelGrant(channel, identifiers(102, 92),
                Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 1_200L, true);
            manager.processChannelGrant(channel, identifiers(102, 92),
                Opcode.STANDARD_TALKGROUP_DATA_CHANNEL_GRANT_SINGLE_ITEM, 1_300L, false);
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }

        assertEquals(2, subscriber.events.size());
        assertEquals(91, subscriber.events.get(0).event().getIdentifierCollection().getToIdentifier().getValue());
        assertEquals(92, subscriber.events.get(1).event().getIdentifierCollection().getToIdentifier().getValue());
        assertEquals(1_000L, subscriber.events.get(0).event().getTimeStart());
        assertEquals(1_200L, subscriber.events.get(1).event().getTimeStart());
    }

    @Test
    void trafficUpdatesAttributeLateSourceAndEncryptionWithoutStartingAnotherCall()
    {
        Channel parent = new Channel("DMR Site", Channel.ChannelType.STANDARD);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        config.setTrafficChannelPoolSize(0);
        parent.setDecodeConfiguration(config);
        DMRTrafficChannelManager manager = new DMRTrafficChannelManager(parent);
        manager.setInterModuleEventBus(new EventBus());
        DMRTier3Channel channel = new DMRTier3Channel(12, 1);
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(12);
        mapping.setDownlinkFrequency(451_012_500L);
        channel.setTimeslotFrequency(mapping);
        MutableIdentifierCollection targetOnly = new MutableIdentifierCollection();
        targetOnly.update(DMRTalkgroup.create(91));
        CallStartSubscriber startSubscriber = new CallStartSubscriber();
        AttributionSubscriber attributionSubscriber = new AttributionSubscriber();
        MyEventBus.getGlobalEventBus().register(startSubscriber);
        MyEventBus.getGlobalEventBus().register(attributionSubscriber);

        try
        {
            manager.processChannelGrant(channel, targetOnly,
                Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 1_000L, false);
            DecodeEvent sourceUpdate = DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L)
                .channel(channel)
                .identifiers(identifiers(101, 91))
                .timeslot(1)
                .build();
            sourceUpdate.end(1_100L);
            manager.receiveTrafficChannelEvent(sourceUpdate);
            manager.receiveTrafficChannelEvent(sourceUpdate);

            DecodeEvent encryptedUpdate = DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP_ENCRYPTED, 1_000L)
                .channel(channel)
                .identifiers(identifiers(101, 91))
                .timeslot(1)
                .build();
            encryptedUpdate.end(1_200L);
            manager.receiveTrafficChannelEvent(encryptedUpdate);
            manager.receiveTrafficChannelEvent(encryptedUpdate);
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(attributionSubscriber);
            MyEventBus.getGlobalEventBus().unregister(startSubscriber);
        }

        assertEquals(1, startSubscriber.events.size());
        assertEquals(2, attributionSubscriber.events.size());
        assertTrue(attributionSubscriber.events.get(0).sourceBecameKnown());
        assertEquals(101, attributionSubscriber.events.get(0).identifiers().getFromIdentifier().getValue());
        assertTrue(attributionSubscriber.events.get(1).encryptionBecameKnown());
    }

    @Test
    void publishesOnlyExplicitTalkerAliasWithKnownSource()
    {
        Channel parent = new Channel("DMR Site", Channel.ChannelType.STANDARD);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        parent.setDecodeConfiguration(config);
        DMRTrafficChannelManager manager = new DMRTrafficChannelManager(parent);
        TalkerAliasSubscriber subscriber = new TalkerAliasSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);

        try
        {
            MutableIdentifierCollection identifiers = identifiers(101, 91);
            manager.processTalkerAlias(DmrTalkerAliasIdentifier.create("ENGINE 4"),
                DMRRadio.createFrom(101), identifiers, 2_000L);
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }

        assertEquals(1, subscriber.events.size());
        assertEquals("ENGINE 4", subscriber.events.getFirst().alias().getValue());
        assertEquals(101, subscriber.events.getFirst().radio().getValue());
    }

    @Test
    void publishesResolvedTierThreeGrantToSystemsModel() throws Exception
    {
        ChannelActivityModel activityModel = new ChannelActivityModel(new AliasModel(),
            new NowPlayingPreference(type -> {}));
        Channel parent = new Channel("2.2", Channel.ChannelType.STANDARD);
        parent.setSystem("bus");
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        config.setTrafficChannelPoolSize(0);
        parent.setDecodeConfiguration(config);
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(139_518_750L);
        parent.setSourceConfiguration(source);

        DMRTrafficChannelManager manager = new DMRTrafficChannelManager(parent);
        manager.setInterModuleEventBus(new EventBus());
        manager.setChannelActivityModel(activityModel);
        manager.setCurrentControlFrequency(139_518_750L, parent);

        DMRTier3Channel controlTimeslotCall = new DMRTier3Channel(901, 2);
        TimeslotFrequency controlMapping = new TimeslotFrequency();
        controlMapping.setNumber(901);
        controlMapping.setDownlinkFrequency(139_518_750L);
        controlTimeslotCall.setTimeslotFrequency(controlMapping);

        DMRTier3Channel grant = new DMRTier3Channel(802, 2);
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(802);
        mapping.setDownlinkFrequency(139_068_750L);
        grant.setTimeslotFrequency(mapping);

        manager.processChannelGrant(controlTimeslotCall, new MutableIdentifierCollection(),
            Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 900L, false);
        manager.processChannelGrant(grant, new MutableIdentifierCollection(),
            Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 1_000L, false);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);

        while(System.nanoTime() < deadline)
        {
            if(activityModel.getTables().size() == 2 && activityModel.getTables().get(1).getRows().stream()
                .anyMatch(row -> row.getFrequency() == 139_068_750L && Integer.valueOf(2).equals(row.getTimeslot())) &&
                activityModel.getTables().get(1).getRows().stream()
                    .anyMatch(row -> row.getFrequency() == 139_518_750L && Integer.valueOf(2).equals(row.getTimeslot())))
            {
                break;
            }

            Thread.sleep(5);
        }

        assertEquals(2, activityModel.getTables().size());
        assertEquals("DMR: bus / 2.2", activityModel.getTables().get(1).getTitle());
        assertTrue(activityModel.getTables().get(1).isControlActive());
        assertTrue(activityModel.getTables().get(1).getRows().stream()
            .anyMatch(row -> row.getRole() == ChannelActivityRow.Role.TRAFFIC &&
                row.getFrequency() == 139_068_750L && row.getTimeslot() == 2 && "802".equals(row.getLcn())));
        assertTrue(activityModel.getTables().get(1).getRows().stream()
            .anyMatch(row -> row.getRole() == ChannelActivityRow.Role.TRAFFIC &&
                row.getFrequency() == 139_518_750L && row.getTimeslot() == 2));
    }

    @Test
    void conventionalModeDoesNotAllocateOrPromoteTrunking() throws Exception
    {
        ChannelActivityModel activityModel = new ChannelActivityModel(new AliasModel(),
            new NowPlayingPreference(type -> {}));
        Channel parent = new Channel("Repeater", Channel.ChannelType.STANDARD);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.CONVENTIONAL);
        config.setTrafficChannelPoolSize(20);
        parent.setDecodeConfiguration(config);
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(451_012_500L);
        parent.setSourceConfiguration(source);

        DMRTrafficChannelManager manager = new DMRTrafficChannelManager(parent);
        manager.setInterModuleEventBus(new EventBus());
        manager.setChannelActivityModel(activityModel);
        manager.setCurrentControlFrequency(451_012_500L, parent);

        DMRTier3Channel grant = new DMRTier3Channel(12, 1);
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(12);
        mapping.setDownlinkFrequency(452_012_500L);
        grant.setTimeslotFrequency(mapping);

        manager.processChannelGrant(grant, new MutableIdentifierCollection(),
            Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 1_000L, false);
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals(1, activityModel.getTables().size());
        assertTrue(activityModel.getConventionalTable().getRows().isEmpty());
    }

    private static MutableIdentifierCollection identifiers(int radio, int talkgroup)
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(DMRRadio.createFrom(radio));
        identifiers.update(DMRTalkgroup.create(talkgroup));
        return identifiers;
    }

    private static Channel trunkedParent(long... frequencies)
    {
        Channel parent = new Channel("Capacity Plus", Channel.ChannelType.STANDARD);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        config.setTrafficChannelPoolSize(1);
        parent.setDecodeConfiguration(config);
        SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
        source.setFrequencies(java.util.Arrays.stream(frequencies).boxed().toList());
        source.setPreferredFrequency(frequencies[0]);
        parent.setSourceConfiguration(source);
        return parent;
    }

    private static void await(CountDownLatch latch)
    {
        try
        {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static DMRTier3Channel channel(int number, int timeslot, long frequency)
    {
        DMRTier3Channel channel = new DMRTier3Channel(number, timeslot);
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(number);
        mapping.setDownlinkFrequency(frequency);
        channel.setTimeslotFrequency(mapping);
        return channel;
    }

    private static DmrRestLsn restChannel(int lsn, long frequency)
    {
        DmrRestLsn channel = new DmrRestLsn(lsn);
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(channel.getChannelNumber());
        mapping.setDownlinkFrequency(frequency);
        channel.setTimeslotFrequency(mapping);
        return channel;
    }

    private static DMRChannelGrantPreloadData grantPreload(ChannelStartProcessingRequest request)
    {
        return request.getPreloadDataContents().stream()
            .filter(DMRChannelGrantPreloadData.class::isInstance)
            .map(DMRChannelGrantPreloadData.class::cast)
            .findFirst()
            .orElseThrow();
    }

    private static class CallStartSubscriber
    {
        private final List<TrunkedCallStartEvent> events = new CopyOnWriteArrayList<>();

        @Subscribe
        public void receive(TrunkedCallStartEvent event)
        {
            events.add(event);
        }
    }

    private static class AttributionSubscriber
    {
        private final List<TrunkedCallAttributionEvent> events = new CopyOnWriteArrayList<>();

        @Subscribe
        public void receive(TrunkedCallAttributionEvent event)
        {
            events.add(event);
        }
    }

    private static class TalkerAliasSubscriber
    {
        private final List<TrunkedTalkerAliasEvent> events = new CopyOnWriteArrayList<>();

        @Subscribe
        public void receive(TrunkedTalkerAliasEvent event)
        {
            events.add(event);
        }
    }

    private static class StartRequestSubscriber
    {
        private final List<ChannelStartProcessingRequest> requests = new CopyOnWriteArrayList<>();

        @Subscribe
        public void receive(ChannelStartProcessingRequest request)
        {
            requests.add(request);
        }
    }

    private static class RestHandoffSubscriber
    {
        private final List<DMRRestChannelHandoffRequest> requests = new CopyOnWriteArrayList<>();

        @Subscribe
        public void receive(DMRRestChannelHandoffRequest request)
        {
            requests.add(request);
        }
    }
}
