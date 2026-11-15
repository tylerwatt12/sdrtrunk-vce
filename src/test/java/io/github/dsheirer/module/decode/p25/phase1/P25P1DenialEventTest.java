/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.p25.P25DenialEvent;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.TSBKMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.DenyResponse;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.motorola.osp.MotorolaDenyResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25P1DenialEventTest
{
    @Test
    void standardAndMotorolaDenialsBroadcastTypedOriginalReasonAndIdentity()
    {
        for(boolean motorola: new boolean[]{false, true})
        {
            Channel channel = new Channel("P25 denial", Channel.ChannelType.STANDARD);
            channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
            P25P1DecoderState state = new P25P1DecoderState(channel, new P25TrafficChannelManager(channel));
            List<IDecodeEvent> events = new ArrayList<>();
            state.addDecodeEventListener(events::add);
            CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
            if(motorola) bits.set(16);
            bits.setInt(motorola ? 0 : 0x31, IntField.length8(24));
            bits.setInt(202, IntField.length24(56));
            TSBKMessage message = motorola ?
                new MotorolaDenyResponse(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, bits, 0x293, 1_100L)
                {
                    @Override public Opcode getOpcode() { return Opcode.MOTOROLA_OSP_DENY_RESPONSE; }
                } :
                new DenyResponse(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, bits, 0x293, 1_100L)
                {
                    @Override public Opcode getOpcode() { return Opcode.OSP_DENY_RESPONSE; }
                };
            state.receive(message);

            assertEquals(1, events.size());
            P25DenialEvent denial = assertInstanceOf(P25DenialEvent.class, events.getFirst());
            assertEquals(motorola ? 0 : 0x31, denial.getDenyReasonCode());
            assertEquals(motorola, denial.isVendorSpecificReason());
            assertEquals(1_100L, denial.getTimeStart());
            assertEquals(202, denial.getIdentifierCollection().getToIdentifier().getValue());
            assertTrue(denial.getDetails().contains(motorola ? "SECURE_REQUEST_ON_CLEAR_SUPERGROUP" :
                "TARGET_GROUP_NOT_AUTHORIZED_FOR_SERVICE"));
        }
    }
}
