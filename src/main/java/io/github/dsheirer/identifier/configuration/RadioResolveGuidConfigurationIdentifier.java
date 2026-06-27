/*
 * ******************************************************************************
 * sdrtrunk
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.identifier.configuration;

import io.github.dsheirer.identifier.Form;
import java.util.UUID;

/**
 * Stable RadioResolve GUID for a configured RF source.
 */
public class RadioResolveGuidConfigurationIdentifier extends ConfigurationStringIdentifier
{
    public RadioResolveGuidConfigurationIdentifier()
    {
        this(null);
    }

    public RadioResolveGuidConfigurationIdentifier(String value)
    {
        super(value, Form.RADRES_GUID);
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
                //Invalid GUID string.
            }
        }

        return false;
    }

    public static RadioResolveGuidConfigurationIdentifier create(String value)
    {
        return new RadioResolveGuidConfigurationIdentifier(value);
    }
}
