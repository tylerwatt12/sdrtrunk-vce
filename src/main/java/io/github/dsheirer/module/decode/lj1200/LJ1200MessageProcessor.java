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
package io.github.dsheirer.module.decode.lj1200;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.sample.Listener;

public class LJ1200MessageProcessor implements Listener<CorrectedBinaryMessage>
{

    private static final IntField SYNC = IntField.length16(0);

    private static final int SYNC_TOWER = 0x550F;
    private static final int SYNC_TRANSPONDER = 0x2AD5;

    private Listener<IMessage> mMessageListener;

    public void dispose()
    {
        mMessageListener = null;
    }

    @Override
    public void receive(CorrectedBinaryMessage message)
    {
        int sync = message.getInt(SYNC);

        if(sync == SYNC_TOWER && mMessageListener != null)
        {
            mMessageListener.receive(new LJ1200Message(message));
        }
        else if(sync == SYNC_TRANSPONDER && mMessageListener != null)
        {
            mMessageListener.receive(new LJ1200TransponderMessage(message));
        }
    }

    public void setMessageListener(Listener<IMessage> listener)
    {
        mMessageListener = listener;
    }

    public void removeMessageListener()
    {
        mMessageListener = null;
    }
}
