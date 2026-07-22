/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.dsp.symbol.stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.FeedbackDecoder;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SelectedChannelSymbolSourceTest
{
    @Test
    void primitiveObserversAreAdditiveAndDoNotReplaceLegacySwingListener()
    {
        TestDecoder decoder = new TestDecoder();
        AtomicInteger legacy = new AtomicInteger();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        FeedbackDecoder.SymbolObserver firstObserver = symbol -> first.incrementAndGet();
        FeedbackDecoder.SymbolObserver secondObserver = symbol -> second.incrementAndGet();
        decoder.setSymbolListener(symbol -> legacy.incrementAndGet());
        decoder.addSymbolObserver(firstObserver);
        decoder.addSymbolObserver(firstObserver);
        decoder.addSymbolObserver(secondObserver);

        decoder.broadcast(0.25f);

        assertEquals(1, legacy.get());
        assertEquals(1, first.get());
        assertEquals(1, second.get());
        assertEquals(2, decoder.getSymbolObserverCount());

        decoder.removeSymbolObserver(firstObserver);
        decoder.broadcast(-0.25f);

        assertEquals(2, legacy.get());
        assertEquals(1, first.get());
        assertEquals(2, second.get());
        assertEquals(1, decoder.getSymbolObserverCount());
    }

    @Test
    void batchesIntoOneReplaceableLatestSlotWithoutBackpressure() throws Exception
    {
        TestDecoder decoder = new TestDecoder();

        try(SelectedChannelSymbolSource source = new SelectedChannelSymbolSource(decoder, 7))
        {
            broadcastBatch(decoder, 0.1f);
            SymbolFrame first = source.poll(Duration.ZERO);

            assertNotNull(first);
            assertEquals(7, first.getGeneration());
            assertEquals(1, first.getSequence());
            assertEquals(SelectedChannelSymbolSource.BATCH_SIZE, first.getSymbolCount());

            broadcastBatch(decoder, 0.2f);
            broadcastBatch(decoder, 0.3f);
            SymbolFrame latest = source.poll(Duration.ZERO);

            assertNotNull(latest);
            assertEquals(3, latest.getSequence());
            assertEquals(0.3f, latest.getSymbol(0));
            assertEquals(3, source.getPublishedFrameCount());
            assertEquals(1, source.getDroppedFrameCount());
            assertNull(source.poll(Duration.ZERO));
        }

        assertEquals(0, decoder.getSymbolObserverCount());
    }

    @Test
    void closeIsSafeAgainstAnObserverSnapshotAlreadyBeingBroadcast()
    {
        TestDecoder decoder = new TestDecoder();
        AtomicReference<SelectedChannelSymbolSource> sourceReference = new AtomicReference<>();
        decoder.addSymbolObserver(symbol -> sourceReference.get().close());
        SelectedChannelSymbolSource source = new SelectedChannelSymbolSource(decoder, 1);
        sourceReference.set(source);

        // FeedbackDecoder takes one immutable observer snapshot.  The first observer closes/removes the source, but
        // that old snapshot still invokes the source immediately afterward.  The decoder thread must never throw.
        assertDoesNotThrow(() -> decoder.broadcast(0.5f));
        assertEquals(1, decoder.getSymbolObserverCount());
        assertEquals(0, source.getPublishedFrameCount());
    }

    private static void broadcastBatch(TestDecoder decoder, float value)
    {
        for(int x = 0; x < SelectedChannelSymbolSource.BATCH_SIZE; x++)
        {
            decoder.broadcast(value);
        }
    }

    private static final class TestDecoder extends FeedbackDecoder
    {
        @Override
        public String getProtocolDescription()
        {
            return "Test";
        }

        @Override
        public DecoderType getDecoderType()
        {
            return DecoderType.DMR;
        }
    }
}
