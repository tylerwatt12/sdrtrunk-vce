/* Copyright (C) 2026. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.module.decode.p25.phase1;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.module.decode.p25.P25FrequencyBandPreloadDataContent;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25ExplicitChannel;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.SymbolMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequence;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.AMBTCHeader;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.osp.AMBTCAdjacentStatusBroadcastUncoordinated;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.osp.AMBTCFrequencyBandUpdateTDMA;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.block.UnconfirmedDataBlock;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationStabilizer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class P25P1UncoordinatedAdjacentTest
{
    @Test
    void parsesNeighborScopeAndExplicitChannels() throws Exception
    {
        var adjacent = adjacent(0xABCDE, 0x249, 0x0012, 0x1012, true, 96);
        assertTrue(adjacent.isValid());
        assertEquals(0xABCDE, adjacent.getWacn().getValue());
        assertEquals(0x249, adjacent.getSystem().getValue());
        assertEquals(3, adjacent.getRfss().getValue());
        assertEquals(9, adjacent.getSite().getValue());
        assertEquals(7, adjacent.getLocationRegistrationArea().getValue());
        assertTrue(adjacent.getSystemServiceClass().hasVoiceService());
        assertInstanceOf(APCO25ExplicitChannel.class, adjacent.getChannel(),
            "different band identifiers require explicit channels even when the channel numbers match");
        assertArrayEquals(new int[]{0, 1}, adjacent.getChannel().getFrequencyBandIdentifiers());
        assertTrue(adjacent.getChannels().isEmpty(), "serving-system enrichment must not see neighbor channels");
    }

    @Test
    void handlesUnspecifiedReceiveChannelAndRejectsTruncatedPayloads() throws Exception
    {
        assertFalse(adjacent(0xABCDE, 0x249, 18, 0xFFFF, true, 96).isExtendedChannel());
        var shortMessage = adjacent(0xABCDE, 0x249, 18, 18, true, 59);
        assertFalse(shortMessage.isValid());
        assertNull(shortMessage.getWacn());
        assertNull(shortMessage.getChannel());
        assertTrue(shortMessage.getChannels().isEmpty());
        assertFalse(adjacent(0xABCDE, 0x249, 18, 18, true, 95).isValid());
        assertFalse(adjacent(0xABCDE, 0x249, 18, 18, false, 96).isValid());
        for(int declaredCount: new int[]{0, 2, 127})
        {
            var invalidHeader = header(62);
            invalidHeader.setInt(declaredCount, IntField.range(49, 55));
            var invalidCount = new AMBTCAdjacentStatusBroadcastUncoordinated(
                sequence(invalidHeader, true, new CorrectedBinaryMessage(96)), 0x346, 1_000L);
            assertFalse(invalidCount.isValid(), "this opcode declares exactly one data block");
        }
        var extraBlock = sequence(header(62), true, new CorrectedBinaryMessage(96));
        extraBlock.addDataBlock(new UnconfirmedDataBlock(new SymbolMessage(98)));
        assertFalse(new AMBTCAdjacentStatusBroadcastUncoordinated(extraBlock, 0x346, 1_000L).isValid(),
            "an extra block cannot be retained behind a one-block header");
    }

    @Test
    void servingBandCannotResolveForeignNeighborButMatchingForeignBandCan() throws Exception
    {
        var local = new P25FrequencyBand(0, 851_000_000L, -45_000_000L, 6_250L, 12_500, 1);
        var adjacent = adjacent(0xABCDE, 0x249, 10, 10, true, 96);
        P25P1MessageProcessor processor = new P25P1MessageProcessor();
        processor.preload(new P25FrequencyBandPreloadDataContent(List.of(local)));
        processor.receive(adjacent);
        assertEquals(0L, adjacent.getChannel().getDownlinkFrequency());

        var monitor = new P25P1NetworkConfigurationMonitor(Modulation.C4FM, stabilizedSite());
        addServingBand(monitor, local);
        var unresolved = monitor.process(adjacent).neighborSites().getFirst();
        assertNull(unresolved.downlink());
        assertNull(unresolved.uplink());
        assertEquals(0x249, unresolved.system());
        assertNull(monitor.process(adjacent).currentSite(), "neighbor must never become the serving site");

        monitor.process(foreignBand(0xAAA11, 0x249));
        assertNull(monitor.process(adjacent).neighborSites().getFirst().downlink(),
            "the same system and band number in another WACN cannot supply this plan");
        monitor.process(foreignBand(0xABCDE, 0x249));
        var resolved = monitor.process(adjacent).neighborSites().getFirst();
        assertEquals(770_062_500L, resolved.downlink());
        assertEquals(725_062_500L, resolved.uplink());
        assertTrue(monitor.getSnapshot().channels().isEmpty(), "neighbor channels are not serving controls");
        monitor.reset();
        assertTrue(monitor.getSnapshot().neighborSites().isEmpty());
    }

    @Test
    void zeroWacnUsesTheKnownServingWacnForSameSystemNeighbor() throws Exception
    {
        var monitor = new P25P1NetworkConfigurationMonitor(Modulation.C4FM, stabilizedSite());
        addServingBand(monitor, new P25FrequencyBand(0, 851_000_000L, -45_000_000L, 6_250L, 12_500, 1));
        var neighbor = monitor.process(adjacent(0, 0x348, 10, 10, true, 96)).neighborSites().getFirst();
        assertEquals(851_062_500L, neighbor.downlink());
        assertEquals(806_062_500L, neighbor.uplink());
    }

    @Test
    void boundsRetainedNeighborScopesAndEvictsOldestNewScope() throws Exception
    {
        var monitor = new P25P1NetworkConfigurationMonitor(Modulation.C4FM);
        int capacity = P25P1NetworkConfigurationMonitor.MAX_UNCOORDINATED_NEIGHBOR_SITES;
        for(int index = 0; index < capacity; index++)
        {
            monitor.process(adjacent(0xABCDE, 0x100 + index, 10, 10, true, 96));
        }
        assertEquals(capacity, monitor.getSnapshot().neighborSites().size());

        var replacement = adjacent(0xABCDE, 0x100, 20, 20, true, 96);
        monitor.process(replacement);
        var refreshed = monitor.getSnapshot().neighborSites();
        assertEquals(capacity, refreshed.size(), "repeated scopes replace the retained message");
        assertEquals(replacement.getChannel().toString(), refreshed.getFirst().channel());

        monitor.process(adjacent(0xABCDF, 0x100, 30, 30, true, 96));
        var evicted = monitor.getSnapshot().neighborSites();
        assertEquals(capacity, evicted.size());
        assertEquals(0x101, evicted.getFirst().system(), "the oldest inserted scope is evicted deterministically");
        assertEquals(0x100, evicted.getLast().system(), "another WACN is a distinct scope with the same system/site");
        assertEquals("0-30", evicted.getLast().channel());

        monitor.reset();
        assertTrue(monitor.getSnapshot().neighborSites().isEmpty());
    }

    private static AMBTCAdjacentStatusBroadcastUncoordinated adjacent(int wacn, int system, int transmit,
                                                                      int receive, boolean validHeader,
                                                                      int blockLength) throws Exception
    {
        var header = header(62);
        header.setInt(7, IntField.length8(24));
        header.setInt(system, IntField.length12(36));
        header.setInt(3, IntField.length8(64));
        header.setInt(9, IntField.length8(72));
        var data = new CorrectedBinaryMessage(96);
        data.setInt(transmit, IntField.length16(0));
        data.setInt(receive, IntField.length16(16));
        data.setInt(0x20, IntField.length8(32));
        data.setInt(wacn, IntField.length20(40));
        if(blockLength < 96)
        {
            data = data.getSubMessage(0, blockLength);
        }
        return new AMBTCAdjacentStatusBroadcastUncoordinated(sequence(header, validHeader, data), 0x346, 1_000L);
    }

    private static AMBTCFrequencyBandUpdateTDMA foreignBand(int wacn, int system) throws Exception
    {
        var header = header(51);
        header.setInt(1, IntField.length4(28));
        header.setInt(wacn >>> 4, IntField.length16(32));
        header.setInt(wacn & 15, IntField.length4(64));
        header.setInt(system, IntField.length12(68));
        var data = new CorrectedBinaryMessage(96);
        data.load(0, 32, 770_000_000L / 5);
        data.setInt(7_200, IntField.range(33, 45));
        data.setInt(50, IntField.range(46, 55));
        return new AMBTCFrequencyBandUpdateTDMA(sequence(header, true, data), 0x346, 1_000L);
    }

    private static CorrectedBinaryMessage header(int opcode)
    {
        var header = new CorrectedBinaryMessage(96);
        header.set(2);
        header.setInt(23, IntField.range(3, 7));
        header.setInt(1, IntField.range(49, 55));
        header.setInt(opcode, IntField.length6(58));
        return header;
    }

    private static PDUSequence sequence(CorrectedBinaryMessage header, boolean headerValid,
                                        CorrectedBinaryMessage data) throws Exception
    {
        var sequence = new PDUSequence(new AMBTCHeader(header, headerValid), 1_000L, 0x346);
        var block = new UnconfirmedDataBlock(new SymbolMessage(98));
        Field decodedMessage = UnconfirmedDataBlock.class.getDeclaredField("mDecodedMessage");
        decodedMessage.setAccessible(true);
        decodedMessage.set(block, data);
        sequence.addDataBlock(block);
        return sequence;
    }

    private static void addServingBand(P25P1NetworkConfigurationMonitor monitor, IFrequencyBand band) throws Exception
    {
        Method process = P25P1NetworkConfigurationMonitor.class.getDeclaredMethod("processFrequencyBand", IFrequencyBand.class);
        process.setAccessible(true);
        process.invoke(monitor, band);
        process.invoke(monitor, band);
    }

    private static P25NetworkConfigurationStabilizer stabilizedSite()
    {
        var stabilizer = new P25NetworkConfigurationStabilizer("P25_PHASE_1");
        stabilizer.observe(new P25NetworkConfigurationSnapshot("P25_PHASE_1",
            new P25NetworkConfigurationSnapshot.Network(0xBEE00, 0x348, 0x346, null),
            new P25NetworkConfigurationSnapshot.CurrentSite(0x348, 0x346, 2, 7, null, true),
            List.of(), List.of(), List.of(), List.of(), List.of()), 1_000L);
        return stabilizer;
    }
}
