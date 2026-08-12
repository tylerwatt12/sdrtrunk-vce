/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Bounded retry state for independently recoverable logical topics on one browser transport. */
final class LiveTopicRetryPolicy
{
    private static final long INITIAL_DELAY_MILLISECONDS = 250;
    private static final long MAXIMUM_DELAY_MILLISECONDS = 8_000;
    private final Map<String,Retry> mRetries = new LinkedHashMap<>();

    boolean canAttempt(String topic, Object parameters, long nowNanos)
    {
        Retry retry = mRetries.get(topic);

        if(retry != null && !Objects.equals(retry.parameters(), parameters))
        {
            mRetries.remove(topic);
            return true;
        }

        return retry == null || nowNanos >= retry.retryAfterNanos();
    }

    void failed(String topic, Object parameters, long nowNanos)
    {
        Retry previous = mRetries.get(topic);
        int attempts = previous != null && Objects.equals(previous.parameters(), parameters) ?
            previous.attempts() + 1 : 1;
        long delayMilliseconds = Math.min(MAXIMUM_DELAY_MILLISECONDS,
            INITIAL_DELAY_MILLISECONDS << Math.min(5, Math.max(0, attempts - 1)));
        mRetries.put(topic, new Retry(parameters, attempts,
            nowNanos + TimeUnit.MILLISECONDS.toNanos(delayMilliseconds)));
    }

    void succeeded(String topic)
    {
        mRetries.remove(topic);
    }

    void clear(String topic)
    {
        mRetries.remove(topic);
    }

    void clear()
    {
        mRetries.clear();
    }

    int attempts(String topic)
    {
        Retry retry = mRetries.get(topic);
        return retry != null ? retry.attempts() : 0;
    }

    long retryAfterNanos(String topic)
    {
        Retry retry = mRetries.get(topic);
        return retry != null ? retry.retryAfterNanos() : 0;
    }

    private record Retry(Object parameters, int attempts, long retryAfterNanos)
    {
    }
}
