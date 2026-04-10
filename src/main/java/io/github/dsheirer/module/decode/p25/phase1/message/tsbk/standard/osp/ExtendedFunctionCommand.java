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
import io.github.dsheirer.module.decode.p25.reference.ExtendedFunction;

import java.util.ArrayList;
import java.util.List;

/**
 * Extended function response.
 */
public class ExtendedFunctionCommand extends OSPMessage
{
    private static final IntField FUNCTION = IntField.length16(16);
    private static final IntField ARGUMENTS = IntField.length24(32);
    private static final IntField TARGET_ADDRESS = IntField.length24(56);

    private ExtendedFunction mExtendedFunction;
    private String mArguments;
    private Identifier mTargetAddress;
    private List<Identifier> mIdentifiers;

    /**
     * Constructs a TSBK from the binary message sequence.
     */
    public ExtendedFunctionCommand(P25P1DataUnitID dataUnitId, CorrectedBinaryMessage message, int nac, long timestamp)
    {
        super(dataUnitId, message, nac, timestamp);
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getMessageStub());
        sb.append(" TO:").append(getTargetAddress());
        sb.append(" FUNCTION:").append(getExtendedFunction());
        sb.append(" ARGUMENTS:").append(getArguments());
        return sb.toString();
    }

    public ExtendedFunction getExtendedFunction()
    {
        if(mExtendedFunction == null)
        {
            mExtendedFunction = ExtendedFunction.fromValue(getMessage().getInt(FUNCTION));
        }

        return mExtendedFunction;
    }

    public String getArguments()
    {
        if(mArguments == null)
        {
            mArguments = getMessage().getHex(ARGUMENTS).toUpperCase();
        }

        return mArguments;
    }

    public Identifier getTargetAddress()
    {
        if(mTargetAddress == null)
        {
            mTargetAddress = APCO25RadioIdentifier.createTo(getMessage().getInt(TARGET_ADDRESS));
        }

        return mTargetAddress;
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
