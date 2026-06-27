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
package io.github.dsheirer.module.decode.event;

import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.StuffBitsMessage;
import io.github.dsheirer.sample.Listener;
import java.awt.EventQueue;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Table Model for decoded IMessages.
 */
public class MessageActivityModel extends ClearableHistoryModel<MessageItem> implements Listener<IMessage>
{
    private static final long serialVersionUID = 1L;
    private static final int TIME = 0;
    private static final int PROTOCOL = 1;
    private static final int TIMESLOT = 2;
    private static final int MESSAGE = 3;

    private String[] mHeaders = new String[]{"Time", "Protocol", "Timeslot", "Message"};
    private SimpleDateFormat mSDFTime = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
    private transient Set<IMessage> mDisplayedMessages = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Implements the listener interface and wraps the IMessage in table-compatible message item wrapper.
     * @param message to add to the model
     */
    public void receive(final IMessage message)
    {
        EventQueue.invokeLater(() -> addMessage(message));
    }

    /**
     * Adds a snapshot of messages without clearing the current model.
     */
    public void addMessages(List<IMessage> messages)
    {
        if(messages == null || messages.isEmpty())
        {
            return;
        }

        EventQueue.invokeLater(() -> messages.forEach(this::addMessage));
    }

    @Override
    public void clear()
    {
        mDisplayedMessages.clear();
        super.clear();
    }

    @Override
    public void clearAndSet(List<MessageItem> items)
    {
        mDisplayedMessages.clear();

        List<MessageItem> deduplicated = new ArrayList<>();

        for(MessageItem item: items)
        {
            if(item != null && item.getMessage() != null && mDisplayedMessages.add(item.getMessage()))
            {
                deduplicated.add(item);
            }
        }

        super.clearAndSet(deduplicated);
    }

    private void addMessage(IMessage message)
    {
        //Don't process tail bits or stuff bits message fragments
        if(message instanceof StuffBitsMessage)
        {
            return;
        }

        if(message != null && mDisplayedMessages.add(message))
        {
            add(new MessageItem(message));
        }
    }

    @Override
    public int getColumnCount()
    {
        return mHeaders.length;
    }

    public String getColumnName(int column)
    {
        return mHeaders[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex)
    {
        MessageItem item = getItem(rowIndex);

        if(item != null)
        {
            switch(columnIndex)
            {
                case TIME:
                    return item.getTimestamp(mSDFTime);
                case PROTOCOL:
                    return item.getProtocol();
                case TIMESLOT:
                    return item.getTimeslot();
                case MESSAGE:
                    return item.getText();
                default:
                    break;
            }
        }

        return null;
    }
}
