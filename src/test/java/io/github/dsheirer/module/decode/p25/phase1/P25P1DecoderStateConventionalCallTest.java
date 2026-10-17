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

package io.github.dsheirer.module.decode.p25.phase1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.audio.call.AudioActivityCallEvent;
import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlWord;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlOpcode;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU1Message;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.encryption.APCO25EncryptionKey;
import io.github.dsheirer.module.decode.p25.phase1.message.hdu.HDUMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.hdu.HeaderData;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.identifier.channel.StandardChannel;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.reference.VoiceServiceOptions;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class P25P1DecoderStateConventionalCallTest
{
    @Test
    void forwardsPrivatelyManagedConventionalCallEvents() throws Exception
    {
        long frequency = 154_875_000L;
        Channel channel = new Channel("LorainCountySO", ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Conventional());
        P25P1DecoderState decoderState = new P25P1DecoderState(channel, null);
        List<IDecodeEvent> events = new CopyOnWriteArrayList<>();
        decoderState.addDecodeEventListener(events::add);
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(1_201));
        identifiers.update(APCO25RadioIdentifier.createFrom(1_234_567));
        P25TrafficChannelManager manager = trafficChannelManager(decoderState);

        manager.processP1TrafficCurrentUser(frequency, new StandardChannel(frequency),
            DecodeEventType.CALL_GROUP, VoiceServiceOptions.createUnencrypted(), identifiers, 1_000L, null);
        manager.processP1TrafficCurrentUser(frequency, new StandardChannel(frequency),
            DecodeEventType.CALL_GROUP, VoiceServiceOptions.createUnencrypted(), identifiers, 1_100L, null);

        assertEquals(2, events.size());
        assertSame(events.get(0), events.get(1));
    }

    @Test
    void freshHeaderKeepsAcceptedGrantSourceWithoutReusingDecoderWorkingSource()
    {
        long frequency = 851_012_500L;
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Control"));
        var grant = manager.processP1TrafficCallStart(frequency, APCO25Talkgroup.create(1201),
            APCO25RadioIdentifier.createFrom(700_001), EncryptionKeyIdentifier.create(APCO25EncryptionKey.create(0x80, 0)),
            VoiceServiceOptions.createUnencrypted(), new StandardChannel(frequency), 1_000L);
        Channel traffic = new Channel("Traffic", ChannelType.TRAFFIC);
        traffic.setDecodeConfiguration(new DecodeConfigP25Phase1());
        P25P1DecoderState state = new P25P1DecoderState(traffic, manager);
        state.setCurrentFrequency(frequency);
        state.getIdentifierCollection().update(APCO25RadioIdentifier.createFrom(700_009));
        Attributions attributions = listen(state);

        state.receive(header(1_100L));

        assertNull(state.getIdentifierCollection().getFromIdentifier());
        assertEquals(grant.token(), attributions.events.getLast().token());
        assertSame(grant.attribution(), attributions.events.getLast().attribution());
        assertEquals(APCO25RadioIdentifier.createFrom(700_001), attributions.events.getLast().attribution().source());
    }

    @Test
    void freshConventionalHeaderStartsUnknownUntilItsOwnSourceArrives() throws Exception
    {
        Channel channel = new Channel("Conventional");
        channel.setDecodeConfiguration(new DecodeConfigP25Conventional());
        P25P1DecoderState state = new P25P1DecoderState(channel, null);
        long frequency = 154_875_000L;
        state.setCurrentFrequency(frequency);
        MutableIdentifierCollection previous = new MutableIdentifierCollection();
        previous.update(APCO25Talkgroup.create(1201));
        previous.update(APCO25RadioIdentifier.createFrom(700_001));
        var first = trafficChannelManager(state).processP1TrafficCurrentUser(frequency, new StandardChannel(frequency),
            DecodeEventType.CALL_GROUP, VoiceServiceOptions.createUnencrypted(), previous, 1_000L, null);
        trafficChannelManager(state).processP1TrafficCurrentUser(frequency, new StandardChannel(frequency),
            DecodeEventType.CALL_GROUP, VoiceServiceOptions.createUnencrypted(), previous, 1_050L, null);
        state.getIdentifierCollection().update(previous.getIdentifiers());
        Attributions attributions = listen(state);

        state.receive(header(1_100L));

        assertNull(state.getIdentifierCollection().getFromIdentifier());
        assertNotEquals(first.token(), attributions.events.getLast().token());
        assertNull(attributions.events.getLast().attribution().source());
        assertEquals(APCO25RadioIdentifier.createFrom(700_001), first.attribution().source());
    }

    @Test
    void decodedSourceTransitionStartsANewCallWhenHeaderAndTerminatorWereMissed()
    {
        Channel channel = new Channel("Conventional");
        channel.setDecodeConfiguration(new DecodeConfigP25Conventional());
        P25P1DecoderState state = new P25P1DecoderState(channel, null);
        state.setCurrentFrequency(154_875_000L);
        Attributions attributions = listen(state);
        state.receive(voice(1_000L, 101));
        var first = attributions.events.getLast();
        state.receive(voice(1_180L, 202));
        var second = attributions.events.getLast();
        assertNotEquals(first.token(), second.token());
        assertEquals(APCO25RadioIdentifier.createFrom(101), first.attribution().source());
        assertEquals(APCO25RadioIdentifier.createFrom(202), second.attribution().source());
    }

    private static LDU1Message voice(long timestamp, int source)
    {
        LinkControlWord lc = new LinkControlWord(new CorrectedBinaryMessage(72))
        {
            @Override public LinkControlOpcode getOpcode() { return LinkControlOpcode.GROUP_VOICE_CHANNEL_USER; }
            @Override public List<Identifier> getIdentifiers()
            {
                return List.of(APCO25Talkgroup.create(1201), APCO25RadioIdentifier.createFrom(source));
            }
        };
        lc.setValid(true);
        return new LDU1Message(new CorrectedBinaryMessage(1568), 0x123, timestamp)
        {
            @Override public LinkControlWord getLinkControlWord() { return lc; }
        };
    }

    private static Attributions listen(P25P1DecoderState state)
    {
        Attributions attributions = new Attributions();
        EventBus bus = new EventBus();
        bus.register(attributions);
        state.setInterModuleEventBus(bus);
        return attributions;
    }

    private static final class Attributions
    {
        private final List<AudioActivityCallEvent> events = new CopyOnWriteArrayList<>();
        @Subscribe public void receive(AudioActivityCallEvent event) { events.add(event); }
    }

    private static HDUMessage header(long timestamp)
    {
        BinaryMessage bits = new BinaryMessage(120);
        bits.setInt(0x80, IntField.length8(80));
        bits.setInt(1201, IntField.length16(104));
        HeaderData data = new HeaderData(bits);
        return new HDUMessage(new CorrectedBinaryMessage(648), 0x123, timestamp)
        {
            @Override public HeaderData getHeaderData() { return data; }
        };
    }

    private static P25TrafficChannelManager trafficChannelManager(P25P1DecoderState decoderState) throws Exception
    {
        Field field = P25P1DecoderState.class.getDeclaredField("mTrafficChannelManager");
        field.setAccessible(true);
        return (P25TrafficChannelManager)field.get(decoderState);
    }
}
