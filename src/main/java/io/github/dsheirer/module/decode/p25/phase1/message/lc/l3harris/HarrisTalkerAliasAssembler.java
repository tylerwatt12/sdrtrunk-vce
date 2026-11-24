/*
 * Copyright (C) 2014-2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.module.decode.p25.phase1.message.lc.l3harris;

/** Independent odd/even A/B conveyances (TIA-102.AABF-D-2, July 2023, 7.3.35-36). */
public class HarrisTalkerAliasAssembler
{
    //Receiver admission policy, not a protocol timeout. Source-less parts cannot remain indefinitely.
    private static final long MAXIMUM_PAIR_AGE_MS = 2_000;
    private final LCHarrisTalkerAliasBase[] mFirstParts = new LCHarrisTalkerAliasBase[2];
    private final long[] mTimestamps = new long[2];

    /** Call/source/reception boundaries invalidate both conveyances. */
    public void reset()
    {
        mFirstParts[0] = null;
        mFirstParts[1] = null;
    }

    public LCHarrisTalkerAliasComplete process(LCHarrisTalkerAliasBase part, long timestamp)
    {
        if(part == null) return null;
        int pair = part.getConveyanceIndex();
        if(!part.isValid() || part.isEncrypted() || part.getMessage().size() != 72)
        {
            reset();
            return null;
        }
        if(part instanceof LCHarrisTalkerAliasBlock1 || part instanceof LCHarrisTalkerAliasBlock3)
        {
            mFirstParts[pair] = part;
            mTimestamps[pair] = timestamp;
            return null;
        }
        LCHarrisTalkerAliasBase first = mFirstParts[pair];
        mFirstParts[pair] = null;
        if(first == null || timestamp < mTimestamps[pair] ||
            timestamp - mTimestamps[pair] > MAXIMUM_PAIR_AGE_MS) return null;
        LCHarrisTalkerAliasComplete complete = new LCHarrisTalkerAliasComplete(first, part, timestamp);
        return complete.isValid() ? complete : null;
    }
}
