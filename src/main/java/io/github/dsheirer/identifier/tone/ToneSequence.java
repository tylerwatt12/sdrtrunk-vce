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
import com.google.common.base.Joiner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A sequentially ordered list of tones
 */
public class ToneSequence
{
    private final List<Tone> mTones = new ArrayList<>();
    private final List<Tone> mReadOnlyTones = Collections.unmodifiableList(mTones);
    private final Map<Tone,Integer> mToneReferences = new IdentityHashMap<>();
    private final List<Runnable> mChangeListeners = new CopyOnWriteArrayList<>();
    private final Runnable mToneChanged = this::notifyChanged;

    /**
     * Constructs an instance
     * @param tones for the instance
     */
    public ToneSequence(List<Tone> tones)
    {
        setTones(tones);
    }

    /**
     * Constructs an instance
     */
    public ToneSequence()
    {
    }

    /**
     * Creates a deep copy of this tone sequence
     */
    public ToneSequence copyOf()
    {
        ToneSequence toneSequence = new ToneSequence();
        for(Tone tone: mTones)
        {
            toneSequence.addTone(tone.copyOf());
        }
        return toneSequence;
    }

    public void addChangeListener(Runnable listener) { mChangeListeners.add(listener); }
    public void removeChangeListener(Runnable listener) { mChangeListeners.remove(listener); }
    private void notifyChanged() { mChangeListeners.forEach(Runnable::run); }

    /**
     * List of tones
     */
    public List<Tone> getTones()
    {
        return mReadOnlyTones;
    }

    /**
     * Sets the tones for this list
     */
    public void setTones(List<Tone> tones)
    {
        if(tones != null && !mTones.equals(tones))
        {
            List<Tone> replacement = new ArrayList<>(tones);
            mToneReferences.keySet().forEach(tone -> tone.removeChangeListener(mToneChanged));
            mToneReferences.clear();
            mTones.clear();
            mTones.addAll(replacement);
            mTones.forEach(this::observe);
            notifyChanged();
        }
    }

    @JsonIgnore
    public boolean hasTones()
    {
        return !mTones.isEmpty();
    }

    /**
     * Clears all tones from this sequence
     */
    public void clear()
    {
        if(!mTones.isEmpty())
        {
            mToneReferences.keySet().forEach(tone -> tone.removeChangeListener(mToneChanged));
            mToneReferences.clear();
            mTones.clear();
            notifyChanged();
        }
    }

    /**
     * Adds a tone to this sequence
     */
    public void addTone(Tone tone)
    {
        if(tone != null)
        {
            mTones.add(tone);
            observe(tone);
            notifyChanged();
        }
    }

    /**
     * Removes the tone from this sequence
     */
    public void removeTone(Tone tone)
    {
        if(tone != null && mTones.remove(tone))
        {
            int remaining = mToneReferences.get(tone) - 1;
            if(remaining == 0)
            {
                mToneReferences.remove(tone);
                tone.removeChangeListener(mToneChanged);
            }
            else mToneReferences.put(tone, remaining);
            notifyChanged();
        }
    }

    private void observe(Tone tone)
    {
        if(tone == null) return;
        int references = mToneReferences.getOrDefault(tone, 0);
        if(references == 0) tone.addChangeListener(mToneChanged);
        mToneReferences.put(tone, references + 1);
    }

    @Override
    public String toString()
    {
        if(mTones.isEmpty())
        {
            return "Tones: (empty)";
        }

        return Joiner.on(",").join(mTones);
    }

    /**
     * Indicates if this sequence of tones is contained in the argument tone sequence.  This method is used for
     * matching this sequence to another sequence, where this sequence is either a full or partial sequence contained
     * in the argument sequence.
     *
     * @param other to check for containment
     * @return true if contained
     */
    public boolean isContainedIn(ToneSequence other)
    {
        if(other == null || other.getTones().isEmpty())
        {
            return false;
        }

        List<Tone> otherTones = other.getTones();

        int pointer = 0;

        for(Tone thisTone: mTones)
        {
            boolean matches = false;

            for(int x = pointer; x < otherTones.size(); x++)
            {
                if(thisTone.isContainedIn(otherTones.get(x)))
                {
                    pointer = x + 1;
                    matches = true;
                    break;
                }
            }

            if(!matches)
            {
                return false;
            }
        }

        return true;
    }
}
