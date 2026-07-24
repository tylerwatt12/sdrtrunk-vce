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
package io.github.dsheirer.configuration;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.controller.channel.Channel;

import java.util.ArrayList;
import java.util.List;

public class ConfigurationState
{
    private int mVersion = ConfigurationManager.CONFIGURATION_CURRENT_VERSION;
    private List<Alias> mAliases = new ArrayList<>();
    private List<BroadcastConfiguration> mBroadcastConfigurations = new ArrayList<>();
    private List<Channel> mChannels = new ArrayList<>();

    /**
     * Version number for this configuration state.
     */
    public int getVersion()
    {
        return mVersion;
    }

    /**
     * Sets the version for this configuration state.
     */
    public void setVersion(int version)
    {
        mVersion = version;
    }

    public List<Alias> getAliases()
    {
        return mAliases;
    }

    public void setAliases(List<Alias> aliases)
    {
        mAliases = aliases;
    }

    public List<Channel> getChannels()
    {
        return mChannels;
    }

    public void setChannels(List<Channel> channels)
    {
        mChannels = channels;
    }

    public List<BroadcastConfiguration> getBroadcastConfigurations()
    {
        return mBroadcastConfigurations;
    }

    public void setBroadcastConfigurations(List<BroadcastConfiguration> configurations)
    {
        mBroadcastConfigurations = configurations;
    }
}
