/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.dmr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.eventbus.EventBus;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.dmr.channel.DMRChannel;
import io.github.dsheirer.module.decode.dmr.channel.DMRLsn;
import io.github.dsheirer.module.decode.dmr.event.DMRDecodeEvent;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

class DMRChannelGrantPreloadDataTest
{
    @Test
    void routesTimeslotOneGrantToMatchingState() throws ReflectiveOperationException
    {
        assertRoutesGrant(new DMRLsn(3));
    }

    @Test
    void routesTimeslotTwoGrantToMatchingState() throws ReflectiveOperationException
    {
        assertRoutesGrant(new DMRLsn(4));
    }

    @Test
    void rejectsNullGrantEvent()
    {
        assertThrows(NullPointerException.class, () -> new DMRChannelGrantPreloadData(null));
    }

    private static void assertRoutesGrant(DMRChannel grantedChannel) throws ReflectiveOperationException
    {
        Channel channel = channel();
        TestDMRDecoderState state1 = new TestDMRDecoderState(channel, 1);
        TestDMRDecoderState state2 = new TestDMRDecoderState(channel, 2);
        state1.setSisterDecoderState(state2);
        state2.setSisterDecoderState(state1);
        EventBus eventBus = new EventBus();
        state1.setInterModuleEventBus(eventBus);
        state2.setInterModuleEventBus(eventBus);
        DecodeEvent grantEvent = DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L)
            .channel(grantedChannel)
            .identifiers(new IdentifierCollection(List.of(DMRTalkgroup.create(91))))
            .timeslot(grantedChannel.getTimeslot())
            .build();

        eventBus.post(new DMRChannelGrantPreloadData(grantEvent));

        TestDMRDecoderState matching = grantedChannel.getTimeslot() == 1 ? state1 : state2;
        TestDMRDecoderState sister = grantedChannel.getTimeslot() == 1 ? state2 : state1;
        assertSame(grantEvent, currentCallEvent(matching));
        assertNull(currentCallEvent(sister));
        assertSame(grantedChannel, matching.currentChannel());
        assertEquals(grantedChannel.getSisterTimeslot(), sister.currentChannel());

        state1.setInterModuleEventBus(null);
        state2.setInterModuleEventBus(null);
    }

    private static Channel channel()
    {
        Channel channel = new Channel("Traffic", Channel.ChannelType.TRAFFIC);
        DecodeConfigDMR configuration = new DecodeConfigDMR();
        configuration.setChannelMode(DMRChannelMode.TRUNKED);
        channel.setDecodeConfiguration(configuration);
        return channel;
    }

    private static DecodeEvent currentCallEvent(DMRDecoderState state) throws ReflectiveOperationException
    {
        Field field = DMRDecoderState.class.getDeclaredField("mCurrentCallEvent");
        field.setAccessible(true);
        return (DecodeEvent)field.get(state);
    }

    private static class TestDMRDecoderState extends DMRDecoderState
    {
        private TestDMRDecoderState(Channel channel, int timeslot)
        {
            super(channel, timeslot, null);
        }

        private IChannelDescriptor currentChannel()
        {
            return getCurrentChannel();
        }
    }
}
