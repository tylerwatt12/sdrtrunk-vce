/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.dmr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.alias.TalkerAliasIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.module.decode.dmr.event.DMRDecodeEvent;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.motorola.CapacityMaxTalkerAlias;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DMRDecoderStateCapacityMaxTalkerAliasTest
{
    @Test
    void trafficChannelKeepsGrantTrafficAndAliasManager() throws Exception
    {
        Channel standardChannel = channel("Capacity Plus Rest", Channel.ChannelType.STANDARD);
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager(standardChannel);
        TestDecoderState state = new TestDecoderState(
            channel("Capacity Plus Traffic", Channel.ChannelType.TRAFFIC), manager);
        state.getIdentifierCollection().update(DMRRadio.createFrom(101));

        state.emit(DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L)
            .identifiers(new IdentifierCollection())
            .timeslot(1)
            .build());
        state.receive(capacityMaxAlias("CAR 1", 1_100L));

        assertSame(manager, activeTrafficChannelManager(state));
        assertEquals(1, manager.mTrafficEvents);
        assertEquals(List.of("CAR 1"), manager.mAliases);
    }

    @Test
    void completeShortCapacityMaxAliasIsReportedOnlyOnce()
    {
        Channel channel = channel("Capacity Max", Channel.ChannelType.STANDARD);
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager(channel);
        DMRDecoderState state = new DMRDecoderState(channel, 1, manager);
        state.getIdentifierCollection().update(DMRRadio.createFrom(101));

        state.receive(capacityMaxAlias("ENGINE", 1_000L));
        state.receive(capacityMaxAlias("ENGINE", 1_100L));

        assertEquals(List.of("ENGINE"), manager.mAliases);
    }

    private static Channel channel(String name, Channel.ChannelType type)
    {
        Channel channel = new Channel(name, type);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        channel.setDecodeConfiguration(config);
        return channel;
    }

    private static CapacityMaxTalkerAlias capacityMaxAlias(String alias, long timestamp)
    {
        byte[] bytes = alias.getBytes(StandardCharsets.US_ASCII);
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
        bits.load(2, 6, 20);
        bits.load(8, 8, 16);
        bits.load(19, 4, bytes.length);

        for(int x = 0; x < bytes.length; x++)
        {
            bits.load(24 + (x * 8), 8, bytes[x] & 0xFF);
        }

        return new CapacityMaxTalkerAlias(bits, timestamp, 1);
    }

    private static Object activeTrafficChannelManager(DMRDecoderState state) throws ReflectiveOperationException
    {
        Field field = DMRDecoderState.class.getDeclaredField("mTrafficChannelManager");
        field.setAccessible(true);
        return field.get(state);
    }

    private static class TestDecoderState extends DMRDecoderState
    {
        private TestDecoderState(Channel channel, DMRTrafficChannelManager manager)
        {
            super(channel, 1, manager);
        }

        private void emit(IDecodeEvent event)
        {
            broadcast(event);
        }
    }

    private static class RecordingTrafficChannelManager extends DMRTrafficChannelManager
    {
        private int mTrafficEvents;
        private final List<String> mAliases = new ArrayList<>();

        private RecordingTrafficChannelManager(Channel channel)
        {
            super(channel);
        }

        @Override
        public void receiveTrafficChannelEvent(IDecodeEvent trafficChannelEvent)
        {
            mTrafficEvents++;
        }

        @Override
        public void processTalkerAlias(TalkerAliasIdentifier alias, RadioIdentifier radio,
                                       IdentifierCollection identifiers, long timestamp)
        {
            mAliases.add(alias.getValue().toString());
        }
    }
}
