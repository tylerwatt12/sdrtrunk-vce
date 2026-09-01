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

package io.github.dsheirer.database.upgrade;

/** A non-secret, countable migration effect for preflight and completion reporting. */
public record DatabaseMigrationEffect(Kind kind, String subject, long affectedRows, String detail)
{
    public static final long UNKNOWN_COUNT = -1;

    public enum Kind
    {
        PRESERVE,
        TRANSFORM,
        RESET,
        DROP,
        DEFAULT
    }
}
