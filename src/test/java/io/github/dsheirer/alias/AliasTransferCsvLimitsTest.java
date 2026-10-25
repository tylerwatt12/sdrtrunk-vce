/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.alias;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class AliasTransferCsvLimitsTest
{
    private static final String HEADER = String.join(",", AliasTransferCsv.RR_HEADERS) + "\r\n";
    private static final String ROW = "1,1,Dispatch,D,,,\r\n";
    private static final AliasListDefinition LIST = new AliasListDefinition("County", AliasListFamily.P25);

    @Test void acceptsExactlyOneHundredThousandRowsThroughTheStringImportApi()
    {
        String csv = HEADER + ROW.repeat(AliasTransferCsv.MAX_IMPORT_ROWS);
        List<AliasImportService.Input> rows = AliasTransferCsv.read(csv, AliasTransferCsv.Format.RADIOREFERENCE, LIST);
        assertEquals(100_000, rows.size());
        assertEquals("Dispatch", rows.getLast().alias().getName());
    }

    @Test void readerRefusesTheNextRowBeforeReadingAnUnboundedTailAndClosesItsSource()
    {
        String prefix = HEADER + ROW.repeat(AliasTransferCsv.MAX_IMPORT_ROWS + 1);
        String tail = ROW.repeat(2_000);
        CountingReader reader = new CountingReader(prefix + tail);
        var failure = assertThrows(IllegalArgumentException.class,
            () -> AliasTransferCsv.read(reader, AliasTransferCsv.Format.RADIOREFERENCE, LIST));
        assertEquals("CSV must contain at most 100,000 aliases. Split it into smaller files and try again", failure.getMessage());
        assertTrue(reader.consumed <= prefix.length() + 8192, "only the CSV reader's bounded lookahead may pass the limit");
        assertTrue(reader.consumed < prefix.length() + tail.length());
        assertTrue(reader.closed);
    }

    private static final class CountingReader extends FilterReader
    {
        int consumed;
        boolean closed;
        CountingReader(String text) { super(new StringReader(text)); }
        @Override public int read(char[] chars, int offset, int length) throws IOException
        {
            int count = super.read(chars, offset, length);
            if(count > 0) consumed += count;
            return count;
        }
        @Override public int read() throws IOException
        {
            int value = super.read(); if(value >= 0) consumed++; return value;
        }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}
