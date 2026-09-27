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
        String wizard = Files.readString(ROOT.resolve("setup/SetupWizard.java"));

        assertFalse(directoryEditor.contains("getDirectoryScreenCapture"));
        assertFalse(wizard.contains("Screenshots:"));
        assertTrue(directoryEditor.contains("setFitToWidth(true)"));
        assertTrue(wizard.contains("openWebAfterSetup"));
    }

    @Test
    void deadDesktopDeepLinksAreNotLeftRegistered() throws Exception
    {
        String manager = Files.readString(ROOT.resolve("JavaFxWindowManager.java"));

        assertFalse(manager.contains("process(ViewWebAliasRequest"));
        assertFalse(manager.contains("process(ViewWebP25BandplanOverrideRequest"));
        assertFalse(Files.exists(ROOT.resolve("ViewWebAliasRequest.java")));
        assertFalse(Files.exists(ROOT.resolve("ViewWebP25BandplanOverrideRequest.java")));
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
