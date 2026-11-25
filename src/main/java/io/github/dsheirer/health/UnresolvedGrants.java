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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unresolved grants after the startup grace. The bounded call holders settle on quiet time even if this is the
 * site's last grant; a band plan arriving before then cancels the tentative noFrequency result.
 * Decoder-owned lookup state is never read or locked by the reporting worker.
 */
public class UnresolvedGrants
{
    public static final long GRACE_MS = 10_000;
    public static final long QUIET_MS = 5_000;
    private final Map<String,CallTally.Pending> mCalls = new LinkedHashMap<>();
    private Long mFirstGrant;

    public void reset()
    {
        mCalls.values().forEach(CallTally.Pending::finish);
        mCalls.clear();
        mFirstGrant = null;
    }

    public void resolved(String talkgroup, long timestamp)
    {
        first(timestamp);
        if(talkgroup != null)
        {
            CallTally.Pending pending = mCalls.remove(talkgroup);
            if(pending != null)
            {
                pending.expire(timestamp);
                pending.cancel();
            }
        }
    }

    public void unresolved(String talkgroup, long timestamp, CallTally tally)
    {
        if(!tally.isEnabled()) return;
        first(timestamp);
        if(talkgroup == null || timestamp - mFirstGrant < GRACE_MS) return;
        CallTally.Pending pending = mCalls.get(talkgroup);
        if(pending != null) pending.expire(timestamp);
        if(pending != null && pending.isFinished())
        {
            mCalls.remove(talkgroup);
            pending = null;
        }
        if(pending == null)
        {
            if(mCalls.size() >= CallTally.MAX_PENDING)
            {
                var oldest = mCalls.entrySet().iterator();
                CallTally.Pending retired = oldest.next().getValue();
                retired.expire(timestamp);
                if(!retired.isFinished())
                {
                    retired.cancel();
                    tally.lost();
                }
                oldest.remove();
            }
            pending = tally.pending(CallOutcome.NO_FREQUENCY, timestamp, QUIET_MS);
            if(pending == null) return;
            mCalls.put(talkgroup, pending);
        }
        else
        {
            pending.observe(CallOutcome.NO_FREQUENCY, timestamp);
        }
    }

    private void first(long timestamp)
    {
        if(mFirstGrant == null) mFirstGrant = timestamp;
    }
}
