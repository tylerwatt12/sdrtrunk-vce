/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.channel.metadata.activity.ChannelActivityEvent;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityRow;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySelectionScope;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySnapshot;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityTableModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.preference.UserPreferences;
import org.junit.jupiter.api.Test;

class LiveContextResolverTest
{
    @Test
    void retainsSiteSelectionAcrossGapAndRebindsReplacement()
    {
        ChannelProcessingManager manager = manager();
        LiveContextResolver resolver = new LiveContextResolver(manager);
        resolver.start();

        try
        {
            Channel owner = new Channel("Test Site");
            ChannelActivityTableModel table = new ChannelActivityTableModel("Test Site", owner, true);
            ChannelActivityRow first = table.getOrCreate("old-control", owner,
                ChannelActivityRow.Role.CURRENT_CONTROL, 851_000_000L, null);
            ChannelActivitySnapshot firstSnapshot = ChannelActivitySnapshot.from(table);
            String selectionId = firstSnapshot.rows().getFirst().selectionId();
            resolver.receive(new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT, firstSnapshot));

            assertEquals(851_000_000L, resolver.resolve(selectionId).orElseThrow().selection().frequencyHz());

            table.remove(first);
            resolver.receive(new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT,
                ChannelActivitySnapshot.from(table)));
            assertTrue(resolver.resolve(selectionId).isPresent());

            table.getOrCreate("new-control", owner, ChannelActivityRow.Role.CURRENT_CONTROL,
                852_000_000L, null);
            resolver.receive(new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT,
                ChannelActivitySnapshot.from(table)));

            LiveContext replacement = resolver.resolve(selectionId).orElseThrow();
            assertEquals(selectionId, replacement.selectionId());
            assertEquals(852_000_000L, replacement.selection().frequencyHz());
            assertEquals(ChannelActivitySelectionScope.SITE, replacement.selection().scope());
            assertFalse(replacement.hasExactProcessingChain());
        }
        finally
        {
            resolver.close();
        }
    }

    @Test
    void removesExactSelectionWhenRowEndsAndAllSelectionsWhenTableCloses()
    {
        ChannelProcessingManager manager = manager();
        LiveContextResolver resolver = new LiveContextResolver(manager);
        resolver.start();

        try
        {
            Channel owner = new Channel("Test Site");
            ChannelActivityTableModel table = new ChannelActivityTableModel("Test Site", owner, true);
            ChannelActivityRow control = table.getOrCreate("control", owner,
                ChannelActivityRow.Role.CURRENT_CONTROL, 851_000_000L, null);
            ChannelActivityRow traffic = table.getOrCreate("traffic-slot-1", owner,
                ChannelActivityRow.Role.TRAFFIC, 852_000_000L, 1);
            ChannelActivitySnapshot populated = ChannelActivitySnapshot.from(table);
            String siteSelectionId = populated.rows().stream()
                .filter(row -> row.key().equals(control.getKey())).findFirst().orElseThrow().selectionId();
            String exactSelectionId = populated.rows().stream()
                .filter(row -> row.key().equals(traffic.getKey())).findFirst().orElseThrow().selectionId();
            resolver.receive(new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT, populated));

            assertTrue(resolver.resolve(siteSelectionId).isPresent());
            assertTrue(resolver.resolve(exactSelectionId).isPresent());

            table.remove(traffic);
            resolver.receive(new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT,
                ChannelActivitySnapshot.from(table)));
            assertTrue(resolver.resolve(siteSelectionId).isPresent());
            assertFalse(resolver.resolve(exactSelectionId).isPresent());

            resolver.receive(new ChannelActivityEvent(ChannelActivityEvent.Operation.REMOVE,
                ChannelActivitySnapshot.from(table)));
            assertFalse(resolver.resolve(siteSelectionId).isPresent());
        }
        finally
        {
            resolver.close();
        }
    }

    private static ChannelProcessingManager manager()
    {
        return new ChannelProcessingManager(null, null, null, null, new UserPreferences());
    }
}
