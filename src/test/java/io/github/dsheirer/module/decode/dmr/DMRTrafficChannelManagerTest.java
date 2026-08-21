/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.dmr;

import com.google.common.eventbus.Subscribe;
import com.google.common.eventbus.EventBus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.dmr.event.DMRDecodeEvent;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.Opcode;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.traffic.TrunkedCallAttributionEvent;
import io.github.dsheirer.module.decode.traffic.TrunkedCallStartEvent;
import io.github.dsheirer.module.decode.traffic.TrunkedTalkerAliasEvent;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class DMRTrafficChannelManagerTest
{
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

    private static DMRTier3Channel channel(int number, int timeslot, long frequency)
    {
        DMRTier3Channel channel = new DMRTier3Channel(number, timeslot);
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(number);
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
}
