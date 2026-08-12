/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.am;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;

/**
 * AM conventional channel configuration. It shares the current analog audio controls with NBFM.
 */
public class DecodeConfigAM extends DecodeConfigNBFM
{
    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.AM;
    }

    @Override
    protected Bandwidth getDefaultBandwidth()
    {
        return Bandwidth.BW_15_0;
    }

    @JsonIgnore
    @Override
    public ChannelSpecification getChannelSpecification()
    {
        return switch(getBandwidth())
        {
            case BW_3_0 -> new ChannelSpecification(25000.0, 3000, 1500.0, 1700.0);
            case BW_5_0 -> new ChannelSpecification(25000.0, 5000, 2500.0, 2700.0);
            case BW_8_33 -> new ChannelSpecification(25000.0, 10000, 5000.0, 7000.0);
            case BW_15_0 -> new ChannelSpecification(50000.0, 15000, 7500.0, 9500.0);
            case BW_25_0 -> new ChannelSpecification(50000.0, 25000, 12500.0, 14500.0);
            default -> throw new IllegalArgumentException("Unsupported AM bandwidth: " + getBandwidth());
        };
    }
}
