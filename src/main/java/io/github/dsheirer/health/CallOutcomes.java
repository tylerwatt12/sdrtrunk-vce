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

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Objects;

/** Tentative DMR outcomes, owned by the traffic manager's existing lock; settled once on end or replacement. */
public class CallOutcomes
{
    public static final long NEW_CALL_AFTER_MS = 3000;
    private final CallTally mTally;
    private final Map<String,Call> mCalls = new ConcurrentHashMap<>();
    private final Map<String,String> mFrequencyKeys = new ConcurrentHashMap<>();

    private static class Call
    {
        private final CallTally.Pending pending;
        private CallOutcome outcome;
        private long timestamp;
        private Object destination;

        private Call(CallTally.Pending pending, CallOutcome outcome, long timestamp)
        {
            this.pending = pending;
            this.outcome = outcome;
            this.timestamp = timestamp;
        }
    }

    public CallOutcomes(CallTally tally)
    {
        mTally = tally;
    }

    public static String key(long frequency, int timeslot)
    {
        return "frequency/" + frequency + "/" + timeslot;
    }

    /** Only health routing uses this bounded alias; receiver allocation maps retain their original keys. */
    public void bindFrequency(String key, long frequency, int timeslot)
    {
        if(key == null || frequency <= 0) return;
        String frequencyKey = key(frequency, timeslot);
        if(mFrequencyKeys.size() < CallTally.MAX_PENDING || mFrequencyKeys.containsKey(frequencyKey))
        {
            mFrequencyKeys.put(frequencyKey, key);
        }
        else mTally.lost();
    }

    public String frequencyKey(long frequency, int timeslot)
    {
        return mFrequencyKeys.getOrDefault(key(frequency, timeslot), key(frequency, timeslot));
    }

    /** An unmapped logical channel retains its own tentative miss, independent of frequency-zero decode trackers. */
    public void unmapped(String key, Object destination, long timestamp)
    {
        Call previous = mCalls.get(key);
        boolean newCall = previous == null || !Objects.equals(previous.destination, destination) ||
            timestamp - previous.timestamp > NEW_CALL_AFTER_MS;
        tally(key, newCall, CallOutcome.NO_FREQUENCY, timestamp);
        Call call = mCalls.get(key);
        if(call != null) call.destination = destination;
    }

    /** Cancel only the same logical call resolved before quiet; an older/different call keeps its final miss. */
    public void resolved(String key, Object destination, long timestamp)
    {
        Call call = key != null ? mCalls.remove(key) : null;
        if(call == null) return;
        call.pending.expire(timestamp);
        if(Objects.equals(call.destination, destination) && timestamp - call.timestamp <= NEW_CALL_AFTER_MS)
        {
            call.pending.cancel();
        }
        else call.pending.finish();
    }

    public void tally(String key, boolean newCall, CallOutcome outcome, long timestamp)
    {
        if(key == null || outcome == null) return;
        Call call = mCalls.get(key);
        if(newCall && call != null)
        {
            call.pending.finish();
            mCalls.remove(key);
            call = null;
        }
        if(call == null)
        {
            if(mCalls.size() >= CallTally.MAX_PENDING)
            {
                var oldest = mCalls.entrySet().iterator();
                CallTally.Pending retired = oldest.next().getValue().pending;
                retired.expire(timestamp);
                if(!retired.isFinished())
                {
                    retired.cancel();
                    mTally.lost();
                }
                oldest.remove();
            }
            mCalls.put(key, new Call(mTally.pending(outcome, timestamp, NEW_CALL_AFTER_MS), outcome, timestamp));
        }
        else
        {
            call.outcome = outcome;
            call.timestamp = Math.max(call.timestamp, timestamp);
            call.pending.observe(outcome, timestamp);
        }
    }

    /** Traffic activity refreshes a call without acquiring the manager lock or changing its tentative outcome. */
    public void touch(String key, long timestamp)
    {
        Call call = mCalls.get(key);
        if(call != null) call.pending.observe(null, timestamp);
    }

    /** A synchronous or asynchronous start rejection changes the tentative allocation result. */
    public void rejected(String key)
    {
        Call call = mCalls.get(key);
        if(call != null && call.outcome == CallOutcome.RECORDED)
        {
            call.outcome = CallOutcome.NO_SOURCE;
            call.pending.observe(CallOutcome.NO_SOURCE, call.timestamp);
        }
    }

    public boolean has(String key)
    {
        return key != null && mCalls.containsKey(key);
    }

    public CallOutcome get(String key)
    {
        Call call = mCalls.get(key);
        return call != null ? call.outcome : null;
    }

    /** A running traffic channel ended. A failed start remains tentative for control-grant retries. */
    public void trafficStopped(String key)
    {
        Call call = mCalls.get(key);
        if(call != null && call.outcome == CallOutcome.RECORDED) call.pending.finish();
    }

    /** Source reset/stop settles every remaining call; repeated clear is harmless. */
    public void clear()
    {
        mCalls.values().forEach(call -> call.pending.finish());
        mCalls.clear();
        mFrequencyKeys.clear();
    }
}
