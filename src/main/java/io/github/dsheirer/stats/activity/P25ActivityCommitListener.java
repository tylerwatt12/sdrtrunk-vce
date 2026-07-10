/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import java.util.List;

/**
 * Receives row identifiers only after detailed activity has committed to SQLite.
 */
@FunctionalInterface
public interface P25ActivityCommitListener
{
    void activityCommitted(List<Long> rowIds);
}
