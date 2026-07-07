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

package io.github.dsheirer.audio.playback;

import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;

/**
 * Session-local playback target for hold and avoid controls.
 */
record PlaybackTarget(String system, IdentifierKey identifier)
{
    private static final Form[] FALLBACK_FORMS = {
        Form.CHANNEL_NAME,
        Form.CHANNEL_FREQUENCY,
        Form.CHANNEL_DESCRIPTOR
    };

    static PlaybackTarget from(PlayableAudioCall audioCall)
    {
        return audioCall != null ? from(audioCall.getIdentifierCollection()) : null;
    }

    static PlaybackTarget from(IdentifierCollection identifierCollection)
    {
        if(identifierCollection == null || identifierCollection.isEmpty())
        {
            return null;
        }

        Identifier<?> target = identifierCollection.getToIdentifier();

        if(target == null)
        {
            target = getFallbackIdentifier(identifierCollection);
        }

        if(target == null)
        {
            return null;
        }

        Identifier<?> systemIdentifier = identifierCollection.getIdentifier(IdentifierClass.CONFIGURATION,
            Form.SYSTEM, Role.ANY);
        String system = systemIdentifier != null ? value(systemIdentifier) : null;

        return new PlaybackTarget(system, IdentifierKey.from(target));
    }

    String label()
    {
        if(system == null || system.isBlank())
        {
            return identifier.label();
        }

        return system + " / " + identifier.label();
    }

    private static Identifier<?> getFallbackIdentifier(IdentifierCollection identifierCollection)
    {
        for(Form form: FALLBACK_FORMS)
        {
            Identifier<?> identifier = identifierCollection.getIdentifier(IdentifierClass.CONFIGURATION, form, Role.ANY);

            if(identifier != null)
            {
                return identifier;
            }
        }

        return null;
    }

    private static String value(Identifier<?> identifier)
    {
        Object value = identifier != null ? identifier.getValue() : null;
        return value != null ? value.toString() : "";
    }

    record IdentifierKey(IdentifierClass identifierClass, Form form, Role role, String value)
    {
        static IdentifierKey from(Identifier<?> identifier)
        {
            return new IdentifierKey(identifier.getIdentifierClass(), identifier.getForm(), identifier.getRole(),
                PlaybackTarget.value(identifier));
        }

        String label()
        {
            return form + ":" + value;
        }
    }
}
