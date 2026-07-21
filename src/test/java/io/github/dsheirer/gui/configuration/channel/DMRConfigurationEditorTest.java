/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.gui.configuration.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import java.util.List;
import org.junit.jupiter.api.Test;

class DMRConfigurationEditorTest
{
    @Test
    void parsesSpreadsheetAndCopiedMapFormats()
    {
        List<TimeslotFrequency> mappings = DMRConfigurationEditor.parseTimeslotMap("""
            LCN\tDownlink\tUplink
            2\t452.025\t457.025
            1,452012500,0
            """);

        assertEquals(2, mappings.size());
        assertEquals(1, mappings.get(0).getNumber());
        assertEquals(452_012_500L, mappings.get(0).getDownlinkFrequency());
        assertEquals(2, mappings.get(1).getNumber());
        assertEquals(452_025_000L, mappings.get(1).getDownlinkFrequency());
        assertEquals(457_025_000L, mappings.get(1).getUplinkFrequency());
        assertEquals("1\t452012500\t0" + System.lineSeparator() + "2\t452025000\t457025000",
            DMRConfigurationEditor.formatTimeslotMap(mappings));
    }

    @Test
    void rejectsDuplicateLogicalChannelNumbersWithoutReturningPartialData()
    {
        assertThrows(IllegalArgumentException.class,
            () -> DMRConfigurationEditor.parseTimeslotMap("1\t452.1\n1\t452.2"));
    }
}
