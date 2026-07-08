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

import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.BroadcastServerType;
import java.net.InetAddress;
import java.time.ZoneId;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * Streaming-tab configuration for RadioResolve call uploads and site metadata.
 */
public class RadioResolveConfiguration extends BroadcastConfiguration
{
    public static final String PRODUCTION_ENDPOINT = "https://calls.radioresolve.com";
    public static final int DEFAULT_CONCURRENT_UPLOADS = 4;
    public static final int MIN_CONCURRENT_UPLOADS = 1;
    public static final int MAX_CONCURRENT_UPLOADS = 16;

    private StringProperty mApiKey = new SimpleStringProperty();
    private StringProperty mNodeName = new SimpleStringProperty(getDefaultNodeName());
    private StringProperty mNodeTimezone = new SimpleStringProperty(getDefaultNodeTimezone());
    private BooleanProperty mIgnoreCertificateErrors = new SimpleBooleanProperty(false);
    private ObjectProperty<Mode> mMode = new SimpleObjectProperty<>(Mode.CALLS_AND_METADATA);
    private IntegerProperty mConcurrentUploads = new SimpleIntegerProperty(DEFAULT_CONCURRENT_UPLOADS);

    /**
     * RadioResolve publish mode.  Metadata is always required.
     */
    public enum Mode
    {
        CALLS_AND_METADATA("Calls + Metadata"),
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

        mValid.unbind();
        mValid.bind(Bindings.and(Bindings.isNotEmpty(mHost), Bindings.isNotEmpty(mApiKey)));
    }

    public StringProperty apiKeyProperty()
    {
        return mApiKey;
    }

    public StringProperty nodeNameProperty()
    {
        return mNodeName;
    }

    public StringProperty nodeTimezoneProperty()
    {
        return mNodeTimezone;
    }

    public BooleanProperty ignoreCertificateErrorsProperty()
    {
        return mIgnoreCertificateErrors;
    }

    public ObjectProperty<Mode> modeProperty()
    {
        return mMode;
    }

    public IntegerProperty concurrentUploadsProperty()
    {
        return mConcurrentUploads;
    }

    @Override
    public void setHost(String host)
    {
        super.setHost(normalizeHost(host));
    }

    public String getApiKey()
    {
        return mApiKey.get();
    }

    public void setApiKey(String apiKey)
    {
        mApiKey.set(apiKey);
    }

    public String getNodeName()
    {
        return mNodeName.get();
    }

    public void setNodeName(String nodeName)
    {
        mNodeName.set(nodeName);
    }

    public String getNodeTimezone()
    {
        return mNodeTimezone.get();
    }

    public void setNodeTimezone(String nodeTimezone)
    {
        mNodeTimezone.set(nodeTimezone);
    }

    public boolean getIgnoreCertificateErrors()
    {
        return mIgnoreCertificateErrors.get();
    }

    public boolean isIgnoreCertificateErrors()
    {
        return getIgnoreCertificateErrors();
    }

    public void setIgnoreCertificateErrors(boolean ignore)
    {
        mIgnoreCertificateErrors.set(ignore);
    }

    public Mode getMode()
    {
        return mMode.get();
    }

    public void setMode(Mode mode)
    {
        mMode.set(mode != null ? mode : Mode.CALLS_AND_METADATA);
    }

    public boolean isCallsAndMetadata()
    {
        return getMode() == Mode.CALLS_AND_METADATA;
    }

    public int getConcurrentUploads()
    {
        return clampConcurrentUploads(mConcurrentUploads.get());
    }

    public void setConcurrentUploads(int concurrentUploads)
    {
        mConcurrentUploads.set(clampConcurrentUploads(concurrentUploads));
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
        copy.setNodeName(getNodeName());
        copy.setNodeTimezone(getNodeTimezone());
        copy.setIgnoreCertificateErrors(getIgnoreCertificateErrors());
        copy.setMode(getMode());
        copy.setConcurrentUploads(getConcurrentUploads());
        copy.setMaximumRecordingAge(getMaximumRecordingAge());
        copy.setEnabled(isEnabled());
        return copy;
    }

    public static String getDefaultNodeName()
    {
        try
        {
            return InetAddress.getLocalHost().getHostName();
        }
        catch(Exception _)
        {
            return "sdrtrunk";
        }
    }

    public static String getDefaultNodeTimezone()
    {
        return ZoneId.systemDefault().getId();
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

    private static int clampConcurrentUploads(int concurrentUploads)
    {
        return Math.min(MAX_CONCURRENT_UPLOADS, Math.max(MIN_CONCURRENT_UPLOADS, concurrentUploads));
    }
}
