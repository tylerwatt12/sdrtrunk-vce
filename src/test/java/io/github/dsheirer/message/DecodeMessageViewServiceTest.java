/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DecodeMessageViewServiceTest
{
    private static final String CONFIGURATION_ID = "00000000-0000-0000-0000-000000000001";
    private static final long FREQUENCY = 851_012_500L;

    @Test
    void snapshotsNewestFirstWithoutStuffBitsAndBoundsText()
    {
        MessageHistory history = new MessageHistory(200);
        TestMessage older = new TestMessage(1_000L, Protocol.APCO25, 0, true, "older");
        TestMessage newer = new TestMessage(2_000L, Protocol.DMR, 1, false, "x".repeat(3_000));
        history.receive(older);
        history.receive(new StuffBitsMessage(1_500L, 12, Protocol.APCO25));
        history.receive(newer);

        DecodeMessageViewService service = new DecodeMessageViewService(scope -> history);

        try(DecodeMessageViewService.Session session = service.openSession(scope()))
        {
            List<DecodeMessageViewService.MessageView> snapshot = session.snapshot();

            assertEquals(2, snapshot.size());
            assertEquals(2_000L, snapshot.get(0).timestampMs());
            assertEquals("DMR", snapshot.get(0).protocol());
            assertEquals(1, snapshot.get(0).timeslot());
            assertFalse(snapshot.get(0).valid());
            assertEquals(2_048, snapshot.get(0).text().length());
            assertTrue(snapshot.get(0).text().endsWith("…"));
            assertEquals(1_000L, snapshot.get(1).timestampMs());
        }
    }

    @Test
    void decoderCallbackOnlyQueuesRawMessageUntilConsumerPolls() throws InterruptedException
    {
        MessageHistory history = new MessageHistory(200);
        AtomicInteger textCalls = new AtomicInteger();
        TestMessage message = new TestMessage(1_000L, Protocol.NXDN, 0, true, "queued", textCalls);
        DecodeMessageViewService service = new DecodeMessageViewService(scope -> history);

        try(DecodeMessageViewService.Session session = service.openSession(scope()))
        {
            assertTrue(session.snapshot().isEmpty());
            history.receive(message);
            assertEquals(0, textCalls.get());

            DecodeMessageViewService.MessageView view = session.poll(0, TimeUnit.MILLISECONDS);
            assertEquals("queued", view.text());
            assertEquals(1, textCalls.get());
        }
    }

    @Test
    void detachesAndRebindsWhenProcessingChainHistoryChanges() throws InterruptedException
    {
        MessageHistory first = new MessageHistory(200);
        MessageHistory second = new MessageHistory(200);
        AtomicReference<MessageHistory> selected = new AtomicReference<>(first);
        DecodeMessageViewService service = new DecodeMessageViewService(scope -> selected.get());

        try(DecodeMessageViewService.Session session = service.openSession(scope()))
        {
            assertTrue(session.refresh());
            first.receive(new TestMessage(1_000L, Protocol.APCO25, 0, true, "first"));
            assertEquals("first", session.poll(0, TimeUnit.MILLISECONDS).text());

            selected.set(second);
            assertTrue(session.refresh());
            first.receive(new TestMessage(2_000L, Protocol.APCO25, 0, true, "stale"));
            second.receive(new TestMessage(3_000L, Protocol.APCO25_PHASE2, 1, true, "replacement"));
            assertEquals("replacement", session.poll(0, TimeUnit.MILLISECONDS).text());
            assertNull(session.poll(0, TimeUnit.MILLISECONDS));
        }

        second.receive(new TestMessage(4_000L, Protocol.APCO25_PHASE2, 1, true, "after close"));
    }

    @Test
    void boundsTheLiveQueueByDroppingTheOldestMessages() throws InterruptedException
    {
        MessageHistory history = new MessageHistory(200);
        DecodeMessageViewService service = new DecodeMessageViewService(scope -> history);

        try(DecodeMessageViewService.Session session = service.openSession(scope()))
        {
            session.refresh();
            int messageCount = DecodeMessageViewService.LIVE_QUEUE_SIZE + 5;

            for(int x = 0; x < messageCount; x++)
            {
                history.receive(new TestMessage(x, Protocol.APCO25, 0, true, "message " + x));
            }

            assertEquals(5L, session.poll(0, TimeUnit.MILLISECONDS).timestampMs());
        }
    }

    @Test
    void safelyProjectsMalformedMessages() throws InterruptedException
    {
        MessageHistory history = new MessageHistory(200);
        DecodeMessageViewService service = new DecodeMessageViewService(scope -> history);

        try(DecodeMessageViewService.Session session = service.openSession(scope()))
        {
            session.refresh();
            history.receive(new BrokenMessage());
            DecodeMessageViewService.MessageView view = session.poll(0, TimeUnit.MILLISECONDS);

            assertEquals(0L, view.timestampMs());
            assertEquals("Unknown", view.protocol());
            assertEquals(0, view.timeslot());
            assertFalse(view.valid());
            assertEquals("MESSAGE ITEM ENCOUNTERED PARSING ERROR", view.text());
        }
    }

    @Test
    void requiresAnExactConfigurationUuidAndPositiveFrequency()
    {
        assertEquals(CONFIGURATION_ID, scope().configurationId());
        assertThrows(IllegalArgumentException.class,
            () -> new DecodeMessageViewService.Scope("not-a-uuid", FREQUENCY));
        assertThrows(IllegalArgumentException.class,
            () -> new DecodeMessageViewService.Scope(CONFIGURATION_ID, 0));
    }

    private static DecodeMessageViewService.Scope scope()
    {
        return new DecodeMessageViewService.Scope(CONFIGURATION_ID, FREQUENCY);
    }

    private static class TestMessage implements IMessage
    {
        private final long mTimestamp;
        private final Protocol mProtocol;
        private final int mTimeslot;
        private final boolean mValid;
        private final String mText;
        private final AtomicInteger mTextCalls;

        private TestMessage(long timestamp, Protocol protocol, int timeslot, boolean valid, String text)
        {
            this(timestamp, protocol, timeslot, valid, text, new AtomicInteger());
        }

        private TestMessage(long timestamp, Protocol protocol, int timeslot, boolean valid, String text,
                            AtomicInteger textCalls)
        {
            mTimestamp = timestamp;
            mProtocol = protocol;
            mTimeslot = timeslot;
            mValid = valid;
            mText = text;
            mTextCalls = textCalls;
        }

        @Override
        public long getTimestamp()
        {
            return mTimestamp;
        }

        @Override
        public boolean isValid()
        {
            return mValid;
        }

        @Override
        public Protocol getProtocol()
        {
            return mProtocol;
        }

        @Override
        public int getTimeslot()
        {
            return mTimeslot;
        }

        @Override
        public List<Identifier> getIdentifiers()
        {
            return List.of();
        }

        @Override
        public String toString()
        {
            mTextCalls.incrementAndGet();
            return mText;
        }
    }

    private static class BrokenMessage implements IMessage
    {
        @Override
        public long getTimestamp()
        {
            throw new IllegalStateException("broken timestamp");
        }

        @Override
        public boolean isValid()
        {
            throw new IllegalStateException("broken validity");
        }

        @Override
        public Protocol getProtocol()
        {
            throw new IllegalStateException("broken protocol");
        }

        @Override
        public int getTimeslot()
        {
            throw new IllegalStateException("broken timeslot");
        }

        @Override
        public List<Identifier> getIdentifiers()
        {
            return List.of();
        }

        @Override
        public String toString()
        {
            throw new IllegalStateException("broken text");
        }
    }
}
