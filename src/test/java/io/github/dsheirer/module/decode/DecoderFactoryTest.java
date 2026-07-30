/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.NXDNChannelMode;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.module.decode.p25.phase1.Modulation;
import java.util.List;
import org.junit.jupiter.api.Test;

class DecoderFactoryTest
{
    @Test
    void deepCopiesDmrConfigurationAndFrequencyMap()
    {
        DecodeConfigDMR original = new DecodeConfigDMR();
        original.setChannelMode(DMRChannelMode.TRUNKED);
        original.setIgnoreDataCalls(false);
        original.setIgnoreCRCChecksums(true);
        original.setUseCompressedTalkgroups(true);
        original.setTrafficChannelPoolSize(31);
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(17);
        mapping.setDownlinkFrequency(452_125_000L);
        mapping.setUplinkFrequency(457_125_000L);
        original.setTimeslotMap(List.of(mapping));

        DecodeConfigDMR copy = (DecodeConfigDMR)DecoderFactory.copy(original);

        assertNotSame(original, copy);
        assertEquals(DMRChannelMode.TRUNKED, copy.getChannelMode());
        assertFalse(copy.getIgnoreDataCalls());
        assertTrue(copy.getIgnoreCRCChecksums());
        assertTrue(copy.isUseCompressedTalkgroups());
        assertEquals(31, copy.getTrafficChannelPoolSize());
        assertEquals(1, copy.getTimeslotMap().size());
        assertNotSame(mapping, copy.getTimeslotMap().get(0));
        assertEquals(452_125_000L, copy.getTimeslotMap().get(0).getDownlinkFrequency());

        copy.getTimeslotMap().get(0).setDownlinkFrequency(460_000_000L);
        assertEquals(452_125_000L, original.getTimeslotMap().get(0).getDownlinkFrequency());
    }

    @Test
    void copiesP25ConventionalModulation()
    {
        DecodeConfigP25Conventional original = new DecodeConfigP25Conventional();
        original.setModulation(Modulation.CQPSK);

        DecodeConfigP25Conventional copy = (DecodeConfigP25Conventional)DecoderFactory.copy(original);

        assertNotSame(original, copy);
        assertEquals(Modulation.CQPSK, copy.getModulation());
    }

    @Test
    void keepsConventionalDmrRotationOnActiveCallsAndData()
    {
        DecodeConfigDMR conventional = new DecodeConfigDMR();
        conventional.setChannelMode(DMRChannelMode.CONVENTIONAL);
        DecodeConfigDMR trunked = new DecodeConfigDMR();
        trunked.setChannelMode(DMRChannelMode.TRUNKED);

        assertEquals(List.of(State.CALL, State.ENCRYPTED, State.DATA),
            DecoderFactory.dmrRotationActiveStates(conventional));
        assertEquals(List.of(State.CONTROL), DecoderFactory.dmrRotationActiveStates(trunked));
    }

    @Test
    void usesInferredLegacyDmrModeForRotationStates()
    {
        DecodeConfigDMR noMap = new DecodeConfigDMR();
        DecodeConfigDMR withMap = new DecodeConfigDMR();
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(1);
        mapping.setDownlinkFrequency(452_125_000L);
        withMap.setTimeslotMap(List.of(mapping));

        assertEquals(List.of(State.CALL, State.ENCRYPTED, State.DATA),
            DecoderFactory.dmrRotationActiveStates(noMap));
        assertEquals(List.of(State.CONTROL), DecoderFactory.dmrRotationActiveStates(withMap));
    }

    @Test
    void copiesNxdnModeAndUsesItForRotationStates()
    {
        DecodeConfigNXDN conventional = new DecodeConfigNXDN();
        conventional.setChannelMode(NXDNChannelMode.CONVENTIONAL);

        DecodeConfigNXDN copy = (DecodeConfigNXDN)DecoderFactory.copy(conventional);

        assertEquals(NXDNChannelMode.CONVENTIONAL, copy.getChannelMode());
        assertEquals(List.of(State.CALL, State.ENCRYPTED, State.DATA),
            DecoderFactory.nxdnRotationActiveStates(copy));
        assertEquals(List.of(State.CONTROL),
            DecoderFactory.nxdnRotationActiveStates(new DecodeConfigNXDN()));
    }
}
