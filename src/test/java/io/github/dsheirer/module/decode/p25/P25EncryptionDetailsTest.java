/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.p25;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.encryption.APCO25EncryptionKey;
import io.github.dsheirer.module.decode.p25.reference.Encryption;
import org.junit.jupiter.api.Test;

class P25EncryptionDetailsTest
{
    @Test
    void abbreviatesMotorolaAdp()
    {
        assertEquals("ADP K:2A", format(Encryption.MOTOROLA_ADP));
    }

    @Test
    void limitsKnownAlgorithmLabelsToSixCharacters()
    {
        for(Encryption encryption: Encryption.values())
        {
            if(encryption != Encryption.UNENCRYPTED && encryption != Encryption.UNKNOWN)
            {
                String algorithm = format(encryption).split(" K:", 2)[0];
                assertTrue(algorithm.length() <= 6, () -> encryption + " formatted as " + algorithm);
            }
        }
    }

    private static String format(Encryption encryption)
    {
        return P25EncryptionDetails.format(EncryptionKeyIdentifier.create(
            APCO25EncryptionKey.create(encryption, 0x2A)));
    }
}
