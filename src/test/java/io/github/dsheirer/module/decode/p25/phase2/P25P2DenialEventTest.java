/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.patch.PatchGroupManager;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.p25.P25DenialEvent;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.DataUnitID;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.DenyResponse;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.MacStructure;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.motorola.MotorolaDenyResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25P2DenialEventTest
{
    @Test
    void standardAndMotorolaDenialsBroadcastTypedOriginalReasonAndTimeslot()
    {
        for(boolean motorola: new boolean[]{false, true})
        {
            Channel channel = new Channel("Traffic", Channel.ChannelType.TRAFFIC);
            channel.setDecodeConfiguration(new DecodeConfigP25Phase2());
            P25P2DecoderState state = new P25P2DecoderState(channel, 2, new P25TrafficChannelManager(channel),
                new PatchGroupManager());
            List<IDecodeEvent> events = new ArrayList<>();
            state.addDecodeEventListener(events::add);

            int offset = 13;
            CorrectedBinaryMessage bits = new CorrectedBinaryMessage(160);
            bits.setInt(motorola ? 0xA7 : 0x67, IntField.length8(offset));
            if(motorola)
            {
                bits.setInt(0x90, IntField.length8(offset + 8));
                bits.set(offset + 24); // RC selects the MFID90 table, not additional-information validity.
            }
            bits.setInt(motorola ? 0 : 0x31, IntField.length8(offset + (motorola ? 32 : 16)));
            bits.setInt(202, IntField.length24(offset + (motorola ? 64 : 48)));
            MacStructure structure = motorola ? new MotorolaDenyResponse(bits, offset) : new DenyResponse(bits, offset);
            MacMessage message = new MacMessage(2, DataUnitID.UNSCRAMBLED_FACCH, new CorrectedBinaryMessage(180),
                1_100L, structure);
            state.receive(message);

            assertEquals(1, events.size());
            P25DenialEvent denial = assertInstanceOf(P25DenialEvent.class, events.getFirst());
            assertEquals(motorola ? 0 : 0x31, denial.getDenyReasonCode());
            assertEquals(motorola, denial.isVendorSpecificReason());
            assertEquals(2, denial.getTimeslot());
            assertEquals(1_100L, denial.getTimeStart());
            assertEquals(202, denial.getIdentifierCollection().getToIdentifier().getValue());
            if(motorola)
            {
                assertTrue(denial.getDetails().contains("SECURE_REQUEST_ON_CLEAR_SUPERGROUP"));
                assertFalse(denial.getDetails().contains("INFO:"), "RC alone does not establish additional information");
            }
            else
            {
                assertTrue(denial.getDetails().contains("TARGET_GROUP_NOT_AUTHORIZED_FOR_SERVICE"));
            }
        }
    }
}
