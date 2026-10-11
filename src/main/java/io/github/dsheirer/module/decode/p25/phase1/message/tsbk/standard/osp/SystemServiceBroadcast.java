/*
 * ******************************************************************************
 * sdrtrunk
 * Copyright (C) 2014-2019 Dennis Sheirer
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
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.OSPMessage;
import io.github.dsheirer.module.decode.p25.reference.P25WuidLifetime;
import io.github.dsheirer.module.decode.p25.reference.Service;

import java.util.Collections;
import java.util.List;

/**
 * System services broadcast.
 */
public class SystemServiceBroadcast extends OSPMessage
{
    private static final IntField TWUID_VALIDITY = IntField.length8(16);
    private static final IntField AVAILABLE_SERVICES = IntField.length24(24);
    private static final IntField SUPPORTED_SERVICES = IntField.length24(48);

    private List<Service> mAvailableServices;
    private List<Service> mSupportedServices;


    /**
     * Constructs a TSBK from the binary message sequence.
     */
    public SystemServiceBroadcast(P25P1DataUnitID dataUnitId, CorrectedBinaryMessage message, int nac, long timestamp)
    {
        super(dataUnitId, message, nac, timestamp);
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getMessageStub());
        sb.append(" AVAILABLE SERVICES ").append(getAvailableServices());
        sb.append(" SUPPORTED SERVICES ").append(getSupportedServices());
        sb.append(" ").append(getTemporaryWUIDValidity());
        return sb.toString();
    }

    /** Received WUID validity, normalized to minutes; zero denotes a received no-expiry value. */
    public Integer getTemporaryWUIDValidityMinutes()
    {
        return P25WuidLifetime.decodeMinutes(getMessage().getInt(TWUID_VALIDITY));
    }

    public String getTemporaryWUIDValidity()
    {
        return P25WuidLifetime.describe(getMessage().getInt(TWUID_VALIDITY));
    }

    public List<Service> getAvailableServices()
    {
        if(mAvailableServices == null)
        {
            mAvailableServices = Service.getServices(getMessage().getInt(AVAILABLE_SERVICES));
        }

        return mAvailableServices;
    }

    public List<Service> getSupportedServices()
    {
        if(mSupportedServices == null)
        {
            mSupportedServices = Service.getServices(getMessage().getInt(SUPPORTED_SERVICES));
        }

        return mSupportedServices;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        return Collections.emptyList();
    }
}
