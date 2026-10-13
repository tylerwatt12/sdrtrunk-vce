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

package io.github.dsheirer.identifier.talkgroup;

import io.github.dsheirer.identifier.IdentityResolutionEvidence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.dsheirer.identifier.Role;
import java.util.Objects;

/**
 * Fully qualified talkgroup identity with independent local observation facts.
 */
public abstract class FullyQualifiedTalkgroupIdentifier extends TalkgroupIdentifier
{
    private final int mWacn;
    private final int mSystem;
    private final int mTalkgroup;
    private final boolean mExplicitLocalAddress;
    private final IdentityResolutionEvidence mResolutionEvidence;
    private final TalkgroupIdentifier mRecordingIdentifier;

    /**
     * Constructs an instance
     * @param localAddress used on the local system as an alias to the fully qualified talkgroup.
     * @param wacn for the talkgroup home system.
     * @param system for the talkgroup home system.
     * @param id for the talkgroup within the home system.
     * @param role played by the talkgroup.
     */
    protected FullyQualifiedTalkgroupIdentifier(int localAddress, int wacn, int system, int id, Role role)
    {
        this(localAddress, wacn, system, id, role, false, IdentityResolutionEvidence.DIRECT, null);
    }

    protected FullyQualifiedTalkgroupIdentifier(int localAddress, int wacn, int system, int id, Role role,
        boolean explicitLocalAddress, IdentityResolutionEvidence evidence, TalkgroupIdentifier recordingIdentifier)
    {
        super(localAddress, role);
        mWacn = wacn;
        mSystem = system;
        mTalkgroup = id;
        mExplicitLocalAddress = explicitLocalAddress;
        mResolutionEvidence = Objects.requireNonNull(evidence);
        mRecordingIdentifier = recordingIdentifier;
    }

    /** Whether a separate protocol local-group field was carried, even when its value is zero. */
    public boolean hasExplicitLocalAddress()
    {
        return mExplicitLocalAddress;
    }

    /** Assigned individual group address, or null for an absent, zero or reserved field. */
    public Integer getLocalAddress()
    {
        return mExplicitLocalAddress && getValue() > 0 && getValue() < 0xFFFF ? getValue() : null;
    }

    public IdentityResolutionEvidence getResolutionEvidence()
    {
        return mResolutionEvidence;
    }

    /** Original receiver observation, independent of assignment-derived home identity. */
    @JsonIgnore
    public TalkgroupIdentifier getRecordingIdentifier()
    {
        return mRecordingIdentifier != null ? mRecordingIdentifier : this;
    }

    public int getWacn()
    {
        return mWacn;
    }

    public int getSystem()
    {
        return mSystem;
    }

    public int getTalkgroup()
    {
        return mTalkgroup;
    }

    /**
     * Fully qualified talkgroup identity.
     * @return talkgroup identity
     */
    public String getFullyQualifiedTalkgroupAddress()
    {
        return mWacn + "." + mSystem + "." + mTalkgroup;
    }

    /**
     * Indicates if the talkgroup identity is aliased with a persona value that is different from the talkgroup ID.
     * @return true if aliased.
     */
    public boolean isAliased()
    {
        return getLocalAddress() != null;
    }

    /**
     * Override the default behavior of TalkgroupIdentifier.isValid() to allow for fully qualified TGID's with a local
     * ID of zero which indicates an ISSI patch.
     */
    @Override
    public boolean isValid()
    {
        return true;
    }

    @Override
    public String toString()
    {
        if(isAliased())
        {
            return super.toString() + "(" + getFullyQualifiedTalkgroupAddress() + ")";
        }
        else
        {
            return getFullyQualifiedTalkgroupAddress();
        }
    }

    @Override
    public boolean equals(Object o)
    {
        if(this == o)
        {
            return true;
        }

        if(o instanceof FullyQualifiedTalkgroupIdentifier fqti)
        {
            return getWacn() == fqti.getWacn() &&
                    getSystem() == fqti.getSystem() &&
                    getTalkgroup() == fqti.getTalkgroup() &&
                    getIdentifierClass() == fqti.getIdentifierClass() &&
                    getForm() == fqti.getForm() &&
                    getRole() == fqti.getRole() &&
                    getProtocol() == fqti.getProtocol();
        }

        return false;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(getWacn(), getSystem(), getTalkgroup(), getIdentifierClass(), getForm(),
                getRole(), getProtocol());
    }
}
