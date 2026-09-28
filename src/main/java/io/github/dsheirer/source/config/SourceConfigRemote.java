/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.source.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import io.github.dsheirer.source.SourceType;
import java.text.DecimalFormat;
import java.util.UUID;

/**
 * Persistent routing identity for a P25 bitstream supplied by a remote sdrtrunk sender.
 *
 * Runtime connection, session, stream, and generation identifiers deliberately do not belong in this saved
 * configuration.  They are transport state and can change whenever a sender reconnects.
 */
@JsonSubTypes.Type(value = SourceConfigRemote.class, name = "sourceConfigRemote")
public class SourceConfigRemote extends SourceConfiguration
{
    private static final DecimalFormat FREQUENCY_FORMAT = new DecimalFormat("0.00000");

    private String mSenderId;
    private String mFeedId;
    private long mFrequency;

    public SourceConfigRemote()
    {
        super(SourceType.REMOTE);
    }

    public String getSenderId()
    {
        return mSenderId;
    }

    public void setSenderId(String senderId)
    {
        mSenderId = canonicalUuid(senderId, "senderId");
    }

    public String getFeedId()
    {
        return mFeedId;
    }

    public void setFeedId(String feedId)
    {
        mFeedId = canonicalUuid(feedId, "feedId");
    }

    public long getFrequency()
    {
        return mFrequency;
    }

    public void setFrequency(long frequency)
    {
        mFrequency = frequency;
    }

    @JsonIgnore
    public boolean hasSenderId()
    {
        return mSenderId != null;
    }

    @JsonIgnore
    public boolean hasFeedId()
    {
        return mFeedId != null;
    }

    @JsonIgnore
    @Override
    public String getDescription()
    {
        return FREQUENCY_FORMAT.format(mFrequency / 1_000_000.0d) + "MHz REMOTE";
    }

    @Override
    public String toString()
    {
        return getDescription();
    }

    private static String canonicalUuid(String value, String field)
    {
        if(value == null || value.isBlank())
        {
            return null;
        }

        try
        {
            return UUID.fromString(value.strip()).toString();
        }
        catch(IllegalArgumentException e)
        {
            throw new IllegalArgumentException(field + " must be a UUID", e);
        }
    }
}
