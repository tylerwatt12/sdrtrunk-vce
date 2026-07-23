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

package io.github.dsheirer.audio.call;

/**
 * Supplies a configured duplicate-call preference for a stable site/channel GUID. Lower values are preferred.
 *
 * <p>The default provider leaves every source unprioritized. This hook intentionally uses the stable configured
 * source GUID instead of a transient tuner or channel identifier so that a future preference or web configuration
 * can make the election repeatable across restarts.</p>
 */
@FunctionalInterface
public interface DuplicateCallPriorityProvider
{
    DuplicateCallPriorityProvider NONE = sourceGuid -> Integer.MAX_VALUE;

    /**
     * Returns the configured priority for the supplied stable source GUID. Lower values are preferred.
     *
     * @param sourceGuid stable source GUID, or {@code null} when the call does not carry one
     * @return configured priority
     */
    int getPriority(String sourceGuid);
}
