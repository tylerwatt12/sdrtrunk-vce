/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
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

package io.github.dsheirer.alias.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioFormat;
import io.github.dsheirer.alias.id.radio.RadioFormatter;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupFormat;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupFormatter;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.protocol.Protocol;
import java.text.ParseException;
import org.junit.jupiter.api.Test;

class AliasIdentifierFormatTest
{
    @Test
    void mapsNxdnTalkgroupBounds()
    {
        TalkgroupFormat format = TalkgroupFormat.get(Protocol.NXDN);

        assertSame(TalkgroupFormat.NXDN, format);
        assertEquals(0, format.getMinimumValidValue());
        assertEquals(65_535, format.getMaximumValidValue());
        assertFalse(format.isValid(-1));
        assertTrue(format.isValid(0));
        assertTrue(format.isValid(1));
        assertTrue(format.isValid(65_535));
        assertFalse(format.isValid(65_536));

        assertFalse(new Talkgroup(Protocol.NXDN, -1).isValid());
        assertTrue(new Talkgroup(Protocol.NXDN, 0).isValid());
        assertTrue(new Talkgroup(Protocol.NXDN, 1).isValid());
        assertTrue(new TalkgroupRange(Protocol.NXDN, 0, 65_535).isValid());
        assertTrue(new TalkgroupRange(Protocol.NXDN, 0, 1).isValid());
        assertFalse(new TalkgroupRange(Protocol.NXDN, -1, 1).isValid());
        assertFalse(new TalkgroupRange(Protocol.NXDN, 1, 65_536).isValid());
    }

    @Test
    void mapsNxdnRadioBounds()
    {
        RadioFormat format = RadioFormat.get(Protocol.NXDN);

        assertSame(RadioFormat.NXDN, format);
        assertEquals(1, format.getMinimumValidValue());
        assertEquals(65_535, format.getMaximumValidValue());
        assertFalse(format.isValid(0));
        assertTrue(format.isValid(1));
        assertTrue(format.isValid(65_535));
        assertFalse(format.isValid(65_536));

        assertFalse(new Radio(Protocol.NXDN, 0).isValid());
        assertTrue(new Radio(Protocol.NXDN, 1).isValid());
        assertTrue(new RadioRange(Protocol.NXDN, 1, 65_535).isValid());
        assertFalse(new RadioRange(Protocol.NXDN, 0, 1).isValid());
        assertFalse(new RadioRange(Protocol.NXDN, 1, 65_536).isValid());
    }

    @Test
    void mapsNxdnFormatters()
    {
        assertEquals("65535", TalkgroupFormatter.format(Protocol.NXDN, 65_535));
        assertEquals("65535", RadioFormatter.format(Protocol.NXDN, 65_535));
    }

    @Test
    void preservesDecimalDefaultsAndParsingForEveryProtocol() throws ParseException
    {
        for(Protocol protocol: Protocol.values())
        {
            assertEquals("12345", RadioFormatter.format(protocol, 12_345), protocol.name());
            assertEquals(12_345, RadioFormatter.parse(protocol, " 12345 "), protocol.name());
            assertEquals(Integer.MAX_VALUE, RadioFormatter.parse(protocol, "2147483647"), protocol.name());
            assertEquals(Integer.MIN_VALUE, RadioFormatter.parse(protocol, "-2147483648"), protocol.name());
            assertThrows(ParseException.class, () -> RadioFormatter.parse(protocol, "0x3039"), protocol.name());
            assertThrows(ParseException.class, () -> RadioFormatter.parse(protocol, "2147483648"), protocol.name());

            if(protocol != Protocol.FLEETSYNC)
            {
                assertEquals("12345", TalkgroupFormatter.format(protocol, 12_345), protocol.name());
                assertEquals(12_345, TalkgroupFormatter.parse(protocol, " 12345 "), protocol.name());
                assertEquals(Integer.MAX_VALUE, TalkgroupFormatter.parse(protocol, "2147483647"), protocol.name());
                assertEquals(Integer.MIN_VALUE, TalkgroupFormatter.parse(protocol, "-2147483648"), protocol.name());
                assertThrows(ParseException.class, () -> TalkgroupFormatter.parse(protocol, "0x3039"), protocol.name());
                assertThrows(ParseException.class, () -> TalkgroupFormatter.parse(protocol, "2147483648"), protocol.name());
            }
        }
    }

    @Test
    void preservesFleetsyncStructuredDefaultsAndBoundaries() throws ParseException
    {
        int minimum = (1 << 13) + 1;
        int maximum = (127 << 13) + 8191;
        assertEquals("001-0001", TalkgroupFormatter.format(Protocol.FLEETSYNC, minimum));
        assertEquals("127-8191", TalkgroupFormatter.format(Protocol.FLEETSYNC, maximum));
        assertEquals(minimum, TalkgroupFormatter.parse(Protocol.FLEETSYNC, "1-1"));
        assertEquals(minimum, TalkgroupFormatter.parse(Protocol.FLEETSYNC, "001-0001"));
        assertEquals(maximum, TalkgroupFormatter.parse(Protocol.FLEETSYNC, "127-8191"));

        for(String invalid: new String[]{"0-1", "128-1", "1-0", "1-8192", "8193", " 1-1 ", null})
        {
            assertThrows(ParseException.class, () -> TalkgroupFormatter.parse(Protocol.FLEETSYNC, invalid));
        }
    }

    @Test
    void acceptsDmrTalkgroupZero()
    {
        TalkgroupFormat format = TalkgroupFormat.get(Protocol.DMR);

        assertSame(TalkgroupFormat.DMR, format);
        assertTrue(format.isValid(0));
    }

    @Test
    void preservesApco25Bounds()
    {
        TalkgroupFormat talkgroupFormat = TalkgroupFormat.get(Protocol.APCO25);
        RadioFormat radioFormat = RadioFormat.get(Protocol.APCO25);

        assertSame(TalkgroupFormat.APCO25, talkgroupFormat);
        assertEquals(0, talkgroupFormat.getMinimumValidValue());
        assertEquals(65_535, talkgroupFormat.getMaximumValidValue());
        assertTrue(talkgroupFormat.isValid(0));
        assertTrue(talkgroupFormat.isValid(65_535));
        assertFalse(talkgroupFormat.isValid(65_536));

        assertSame(RadioFormat.APCO25, radioFormat);
        assertEquals(0, radioFormat.getMinimumValidValue());
        assertEquals(16_777_215, radioFormat.getMaximumValidValue());
        assertTrue(radioFormat.isValid(0));
        assertTrue(radioFormat.isValid(16_777_215));
        assertFalse(radioFormat.isValid(16_777_216));
    }
}
