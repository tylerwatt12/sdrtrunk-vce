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
    private static final Path CHANNEL_DIAGNOSTIC =
        Path.of("src/main/java/io/github/dsheirer/gui/channel/ChannelSpectrumPanel.java");

    @Test
    void actionColumnRetainsItsPreferredWidth() throws Exception
    {
        String source = Files.readString(CHANNEL_EDITOR);

        assertTrue(source.contains("mButtonBox.setMinWidth(Region.USE_PREF_SIZE)"));
        assertTrue(source.contains("mButtonBox.getChildren().addAll(getNewButton(), getCloneButton(), " +
            "getDeleteButton())"));
    }

    @Test
    void liveAnalogSquelchAdjusterBelongsToTheChannelEditor() throws Exception
    {
        String editor = Files.readString(ANALOG_EDITOR);
        String adjuster = Files.readString(SQUELCH_DIAGNOSTIC);
        String channelDiagnostic = Files.readString(CHANNEL_DIAGNOSTIC);

        assertTrue(editor.contains("new TitledPane(\"Squelch\""));
        assertTrue(editor.contains("config.setSquelchNoiseOpenThreshold(noiseOpen)"));
        assertTrue(editor.contains("config.setSquelchNoiseCloseThreshold(noiseClose)"));
        assertTrue(editor.contains("config.setSquelchHysteresisOpenThreshold(hysteresisOpen)"));
        assertTrue(editor.contains("config.setSquelchHysteresisCloseThreshold(hysteresisClose)"));
        assertTrue(editor.contains("new Button(\"Live Adjuster…\")"));
        assertTrue(editor.contains("new NoiseSquelchView(getConfigurationManager())"));
        assertTrue(editor.contains("getProcessingChain(getItem())"));
        assertTrue(adjuster.contains("mController.setNoiseThreshold(open, close)"));
        assertTrue(adjuster.contains("mController.setHysteresisThreshold(open, close)"));
        assertTrue(adjuster.contains("mConfigurationManager.scheduleConfigurationSave()"));
        assertFalse(channelDiagnostic.contains("NoiseSquelchView"));
        assertFalse(channelDiagnostic.contains("CARD_NOISE_SQUELCH"));
    }

    @Test
    void liveSquelchFeedbackDoesNotBlockTheDecoderThread() throws Exception
    {
        String source = Files.readString(SQUELCH_DIAGNOSTIC);
        int receiveStart = source.indexOf("public void receive(NoiseSquelchState noiseSquelchState)");
        int receiveEnd = source.indexOf("\n    }", receiveStart);
        String receive = source.substring(receiveStart, receiveEnd);

        assertTrue(receive.contains("mPendingState.lazySet(noiseSquelchState)"));
        assertFalse(receive.contains("synchronized"));
        assertFalse(receive.contains("Platform.runLater"));
    }
}
