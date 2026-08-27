/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JavaFxWindowLoadingShellUiContractTest
{
    private static final Path WINDOW_MANAGER =
        Path.of("src/main/java/io/github/dsheirer/gui/JavaFxWindowManager.java");
    private static final Path CONFIGURATION_EDITOR =
        Path.of("src/main/java/io/github/dsheirer/gui/configuration/ConfigurationEditor.java");

    @Test
    void playlistAndSettingsRevealAThemeAwareLoadingShellBeforeBuildingTheirEditors() throws Exception
    {
        String manager = normalizedSource(WINDOW_MANAGER);
        String configuration = normalizedSource(CONFIGURATION_EDITOR);
        String configurationStage = block(manager, "private Stage getConfigurationStage()",
            "/**\n     * Processes a configuration editor request");
        String preferencesStage = block(manager, "private Stage getUserPreferencesStage()",
            "/**\n     * Processes a user preferences editor request");
        String loading = block(manager, "private static void showLoadingStage(",
            "private static void installLoadedContent(");

        assertTrue(configurationStage.contains(
            "createLoadingShell(\"Playlist\", \"Loading configuration editor…\")"));
        assertFalse(configurationStage.contains("getConfigurationEditor()"));
        assertTrue(preferencesStage.contains(
            "createLoadingShell(\"Settings\", \"Loading user preferences…\")"));
        assertFalse(preferencesStage.contains("getUserPreferencesEditor()"));
        assertTrue(manager.contains("root.setStyle(\"-fx-background-color: -fx-background;\")"));
        assertTrue(manager.contains("titleLabel.setStyle(LOADING_TEXT_STYLE)"));
        assertTrue(manager.contains("statusLabel.setStyle(LOADING_TEXT_STYLE)"));
        assertFalse(manager.contains("titleLabel.setTextFill"));
        assertFalse(manager.contains("statusLabel.setTextFill"));
        assertFalse(manager.contains("root.setBackground(new Background"));
        assertTrue(manager.contains("root.setId(\"javafx-loading-shell\")"));
        assertFalse(manager.contains("new ProgressIndicator"));

        assertTrue(loading.contains("guardLoadingSetup(loadingGate"));
        assertTrue(loading.contains("stage.setOpacity(0.0d)"));
        assertTrue(loading.contains("scene.getRoot().applyCss()"));
        assertTrue(loading.contains("scene.getRoot().layout()"));
        assertTrue(loading.contains("scene.snapshot("));
        assertTrue(loading.contains("stage.setOpacity(1.0d)"));
        assertTrue(loading.contains("if(++mFrames >= 2)"));
        assertTrue(loading.indexOf("stage.setOpacity(1.0d)") <
            loading.indexOf("guardLoadingSetup(loadingGate, loader, failureHandler)"));
        assertTrue(loading.contains("loadingGate.fail()"));
        assertTrue(loading.contains("showLoadingFailure(loadingShell, failureMessage)"));
        assertTrue(loading.contains("recoverLoadingStage(\"restore opacity\", () -> stage.setOpacity(1.0d))"));

        assertTrue(manager.contains("loadingShell.root().setPadding(Insets.EMPTY)"));
        assertTrue(manager.contains("loadingShell.root().getChildren().setAll(content)"));
        assertTrue(configuration.contains("setTop(getMenuBar());"));
        assertTrue(configuration.contains("setCenter(getTabPane());"));
        assertFalse(configuration.contains("Platform.runLater"));
    }

    private static String block(String source, String startMarker, String endMarker)
    {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue(start >= 0, startMarker);
        assertTrue(end > start, endMarker);
        return source.substring(start, end);
    }

    private static String normalizedSource(Path path) throws Exception
    {
        return Files.readString(path).replace("\r\n", "\n");
    }
}
