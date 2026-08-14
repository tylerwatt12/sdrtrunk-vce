/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Source contract for the deliberately small Java desktop surface.
 */
class JavaDesktopSystemsRemovalUiContractTest
{
    private static final Path CONTROLLER =
        Path.of("src/main/java/io/github/dsheirer/controller/ControllerPanel.java");
    private static final Path APPLICATION = Path.of("src/main/java/io/github/dsheirer/gui/SDRTrunk.java");
    private static final Path CHANNEL_PROCESSING_MANAGER =
        Path.of("src/main/java/io/github/dsheirer/controller/channel/ChannelProcessingManager.java");
    private static final Path PREFERENCE =
        Path.of("src/main/java/io/github/dsheirer/preference/nowplaying/NowPlayingPreference.java");
    private static final Path PREFERENCE_EDITOR =
        Path.of("src/main/java/io/github/dsheirer/gui/preference/nowplaying/NowPlayingPreferenceEditor.java");
    private static final Path TUNER_EDITOR =
        Path.of("src/main/java/io/github/dsheirer/source/tuner/ui/TunerEditor.java");
    private static final Path TUNER_EVENT =
        Path.of("src/main/java/io/github/dsheirer/source/tuner/TunerEvent.java");
    private static final Path USER_PREFERENCES =
        Path.of("src/main/java/io/github/dsheirer/preference/UserPreferences.java");

    @Test
    void controllerContainsOnlyOptionalMapAndTunersTabs() throws Exception
    {
        String source = Files.readString(CONTROLLER);

        assertTrue(source.contains("mTabbedPane.addTab(\"Map\", mMapPanel);"));
        assertTrue(source.contains("mTabbedPane.addTab(\"Tuners\", mTunerManagerPanel);"));
        assertEquals(2, occurrences(source, "mTabbedPane.addTab("));
        assertFalse(source.contains("NowPlayingPanel"));
        assertFalse(source.contains("JavaInterfaceView.SYSTEMS"));
        assertFalse(source.contains("addTab(\"Systems\""));
    }

    @Test
    void applicationHasNoSystemsViewOrLowerViewWiring() throws Exception
    {
        String application = Files.readString(APPLICATION);
        String preference = Files.readString(PREFERENCE);
        String editor = Files.readString(PREFERENCE_EDITOR);

        assertFalse(application.contains("PREFERENCE_NOW_PLAYING_LOWER_VIEWS_VISIBLE"));
        assertFalse(application.contains("NOW_PLAYING_SPLIT_PANE_DIVIDER_IDENTIFIER"));
        assertFalse(application.contains("CHANNEL_SPECTRUM_SPLIT_PANE_DIVIDER_IDENTIFIER"));
        assertFalse(application.contains("getLowerViewsToggleButton"));
        assertFalse(application.contains("getNowPlayingPanel"));
        assertTrue(preference.contains("MAP(\"Map\""));
        assertFalse(preference.contains("SPECTRUM(\"Spectrum\""));
        assertFalse(preference.contains("SYSTEMS(\"Systems\""));
        assertFalse(editor.contains("Systems Activity Settings"));
        assertFalse(editor.contains("JavaInterfaceView.values()"));
        assertFalse(editor.contains("JavaInterfaceView.SPECTRUM"));
    }

    @Test
    void applicationHasNoReceiverLocalSpectrumOrWaterfall() throws Exception
    {
        String application = Files.readString(APPLICATION);
        String tunerEditor = Files.readString(TUNER_EDITOR);
        String tunerEvent = Files.readString(TUNER_EVENT);
        String userPreferences = Files.readString(USER_PREFERENCES);

        assertFalse(application.contains("SpectralDisplayPanel"));
        assertFalse(application.contains("SpectrumFrame"));
        assertFalse(application.contains("SpectrumWaterfall"));
        assertFalse(application.contains("TunersMenu"));
        assertFalse(tunerEditor.contains("View Spectrum"));
        assertFalse(tunerEditor.contains("New Spectrum Display"));
        assertFalse(tunerEvent.contains("SPECTRAL_DISPLAY"));
        assertFalse(userPreferences.contains("SpectrumPreference"));
        assertFalse(Files.exists(Path.of(
            "src/main/java/io/github/dsheirer/spectrum/WaterfallPanel.java")));
        assertFalse(Files.exists(Path.of(
            "src/main/java/io/github/dsheirer/spectrum/SpectralDisplayPanel.java")));
    }

    @Test
    void channelMetadataFeedsTheWebActivityModelWithoutASwingRelay() throws Exception
    {
        String source = Files.readString(CHANNEL_PROCESSING_MANAGER);

        assertFalse(source.contains("ChannelMetadataModel"));
        assertFalse(source.contains("ChannelAndMetadata"));
        assertTrue(source.contains("metadata.setUpdateEventListener(mChannelActivityModel)"));
        assertTrue(source.contains("channelMetadata.removeUpdateEventListener()"));
    }

    private static int occurrences(String source, String value)
    {
        int count = 0;
        int offset = 0;

        while((offset = source.indexOf(value, offset)) >= 0)
        {
            count++;
            offset += value.length();
        }

        return count;
    }
}
