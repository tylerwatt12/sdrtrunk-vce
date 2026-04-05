/*
 *
 *  * ******************************************************************************
 *  * Copyright (C) 2014-2019 Dennis Sheirer
 *  *
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *  * *****************************************************************************
 *
 *
 */

package io.github.dsheirer.identifier.talkgroup;

import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.integer.IntegerIdentifier;
import java.util.Objects;

/**
 * Talkgroup identifier.
 *
 * Note: equality is limited to the concrete talkgroup identifier type so that subclasses with additional identity
 * fields can define coherent equals/hashCode implementations.
 */
public abstract class TalkgroupIdentifier extends IntegerIdentifier
{
    /**
     * Constructs an instance
     * @param value for the talkgroup
     * @param role for the talkgroup
     */
    protected TalkgroupIdentifier(Integer value, Role role)
    {
        super(value, IdentifierClass.USER, Form.TALKGROUP, role);
    }

    @Override
    public boolean isValid()
    {
        return getValue() > 0;
    }

    /**
     * Overrides to compare the talkgroup value, identifier details, protocol, and concrete type.
     */
    @Override
    public boolean equals(Object o)
    {
        if(this == o)
        {
            return true;
        }

        if(o == null || getClass() != o.getClass())
        {
            return false;
        }

        TalkgroupIdentifier tg = (TalkgroupIdentifier)o;
        return Objects.equals(getValue(), tg.getValue()) &&
                getIdentifierClass() == tg.getIdentifierClass() &&
                getForm() == tg.getForm() &&
                getRole() == tg.getRole() &&
                getProtocol() == tg.getProtocol();
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(getClass(), getValue(), getIdentifierClass(), getForm(), getRole(), getProtocol());
    }
}
