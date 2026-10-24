package io.github.dsheirer.alerts.delivery;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;
import static org.junit.jupiter.api.Assertions.*;

class PushDeliveryRetryLogTest
{
    @Test void firstFailureIsImmediateAndRepeatedFailuresAreSummarizedEveryFiveMinutes()
    {
        AtomicLong now = new AtomicLong();
        List<String> messages = new ArrayList<>();
        var retry = new PushDeliveryService.RelayRetryLog(now::get, messages::add);
        retry.failed(new IOException("synthetic-secret"));
        assertEquals(List.of("RadioResolve status and queued preferences will be checked again (failures: IOException=1)"), messages);

        now.set(TimeUnit.SECONDS.toNanos(15)); retry.failed(new IOException());
        now.set(TimeUnit.SECONDS.toNanos(299)); retry.failed(new IOException());
        assertEquals(1, messages.size());
        now.set(TimeUnit.MINUTES.toNanos(5)); retry.failed(new IOException());
        assertEquals("RadioResolve status and queued preferences are still retrying (failures: IOException=4)", messages.getLast());
        now.set(TimeUnit.SECONDS.toNanos(599)); retry.failed(new IOException());
        assertEquals(2, messages.size());
        now.set(TimeUnit.MINUTES.toNanos(10)); retry.failed(new IOException());
        assertEquals(3, messages.size());
        assertEquals("RadioResolve status and queued preferences are still retrying (failures: IOException=6)", messages.getLast());
        assertFalse(String.join("\n", messages).contains("synthetic-secret"));
    }

    @Test void newExceptionClassesAreVisibleWithoutLoggingEveryAlternation()
    {
        AtomicLong now = new AtomicLong();
        List<String> messages = new ArrayList<>();
        var retry = new PushDeliveryService.RelayRetryLog(now::get, messages::add);
        retry.failed(new IOException());
        now.set(TimeUnit.SECONDS.toNanos(15));
        retry.failed(new SQLiteException("synthetic-secret", SQLiteErrorCode.SQLITE_BUSY));
        assertEquals(2, messages.size());
        assertEquals("RadioResolve status and queued preferences will be checked again (failures: IOException=1, SQLiteException=1)", messages.getLast());

        retry.failed(new IOException());
        retry.failed(new SQLiteException("synthetic-secret", SQLiteErrorCode.SQLITE_BUSY));
        assertEquals(2, messages.size());
        retry.failed(new RadioResolvePushClient.GatewayException(503));
        assertEquals(3, messages.size());
        assertEquals("RadioResolve status and queued preferences will be checked again (failures: GatewayException=1, IOException=2, SQLiteException=2)", messages.getLast());
        now.addAndGet(TimeUnit.MINUTES.toNanos(5)); retry.failed(new IOException());
        assertEquals(4, messages.size());
        assertEquals("RadioResolve status and queued preferences are still retrying (failures: GatewayException=1, IOException=3, SQLiteException=2)", messages.getLast());
        assertFalse(String.join("\n", messages).contains("synthetic-secret"));
        assertFalse(String.join("\n", messages).contains("503"));
    }

    @Test void recoveryIsLoggedOnceAndNextFailureStartsANewEpisode()
    {
        AtomicLong now = new AtomicLong();
        List<String> messages = new ArrayList<>();
        var retry = new PushDeliveryService.RelayRetryLog(now::get, messages::add);
        retry.succeeded();
        assertTrue(messages.isEmpty());
        retry.failed(new IOException());
        retry.failed(new IOException());
        retry.failed(new SQLiteException("synthetic-secret", SQLiteErrorCode.SQLITE_BUSY));
        retry.succeeded();
        assertEquals("RadioResolve status and queued preferences recovered (failures: IOException=2, SQLiteException=1)", messages.getLast());
        int recoveredMessages = messages.size();
        retry.succeeded();
        assertEquals(recoveredMessages, messages.size());

        retry.failed(new IOException());
        assertEquals(recoveredMessages + 1, messages.size());
        assertEquals("RadioResolve status and queued preferences will be checked again (failures: IOException=1)", messages.getLast());
        retry.succeeded();
        assertEquals("RadioResolve status and queued preferences recovered (failures: IOException=1)", messages.getLast());
    }
}
