/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui;

/** Opens the embedded web Alias editor, optionally focused on one persisted Alias. */
public final class ViewWebAliasRequest extends JavaFxWindowRequest
{
    private final long mAliasListId;
    private final long mAliasId;

    public ViewWebAliasRequest()
    {
        mAliasListId = 0;
        mAliasId = 0;
    }

    public ViewWebAliasRequest(long aliasListId, long aliasId)
    {
        if(aliasListId <= 0 || aliasId <= 0)
        {
            throw new IllegalArgumentException("Alias List and Alias IDs must be positive");
        }

        mAliasListId = aliasListId;
        mAliasId = aliasId;
    }

    public long getAliasListId()
    {
        return mAliasListId;
    }

    public long getAliasId()
    {
        return mAliasId;
    }

    public boolean hasAlias()
    {
        return mAliasListId > 0;
    }
}
