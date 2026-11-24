/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.protocol.Protocol;
import org.junit.jupiter.api.Test;

public class HealthMonitorTest
{
    @Test
    void time_without_sync_is_counted_in_symbols_on_a_p25_control_channel()
    {
        Channel channel = new Channel("P25 control");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        HealthMonitor monitor = new HealthMonitor(channel);
        assertEquals(0, monitor.getHealth().totals(0).syncLossSym(), "measured, and none yet");

        //A second of P25 Phase 1 without sync: 9600 bits, 4800 symbols.
        monitor.receive(new SyncLossMessage(1_000, 9600, Protocol.APCO25));
        monitor.receive(new SyncLossMessage(2_000, 96, Protocol.APCO25));
        assertEquals(4848, monitor.getHealth().totals(0).syncLossSym());
    }

    @Test
    void sync_loss_is_left_out_for_a_decoder_that_doesnt_say_when_it_loses_sync()
    {
        Channel channel = new Channel("DMR control");
        channel.setDecodeConfiguration(new DecodeConfigDMR());
        HealthMonitor monitor = new HealthMonitor(channel);
        assertNull(monitor.getHealth().totals(0).syncLossSym());
    }
}
