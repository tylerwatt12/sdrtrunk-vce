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

package io.github.dsheirer.record.wave;

/**
 * WAVE INFO/LIST audio metadata tags.
 *
 * Metadata tag details:
 * LIST: https://sno.phy.queensu.ca/~phil/exiftool/TagNames/RIFF.html#Info
 *
 */
public enum AudioMetadata
{
    //Primary tags
    ARTIST_NAME("IART", true),
    ALBUM_TITLE("IPRD", true),
    GROUPING("ISBJ", true),
    TRACK_TITLE("INAM", true),
    COMMENTS("ICMT", true),
    DATE_CREATED("ICRD", true),
    GENRE("IGNR", true),
    YEAR("ICOP", true),
    COMPOSER("ISFT", true);

    private String mLISTTag;
    private boolean mPrimaryTag;

    /**
     * Wave file LIST chunk metadata tag types
     * @param listTag used in the LIST chunk
     * @param PrimaryTag indicates if this is a custom LIST tag (true) or standard LIST tag (false)
     */
    AudioMetadata(String listTag, boolean PrimaryTag)
    {
        mLISTTag = listTag;
        mPrimaryTag = PrimaryTag;
    }

    /**
     * Tag to use when this metadata type is included in a WAV INFO/LIST chunk
     */
    public String getLISTTag()
    {
        return mLISTTag;
    }

    /**
     * Indicates if this is a primary tag that should be listed first, before the secondary tags
     */
    public boolean isPrimaryTag()
    {
        return mPrimaryTag;
    }
}
