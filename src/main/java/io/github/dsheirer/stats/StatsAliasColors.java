/* Copyright (C) 2026 Dennis Sheirer. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.stats;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.identifier.Identifier;
import java.util.Objects;

/** Web presentation only; callers run on observer workers or HTTP request threads. */
final class StatsAliasColors
{
    private StatsAliasColors() {}

    /** Use the alias that supplied the displayed metadata, including recording-priority destination choices. */
    static Integer color(AliasList list, Identifier<?> identity, String name, String description, String group)
    {
        if(list == null || identity == null || name == null || name.isBlank()) return null;
        Integer color = null;
        for(Alias alias: list.getAliases(identity))
        {
            if(Objects.equals(name, clean(alias.getName())) &&
                Objects.equals(description, clean(alias.getDescription())) && Objects.equals(group, clean(alias.getGroup())))
            {
                if(color != null && color.intValue() != alias.getColor()) return null;
                color = alias.getColor();
            }
        }
        return color;
    }

    private static String clean(String value)
    {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
