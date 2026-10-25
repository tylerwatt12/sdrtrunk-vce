/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationHistoryInstallTest
{
    @TempDir Path directory;
    @Test void failedConfigurationActivationRemovesOnlyItsOwnMovedActivityFile() throws Exception
    {
        Path database=directory.resolve("sdrtrunk.sqlite");
        assertThrows(IOException.class,() -> DatabaseFileInstaller.install(database,
            SdrTrunkDatabaseStartup::createGlobalDatabase,() -> { throw new IOException("injected main move failure"); }));
        assertFalse(Files.exists(database));
        assertFalse(Files.exists(ActivityDatabasePath.forConfiguration(database)));
        try(var files=Files.list(directory))
        { assertFalse(files.anyMatch(path -> path.getFileName().toString().contains(".creating-"))); }
    }
}
