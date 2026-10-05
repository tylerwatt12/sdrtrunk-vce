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

package io.github.dsheirer.identifier.radio;

import io.github.dsheirer.identifier.Role;
import java.util.Objects;

/**
 * Fully qualified radio identifier.  This is used for roaming radios or any time there is a need to fully qualify a
 * radio.
 */
public abstract class FullyQualifiedRadioIdentifier extends RadioIdentifier
{
    /** Highest P25 address assigned to an individual subscriber; larger values are infrastructure/reserved. */
    private static final int MAXIMUM_WORKING_ADDRESS = 0xFFFFFC;
    private int mWacn;
    private int mSystem;
    private int mRadio;
    private final boolean mExplicitWorkingAddress;
    private final ResolvedRadioIdentity.Evidence mResolutionEvidence;

    /**
     * Constructs an instance
     * @param localAddress radio identifier, aka alias.  This can be the same as the radio ID when the fully qualified radio
     * is not being aliased on a local radio system.
     * @param wacn of the home network for the radio.
     * @param system of the home network for the radio.
     * @param id of the radio within the home network.
     */
    protected FullyQualifiedRadioIdentifier(int localAddress, int wacn, int system, int id, Role role)
    {
        this(localAddress, wacn, system, id, role, false);
    }

    /**
     * Constructs an instance and records whether {@code localAddress} came from a distinct protocol working-address
     * field.  Callers that only have the canonical home identity must use the five-argument constructor so an equal
     * local and subscriber number is not inferred to be a working-address assignment.
     */
    protected FullyQualifiedRadioIdentifier(int localAddress, int wacn, int system, int id, Role role,
                                            boolean explicitWorkingAddress)
    {
        this(localAddress, wacn, system, id, role, explicitWorkingAddress, ResolvedRadioIdentity.Evidence.DIRECT);
    }

    protected FullyQualifiedRadioIdentifier(int localAddress, int wacn, int system, int id, Role role,
                                            boolean explicitWorkingAddress, ResolvedRadioIdentity.Evidence evidence)
    {
        super(localAddress, role);
        mWacn = wacn;
        mSystem = system;
        mRadio = id;
        mExplicitWorkingAddress = explicitWorkingAddress;
        mResolutionEvidence = Objects.requireNonNull(evidence);
    }

    public ResolvedRadioIdentity.Evidence getResolutionEvidence()
    {
        return mResolutionEvidence;
    }

    public int getWacn()
    {
        return mWacn;
    }

    public int getSystem()
    {
        return mSystem;
    }

    public int getRadio()
    {
        return mRadio;
    }

    /**
     * Fully qualified radio identity.
     * @return radio identity
     */
    public String getFullyQualifiedRadioAddress()
    {
        return P25SubscriberIdentityFormatter.format(mWacn, mSystem, mRadio);
    }

    /** Indicates whether the protocol explicitly supplied a separate working/local address field. */
    public boolean hasExplicitWorkingAddress()
    {
        return mExplicitWorkingAddress;
    }

    /**
     * Explicit, valid subscriber working address, or {@code null} when this identifier contains only a canonical
     * identity (or when the separate address is reserved/invalid).  Equality of the working and home subscriber
     * numbers does not erase the protocol fact that both fields were present.
     */
    public Integer getWorkingAddress()
    {
        Integer address = getValue();
        return mExplicitWorkingAddress && address != null && address > 0 && address <= MAXIMUM_WORKING_ADDRESS ?
            address : null;
    }

    /**
     * Indicates that this radio has an explicit, valid working-address assignment.  The legacy method name remains
     * for callers that use it to decide whether to display working-address context.
     */
    public boolean isAliased()
    {
        return getWorkingAddress() != null;
    }

    /**
     * Override the default behavior of RadioIdentifier.isValid() to allow for fully qualified SUID's with a local
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
            return getFullyQualifiedRadioAddress() + " (Working ID " + getWorkingAddress() + ")";
        }
        else
        {
            return getFullyQualifiedRadioAddress();
        }
    }

    @Override
    public boolean equals(Object o)
    {
        if(this == o)
        {
            return true;
        }

        if(o instanceof FullyQualifiedRadioIdentifier fqri)
        {
            return getWacn() == fqri.getWacn() &&
                    getSystem() == fqri.getSystem() &&
                    getRadio() == fqri.getRadio() &&
                    getIdentifierClass() == fqri.getIdentifierClass() &&
                    getForm() == fqri.getForm() &&
                    getRole() == fqri.getRole() &&
                    getProtocol() == fqri.getProtocol();
        }

        return false;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(getWacn(), getSystem(), getRadio(), getIdentifierClass(), getForm(),
                getRole(), getProtocol());
    }
}
