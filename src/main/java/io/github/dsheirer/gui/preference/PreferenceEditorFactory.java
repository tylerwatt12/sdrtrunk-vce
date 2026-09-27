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

package io.github.dsheirer.gui.preference;

import io.github.dsheirer.gui.preference.application.AppearancePreferenceEditor;
import io.github.dsheirer.gui.preference.calibration.VectorCalibrationPreferenceEditor;
import io.github.dsheirer.gui.preference.decoder.JmbeLibraryPreferenceEditor;
import io.github.dsheirer.gui.preference.decoder.VoiceDecryptionModulePreferenceEditor;
import io.github.dsheirer.gui.preference.directory.DirectoryPreferenceEditor;
import io.github.dsheirer.gui.preference.stats.StatsServerPreferenceEditor;
import io.github.dsheirer.gui.preference.stats.WebServerPreferenceEditor;
import io.github.dsheirer.gui.preference.tuner.TunerPreferenceEditor;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.stats.StatsWebServerService;
import javafx.scene.Node;

/**
 * Creates an editor for the specified preference editor type
 */
public class PreferenceEditorFactory
{
    private PreferenceEditorFactory()
    {
    }

    public static Node getEditor(PreferenceEditorType preferenceEditorType, UserPreferences userPreferences)
    {
        return getEditor(preferenceEditorType, userPreferences, null);
    }

    public static Node getEditor(PreferenceEditorType preferenceEditorType, UserPreferences userPreferences,
                                 StatsWebServerService statsWebServerService)
    {
        switch(preferenceEditorType)
        {
            case APPEARANCE:
                return new AppearancePreferenceEditor(userPreferences);
            case DIRECTORY:
                return new DirectoryPreferenceEditor(userPreferences);
            case JMBE_LIBRARY:
                return new JmbeLibraryPreferenceEditor(userPreferences);
            case VOICE_DECRYPTION_MODULE:
                return new VoiceDecryptionModulePreferenceEditor(userPreferences);
            case SOURCE_TUNERS:
                return new TunerPreferenceEditor(userPreferences);
            case STATS_SERVER:
                return new StatsServerPreferenceEditor(userPreferences);
            case VECTOR_CALIBRATION:
                return new VectorCalibrationPreferenceEditor(userPreferences);
            case WEB_SERVER:
                return new WebServerPreferenceEditor(userPreferences, statsWebServerService);
        }

        return null;
    }
}
