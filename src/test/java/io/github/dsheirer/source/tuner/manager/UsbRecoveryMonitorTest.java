package io.github.dsheirer.source.tuner.manager;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UsbRecoveryMonitorTest
{
    private static final String RADIO = "test-radio";

    @Test
    void onlyUsefulSampleProgressResetsTheStallDeadline()
    {
        AtomicLong clock = new AtomicLong();
        UsbRecoveryMonitor monitor = new UsbRecoveryMonitor(clock::get);
        Object runtime = new Object();
        assertFalse(monitor.samplesStalled(RADIO, runtime, 1, true, 0));
        clock.set(seconds(8));
        assertFalse(monitor.samplesStalled(RADIO, runtime, 1, true, 0));
        clock.set(seconds(10));
        assertTrue(monitor.samplesStalled(RADIO, runtime, 1, true, 0),
            "Timeout callbacks or zero-length transfers cannot disguise missing usable samples");
        assertFalse(monitor.samplesStalled(RADIO, runtime, 1, true, 4096));
        clock.set(seconds(19));
        assertFalse(monitor.samplesStalled(RADIO, runtime, 1, true, 4096));
        clock.set(seconds(20));
        assertTrue(monitor.samplesStalled(RADIO, runtime, 1, true, 4096));
    }

    @Test
    void inactiveReceptionNewStreamsAndNewRuntimesGetTheirOwnGracePeriod()
    {
        AtomicLong clock = new AtomicLong();
        UsbRecoveryMonitor monitor = new UsbRecoveryMonitor(clock::get);
        Object runtime = new Object();
        assertFalse(monitor.samplesStalled(RADIO, runtime, 1, true, 10));
        clock.set(seconds(8));
        assertFalse(monitor.samplesStalled(RADIO, runtime, 2, true, 10));
        clock.set(seconds(16));
        assertFalse(monitor.samplesStalled(RADIO, runtime, 2, true, 10));
        clock.set(seconds(18));
        assertTrue(monitor.samplesStalled(RADIO, runtime, 2, true, 10));
        Object replacement = new Object();
        assertFalse(monitor.samplesStalled(RADIO, replacement, 2, true, 10));
        clock.set(seconds(26));
        assertFalse(monitor.samplesStalled(RADIO, replacement, 2, true, 10));
        clock.set(seconds(28));
        assertTrue(monitor.samplesStalled(RADIO, replacement, 2, true, 10));
        assertFalse(monitor.samplesStalled(RADIO, replacement, 2, false, 10));
        clock.set(seconds(100));
        assertFalse(monitor.samplesStalled(RADIO, replacement, 2, false, 10));
        assertFalse(monitor.samplesStalled(RADIO, replacement, 2, true, 10));
        clock.set(seconds(109));
        assertFalse(monitor.samplesStalled(RADIO, replacement, 2, true, 10));
        clock.set(seconds(110));
        assertTrue(monitor.samplesStalled(RADIO, replacement, 2, true, 10));
    }

    @Test
    void wakeOrClockDiscontinuityStartsFreshGraceInsteadOfResettingAHealthyRadio()
    {
        AtomicLong clock = new AtomicLong();
        UsbRecoveryMonitor monitor = new UsbRecoveryMonitor(clock::get);
        Object runtime = new Object();
        assertFalse(monitor.samplesStalled(RADIO, runtime, 1, true, 100));
        clock.set(seconds(8));
        assertFalse(monitor.samplesStalled(RADIO, runtime, 1, true, 100));
        clock.set(seconds(60));
        assertFalse(monitor.samplesStalled(RADIO, runtime, 1, true, 100));
        clock.set(seconds(69));
        assertFalse(monitor.samplesStalled(RADIO, runtime, 1, true, 100));
        clock.set(seconds(70));
        assertTrue(monitor.samplesStalled(RADIO, runtime, 1, true, 100));
        clock.set(seconds(1));
        assertFalse(monitor.samplesStalled(RADIO, runtime, 1, true, 100));
        clock.set(seconds(10));
        assertFalse(monitor.samplesStalled(RADIO, runtime, 1, true, 100));
        clock.set(seconds(11));
        assertTrue(monitor.samplesStalled(RADIO, runtime, 1, true, 100));
    }

    @Test
    void repeatedFailuresBackOffAndCapAtThirtySeconds()
    {
        AtomicLong clock = new AtomicLong();
        UsbRecoveryMonitor monitor = new UsbRecoveryMonitor(clock::get);
        assertTrue(monitor.mayRetry(RADIO, 7, false, false));
        for(long delay : new long[]{2, 4, 8, 16, 30, 30, 30})
        {
            monitor.failed(RADIO, 7);
            assertFalse(monitor.mayRetry(RADIO, 7, false, false));
            clock.addAndGet(seconds(delay) - 1);
            assertFalse(monitor.mayRetry(RADIO, 7, false, false));
            clock.incrementAndGet();
            assertTrue(monitor.mayRetry(RADIO, 7, false, false));
        }
    }

    @Test
    void explicitRequestNewAttachmentOrOperatorChangeBypassesBackoff()
    {
        AtomicLong clock = new AtomicLong();
        UsbRecoveryMonitor monitor = new UsbRecoveryMonitor(clock::get);
        monitor.failed(RADIO, 3);
        assertFalse(monitor.mayRetry(RADIO, 3, false, false));
        assertTrue(monitor.mayRetry(RADIO, 3, true, false));
        assertTrue(monitor.mayRetry(RADIO, 3, false, true));
        assertTrue(monitor.mayRetry(RADIO, 4, false, false));
        assertFalse(monitor.mayRetry(RADIO, 3, false, false));
        monitor.recovered(RADIO);
        assertTrue(monitor.mayRetry(RADIO, 3, false, false));
        monitor.failed(RADIO, 3);
        clock.set(seconds(2));
        assertTrue(monitor.mayRetry(RADIO, 3, false, false), "Success resets the previous failure count");
    }

    @Test
    void forgettingDeviceClearsBothRetryAndSampleProgress()
    {
        AtomicLong clock = new AtomicLong();
        UsbRecoveryMonitor monitor = new UsbRecoveryMonitor(clock::get);
        Object runtime = new Object();
        monitor.failed(RADIO, 1);
        assertFalse(monitor.samplesStalled(RADIO, runtime, 1, true, 0));
        clock.set(seconds(1));
        monitor.forget(RADIO);
        assertTrue(monitor.mayRetry(RADIO, 1, false, false));
        clock.set(seconds(10));
        assertFalse(monitor.samplesStalled(RADIO, runtime, 1, true, 0));
    }

    private static long seconds(long value)
    {
        return TimeUnit.SECONDS.toNanos(value);
    }
}
