/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.traffic;

/**
 * Numeric identity interpretation for trunked protocols that reuse the same address range in different modes.
 */
public enum TrunkedIdentityDomain
{
    STANDARD,
    NXDN_TYPE_C,
    NXDN_TYPE_D
}
