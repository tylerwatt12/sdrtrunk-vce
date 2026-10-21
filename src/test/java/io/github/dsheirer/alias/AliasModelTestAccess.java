/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.alias;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Test-only observation of explicit model publications and immutable lookup snapshots. */
public final class AliasModelTestAccess
{
    private AliasModelTestAccess() { }

    public static void observeAdditions(AliasModel model, Consumer<Alias> observer)
    {
        AtomicReference<List<Alias>> previous = new AtomicReference<>(model.aliasList());
        model.addChangeListener(change -> {
            if(!change.aliasesChanged()) return;
            List<Alias> current = model.aliasList();
            List<Alias> before = previous.getAndSet(current);
            current.stream().filter(alias -> !before.contains(alias)).forEach(observer);
        });
    }

    public static Object lookupIndex(AliasList list)
    {
        try
        {
            var field = AliasList.class.getDeclaredField("mLookupIndex");
            field.setAccessible(true);
            return field.get(list);
        }
        catch(ReflectiveOperationException exception)
        {
            throw new AssertionError("Unable to inspect the prepared alias lookup", exception);
        }
    }
}
