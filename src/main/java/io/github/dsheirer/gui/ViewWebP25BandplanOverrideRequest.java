/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui;

import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import java.util.Objects;

/** Opens a new site-scoped P25 bandplan override draft in the embedded web editor. */
public final class ViewWebP25BandplanOverrideRequest extends JavaFxWindowRequest
{
    private final P25SiteIdentity mIdentity;

    public ViewWebP25BandplanOverrideRequest(P25SiteIdentity identity)
    {
        mIdentity = Objects.requireNonNull(identity, "P25 site identity cannot be null");
    }

    public P25SiteIdentity getIdentity()
    {
        return mIdentity;
    }
}
