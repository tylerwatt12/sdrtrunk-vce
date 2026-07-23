/*
 * ******************************************************************************
 * sdrtrunk
 * Copyright (C) 2026 Dennis Sheirer
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
import java.util.UUID;

/**
 * Stable internal identity of the saved channel configuration that owns a processing chain.
 */
public class ChannelConfigurationIdentifier extends ConfigurationStringIdentifier
{
    public ChannelConfigurationIdentifier()
    {
        this(null);
    }

    public ChannelConfigurationIdentifier(String value)
    {
        super(value, Form.UNIQUE_ID);
    }

    @Override
    public boolean isValid()
    {
        if(getValue() != null && !getValue().isBlank())
        {
            try
            {
                UUID.fromString(getValue());
                return true;
            }
            catch(IllegalArgumentException _)
            {
                //Invalid UUID string.
            }
        }

        return false;
    }

    public static ChannelConfigurationIdentifier create(String value)
    {
        return new ChannelConfigurationIdentifier(value);
    }
}
