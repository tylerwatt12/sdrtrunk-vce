/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.audio.call;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/** Process-local exact call correlation. Tokens never identify recordings or become persistent call IDs. */
public final class ActivityCallToken
{
    private static final String RUN = Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 36);
    private static final AtomicLong NEXT = new AtomicLong();
    private ActivityCallToken() {}
    public static String next() { return RUN + ':' + Long.toUnsignedString(NEXT.incrementAndGet(), 36); }
}
