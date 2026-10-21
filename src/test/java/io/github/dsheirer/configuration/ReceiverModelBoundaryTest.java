/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ReceiverModelBoundaryTest
{
    @Test
    void receiverModelsAndAdministrationDoNotDependOnDesktopToolkits() throws Exception
    {
        Pattern desktopImport = Pattern.compile("(?m)^import (?:javafx\\.|javax\\.swing\\.|java\\.awt\\.)");
        Path root = Path.of("src/main/java/io/github/dsheirer");
        for(String directory: List.of("alias", "configuration", "channel", "controller/channel", "audio/broadcast",
            "identifier/tone", "receiver", "source/tuner/manager", "module/decode/dmr/channel",
            "audio/codec/mbe/decrypt", "preference/encryption/vault"))
        {
            try(var paths = Files.walk(root.resolve(directory)))
            {
                for(Path file: paths.filter(path -> path.toString().endsWith(".java")).toList())
                    assertFalse(desktopImport.matcher(Files.readString(file)).find(), file.toString());
            }
        }
    }
}
