/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.channel.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class SingleChannelStateTimeoutTest
{
    @Test
    void trafficRetainsDefaultAndDecoderRequestedTimeout() throws Exception
    {
        SingleChannelState state = state(Channel.ChannelType.TRAFFIC);
        StateMachine machine = machine(state);
        assertEquals(45_000, machine.mFadeTimeoutBufferMilliseconds);

        state.getDecoderStateListener().receive(new DecoderStateEvent(this,
            DecoderStateEvent.Event.START, State.CALL));
        assertEquals(State.CALL, machine.getState());

        state.getDecoderStateListener().receive(new ChangeChannelTimeoutEvent(this,
            Channel.ChannelType.TRAFFIC, 0));
        state.checkState();
        assertEquals(State.FADE, machine.getState());
    }

    @Test
    void standardChannelRetainsShortInactivityDelay() throws Exception
    {
        StateMachine machine = machine(state(Channel.ChannelType.STANDARD));
        assertEquals(1_200, machine.mFadeTimeoutBufferMilliseconds);
    }

    private static SingleChannelState state(Channel.ChannelType type)
    {
        Channel channel = new Channel("Timeout test", type);
        channel.setDecodeConfiguration(new DecodeConfigNBFM());
        return new SingleChannelState(channel, new AliasModel());
    }

    private static StateMachine machine(SingleChannelState state) throws Exception
    {
        Field field = SingleChannelState.class.getDeclaredField("mStateMachine");
        field.setAccessible(true);
        return (StateMachine)field.get(state);
    }
}
