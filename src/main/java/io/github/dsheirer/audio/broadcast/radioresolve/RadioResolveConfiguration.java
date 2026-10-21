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

package io.github.dsheirer.audio.broadcast.radioresolve;

import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.BroadcastServerType;

/**
 * Streaming-tab configuration for RadioResolve call uploads and site metadata.
 */
public class RadioResolveConfiguration extends BroadcastConfiguration
{
    public static final String PRODUCTION_ENDPOINT = "https://calls.radioresolve.com";
    private String mApiKey = null;
    private boolean mIgnoreCertificateErrors = false;
    private Mode mMode = Mode.CALLS_AND_METADATA;

    /**
     * RadioResolve call and site-metadata publishing mode.
     */
    public enum Mode
    {
        CALLS_AND_METADATA("Calls + Metadata"),
        CALLS_ONLY("Calls Only"),
        METADATA_ONLY("Metadata Only");

        private final String mLabel;

        Mode(String label)
        {
            mLabel = label;
        }

        @Override
        public String toString()
        {
            return mLabel;
        }
    }

    /**
     * Constructor for jackson.
     */
    public RadioResolveConfiguration()
    {
        this(BroadcastFormat.MP3);
    }

    /**
     * Constructs an instance.
     */
    public RadioResolveConfiguration(BroadcastFormat format)
    {
        super(format);

        if(getHost() == null || getHost().isEmpty())
        {
            setHost(PRODUCTION_ENDPOINT);
        }
    }

    @Override
    public void setHost(String host)
    {
        super.setHost(normalizeHost(host));
    }

    public String getApiKey()
    {
        return mApiKey;
    }

    public void setApiKey(String apiKey)
    {
        if(!Objects.equals(mApiKey, apiKey))
        {
            mApiKey = apiKey;
            notifyChanged();
        }
    }

    public boolean getIgnoreCertificateErrors()
    {
        return mIgnoreCertificateErrors;
    }

    public boolean isIgnoreCertificateErrors()
    {
        return getIgnoreCertificateErrors();
    }

    public void setIgnoreCertificateErrors(boolean ignore)
    {
        if(!Objects.equals(mIgnoreCertificateErrors, ignore))
        {
            mIgnoreCertificateErrors = ignore;
            notifyChanged();
        }
    }

    public Mode getMode()
    {
        return mMode;
    }

    public void setMode(Mode mode)
    {
        if(!Objects.equals(mMode, mode != null ? mode : Mode.CALLS_AND_METADATA))
        {
            mMode = mode != null ? mode : Mode.CALLS_AND_METADATA;
            notifyChanged();
        }
    }

    @JsonIgnore
    public boolean isCallUploadEnabled()
    {
        Mode mode = getMode();
        return mode == Mode.CALLS_AND_METADATA || mode == Mode.CALLS_ONLY;
    }

    @JsonIgnore
    public boolean isSiteMetadataEnabled()
    {
        Mode mode = getMode();
        return mode == Mode.CALLS_AND_METADATA || mode == Mode.METADATA_ONLY;
    }

    @Override
    public BroadcastServerType getBroadcastServerType()
    {
        return BroadcastServerType.RADIORESOLVE;
    }

    @Override
    public BroadcastConfiguration copyOf()
    {
        RadioResolveConfiguration copy = new RadioResolveConfiguration(getBroadcastFormat());
        copy.setName(getName());
        copy.setHost(getHost());
        copy.setApiKey(getApiKey());
        copy.setIgnoreCertificateErrors(getIgnoreCertificateErrors());
        copy.setMode(getMode());
        copy.setMaximumRecordingAge(getMaximumRecordingAge());
        copy.setEnabled(isEnabled());
        return copy;
    }

    private static String normalizeHost(String host)
    {
        if(host == null || host.isBlank())
        {
            return host;
        }

        String trimmed = host.trim();

        if(!trimmed.startsWith("http://") && !trimmed.startsWith("https://"))
        {
            trimmed = "https://" + trimmed;
        }

        while(trimmed.endsWith("/"))
        {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        return trimmed;
    }

    @Override
    public boolean isValid()
    {
        return getHost() != null && !getHost().isEmpty() && getApiKey() != null && !getApiKey().isEmpty();
    }
}
