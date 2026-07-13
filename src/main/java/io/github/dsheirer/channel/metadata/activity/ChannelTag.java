/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.module.decode.event.DecodeEventType;

/**
 * Non-exclusive evidence describing how a frequency has been used.
 */
public enum ChannelTag
{
    CONVENTIONAL("CONV", "Conventional channel"),
    CONFIGURED("CFG", "Configured frequency"),
    CONTROL("CC", "Observed control channel"),
    CURRENT_CONTROL("CC", "Current control channel"),
    ALTERNATE_CONTROL("ACC", "Alternate control channel"),
    VOICE("VC", "Observed voice traffic"),
    DATA("DAT", "Observed data traffic"),
    DATA_ANNOUNCED("DAT-A", "Announced data channel");

    private final String mLabel;
    private final String mDescription;

    ChannelTag(String label, String description)
    {
        mLabel = label;
        mDescription = description;
    }

    public String getLabel()
    {
        return mLabel;
    }

    public String getDescription()
    {
        return mDescription;
    }

    public ChannelTag asHistoricalEvidence()
    {
        return this == CURRENT_CONTROL ? CONTROL : this;
    }

    public static ChannelTag fromNetworkRole(String role)
    {
        return switch(role != null ? role : "")
        {
            case "primary_control", "current_control" -> CURRENT_CONTROL;
            case "secondary_control", "alternate_control" -> ALTERNATE_CONTROL;
            case "fdma_data", "tdma_data" -> DATA_ANNOUNCED;
            default -> null;
        };
    }

    public static ChannelTag fromService(DecodeEventType eventType)
    {
        if(eventType == null)
        {
            return null;
        }

        if(DecodeEventType.DATA_CALLS.contains(eventType))
        {
            return DATA;
        }

        return eventType.isVoiceCallEvent() ? VOICE : null;
    }

    public static ChannelTag fromService(State state)
    {
        return state == State.DATA ? DATA : state == State.CALL || state == State.ENCRYPTED ? VOICE : null;
    }
}
