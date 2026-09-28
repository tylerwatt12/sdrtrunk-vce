/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Source contracts for the intentionally small local operator shell. */
class JavaShellCleanupUiContractTest
{
    private static final Path ROOT = Path.of("src/main/java/io/github/dsheirer/gui");

    @Test
    void onlyPrimaryWebButtonRemainsInTheVisibleLocalShell() throws Exception
    {
        String application = Files.readString(ROOT.resolve("SDRTrunk.java"));
        String preferences = Files.readString(ROOT.resolve("preference/UserPreferencesEditor.java"));
        String webSettings = Files.readString(ROOT.resolve("preference/stats/WebServerPreferenceEditor.java"));

        assertTrue(application.contains("new JButton(\"Web\""));
        assertTrue(application.contains("getNetworkAccessPanel()"));
        assertTrue(application.contains("mCopyNetworkAccessUrlButton"));
        assertTrue(application.contains("state.anyIpEnabled()"));
        assertTrue(application.contains("Cannot connect (local only)"));
        assertTrue(application.contains("mNetworkAccessWarningLabel.setVisible(status.warning() != null)"));
        assertTrue(application.contains("mCopyNetworkAccessUrlButton.setEnabled(status.url() != null)"));
        assertTrue(application.contains("String adminWarning = warning;"));
        assertTrue(application.contains("ensureShellFitsContent();"));
        assertFalse(application.contains("Streaming (Web)"));
        assertFalse(application.contains("Screen Capture"));
        assertFalse(preferences.contains("Streaming (Web)"));
        assertFalse(webSettings.contains("getOpenButton()"));
    }

    @Test
    void debugRecordingExplainsItsStopAndFileSizeLifecycle() throws Exception
    {
        String recorder = Files.readString(ROOT.resolve("diagnostic/BasebandRecordingDialog.java"));

        assertTrue(recorder.contains("Stop & Close"));
        assertTrue(recorder.contains("WAV finalization may continue briefly"));
        assertTrue(recorder.contains("mSize.setText("));
        assertTrue(recorder.contains("mFile.setText("));
        assertTrue(recorder.contains("mLatestStatus.set(null);"));
        assertTrue(recorder.contains("mFile.setText(\"\")"));
        assertTrue(recorder.contains("public void dispose()"));
        assertTrue(recorder.contains("stopOwnedRecording();"));
    }

    @Test
    void obsoleteCaptureControlsAreGoneButNoStoredDirectoryIsDeleted() throws Exception
    {
        String directoryEditor = Files.readString(ROOT.resolve("preference/directory/DirectoryPreferenceEditor.java"));
        String directoryPreference = Files.readString(
            Path.of("src/main/java/io/github/dsheirer/preference/directory/DirectoryPreference.java"));
        String migrator = Files.readString(
            Path.of("src/main/java/io/github/dsheirer/database/upgrade/ApplicationDatabaseMigrator.java"));
        String wizard = Files.readString(ROOT.resolve("setup/SetupWizard.java"));

        assertFalse(directoryEditor.contains("getDirectoryScreenCapture"));
        assertFalse(directoryPreference.contains("DIRECTORY_SCREEN_CAPTURE"));
        assertFalse(directoryPreference.contains("getDirectoryScreenCapture"));
        assertFalse(directoryPreference.contains("setDirectoryScreenCapture"));
        assertFalse(directoryPreference.contains("resetDirectoryScreenCapture"));
        assertFalse(directoryPreference.contains("getDefaultScreenCaptureDirectory"));
        assertTrue(migrator.contains("\"directory.screen.capture\""));
        assertFalse(wizard.contains("Screenshots:"));
        assertTrue(directoryEditor.contains("setFitToWidth(true)"));
        assertTrue(wizard.contains("openWebAfterSetup"));
    }

    @Test
    void deadDesktopDeepLinksAreNotLeftRegistered() throws Exception
    {
        String manager = Files.readString(ROOT.resolve("JavaFxWindowManager.java"));
        String service = Files.readString(
            Path.of("src/main/java/io/github/dsheirer/stats/StatsWebServerService.java"));
        String sessions = Files.readString(
            Path.of("src/main/java/io/github/dsheirer/web/http/WebSessionHttpController.java"));

        assertFalse(manager.contains("process(ViewWebAliasRequest"));
        assertFalse(manager.contains("process(ViewWebP25BandplanOverrideRequest"));
        assertFalse(Files.exists(ROOT.resolve("ViewWebAliasRequest.java")));
        assertFalse(Files.exists(ROOT.resolve("ViewWebP25BandplanOverrideRequest.java")));
        assertTrue(service.contains("createDesktopAdministratorHandoffUri()"));
        assertFalse(service.contains("createDesktopAdministratorAliasHandoffUri"));
        assertFalse(service.contains("createDesktopAdministratorStreamingHandoffUri"));
        assertFalse(service.contains("createDesktopAdministratorP25BandplanOverrideHandoffUri"));
        assertFalse(sessions.contains("desktopAliasHandoffPath"));
        assertFalse(sessions.contains("desktopStreamingHandoffPath"));
        assertFalse(sessions.contains("desktopP25BandplanOverrideHandoffPath"));
    }

