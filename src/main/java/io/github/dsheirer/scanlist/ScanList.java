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

package io.github.dsheirer.scanlist;

import java.util.Locale;
import java.util.Objects;

/**
 * Administrator-owned scan-list definition. The database identity is assigned once when a new definition is first
 * persisted; all other values are immutable so runtime readers can safely retain a configuration snapshot.
 */
public final class ScanList
{
    public static final long UNASSIGNED_ID = 0L;
    public static final String DEFAULT_NAME = "Default";
    public static final int MAXIMUM_NAME_LENGTH = 100;
    public static final int MAXIMUM_DESCRIPTION_LENGTH = 1_000;

    private volatile long mId;
    private final int mSortOrder;
    private final String mName;
    private final String mDescription;
    private final boolean mPublished;
    private final boolean mDefault;

    public ScanList(String name)
    {
        this(UNASSIGNED_ID, 0, name, null, true, false);
    }

    public ScanList(long id, int sortOrder, String name, String description, boolean published,
                    boolean defaultScanList)
    {
        if(id < UNASSIGNED_ID)
        {
            throw new IllegalArgumentException("Scan-list ID cannot be negative");
        }
        if(sortOrder < 0)
        {
            throw new IllegalArgumentException("Scan-list sort order cannot be negative");
        }

        mId = id;
        mSortOrder = sortOrder;
        mName = requireText(name, "Scan-list name", MAXIMUM_NAME_LENGTH);
        mDescription = optionalText(description, "Scan-list description", MAXIMUM_DESCRIPTION_LENGTH);
        mPublished = published;
        mDefault = defaultScanList;

        if(mDefault && !mPublished)
        {
            throw new IllegalArgumentException("The default scan list must be published");
        }
    }

    /**
     * Fresh-install default. Its durable ID is assigned by the database store.
     */
    public static ScanList defaultScanList()
    {
        return new ScanList(UNASSIGNED_ID, 0, DEFAULT_NAME, null, true, true);
    }

    public long getId()
    {
        return mId;
    }

    /**
     * Assigns the generated database identity once. Reassigning a persisted definition is rejected.
     */
    public void assignId(long id)
    {
        if(id <= UNASSIGNED_ID)
        {
            throw new IllegalArgumentException("Persisted scan-list ID must be greater than zero");
        }
        if(mId != UNASSIGNED_ID && mId != id)
        {
            throw new IllegalStateException("Persisted scan-list ID cannot be changed");
        }

        mId = id;
    }

    public int getSortOrder()
    {
        return mSortOrder;
    }

    public String getName()
    {
        return mName;
    }

    public String getDescription()
    {
        return mDescription;
    }

    public boolean isPublished()
    {
        return mPublished;
    }

    public boolean isDefault()
    {
        return mDefault;
    }

    public ScanList withDefinition(int sortOrder, String name, String description, boolean published,
                                   boolean defaultScanList)
    {
        return new ScanList(getId(), sortOrder, name, description, published, defaultScanList);
    }

    String normalizedName()
    {
        return mName.toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String label, int maximumLength)
    {
        String prepared = optionalText(value, label, maximumLength);
        if(prepared == null)
        {
            throw new IllegalArgumentException(label + " is required");
        }
        return prepared;
    }

    private static String optionalText(String value, String label, int maximumLength)
    {
        if(value == null)
        {
            return null;
        }

        String prepared = value.strip();
        if(prepared.isEmpty())
        {
            return null;
        }
        if(prepared.length() > maximumLength)
        {
            throw new IllegalArgumentException(label + " cannot exceed " + maximumLength + " characters");
        }
        return prepared;
    }

    @Override
    public boolean equals(Object object)
    {
        if(this == object)
        {
            return true;
        }
        if(!(object instanceof ScanList other))
        {
            return false;
        }
        return getId() == other.getId() && mSortOrder == other.mSortOrder && mPublished == other.mPublished &&
            mDefault == other.mDefault && mName.equals(other.mName) &&
            Objects.equals(mDescription, other.mDescription);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(getId(), mSortOrder, mName, mDescription, mPublished, mDefault);
    }

    @Override
    public String toString()
    {
        return mName;
    }
}

