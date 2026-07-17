/*
 * *****************************************************************************
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.p25;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Wacn;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class P25TrafficChannelManagerTest
{
    @Test
    void clearsFrequencyBandsWhenControlFrequencyChanges() throws Exception
    {
        Channel parentChannel = new Channel("Control");
        TestP25TrafficChannelManager manager = new TestP25TrafficChannelManager(parentChannel);
        manager.processFrequencyBand(band(0, 851_006_250L, 6250L, 1));

        assertEquals(1, frequencyBands(manager).size());

        manager.changeControlFrequency(851_012_500L, 852_012_500L, parentChannel);

        assertTrue(frequencyBands(manager).isEmpty());
    }

    @Test
    void publishesTalkerAliasWithoutActiveCallTracker()
    {
        Channel parentChannel = new Channel("Control");
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parentChannel);
        RadioIdentifier radio = APCO25RadioIdentifier.createFrom(1811524);
        P25TalkerAliasIdentifier alias = P25TalkerAliasIdentifier.create("CAR 201");
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Wacn.create(0xBEE00));
        identifiers.update(APCO25System.create(0x348));
        TalkerAliasSubscriber subscriber = new TalkerAliasSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);

        try
        {
            manager.processP1TalkerAlias(851_012_500L, radio, alias, identifiers, 2000L);
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }

        assertTrue(manager.getTalkerAliasManager().hasAlias(radio));
        P25TalkerAliasEvent event = subscriber.event.get();
        assertNotNull(event);
        assertEquals(parentChannel, event.channel());
        assertEquals(radio, event.radio());
        assertEquals(alias, event.alias());
        assertEquals(2000L, event.timestamp());
    }

    @Test
    void appliesHarrisTalkerAliasBeforeRadioIdentifierIsKnown() throws Exception
    {
        long frequency = 851_012_500L;
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Control"));
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(1201));
        P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, 1_000L, null)
            .identifiers(identifiers)
            .build();
        P25TrafficChannelEventTracker tracker = new P25TrafficChannelEventTracker(event);
        trafficTrackers(manager).put(frequency, tracker);
        P25TalkerAliasIdentifier alias = P25TalkerAliasIdentifier.create("DISPATCH");

        manager.processP1HarrisTalkerAlias(frequency, null, alias, identifiers, 1_100L);

        assertTrue(event.getIdentifierCollection().hasIdentifier(alias));
    }

    @Test
    void usesMatchingGrantRadioForHarrisConsoleAlias() throws Exception
    {
        long frequency = 851_012_500L;
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Control"));
        RadioIdentifier subscriber = APCO25RadioIdentifier.createFrom(1_880_997);
        RadioIdentifier console = APCO25RadioIdentifier.createFrom(1_104);
        MutableIdentifierCollection grantIdentifiers = identifiers(56_132, subscriber);
        P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, 1_000L, null)
            .identifiers(grantIdentifiers)
            .build();
        trafficTrackers(manager).put(frequency, new P25TrafficChannelEventTracker(event));
        MutableIdentifierCollection trafficIdentifiers = identifiers(56_132, console);
        P25TalkerAliasIdentifier alias = P25TalkerAliasIdentifier.create("CDP #0997");
        TalkerAliasSubscriber aliasSubscriber = new TalkerAliasSubscriber();
        MyEventBus.getGlobalEventBus().register(aliasSubscriber);

        try
        {
            manager.processP1HarrisTalkerAlias(frequency, console, alias, trafficIdentifiers, 1_100L);
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(aliasSubscriber);
        }

        assertEquals(subscriber, aliasSubscriber.event.get().radio());
        assertTrue(manager.getTalkerAliasManager().hasAlias(subscriber));
        assertFalse(manager.getTalkerAliasManager().hasAlias(console));
        assertEquals(subscriber, event.getIdentifierCollection().getFromIdentifier());
        assertTrue(event.getIdentifierCollection().hasIdentifier(alias));
    }

    @Test
    void doesNotAttachAliasToDifferentTrackedCall() throws Exception
    {
        long frequency = 851_012_500L;
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Control"));
        RadioIdentifier trackedRadio = APCO25RadioIdentifier.createFrom(1_880_997);
        RadioIdentifier observedRadio = APCO25RadioIdentifier.createFrom(1_104);
        P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, 1_000L, null)
            .identifiers(identifiers(56_132, trackedRadio))
            .build();
        trafficTrackers(manager).put(frequency, new P25TrafficChannelEventTracker(event));
        P25TalkerAliasIdentifier alias = P25TalkerAliasIdentifier.create("DISPATCH");

        manager.processP1HarrisTalkerAlias(frequency, observedRadio, alias,
            identifiers(56_106, observedRadio), 1_100L);

        assertTrue(manager.getTalkerAliasManager().hasAlias(observedRadio));
        assertFalse(manager.getTalkerAliasManager().hasAlias(trackedRadio));
        assertFalse(event.getIdentifierCollection().hasIdentifier(alias));
    }

    @Test
    void sourceLessGrantUpdatesReuseIncompleteCallAndPreserveRadio() throws Exception
    {
        long frequency = 853_875_000L;
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Control"));
        APCO25Channel channel = APCO25Channel.create(0, 459);
        channel.setFrequencyBand(new P25FrequencyBand(0, 851_006_250L, -45_000_000L, 6_250L, 12_500, 1));
        assertEquals(frequency, channel.getDownlinkFrequency());
        RadioIdentifier source = APCO25RadioIdentifier.createFrom(1234567);
        MutableIdentifierCollection grantIdentifiers = identifiers(1201, source);

        manager.processP1ControlDirectedChannelGrant(channel, null, grantIdentifiers,
            Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT, 1_000L);
        P25TrafficChannelEventTracker initial = trafficTrackers(manager).get(frequency);
        assertNotNull(initial);

        MutableIdentifierCollection updateIdentifiers = identifiers(1201, null);
        manager.processP1ControlAnnouncedTrafficUpdate(channel, null, updateIdentifiers,
            Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT_UPDATE, 4_000L);
        manager.processP1ControlAnnouncedTrafficUpdate(channel, null, updateIdentifiers,
            Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT_UPDATE, 7_000L);

        P25TrafficChannelEventTracker continued = trafficTrackers(manager).get(frequency);
        assertSame(initial, continued);
        assertEquals(source, continued.getEvent().getIdentifierCollection().getFromIdentifier());

        assertTrue(initial.completeTraffic(7_100L));
        manager.processP1ControlAnnouncedTrafficUpdate(channel, null, updateIdentifiers,
            Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT_UPDATE, 7_200L);
        assertNotSame(initial, trafficTrackers(manager).get(frequency));
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer,IFrequencyBand> frequencyBands(P25TrafficChannelManager manager) throws Exception
    {
        Field field = P25TrafficChannelManager.class.getDeclaredField("mFrequencyBandMap");
        field.setAccessible(true);
        return (Map<Integer,IFrequencyBand>)field.get(manager);
    }

    @SuppressWarnings("unchecked")
    private static Map<Long,P25TrafficChannelEventTracker> trafficTrackers(P25TrafficChannelManager manager)
        throws Exception
    {
        Field field = P25TrafficChannelManager.class.getDeclaredField("mTS1ChannelGrantEventMap");
        field.setAccessible(true);
        return (Map<Long,P25TrafficChannelEventTracker>)field.get(manager);
    }

    private static IFrequencyBand band(int identifier, long base, long spacing, int timeslots)
    {
        return new P25FrequencyBand(identifier, base, -45_000_000L, spacing, 12_500, timeslots);
    }

    private static MutableIdentifierCollection identifiers(int talkgroup, RadioIdentifier source)
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(talkgroup));

        if(source != null)
        {
            identifiers.update(source);
        }

        return identifiers;
    }

    private static class TestP25TrafficChannelManager extends P25TrafficChannelManager
    {
        private TestP25TrafficChannelManager(Channel parentChannel)
        {
            super(parentChannel);
        }

        private void changeControlFrequency(long previous, long current, Channel parentChannel)
        {
            processControlFrequencyUpdate(previous, current, parentChannel);
        }
    }

    private static class TalkerAliasSubscriber
    {
        private final AtomicReference<P25TalkerAliasEvent> event = new AtomicReference<>();

        @Subscribe
        public void receive(P25TalkerAliasEvent talkerAliasEvent)
        {
            event.set(talkerAliasEvent);
        }
    }
}
