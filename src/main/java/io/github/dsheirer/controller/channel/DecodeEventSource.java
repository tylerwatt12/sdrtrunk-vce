/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.controller.channel;

import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.source.Source;

/**
 * Lifecycle-prepared decoder-event origin. A callback forwards this existing reference; an observer can reject a
 * queued event after its channel, processing chain or sample source has been replaced.
 */
public record DecodeEventSource(Channel channel, ProcessingChain processingChain, Source source)
{
}
