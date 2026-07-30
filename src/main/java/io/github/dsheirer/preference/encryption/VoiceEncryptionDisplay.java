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

package io.github.dsheirer.preference.encryption;

import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.encryption.EncryptionKey;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.protocol.Protocol;

/**
 * Protocol-aware encryption names for user-facing activity displays.  Raw decoder diagnostics retain their existing
 * protocol-specific strings; this formatter supplies one compact vocabulary for P25, DMR and NXDN user interfaces.
 */
public final class VoiceEncryptionDisplay
{
    public static final String ENCRYPTED = "ENC";

    private VoiceEncryptionDisplay()
    {
    }

    /**
     * Formats the first encrypted key identifier in a collection.
     *
     * @return compact encryption details, or null when no encrypted identifier is present
     */
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

    /**
     * Formats an encrypted key identifier.
     *
     * @return compact encryption details, or null when the identifier does not describe encryption
     */
    public static String format(Identifier<?> identifier)
    {
        if(identifier instanceof EncryptionKeyIdentifier encryptionKeyIdentifier &&
            encryptionKeyIdentifier.isEncrypted())
        {
            EncryptionKey encryptionKey = encryptionKeyIdentifier.getValue();
            return format(encryptionKeyIdentifier.getProtocol(), encryptionKey.getAlgorithm(), encryptionKey.getKey());
        }

        return null;
    }

    /**
     * Formats raw encrypted-call fields with a compact algorithm token.  A null algorithm means that encryption was
     * observed without usable algorithm details.
     */
    public static String format(Protocol protocol, Integer algorithm, Integer key)
    {
        return format(VoiceEncryptionProtocol.fromProtocol(protocol), algorithm, key);
    }

    /**
     * Formats raw encrypted-call fields with a compact algorithm token.
     */
    public static String format(VoiceEncryptionProtocol protocol, Integer algorithm, Integer key)
    {
        return withKey(compactAlgorithm(protocol, algorithm), key);
    }

    /**
     * Formats raw encrypted-call fields with the full algorithm name.
     */
    public static String formatFull(Protocol protocol, Integer algorithm, Integer key)
    {
        return formatFull(VoiceEncryptionProtocol.fromProtocol(protocol), algorithm, key);
    }

    /**
     * Formats raw encrypted-call fields with the full algorithm name.
     */
    public static String formatFull(VoiceEncryptionProtocol protocol, Integer algorithm, Integer key)
    {
        return withKey(fullAlgorithm(protocol, algorithm), key);
    }

    /**
     * Compact algorithm token without key information.
     */
    public static String compactAlgorithm(Protocol protocol, Integer algorithm)
    {
        return compactAlgorithm(VoiceEncryptionProtocol.fromProtocol(protocol), algorithm);
    }

    /**
     * Compact algorithm token without key information.
     */
    public static String compactAlgorithm(VoiceEncryptionProtocol protocol, Integer algorithm)
    {
        if(algorithm == null)
        {
            return ENCRYPTED;
        }

        return VoiceEncryptionAlgorithm.getCompactLabel(protocol, algorithm);
    }

    /**
     * Full algorithm name without key information.
     */
    public static String fullAlgorithm(Protocol protocol, Integer algorithm)
    {
        return fullAlgorithm(VoiceEncryptionProtocol.fromProtocol(protocol), algorithm);
    }

    /**
     * Full algorithm name without key information.
     */
    public static String fullAlgorithm(VoiceEncryptionProtocol protocol, Integer algorithm)
    {
        if(algorithm == null)
        {
            return ENCRYPTED;
        }

        return VoiceEncryptionAlgorithm.getFullLabel(protocol, algorithm);
    }

    private static String withKey(String algorithm, Integer key)
    {
        if(ENCRYPTED.equals(algorithm) || key == null)
        {
            return algorithm;
        }

        return algorithm + " K:" + Integer.toHexString(key).toUpperCase();
    }
}
