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
import io.github.dsheirer.module.decode.p25.reference.AnswerResponse;
import io.github.dsheirer.module.decode.p25.reference.DataServiceOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * SNDCP Data Channel Request
 */
public class SNDCPDataPageResponse extends ISPMessage
{
    private static final IntField DATA_SERVICE_OPTIONS = IntField.length8(16);
    private static final IntField ANSWER_RESPONSE = IntField.length8(24);
    private static final IntField DATA_ACCESS_CONTROL = IntField.length16(32);
    private static final IntField SOURCE_ADDRESS = IntField.length24(56);

    private DataServiceOptions mDataServiceOptions;
    private Identifier mSourceAddress;
    private List<Identifier> mIdentifiers;

    /**
     * Constructs a TSBK from the binary message sequence.
     */
    public SNDCPDataPageResponse(P25P1DataUnitID dataUnitId, CorrectedBinaryMessage message, int nac, long timestamp)
    {
        super(dataUnitId, message, nac, timestamp);
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getMessageStub());
        sb.append(" FM:").append(getSourceAddress());
        sb.append(" RESPONSE:").append(getAnswerResponse());
        sb.append(" ").append(getDataServiceOptions());
        sb.append(" DAC:").append(getDataAccessControl());
        return sb.toString();
    }

    public AnswerResponse getAnswerResponse()
    {
        return AnswerResponse.fromValue(getMessage().getInt(ANSWER_RESPONSE));
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
