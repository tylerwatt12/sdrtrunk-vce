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
import java.util.List;
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
    void onlyRuntimeCoordinateRemainsFromSwingMapViewer() throws Exception
    {
        Path sourceRoot = Path.of("src/main/java/org/jdesktop");
        try(var files = Files.walk(sourceRoot))
        {
            List<Path> retained = files.filter(Files::isRegularFile)
                .map(sourceRoot::relativize).sorted().toList();
            assertEquals(List.of(Path.of("swingx/mapviewer/GeoPosition.java")), retained);
        }
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
    void dormantEngineeringViewersAreNotCompiledIntoTheReceiver() throws Exception
    {
        for(String path: List.of(
            "src/main/java/io/github/dsheirer/gui/channelizer/ChannelizerViewer.java",
            "src/main/java/io/github/dsheirer/gui/channelizer/ChannelizerViewer2.java",
            "src/main/java/io/github/dsheirer/gui/channelizer/SynthesizerViewer.java",
            "src/main/java/io/github/dsheirer/gui/viewer/symbol/SymbolViewerFX.java",
            "src/main/java/io/github/dsheirer/gui/viewer/sync/SyncResultsViewer.java",
            "src/main/java/io/github/dsheirer/spectrum/ComplexDftProcessor.java",
            "src/main/java/io/github/dsheirer/spectrum/SpectrumPanel.java",
            "src/main/java/io/github/dsheirer/dsp/filter/design/FilterViewer.java",
            "src/main/java/io/github/dsheirer/dsp/filter/design/FilterView.java",
            "src/main/java/io/github/dsheirer/gui/control/CurveFittedAreaChart.java",
            "src/main/java/io/github/dsheirer/filter/FilterEditor.java",
            "src/main/java/io/github/dsheirer/log/TextAreaLogAppender.java",
            "src/main/java/io/github/dsheirer/controller/channel/AutoStartChannelModel.java",
            "src/main/java/io/github/dsheirer/controller/channel/ConfigurationValidationException.java",
            "src/main/java/io/github/dsheirer/dsp/afsk/AFSK1200DecoderInstrumented.java",
            "src/main/java/io/github/dsheirer/dsp/afsk/AFSKSampleBufferInstrumented.java",
            "src/main/java/io/github/dsheirer/dsp/afsk/AFSKTimingErrorDetectorInstrumented.java",
            "src/main/java/io/github/dsheirer/dsp/fsk/SampleBufferInstrumented.java",
            "src/main/java/io/github/dsheirer/dsp/fsk/ZeroCrossingErrorDetectorInstrumented.java",
            "src/main/java/io/github/dsheirer/module/decode/nxdn/channel/ObservableChannelFrequency.java",
            "src/main/java/io/github/dsheirer/util/ColorIcon.java",
            "src/main/java/io/github/dsheirer/util/SwingUtils.java"))
        {
            assertFalse(Files.exists(Path.of(path)), () -> "Retired engineering UI remains: " + path);
        }

        String p25Lsm = Files.readString(Path.of(
            "src/main/java/io/github/dsheirer/module/decode/p25/phase1/P25P1DemodulatorLSM.java"));
        String p25C4fm = Files.readString(Path.of(
            "src/main/java/io/github/dsheirer/module/decode/p25/phase1/P25P1DemodulatorC4FM.java"));
        String dmr = Files.readString(Path.of(
            "src/main/java/io/github/dsheirer/module/decode/dmr/DMRSoftSymbolProcessor.java"));

        assertFalse(p25Lsm.contains("SymbolViewer"));
        assertFalse(p25C4fm.contains("SyncResultsViewer"));
        assertFalse(p25C4fm.contains("visualizeSyncDetect"));
        assertFalse(dmr.contains("SyncResultsViewer"));
        assertFalse(dmr.contains("visualizeSyncDetect"));
        assertFalse(dmr.contains("visualizeBufferContents"));

        for(String resource: List.of(
            "src/main/resources/FilterView.css",
            "src/main/resources/images/Curve-fitted-background.png",
            "src/main/resources/images/Curve-fitted-chart-background.png",
            "src/main/resources/images/Curve-fitted-graph-gridlines.png",
            "src/main/resources/sdrtrunk_style.css"))
        {
            assertFalse(Files.exists(Path.of(resource)), () -> "Retired Java UI resource remains: " + resource);
        }

        String icon = Files.readString(Path.of("src/main/java/io/github/dsheirer/icon/Icon.java"));
        String iconModel = Files.readString(Path.of("src/main/java/io/github/dsheirer/icon/IconModel.java"));
        assertFalse(icon.contains("ImageIcon"));
        assertFalse(icon.contains("getFxImage"));
        assertFalse(iconModel.contains("getScaledIcon"));
        assertFalse(iconModel.contains("mResizedIcons"));
    }

    @Test
    void disconnectedAudioAndLegacyDesktopModelsAreNotPackaged() throws Exception
    {
        for(String path: List.of(
            "src/main/java/io/github/dsheirer/audio/convert/thumbdv",
            "src/main/java/io/github/dsheirer/audio/invert",
            "src/main/java/io/github/dsheirer/settings"))
        {
            Path directory = Path.of(path);
            if(Files.exists(directory))
            {
                try(var files = Files.walk(directory))
                {
                    assertFalse(files.anyMatch(Files::isRegularFile),
                        () -> "Retired subsystem sources remain: " + path);
                }
            }
        }

        for(String path: List.of(
            "src/main/java/io/github/dsheirer/map/DefaultIcon.java",
            "src/main/java/io/github/dsheirer/map/MapIcon.java",
            "src/main/java/io/github/dsheirer/gui/CreditsDialog.java"))
        {
            assertFalse(Files.exists(Path.of(path)), () -> "Retired subsystem remains: " + path);
        }

        String build = Files.readString(Path.of("build.gradle"));
        String aliasTypes = Files.readString(Path.of(
            "src/main/java/io/github/dsheirer/alias/id/AliasIDType.java"));

        assertFalse(build.contains("com.fazecast:jSerialComm"));
        assertFalse(build.contains("org.controlsfx:controlsfx"));
        assertFalse(aliasTypes.contains("Audio Inversion"));
        assertTrue(Files.exists(Path.of(
            "src/main/java/io/github/dsheirer/audio/codec/mbe/JmbeAudioModule.java")));
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
