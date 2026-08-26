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
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Table Model for decoded IMessages.
 */
public class MessageActivityModel extends ClearableHistoryModel<MessageItem>
{
    private static final long serialVersionUID = 1L;
    private static final int MAX_OBSERVED_MESSAGE_IDENTITIES = 4096;
    private static final int TIME = 0;
    private static final int PROTOCOL = 1;
    private static final int TIMESLOT = 2;
    private static final int MESSAGE = 3;

    private String[] mHeaders = new String[]{"Time", "Protocol", "Timeslot", "Message"};
    private SimpleDateFormat mSDFTime = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
    private transient Set<IMessage> mObservedMessages = Collections.newSetFromMap(new IdentityHashMap<>());
    private transient ArrayDeque<IMessage> mObservedMessageOrder = new ArrayDeque<>();

    /**
     * Adds a snapshot of messages without clearing the current model.
     */
    void addMessages(List<IMessage> messages)
    {
        if(messages == null || messages.isEmpty())
        {
            return;
        }

        messages.forEach(this::addMessage);
    }

    @Override
    public void clear()
    {
        //Manual Clear removes visible rows but preserves the source-identity watermark so a reattach does not
        //resurrect the same bounded MessageHistory snapshot.
        super.clear();
    }

    /**
     * Clears both visible rows and observed identities for a different logical selection.
     */
    void resetSelectionHistory()
    {
        clearObservedMessages();
        super.clear();
    }

    @Override
    public void clearAndSet(List<MessageItem> items)
    {
        clearObservedMessages();

        List<MessageItem> deduplicated = new ArrayList<>();

        for(MessageItem item: items)
        {
            if(item != null && markObservedMessage(item.getMessage()))
            {
                deduplicated.add(item);
            }
        }

        super.clearAndSet(deduplicated);
    }

    /**
     * Adds a message immediately.  Callers outside this model use this only after dispatching to the Swing event
     * thread so attachment-generation checks and insertion remain one atomic UI operation.
     */
    void addMessage(IMessage message)
    {
        //Don't process tail bits or stuff bits message fragments
        if(message instanceof StuffBitsMessage)
        {
            return;
        }

        if(message != null && markObservedMessage(message))
        {
            add(new MessageItem(message));
        }
    }

    /**
     * Records a source snapshot without adding visible rows.  Used to linearize a user-requested Clear against the
     * bounded live handoff.
     */
    void markObservedMessages(List<IMessage> messages)
    {
        if(messages != null)
        {
            messages.forEach(this::markObservedMessage);
        }
    }

    private boolean markObservedMessage(IMessage message)
    {
        if(message == null)
        {
            return false;
        }

        boolean newlyObserved = mObservedMessages.add(message);

        if(!newlyObserved)
        {
            mObservedMessageOrder.removeIf(observed -> observed == message);
        }

        mObservedMessageOrder.addLast(message);

        while(mObservedMessageOrder.size() > MAX_OBSERVED_MESSAGE_IDENTITIES)
        {
            mObservedMessages.remove(mObservedMessageOrder.removeFirst());
        }

        return newlyObserved;
    }

    private void clearObservedMessages()
    {
        mObservedMessages.clear();
        mObservedMessageOrder.clear();
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
