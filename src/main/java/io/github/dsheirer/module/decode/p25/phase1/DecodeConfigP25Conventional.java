/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase1;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;

/**
 * APCO25 Phase 1 conventional decoder configuration.
 */
public class DecodeConfigP25Conventional extends DecodeConfiguration
{
    private Modulation mModulation = Modulation.C4FM;

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.P25_CONVENTIONAL;
    }

    public Modulation getModulation()
    {
        return mModulation;
    }

    public void setModulation(Modulation modulation)
    {
        mModulation = modulation != null ? modulation : Modulation.C4FM;
    }

    /**
     * Source channel specification for this decoder.
     */
    @JsonIgnore
    @Override
    public ChannelSpecification getChannelSpecification()
    {
        return new ChannelSpecification(50000.0, 12500, 5750.0, 6500.0);
    }
}
