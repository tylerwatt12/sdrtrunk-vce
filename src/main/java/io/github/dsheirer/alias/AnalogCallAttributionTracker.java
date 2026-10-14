/*
 * Copyright (C) 2026 Dennis Sheirer
 */
package io.github.dsheirer.alias;

import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import java.util.concurrent.atomic.AtomicReference;

/** Shares one small immutable match decision between the analog event and audio producers. */
public final class AnalogCallAttributionTracker
{
    private final String mChannelId;
    private final AliasList mAliasList;
    private final TalkgroupIdentifier mDestination;
    private final AtomicReference<AnalogCallAttribution> mCurrent = new AtomicReference<>();

    public AnalogCallAttributionTracker(String channelId, AliasList aliasList, TalkgroupIdentifier destination)
    {
        mChannelId = channelId;
        mAliasList = aliasList;
        mDestination = destination;
    }

    /** Either listener may see squelch-open first. Neither waits for the other or for a configuration writer. */
    public AnalogCallAttribution start()
    {
        AnalogCallAttribution current = mCurrent.get();
        if(current != null)
        {
            return current;
        }
        AnalogCallAttribution captured = AnalogCallAttribution.capture(mChannelId, mAliasList, mDestination,
            System.currentTimeMillis());
        AnalogCallAttribution witness = mCurrent.compareAndExchange(null, captured);
        return witness != null ? witness : captured;
    }

    public void end(AnalogCallAttribution call)
    {
        if(call != null)
        {
            mCurrent.compareAndSet(call, null);
        }
    }
}
