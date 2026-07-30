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

package io.github.dsheirer.module.decode.p25.reference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EncryptionTest
{
    @Test
    void resolvesAesCbcAndAes128Ofb()
    {
        assertEquals(Encryption.AES_CBC, Encryption.fromValue(0x88));
        assertEquals(Encryption.AES_128_OFB, Encryption.fromValue(0x89));
    }
}
