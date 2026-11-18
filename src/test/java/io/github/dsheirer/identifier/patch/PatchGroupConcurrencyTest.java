/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.identifier.patch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for concurrent mutation of a patch group's member lists.
 *
 * The decoder thread adds and removes patched talkgroups while the activity model
 * stringifies the patch group to render a snapshot row. When the member lists were
 * plain ArrayLists this produced a ConcurrentModificationException that dropped the
 * activity observation (issue #95).
 */
class PatchGroupConcurrencyTest
{
    /**
     * Stringifying a patch group while members are being added must not throw.
     */
    @Test
    void toStringIsSafeWhileTalkgroupsAreAdded()
    {
        PatchGroup patchGroup = new PatchGroup(APCO25Talkgroup.create(20), 3);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread writer = new Thread(() ->
        {
            int talkgroup = 100;

            while(running.get())
            {
                patchGroup.addPatchedTalkgroup(APCO25Talkgroup.create(talkgroup++));
            }
        }, "patch-group-writer");

        writer.start();

        try
        {
            assertDoesNotThrow(() ->
            {
                for(int x = 0; x < 20_000; x++)
                {
                    patchGroup.toString();
                }
            });
        }
        catch(Throwable t)
        {
            failure.set(t);
            throw t;
        }
        finally
        {
            running.set(false);

            try
            {
                writer.join(2_000);
            }
            catch(InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }

        if(failure.get() != null)
        {
            throw new AssertionError("concurrent toString failed", failure.get());
        }
    }

    /**
     * Iterating the returned member lists while members are added must not throw.
     */
    @Test
    void memberListsAreSafeToIterateDuringMutation()
    {
        PatchGroup patchGroup = new PatchGroup(APCO25Talkgroup.create(21), 4);
        AtomicBoolean running = new AtomicBoolean(true);

        Thread writer = new Thread(() ->
        {
            int talkgroup = 500;

            while(running.get())
            {
                patchGroup.addPatchedTalkgroup(APCO25Talkgroup.create(talkgroup++));
            }
        }, "patch-group-writer-2");

        writer.start();

        try
        {
            assertDoesNotThrow(() ->
            {
                for(int x = 0; x < 5_000; x++)
                {
                    patchGroup.getPatchedTalkgroupIdentifiers().forEach(identifier -> identifier.toString());
                    patchGroup.hasPatchedTalkgroups();
                }
            });
        }
        finally
        {
            running.set(false);

            try
            {
                writer.join(2_000);
            }
            catch(InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }
    }
}
