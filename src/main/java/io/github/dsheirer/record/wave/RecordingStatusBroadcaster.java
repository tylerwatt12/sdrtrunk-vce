/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
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

package io.github.dsheirer.record.wave;

import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Broadcasts recording status updates and retains the latest update so that replacement UI listeners can immediately
 * display an in-progress recording.
 */
public final class RecordingStatusBroadcaster implements IRecordingStatusListener
{
    private static final Logger mLog = LoggerFactory.getLogger(RecordingStatusBroadcaster.class);
    private final CopyOnWriteArrayList<IRecordingStatusListener> mListeners = new CopyOnWriteArrayList<>();
    private volatile RecordingStatus mCurrentStatus;

    /**
     * Adds a listener and immediately replays the current recording status, when available.
     */
    public void addListener(IRecordingStatusListener listener)
    {
        if(listener != null)
        {
            if(mListeners.addIfAbsent(listener))
            {
                RecordingStatus currentStatus = mCurrentStatus;

                if(currentStatus != null)
                {
                    notifyListener(listener, currentStatus);
                }
            }
        }
    }

    /**
     * Removes a listener.
     */
    public void removeListener(IRecordingStatusListener listener)
    {
        if(listener != null)
        {
            mListeners.remove(listener);
        }
    }

    /**
     * Clears the retained status when a recording stops without removing listeners for the active tuner editor.
     */
    public void clearStatus()
    {
        mCurrentStatus = null;
    }

    /**
     * Clears retained state and listeners when the tuner controller is disposed.
     */
    public void dispose()
    {
        clearStatus();
        mListeners.clear();
    }

    @Override
    public void update(int fileCount, String file, long size)
    {
        RecordingStatus status = new RecordingStatus(fileCount, file, size);
        mCurrentStatus = status;

        for(IRecordingStatusListener listener: mListeners)
        {
            notifyListener(listener, status);
        }
    }

    private void notifyListener(IRecordingStatusListener listener, RecordingStatus status)
    {
        try
        {
            listener.update(status.fileCount(), status.file(), status.size());
        }
        catch(Exception e)
        {
            mLog.error("Error broadcasting tuner recording status", e);
        }
    }

    private record RecordingStatus(int fileCount, String file, long size) {}
}
