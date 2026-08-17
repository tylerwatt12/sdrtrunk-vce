/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui.configuration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ConfigurationEditorStartupUiContractTest
{
    private static final Path EDITOR = Path.of("src/main/java/io/github/dsheirer/gui/configuration/ConfigurationEditor.java");

    @Test
    void defersExpensivePlaylistContentUntilAfterTheInitialWindowPulse() throws Exception
    {
        String source = Files.readString(EDITOR);
        int constructor = source.indexOf("public ConfigurationEditor(ConfigurationManager configurationManager,");
        int process = source.indexOf("public void process(ConfigurationEditorRequest request)");
        String initialization = source.substring(constructor, process);

        assertTrue(initialization.contains("setTop(getMenuBar());"));
        assertTrue(initialization.contains("setCenter(getTabPane());"));
        assertTrue(initialization.contains("Platform.runLater"));
    }
}
