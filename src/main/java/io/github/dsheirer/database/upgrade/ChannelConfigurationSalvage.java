/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.controller.channel.Channel;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Defaults independently disposable channel subdocuments while preserving usable decode and source settings. */
final class ChannelConfigurationSalvage
{
    private static final List<String> OPTIONAL_COMPONENTS = List.of(
        "auxDecodeConfiguration", "eventLogConfiguration", "recordConfiguration");

    private ChannelConfigurationSalvage()
    {
    }

    static Result decode(ObjectMapper mapper, ObjectNode source, String label) throws IOException
    {
        ObjectNode sanitized = source.deepCopy();
        Map<String,JsonNode> optional = new LinkedHashMap<>();
        for(String property: OPTIONAL_COMPONENTS)
        {
            JsonNode value = sanitized.remove(property);
            if(value != null)
            {
                optional.put(property, value);
            }
        }

        Channel channel = decodeStrict(mapper, sanitized, label);
        long defaultedComponents = 0;
        for(Map.Entry<String,JsonNode> entry: optional.entrySet())
        {
            ObjectNode candidate = sanitized.deepCopy();
            candidate.set(entry.getKey(), entry.getValue());
            try
            {
                channel = decodeStrict(mapper, candidate, label);
                sanitized = candidate;
            }
            catch(IOException exception)
            {
                defaultedComponents++;
            }
        }
        return new Result(channel, sanitized, defaultedComponents);
    }

    private static Channel decodeStrict(ObjectMapper mapper, ObjectNode payload, String label) throws IOException
    {
        try
        {
            return mapper.treeToValue(payload, Channel.class);
        }
        catch(IOException | RuntimeException exception)
        {
            throw new IOException(label + " is not a supported saved channel document", exception);
        }
    }

    record Result(Channel channel, ObjectNode payload, long defaultedComponents)
    {
    }
}
