/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ApiRequestDecoderTest
{
    @Test
    void decodesUtf8OnceAndKeepsPathPlusLiteral()
    {
        assertEquals("listener", ApiRequestDecoder.decodeComponent("%6cistener", false));
        assertEquals("a+b", ApiRequestDecoder.decodeComponent("a+b", false));
        assertEquals("a b", ApiRequestDecoder.decodeComponent("a+b", true));
        assertEquals("%61dmin", ApiRequestDecoder.decodeComponent("%2561dmin", false));
    }

    @Test
    void rejectsMalformedPercentUtf8AndControls()
    {
        assertThrows(IllegalArgumentException.class,
            () -> ApiRequestDecoder.decodeComponent("%", false));
        assertThrows(IllegalArgumentException.class,
            () -> ApiRequestDecoder.decodeComponent("%GG", false));
        assertThrows(IllegalArgumentException.class,
            () -> ApiRequestDecoder.decodeComponent("%C3%28", false));
        assertThrows(IllegalArgumentException.class,
            () -> ApiRequestDecoder.decodeComponent("%00", false));
    }
}
