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
    private final String mConfigurationId;

    public ViewWebP25BandplanOverrideRequest(P25SiteIdentity identity, String configurationId)
    {
        mIdentity = Objects.requireNonNull(identity, "P25 site identity cannot be null");
        mConfigurationId = canonicalConfigurationId(configurationId);
    }

    public P25SiteIdentity getIdentity()
    {
        return mIdentity;
    }

    public String getConfigurationId()
    {
        return mConfigurationId;
    }

    private static String canonicalConfigurationId(String configurationId)
    {
        String candidate = Objects.requireNonNull(configurationId, "Channel configuration ID cannot be null");

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

        throw new IllegalArgumentException("Channel configuration ID must be a canonical lowercase UUID");
    }
}
