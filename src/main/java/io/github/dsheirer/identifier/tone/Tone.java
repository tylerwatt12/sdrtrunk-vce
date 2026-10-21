/*
 * *****************************************************************************
 *  Copyright (C) 2014-2020 Dennis Sheirer
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

package io.github.dsheirer.identifier.tone;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An AMBE tone with a duration in 20 millisecond units.
 */
public class Tone
{
    private AmbeTone mAmbeTone;
    private int mDuration = 0;
    private final List<Runnable> mChangeListeners = new CopyOnWriteArrayList<>();

    /**
     * Constructs an instance for the specified tone and duration
     */
    public Tone(AmbeTone tone, int duration)
    {
        mAmbeTone = tone;
        mDuration = duration;
    }

    public Tone copyOf()
    {
        return new Tone(mAmbeTone, mDuration);
    }

    /**
     * Constructs an instance for the specified tone.
     */
    public Tone(AmbeTone tone)
    {
        this(tone, 0);
    }

    /**
     * Constructs an instance
     *
     * Do not use.  Used by Jackson for serialization.
     */
    public Tone()
    {
        //Empty for jackson constructor

        //Placeholder to ensure no null
        mAmbeTone = AmbeTone.INVALID;
    }

    public void addChangeListener(Runnable listener) { mChangeListeners.add(listener); }
    public void removeChangeListener(Runnable listener) { mChangeListeners.remove(listener); }
    private void notifyChanged() { mChangeListeners.forEach(Runnable::run); }

    /**
     * Ambe tone for this sequence
     */
    public AmbeTone getAmbeTone()
    {
        return mAmbeTone;
    }

    /**
     * Sets the tone
     */
    public void setAmbeTone(AmbeTone tone)
    {
        if(tone != null && tone != mAmbeTone)
        {
            mAmbeTone = tone;
            notifyChanged();
        }
    }

    /**
     * Count of 20 millisecond units of duration
     */
    public int getDuration()
    {
        return mDuration;
    }

    /**
     * Sets the duration in units of 20 milliseconds.
     */
    public void setDuration(int duration)
    {
        if(mDuration != duration)
        {
            mDuration = duration;
            notifyChanged();
        }
    }

    /**
     * Increments the duration count by 1 unit of 20 milliseconds.
     */
    public void incrementDuration()
    {
        mDuration++;
        notifyChanged();
    }

    /**
     * Indicates if this tone sequence is contained in the other tone sequence, meaning that the specific tone for this
     * sequence matches the other sequence tone and this duration is less than or equal to the other duration.
     * @param other to check for contains
     * @return true if this sequence is contained in the other sequence.
     */
    @JsonIgnore
    public boolean isContainedIn(Tone other)
    {
        return other != null && getAmbeTone() == other.getAmbeTone() && getDuration() <= other.getDuration();
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(mAmbeTone.toString());
        sb.append(" (").append(mDuration * 20).append("ms)");
        return sb.toString();
    }

}
