/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.gui.configuration.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class P25SiteIdentityUiContractTest
{
    private static final Path EDITORS =
        Path.of("src/main/java/io/github/dsheirer/gui/configuration/channel");

    @Test
    void learnedIdentityIsLimitedToP25TrunkedEditors() throws Exception
    {
        try(var files = Files.list(EDITORS))
        {
            Set<String> identityEditors = files
                .filter(path -> path.getFileName().toString().endsWith("ConfigurationEditor.java"))
                .filter(path -> {
                    try
                    {
                        return Files.readString(path).contains("new P25SiteIdentityView()");
                    }
                    catch(Exception exception)
                    {
                        throw new RuntimeException(exception);
                    }
                })
                .map(path -> path.getFileName().toString())
                .collect(Collectors.toSet());

            assertEquals(Set.of("P25P1ConfigurationEditor.java", "P25P2ConfigurationEditor.java"),
                identityEditors);
        }

        String common = Files.readString(EDITORS.resolve("ChannelConfigurationEditor.java"));
        String view = Files.readString(EDITORS.resolve("P25SiteIdentityView.java"));
        assertFalse(common.contains("P25SiteIdentity"));
        assertTrue(view.contains("Learned Site Identity"));
        assertTrue(view.contains("p25SiteIdentityProperty().addListener"));
    }
}
