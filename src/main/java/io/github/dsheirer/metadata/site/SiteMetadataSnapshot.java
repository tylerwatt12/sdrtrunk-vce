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

package io.github.dsheirer.metadata.site;

import io.github.dsheirer.protocol.Protocol;

/**
 * Protocol-neutral contract for an immutable, over-the-air site configuration snapshot.
 */
public interface SiteMetadataSnapshot
{
    /**
     * Air-interface protocol represented by this snapshot.
     */
    Protocol protocol();

    /**
     * Decoder or protocol variant that produced this snapshot.
     */
    String decoder();

    /**
     * Protocol variant suitable for a generic consumer label.
     */
    default String variant()
    {
        return decoder();
    }

    /**
     * Indicates if the snapshot contains meaningful learned site configuration.
     */
    boolean isUseful();
}
