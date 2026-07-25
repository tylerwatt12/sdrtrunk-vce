/*******************************************************************************
 * sdr-trunk
 * Copyright (C) 2014-2018 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by  the Free Software Foundation, either version 3 of the License, or  (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful,  but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License  along with this program.
 * If not, see <http://www.gnu.org/licenses/>
 *
 ******************************************************************************/
package io.github.dsheirer.source;

/**
 * Source Configuration Types enumeration.
 */
public enum SourceType
{
    NONE("No Source"),
    /**
     * Compatibility value for saved channels that used a computer sound-card input.  These channels are retained in
     * legacy storage but are not loaded, selectable, or runnable.
     */
    MIXER("Sound Card", Availability.RETIRED_COMPATIBILITY),
    TUNER("Tuner"),
    TUNER_MULTIPLE_FREQUENCIES("Tuner - Multiple Frequencies"),
    RECORDING("IQ Recording");

    private String mDisplayString;
    private Availability mAvailability;

    SourceType(String displayString)
    {
        this(displayString, Availability.ACTIVE);
    }

    SourceType(String displayString, Availability availability)
    {
        mDisplayString = displayString;
        mAvailability = availability;
    }

    public static SourceType[] getTypes()
    {
        return java.util.stream.Stream.of(SourceType.TUNER, SourceType.TUNER_MULTIPLE_FREQUENCIES)
            .filter(SourceType::isActive)
            .toArray(SourceType[]::new);
    }

    public boolean isActive()
    {
        return mAvailability == Availability.ACTIVE;
    }

    public boolean isRetiredCompatibility()
    {
        return mAvailability == Availability.RETIRED_COMPATIBILITY;
    }

    @Override
    public String toString()
    {
        return mDisplayString;
    }

    public enum Availability
    {
        ACTIVE,
        RETIRED_COMPATIBILITY
    }
}
