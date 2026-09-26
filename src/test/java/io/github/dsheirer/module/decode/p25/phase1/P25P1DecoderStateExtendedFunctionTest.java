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

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.isp.ExtendedFunctionResponse;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.ExtendedFunctionCommand;
import io.github.dsheirer.module.decode.p25.reference.ExtendedFunction;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class P25P1DecoderStateExtendedFunctionTest
{
    private static final int NAC = 0x293;

    @Test
    void broadcastsExactRadioInhibitCommandAndAcknowledgementTypes()
    {
        Channel channel = new Channel("P25 Extended Function", ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        P25P1DecoderState state = new P25P1DecoderState(channel, new P25TrafficChannelManager(channel));
        List<IDecodeEvent> events = new CopyOnWriteArrayList<>();
        state.addDecodeEventListener(events::add);

        state.receive(new TestCommand(ExtendedFunction.RADIO_UNINHIBIT, 1_000L));
        state.receive(new TestCommand(ExtendedFunction.RADIO_INHIBIT, 2_000L));
        state.receive(new TestResponse(ExtendedFunction.RADIO_UNINHIBIT_ACK, 3_000L));
        state.receive(new TestResponse(ExtendedFunction.RADIO_INHIBIT_ACK, 4_000L));

        assertEquals(List.of(DecodeEventType.RADIO_UNINHIBIT, DecodeEventType.RADIO_INHIBIT,
            DecodeEventType.RADIO_UNINHIBIT_ACK, DecodeEventType.RADIO_INHIBIT_ACK),
            events.stream().map(IDecodeEvent::getEventType).toList());
    }

    private static class TestCommand extends ExtendedFunctionCommand
    {
        private final ExtendedFunction mFunction;

        private TestCommand(ExtendedFunction function, long timestamp)
        {
            super(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, new CorrectedBinaryMessage(96), NAC, timestamp);
            mFunction = function;
        }

        @Override
        public Opcode getOpcode()
        {
            return Opcode.OSP_EXTENDED_FUNCTION_COMMAND;
        }

        @Override
        public ExtendedFunction getExtendedFunction()
        {
            return mFunction;
        }
    }

    private static class TestResponse extends ExtendedFunctionResponse
    {
        private final ExtendedFunction mFunction;

        private TestResponse(ExtendedFunction function, long timestamp)
        {
            super(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, new CorrectedBinaryMessage(96), NAC, timestamp);
            mFunction = function;
        }

        @Override
        public Opcode getOpcode()
        {
            return Opcode.ISP_EXTENDED_FUNCTION_RESPONSE;
        }

        @Override
        public ExtendedFunction getExtendedFunction()
        {
            return mFunction;
        }
    }
}
