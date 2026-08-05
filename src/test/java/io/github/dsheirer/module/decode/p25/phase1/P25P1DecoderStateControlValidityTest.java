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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.p25.phase1.message.P25P1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.TSBKMessage;
import io.github.dsheirer.module.decode.p25.reference.Direction;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25P1DecoderStateControlValidityTest
{
    private static final List<P25P1DataUnitID> CONTROL_DATA_UNITS = List.of(
        P25P1DataUnitID.ALTERNATE_MULTI_BLOCK_TRUNKING_CONTROL,
        P25P1DataUnitID.UNCONFIRMED_MULTI_BLOCK_TRUNKING_CONTROL,
        P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1,
        P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_2,
        P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_3);

    @Test
    void invalidControlFramesDoNotChangeConventionalState()
    {
        for(P25P1DataUnitID dataUnit: CONTROL_DATA_UNITS)
        {
            List<DecoderStateEvent> events = receive(new DecodeConfigP25Conventional(), dataUnit, false);

            assertTrue(events.isEmpty(),
                () -> dataUnit + " emitted a state change that could make a conventional channel flicker");
        }
    }

    @Test
    void validControlFramesStillIdentifyRealConventionalControlActivity()
    {
        for(P25P1DataUnitID dataUnit: CONTROL_DATA_UNITS)
        {
            List<DecoderStateEvent> events = receive(new DecodeConfigP25Conventional(), dataUnit, true);

            assertEquals(1, events.size(), dataUnit::toString);
            assertEquals(State.CONTROL, events.getFirst().getState(), dataUnit::toString);
        }
    }

    @Test
    void invalidControlFramesPreserveExistingTrunkedBehavior()
    {
        for(P25P1DataUnitID dataUnit: CONTROL_DATA_UNITS)
        {
            List<DecoderStateEvent> events = receive(new DecodeConfigP25Phase1(), dataUnit, false);

            assertEquals(1, events.size(), dataUnit::toString);
            assertEquals(State.CONTROL, events.getFirst().getState(), dataUnit::toString);
        }
    }

    private static List<DecoderStateEvent> receive(DecodeConfiguration configuration,
                                                    P25P1DataUnitID dataUnit, boolean valid)
    {
        Channel channel = new Channel("Test", ChannelType.STANDARD);
        channel.setDecodeConfiguration(configuration);
        P25P1DecoderState decoderState = new P25P1DecoderState(channel, null);
        List<DecoderStateEvent> events = new ArrayList<>();
        decoderState.setDecoderStateListener(events::add);

        if(configuration instanceof DecodeConfigP25Phase1)
        {
            decoderState.receive(new NACAuthorityMessage(997L));
            decoderState.receive(new NACAuthorityMessage(998L));
            decoderState.receive(new NACAuthorityMessage(999L));
            events.clear();
        }

        TestMessage message = new TestMessage(dataUnit);
        message.setValid(valid);
        decoderState.receive(message);
        return events;
    }

    private static class NACAuthorityMessage extends TSBKMessage
    {
        private NACAuthorityMessage(long timestamp)
        {
            super(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, new CorrectedBinaryMessage(96), 0x123, timestamp);
        }

        @Override
        public Direction getDirection()
        {
            return Direction.OUTBOUND;
        }

        @Override
        public Opcode getOpcode()
        {
            return Opcode.OSP_UNKNOWN;
        }

        @Override
        public List<Identifier> getIdentifiers()
        {
            return List.of();
        }
    }

    private static class TestMessage extends P25P1Message
    {
        private final P25P1DataUnitID mDataUnit;

        private TestMessage(P25P1DataUnitID dataUnit)
        {
            super(new CorrectedBinaryMessage(0), 0x123, 1_000L);
            mDataUnit = dataUnit;
        }

        @Override
        public P25P1DataUnitID getDUID()
        {
            return mDataUnit;
        }

        @Override
        public List<Identifier> getIdentifiers()
        {
            return List.of();
        }
    }
}
