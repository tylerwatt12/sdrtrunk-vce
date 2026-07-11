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

package io.github.dsheirer.jmbe.github;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version representation and parsing class
 */
public class Version implements Comparable<Version>
{
    public static final Pattern VERSION_PATTERN = Pattern.compile("v?(\\d{1,5})\\.(\\d{1,5})\\.(\\d{1,5})([A-Za-z]?)");

    private Integer mMajor;
    private Integer mMinor;
    private Integer mRelease;
    private Character mPatch;

    /**
     * Constructs an instance
     * @param major version
     * @param minor version
     * @param release version
     * @param patch version (optional)
     */
    public Version(int major, int minor, int release, Character patch)
    {
        mMajor = major;
        mMinor = minor;
        mRelease = release;
        mPatch = patch;
    }

    /**
     * Parses the argument into a version instance.
     * @param version string (e.g. v1.0.6a)
     * @return
     */
    public static Version fromString(String version)
    {
        if(version != null)
        {
            Matcher m = VERSION_PATTERN.matcher(version.replace("\"", ""));

            if(m.matches())
            {
                int major = Integer.parseInt(m.group(1));
                int minor = Integer.parseInt(m.group(2));
                int release = Integer.parseInt(m.group(3));
                String rawPatch = m.group(4);
                Character patch = rawPatch.isEmpty() ? null : rawPatch.charAt(0);

                return new Version(major, minor, release, patch);
            }
        }

        return null;
    }

    public Integer getMajor()
    {
        return mMajor;
    }

    public boolean hasMajor()
    {
        return mMajor != null;
    }

    public Integer getMinor()
    {
        return mMinor;
    }

    public boolean hasMinor()
    {
        return mMinor != null;
    }

    public Integer getRelease()
    {
        return mRelease;
    }

    public boolean hasRelease()
    {
        return mRelease != null;
    }

    public char getPatch()
    {
        return mPatch;
    }

    public boolean hasPatch()
    {
        return mPatch != null;
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(hasMajor() ? getMajor() : "x");
        sb.append(".").append(hasMinor() ? getMinor() : "x");
        sb.append(".").append(hasRelease() ? getRelease() : "x");
        if(hasPatch())
        {
            sb.append(mPatch);
        }

        return sb.toString();
    }

    @Override
    public int compareTo(Version other)
    {
        Objects.requireNonNull(other, "Version cannot be null");
        int comparison = Integer.compare(getMajor(), other.getMajor());

        if(comparison == 0)
        {
            comparison = Integer.compare(getMinor(), other.getMinor());
        }

        if(comparison == 0)
        {
            comparison = Integer.compare(getRelease(), other.getRelease());
        }

        if(comparison == 0)
        {
            if(hasPatch() && other.hasPatch())
            {
                comparison = Character.compare(getPatch(), other.getPatch());
            }
            else if(hasPatch())
            {
                comparison = 1;
            }
            else if(other.hasPatch())
            {
                comparison = -1;
            }
        }

        return comparison;
    }

    @Override
    public boolean equals(Object o)
    {
        if(this == o) return true;
        if(!(o instanceof Version other)) return false;
        return compareTo(other) == 0;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(mMajor, mMinor, mRelease, mPatch);
    }
}
