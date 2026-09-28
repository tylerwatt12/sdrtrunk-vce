/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.remote;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.SourceException;

/**
 * Process-local boundary between channel/decoder lifecycle and the remote transport. Implementations must keep
 * network and serialization work off decoder callbacks.
 */
public interface P25RemoteBitstreamService
{
    /** Acquires a remote decoded-bit source for a host channel. */
    Source acquireSource(Channel channel, String threadName) throws SourceException;

    /**
     * Acquires a source with transient OPEN context available before the processing chain starts. Implementations that
     * do not need stream identity can retain the simpler method above.
     */
    default Source acquireSource(Channel channel, ChannelStartProcessingRequest request, String threadName)
        throws SourceException
    {
        return acquireSource(channel, threadName);
    }

    /** Called only after a processing chain has started successfully. */
    void channelStarted(Channel channel, ChannelStartProcessingRequest request, ProcessingChain chain);

    /** Called during processing-chain teardown after source/decoder input has stopped. */
    void channelStopped(Channel channel, ProcessingChain chain);
}
