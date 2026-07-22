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

package io.github.dsheirer.module;

import io.github.dsheirer.sample.Broadcaster;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Abstract base history module.  Maintains a history of items and constrains the total history size.  Adds support
 * for registering a listener to receive a copy of new items as they arrive.
 *
 * Note: internal history items are de-duplicated.  However, all items are passed through to the listener.
 */
public abstract class HistoryModule<T> extends Module implements Listener<T>
{
    private final ArrayDeque<T> mItems = new ArrayDeque<>();
    private final ReentrantLock mItemsLock = new ReentrantLock();
    private final Broadcaster<T> mBroadcaster = new Broadcaster<>();
    private final int mMaximumHistorySize;

    /**
     * Constructs an instance
     */
    protected HistoryModule(int maximumHistorySize)
    {
        mMaximumHistorySize = Math.max(0, maximumHistorySize);
    }

    /**
     * Access a copy of the events from this event history
     */
    public List<T> getItems()
    {
        mItemsLock.lock();

        try
        {
            return List.copyOf(mItems);
        }
        finally
        {
            mItemsLock.unlock();
        }
    }

    @Override
    public void reset()
    {
        clearItems();
    }

    @Override
    public void start()
    {
    }

    @Override
    public void stop()
    {
        clearItems();
        mBroadcaster.clear();
    }

    /**
     * Adds the listener to receive a copy of all items received by this history.
     * @param listener to receive items, or pass null to clear existing listener.
     */
    public void addListener(Listener<T> listener)
    {
        mBroadcaster.addListener(listener);
    }

    /**
     * Removes the listener from receiving items.
     * @param listener to remove
     */
    public void removeListener(Listener<T> listener)
    {
        mBroadcaster.removeListener(listener);
    }

    /**
     * Primary item receiver method.
     */
    @Override
    public void receive(T item)
    {
        if(item != null && mMaximumHistorySize > 0)
        {
            mItemsLock.lock();

            try
            {
                if(!mItems.contains(item))
                {
                    while(mItems.size() >= mMaximumHistorySize)
                    {
                        mItems.removeFirst();
                    }

                    mItems.addLast(item);
                }
            }
            finally
            {
                mItemsLock.unlock();
            }
        }

        mBroadcaster.broadcast(item);
    }

    private void clearItems()
    {
        mItemsLock.lock();

        try
        {
            mItems.clear();
        }
        finally
        {
            mItemsLock.unlock();
        }
    }
}
