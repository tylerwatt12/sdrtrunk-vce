/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.am;

import io.github.dsheirer.dsp.am.AmplitudeDemodulator;
import io.github.dsheirer.dsp.gain.AudioGainAndDcFilter;
import io.github.dsheirer.dsp.squelch.CarrierSquelch;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.nbfm.NBFMDecoder;
import java.util.Objects;

/**
 * AM envelope decoder using the shared conventional analog audio path.
 */
public class AMDecoder extends NBFMDecoder
{
    private final AudioGainAndDcFilter mAudioGain;

    public AMDecoder(DecodeConfigAM config)
    {
        this(config, new AudioGainAndDcFilter(0.5f, 16.0f, 0.75f));
    }

    AMDecoder(DecodeConfigAM config, AudioGainAndDcFilter audioGain)
    {
        super(config, new AmplitudeDemodulator(), new CarrierSquelch(config.getSquelchNoiseOpenThreshold(),
            config.getSquelchNoiseCloseThreshold(), config.getSquelchHysteresisOpenThreshold(),
            config.getSquelchHysteresisCloseThreshold()));
        mAudioGain = Objects.requireNonNull(audioGain);
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.AM;
    }

    @Override
    public DecodeConfigAM getDecodeConfiguration()
    {
        return (DecodeConfigAM)super.getDecodeConfiguration();
    }

    @Override
    protected float[] processOutputAudio(float[] audio)
    {
        return mAudioGain.process(audio);
    }

    @Override
    protected void onCallStart()
    {
        mAudioGain.reset();
    }
}
