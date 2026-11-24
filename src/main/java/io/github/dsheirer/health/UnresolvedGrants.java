/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.health;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Calls a control channel granted on a channel it couldn't turn into a frequency (no band plan for it), for node
 * health reports: noFrequency.  Such a grant never becomes a tracked call, so it is counted here, once per call: a
 * talkgroup's unresolved grants are one call until they go quiet.
 *
 * A call is counted only when it ends without ever resolving: one whose channel resolves on a later grant (the band
 * plan arrived) is counted as a call like any other instead.  Grants in the first seconds after the control channel
 * starts, or moves, aren't counted at all: the band plan is still being learned then, and a site that never sends it
 * is shown by every call after.
 *
 * Not thread safe: the traffic channel manager calls it under its lock.
 */
public class UnresolvedGrants
{
    /** Grants this soon after the control channel starts or moves aren't counted. */
    public static final long GRACE_MS = 10_000;
    /** A call's grants stop this long before it counts as ended. */
    public static final long QUIET_MS = 5_000;

    private final Map<String,long[]> mCalls = new HashMap<>();
    private Long mFirstGrant;

    /**
     * The control channel moved: the band plan is learned again.
     */
    public void reset()
    {
        mCalls.clear();
        mFirstGrant = null;
    }

    /**
     * A grant whose channel resolved: that talkgroup's call is counted as a call like any other.
     * @param talkgroup of the grant, or null
     * @param timestamp of the grant
     */
    public void resolved(String talkgroup, long timestamp)
    {
        first(timestamp);

        if(talkgroup != null)
        {
            mCalls.remove(talkgroup);
        }
    }

    /**
     * A grant whose channel couldn't be turned into a frequency.
     * @param talkgroup of the grant, or null
     * @param timestamp of the grant
     */
    public void unresolved(String talkgroup, long timestamp)
    {
        first(timestamp);

        if(talkgroup == null || timestamp - mFirstGrant < GRACE_MS)
        {
            return;
        }

        long[] call = mCalls.get(talkgroup);

        if(call == null)
        {
            mCalls.put(talkgroup, new long[]{timestamp});
        }
        else
        {
            call[0] = Math.max(call[0], timestamp);
        }
    }

    /**
     * Ends the calls whose grants went quiet.
     * @param timestamp now
     * @return calls that ended without resolving, to count as noFrequency
     */
    public int ended(long timestamp)
    {
        int ended = 0;
        Iterator<long[]> it = mCalls.values().iterator();

        while(it.hasNext())
        {
            if(timestamp - it.next()[0] > QUIET_MS)
            {
                it.remove();
                ended++;
            }
        }

        return ended;
    }

    private void first(long timestamp)
    {
        if(mFirstGrant == null)
        {
            mFirstGrant = timestamp;
        }
    }
}
