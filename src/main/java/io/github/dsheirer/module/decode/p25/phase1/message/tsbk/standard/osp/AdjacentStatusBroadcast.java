/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Lra;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Rfss;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Site;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBandReceiver;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.OSPMessage;
import io.github.dsheirer.module.decode.p25.reference.SystemServiceClass;
import java.util.ArrayList;
import java.util.List;

/**
 * Adjacent status broadcast - neighbor sites
 */
public class AdjacentStatusBroadcast extends OSPMessage implements IFrequencyBandReceiver
{
    private static final IntField LOCATION_REGISTRATION_AREA = IntField.length8(16);
    private static final int CONVENTIONAL_CHANNEL_FLAG = 24;
    private static final int SITE_FAILURE_FLAG = 25;
    private static final int VALID_INFORMATION_FLAG = 26;
    private static final int ACTIVE_NETWORK_CONNECTION_TO_RFSS_CONTROLLER_FLAG = 27;
    private static final IntField SYSTEM = IntField.length12(28);
    private static final IntField RFSS = IntField.length8(40);
    private static final IntField SITE = IntField.length8(48);
    private static final IntField FREQUENCY_BAND = IntField.length4(56);
    private static final IntField CHANNEL_NUMBER = IntField.length12(60);
    private static final IntField SYSTEM_SERVICE_CLASS = IntField.length8(72);

    private Identifier mLocationRegistrationArea;
    private Identifier mSystem;
    private Identifier mSite;
    private Identifier mRfss;
    private IChannelDescriptor mChannel;
    private SystemServiceClass mSystemServiceClass;
    private List<Identifier> mIdentifiers;
    private List<String> mSiteFlags;

    /**
     * Constructs a TSBK from the binary message sequence.
     */
    public AdjacentStatusBroadcast(P25P1DataUnitID dataUnitId, CorrectedBinaryMessage message, int nac, long timestamp)
    {
        super(dataUnitId, message, nac, timestamp);
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getMessageStub());
        sb.append(" SYSTEM:").append(getSystem());
        sb.append(" LRA:").append(getLocationRegistrationArea());
        sb.append(" RFSS:").append(getRfss());
        sb.append(" SITE:").append(getSite());
        sb.append(" CHANNEL:").append(getChannel());
        sb.append(" FLAGS ").append(getSiteFlags());
        sb.append(" SERVICES ").append(getSystemServiceClass());
        return sb.toString();
    }

    public List<String> getSiteFlags()
    {
        if(mSiteFlags == null)
        {
            mSiteFlags = new ArrayList<>();

            if(isConventionalChannel())
            {
                mSiteFlags.add("CONVENTIONAL CHANNEL");
            }

            if(isFailedConditionSite())
            {
                mSiteFlags.add("FAILURE CONDITION");
            }

            if(isValidSiteInformation())
            {
                mSiteFlags.add("VALID INFORMATION");
            }

            if(isActiveNetworkConnectionToRfssControllerSite())
            {
                mSiteFlags.add("ACTIVE RFSS CONNECTION");
            }
        }

        return mSiteFlags;
    }

    /**
     * Indicates if the channel is a conventional repeater channel
     */
    public boolean isConventionalChannel()
    {
        return getMessage().get(CONVENTIONAL_CHANNEL_FLAG);
    }

    /**
     * Indicates if the site is in a failure condition
     */
    public boolean isFailedConditionSite()
    {
        return getMessage().get(SITE_FAILURE_FLAG);
    }

    /**
     * Indicates if the site information is valid
     */
    public boolean isValidSiteInformation()
    {
        return getMessage().get(VALID_INFORMATION_FLAG);
    }

    /**
     * Indicates if the site has an active network connection to the RFSS controller
     */
    public boolean isActiveNetworkConnectionToRfssControllerSite()
    {
        return getMessage().get(ACTIVE_NETWORK_CONNECTION_TO_RFSS_CONTROLLER_FLAG);
    }

    public Identifier getLocationRegistrationArea()
    {
        if(mLocationRegistrationArea == null)
        {
            mLocationRegistrationArea = APCO25Lra.create(getMessage().getInt(LOCATION_REGISTRATION_AREA));
        }

        return mLocationRegistrationArea;
    }

    public Identifier getSystem()
    {
        if(mSystem == null)
        {
            mSystem = APCO25System.create(getMessage().getInt(SYSTEM));
        }

        return mSystem;
    }

    public Identifier getSite()
    {
        if(mSite == null)
        {
            mSite = APCO25Site.create(getMessage().getInt(SITE));
        }

        return mSite;
    }

    public Identifier getRfss()
    {
        if(mRfss == null)
        {
            mRfss = APCO25Rfss.create(getMessage().getInt(RFSS));
        }

        return mRfss;
    }

    public IChannelDescriptor getChannel()
    {
        if(mChannel == null)
        {
            mChannel = APCO25Channel.create(getMessage().getInt(FREQUENCY_BAND), getMessage().getInt(CHANNEL_NUMBER));
        }

        return mChannel;
    }

    public SystemServiceClass getSystemServiceClass()
    {
        if(mSystemServiceClass == null)
        {
            mSystemServiceClass = new SystemServiceClass(getMessage().getInt(SYSTEM_SERVICE_CLASS));
        }

        return mSystemServiceClass;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            mIdentifiers.add(getLocationRegistrationArea());
            mIdentifiers.add(getSystem());
            mIdentifiers.add(getSite());
            mIdentifiers.add(getRfss());
        }

        return mIdentifiers;
    }

    @Override
    public List<IChannelDescriptor> getChannels()
    {
        List<IChannelDescriptor> channels = new ArrayList<>();
        channels.add(getChannel());
        return channels;
    }
}
