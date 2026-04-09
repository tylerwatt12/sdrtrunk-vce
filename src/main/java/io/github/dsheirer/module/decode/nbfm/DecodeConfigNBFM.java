/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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
package io.github.dsheirer.module.decode.nbfm;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import io.github.dsheirer.dsp.squelch.NoiseSquelch;
import io.github.dsheirer.dsp.squelch.SquelchTailRemover;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.analog.DecodeConfigAnalog;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;

/**
 * Decoder configuration for an NBFM channel
 */
public class DecodeConfigNBFM extends DecodeConfigAnalog
{
    public enum DeemphasisMode
    {
        NONE("None", 0),
        US_750US("750 µs (North America)", 750),
        CEPT_530US("530 µs (Europe/CEPT)", 530);

        private final String mLabel;
        private final int mMicroseconds;

        DeemphasisMode(String label, int microseconds)
        {
            mLabel = label;
            mMicroseconds = microseconds;
        }

        public int getMicroseconds()
        {
            return mMicroseconds;
        }

        @Override
        public String toString()
        {
            return mLabel;
        }
    }

    private boolean mAudioFilter = true;
    private float mSquelchNoiseOpenThreshold = NoiseSquelch.DEFAULT_NOISE_OPEN_THRESHOLD;
    private float mSquelchNoiseCloseThreshold = NoiseSquelch.DEFAULT_NOISE_CLOSE_THRESHOLD;
    private int mSquelchHysteresisOpenThreshold = NoiseSquelch.DEFAULT_HYSTERESIS_OPEN_THRESHOLD;
    private int mSquelchHysteresisCloseThreshold = NoiseSquelch.DEFAULT_HYSTERESIS_CLOSE_THRESHOLD;
    private DeemphasisMode mDeemphasis = DeemphasisMode.NONE;
    private boolean mSquelchTailRemovalEnabled;
    private int mSquelchTailRemovalMs = SquelchTailRemover.DEFAULT_TAIL_REMOVAL_MS;
    private int mSquelchHeadRemovalMs = SquelchTailRemover.DEFAULT_HEAD_REMOVAL_MS;
    private boolean mLowPassEnabled;
    private int mLowPassCutoff = 3400;
    private boolean mVoiceEnhanceEnabled;
    private float mVoiceEnhanceAmount = 30.0f;
    private boolean mBassBoostEnabled;
    private float mBassBoostDb;
    private float mOutputGain = 1.0f;

    @JacksonXmlProperty(isAttribute = true, localName = "type", namespace = "http://www.w3.org/2001/XMLSchema-instance")
    public DecoderType getDecoderType()
    {
        return DecoderType.NBFM;
    }

    @Override
    protected Bandwidth getDefaultBandwidth()
    {
        return Bandwidth.BW_12_5;
    }

    /**
     * Channel sample stream specification.
     */
    @JsonIgnore
    @Override
    public ChannelSpecification getChannelSpecification()
    {
        switch(getBandwidth())
        {
            case BW_7_5:
                return new ChannelSpecification(25000.0, 7500, 3500.0, 3750.0);
            case BW_12_5:
                return new ChannelSpecification(25000.0, 12500, 6000.0, 7000.0);
            case BW_25_0:
                return new ChannelSpecification(50000.0, 25000, 12500.0, 13500.0);
            default:
                throw new IllegalArgumentException("Unrecognized FM bandwidth value: " + getBandwidth());
        }
    }

    /**
     * Indicates if the user wants the demodulated audio to be high-pass filtered.
     * @return enable status, defaults to true.
     */
    @JacksonXmlProperty(isAttribute = true, localName = "audioFilter")
    public boolean isAudioFilter()
    {
        return mAudioFilter;
    }

    /**
     * Sets the enabled state of high-pass filtering of the demodulated audio.
     * @param audioFilter to true to enable high-pass filtering.
     */
    public void setAudioFilter(boolean audioFilter)
    {
        mAudioFilter = audioFilter;
    }

    /**
     * Squelch noise open threshold in the range 0.0 to 1.0 with a default of 0.1
     * @return noise open threshold
     */
    @JacksonXmlProperty(isAttribute = true, localName = "squelchNoiseOpenThreshold")
    public float getSquelchNoiseOpenThreshold()
    {
        return mSquelchNoiseOpenThreshold;
    }

    /**
     * Squelch noise close threshold in the range 0.0 to 1.0, greater than or equal to open threshold, with a default of 0.2
     * @return noise close threshold
     */
    @JacksonXmlProperty(isAttribute = true, localName = "squelchNoiseCloseThreshold")
    public float getSquelchNoiseCloseThreshold()
    {
        return mSquelchNoiseCloseThreshold;
    }

    /**
     * Sets the squelch noise threshold.
     * @param open in range 0.0 to 1.0 with a default of 0.1
     */
    public void setSquelchNoiseOpenThreshold(float open)
    {
        if(open < NoiseSquelch.MINIMUM_NOISE_THRESHOLD || open > NoiseSquelch.MAXIMUM_NOISE_THRESHOLD)
        {
            throw new IllegalArgumentException("Squelch noise open threshold is out of range: " + open);
        }

        mSquelchNoiseOpenThreshold = open;
    }

    /**
     * Sets the squelch noise close threshold.
     * @param close in range 0.0 to 1.0 and greater than or equal to open, with a default of 0.1
     */
    public void setSquelchNoiseCloseThreshold(float close)
    {
        if(close < NoiseSquelch.MINIMUM_NOISE_THRESHOLD || close > NoiseSquelch.MAXIMUM_NOISE_THRESHOLD)
        {
            throw new IllegalArgumentException("Squelch noise close threshold is out of range: " + close);
        }

        mSquelchNoiseCloseThreshold = close;
    }

