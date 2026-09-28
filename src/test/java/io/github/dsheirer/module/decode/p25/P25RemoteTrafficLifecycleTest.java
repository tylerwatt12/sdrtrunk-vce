/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.ChannelProcessingManager.RemoteTrafficOpenResult;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.P25P1NACPreloadDataContent;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.module.decode.p25.phase2.P25P2ScrambleParametersPreloadData;
import io.github.dsheirer.module.decode.p25.reference.VoiceServiceOptions;
import io.github.dsheirer.remote.P25RemotePhase;
import io.github.dsheirer.remote.P25RemoteTrafficOpen;
import io.github.dsheirer.source.config.SourceConfigRemote;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class P25RemoteTrafficLifecycleTest
{
    private static final String SENDER_ID = "c583c158-1581-4f14-90ce-745635c05526";
    private static final String FEED_ID = "f02e5285-33c9-4491-964c-afd88298d8df";

    @Test
    void phase1OpenIsIdempotentAndOnlyExactCloseStopsIt()
    {
        Channel parent = remoteParent(new DecodeConfigP25Phase1());
        P25TrafficChannelManager manager = manager(parent);
        StartRequestSubscriber starts = subscribe(manager);
        List<ChannelEvent> channelEvents = new CopyOnWriteArrayList<>();
        manager.setChannelEventListener(channelEvents::add);
        String streamId = UUID.randomUUID().toString();
        P25RemoteTrafficOpen open = open(parent, streamId, 7L, P25RemotePhase.PHASE_1, 851_012_500L,
            0x491, null, null);

        assertEquals(RemoteTrafficOpenResult.STARTING, manager.acceptRemoteTrafficOpen(open));
        assertEquals(RemoteTrafficOpenResult.ALREADY_OPEN, manager.acceptRemoteTrafficOpen(open));
        assertEquals(RemoteTrafficOpenResult.REJECTED, manager.acceptRemoteTrafficOpen(
            open(parent, streamId, 7L, P25RemotePhase.PHASE_1, 851_018_750L, 0x491, null, null)));
        assertEquals(RemoteTrafficOpenResult.REJECTED, manager.acceptRemoteTrafficOpen(
            open(parent, streamId, 8L, P25RemotePhase.PHASE_1, 851_012_500L, 0x491, null, null)));
        assertEquals(1, starts.requests.size());
        ChannelStartProcessingRequest request = starts.requests.getFirst();
        assertEquals(open, request.getRemoteTrafficOpen());
        SourceConfigRemote source = assertInstanceOf(SourceConfigRemote.class,
            request.getChannel().getSourceConfiguration());
        assertEquals(SENDER_ID, source.getSenderId());
        assertEquals(FEED_ID, source.getFeedId());
        assertEquals(851_012_500L, source.getFrequency());
        assertTrue(request.getPreloadDataContents().stream()
            .filter(P25P1NACPreloadDataContent.class::isInstance)
            .map(P25P1NACPreloadDataContent.class::cast)
            .anyMatch(preload -> preload.getNAC() == 0x491));

        assertFalse(manager.acceptRemoteTrafficClose(streamId, 6L));
        assertTrue(manager.acceptRemoteTrafficClose(streamId, 7L));
        assertEquals(1, channelEvents.size());
        assertEquals(ChannelEvent.Event.REQUEST_DISABLE, channelEvents.getFirst().getEvent());

        manager.getChannelEventListener().receive(
            new ChannelEvent(request.getChannel(), ChannelEvent.Event.NOTIFICATION_PROCESSING_STOP));
        P25RemoteTrafficOpen replacement =
            open(parent, streamId, 8L, P25RemotePhase.PHASE_1, 851_018_750L, 0x491, null, null);
        assertEquals(RemoteTrafficOpenResult.STARTING, manager.acceptRemoteTrafficOpen(replacement));
        assertFalse(manager.acceptRemoteTrafficClose(streamId, 7L));
        manager.getChannelEventListener().receive(new ChannelEvent(starts.requests.get(1).getChannel(),
            ChannelEvent.Event.NOTIFICATION_PROCESSING_STOP));
        assertEquals(RemoteTrafficOpenResult.REJECTED, manager.acceptRemoteTrafficOpen(open));
    }

    @Test
    void phase2OpenPreloadsScrambleParameters()
    {
        DecodeConfigP25Phase2 config = new DecodeConfigP25Phase2();
        config.setTrafficChannelPoolSize(1);
        Channel parent = remoteParent(config);
        P25TrafficChannelManager manager = manager(parent);
        StartRequestSubscriber starts = subscribe(manager);

        assertEquals(RemoteTrafficOpenResult.STARTING, manager.acceptRemoteTrafficOpen(open(parent,
            UUID.randomUUID().toString(), 1L, P25RemotePhase.PHASE_2, 851_012_500L, 0x491, 0xABCDE, 0x123)));
        ChannelStartProcessingRequest request = starts.requests.getFirst();
        assertInstanceOf(DecodeConfigP25Phase2.class, request.getChannel().getDecodeConfiguration());
        assertTrue(request.getPreloadDataContents().stream()
            .anyMatch(P25P2ScrambleParametersPreloadData.class::isInstance));
    }

    @Test
    void remoteControlGrantsTrackActivityButCannotAllocateHostTraffic()
    {
        DecodeConfigP25Phase1 config = new DecodeConfigP25Phase1();
        config.setTrafficChannelPoolSize(1);
        Channel parent = remoteParent(config);
        P25TrafficChannelManager manager = manager(parent);
        P25FrequencyBand band = new P25FrequencyBand(0, 851_006_250L, -45_000_000L, 6_250L, 12_500, 1);
        manager.processFrequencyBand(band);
        manager.processFrequencyBand(band);
        StartRequestSubscriber starts = subscribe(manager);
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(1201));

        manager.processP1ControlDirectedChannelGrant(APCO25Channel.create(0, 1),
            VoiceServiceOptions.createUnencrypted(), identifiers, Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT, 1_000L);
        assertTrue(starts.requests.isEmpty(), "remote grants must not independently start a host traffic chain");

        assertEquals(RemoteTrafficOpenResult.STARTING, manager.acceptRemoteTrafficOpen(open(parent,
            UUID.randomUUID().toString(), 1L, P25RemotePhase.PHASE_1, 851_012_500L, 0x491, null, null)));
        assertEquals(1, starts.requests.size(), "the sender-confirmed OPEN must still have the full pool available");
    }

    private static Channel remoteParent(Object decodeConfiguration)
    {
        Channel parent = new Channel("Remote", Channel.ChannelType.STANDARD);

        if(decodeConfiguration instanceof DecodeConfigP25Phase1 phase1)
        {
            phase1.setTrafficChannelPoolSize(1);
            parent.setDecodeConfiguration(phase1);
        }
        else
        {
            parent.setDecodeConfiguration((DecodeConfigP25Phase2)decodeConfiguration);
        }

        SourceConfigRemote source = new SourceConfigRemote();
        source.setSenderId(SENDER_ID);
        source.setFeedId(FEED_ID);
        source.setFrequency(851_006_250L);
        parent.setSourceConfiguration(source);
        return parent;
    }

    private static P25TrafficChannelManager manager(Channel parent)
    {
        return new P25TrafficChannelManager(parent);
    }

    private static StartRequestSubscriber subscribe(P25TrafficChannelManager manager)
    {
        StartRequestSubscriber subscriber = new StartRequestSubscriber();
        EventBus eventBus = new EventBus();
        eventBus.register(subscriber);
        manager.setInterModuleEventBus(eventBus);
        return subscriber;
    }

    private static P25RemoteTrafficOpen open(Channel parent, String streamId, long generation, P25RemotePhase phase,
                                              long frequency, Integer nac, Integer wacn, Integer systemId)
    {
        return new P25RemoteTrafficOpen(parent.getConfigurationId(), streamId, FEED_ID, false, generation, phase,
            frequency, 1_000L, nac, wacn, systemId);
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
}
