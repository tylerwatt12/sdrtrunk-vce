/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.source.SourceEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeedbackDecoderTest
{
    @Test
    void symbolPhaseUsesSymbolRateRegardlessOfDecimation()
    {
        for(double sampleRate: new double[]{19200, 25000, 37500})
        {
            TestDecoder decoder = new TestDecoder();
            decoder.setDecimatedSampleRate(sampleRate);
            List<SourceEvent> requests = new ArrayList<>();
            decoder.setSourceEventListener(requests::add);

            for(double frequency: new double[]{-800, -100, -2, 0, 2, 100, 800})
            {
                decoder.processSymbolPLLError((float)(2 * Math.PI * frequency / 4800), 4800);
                SourceEvent request = requests.getLast();
                assertEquals(SourceEvent.Event.REQUEST_FREQUENCY_CORRECTION, request.getEvent());
                assertTrue(Math.abs(frequency - request.getValue().longValue()) < 1.01,
                    "frequency=" + frequency + " sampleRate=" + sampleRate);
            }
        }
    }

    @Test
    void adjacentSamplePhaseRetainsItsSampleRateConversion()
    {
        for(double sampleRate: new double[]{12500, 25000, 30000})
        {
            TestDecoder decoder = new TestDecoder();
            decoder.setDecimatedSampleRate(sampleRate);
            List<SourceEvent> requests = new ArrayList<>();
            decoder.setSourceEventListener(requests::add);

            for(double frequency: new double[]{-250, -100, 0, 100, 250})
            {
                decoder.processSamplePLLError((float)(2 * Math.PI * frequency / sampleRate));
                assertTrue(Math.abs(frequency - requests.getLast().getValue().longValue()) < 1.01);
            }
        }
    }

    @Test
    void retainsSignedWholeHertzTruncationAndRequiresAnExplicitMeasurementRate()
    {
        TestDecoder decoder = new TestDecoder();
        assertThrows(IllegalStateException.class, () -> decoder.processSamplePLLError(0.1f));
        assertThrows(IllegalArgumentException.class, () -> decoder.processSymbolPLLError(0.1f, 0));

        for(double invalid: new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY})
        {
            assertThrows(IllegalArgumentException.class, () -> decoder.setDecimatedSampleRate(invalid));
        }

        List<SourceEvent> requests = new ArrayList<>();
        decoder.setSourceEventListener(requests::add);
        decoder.processSymbolPLLError((float)(2 * Math.PI * 100.25 / 4800), 4800);
        decoder.processSymbolPLLError((float)(-2 * Math.PI * 100.25 / 4800), 4800);
        assertEquals(100L, requests.get(0).getValue().longValue());
        assertEquals(-100L, requests.get(1).getValue().longValue());
    }

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
