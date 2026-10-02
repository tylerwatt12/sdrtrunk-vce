/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.controller.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager.StopCurrentResult;
import io.github.dsheirer.controller.channel.ChannelProcessingManager.TunerChannelAssignment;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.preference.UserPreferences;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChannelProcessingManagerConditionalStopTest
{
    @Test
    void staleIncarnationCannotStopAReplacementChain() throws Exception
    {
        AliasModel aliases = new AliasModel();
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, aliases,
            new UserPreferences());
        Channel channel = new Channel("Control");
        ProcessingChain original = new ProcessingChain(channel, aliases);
        install(manager, channel, original, 101L);
        TunerChannelAssignment stale = ChannelProcessingManager.tunerChannelAssignment(channel,
            851_012_500L, 101L);

        manager.stop(channel);
        ProcessingChain replacement = new ProcessingChain(channel, aliases);
        install(manager, channel, replacement, 102L);

        try
        {
            assertEquals(StopCurrentResult.NOT_OWNED, manager.stopIfCurrent(stale));
            assertSame(replacement, manager.getProcessingChain(channel));

            TunerChannelAssignment current = ChannelProcessingManager.tunerChannelAssignment(channel,
                851_012_500L, 102L);
            assertEquals(StopCurrentResult.CLAIMED, manager.stopIfCurrent(current));
            assertNull(manager.getProcessingChain(channel));
        }
        finally { manager.close(); }
    }

    @Test
    void claimedCleanupFailureRemainsDistinguishableFromNotOwned() throws Exception
    {
        AliasModel aliases = new AliasModel();
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, aliases,
            new UserPreferences());
        Channel channel = new Channel("Control");
        ProcessingChain failing = new ProcessingChain(channel, aliases)
        {
            @Override public void stop() { throw new IllegalStateException("cleanup failed"); }
        };
        install(manager, channel, failing, 201L);
        TunerChannelAssignment current = ChannelProcessingManager.tunerChannelAssignment(channel,
            851_012_500L, 201L);

        try
        {
            assertEquals(StopCurrentResult.CLAIMED_WITH_CLEANUP_FAILURE, manager.stopIfCurrent(current));
            assertNull(manager.getProcessingChain(channel));
            assertEquals(StopCurrentResult.NOT_OWNED, manager.stopIfCurrent(current));
        }
        finally { manager.close(); }
    }

    @SuppressWarnings("unchecked")
    private static void install(ChannelProcessingManager manager, Channel channel, ProcessingChain chain,
                                long incarnation) throws Exception
    {
        Field mapField = ChannelProcessingManager.class.getDeclaredField("mProcessingChainsMap");
        mapField.setAccessible(true);
        channel.activateProcessingIncarnation(incarnation);
        ((Map<Channel,ProcessingChain>)mapField.get(manager)).put(channel, chain);
        chain.getEventBus().register(manager);
    }
}
