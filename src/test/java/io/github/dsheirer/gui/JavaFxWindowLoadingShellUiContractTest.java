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
        String loading = block(manager, "private static void showLoadingStage(",
            "private static void installLoadedContent(");

        assertTrue(manager.contains("createLoadingShell(\"Playlist\", \"Loading configuration editor…\")"));
        assertTrue(manager.contains("createLoadingShell(\"Settings\", \"Loading user preferences…\")"));
        assertTrue(manager.contains("root.setStyle(\"-fx-background-color: -fx-background;\")"));
        assertFalse(manager.contains("root.setBackground(new Background"));
        assertTrue(manager.contains("root.setId(\"javafx-loading-shell\")"));
        assertFalse(manager.contains("new ProgressIndicator"));
        assertTrue(loading.contains("stage.setOpacity(0.0d)"));
        assertTrue(loading.contains("scene.snapshot("));
        assertTrue(loading.contains("stage.setOpacity(1.0d)"));
        assertTrue(loading.contains("if(++mFrames >= 2)"));
        assertTrue(loading.indexOf("stage.setOpacity(1.0d)") < loading.indexOf("loader.run()"));
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
