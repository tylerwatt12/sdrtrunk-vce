/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.module;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelConfigurationChangeNotification;
import io.github.dsheirer.controller.channel.DecodeEventSource;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.ComplexSource;
import io.github.dsheirer.source.SourceEvent;
import org.junit.jupiter.api.Test;

class ProcessingChainDecodeEventSourceTest
{
    @Test
    void sourceReplacementAndStopInvalidateThePreparedOrigin()
    {
        Channel channel = channel("Control", Channel.ChannelType.STANDARD);
        ProcessingChain chain = new ProcessingChain(channel, new AliasModel());

        try
        {
            assertNull(chain.getDecodeEventSource());
            TestSource first = new TestSource();
            chain.setSource(first);
            DecodeEventSource origin = chain.getDecodeEventSource();
            assertSame(origin, chain.getDecodeEventSource());
            assertSame(channel, origin.channel());
            assertSame(chain, origin.processingChain());
            assertSame(first, origin.source());

            chain.stop();
            assertNull(chain.getDecodeEventSource());
            TestSource second = new TestSource();
            chain.setSource(second);
            assertNotSame(origin, chain.getDecodeEventSource());
            assertSame(second, chain.getDecodeEventSource().source());
        }
        finally
        {
            chain.dispose();
        }
    }

    @Test
    void publishedConversionAndRollbackReplaceTheOriginWithoutChangingItsSampleSource()
    {
        Channel control = channel("Control", Channel.ChannelType.STANDARD);
        Channel traffic = channel("Traffic", Channel.ChannelType.TRAFFIC);
        ProcessingChain chain = new ProcessingChain(control, new AliasModel());

        try
        {
            chain.setSource(new TestSource());
            DecodeEventSource original = chain.getDecodeEventSource();
            var transition = chain.beginChannelConfigurationTransition(traffic);
            assertSame(original, chain.getDecodeEventSource());

            chain.publishChannelConfigurationTransition(transition);
            DecodeEventSource published = chain.getDecodeEventSource();
            assertNotSame(original, published);
            assertSame(traffic, published.channel());
            assertSame(original.source(), published.source());

            chain.rollbackChannelConfigurationTransition(transition);
            DecodeEventSource restored = chain.getDecodeEventSource();
            assertNotSame(published, restored);
            assertSame(control, restored.channel());
            assertSame(original.source(), restored.source());

            transition = chain.beginChannelConfigurationTransition(traffic);
            chain.publishChannelConfigurationTransition(transition);
            DecodeEventSource committed = chain.getDecodeEventSource();
            chain.channelConfigurationChanged(new ChannelConfigurationChangeNotification(traffic));
            chain.completeChannelConfigurationTransition(transition);
            assertSame(committed, chain.getDecodeEventSource(),
                "projection and completion must retain the origin that callbacks already use");
        }
        finally
        {
            chain.dispose();
        }
    }

    @Test
    void legacyConfigurationNotificationAlsoUpdatesTheFunctionalOrigin()
    {
        Channel control = channel("Control", Channel.ChannelType.STANDARD);
        Channel traffic = channel("Traffic", Channel.ChannelType.TRAFFIC);
        ProcessingChain chain = new ProcessingChain(control, new AliasModel());

        try
        {
            chain.setSource(new TestSource());
            DecodeEventSource original = chain.getDecodeEventSource();
            chain.channelConfigurationChanged(new ChannelConfigurationChangeNotification(traffic));
            DecodeEventSource converted = chain.getDecodeEventSource();
            assertNotSame(original, converted);
            assertSame(traffic, converted.channel());
            assertSame(chain, converted.processingChain());
            assertSame(original.source(), converted.source());
        }
        finally
        {
            chain.dispose();
        }
    }

    private static Channel channel(String name, Channel.ChannelType type)
    {
        Channel channel = new Channel(name, type);
        channel.setDecodeConfiguration(new DecodeConfigDMR());
        return channel;
    }

    private static class TestSource extends ComplexSource
    {
        @Override public void setListener(Listener<ComplexSamples> listener) {}
        @Override public Listener<SourceEvent> getSourceEventListener() { return event -> {}; }
        @Override public void setSourceEventListener(Listener<SourceEvent> listener) {}
        @Override public void removeSourceEventListener() {}
        @Override public double getSampleRate() { return 25_000; }
        @Override public long getFrequency() { return 451_000_000; }
        @Override public void reset() {}
        @Override public void start() {}
        @Override public void stop() {}
    }
}
