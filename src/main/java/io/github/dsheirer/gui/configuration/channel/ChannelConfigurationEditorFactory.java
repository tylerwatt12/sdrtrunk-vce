/*
 * *****************************************************************************
 * Copyright (C) 2014-2023 Dennis Sheirer
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

package io.github.dsheirer.gui.configuration.channel;

import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides access to channel configuration editors for various decoder types.
 */
public class ChannelConfigurationEditorFactory
{
    private static final Logger mLog = LoggerFactory.getLogger(ChannelConfigurationEditorFactory.class);
    private static List<DecoderType> mLoggedUnrecognizedTypes = new ArrayList<>();

    private ChannelConfigurationEditorFactory()
    {
    }

    /**
     * Constructs an editor for the specified decoder type
     * @param decoderType to create
     * @param configurationManager for the editor
     * @param tunerManager for tuners
     * @param userPreferences for preferences
     * @param filterProcessor to be notified to clear and restore any applied filters.
     * @return constructed editor
     */
    public static ChannelConfigurationEditor getEditor(DecoderType decoderType, ConfigurationManager configurationManager,
                                                       TunerManager tunerManager, UserPreferences userPreferences,
                                                       IFilterProcessor filterProcessor)
    {
        switch(decoderType)
        {
            case AM:
                return new AMConfigurationEditor(configurationManager, tunerManager, userPreferences, filterProcessor);
            case DMR:
                return new DMRConfigurationEditor(configurationManager, tunerManager, userPreferences, filterProcessor);
            case NBFM:
                return new NBFMConfigurationEditor(configurationManager, tunerManager, userPreferences, filterProcessor);
            case LTR_NET:
                return new LTRNetConfigurationEditor(configurationManager, tunerManager, userPreferences, filterProcessor);
            case LTR:
                return new LTRConfigurationEditor(configurationManager, tunerManager, userPreferences, filterProcessor);
            case MPT1327:
                return new MPT1327ConfigurationEditor(configurationManager, tunerManager, userPreferences, filterProcessor);
            case PASSPORT:
                return new PassportConfigurationEditor(configurationManager, tunerManager, userPreferences, filterProcessor);
            case P25_CONVENTIONAL:
                return new P25ConventionalConfigurationEditor(configurationManager, tunerManager, userPreferences,
                    filterProcessor);
            case P25_PHASE1:
                return new P25P1ConfigurationEditor(configurationManager, tunerManager, userPreferences, filterProcessor);
            case P25_PHASE2:
                return new P25P2ConfigurationEditor(configurationManager, tunerManager, userPreferences, filterProcessor);
            default:
                if(decoderType != null && !mLoggedUnrecognizedTypes.contains(decoderType))
                {
                    mLog.warn("Can't create channel configuration editor - unrecognized decoder type: " + decoderType);
                    mLoggedUnrecognizedTypes.add(decoderType);
                }
                return null;
        }
    }
}
