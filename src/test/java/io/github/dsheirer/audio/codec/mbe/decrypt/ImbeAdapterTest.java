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

package io.github.dsheirer.audio.codec.mbe.decrypt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ImbeAdapterTest
{
    @Test
    void decodesSdrtrunkTransmittedImbeToPackedParameters() throws Exception
    {
        byte[] transmitted = hex("751A1E8BFFF7BA782328B33789A23B9F833D");

        assertEquals("7FAC5FE5EBA4CEFE923858", hex(ImbeAdapter.decode18ToPacked11(transmitted)));
    }

    @Test
    void encodesPackedParametersToTransmittedImbe() throws Exception
    {
        byte[] packed = hex("040CFD7BFB7DF37B399E4C");
        byte[] transmitted = ImbeAdapter.encodePacked11To18(packed);

        assertEquals("6862E95DE2E82E83E3D981F9BE23B18EF004", hex(transmitted));
        assertArrayEquals(packed, ImbeAdapter.decode18ToPacked11(transmitted));
    }

    private static byte[] hex(String value)
    {
        byte[] bytes = new byte[value.length() / 2];

        for(int x = 0; x < bytes.length; x++)
        {
            bytes[x] = (byte)Integer.parseInt(value.substring(x * 2, (x * 2) + 2), 16);
        }

        return bytes;
    }

    private static String hex(byte[] bytes)
    {
        StringBuilder sb = new StringBuilder();

        for(byte value: bytes)
        {
            sb.append(String.format("%02X", value & 0xFF));
        }

        return sb.toString();
    }
}
