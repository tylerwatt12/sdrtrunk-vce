/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.audio.call.AudioCallId;
import org.junit.jupiter.api.Test;

class RecordedCallCatalogTokensTest
{
    @Test
    void publicIdAndCursorAreStableBoundedAndChecksumProtected()
    {
        AudioCallId runtimeId = new AudioCallId(123, 456, 1);
        long completedAt = 1_721_754_129_876L;
        String publicId = RecordedCallCatalogTokens.callId(completedAt, runtimeId);
        String same = RecordedCallCatalogTokens.callId(completedAt, runtimeId);
        String afterRestart = RecordedCallCatalogTokens.callId(completedAt + 1, runtimeId);

        assertEquals(publicId, same);
        assertNotEquals(publicId, afterRestart);
        assertEquals(completedAt, RecordedCallCatalogTokens.parseCallId(publicId).completedAtMs());
        assertEquals(runtimeId, RecordedCallCatalogTokens.parseCallId(publicId).callId());

        String cursor = RecordedCallCatalogTokens.cursor(completedAt, runtimeId);
        assertEquals(completedAt, RecordedCallCatalogTokens.parseCursor(cursor).completedAtMs());
        assertEquals(runtimeId, RecordedCallCatalogTokens.parseCursor(cursor).callId());
        assertThrows(IllegalArgumentException.class,
            () -> RecordedCallCatalogTokens.parseCallId(tamper(publicId)));
        assertThrows(IllegalArgumentException.class,
            () -> new RecordedCallCatalogSearch.Cursor(tamper(cursor)));
    }

    private static String tamper(String token)
    {
        char replacement = token.endsWith("A") ? 'B' : 'A';
        return token.substring(0, token.length() - 1) + replacement;
    }
}
