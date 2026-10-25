/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AliasTransferUploadTest
{
    private static final String BOUNDARY = "test-boundary";
    private static final String TYPE = "multipart/form-data; boundary=\"" + BOUNDARY + "\"";
    private static final String PREFIX = "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"options\"\r\n\r\n";
    private static final String BETWEEN = "\r\n--" + BOUNDARY +
        "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"aliases.csv\"\r\nContent-Type: text/csv\r\n\r\n";
    private static final String END = "\r\n--" + BOUNDARY + "--\r\n";

    @Test void preservesCsvQuotesEmbeddedNewlinesUnicodeAndBoundaryPrefixesAndClosesSource() throws Exception
    {
        String csv = "name,description\r\n\"Départ\",\"first\r\n--test-boundary-prefix\r\nlast\"\r\n";
        var source = new TrackedInput(PREFIX + "{\"action\":\"preview\"}" + BETWEEN + csv + END);
        try(var upload = new AliasTransferUpload(source, TYPE, 1024))
        {
            assertEquals("{\"action\":\"preview\"}", new String(upload.options(), StandardCharsets.UTF_8));
            assertEquals(csv, new String(upload.csv().readAllBytes(), StandardCharsets.UTF_8));
            upload.finish();
        }
        assertTrue(source.closed);
    }

    @Test void acceptsFinalBoundaryWithoutTrailingCrLf() throws Exception
    {
        try(var upload = new AliasTransferUpload(new TrackedInput(PREFIX + "{}" + BETWEEN + "csv" + END.stripTrailing()), TYPE, 3))
        {
            assertEquals("csv", new String(upload.csv().readAllBytes(), StandardCharsets.UTF_8));
            upload.finish();
        }
    }

    @Test void countsUtf8BytesForBothPartsAndRejectsOneByteOverLimit() throws Exception
    {
        try(var upload = new AliasTransferUpload(new TrackedInput(PREFIX + "é" + BETWEEN + "é" + END), TYPE, 2, 2))
        {
            assertEquals(2, upload.options().length);
            assertEquals(2, upload.csv().readAllBytes().length);
            upload.finish();
        }
        assertThrows(AliasTransferUpload.TooLargeException.class, () ->
            new AliasTransferUpload(new TrackedInput(PREFIX + "é" + BETWEEN + "csv" + END), TYPE, 1, 3));
        try(var upload = new AliasTransferUpload(new TrackedInput(PREFIX + "{}" + BETWEEN + "é" + END), TYPE, 2, 1))
        {
            assertThrows(AliasTransferUpload.TooLargeException.class, () -> upload.csv().readAllBytes());
        }
    }

    @Test void rejectsMissingOrAmbiguousBoundaryWrongPartsTruncationExtraPartsAndTrailingData() throws Exception
    {
        for(String type: new String[]{"multipart/form-data", TYPE + "; boundary=other", "multipart/form-data; boundary=\"bad;boundary\""})
            assertThrows(IOException.class, () -> new AliasTransferUpload(new TrackedInput(""), type, 64));
        for(String body: new String[]{PREFIX.replace("name=\"options\"", "name=\"file\"") + "{}" + BETWEEN + "csv" + END,
            PREFIX + "{}", PREFIX + "{}" + BETWEEN + "csv", PREFIX + "{}" + BETWEEN + "csv" + END + "unexpected",
            PREFIX + "{}" + BETWEEN + "csv" + BETWEEN + "extra" + END})
        {
            var source = new TrackedInput(body);
            assertThrows(IOException.class, () -> {
                try(var upload = new AliasTransferUpload(source, TYPE, 64))
                { upload.csv().readAllBytes(); upload.finish(); }
            });
            assertTrue(source.closed);
        }
    }

    private static final class TrackedInput extends ByteArrayInputStream
    {
        boolean closed;
        TrackedInput(String text) { super(text.getBytes(StandardCharsets.UTF_8)); }
        @Override public void close() { closed = true; }
    }
}
