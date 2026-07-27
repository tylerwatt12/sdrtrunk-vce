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

package io.github.dsheirer.alias;

import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.AliasIDType;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One user-selectable alias matcher kind and its list-capability policy.
 */
public record AliasMatchDescriptor(String label, AliasIDType type, Set<AliasListFamily> families,
                                   Function<AliasListDefinition,AliasID> factory,
                                   Predicate<AliasID> matcher)
{
    public AliasMatchDescriptor
    {
        families = Set.copyOf(families);
    }

    public boolean supports(AliasListDefinition definition)
    {
        return definition != null && definition.getSystemName() != null && !definition.getSystemName().isBlank() &&
            families.contains(definition.getFamily());
    }

    public boolean matches(AliasID identifier)
    {
        return identifier != null && identifier.getType() == type && matcher.test(identifier);
    }

    public AliasID create(AliasListDefinition definition)
    {
        if(!supports(definition))
        {
            throw new IllegalArgumentException("Matcher [" + label + "] is not supported by alias list [" +
                (definition != null ? definition.getName() : null) + "]");
        }

        return factory.apply(definition);
    }

    @Override
    public String toString()
    {
        return label;
    }
}
