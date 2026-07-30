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

/**
 * Immutable name and protocol family for an alias list. The database ID is assigned once when a new definition is
 * first persisted.
 */
public final class AliasListDefinition
{
    public static final long UNASSIGNED_ID = 0L;

    private volatile long mId = UNASSIGNED_ID;
    private final String mName;
    private final AliasListFamily mFamily;

    public AliasListDefinition(String name, AliasListFamily family)
    {
        mName = name;
        mFamily = family;
    }

    public long getId()
    {
        return mId;
    }

    public void setId(long id)
    {
        if(id < UNASSIGNED_ID)
        {
            throw new IllegalArgumentException("Alias list ID cannot be negative");
        }

        mId = id;
    }

    public String getName()
    {
        return mName;
    }

    public AliasListFamily getFamily()
    {
        return mFamily;
    }

    @Override
    public String toString()
    {
        return mName;
    }
}
