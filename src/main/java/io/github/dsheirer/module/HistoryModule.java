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
import java.util.ArrayList;
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
    private List<T> mItems = new ArrayList<>();
    private Broadcaster<T> mBroadcaster = new Broadcaster<>();
    private int mMaximumHistorySize;
    private final ReentrantLock mItemsLock = new ReentrantLock();

    /**
     * Constructs an instance
     */
    protected HistoryModule(int maximumHistorySize)
    {
        mMaximumHistorySize = maximumHistorySize;
    }

    /**
     * Access a copy of the events from this event history
     */
    public List<T> getItems()
    {
        mItemsLock.lock();

        try
        {
            return new ArrayList<>(mItems);
        }
        finally
        {
            mItemsLock.unlock();
        }
    }

    @Override
    public void reset()
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

    @Override
    public void start()
    {
    }

    @Override
    public void stop()
    {
        reset();
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
        /*
         * History is an observer.  A UI or web snapshot may briefly own the history lock, but a decoder callback must
         * never wait for that copy.  Missing a history row is preferable to delaying live decode; listeners still
         * receive the observation through their own bounded handoffs.
         */
        if(mItemsLock.tryLock())
        {
            try
            {
                if(!mItems.contains(item) && mMaximumHistorySize > 0)
                {
                    while(mItems.size() >= mMaximumHistorySize)
                    {
                        mItems.remove(0);
                    }

                    mItems.add(item);
                }
            }
            finally
            {
                mItemsLock.unlock();
            }
        }

        mBroadcaster.broadcast(item);
    }
}
