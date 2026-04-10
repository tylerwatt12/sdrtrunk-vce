/*
 * *****************************************************************************
 * Copyright (C) 2014-2023 Dennis Sheirer
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
package io.github.dsheirer.module.decode.ltrnet.message;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.edac.CRC;
import io.github.dsheirer.edac.CRCLTR;
import io.github.dsheirer.identifier.talkgroup.LTRTalkgroup;
import io.github.dsheirer.message.Message;
import io.github.dsheirer.message.MessageDirection;
import io.github.dsheirer.module.decode.ltrnet.LtrNetMessageType;
import io.github.dsheirer.protocol.Protocol;

public abstract class LtrNetMessage extends Message
{
    protected static final IntField SYNC = IntField.range(0, 8);
    protected static final IntField AREA = IntField.range(9, 9);
    protected static final IntField CHANNEL = IntField.range(10, 14);
    protected static final IntField HOME_REPEATER = IntField.range(15, 19);
    protected static final IntField GROUP = IntField.length8(20);
    protected static final IntField FREE = IntField.range(28, 32);
    protected static final IntField CRC_FIELD = IntField.range(33, 39);
    protected static final IntField SIXTEEN_BITS = IntField.length16(17);

    protected CorrectedBinaryMessage mMessage;
    protected CRC mCRC;
    private LTRTalkgroup mTalkgroup;
    private MessageDirection mMessageDirection;

    protected LtrNetMessage(CorrectedBinaryMessage message, MessageDirection direction, long timestamp)
    {
        super(timestamp);
        mMessage = message;
        mMessageDirection = direction;
        mCRC = CRCLTR.check(message, direction);
    }

    /**
     * Message direction: outbound (OSW) from the repeater or inbound (ISW) to the repeater
     * @return message direction
     */
    public MessageDirection getMessageDirection()
    {
        return mMessageDirection;
    }

    /**
     * Underlying binary message
     */
    protected CorrectedBinaryMessage getMessage()
    {
        return mMessage;
    }

    public abstract LtrNetMessageType getLtrNetMessageType();

    @Override
    public Protocol getProtocol()
    {
        return Protocol.LTR_NET;
    }

    /**
     * Talkgroup identifier
     */
    public LTRTalkgroup getTalkgroup()
    {
        if(mTalkgroup == null)
        {
            mTalkgroup = LTRTalkgroup.create((getArea(getMessage()) << 13) + (getHomeRepeater(getMessage()) << 8) +
                getGroup(getMessage()));
        }

        return mTalkgroup;
    }

    public boolean isValid()
    {
        return mCRC.passes();
    }

    public CRC getCRC()
    {
        return mCRC;
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
}
