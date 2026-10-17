/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.audio.call;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Durable identity of one receiver's call occurrence, independent of optional Activity and recording collection.
 * Background consumers derive the same namespaced UUID from the exact producer binding. The process-local token
 * itself is never stored. There is no lookup by radio, frequency, time, or display text and no persistent registry.
 */
public final class SourceCallReference
{
    private SourceCallReference() {}

    /** Worker-only projection; unknown producer provenance remains unknown. */
    public static String fromToken(String token)
    {
        if(token == null || token.isBlank() || token.length() > 240) return null;
        return UUID.nameUUIDFromBytes(("sdrtrunk-vce:source-call:v1:" + token)
            .getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static String canonical(String value)
    {
        if(value == null) return null;
        try
        {
            if(value.length() == 36 && UUID.fromString(value).toString().equals(value)) return value;
        }
        catch(IllegalArgumentException ignored) { }
        throw new IllegalArgumentException("Invalid source call reference");
    }
}
