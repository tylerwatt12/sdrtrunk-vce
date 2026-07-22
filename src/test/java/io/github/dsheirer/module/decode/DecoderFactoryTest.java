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

import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import java.util.List;
import org.junit.jupiter.api.Test;

class DecoderFactoryTest
{
    @Test
    void deepCopiesDmrConfigurationAndFrequencyMap()
    {
        DecodeConfigDMR original = new DecodeConfigDMR();
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
}
