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

import java.util.Objects;

/**
 * One bounded browse value read from the retained call catalog.
 *
 * <p>The value key is the stable filter value returned by the website.  It is not a database row ID: repeated
 * system, site, channel, and talkgroup values live directly on the directory bucket, while source-radio values live
 * directly on the call row.</p>
 */
public record RecordedCallIdentity(RecordedCallIdentityKind kind, String scopeKey, String valueKey,
                                   String displayLabel)
{
    public RecordedCallIdentity
    {
        Objects.requireNonNull(kind, "Recorded-call identity kind cannot be null");
        Objects.requireNonNull(scopeKey, "Recorded-call identity scope cannot be null");

        if(valueKey == null || valueKey.isBlank() || valueKey.length() > 512)
        {
            throw new IllegalArgumentException("Recorded-call identity value is invalid");
        }

        if(scopeKey.length() > 512)
        {
            throw new IllegalArgumentException("Recorded-call identity scope is too long");
        }

        if(displayLabel != null && displayLabel.length() > 160)
        {
            throw new IllegalArgumentException("Recorded-call identity label is too long");
        }
    }
}
