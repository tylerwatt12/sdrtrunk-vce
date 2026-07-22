/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.application.service;

import io.github.dsheirer.channel.metadata.activity.ChannelActivitySelectionDescriptor;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.ProcessingChain;

/**
 * Internal resolved handle for one browser Live selection.  This object contains live runtime references and must not
 * be serialized or retained after the request/subscription that resolved it ends.
 *
 * @param selection immutable public selection description
 * @param ownerChannel currently processing site owner, when applicable
 * @param rowChannel currently processing channel associated with the selected row, when applicable
 * @param processingChain exact frequency/timeslot chain, when currently available
 * @param eventProcessingChain chain supplying Events for the selection
 */
public record LiveContext(ChannelActivitySelectionDescriptor selection, Channel ownerChannel, Channel rowChannel,
                          ProcessingChain processingChain, ProcessingChain eventProcessingChain)
{
    public LiveContext
    {
        if(selection == null)
        {
            throw new IllegalArgumentException("Live selection cannot be null");
        }
    }

    public String selectionId()
    {
        return selection.selectionId();
    }

    public boolean isSiteSelection()
    {
        return selection.isSite();
    }

    public boolean hasExactProcessingChain()
    {
        return processingChain != null;
    }

    public boolean hasEventProcessingChain()
    {
        return eventProcessingChain != null;
    }
}
