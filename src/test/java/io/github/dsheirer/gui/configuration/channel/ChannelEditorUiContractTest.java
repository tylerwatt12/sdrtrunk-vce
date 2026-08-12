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

package io.github.dsheirer.gui.configuration.channel;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Source contract for the channel list action column.
 */
class ChannelEditorUiContractTest
{
    private static final Path CHANNEL_EDITOR =
        Path.of("src/main/java/io/github/dsheirer/gui/configuration/channel/ChannelEditor.java");
    private static final Path ANALOG_EDITOR =
        Path.of("src/main/java/io/github/dsheirer/gui/configuration/channel/NBFMConfigurationEditor.java");
    private static final Path SQUELCH_DIAGNOSTIC =
        Path.of("src/main/java/io/github/dsheirer/gui/squelch/NoiseSquelchView.java");

    @Test
    void actionColumnRetainsItsPreferredWidth() throws Exception
    {
        String source = Files.readString(CHANNEL_EDITOR);

        assertTrue(source.contains("mButtonBox.setMinWidth(Region.USE_PREF_SIZE)"));
        assertTrue(source.contains("mButtonBox.getChildren().addAll(getNewButton(), getCloneButton(), " +
            "getDeleteButton())"));
    }

    @Test
    void analogSquelchSettingsBelongToTheChannelEditorAndDiagnosticsRemainReadOnly() throws Exception
    {
        String editor = Files.readString(ANALOG_EDITOR);
        String diagnostic = Files.readString(SQUELCH_DIAGNOSTIC);

        assertTrue(editor.contains("new TitledPane(\"Squelch\""));
        assertTrue(editor.contains("config.setSquelchNoiseOpenThreshold(noiseOpen)"));
        assertTrue(editor.contains("config.setSquelchNoiseCloseThreshold(noiseClose)"));
        assertTrue(editor.contains("config.setSquelchHysteresisOpenThreshold(hysteresisOpen)"));
        assertTrue(editor.contains("config.setSquelchHysteresisCloseThreshold(hysteresisClose)"));
        assertFalse(diagnostic.contains(".setNoiseThreshold("));
        assertFalse(diagnostic.contains(".setHysteresisThreshold("));
        assertFalse(diagnostic.contains(".setSquelchOverride("));
        assertFalse(diagnostic.contains("scheduleConfigurationSave"));
    }
}
