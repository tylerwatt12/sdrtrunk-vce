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

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.preference.encryption.VoiceEncryptionDisplay;

/**
 * Backward-compatible P25 entry point for the protocol-aware voice encryption display formatter.
 *
 * @deprecated use {@link VoiceEncryptionDisplay}
 */
@Deprecated
public final class P25EncryptionDetails
{
    public static final String ADVANCED_P25_ENCRYPTION_STATUS_PROPERTY =
        "now.playing.activity.advanced.p25.encryption.status";

    private P25EncryptionDetails()
    {
    }

    public static String format(IdentifierCollection identifiers)
    {
        return VoiceEncryptionDisplay.format(identifiers);
    }

    public static String format(Identifier<?> identifier)
    {
        return VoiceEncryptionDisplay.format(identifier);
    }
}
