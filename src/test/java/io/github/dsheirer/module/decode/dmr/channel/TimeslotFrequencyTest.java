/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.module.decode.dmr.channel;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.junit.jupiter.api.Test;

class TimeslotFrequencyTest
{
    @Test
    void plainFieldsPreserveLegacyXmlAndCopyBehavior() throws Exception
    {
        XmlMapper mapper = new XmlMapper();
        TimeslotFrequency original = mapper.readValue(
            "<TimeslotFrequency lsn=\"3\" downlink=\"451012500\" uplink=\"456012500\"/>", TimeslotFrequency.class);
        assertEquals(3, original.getNumber());
        assertEquals(451_012_500L, original.getDownlinkFrequency());
        assertEquals(456_012_500L, original.getUplinkFrequency());
        assertEquals("LSN:5/6 or Tier3 Chan:7/8", original.getDescription());
        TimeslotFrequency copy = original.copy();
        copy.setNumber(4);
        copy.setDownlinkFrequency(452_012_500L);
        assertEquals(3, original.getNumber());
        assertEquals(451_012_500L, original.getDownlinkFrequency());
        String xml = mapper.writeValueAsString(original);
        assertTrue(xml.contains("lsn=\"3\""));
        assertFalse(xml.contains("Property"));
        assertFalse(xml.contains("MHz"));
        assertEquals(original.toString(), mapper.readValue(xml, TimeslotFrequency.class).toString());
    }
}
