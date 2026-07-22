/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeedbackDecoderTest
{
    @Test
    void primitiveObserverCoexistsWithLegacyListenerAndCanBeRemovedIndependently()
    {
        TestFeedbackDecoder decoder = new TestFeedbackDecoder();
        List<Float> legacySymbols = new ArrayList<>();
        float[] observedSymbol = new float[1];
        int[] observedCount = new int[1];
        FeedbackDecoder.SymbolObserver observer = symbol -> {
            observedSymbol[0] = symbol;
            observedCount[0]++;
        };

        decoder.setSymbolListener(legacySymbols::add);
        decoder.addSymbolObserver(observer);
        decoder.addSymbolObserver(observer);
        assertEquals(1, decoder.getSymbolObserverCount());

        decoder.broadcast(1.25f);
        assertEquals(List.of(1.25f), legacySymbols);
        assertEquals(1, observedCount[0]);
        assertEquals(1.25f, observedSymbol[0]);

        decoder.removeSymbolListener();
        decoder.broadcast(-0.75f);
        assertEquals(List.of(1.25f), legacySymbols);
        assertEquals(2, observedCount[0]);
        assertEquals(-0.75f, observedSymbol[0]);

        decoder.removeSymbolObserver(observer);
        decoder.removeSymbolObserver(observer);
        decoder.broadcast(0.5f);
        assertEquals(0, decoder.getSymbolObserverCount());
        assertEquals(2, observedCount[0]);
    }

    private static class TestFeedbackDecoder extends FeedbackDecoder
    {
        @Override
        public String getProtocolDescription()
        {
            return "Test";
        }

        @Override
        public DecoderType getDecoderType()
        {
            return DecoderType.P25_PHASE1;
        }
    }
}
