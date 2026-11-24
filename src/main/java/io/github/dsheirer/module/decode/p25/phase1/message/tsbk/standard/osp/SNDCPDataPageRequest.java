/*
 *
 *  * ******************************************************************************
 *  * Copyright (C) 2014-2020 Dennis Sheirer
 *  *
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *  * *****************************************************************************
 *
 *
 */

package io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.OSPMessage;
import io.github.dsheirer.module.decode.p25.reference.DataServiceOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * Sub-Network Dependent Convergence Protocol (SNDCP) Data Page Request
 */
public class SNDCPDataPageRequest extends OSPMessage
{
    private static final IntField DATA_SERVICE_OPTIONS = IntField.length8(16);
    // TIA-102.AABC-E 5.2.6: the page carries a target WUID, not a source radio address.
    private static final IntField DATA_ACCESS_CONTROL = IntField.length16(40);
    private static final IntField TARGET_ADDRESS = IntField.length24(56);

    private DataServiceOptions mServiceOptions;
    private Identifier mTargetAddress;
    private List<Identifier> mIdentifiers;

    /**
     * Constructs a TSBK from the binary message sequence.
     */
    public SNDCPDataPageRequest(P25P1DataUnitID dataUnitId, CorrectedBinaryMessage message, int nac, long timestamp)
    {
        super(dataUnitId, message, nac, timestamp);
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getMessageStub());
        sb.append(" TO:").append(getTargetAddress());
        sb.append(" DATA ACCESS CONTROL:").append(String.format("%04X", getDataAccessControl()));
        sb.append(" ").append(getServiceOptions().toString());
        return sb.toString();
    }

    /**
     * Service options for the request
     */
    public DataServiceOptions getServiceOptions()
    {
        if(mServiceOptions == null)
        {
            mServiceOptions = new DataServiceOptions(getMessage().getInt(DATA_SERVICE_OPTIONS));
        }

        return mServiceOptions;
    }

    public Identifier getTargetAddress()
    {
        if(mTargetAddress == null)
        {
            mTargetAddress = APCO25RadioIdentifier.createTo(getMessage().getInt(TARGET_ADDRESS));
        }

        return mTargetAddress;
    }

    /** Data Access Control is a control field, not a radio identity. */
    public int getDataAccessControl()
    {
        return getMessage().getInt(DATA_ACCESS_CONTROL);
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            mIdentifiers.add(getTargetAddress());
        }

        return mIdentifiers;
    }
}
