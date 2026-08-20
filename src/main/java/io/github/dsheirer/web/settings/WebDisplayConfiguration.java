/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.settings;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Versioned receiver-wide presentation settings for the browser interface.
 */
public record WebDisplayConfiguration(@JsonProperty("format_version") int formatVersion,
                                      @JsonProperty("show_encryption_details") boolean showEncryptionDetails)
{
    public static final int CURRENT_FORMAT_VERSION = 1;

    public WebDisplayConfiguration
    {
        if(formatVersion != CURRENT_FORMAT_VERSION)
        {
            throw new IllegalArgumentException("Unsupported web display configuration format");
        }
    }

    public static WebDisplayConfiguration defaults()
    {
        return new WebDisplayConfiguration(CURRENT_FORMAT_VERSION, true);
    }

    public WebDisplayConfiguration withShowEncryptionDetails(boolean show)
    {
        return new WebDisplayConfiguration(formatVersion, show);
    }
}
