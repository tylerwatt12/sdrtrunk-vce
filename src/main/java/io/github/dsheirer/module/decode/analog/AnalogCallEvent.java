/*
 * Copyright (C) 2026 Dennis Sheirer
 */
package io.github.dsheirer.module.decode.analog;

import io.github.dsheirer.alias.AnalogCallAttribution;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;

/** Analog decode event carrying the call's original list, configured number and alias matches. */
public final class AnalogCallEvent extends DecodeEvent
{
    private final AnalogCallAttribution mAttribution;

    public AnalogCallEvent(long startedAt, AnalogCallAttribution attribution)
    {
        super(DecodeEventType.CALL, startedAt);
        mAttribution = attribution;
    }

    public AnalogCallAttribution getAttribution()
    {
        return mAttribution;
    }
}
