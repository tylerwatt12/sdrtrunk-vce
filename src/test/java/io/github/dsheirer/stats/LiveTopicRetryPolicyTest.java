/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class LiveTopicRetryPolicyTest
{
    @Test
    void transientFailureRetriesAfterDeadlineThenClearsOnSuccess()
    {
        LiveTopicRetryPolicy policy = new LiveTopicRetryPolicy();
        Map<String,Object> parameters = Map.of("target_id", "tuner-1");
        AtomicInteger opens = new AtomicInteger();
        long started = TimeUnit.SECONDS.toNanos(1);

        assertFalse(attempt(policy, "tuner_diagnostics", parameters, started,
            () -> opens.incrementAndGet() > 1));
        assertEquals(1, policy.attempts("tuner_diagnostics"));
        long retryAt = policy.retryAfterNanos("tuner_diagnostics");
        assertFalse(policy.canAttempt("tuner_diagnostics", parameters, retryAt - 1));
        assertTrue(attempt(policy, "tuner_diagnostics", parameters, retryAt,
            () -> opens.incrementAndGet() > 1));
        assertEquals(2, opens.get());
        assertEquals(0, policy.attempts("tuner_diagnostics"));
        assertTrue(policy.canAttempt("tuner_diagnostics", parameters, retryAt));
    }

    @Test
    void delayIsBoundedAndAParameterChangeCanAttemptImmediately()
    {
        LiveTopicRetryPolicy policy = new LiveTopicRetryPolicy();
        Map<String,Object> first = Map.of("target_id", "tuner-1");
        long now = TimeUnit.SECONDS.toNanos(1);

        for(int attempt = 1; attempt <= 20; attempt++)
        {
            policy.failed("tuner_diagnostics", first, now);
            long delay = policy.retryAfterNanos("tuner_diagnostics") - now;
            assertTrue(delay >= TimeUnit.MILLISECONDS.toNanos(250));
            assertTrue(delay <= TimeUnit.SECONDS.toNanos(8));
            now = policy.retryAfterNanos("tuner_diagnostics");
        }

        assertTrue(policy.canAttempt("tuner_diagnostics", Map.of("target_id", "tuner-2"), now - 1));
        assertEquals(0, policy.attempts("tuner_diagnostics"));
    }

    private static boolean attempt(LiveTopicRetryPolicy policy, String topic, Object parameters, long now,
                                   BooleanSupplier opener)
    {
        if(!policy.canAttempt(topic, parameters, now))
        {
            return false;
        }

        if(opener.getAsBoolean())
        {
            policy.succeeded(topic);
            return true;
        }

        policy.failed(topic, parameters, now);
        return false;
    }
}
