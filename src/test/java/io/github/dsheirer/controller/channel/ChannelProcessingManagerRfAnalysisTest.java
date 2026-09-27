/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.controller.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.SampleType;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.SourceEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChannelProcessingManagerRfAnalysisTest
{
    @Test
    void readsOnlyLiveSourceFrequenciesWithoutUsingSavedCandidates()
    {
        ProcessingChain runningA = chain(155_085_000L, true);
        ProcessingChain runningB = chain(159_030_000L, true);
        ProcessingChain sameFrequency = chain(155_085_000L, true);
        ProcessingChain stopped = chain(146_520_000L, false);
        ProcessingChain unavailable = chain(0, true);

        assertEquals(List.of(155_085_000L, 155_085_000L, 159_030_000L),
            ChannelProcessingManager.activeSourceFrequencies(
                List.of(runningB, stopped, runningA, sameFrequency, unavailable)));
    }

    private static ProcessingChain chain(long frequency, boolean processing)
    {
        Channel channel = new Channel("Test");
        channel.setDecodeConfiguration(new DecodeConfigNBFM());
        return new ProcessingChain(channel, new AliasModel())
        {
            private final Source mSource = new FixedFrequencySource(frequency);
            @Override public boolean isProcessing() { return processing; }
            @Override public Source getSource() { return mSource; }
        };
    }

    private static final class FixedFrequencySource extends Source
    {
        private final long mFrequency;

        private FixedFrequencySource(long frequency)
        {
            mFrequency = frequency;
        }

        @Override public SampleType getSampleType() { return SampleType.COMPLEX; }
        @Override public double getSampleRate() { return 2_400_000; }
        @Override public long getFrequency() { return mFrequency; }
        @Override public void reset() { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public Listener<SourceEvent> getSourceEventListener() { return ignored -> { }; }
        @Override public void setSourceEventListener(Listener<SourceEvent> listener) { }
        @Override public void removeSourceEventListener() { }
    }
}
