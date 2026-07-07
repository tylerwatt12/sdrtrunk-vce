/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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
package io.github.dsheirer.module.decode.p25;

import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.encryption.EncryptionKey;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.encryption.APCO25EncryptionKey;
import io.github.dsheirer.module.decode.p25.reference.Encryption;

/**
 * Formats P25 encryption metadata for compact Now Playing display.
 */
public final class P25EncryptionDetails
{
    public static final String ADVANCED_P25_ENCRYPTION_STATUS_PROPERTY =
        "now.playing.activity.advanced.p25.encryption.status";

    private P25EncryptionDetails()
    {
    }

    public static String format(IdentifierCollection identifiers)
    {
        if(identifiers != null)
        {
            for(Identifier<?> identifier: identifiers.getIdentifiers(Form.ENCRYPTION_KEY))
            {
                String details = format(identifier);

                if(details != null)
                {
                    return details;
                }
            }
        }

        return null;
    }

    public static String format(Identifier<?> identifier)
    {
        if(identifier instanceof EncryptionKeyIdentifier encryptionKeyIdentifier &&
            encryptionKeyIdentifier.isEncrypted())
        {
            return format(encryptionKeyIdentifier.getValue());
        }

        return null;
    }

    private static String format(EncryptionKey encryptionKey)
    {
        if(encryptionKey == null || !encryptionKey.isEncrypted())
        {
            return null;
        }

        String algorithm = "ALG:" + toHex(encryptionKey.getAlgorithm(), 2);

        if(encryptionKey instanceof APCO25EncryptionKey apco25EncryptionKey)
        {
            Encryption encryption = apco25EncryptionKey.getEncryptionAlgorithm();

            if(encryption != Encryption.UNKNOWN)
            {
                algorithm = encryption.toString();
            }
        }

        return algorithm + " K:" + toHex(encryptionKey.getKey());
    }

    private static String toHex(int value)
    {
        return Integer.toHexString(value).toUpperCase();
    }

    private static String toHex(int value, int width)
    {
        return String.format("%0" + width + "X", value);
    }
}
