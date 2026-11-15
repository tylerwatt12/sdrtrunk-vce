/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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

package io.github.dsheirer.identifier.patch;

import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Temporary grouping or regrouping of talkgroups or individual radios.
 */
public class PatchGroup
{
    private final TalkgroupIdentifier mPatchGroupIdentifier;
    private final AtomicReference<Members> mMembers = new AtomicReference<>(new Members(List.of(), List.of()));
    private final int mVersion;

    /**
     * Readers capture one immutable membership version without a lock or a collection copy. In particular, a slow
     * formatter never holds anything needed by the decoder. New lists are prepared only for membership changes;
     * repeated grants and duplicate member observations retain the existing version. CAS preserves concurrent
     * add/expiry updates without making a writer acquire an observer's lock.
     */
    private record Members(List<TalkgroupIdentifier> talkgroups, List<RadioIdentifier> radios) {}

    /**
     * Constructs an instance
     * @param patchGroupIdentifier for the patch group
     * @param version for this patch group identifier.  Note: if the patch group identifier is frequently reused, a
     * new version number will indicate a new instance of the patch group.
     */
    public PatchGroup(TalkgroupIdentifier patchGroupIdentifier, int version)
    {
        mPatchGroupIdentifier = patchGroupIdentifier;
        mVersion = version;
    }

    /**
     * Constructs an instance
     * @param patchGroupIdentifier that is the overall identifier for this temporary grouping.
     */
    public PatchGroup(TalkgroupIdentifier patchGroupIdentifier)
    {
        this(patchGroupIdentifier, 0);
    }

    /**
     * Version number for this patch group used to differentiate frequently reused patch group identifiers.
     * @return version number (aka super group sequence number).
     */
    public int getVersion()
    {
        return mVersion;
    }

    /**
     * Patch group identifier.
     * @return identifier
     */
    public TalkgroupIdentifier getPatchGroup()
    {
        return mPatchGroupIdentifier;
    }

    /**
     * Indicates if this patch group contains any patched talkgroups.
     * @return true if there are patched talkgroups.
     */
    public boolean hasPatchedTalkgroups()
    {
        return !getPatchedTalkgroupIdentifiers().isEmpty();
    }

    /**
     * Indicates if this patch group contains any patched radio identifiers.
     * @return true if there are patched radios.
     */
    public boolean hasPatchedRadios()
    {
        return !getPatchedRadioIdentifiers().isEmpty();
    }

    /**
     * Adds the talkgroup identifier to this patched group.
     * @param patchedGroupIdentifier to add
     * @return true if the patched group identifier is new and was added
     */
    public boolean addPatchedTalkgroup(TalkgroupIdentifier patchedGroupIdentifier)
    {
        while(true)
        {
            Members before = mMembers.get();
            if(before.talkgroups().contains(patchedGroupIdentifier))
            {
                return false;
            }
            List<TalkgroupIdentifier> updated = new ArrayList<>(before.talkgroups());
            updated.add(patchedGroupIdentifier);
            if(mMembers.compareAndSet(before, new Members(List.copyOf(updated), before.radios())))
            {
                return true;
            }
        }
    }

    /**
     * Removes the patched talkgroup from this patch group.
     * @param talkgroup to remove
     */
    public void removePatchedTalkgroup(TalkgroupIdentifier talkgroup)
    {
        while(true)
        {
            Members before = mMembers.get();
            if(!before.talkgroups().contains(talkgroup))
            {
                return;
            }
            List<TalkgroupIdentifier> updated = new ArrayList<>(before.talkgroups());
            updated.remove(talkgroup);
            if(mMembers.compareAndSet(before, new Members(List.copyOf(updated), before.radios())))
            {
                return;
            }
        }
    }


    /**
     * Adds a list of talkgroup identifiers to this patched group.
     * @param patchedGroupIdentifiers to add
     */
    public boolean addPatchedTalkgroups(List<TalkgroupIdentifier> patchedGroupIdentifiers)
    {
        boolean added = false;

        for(TalkgroupIdentifier identifier : patchedGroupIdentifiers)
        {
            added |= addPatchedTalkgroup(identifier);
        }

        return added;
    }

    /**
     * List of patched group identifiers that are part of this patch group.
     * @return immutable membership version; later additions and removals do not change this list.
     */
    public List<TalkgroupIdentifier> getPatchedTalkgroupIdentifiers()
    {
        return mMembers.get().talkgroups();
    }

    /**
     * Adds the radio identifier to this patched group.
     * @param patchedRadioIdentifier to add
     */
    public boolean addPatchedRadio(RadioIdentifier patchedRadioIdentifier)
    {
        while(true)
        {
            Members before = mMembers.get();
            if(before.radios().contains(patchedRadioIdentifier))
            {
                return false;
            }
            List<RadioIdentifier> updated = new ArrayList<>(before.radios());
            updated.add(patchedRadioIdentifier);
            if(mMembers.compareAndSet(before, new Members(before.talkgroups(), List.copyOf(updated))))
            {
                return true;
            }
        }
    }

    /**
     * Removes the patched radio from this patch group.
     * @param radioIdentifier to remove
     */
    public void removePatchedRadio(RadioIdentifier radioIdentifier)
    {
        while(true)
        {
            Members before = mMembers.get();
            if(!before.radios().contains(radioIdentifier))
            {
                return;
            }
            List<RadioIdentifier> updated = new ArrayList<>(before.radios());
            updated.remove(radioIdentifier);
            if(mMembers.compareAndSet(before, new Members(before.talkgroups(), List.copyOf(updated))))
            {
                return;
            }
        }
    }

    /**
     * Adds a list of radio identifiers to this patched group.
     * @param patchedRadioIdentifiers to add
     * @return true if any new identifiers were added
     */
    public boolean addPatchedRadios(List<RadioIdentifier> patchedRadioIdentifiers)
    {
        boolean added = false;

        for(RadioIdentifier identifier : patchedRadioIdentifiers)
        {
            added |= addPatchedRadio(identifier);
        }

        return added;
    }

    /**
     * List of patched radio identifiers that are part of this patch group.
     * @return immutable membership version; later additions and removals do not change this list.
     */
    public List<RadioIdentifier> getPatchedRadioIdentifiers()
    {
        return mMembers.get().radios();
    }

    @Override
    public String toString()
    {
        Members members = mMembers.get();
        StringBuilder sb = new StringBuilder();
        sb.append("P:").append(getPatchGroup());

        if(!members.talkgroups().isEmpty())
        {
            sb.append(" ").append(members.talkgroups());
        }

        if(!members.radios().isEmpty())
        {
            sb.append(" ").append(members.radios());
        }

        return sb.toString();
    }

    @Override
    public boolean equals(Object o)
    {
        if(this == o)
        {
            return true;
        }
        if(o == null || getClass() != o.getClass())
        {
            return false;
        }
        PatchGroup that = (PatchGroup)o;
        return Objects.equals(mPatchGroupIdentifier, that.mPatchGroupIdentifier);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(mPatchGroupIdentifier);
    }
}
