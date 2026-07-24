/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.record;

import io.github.dsheirer.audio.call.AudioCallId;
import java.nio.file.Path;

/**
 * Portable path encoding for the recorded-call catalog.
 */
final class RecordedCallCatalogPaths
{
    private RecordedCallCatalogPaths()
    {
    }

    static String portableDirectory(Path relativeDirectory)
    {
        if(relativeDirectory == null || relativeDirectory.isAbsolute() ||
            !relativeDirectory.normalize().equals(relativeDirectory))
        {
            throw new IllegalArgumentException("Recorded-call directory must be a normalized relative path");
        }

        StringBuilder value = new StringBuilder();

        for(Path component: relativeDirectory)
        {
            if(!value.isEmpty())
            {
                value.append('/');
            }

            String name = component.toString();

            if(name.isBlank() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0)
            {
                throw new IllegalArgumentException("Recorded-call directory contains an invalid component");
            }

            value.append(name);
        }

        return value.toString();
    }

    static Path relativeDirectory(String portableDirectory)
    {
        if(portableDirectory == null || portableDirectory.isBlank() || portableDirectory.length() > 192 ||
            portableDirectory.startsWith("/") || portableDirectory.endsWith("/") ||
            portableDirectory.indexOf('\\') >= 0)
        {
            throw new IllegalArgumentException("Stored recorded-call directory is invalid");
        }

        String[] components = portableDirectory.split("/", -1);
        Path result = Path.of("");

        for(String component: components)
        {
            if(component.isBlank() || ".".equals(component) || "..".equals(component))
            {
                throw new IllegalArgumentException("Stored recorded-call directory contains an invalid component");
            }

            result = result.resolve(component);
        }

        return result;
    }

    static String fileName(AudioCallId callId, long completedAtMs, RecordFormat format)
    {
        return ManagedRecordingPath.fileName(callId, completedAtMs, format);
    }

    static Path relativePath(String portableDirectory, AudioCallId callId, long completedAtMs, RecordFormat format)
    {
        Path result = relativeDirectory(portableDirectory).resolve(fileName(callId, completedAtMs, format));

        if(ManagedRecordingPath.parse(result).isEmpty())
        {
            throw new IllegalArgumentException("Catalog values do not reconstruct a managed recording path");
        }

        return result;
    }
}
