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
package io.github.dsheirer.preference.record;

/** Controls where newly completed call recordings are written. */
public enum RecordingMode
{
    /** Existing flat folder and filename behavior for external recording consumers. */
    CLASSIC,
    /** Date/channel/destination folders and a searchable recording catalog. */
    MANAGED
}