    /**
     * Squelch hysteresis open threshold in range 1-10 with a default of 4.
     * @return hysteresis open threshold
     */
    @JacksonXmlProperty(isAttribute = true, localName = "squelchHysteresisOpenThreshold")
    public int getSquelchHysteresisOpenThreshold()
    {
        return mSquelchHysteresisOpenThreshold;
    }

    /**
     * Sets the squelch time threshold in the range 1-10.
     * @param open threshold
     */
    public void setSquelchHysteresisOpenThreshold(int open)
    {
        if(open < NoiseSquelch.MINIMUM_HYSTERESIS_THRESHOLD || open > NoiseSquelch.MAXIMUM_HYSTERESIS_THRESHOLD)
        {
            throw new IllegalArgumentException("Squelch hysteresis open threshold is out of range: " + open);
        }

        mSquelchHysteresisOpenThreshold = open;
    }

    /**
     * Squelch hysteresis close threshold in range 1-10 with a default of 4.
     * @return hysteresis close threshold
     */
    @JacksonXmlProperty(isAttribute = true, localName = "squelchHysteresisCloseThreshold")
    public int getSquelchHysteresisCloseThreshold()
    {
        return mSquelchHysteresisCloseThreshold;
    }

    /**
     * Sets the squelch close threshold in the range 1-10.
     * @param close threshold
     */
    public void setSquelchHysteresisCloseThreshold(int close)
    {
        if(close < NoiseSquelch.MINIMUM_HYSTERESIS_THRESHOLD || close > NoiseSquelch.MAXIMUM_HYSTERESIS_THRESHOLD)
        {
            throw new IllegalArgumentException("Squelch hysteresis close threshold is out of range: " + close);
        }

        mSquelchHysteresisCloseThreshold = close;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "deemphasis")
    public DeemphasisMode getDeemphasis()
    {
        return mDeemphasis;
    }

    public void setDeemphasis(DeemphasisMode deemphasis)
    {
        mDeemphasis = deemphasis != null ? deemphasis : DeemphasisMode.NONE;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "squelchTailRemovalEnabled")
    public boolean isSquelchTailRemovalEnabled()
    {
        return mSquelchTailRemovalEnabled;
    }

    public void setSquelchTailRemovalEnabled(boolean enabled)
    {
        mSquelchTailRemovalEnabled = enabled;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "squelchTailRemovalMs")
    public int getSquelchTailRemovalMs()
    {
        return mSquelchTailRemovalMs;
    }

    public void setSquelchTailRemovalMs(int ms)
    {
        mSquelchTailRemovalMs = Math.max(SquelchTailRemover.MINIMUM_REMOVAL_MS,
                Math.min(SquelchTailRemover.MAXIMUM_TAIL_REMOVAL_MS, ms));
    }

    @JacksonXmlProperty(isAttribute = true, localName = "squelchHeadRemovalMs")
    public int getSquelchHeadRemovalMs()
    {
        return mSquelchHeadRemovalMs;
    }

    public void setSquelchHeadRemovalMs(int ms)
    {
        mSquelchHeadRemovalMs = Math.max(SquelchTailRemover.MINIMUM_REMOVAL_MS,
                Math.min(SquelchTailRemover.MAXIMUM_HEAD_REMOVAL_MS, ms));
    }

    @JacksonXmlProperty(isAttribute = true, localName = "lowPassEnabled")
    public boolean isLowPassEnabled()
    {
        return mLowPassEnabled;
    }

    public void setLowPassEnabled(boolean enabled)
    {
        mLowPassEnabled = enabled;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "lowPassCutoff")
    public int getLowPassCutoff()
    {
        return mLowPassCutoff;
    }

    public void setLowPassCutoff(int cutoff)
    {
        mLowPassCutoff = Math.max(2000, Math.min(4000, cutoff));
    }

    @JacksonXmlProperty(isAttribute = true, localName = "voiceEnhanceEnabled")
    public boolean isVoiceEnhanceEnabled()
    {
        return mVoiceEnhanceEnabled;
    }

    public void setVoiceEnhanceEnabled(boolean enabled)
    {
        mVoiceEnhanceEnabled = enabled;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "voiceEnhanceAmount")
    public float getVoiceEnhanceAmount()
    {
        return mVoiceEnhanceAmount;
    }

    public void setVoiceEnhanceAmount(float amount)
    {
        mVoiceEnhanceAmount = Math.max(0.0f, Math.min(100.0f, amount));
    }

    @JacksonXmlProperty(isAttribute = true, localName = "bassBoostEnabled")
    public boolean isBassBoostEnabled()
    {
        return mBassBoostEnabled;
    }

    public void setBassBoostEnabled(boolean enabled)
    {
        mBassBoostEnabled = enabled;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "bassBoostDb")
    public float getBassBoostDb()
    {
        return mBassBoostDb;
    }

    public void setBassBoostDb(float boostDb)
    {
        mBassBoostDb = Math.max(0.0f, Math.min(12.0f, boostDb));
    }

    @JacksonXmlProperty(isAttribute = true, localName = "outputGain")
    public float getOutputGain()
    {
        return mOutputGain;
    }

    public void setOutputGain(float outputGain)
    {
        mOutputGain = Math.max(0.25f, Math.min(4.0f, outputGain));
    }
}
