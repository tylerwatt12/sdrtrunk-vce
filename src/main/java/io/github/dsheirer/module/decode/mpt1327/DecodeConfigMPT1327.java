/*
 *
 *  * ******************************************************************************
 *  * Copyright (C) 2014-2019 Dennis Sheirer
 *  *
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *  * *****************************************************************************
 *
 *
 */
package io.github.dsheirer.module.decode.mpt1327;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;

/**
 * Inert compatibility object for configurations written by releases that supported MPT-1327.  It exists only so
 * legacy XML can be parsed and filtered without preventing supported configuration from importing.
 */
@Deprecated(forRemoval = false)
public final class DecodeConfigMPT1327 extends DecodeConfiguration
{
    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.MPT1327;
    }

    @JsonIgnore
    @Override
    public ChannelSpecification getChannelSpecification()
    {
        throw new UnsupportedOperationException("MPT-1327 decoder support is retired");
    }
}
