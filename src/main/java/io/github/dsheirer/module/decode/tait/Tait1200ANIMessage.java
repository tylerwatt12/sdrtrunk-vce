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
    private static final int[] FROM_DIGIT_1 = {36, 37, 38, 39, 40, 41, 42, 43};
    private static final int[] FROM_DIGIT_2 = {44, 45, 46, 47, 48, 49, 50, 51};
    private static final int[] FROM_DIGIT_3 = {52, 53, 54, 55, 56, 57, 58, 59};
    private static final int[] FROM_DIGIT_4 = {60, 61, 62, 63, 64, 65, 66, 67};
    private static final int[] FROM_DIGIT_5 = {68, 69, 70, 71, 72, 73, 74, 75};
    private static final int[] FROM_DIGIT_6 = {76, 77, 78, 79, 80, 81, 82, 83};
    private static final int[] FROM_DIGIT_7 = {84, 85, 86, 87, 88, 89, 90, 91};
    private static final int[] FROM_DIGIT_8 = {92, 93, 94, 95, 96, 97, 98, 99};

    private static final int[] TO_DIGIT_1 = {204, 205, 206, 207, 208, 209, 210, 211};
    private static final int[] TO_DIGIT_2 = {212, 213, 214, 215, 216, 217, 218, 219};
    private static final int[] TO_DIGIT_3 = {220, 221, 222, 223, 224, 225, 226, 227};
    private static final int[] TO_DIGIT_4 = {228, 229, 230, 231, 232, 233, 234, 235};
    private static final int[] TO_DIGIT_5 = {236, 237, 238, 239, 240, 241, 242, 243};
    private static final int[] TO_DIGIT_6 = {244, 245, 246, 247, 248, 249, 250, 251};
    private static final int[] TO_DIGIT_7 = {252, 253, 254, 255, 256, 257, 258, 259};
    private static final int[] TO_DIGIT_8 = {260, 261, 262, 263, 264, 265, 266, 267};

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

    public char getCharacter(int[] bits)
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
