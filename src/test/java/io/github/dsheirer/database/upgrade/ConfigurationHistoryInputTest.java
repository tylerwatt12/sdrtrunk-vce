/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationHistoryInputTest
{
    @TempDir Path directory;

    @Test void activityCompanionCannotBeSelectedAsAConfigurationImport() throws Exception
    {
        Path configuration=directory.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(configuration);
        Path history=ActivityDatabasePath.forConfiguration(configuration);
        byte[] before=Files.readAllBytes(history);
        IOException plan=assertThrows(IOException.class,() -> ApplicationMigrationService.readMigrationPlan(history));
        assertTrue(plan.getMessage().contains("Select the configuration database"));
        IOException approval=assertThrows(IOException.class,() -> ApplicationMigrationService.readMigrationApproval(history,directory));
        assertTrue(approval.getMessage().contains("cannot be imported on its own"));
        assertArrayEquals(before,Files.readAllBytes(history));
        try(var files=Files.list(directory))
        { assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".sdrtrunk-migration-approval-"))); }
    }
}
