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
package io.github.dsheirer.audio.broadcast;

public enum BroadcastServerType
{
    /**
     * Broadcastify feeds (ie streaming) service
     */
    BROADCASTIFY("Broadcastify Feed"), //Icecast Server 2.3.2

    /**
     * Broadcastify calls - completed audio recording push service
     */
    BROADCASTIFY_CALL("Broadcastify Call"),

    /**
     * Broadcastify calls restricted to calls observed by one configured trunked-site channel
     */
    BROADCASTIFY_CALL_SITE("Broadcastify Calls (Trunked Site)"),

    ICECAST_HTTP("Icecast 2 (v2.4+)"),
    RDIOSCANNER_CALL("Rdio Scanner"),
    OPENMHZ("OpenMHz"),
    RADIORESOLVE("RadioResolve"),
    ICECAST_TCP("Icecast (v2.3)"),
    SHOUTCAST_V1("Shoutcast v1.x"),
    UNKNOWN("Unknown");

    private final String mLabel;

    BroadcastServerType(String label)
    {
        mLabel = label;
    }

    @Override
    public String toString()
    {
        return mLabel;
    }
}
