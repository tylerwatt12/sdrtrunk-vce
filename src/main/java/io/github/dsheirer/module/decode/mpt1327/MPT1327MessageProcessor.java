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
package io.github.dsheirer.module.decode.mpt1327;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.sample.Listener;

public class MPT1327MessageProcessor implements Listener<CorrectedBinaryMessage>
{
    private Listener<IMessage> mMessageListener;

    public void dispose()
    {
        mMessageListener = null;
    }

    @Override
    public void receive(CorrectedBinaryMessage message)
    {
        if(mMessageListener != null)
        {
            mMessageListener.receive(new MPT1327Message(message));
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
