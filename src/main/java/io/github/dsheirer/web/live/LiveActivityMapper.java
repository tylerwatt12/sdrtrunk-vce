/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.live;

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.StuffBitsMessage;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts decoder-owned objects to small immutable DTOs on a web worker.  Callers must not invoke these methods on a
 * tuner, sample, decoder, audio, or recording callback thread.
 */
public final class LiveActivityMapper
{
    static final int MAXIMUM_IDENTIFIERS = 24;
    static final int MAXIMUM_DETAILS_CHARACTERS = 512;
    static final int MAXIMUM_MESSAGE_CHARACTERS = 1_024;
    private static final int MAXIMUM_CHANNEL_CHARACTERS = 120;

    private LiveActivityMapper()
    {
    }

    public static LiveDecodeEventDto event(String id, long generation, IDecodeEvent event)
    {
        if(event == null)
        {
            return null;
        }

        DecodeEventType type = event.getEventType();
        IdentifierCollection collection = event.getIdentifierCollection();
        List<Identifier> identifiers = collection != null ? collection.getIdentifiers() : List.of();
        IChannelDescriptor descriptor = event.getChannelDescriptor();
        long frequency = descriptor != null ? Math.max(0, descriptor.getDownlinkFrequency()) : 0;
        Integer timeslot = event.hasTimeslot() ? canonicalTimeslot(event.getTimeslot()) : null;
        String channel = descriptor != null ? LiveText.normalize(descriptor.toString(), MAXIMUM_CHANNEL_CHARACTERS) : "";

        return new LiveDecodeEventDto(id, generation, event.getTimeStart(), Math.max(0, event.getDuration()),
            type != null ? type.name() : "UNKNOWN", type != null ? type.getLabel() : "Unknown",
            eventCategory(type), event.getProtocol() != null ? event.getProtocol().toString() : "Unknown",
            identifiers(identifiers, Role.FROM), identifiers(identifiers, Role.TO), channel, frequency, timeslot,
            LiveText.normalize(event.getDetails(), MAXIMUM_DETAILS_CHARACTERS));
    }

    public static LiveMessageDto message(String id, long generation, long sequence, IMessage message)
    {
        if(message == null || message instanceof StuffBitsMessage)
        {
            return null;
        }

        String protocol = message.getProtocol() != null ? message.getProtocol().toString() : "Unknown";
        String text;

        try
        {
            text = LiveText.normalize(message.toString(), MAXIMUM_MESSAGE_CHARACTERS);
        }
        catch(RuntimeException exception)
        {
            text = "Message text unavailable";
        }

        return new LiveMessageDto(id, generation, sequence, message.getTimestamp(), message.isValid(), protocol,
            canonicalMessageTimeslot(message.getTimeslot()), message.getClass().getSimpleName(), text,
            identifiers(message.getIdentifiers(), null));
    }

    /**
     * Decoder messages use zero to mean that no timeslot applies and otherwise expose the displayed one-based slot.
     */
    public static Integer canonicalMessageTimeslot(int timeslot)
    {
        return timeslot > 0 ? timeslot : null;
    }

    private static int canonicalTimeslot(int timeslot)
    {
        return Math.max(0, timeslot);
    }

    private static List<LiveIdentifierDto> identifiers(List<? extends Identifier> source, Role role)
    {
        if(source == null || source.isEmpty())
        {
            return List.of();
        }

        List<LiveIdentifierDto> result = new ArrayList<>(Math.min(source.size(), MAXIMUM_IDENTIFIERS));

        for(Identifier<?> identifier: source)
        {
            if(identifier != null && (role == null || identifier.getRole() == role))
            {
                LiveIdentifierDto dto = LiveIdentifierDto.from(identifier);

                if(dto != null)
                {
                    result.add(dto);
                }

                if(result.size() >= MAXIMUM_IDENTIFIERS)
                {
                    break;
                }
            }
        }

        return List.copyOf(result);
    }

    private static String eventCategory(DecodeEventType type)
    {
        if(type == null)
        {
            return "other";
        }

        if(DecodeEventType.VOICE_CALLS.contains(type))
        {
            return "voice";
        }
        else if(DecodeEventType.VOICE_CALLS_ENCRYPTED.contains(type))
        {
            return "protected-voice";
        }
        else if(DecodeEventType.DATA_CALLS.contains(type))
        {
            return "data";
        }
        else if(DecodeEventType.COMMANDS.contains(type))
        {
            return "commands";
        }
        else if(DecodeEventType.REGISTRATION.contains(type))
        {
            return "registrations";
        }

        return "other";
    }
}
