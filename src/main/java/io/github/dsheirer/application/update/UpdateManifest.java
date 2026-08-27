/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.application.update;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.Properties;

/**
 * Minimal remotely published update description.
 */
record UpdateManifest(int format, String track, long build, String version, URI releaseUri)
{
    private static final int MAX_MANIFEST_LENGTH = 16_384;
    private static final int CURRENT_FORMAT = 2;

    static UpdateManifest parse(String content) throws IOException
    {
        if(content == null || content.length() > MAX_MANIFEST_LENGTH)
        {
            throw new IOException("Update manifest is missing or too large");
        }

        Properties properties = new Properties();
        properties.load(new StringReader(content));

        int format;

        try
        {
            format = Integer.parseInt(required(properties, "format"));
        }
        catch(NumberFormatException e)
        {
            throw new IOException("Update manifest format is not an integer", e);
        }

        if(format != CURRENT_FORMAT)
        {
            throw new IOException("Unsupported update manifest format: " + format);
        }

        String track = required(properties, "track");

        if(track.length() > 32)
        {
            throw new IOException("Update manifest track is too long");
        }

        long build;

        try
        {
            build = Long.parseLong(required(properties, "build"));
        }
        catch(NumberFormatException e)
        {
            throw new IOException("Update manifest build is not an integer", e);
        }

        if(build < 0)
        {
            throw new IOException("Update manifest build cannot be negative");
        }

        String version = required(properties, "version");

        if(version.length() > 100)
        {
            throw new IOException("Update manifest version is too long");
        }

        URI releaseUri;

        try
        {
            releaseUri = URI.create(required(properties, "url"));
        }
        catch(IllegalArgumentException e)
        {
            throw new IOException("Update manifest release URL is invalid", e);
        }

        if(!"https".equalsIgnoreCase(releaseUri.getScheme()) ||
            !"github.com".equalsIgnoreCase(releaseUri.getHost()) ||
            releaseUri.getPath() == null ||
            !releaseUri.getPath().startsWith("/tylerwatt12/sdrtrunk-vce/releases/"))
        {
            throw new IOException("Update manifest release URL is not an allowed sdrtrunk-vce release page");
        }

        return new UpdateManifest(format, track, build, version, releaseUri);
    }

    private static String required(Properties properties, String key) throws IOException
    {
        String value = properties.getProperty(key);

        if(value == null || value.isBlank())
        {
            throw new IOException("Update manifest is missing " + key);
        }

        return value.trim();
    }
}
