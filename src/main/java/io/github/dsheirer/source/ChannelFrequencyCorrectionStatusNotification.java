/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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
package io.github.dsheirer.source;

import io.github.dsheirer.source.tuner.channel.TunerChannelSource;

/**
 * Snapshot of decoder, channel, and tuner frequency correction state for one tuner channel source.
 */
public class ChannelFrequencyCorrectionStatusNotification extends SourceEvent
{
    private final long mChannelCorrection;
    private final long mTunerCorrection;
    private final double mTunerPPM;
    private final boolean mAutoPPM;

    private ChannelFrequencyCorrectionStatusNotification(TunerChannelSource source, long decoderCorrection,
                                                         long channelCorrection, double tunerPPM,
                                                         long tunerCorrection, boolean autoPPM)
    {
        super(Event.NOTIFICATION_CHANNEL_FREQUENCY_CORRECTION_STATUS, source, decoderCorrection, "Status Report");
        mChannelCorrection = channelCorrection;
        mTunerCorrection = tunerCorrection;
        mTunerPPM = tunerPPM;
        mAutoPPM = autoPPM;
    }

    public boolean isAutoPPM()
    {
        return mAutoPPM;
    }

    public long getTunerCorrection()
    {
        return mTunerCorrection;
    }

    public double getTunerPPM()
    {
        return mTunerPPM;
    }

    public long getChannelCorrection()
    {
        return mChannelCorrection;
    }

    public long getDecoderCorrection()
    {
        return getValue().longValue();
    }

    public static ChannelFrequencyCorrectionStatusNotification create(TunerChannelSource source,
                                                                      long decoderCorrection,
                                                                      long channelCorrection,
                                                                      double tunerPPM,
                                                                      long tunerCorrection,
                                                                      boolean autoPPM)
    {
        return new ChannelFrequencyCorrectionStatusNotification(source, decoderCorrection, channelCorrection,
            tunerPPM, tunerCorrection, autoPPM);
    }
}
