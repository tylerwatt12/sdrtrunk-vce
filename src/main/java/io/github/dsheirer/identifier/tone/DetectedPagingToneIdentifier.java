/*
 * *****************************************************************************
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.identifier.tone;

import io.github.dsheirer.audio.tone.P25PagingToneDetector.DetectedTone;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Inferred continuous PCM tones, deliberately separate from the codec-provided AMBE ToneIdentifier type. */
public final class DetectedPagingToneIdentifier extends Identifier<List<DetectedTone>>
{
    public DetectedPagingToneIdentifier(List<DetectedTone> tones)
    {
        super(List.copyOf(tones), IdentifierClass.USER, Form.TONE, Role.FROM);

        if(tones.isEmpty()) throw new IllegalArgumentException("Detected tones are required");
    }

    @Override
    public Protocol getProtocol()
    {
        return Protocol.APCO25;
    }

    @Override
    public String toString()
    {
        return getValue().stream().map(tone -> String.format(Locale.ROOT, "%.1f Hz (%d ms)",
            tone.frequencyHz(), tone.durationMilliseconds())).collect(Collectors.joining(", "));
    }
}
