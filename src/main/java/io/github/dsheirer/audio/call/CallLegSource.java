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
package io.github.dsheirer.audio.call;

import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;

/**
 * Immutable configured and learned source identity captured when a decoder call leg is created.
 *
 * <p>The Alias List database identifier is intentionally carried instead of relying only on the mutable Alias List
 * object or its display name.  A zero identifier means that the list has not been durably assigned.  Learned P25
 * identity is nullable because conventional channels and newly started trunked channels may not have one.</p>
 */
public record CallLegSource(DecoderType decoderType, String channelConfigurationId, String channelName,
                            String siteGuid, long aliasListId, P25SiteIdentity p25SiteIdentity,
                            boolean trafficChannel)
{
    public static final CallLegSource UNKNOWN = new CallLegSource(null, null, null, null, 0, null, false);

    public CallLegSource
    {
        channelConfigurationId = normalize(channelConfigurationId);
        channelName = normalize(channelName);
        siteGuid = normalize(siteGuid);
    }

    public boolean hasDurableAliasListId()
    {
        return aliasListId > 0;
    }

    public boolean hasLearnedP25SiteIdentity()
    {
        return p25SiteIdentity != null;
    }

    /**
     * Returns this source classified as a trunked traffic channel.  DMR Capacity Plus can convert an already-running
     * rest-channel processing chain into a traffic chain, so audio modules must be able to publish that committed
     * lifecycle change without mutating source evidence already handed to another thread.
     */
    public CallLegSource asTrafficChannel()
    {
        return trafficChannel ? this : new CallLegSource(decoderType, channelConfigurationId, channelName, siteGuid,
            aliasListId, p25SiteIdentity, true);
    }

    private static String normalize(String value)
    {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}
