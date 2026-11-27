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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.audio.squelch.SquelchStateEvent;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.dmr.message.data.SlotType;
import io.github.dsheirer.module.decode.dmr.message.data.UnknownDataMessage;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.UnknownCSBKMessage;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.layer2.LICH;
import io.github.dsheirer.module.decode.nxdn.layer2.SACCHFragment;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.DataUnitID;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.SourceEvent;
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
        HealthMonitor monitor = collectingMonitor(channel, true);
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
        HealthMonitor monitor = collectingMonitor(channel, true);
        assertNull(monitor.getHealth().totals(0).syncLossSym());
    }

    @Test
    void delayed_control_messages_use_arrival_freshness_and_sync_loss_advances_decoder_time()
    {
        Channel channel = new Channel("P25 control with delayed samples");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase2());
        HealthMonitor monitor = collectingMonitor(channel, true);
        ChannelHealth health = monitor.getHealth();
        health.started(4_000);
        try
        {
            MacMessage first = new MacMessage(0, DataUnitID.UNSCRAMBLED_LCCH,
                new CorrectedBinaryMessage(320), 1_000, null);
            first.setValid(true);
            monitor.receive(first, 4_000);
            assertTrue(health.totals(4_000).locked(), "Three seconds of sample delay must not hide fresh decoding");

            monitor.receive(new SyncLossMessage(3_001, 96, Protocol.APCO25), 6_001);
            assertFalse(health.totals(6_001).locked(),
                "Fresh sync-loss delivery still advances beyond the last good control unit");
            assertEquals(48, health.totals(6_001).syncLossSym());

            MacMessage recovered = new MacMessage(0, DataUnitID.UNSCRAMBLED_LCCH,
                new CorrectedBinaryMessage(320), 4_000, null);
            recovered.setValid(true);
            monitor.receive(recovered, 7_000);
            assertTrue(health.totals(7_000).locked());
            assertEquals(2, health.totals(7_000).good());
            assertFalse(health.totals(9_001).locked(), "Silence still expires after two seconds");
        }
        finally
        {
            health.stopped(9_001);
            HealthRegistry.getInstance().retire(channel);
        }
    }

    @Test
    void dmr_reports_csbks_only_and_keeps_raw_integrity_separate_from_ignore_crc()
    {
        Channel channel = new Channel("DMR control units");
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setIgnoreCRCChecksums(true);
        channel.setDecodeConfiguration(config);
        HealthMonitor monitor = collectingMonitor(channel, true);
        SlotType validSlot = SlotType.getSlotType(new CorrectedBinaryMessage(288));
        UnknownDataMessage data = new UnknownDataMessage(DMRSyncPattern.BASE_STATION_DATA,
            new CorrectedBinaryMessage(99), null, validSlot, 1_000, 1);
        data.setValid(true);
        monitor.receive(data);
        UnknownCSBKMessage good = csbk(1_030, true, validSlot);
        UnknownCSBKMessage bad = csbk(1_060, false, validSlot);
        monitor.receive(good);
        monitor.receive(bad);
        SlotType invalidSlot = new SlotType(new CorrectedBinaryMessage(24))
        {
            @Override
            public boolean isValid()
            {
                return false;
            }
        };
        monitor.receive(csbk(1_090, true, invalidSlot));
        ChannelHealth.Totals totals = monitor.getHealth().totals(2_000);
        assertEquals(1, totals.good());
        assertEquals(2, totals.bad());
        assertEquals("dmr", totals.protocol());
    }

    @Test
    void nxdn_reports_outbound_cac_once_and_excludes_traffic_type_d_and_noncarrier_messages()
    {
        Channel channel = new Channel("NXDN CAC units");
        channel.setDecodeConfiguration(new DecodeConfigNXDN());
        HealthMonitor monitor = collectingMonitor(channel, true);
        SACCHFragment cac = nxdn(1_000, LICH.RCCH_OUTBOUND_SINGLE_CAC_NORMAL);
        cac.setRfFrameQuality(true, 0);
        monitor.receive(cac);
        SACCHFragment badCac = nxdn(1_040, LICH.RCCH_OUTBOUND_SINGLE_CAC_IDLE);
        badCac.setRfFrameQuality(false, 0);
        monitor.receive(badCac);
        monitor.receive(nxdn(1_000, LICH.RCCH_OUTBOUND_SINGLE_CAC_NORMAL));
        SACCHFragment typeD = nxdn(1_080, LICH.RTCH_2_OUTBOUND_SINGLE_FACCH1_FACCH1);
        typeD.setRfFrameQuality(true, 0);
        monitor.receive(typeD);
        SACCHFragment inbound = nxdn(1_120, LICH.RCCH_INBOUND_SINGLE_CAC_LONG);
        inbound.setRfFrameQuality(true, 0);
        monitor.receive(inbound);
        ChannelHealth.Totals totals = monitor.getHealth().totals(2_000);
        assertEquals(1, totals.good());
        assertEquals(1, totals.bad());
        assertEquals("nxdn", totals.protocol());
    }

    @Test
    void lcch_deduplication_is_reset_when_processing_restarts()
    {
        Channel channel = new Channel("P25 LCCH restart");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase2());
        HealthMonitor monitor = collectingMonitor(channel, true);
        monitor.start();
        MacMessage message = new MacMessage(0, DataUnitID.UNSCRAMBLED_LCCH,
            new CorrectedBinaryMessage(320), 1_000, null);
        message.setValid(true);
        monitor.receive(message);
        monitor.receive(message);
        assertEquals(1, monitor.getHealth().totals(2_000).good());
        monitor.stop();
        monitor.start();
        monitor.receive(message);
        assertEquals(2, monitor.getHealth().totals(2_000).good());
        monitor.stop();
    }

    @Test
    void conventional_heard_duration_uses_actual_delivered_samples_instead_of_started_time()
    {
        Channel channel = new Channel("FM delivered samples");
        channel.setDecodeConfiguration(new io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM());
        HealthMonitor monitor = collectingMonitor(channel, true);
        assertNull(monitor.getHealth().totals(10_000).heardMs());
        monitor.getSourceEventListener().receive(SourceEvent.sampleRateChange(48_000));
        assertEquals(0, monitor.getHealth().totals(10_000).heardMs());
        monitor.getComplexSamplesListener().receive(new ComplexSamples(new float[480], new float[480], 1_000));
        //Timestamp discontinuity contributes no invented samples; only the delivered array length is counted.
        monitor.getComplexSamplesListener().receive(new ComplexSamples(new float[480], new float[480], 9_000));
        assertEquals(20, monitor.getHealth().totals(10_000).heardMs());
        monitor.getSourceEventListener().receive(SourceEvent.sampleRateChange(24_000));
        monitor.getComplexSamplesListener().receive(new ComplexSamples(new float[480], new float[480], 9_020));
        assertEquals(40, monitor.getHealth().totals(10_000).heardMs());
    }

    @Test
    void remote_bitstream_symbol_rate_is_not_a_complex_sample_delivery_measurement()
    {
        Channel channel = new Channel("Remote bitstream");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        HealthMonitor monitor = collectingMonitor(channel, false);
        monitor.getSourceEventListener().receive(SourceEvent.sampleRateChange(4_800));
        assertNull(monitor.getHealth().totals(10_000).heardMs());
    }

    private static UnknownCSBKMessage csbk(long timestamp, boolean valid, SlotType slot)
    {
        UnknownCSBKMessage message = new UnknownCSBKMessage(DMRSyncPattern.BASE_STATION_DATA,
            new CorrectedBinaryMessage(99), null, slot, timestamp, 1);
        message.setValid(valid);
        return message;
    }

    private static SACCHFragment nxdn(long timestamp, LICH lich)
    {
        return new SACCHFragment(new CorrectedBinaryMessage(26), timestamp, lich);
    }

    @Test
    void phase_two_protocol_is_known_before_any_control_message_decodes()
    {
        Channel channel = new Channel("Deaf Phase 2 control");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase2());
        HealthMonitor monitor = collectingMonitor(channel, true);
        ChannelHealth health = monitor.getHealth();
        assertEquals("p25", health.totals(0).protocol());
        assertTrue(health.totals(0).isSite());
        assertEquals(0, health.totals(0).good());
        assertEquals(0, health.totals(0).bad());
        assertNull(health.totals(0).calls(), "Protocol alone does not turn conventional digital into trunked coverage");
        health.setCallsCounted();
        assertNotNull(health.totals(0).calls());
    }

    private static HealthMonitor collectingMonitor(Channel channel, boolean complexSource)
    {
        HealthMonitor monitor = new HealthMonitor(channel, complexSource);
        monitor.getHealth().setCollecting(true, 0);
        return monitor;
    }

    @Test
    void disabled_monitor_returns_before_control_classification_or_sample_projection()
    {
        Channel channel = new Channel("Inactive report collection");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase2());
        HealthMonitor monitor = new HealthMonitor(channel);
        monitor.getHealth().setCollecting(false, 0);
        MacMessage forbiddenProjection = new MacMessage(0, DataUnitID.UNSCRAMBLED_LCCH,
            new CorrectedBinaryMessage(320), 1_000, null)
        {
            @Override
            public DataUnitID getDataUnitID()
            {
                throw new AssertionError("Disabled reporting inspected a control message");
            }
        };
        monitor.receive(forbiddenProjection);
        //The intentionally absent arrays expose any attempt to read sample length while disabled.
        monitor.getComplexSamplesListener().receive(new ComplexSamples(null, null, 1_000));
        assertEquals(0, monitor.getHealth().totals(2_000).good());
        assertEquals(0, monitor.getHealth().totals(2_000).bad());
    }

    @Test
    void monitor_retains_off_rate_and_squelch_state_but_skips_trunked_sample_work()
    {
        Channel channel = new Channel("Conventional demand activation");
        channel.setDecodeConfiguration(new io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM());
        HealthMonitor monitor = new HealthMonitor(channel);
        monitor.getHealth().setCollecting(false, 0);
        monitor.getSourceEventListener().receive(SourceEvent.sampleRateChange(48_000));
        monitor.getSquelchStateListener().receive(SquelchStateEvent.create(SquelchState.UNSQUELCH));
        monitor.getHealth().started(1_000);
        monitor.getHealth().setCollecting(true, 2_000);
        monitor.getComplexSamplesListener().receive(new ComplexSamples(new float[480], new float[480], 2_000));
        ChannelHealth.Totals totals = monitor.getHealth().totals(3_000);
        assertEquals(1_000, totals.runningMs());
        assertEquals(1_000, totals.openMs());
        assertEquals(0, totals.opens());
        assertEquals(10, totals.heardMs());
        monitor.getHealth().setCallsCounted();
        monitor.getComplexSamplesListener().receive(new ComplexSamples(null, null, 3_000));
        assertEquals(10, monitor.getHealth().totals(3_000).heardMs(),
            "Known trunked coverage skips length/duration work entirely");
    }

    @Test
    void reenabled_monitor_counts_lcch_burst_without_reusing_previous_subscription_deduplication()
    {
        Channel channel = new Channel("LCCH reporting generations");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase2());
        HealthMonitor monitor = collectingMonitor(channel, true);
        MacMessage message = new MacMessage(0, DataUnitID.UNSCRAMBLED_LCCH,
            new CorrectedBinaryMessage(320), 1_000, null);
        message.setValid(true);
        monitor.receive(message);
        monitor.getHealth().setCollecting(false, 2_000);
        monitor.getHealth().setCollecting(true, 3_000);
        monitor.receive(message);
        assertEquals(2, monitor.getHealth().totals(4_000).good());
    }

}
