/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui;

import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import java.util.Objects;
import java.util.UUID;

/** Opens a new site-scoped P25 bandplan override draft in the embedded web editor. */
public final class ViewWebP25BandplanOverrideRequest extends JavaFxWindowRequest
{
    private final P25SiteIdentity mIdentity;
    private final String mRadioResolveId;

    public ViewWebP25BandplanOverrideRequest(P25SiteIdentity identity, String radioResolveId)
    {
        mIdentity = Objects.requireNonNull(identity, "P25 site identity cannot be null");
        mRadioResolveId = canonicalRadioResolveId(radioResolveId);
    }

    public P25SiteIdentity getIdentity()
    {
        return mIdentity;
    }

    public String getRadioResolveId()
    {
        return mRadioResolveId;
    }

    private static String canonicalRadioResolveId(String radioResolveId)
    {
        String candidate = Objects.requireNonNull(radioResolveId, "RadioResolve ID cannot be null");

        try
        {
            String canonical = UUID.fromString(candidate).toString();

            if(canonical.equals(candidate))
            {
                return canonical;
            }
        }
        catch(IllegalArgumentException exception)
        {
            //Report one stable validation error below.
        }

        throw new IllegalArgumentException("RadioResolve ID must be a canonical lowercase UUID");
    }
}
