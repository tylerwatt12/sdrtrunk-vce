/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeedbackDecoderTest
{
    @Test
    void keepsObserversIndependentAndIgnoresDuplicateRegistration()
    {
        TestDecoder decoder = new TestDecoder();
        List<Float> first = new ArrayList<>();
        List<Float> second = new ArrayList<>();
        FeedbackDecoder.SymbolObserver firstObserver = first::add;
        FeedbackDecoder.SymbolObserver secondObserver = second::add;

        decoder.addSymbolObserver(firstObserver);
        decoder.addSymbolObserver(firstObserver);
        decoder.addSymbolObserver(secondObserver);
        decoder.broadcast(1.25f);

        assertEquals(List.of(1.25f), first);
        assertEquals(List.of(1.25f), second);

        decoder.removeSymbolObserver(firstObserver);
        decoder.broadcast(-0.75f);
        assertEquals(List.of(1.25f), first);
        assertEquals(List.of(1.25f, -0.75f), second);

        decoder.removeSymbolObserver(secondObserver);
        decoder.broadcast(0.5f);
        assertEquals(List.of(1.25f, -0.75f), second);
    }

    private static class TestDecoder extends FeedbackDecoder
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
