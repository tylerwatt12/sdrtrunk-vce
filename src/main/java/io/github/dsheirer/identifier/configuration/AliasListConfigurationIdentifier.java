/*
 * ******************************************************************************
 * sdrtrunk
 * Copyright (C) 2014-2018 Dennis Sheirer
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
 * *****************************************************************************
 */

package io.github.dsheirer.identifier.configuration;

import io.github.dsheirer.identifier.Form;
import java.util.Objects;

/**
 * Alias-list identity and its display name when this decode configuration was captured.
 */
public class AliasListConfigurationIdentifier extends ConfigurationStringIdentifier
{
    private final long mAliasListId;

    public AliasListConfigurationIdentifier(String value)
    {
        this(0, value);
    }

    public AliasListConfigurationIdentifier(long aliasListId, String value)
    {
        super(value, Form.ALIAS_LIST);
        if(aliasListId < 0)
        {
            throw new IllegalArgumentException("Alias list ID cannot be negative");
        }
        mAliasListId = aliasListId;
    }

    /** Stable list identity, or zero for a legacy name-only identifier. */
    public long getAliasListId()
    {
        return mAliasListId;
    }

    @Override
    public boolean isValid()
    {
        return mAliasListId > 0 || getValue() != null && !getValue().isEmpty();
    }

    public static AliasListConfigurationIdentifier create(String value)
    {
        return new AliasListConfigurationIdentifier(value);
    }

    public static AliasListConfigurationIdentifier create(long aliasListId, String value)
    {
        return new AliasListConfigurationIdentifier(aliasListId, value);
    }

    @Override
    public boolean equals(Object other)
    {
        return super.equals(other) && mAliasListId == ((AliasListConfigurationIdentifier)other).mAliasListId;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(super.hashCode(), mAliasListId);
    }
}
