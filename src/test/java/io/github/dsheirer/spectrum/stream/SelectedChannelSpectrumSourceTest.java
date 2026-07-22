/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.spectrum.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.ComplexSource;
import io.github.dsheirer.source.SourceEvent;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SelectedChannelSpectrumSourceTest
{
    @Test
    void tapsAnAlreadyRunningChainAndDetachesCompletely() throws Exception
    {
        Channel channel = new Channel("Spectrum test");
        ProcessingChain chain = new ProcessingChain(channel, new AliasModel());
        TestComplexSource sampleSource = new TestComplexSource(851_012_500L, 48_000.0);
        chain.setSource(sampleSource);
        chain.start();
        int modulesBefore = chain.getModules().size();

        try(SelectedChannelSpectrumSource source =
                new SelectedChannelSpectrumSource(chain, 4, sampleSource.getFrequency(),
                    Math.round(sampleSource.getSampleRate())))
        {
            assertEquals(modulesBefore + 1, chain.getModules().size());

            for(int x = 0; x < 8; x++)
            {
                sampleSource.emit(tone(SelectedChannelSpectrumSource.FFT_SIZE, x));
                Thread.sleep(15);
            }

            SpectrumFrame frame = source.poll(Duration.ofSeconds(2));
            assertNotNull(frame);
            assertEquals(4, frame.getTargetGeneration());
            assertEquals(851_012_500L, frame.getCenterFrequencyHz());
            assertEquals(48_000L, frame.getSampleRateHz());
            assertEquals(SelectedChannelSpectrumSource.FFT_SIZE, frame.getFftSize());
            assertEquals(SelectedChannelSpectrumSource.FFT_SIZE, frame.getBinCount());
            assertEquals(SpectrumEncoding.FLOAT32, frame.getEncoding());
        }

        assertEquals(modulesBefore, chain.getModules().size());
        assertFalse(chain.getModules().stream().anyMatch(module ->
            module instanceof io.github.dsheirer.sample.complex.ComplexSamplesToNativeBufferModule));
        chain.stop();
    }

    private static ComplexSamples tone(int count, int phaseOffset)
    {
        float[] i = new float[count];
        float[] q = new float[count];

        for(int x = 0; x < count; x++)
        {
            double phase = 2.0 * Math.PI * (x + phaseOffset) / 32.0;
            i[x] = (float)Math.cos(phase);
            q[x] = (float)Math.sin(phase);
        }

        return new ComplexSamples(i, q, System.currentTimeMillis());
    }

    private static final class TestComplexSource extends ComplexSource
    {
        private final long mFrequency;
        private final double mSampleRate;
        private Listener<ComplexSamples> mListener;
        private Listener<SourceEvent> mSourceEventListener;

        private TestComplexSource(long frequency, double sampleRate)
        {
            mFrequency = frequency;
            mSampleRate = sampleRate;
        }

        private void emit(ComplexSamples samples)
        {
            Listener<ComplexSamples> listener = mListener;

            if(listener != null)
            {
                listener.receive(samples);
            }
        }

        @Override
        public void setListener(Listener<ComplexSamples> listener)
        {
            mListener = listener;
        }

        @Override
        public Listener<SourceEvent> getSourceEventListener()
        {
            return event -> { };
        }

        @Override
        public void setSourceEventListener(Listener<SourceEvent> listener)
        {
            mSourceEventListener = listener;
        }

        @Override
        public void removeSourceEventListener()
        {
            mSourceEventListener = null;
        }

        @Override
        public double getSampleRate()
        {
            return mSampleRate;
        }

        @Override
        public long getFrequency()
        {
            return mFrequency;
        }

        @Override
        public void reset()
        {
        }

        @Override
        public void start()
        {
        }

        @Override
        public void stop()
        {
        }
    }
}
