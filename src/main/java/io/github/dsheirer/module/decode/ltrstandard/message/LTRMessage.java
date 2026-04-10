/*
 * *****************************************************************************
 * Copyright (C) 2014-2022 Dennis Sheirer
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
package io.github.dsheirer.module.decode.ltrstandard.message;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.edac.CRC;
import io.github.dsheirer.identifier.talkgroup.LTRTalkgroup;
import io.github.dsheirer.message.Message;
import io.github.dsheirer.module.decode.ltrstandard.LtrStandardMessageType;
import io.github.dsheirer.protocol.Protocol;

/**
 * LTR Standard Base Message
 */
public abstract class LTRMessage extends Message
{
    private static final IntField AREA = IntField.range(9, 9);
    private static final IntField CHANNEL = IntField.range(10, 14);
    private static final IntField HOME_REPEATER = IntField.range(15, 19);
    private static final IntField GROUP = IntField.length8(20);
    private static final IntField FREE = IntField.range(28, 32);

    private CorrectedBinaryMessage mMessage;
    private CRC mCRC;
    private LTRTalkgroup mTalkgroup;

    /**
     * Constructs the message
     * @param message containing the raw bits
     * @param crc error check
     */
    protected LTRMessage(CorrectedBinaryMessage message, CRC crc)
    {
        mMessage = message;
        mCRC = crc;
    }

    /**
     * Identifies the type of message
     */
    public abstract LtrStandardMessageType getMessageType();

    /**
     * Indicates if this message passes the CRC check
     */
    public boolean isValid()
    {
        return mCRC.passes();
    }

    /**
     * Raw binary message
     */
    public CorrectedBinaryMessage getMessage()
    {
        return mMessage;
    }

    /**
     * CRC error check stats
     */
    public CRC getCRC()
    {
        return mCRC;
    }

    /**
     * Area: 0 or 1
     */
    public int getArea()
    {
        return getArea(mMessage);
    }

    /**
     * Logical Channel Number (LCN) for the repeater
     */
    public int getChannel()
    {
        return getChannel(mMessage);
    }

    /**
     * Home repeater number for the talkgroup
     */
    public int getHomeRepeater()
    {
        return getHomeRepeater(mMessage);
    }

    /**
     * Talkgroup number
     */
    public int getGroup()
    {
        return getGroup(mMessage);
    }

    /**
     * Free or available repeater channel for other subscribers to use when this repeater channel is busy
     */
    public int getFree()
    {
        return getFree(mMessage);
    }

    public static int getArea(CorrectedBinaryMessage message)
    {
        return message.getInt(AREA);
    }

    public static int getChannel(CorrectedBinaryMessage message)
    {
        return message.getInt(CHANNEL);
    }

    public static int getHomeRepeater(CorrectedBinaryMessage message)
    {
        return message.getInt(HOME_REPEATER);
    }

    public static int getGroup(CorrectedBinaryMessage message)
    {
        return message.getInt(GROUP);
    }

    public static int getFree(CorrectedBinaryMessage message)
    {
        return message.getInt(FREE);
    }

    /**
     * Talkgroup identifier
     */
    public LTRTalkgroup getTalkgroup()
    {
        if(mTalkgroup == null)
        {
            mTalkgroup = LTRTalkgroup.create((getArea() << 13) + (getHomeRepeater() << 8) + getGroup());
        }

        return mTalkgroup;
    }

    @Override
    public Protocol getProtocol()
    {
        return Protocol.LTR;
    }
}