    @Test
    void deadViewerLaunchersAreGoneButTheBitsViewerRemainsReachable() throws Exception
    {
        String application = Files.readString(ROOT.resolve("SDRTrunk.java"));
        String manager = Files.readString(ROOT.resolve("JavaFxWindowManager.java"));
        String recordingViewer = Files.readString(ROOT.resolve("viewer/MessageRecordingViewer.java"));
        String nxdnViewer = Files.readString(ROOT.resolve("viewer/NxdnViewer.java"));

        assertTrue(application.contains("Message Recording Viewer (.bits)"));
        assertTrue(manager.contains("new MessageRecordingViewer()"));
        assertFalse(recordingViewer.contains("public static void main(String[] args)"));
        assertFalse(nxdnViewer.contains("static void main()"));
        assertFalse(nxdnViewer.contains("class A implements Listener<IMessage>"));
    }

    @Test
    void retiredJavaUiScaffoldingIsRemoved() throws Exception
    {
        String application = Files.readString(ROOT.resolve("SDRTrunk.java"));
        String manager = Files.readString(ROOT.resolve("JavaFxWindowManager.java"));
        String configurationManager = Files.readString(
            Path.of("src/main/java/io/github/dsheirer/configuration/ConfigurationManager.java"));
        String aliasAdministration = Files.readString(
            Path.of("src/main/java/io/github/dsheirer/alias/AliasAdministrationService.java"));

        assertFalse(application.contains("Reset Table Column Widths"));
        assertFalse(application.contains("JTableColumnWidthMonitor"));
        assertFalse(manager.contains("CONFIGURATION_EDITOR"));
        assertFalse(manager.contains("STAGE_MONITOR_KEY_CALIBRATION_DIALOG"));
        assertFalse(manager.contains("STAGE_MONITOR_KEY_CONFIGURATION_EDITOR"));
        assertFalse(manager.contains("extends Application"));
        assertFalse(manager.contains("public static void main(String[] args)"));
        assertFalse(manager.contains("isCloseEditorRequest"));
        assertFalse(configurationManager.contains("IAliasListRefreshListener"));
        assertFalse(configurationManager.contains("prepareForAliasListRefresh"));
        assertFalse(configurationManager.contains("beforePublication"));
        assertFalse(aliasAdministration.contains("prepareForAliasListRefresh"));

        String[] removed = {
            "WebAdministratorNavigator.java",
            "configuration/ConfigurationEditorRequest.java",
            "configuration/ViewConfigurationRequest.java",
            "configuration/AliasMutationUi.java",
            "configuration/Editor.java",
            "configuration/IAliasListRefreshListener.java",
            "editor/Editor.java",
            "RecentFilesMenu.java",
            "JavaFxWindowRequest.java",
            "ChannelMemoryLogger.java",
            "control/ConstellationViewer.java",
            "control/DbPowerMeter.java",
            "control/FrequencyTextField.java",
            "control/HexFormatter.java",
            "control/IntegerFormatter.java",
            "control/JFrequencyControl.java",
            "control/LongFormatter.java",
            "control/MaxLengthUnaryOperator.java",
            "control/PrefixIdentFormatter.java",
            "power/PeakMonitor.java",
            "symbol/ChannelView.java"
        };

        for(String relativePath: removed)
        {
            assertFalse(Files.exists(ROOT.resolve(relativePath)), relativePath);
        }

        assertFalse(Files.exists(Path.of(
            "src/main/java/io/github/dsheirer/preference/swing/JTableColumnWidthMonitor.java")));
    }

    @Test
    void twoRowStatusFooterFitsTheMinimumShellWidth() throws Exception
    {
        String footer = Files.readString(Path.of("src/main/java/io/github/dsheirer/monitor/StatusBox.java"));
        double secondaryRowWidth = 2 * constant(footer, "STORAGE_CELL_WIDTH") +
            constant(footer, "STATS_CELL_WIDTH") + constant(footer, "WEB_CELL_WIDTH") +
            constant(footer, "VAULT_CELL_WIDTH") + 4 * 2 + 8;

        //Main frame is at least 480px wide with 6px of layout inset on each side.
        assertTrue(secondaryRowWidth <= 480 - 12, "Secondary status row must fit the compact shell");
        assertTrue(footer.contains("mPrimaryRow"));
        assertTrue(footer.contains("mSecondaryRow"));
    }

    private static double constant(String source, String name)
    {
        Matcher matcher = Pattern.compile(name + " = (\\d+);").matcher(source);
        assertTrue(matcher.find(), "Missing status width " + name);
        return Double.parseDouble(matcher.group(1));
    }
}
