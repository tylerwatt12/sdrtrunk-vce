/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.playback;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import java.util.List;

/**
 * Immutable display-safe view of one call in the shared local playback session.
 */
public record AudioPlaybackCall(String callId, String output, String system, String sourceId, String sourceAlias,
                                String targetId, String targetAlias, long frequencyHz, int timeslot,
                                boolean encrypted, int priority)
{
    static AudioPlaybackCall from(String output, PlayableAudioCall audioCall)
    {
        if(audioCall == null || audioCall.callId() == null)
        {
            return null;
        }

        AudioCallSnapshot snapshot = audioCall.snapshot();
        IdentifierCollection identifiers = audioCall.getIdentifierCollection();
        Identifier<?> source = identifiers != null ? identifiers.getFromIdentifier() : null;
        Identifier<?> target = identifiers != null ? identifiers.getToIdentifier() : null;
        Identifier<?> system = identifiers != null ? identifiers.getIdentifier(IdentifierClass.CONFIGURATION,
            Form.SYSTEM, Role.ANY) : null;
        Identifier<?> frequency = identifiers != null ? identifiers.getIdentifier(IdentifierClass.CONFIGURATION,
            Form.CHANNEL_FREQUENCY, Role.ANY) : null;
        AliasList aliasList = snapshot != null ? snapshot.aliasList() : null;

        return new AudioPlaybackCall(audioCall.callId().toString(), output, value(system), value(source),
            alias(aliasList, source), value(target), alias(aliasList, target), longValue(frequency),
            snapshot != null ? snapshot.timeslot() : 0, audioCall.isEncrypted(), audioCall.getMonitorPriority());
    }

    private static String value(Identifier<?> identifier)
    {
        Object value = identifier != null ? identifier.getValue() : null;
        return value != null ? value.toString() : null;
    }

    private static long longValue(Identifier<?> identifier)
    {
        Object value = identifier != null ? identifier.getValue() : null;

        if(value instanceof Number number)
        {
            return number.longValue();
        }

        try
        {
            return value != null ? Long.parseLong(value.toString()) : 0;
        }
        catch(NumberFormatException e)
        {
            return 0;
        }
    }

    private static String alias(AliasList aliasList, Identifier<?> identifier)
    {
        if(aliasList != null && identifier != null)
        {
            List<Alias> aliases = aliasList.getAliases(identifier);

            if(!aliases.isEmpty())
            {
                return aliases.getFirst().getName();
            }
        }

        return null;
    }
}
