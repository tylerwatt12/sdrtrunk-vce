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

package io.github.dsheirer.identifier.esn;

import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.string.StringIdentifier;
import io.github.dsheirer.protocol.Protocol;
import java.util.Objects;
import org.apache.commons.lang3.Strings;

public class ESNIdentifier extends StringIdentifier implements Comparable<ESNIdentifier>
{
    private Protocol mProtocol;

    public ESNIdentifier(String esn, Protocol protocol, Role role)
    {
        super(esn, IdentifierClass.USER, Form.ESN, role);
        mProtocol = protocol;
    }

    @Override
    public Protocol getProtocol()
    {
        return mProtocol;
    }

    public static ESNIdentifier create(String esn, Protocol protocol, Role role)
    {
        return new ESNIdentifier(esn, protocol, role);
    }

    @Override
    public int compareTo(ESNIdentifier o)
    {
        if(o == null)
        {
            return 1;
        }

        int comparison = Strings.CS.compare(getValue(), o.getValue());

        if(comparison == 0)
        {
            comparison = getRole().compareTo(o.getRole());
        }

        if(comparison == 0)
        {
            comparison = getProtocol().compareTo(o.getProtocol());
        }

        return comparison;
    }

    @Override
    public boolean equals(Object o)
    {
        if(this == o)
        {
            return true;
        }

        if(!(o instanceof ESNIdentifier other))
        {
            return false;
        }

        return Objects.equals(getValue(), other.getValue()) &&
                getIdentifierClass() == other.getIdentifierClass() &&
                getForm() == other.getForm() &&
                getRole() == other.getRole() &&
                getProtocol() == other.getProtocol();
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(getValue(), getIdentifierClass(), getForm(), getRole(), getProtocol());
    }
}
