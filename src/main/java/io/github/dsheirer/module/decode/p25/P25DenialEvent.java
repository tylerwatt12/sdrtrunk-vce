/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25;

import io.github.dsheirer.module.decode.event.DecodeEventType;

/** A denial carrying the original reason byte and the selection of its reason-code lookup. */
public class P25DenialEvent extends P25DecodeEvent
{
    private final int mDenyReasonCode;
    private final boolean mVendorSpecificReason;

    public P25DenialEvent(long timestamp, int denyReasonCode, boolean vendorSpecificReason)
    {
        super(DecodeEventType.DENIAL, timestamp);
        if(denyReasonCode < 0 || denyReasonCode > 255)
        {
            throw new IllegalArgumentException("Denial reason must be an unsigned byte");
        }
        mDenyReasonCode = denyReasonCode;
        mVendorSpecificReason = vendorSpecificReason;
    }

    public int getDenyReasonCode()
    {
        return mDenyReasonCode;
    }

    /** True selects the MFID90 reason mapping used by Motorola denial messages. */
    public boolean isVendorSpecificReason()
    {
        return mVendorSpecificReason;
    }
}
