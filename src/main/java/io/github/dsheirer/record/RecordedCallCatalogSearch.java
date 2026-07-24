/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.record;

import java.time.Duration;

/**
 * Bounded recorded-call search used by the website.
 *
 * <p>Time is always required.  Any stable bucket or source-radio key can be combined with duration and keyset
 * pagination. A page never uses an unbounded offset scan.</p>
 */
public record RecordedCallCatalogSearch(String systemKey, String siteKey, String talkgroupKey,
                                        String channelKey, String sourceRadioKey, long fromInclusiveMs,
                                        long toExclusiveMs, long minimumDurationMs, long maximumDurationMs,
                                        int pageSize, Cursor before)
{
    public static final int MAXIMUM_PAGE_SIZE = 200;
    public static final long MAXIMUM_TIME_RANGE_MS = Duration.ofDays(3_650).toMillis();
    public static final long MAXIMUM_CALL_DURATION_MS = Duration.ofHours(24).toMillis();

    public RecordedCallCatalogSearch
    {
        validateKey(systemKey);
        validateKey(siteKey);
        validateKey(talkgroupKey);
        validateKey(channelKey);
        validateKey(sourceRadioKey);

        if(fromInclusiveMs < 0 || toExclusiveMs <= fromInclusiveMs ||
            toExclusiveMs - fromInclusiveMs > MAXIMUM_TIME_RANGE_MS)
        {
            throw new IllegalArgumentException("Recorded-call search time range is invalid or too large");
        }

        if(minimumDurationMs < 0 || maximumDurationMs < minimumDurationMs ||
            maximumDurationMs > MAXIMUM_CALL_DURATION_MS)
        {
            throw new IllegalArgumentException("Recorded-call duration range is invalid");
        }

        if(pageSize < 1 || pageSize > MAXIMUM_PAGE_SIZE)
        {
            throw new IllegalArgumentException("Recorded-call page size must be between 1 and " +
                MAXIMUM_PAGE_SIZE);
        }

        if(before != null)
        {
            before.values();
        }
    }

    public static RecordedCallCatalogSearch recent(long fromInclusiveMs, long toExclusiveMs, int pageSize)
    {
        return new RecordedCallCatalogSearch(null, null, null, null, null, fromInclusiveMs, toExclusiveMs,
            0, MAXIMUM_CALL_DURATION_MS, pageSize, null);
    }

    private static void validateKey(String key)
    {
        if(key != null && (key.isBlank() || key.length() > 512))
        {
            throw new IllegalArgumentException("Recorded-call filter keys cannot be blank or exceed 512 characters");
        }
    }

    public record Cursor(String token)
    {
        public Cursor
        {
            RecordedCallCatalogTokens.parseCursor(token);
        }

        static Cursor create(long completedAtMs, io.github.dsheirer.audio.call.AudioCallId callId)
        {
            return new Cursor(RecordedCallCatalogTokens.cursor(completedAtMs, callId));
        }

        RecordedCallCatalogTokens.CursorValues values()
        {
            return RecordedCallCatalogTokens.parseCursor(token);
        }
    }
}
