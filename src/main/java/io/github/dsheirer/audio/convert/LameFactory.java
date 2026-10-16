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

package io.github.dsheirer.audio.convert;

import net.sourceforge.lame.lowlevel.LameEncoder;
import net.sourceforge.lame.mp3.MPEGMode;

/**
 * Factory class to create and configure LAME MP3 encoder
 */
public class LameFactory
{
    private LameFactory()
    {
    }

    /**
     * Creates a LAME MP3 encoder using the specified input sample rate and MP3 settings
     * @param input sample rate
     * @param setting to apply to the encoder
     * @return lame encoder
     */
    public static LameEncoder getLameEncoder(InputAudioFormat input, MP3Setting setting)
    {
        return switch(setting)
        {
            case CBR_16 -> new LameEncoder(input.getAudioFormat(), 16, MPEGMode.MONO, LameEncoder.DEFAULT_QUALITY, false);
            case CBR_32 -> new LameEncoder(input.getAudioFormat(), 32, MPEGMode.MONO, LameEncoder.DEFAULT_QUALITY, false);
            case ABR_56 -> new LameEncoder(input.getAudioFormat(), 56, MPEGMode.MONO, LameEncoder.DEFAULT_QUALITY, true);
            case VBR_5 -> new LameEncoder(input.getAudioFormat(), LameEncoder.DEFAULT_BITRATE, MPEGMode.MONO, 5, true);
            case VBR_7 -> new LameEncoder(input.getAudioFormat(), LameEncoder.DEFAULT_BITRATE, MPEGMode.MONO, 7, true);
            default -> throw new IllegalArgumentException("Unrecognized MP3 setting: " + setting);
        };
    }

}
