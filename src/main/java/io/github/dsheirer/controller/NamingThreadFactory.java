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

package io.github.dsheirer.controller;

import io.github.dsheirer.util.concurrent.ThreadQoS;
import io.github.dsheirer.util.concurrent.ThreadQoS.QoSClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread factory that applies custom names to threads
 */
public class NamingThreadFactory implements ThreadFactory 
{
    private static final Logger mLog = LoggerFactory.getLogger(NamingThreadFactory.class);

    private final AtomicInteger mThreadNumber = new AtomicInteger(1);
    
    private final String mNamePrefix;
    private final QoSClass mQoSClass;

    public NamingThreadFactory( String prefix ) 
    {
        this(prefix, null);
    }

    /**
     * Creates named threads that apply the requested OS scheduling class once when each worker starts.
     */
    public NamingThreadFactory(String prefix, QoSClass qosClass)
    {
        mNamePrefix = prefix + " thread ";
        mQoSClass = qosClass;
    }

    public Thread newThread( Runnable runnable ) 
    {
        Runnable worker = mQoSClass != null ? ThreadQoS.wrap(mQoSClass, runnable) : runnable;
        Thread thread = new Thread(worker, mNamePrefix + mThreadNumber.getAndIncrement());
        
        if( thread.isDaemon() )
        {
            thread.setDaemon( false );
        }
        
        if( thread.getPriority() != Thread.NORM_PRIORITY )
        {
            thread.setPriority( Thread.NORM_PRIORITY );
        }

        thread.setUncaughtExceptionHandler((t, e) ->
            mLog.error("Error while executing runnable in scheduled thread pool [" + t.getName() + "]", e));

        return thread;
    }
}
