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
 * Stable identifier used to correlate a configured RF source with RadioResolve.
 */
public class RadioResolveConfigurationIdentifier extends ConfigurationStringIdentifier
{
    public RadioResolveConfigurationIdentifier()
    {
        this(null);
    }

    public RadioResolveConfigurationIdentifier(String value)
    {
        super(value, Form.RADIORESOLVE_ID);
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

    public static RadioResolveConfigurationIdentifier create(String value)
    {
        return new RadioResolveConfigurationIdentifier(value);
    }
}
