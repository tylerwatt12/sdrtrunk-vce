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
import io.github.dsheirer.module.decode.p25.identifier.telephone.APCO25TelephoneNumber;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.OSPMessage;
import io.github.dsheirer.module.decode.p25.reference.Digit;

import java.util.ArrayList;
import java.util.List;

/**
 * Telephone Interconnect Answer Request.
 */
public class TelephoneInterconnectAnswerRequest extends OSPMessage
{
    private static final IntField DIGIT_1 = IntField.length4(16);
    private static final IntField DIGIT_2 = IntField.length4(20);
    private static final IntField DIGIT_3 = IntField.length4(24);
    private static final IntField DIGIT_4 = IntField.length4(28);
    private static final IntField DIGIT_5 = IntField.length4(32);
    private static final IntField DIGIT_6 = IntField.length4(36);
    private static final IntField DIGIT_7 = IntField.length4(40);
    private static final IntField DIGIT_8 = IntField.length4(44);
    private static final IntField DIGIT_9 = IntField.length4(48);
    private static final IntField DIGIT_10 = IntField.length4(52);
    private static final IntField TARGET_ADDRESS = IntField.length24(56);

    private Identifier mTelephoneNumber;
    private Identifier mTargetAddress;
    private List<Identifier> mIdentifiers;

    /**
     * Constructs a TSBK from the binary message sequence.
     */
    public TelephoneInterconnectAnswerRequest(P25P1DataUnitID dataUnitId, CorrectedBinaryMessage message, int nac, long timestamp)
    {
        super(dataUnitId, message, nac, timestamp);
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getMessageStub());
        sb.append(" FM:").append(getTelephoneNumber());
        sb.append(" TO:").append(getTargetAddress());
        return sb.toString();
    }

    public Identifier getTelephoneNumber()
    {
        if(mTelephoneNumber == null)
        {
            List<Integer> digits = new ArrayList<>();
            digits.add(getMessage().getInt(DIGIT_1));
            digits.add(getMessage().getInt(DIGIT_2));
            digits.add(getMessage().getInt(DIGIT_3));
            digits.add(getMessage().getInt(DIGIT_4));
            digits.add(getMessage().getInt(DIGIT_5));
            digits.add(getMessage().getInt(DIGIT_6));
            digits.add(getMessage().getInt(DIGIT_7));
            digits.add(getMessage().getInt(DIGIT_8));
            digits.add(getMessage().getInt(DIGIT_9));
            digits.add(getMessage().getInt(DIGIT_10));

            mTelephoneNumber = APCO25TelephoneNumber.createFrom(Digit.decode(digits));
        }

        return mTelephoneNumber;
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
            mIdentifiers.add(getTelephoneNumber());
        }

        return mIdentifiers;
    }
}
