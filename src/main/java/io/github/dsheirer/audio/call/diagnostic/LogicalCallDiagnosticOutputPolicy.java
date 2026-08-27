/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Frozen output intent for a resolved call. Stream routing keys are configured logical route names only; they can
 * never contain provider API keys, passwords, tokens, or other credentials.
 */
public record LogicalCallDiagnosticOutputPolicy(boolean recordRequested, List<String> streamRoutingKeys,
                                                int streamRoutingKeyCount, boolean browserOffered)
{
    /** Enough names to identify normal routing while keeping every diagnostic record predictably bounded. */
    public static final int MAXIMUM_RETAINED_STREAM_ROUTE_NAMES = 8;

    public LogicalCallDiagnosticOutputPolicy
    {
        LinkedHashSet<String> retained = new LinkedHashSet<>();

        if(streamRoutingKeys != null)
        {
            for(String routingKey : streamRoutingKeys)
            {
                if(routingKey != null && !routingKey.isBlank())
                {
                    retained.add(routingKey);

                    if(retained.size() >= MAXIMUM_RETAINED_STREAM_ROUTE_NAMES)
                    {
                        break;
                    }
                }
            }
        }

        streamRoutingKeys = List.copyOf(retained);
        streamRoutingKeyCount = Math.max(streamRoutingKeys.size(), streamRoutingKeyCount);
    }
}
