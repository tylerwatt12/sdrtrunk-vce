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

import io.github.dsheirer.protocol.Protocol;
import java.util.Locale;

/**
 * Protocol families for configured voice encryption keys.
 */
public enum VoiceEncryptionProtocol
{
    APCO25("APCO-25"),
    DMR("DMR"),
    NXDN("NXDN");

    private final String mLabel;

    VoiceEncryptionProtocol(String label)
    {
        mLabel = label;
    }

    /**
     * Maps a decoded protocol to its voice-encryption namespace.  P25 phase 1 and phase 2 share the same algorithm IDs.
     *
     * @return matching encryption protocol, or null when the protocol has no supported voice-encryption namespace
     */
    public static VoiceEncryptionProtocol fromProtocol(Protocol protocol)
    {
        if(protocol == null)
        {
            return null;
        }

        return switch(protocol)
        {
            case APCO25, APCO25_PHASE2 -> APCO25;
            case DMR -> DMR;
            case NXDN -> NXDN;
            default -> null;
        };
    }

    /**
     * Maps persisted/API protocol names to their voice-encryption namespace.
     */
    public static VoiceEncryptionProtocol fromProtocolName(String protocol)
    {
        if(protocol == null || protocol.isBlank())
        {
            return null;
        }

        String normalized = protocol.trim().toUpperCase(Locale.ROOT)
            .replace("-", "")
            .replace("_", "")
            .replace(" ", "");

        return switch(normalized)
        {
            case "P25", "P25P1", "P25P2", "P25PHASE1", "P25PHASE2", "APCO25", "APCO25P1", "APCO25P2",
                "APCO25PHASE1", "APCO25PHASE2" -> APCO25;
            case "DMR" -> DMR;
            case "NXDN" -> NXDN;
            default -> null;
        };
    }

    @Override
    public String toString()
    {
        return mLabel;
    }
}
