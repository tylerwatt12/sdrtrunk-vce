/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.controller;

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
    private static final Path DEBUG_RECORDER =
        Path.of("src/main/java/io/github/dsheirer/gui/diagnostic/BasebandRecordingDialog.java");
    private static final Path TUNER_EVENT =
        Path.of("src/main/java/io/github/dsheirer/source/tuner/TunerEvent.java");
    private static final Path USER_PREFERENCES =
        Path.of("src/main/java/io/github/dsheirer/preference/UserPreferences.java");

    @Test
    void mapIsReceiverOwnedWithoutSwingController() throws Exception
    {
        String application = Files.readString(APPLICATION);

        assertFalse(Files.exists(CONTROLLER));
        assertFalse(Files.exists(Path.of("src/main/java/io/github/dsheirer/map/MapPanel.java")));
        assertFalse(Files.exists(Path.of("src/main/java/io/github/dsheirer/map/MapService.java")));
        assertTrue(application.contains("new MapSnapshotService(aliasModel)"));
        assertTrue(application.contains("addDecodeEventListener(mMapSnapshotService)"));
        assertTrue(application.contains("setMapSnapshotService(mMapSnapshotService)"));
        assertFalse(application.contains("mControllerPanel"));
        assertFalse(application.contains("new MapService("));
    }

    @Test
    void applicationHasNoSystemsViewOrLowerViewWiring() throws Exception
    {
        String application = Files.readString(APPLICATION);
        String preference = Files.readString(PREFERENCE);

        assertFalse(application.contains("PREFERENCE_NOW_PLAYING_LOWER_VIEWS_VISIBLE"));
        assertFalse(application.contains("NOW_PLAYING_SPLIT_PANE_DIVIDER_IDENTIFIER"));
        assertFalse(application.contains("CHANNEL_SPECTRUM_SPLIT_PANE_DIVIDER_IDENTIFIER"));
        assertFalse(application.contains("getLowerViewsToggleButton"));
        assertFalse(application.contains("getNowPlayingPanel"));
        assertFalse(preference.contains("JavaInterfaceView"));
        assertFalse(Files.exists(PREFERENCE_EDITOR));
    }

    @Test
    void applicationHasNoReceiverLocalSpectrumOrWaterfall() throws Exception
    {
        String application = Files.readString(APPLICATION);
        String tunerEvent = Files.readString(TUNER_EVENT);
        String userPreferences = Files.readString(USER_PREFERENCES);

        assertFalse(application.contains("SpectralDisplayPanel"));
        assertFalse(application.contains("SpectrumFrame"));
        assertFalse(application.contains("SpectrumWaterfall"));
        assertFalse(application.contains("TunersMenu"));
        assertFalse(Files.exists(TUNER_EDITOR));
        assertFalse(Files.exists(Path.of(
            "src/main/java/io/github/dsheirer/source/tuner/ui/TunerViewPanel.java")));
        assertFalse(tunerEvent.contains("SPECTRAL_DISPLAY"));
        assertFalse(userPreferences.contains("SpectrumPreference"));
        assertFalse(Files.exists(Path.of(
            "src/main/java/io/github/dsheirer/spectrum/WaterfallPanel.java")));
        assertFalse(Files.exists(Path.of(
            "src/main/java/io/github/dsheirer/spectrum/SpectralDisplayPanel.java")));
    }

    @Test
    void localDebugRecorderSurvivesTunerEditorRemoval() throws Exception
    {
        String application = Files.readString(APPLICATION);
        String recorder = Files.readString(DEBUG_RECORDER);

        assertTrue(application.contains("Baseband Recording (Debug)"));
        assertTrue(recorder.contains("controller.startRecorder("));
        assertTrue(recorder.contains("controller.stopRecorder();"));
        assertTrue(recorder.contains("public void dispose()"));
        assertFalse(recorder.contains("selected.setEnabled("));
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

}
