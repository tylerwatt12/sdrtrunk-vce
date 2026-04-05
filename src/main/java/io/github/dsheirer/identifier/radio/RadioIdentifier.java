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

package io.github.dsheirer.identifier.radio;

import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.integer.IntegerIdentifier;
import java.util.Objects;

/**
 * Radio identifier.
 *
 * Note: equality is limited to the concrete radio identifier type so that subclasses with additional identity
 * fields can define coherent equals/hashCode implementations.
 */
public abstract class RadioIdentifier extends IntegerIdentifier
{
    public RadioIdentifier(Integer value, Role role)
    {
        super(value, IdentifierClass.USER, Form.RADIO, role);
    }

    @Override
    public boolean isValid()
    {
        return getValue() > 0;
    }

    /**
     * Overrides to compare the radio value, identifier details, protocol, and concrete type.
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

        RadioIdentifier radio = (RadioIdentifier)o;
        return Objects.equals(getValue(), radio.getValue()) &&
                getIdentifierClass() == radio.getIdentifierClass() &&
                getForm() == radio.getForm() &&
                getRole() == radio.getRole() &&
                getProtocol() == radio.getProtocol();
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(getClass(), getValue(), getIdentifierClass(), getForm(), getRole(), getProtocol());
    }
}
