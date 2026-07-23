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
                algorithm = abbreviation(encryption);
            }
        }

        return algorithm + " K:" + toHex(encryptionKey.getKey());
    }

    private static String abbreviation(Encryption encryption)
    {
        return switch(encryption)
        {
            case ACCORDION_3 -> "ACRD3";
            case BATON_AUTO_EVEN -> "BAT-E";
            case FIREFLY_TYPE1 -> "FIREF";
            case MAYFLY_TYPE1 -> "MAYFL";
            case SAVILLE -> "SAVIL";
            case MOTOROLA_PADSTONE -> "PADSTN";
            case BATON_AUTO_ODD -> "BAT-O";
            case DES_OFB -> "DESOFB";
            case TRIPLE_DES_2_KEY -> "3DES2";
            case TRIPLE_DES_3_KEY -> "3DES3";
            case AES_256 -> "AES256";
            case AES_128 -> "AES128";
            case AES_CBC -> "AESCBC";
            case AES_128_OFB -> "A128OF";
            case DES_XL -> "DESXL";
            case DVI_XL -> "DVIXL";
            case DVP_XL -> "DVPXL";
            case DVP_SPFL -> "DVPSPF";
            case HAYSTACK -> "HAYSTK";
            case MOTOROLA_A4 -> "MOT-A4";
            case MOTOROLA_A5 -> "MOT-A5";
            case MOTOROLA_A6 -> "MOT-A6";
            case MOTOROLA_A7 -> "MOT-A7";
            case MOTOROLA_A8 -> "MOT-A8";
            case MOTOROLA_A9 -> "MOT-A9";
            case MOTOROLA_ADP -> "ADP";
            case MOTOROLA_AB -> "CFX256";
            case MOTOROLA_AC -> "MOT-AC";
            case MOTOROLA_AD -> "MOT-AD";
            case MOTOROLA_AE -> "MOT-AE";
            case MOTOROLA_AF -> "A256GM";
            case MOTOROLA_B0 -> "DVPB0";
            case UNENCRYPTED, UNKNOWN -> "ALG:" + toHex(encryption.getValue(), 2);
        };
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
