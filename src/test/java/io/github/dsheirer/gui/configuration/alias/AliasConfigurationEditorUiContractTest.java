/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui.configuration.alias;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Exercises the draft and deferred-selection contracts without launching a JavaFX window. */
class AliasConfigurationEditorUiContractTest
{
    @Test
    void newAndCloneProduceDetachedUnsavedDrafts()
    {
        AliasListDefinition definition = new AliasListDefinition("County", AliasListFamily.P25);
        definition.setId(11L);

        Alias created = AliasDrafts.create(definition);
        assertTrue(AliasDrafts.isNew(created));
        assertEquals(11L, created.getAliasListId());

        Alias original = new Alias("Dispatch");
        original.setId(101L);
        original.setAliasListDefinition(definition);
        original.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 1001));

        Alias cloned = AliasDrafts.cloneOf(original);
        assertTrue(AliasDrafts.isNew(cloned));
        assertEquals(original.getAliasListId(), cloned.getAliasListId());
        assertNotSame(original, cloned);
        assertNotSame(original.getMatchIdentifier(), cloned.getMatchIdentifier());
        cloned.setName("Changed clone");
        assertEquals("Dispatch", original.getName());

        assertNull(AliasDrafts.create(null));
    }

    @Test
    void selectionRefreshDrainsChangesThatArriveWhilePending()
    {
        List<Runnable> deferred = new ArrayList<>();
        AtomicInteger refreshCount = new AtomicInteger();
        AliasSelectionRefreshScheduler[] scheduler = new AliasSelectionRefreshScheduler[1];
        scheduler[0] = new AliasSelectionRefreshScheduler(deferred::add, () ->
        {
            if(refreshCount.incrementAndGet() == 1)
            {
                scheduler[0].request();
            }
        });

        scheduler[0].request();
        scheduler[0].request();
        assertEquals(1, deferred.size());

        deferred.removeFirst().run();
        assertEquals(2, refreshCount.get());
        assertTrue(deferred.isEmpty());

        scheduler[0].request();
        assertEquals(1, deferred.size());
    }

    @Test
    void explicitDraftPresentationCanCancelAnOlderSelectionRefresh()
    {
        List<Runnable> deferred = new ArrayList<>();
        AtomicInteger refreshCount = new AtomicInteger();
        AliasSelectionRefreshScheduler scheduler =
            new AliasSelectionRefreshScheduler(deferred::add, refreshCount::incrementAndGet);

        scheduler.request();
        scheduler.cancelPending();
        deferred.removeFirst().run();

        assertEquals(0, refreshCount.get());
    }
}
