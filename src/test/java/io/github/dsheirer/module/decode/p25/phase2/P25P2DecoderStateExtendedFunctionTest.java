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

package io.github.dsheirer.module.decode.p25.phase2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.identifier.patch.PatchGroupManager;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.DataUnitID;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacOpcode;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.ExtendedFunctionCommandAbbreviated;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.ExtendedFunctionCommandExtendedLCCH;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.ExtendedFunctionCommandExtendedVCH;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.MacStructure;
import io.github.dsheirer.module.decode.p25.reference.ExtendedFunction;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class P25P2DecoderStateExtendedFunctionTest
{
    private static final int NAC = 0x293;

    @Test
    void allStandardCommandFormsBroadcastExactRadioInhibitTypes()
    {
        Channel channel = new Channel("P25 Phase 2 Extended Function", ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase2());
        P25P2DecoderState state = new P25P2DecoderState(channel, 0, new P25TrafficChannelManager(channel),
            new PatchGroupManager());
        List<IDecodeEvent> events = new CopyOnWriteArrayList<>();
        state.addDecodeEventListener(events::add);

        state.receive(message(1_000L, new TestAbbreviatedCommand(ExtendedFunction.RADIO_UNINHIBIT)));
        state.receive(message(2_000L, new TestExtendedVchCommand(ExtendedFunction.RADIO_INHIBIT)));
        state.receive(message(3_000L, new TestExtendedLcchCommand(ExtendedFunction.RADIO_UNINHIBIT)));

        assertEquals(List.of(DecodeEventType.RADIO_UNINHIBIT, DecodeEventType.RADIO_INHIBIT,
            DecodeEventType.RADIO_UNINHIBIT), events.stream().map(IDecodeEvent::getEventType).toList());
    }

    private static MacMessage message(long timestamp, MacStructure structure)
    {
        MacMessage message = new MacMessage(0, DataUnitID.UNSCRAMBLED_LCCH,
            new CorrectedBinaryMessage(180), timestamp, structure);
        message.setNAC(NAC);
        return message;
    }

    private static class TestAbbreviatedCommand extends ExtendedFunctionCommandAbbreviated
    {
        private final ExtendedFunction mFunction;

        private TestAbbreviatedCommand(ExtendedFunction function)
        {
            super(new CorrectedBinaryMessage(180), 0);
            mFunction = function;
        }

        @Override
        public MacOpcode getOpcode()
        {
            return MacOpcode.PHASE1_64_EXTENDED_FUNCTION_COMMAND_ABBREVIATED;
        }

        @Override
        public ExtendedFunction getExtendedFunction()
        {
            return mFunction;
        }
    }

    private static class TestExtendedVchCommand extends ExtendedFunctionCommandExtendedVCH
    {
        private final ExtendedFunction mFunction;

        private TestExtendedVchCommand(ExtendedFunction function)
        {
            super(new CorrectedBinaryMessage(180), 0);
            mFunction = function;
        }

        @Override
        public MacOpcode getOpcode()
        {
            return MacOpcode.PHASE1_E4_EXTENDED_FUNCTION_COMMAND_EXTENDED_VCH;
        }

        @Override
        public ExtendedFunction getExtendedFunction()
        {
            return mFunction;
        }
    }

    private static class TestExtendedLcchCommand extends ExtendedFunctionCommandExtendedLCCH
    {
        private final ExtendedFunction mFunction;

        private TestExtendedLcchCommand(ExtendedFunction function)
        {
            super(new CorrectedBinaryMessage(180), 0);
            mFunction = function;
        }

        @Override
        public MacOpcode getOpcode()
        {
            return MacOpcode.PHASE1_E5_EXTENDED_FUNCTION_COMMAND_EXTENDED_LCCH;
        }

        @Override
        public ExtendedFunction getExtendedFunction()
        {
            return mFunction;
        }
    }
}
