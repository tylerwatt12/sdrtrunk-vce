/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.audio.call;

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.radio.RadioIdentifier;

/** Accepted source owned by one physical call; decoder cleanup cannot erase it or transfer it to another call. */
public final class CallAttribution
{
    private final String mToken;
    private volatile Identifier<?> mSource;
    private volatile boolean mComplete;

    public CallAttribution(String token)
    {
        mToken = token;
    }

    public String token() { return mToken; }
    public Identifier<?> source() { return mSource; }
    public boolean isComplete() { return mComplete; }
    public void complete() { mComplete = true; }

    /** The call tracker publishes accepted evidence; absent observations do not withdraw a known caller. */
    public void updateSource(Identifier<?> source)
    {
        if(source instanceof RadioIdentifier && source.getRole() == Role.FROM && source.isValid()) mSource = source;
    }
}
