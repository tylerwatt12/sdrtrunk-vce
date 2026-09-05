/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.traffic;

/** The radio system and start time frozen when one physical call begins. */
public record CallSystemIdentity(long callStartEpochMilliseconds, String radioSystemKey)
{
    public CallSystemIdentity
    {
        if(callStartEpochMilliseconds <= 0)
        {
            throw new IllegalArgumentException("Call identity requires a positive start timestamp");
        }

        radioSystemKey = radioSystemKey != null ? RadioSystemKey.parse(radioSystemKey) : null;
    }
}
