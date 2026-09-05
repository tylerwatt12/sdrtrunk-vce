/* Copyright (C) 2026 Dennis Sheirer. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.alias;

import java.util.Objects;

/** RadioReference owns only these descriptive fields on an existing alias. */
public final class RadioReferenceAliasFields
{
    private RadioReferenceAliasFields() {}

    public static Alias replacement(Alias existing, String name, String description, String group,
                                    boolean groupProvided)
    {
        Alias result = AliasFactory.copyOf(existing);
        result.setId(existing.getId());
        result.setName(name);
        result.setDescription(description);
        if(groupProvided) result.setGroup(group);
        return result;
    }

    public static boolean identical(Alias existing, String name, String description, String group,
                                    boolean groupProvided)
    {
        return existing != null && same(existing.getName(), name) && same(existing.getDescription(), description) &&
            (!groupProvided || same(existing.getGroup(), group));
    }

    private static boolean same(String left, String right)
    {
        return Objects.equals(left == null ? "" : left.trim(), right == null ? "" : right.trim());
    }
}
