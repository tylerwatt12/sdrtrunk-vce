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
package io.github.dsheirer.identifier.alias;

/**
 * Provides access to the shared talker-alias cache for a decoder system.
 */
public interface TalkerAliasManagerProvider
{
    TalkerAliasManager getTalkerAliasManager();
}
