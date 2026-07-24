/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
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

package io.github.dsheirer.channel.quality;

import java.util.OptionalDouble;

/**
 * Supplies the most recent usable control-channel decode health for a stable configured site identity.
 *
 * <p>Implementations used by the audio-call coordinator must be an in-memory lookup. They must not perform database,
 * network, filesystem, or other blocking work on the coordinator thread.</p>
 */
@FunctionalInterface
public interface ControlChannelQualityProvider
{
    ControlChannelQualityProvider NONE = _ -> OptionalDouble.empty();

    /**
     * Gets fresh, active control-channel decode health for the supplied stable site identity.
     *
     * @param stableSiteIdentity stable configured site identity
     * @return decode health from 0 through 100, or empty when the site is inactive, stale, or unknown
     */
    OptionalDouble getDecodeHealthPercent(String stableSiteIdentity);
}
