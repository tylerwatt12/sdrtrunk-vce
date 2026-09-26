/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25;

import io.github.dsheirer.module.decode.event.DecodeEventType;

/** A P25 decoder event carrying an exact semantic action that has no dedicated generic decode-event type. */
public class P25SignalingEvent extends P25DecodeEvent
{
    private final P25SignalingSemantics.Action mAction;

    public P25SignalingEvent(DecodeEventType eventType, long timestamp, P25SignalingSemantics.Action action)
    {
        super(eventType, timestamp);
        mAction = java.util.Objects.requireNonNull(action, "action cannot be null");
    }

    public P25SignalingSemantics.Action getAction()
    {
        return mAction;
    }
}
