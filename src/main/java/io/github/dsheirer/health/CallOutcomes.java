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
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.Objects;

/** Tentative outcomes under the traffic manager's existing lock; disabled and retired generations retain no maps. */
public class CallOutcomes
{
    public static final long NEW_CALL_AFTER_MS = 3000;
    private final CallTally mTally;
    private final AtomicReference<Maps> mMaps = new AtomicReference<>();

    private static final class Maps
    {
        private final long generation;
        private final Map<String,Call> calls = new ConcurrentHashMap<>();
        private final Map<String,String> frequencyKeys = new ConcurrentHashMap<>();
        private Maps(long generation) { this.generation = generation; }
    }

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

    public CallOutcomes(CallTally tally) { mTally = tally; }
    public boolean isEnabled()
    {
        boolean enabled = mTally.isEnabled();
        Maps maps = mMaps.get();
        if(maps != null && !mTally.isGenerationEnabled(maps.generation)) mMaps.compareAndSet(maps, null);
        return enabled;
    }

    /** Only decoder-owned manager calls initialize/replace maps; traffic-side reads never allocate or scan. */
    private Maps collectingMaps()
    {
        if(!isEnabled()) return null;
        long generation = mTally.generation();
        Maps maps = mMaps.get();
        if(maps == null || maps.generation != generation)
        {
            Maps candidate = new Maps(generation);
            if(mMaps.compareAndSet(maps, candidate)) maps = candidate;
            else maps = mMaps.get();
        }
        return mTally.isEnabled() && mTally.generation() == generation && maps != null &&
            maps.generation == generation ? maps : null;
    }

    private Maps currentMaps()
    {
        if(!isEnabled()) return null;
        Maps maps = mMaps.get();
        return maps != null && maps.generation == mTally.generation() ? maps : null;
    }

    public static String key(long frequency, int timeslot) { return "frequency/" + frequency + "/" + timeslot; }

    public void bindFrequency(String key, long frequency, int timeslot)
    {
        if(key == null || frequency <= 0) return;
        Maps maps = collectingMaps();
        if(maps == null) return;
        String frequencyKey = key(frequency, timeslot);
        if(maps.frequencyKeys.size() < CallTally.MAX_PENDING || maps.frequencyKeys.containsKey(frequencyKey))
            maps.frequencyKeys.put(frequencyKey, key);
        else mTally.lost();
    }

    public String frequencyKey(long frequency, int timeslot)
    {
        Maps maps = currentMaps();
        if(maps == null) return null;
        String key = key(frequency, timeslot);
        return maps.frequencyKeys.getOrDefault(key, key);
    }

    public void unmapped(String key, Object destination, long timestamp)
    {
        Maps maps = collectingMaps();
        if(maps == null || key == null) return;
        Call previous = maps.calls.get(key);
        boolean newCall = previous == null || !Objects.equals(previous.destination, destination) ||
            timestamp - previous.timestamp > NEW_CALL_AFTER_MS;
        tally(maps, key, newCall, CallOutcome.NO_FREQUENCY, timestamp);
        Call call = maps.calls.get(key);
        if(call != null) call.destination = destination;
    }

    public void resolved(String key, Object destination, long timestamp)
    {
        Maps maps = currentMaps();
        if(maps == null || key == null) return;
        Call call = maps.calls.remove(key);
        if(call == null) return;
        call.pending.expire(timestamp);
        if(Objects.equals(call.destination, destination) && timestamp - call.timestamp <= NEW_CALL_AFTER_MS)
            call.pending.cancel();
        else call.pending.finish();
    }

    public void tally(String key, boolean newCall, CallOutcome outcome, long timestamp)
    {
        if(key == null || outcome == null) return;
        Maps maps = collectingMaps();
        if(maps != null) tally(maps, key, newCall, outcome, timestamp);
    }

    private void tally(Maps maps, String key, boolean newCall, CallOutcome outcome, long timestamp)
    {
        Call call = maps.calls.get(key);
        if(newCall && call != null)
        {
            call.pending.finish();
            maps.calls.remove(key);
            call = null;
        }
        if(call == null)
        {
            CallTally.Pending pending = mTally.pending(outcome, timestamp, NEW_CALL_AFTER_MS);
            if(pending == null) return;
            if(pending.generation() != maps.generation)
            {
                pending.cancel();
                return;
            }
            if(maps.calls.size() >= CallTally.MAX_PENDING)
            {
                var oldest = maps.calls.entrySet().iterator();
                CallTally.Pending retired = oldest.next().getValue().pending;
                retired.expire(timestamp);
                if(!retired.isFinished())
                {
                    retired.cancel();
                    mTally.lost();
                }
                oldest.remove();
            }
            maps.calls.put(key, new Call(pending, outcome, timestamp));
        }
        else
        {
            call.outcome = outcome;
            call.timestamp = Math.max(call.timestamp, timestamp);
            call.pending.observe(outcome, timestamp);
        }
    }

    public void touch(String key, long timestamp)
    {
        Maps maps = currentMaps();
        if(maps == null || key == null) return;
        Call call = maps.calls.get(key);
        if(call != null) call.pending.observe(null, timestamp);
    }

    public void rejected(String key)
    {
        Maps maps = currentMaps();
        if(maps == null || key == null) return;
        Call call = maps.calls.get(key);
        if(call != null && call.outcome == CallOutcome.RECORDED)
        {
            call.outcome = CallOutcome.NO_SOURCE;
            call.pending.observe(CallOutcome.NO_SOURCE, call.timestamp);
        }
    }

    public boolean has(String key)
    {
        Maps maps = currentMaps();
        return maps != null && key != null && maps.calls.containsKey(key);
    }

    public CallOutcome get(String key)
    {
        Maps maps = currentMaps();
        Call call = maps != null && key != null ? maps.calls.get(key) : null;
        return call != null ? call.outcome : null;
    }

    public void trafficStopped(String key)
    {
        Maps maps = currentMaps();
        Call call = maps != null && key != null ? maps.calls.get(key) : null;
        if(call != null && call.outcome == CallOutcome.RECORDED) call.pending.finish();
    }

    /** Retired/disabled maps are dropped in O(1); enabled lifecycle teardown settles only its current generation. */
    public void clear()
    {
        Maps maps = mMaps.getAndSet(null);
        if(mTally.isEnabled() && maps != null && maps.generation == mTally.generation())
            maps.calls.values().forEach(call -> call.pending.finish());
    }
}
