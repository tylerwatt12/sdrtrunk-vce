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

package io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.isp;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.ISPMessage;
import io.github.dsheirer.module.decode.p25.reference.DataServiceOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * SNDCP Reconnect Request
 */
public class SNDCPReconnectRequest extends ISPMessage
{
    private static final IntField DATA_SERVICE_OPTIONS = IntField.length8(16);
    private static final IntField DATA_ACCESS_CONTROL = IntField.length16(24);
    private static final int DATA_TO_SEND_FLAG = 40;
    private static final IntField SOURCE_ADDRESS = IntField.length24(56);

    private DataServiceOptions mDataServiceOptions;
    private Identifier mSourceAddress;
    private List<Identifier> mIdentifiers;

    /**
     * Constructs a TSBK from the binary message sequence.
     */
    public SNDCPReconnectRequest(P25P1DataUnitID dataUnitId, CorrectedBinaryMessage message, int nac, long timestamp)
    {
        super(dataUnitId, message, nac, timestamp);
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getMessageStub());
        sb.append(" FM:").append(getSourceAddress());
        sb.append(hasDataToSend() ? " HAS DATA TO SEND" : " HAS NO DATA TO SEND");
        sb.append(" ").append(getDataServiceOptions());
        sb.append(" DAC:").append(getDataAccessControl());
        return sb.toString();
    }

    public boolean hasDataToSend()
    {
        return getMessage().get(DATA_TO_SEND_FLAG);
    }

    public DataServiceOptions getDataServiceOptions()
    {
        if(mDataServiceOptions == null)
        {
            mDataServiceOptions = new DataServiceOptions(getMessage().getInt(DATA_SERVICE_OPTIONS));
        }

        return mDataServiceOptions;
    }

    public int getDataAccessControl()
    {
        return getMessage().getInt(DATA_ACCESS_CONTROL);
    }

    public Identifier getSourceAddress()
    {
        if(mSourceAddress == null)
        {
            mSourceAddress = APCO25RadioIdentifier.createFrom(getMessage().getInt(SOURCE_ADDRESS));
        }

        return mSourceAddress;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            mIdentifiers.add(getSourceAddress());
        }

        return mIdentifiers;
    }
}
