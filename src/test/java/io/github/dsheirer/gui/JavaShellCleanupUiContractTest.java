/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.gui.preference.ViewUserPreferenceEditorRequest;
import io.github.dsheirer.gui.preference.encryption.ViewEncryptionKeyPreferenceEditorRequest;
import io.github.dsheirer.gui.viewer.ViewRecordingViewerRequest;
import io.github.dsheirer.jmbe.JmbeEditorRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Active controls and request boundaries for the intentionally small local operator shell. */
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
        assertTrue(application.contains("Not available (this computer only)"));
        assertTrue(application.contains("mNetworkAccessWarningLabel.setVisible(status.warning() != null)"));
        assertTrue(application.contains("mCopyNetworkAccessUrlButton.setEnabled(status.url() != null)"));
        assertTrue(application.contains("String adminWarning = warning;"));
        assertTrue(application.contains("ensureShellFitsContent();"));
        assertFalse(application.contains("Streaming (Web)"));
        assertFalse(application.contains("Screen Capture"));
        assertFalse(application.contains("JMenuItem preferencesItem"));
        assertFalse(application.contains("Credits & Licensing"));
        assertFalse(Files.exists(ROOT.resolve("CreditsDialog.java")));
        assertFalse(preferences.contains("Streaming (Web)"));
        assertFalse(webSettings.contains("getOpenButton()"));
        assertFalse(webSettings.contains("Editable assets folder"));
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
        assertFalse(directoryEditor.contains("JMBE Libraries"));
        assertFalse(directoryEditor.contains("getDirectoryJmbe"));
        assertFalse(directoryPreference.contains("getDirectoryJmbe"));
        assertFalse(directoryPreference.contains("setDirectoryJmbe"));
        assertFalse(directoryPreference.contains("resetDirectoryJmbe"));
        assertTrue(directoryPreference.contains("getDefaultJmbeDirectory"));
        assertTrue(migrator.contains("\"directory.jmbe\""));
        assertFalse(wizard.contains("Screenshots:"));
        assertTrue(directoryEditor.contains("setFitToWidth(true)"));
        assertTrue(wizard.contains("openWebAfterSetup"));
        String application = Files.readString(ROOT.resolve("SDRTrunk.java"));
        assertTrue(application.contains("openWebInterface(false, DesktopHandoffDestination.CHANNEL_SETUP)"));
        assertTrue(application.contains("openWebInterface(true, DesktopHandoffDestination.DASHBOARD)"));
    }

    @Test
    void bitsViewerRemainsReachableFromDiagnostics() throws Exception
    {
        String application = Files.readString(ROOT.resolve("SDRTrunk.java"));
        String manager = Files.readString(ROOT.resolve("JavaFxWindowManager.java"));

        assertTrue(application.contains("Message Recording Viewer (.bits)"));
        assertTrue(application.contains("diagnosticsMenu.add(recordingViewerMenu)"));
        assertFalse(application.contains("viewMenu.add(recordingViewerMenu)"));
        assertTrue(manager.contains("new MessageRecordingViewer()"));
    }

    @Test
    void localWindowsKeepTheirSupportedRequestBoundaryAndSavedMaximization() throws Exception
    {
        Set<Class<?>> requestTypes = Arrays.stream(JavaFxWindowManager.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(Subscribe.class))
            .map(method -> {
                assertEquals(1, method.getParameterCount(), "Each window request has one event");
                return method.getParameterTypes()[0];
            })
            .collect(Collectors.toSet());
        assertEquals(Set.of(JmbeEditorRequest.class, ViewUserPreferenceEditorRequest.class,
            ViewEncryptionKeyPreferenceEditorRequest.class, ViewRecordingViewerRequest.class), requestTypes);

        String javaFxPreferences = Files.readString(
            Path.of("src/main/java/io/github/dsheirer/preference/javafx/JavaFxPreferences.java"));
        assertTrue(javaFxPreferences.contains(
            "getBoolean(PREFERENCE_STAGE_MAXIMIZED + mKey, stage.isMaximized())"));
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
