/*
 * ******************************************************************************
 * sdrtrunk
 * Copyright (C) 2014-2018 Dennis Sheirer
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
package io.github.dsheirer.module.decode.tait;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.edac.CRC;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.message.Message;
import io.github.dsheirer.module.decode.tait.identifier.TaitIdentifier;
import io.github.dsheirer.protocol.Protocol;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

public class Tait1200ANIMessage extends Message
{
    private static final IntField FROM_DIGIT_1 = IntField.length8(36);
    private static final IntField FROM_DIGIT_2 = IntField.length8(44);
    private static final IntField FROM_DIGIT_3 = IntField.length8(52);
    private static final IntField FROM_DIGIT_4 = IntField.length8(60);
    private static final IntField FROM_DIGIT_5 = IntField.length8(68);
    private static final IntField FROM_DIGIT_6 = IntField.length8(76);
    private static final IntField FROM_DIGIT_7 = IntField.length8(84);
    private static final IntField FROM_DIGIT_8 = IntField.length8(92);

    private static final IntField TO_DIGIT_1 = IntField.length8(204);
    private static final IntField TO_DIGIT_2 = IntField.length8(212);
    private static final IntField TO_DIGIT_3 = IntField.length8(220);
    private static final IntField TO_DIGIT_4 = IntField.length8(228);
    private static final IntField TO_DIGIT_5 = IntField.length8(236);
    private static final IntField TO_DIGIT_6 = IntField.length8(244);
    private static final IntField TO_DIGIT_7 = IntField.length8(252);
    private static final IntField TO_DIGIT_8 = IntField.length8(260);

    private CorrectedBinaryMessage mMessage;
    private TaitIdentifier mFromIdentifier;
    private TaitIdentifier mToIdentifier;
    private List<Identifier> mIdentifiers;

    public Tait1200ANIMessage(CorrectedBinaryMessage message)
    {
        mMessage = message;
    }

    public TaitIdentifier getFromIdentifier()
    {
        if(mFromIdentifier == null)
        {
            StringBuilder sb = new StringBuilder();

            sb.append(getCharacter(FROM_DIGIT_1));
            sb.append(getCharacter(FROM_DIGIT_2));
            sb.append(getCharacter(FROM_DIGIT_3));
            sb.append(getCharacter(FROM_DIGIT_4));
            sb.append(getCharacter(FROM_DIGIT_5));
            sb.append(getCharacter(FROM_DIGIT_6));
            sb.append(getCharacter(FROM_DIGIT_7));
            sb.append(getCharacter(FROM_DIGIT_8));

            mFromIdentifier = TaitIdentifier.createFrom(sb.toString().trim());
        }

        return mFromIdentifier;
    }

    public TaitIdentifier getToIdentifier()
    {
        if(mToIdentifier == null)
        {
            StringBuilder sb = new StringBuilder();

            sb.append(getCharacter(TO_DIGIT_1));
            sb.append(getCharacter(TO_DIGIT_2));
            sb.append(getCharacter(TO_DIGIT_3));
            sb.append(getCharacter(TO_DIGIT_4));
            sb.append(getCharacter(TO_DIGIT_5));
            sb.append(getCharacter(TO_DIGIT_6));
            sb.append(getCharacter(TO_DIGIT_7));
            sb.append(getCharacter(TO_DIGIT_8));

            mToIdentifier = TaitIdentifier.createTo(sb.toString().trim());
        }

        return mToIdentifier;
    }

    public char getCharacter(IntField bits)
    {
        int value = mMessage.getInt(bits);

        return (char)value;
    }

    public boolean isValid()
    {
        return true;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();

        sb.append("ANI FROM:").append(getFromIdentifier());
        sb.append(" TO:").append(getToIdentifier());

        return sb.toString();
    }

    @Override
    public Protocol getProtocol()
    {
        return Protocol.TAIT1200;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            mIdentifiers.add(getFromIdentifier());
            mIdentifiers.add(getToIdentifier());
        }

        return mIdentifiers;
    }
}
