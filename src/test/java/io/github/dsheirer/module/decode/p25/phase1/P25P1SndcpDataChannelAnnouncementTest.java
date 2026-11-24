/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.phase1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.SNDCPDataChannelAnnouncementExplicit;
import org.junit.jupiter.api.Test;

class P25P1SndcpDataChannelAnnouncementTest
{
    //TIA-102.AABC-E (April 2019), 5.2.7 and Figure 5.2.7-1.
    //Synthetic fields use zero-based MSB-first TSBK positions independently of decoder constants.
    @Test
    void distinguishesAllFourAccessPermissions()
    {
        String[] descriptions = {" DATA SERVICE NOT AVAILABLE", " REQUESTED-ACCESS",
            " AUTONOMOUS-ACCESS", " AUTONOMOUS/REQUESTED-ACCESS"};

        for(int flags = 0; flags < descriptions.length; flags++)
        {
            String text = announcement(flags, 123).toString();

            for(int description = 0; description < descriptions.length; description++)
            {
                assertEquals(description == flags, text.contains(descriptions[description]), text);
            }
            assertEquals(flags >= 2, text.contains(" CHAN:"), text);
        }
    }

    @Test
    void ignoresChannelContentsWhenOnlyRequestedAccessOrNoServiceIsAvailable()
    {
        for(int flags: new int[]{0, 1})
        {
            String expected = announcement(flags, 123).toString();

            for(int channel: new int[]{0, 123, 4094, 4095})
            {
                SNDCPDataChannelAnnouncementExplicit message = announcement(flags, channel);
                message.getMessage().setInt(channel == 4095 ? 15 : 2, IntField.length4(32));
                message.getMessage().setInt(channel == 4095 ? 15 : 3, IntField.length4(48));
                message.getMessage().setInt(channel, IntField.length12(52));

                assertFalse(message.hasChannel());
                assertTrue(message.getChannels().isEmpty());
                assertEquals(expected, message.toString());
                assertFalse(message.toString().contains(" CHAN:"));
                assertTrue(message.getChannels().isEmpty());
            }
        }
    }

    @Test
    void preservesAdvertisedChannelsWhenAutonomousAccessIsAvailable()
    {
        for(int flags: new int[]{2, 3})
        {
            SNDCPDataChannelAnnouncementExplicit message = announcement(flags, 123);
            var channel = message.getChannel();

            assertTrue(message.hasChannel());
            assertEquals(1, message.getChannels().size());
            assertSame(channel, message.getChannels().getFirst());
            assertTrue(message.toString().contains(" CHAN:2-123"));
            assertSame(channel, message.getChannels().getFirst());
        }
    }

    @Test
    void keepsAccessGroupAndDecodedServiceOptionsWithoutRawDebugText()
    {
        for(int flags = 0; flags < 4; flags++)
        {
            SNDCPDataChannelAnnouncementExplicit message = announcement(flags, 123);
            String text = message.toString();

            assertTrue(text.contains(" DAC:42"));
            assertTrue(text.contains(" SERVICE OPTIONS:NSAPI:5 ENCRYPTED FULL DUPLEX PACKET MODE"));
            assertFalse(text.contains(" **:"));
            assertEquals(42, message.getDataAccessControl());
            assertEquals(5, message.getServiceOptions().getNSAPI());
        }
    }

    private static SNDCPDataChannelAnnouncementExplicit announcement(int flags, int downlink)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(96);
        message.setInt(22, IntField.length6(2));
        message.setInt(0x75, IntField.length8(16));
        message.set(24, (flags & 2) != 0);
        message.set(25, (flags & 1) != 0);
        message.setInt(2, IntField.length4(32));
        message.setInt(downlink, IntField.length12(36));
        message.setInt(3, IntField.length4(48));
        message.setInt(456, IntField.length12(52));
        message.setInt(42, IntField.length16(64));
        return new SNDCPDataChannelAnnouncementExplicit(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1,
            message, 1, 0L);
    }
}
