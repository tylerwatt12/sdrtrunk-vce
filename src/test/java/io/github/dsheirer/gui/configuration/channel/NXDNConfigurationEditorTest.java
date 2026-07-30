/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui.configuration.channel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NXDNConfigurationEditorTest
{
    private static final Path EDITOR = Path.of(
        "src/main/java/io/github/dsheirer/gui/configuration/channel/NXDNConfigurationEditor.java");

    @Test
    void wiresExplicitChannelModeAndTrunkOnlyControls() throws IOException
    {
        String source = Files.readString(EDITOR);

        assertTrue(source.contains("new ComboBox<>(FXCollections.observableArrayList(NXDNChannelMode.values()))"));
        assertTrue(source.contains("getChannelModeComboBox().setValue(configNXDN.getChannelMode())"));
        assertTrue(source.contains("config.setChannelMode(getChannelModeComboBox().getValue())"));
        assertTrue(source.contains("getChannelMapTable().setDisable(!trunked)"));
        assertTrue(source.contains("getTrafficChannelPoolSizeSpinner().setDisable(!trunked)"));
    }
}
