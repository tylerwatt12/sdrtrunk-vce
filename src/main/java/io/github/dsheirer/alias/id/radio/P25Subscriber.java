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
package io.github.dsheirer.alias.id.radio;

import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.AliasIDType;
import io.github.dsheirer.identifier.radio.P25SubscriberIdentityFormatter;
import io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity;
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
import java.util.Objects;

/**
 * Exact Alias matcher for a permanent P25 subscriber identity.  It never matches a temporary working unit address.
 */
public class P25Subscriber extends AliasID
{
    private int mHomeWacn;
    private int mHomeSystemId;
    private int mSubscriberId;

    public P25Subscriber()
    {
        //No-arg construction for editor and configuration deserialization.
    }

    public P25Subscriber(int homeWacn, int homeSystemId, int subscriberId)
    {
        mHomeWacn = homeWacn;
        mHomeSystemId = homeSystemId;
        mSubscriberId = subscriberId;
    }

    public int getHomeWacn()
    {
        return mHomeWacn;
    }

    public void setHomeWacn(int homeWacn)
    {
        mHomeWacn = homeWacn;
        updateValueProperty();
    }

    public int getHomeSystemId()
    {
        return mHomeSystemId;
    }

    public void setHomeSystemId(int homeSystemId)
    {
        mHomeSystemId = homeSystemId;
        updateValueProperty();
    }

    public int getSubscriberId()
    {
        return mSubscriberId;
    }

    public void setSubscriberId(int subscriberId)
    {
        mSubscriberId = subscriberId;
        updateValueProperty();
    }

    public P25SubscriberIdentity identity()
    {
        return new P25SubscriberIdentity(mHomeWacn, mHomeSystemId, mSubscriberId);
    }

    @Override
    public AliasIDType getType()
    {
        return AliasIDType.P25_SUBSCRIBER_IDENTITY;
    }

    @Override
    public boolean matches(AliasID id)
    {
        return id instanceof P25Subscriber other && mHomeWacn == other.mHomeWacn &&
            mHomeSystemId == other.mHomeSystemId && mSubscriberId == other.mSubscriberId;
    }

    @Override
    public boolean isValid()
    {
        return mHomeWacn >= 0 && mHomeWacn <= 0xFFFFF && mHomeSystemId >= 0 && mHomeSystemId <= 0xFFF &&
            mSubscriberId >= 1 && mSubscriberId <= RadioSystemIdentityKey.MAX_P25_RADIO_ID;
    }

    @Override
    public boolean equals(Object object)
    {
        return this == object || object instanceof P25Subscriber other && matches(other);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(mHomeWacn, mHomeSystemId, mSubscriberId);
    }

    @Override
    public String toString()
    {
        if(!isValid())
        {
            return "P25 Subscriber: **NOT VALID**";
        }

        return "P25 Subscriber: " + P25SubscriberIdentityFormatter.format(mHomeWacn, mHomeSystemId, mSubscriberId);
    }
}
