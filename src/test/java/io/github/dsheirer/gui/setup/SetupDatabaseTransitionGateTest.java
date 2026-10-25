/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.gui.setup;

import io.github.dsheirer.database.ActivityDatabasePath;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupDatabaseTransitionGateTest
{
    @TempDir
    Path directory;

    @Test
    void missingConfigurationDuringActivationRemainsAnOwnedProfile() throws Exception
    {
        Path configuration = directory.resolve("sdrtrunk.sqlite");
        assertFalse(SetupWizard.hasExistingProfile(configuration));
        Files.writeString(ActivityDatabasePath.splitReceipt(configuration), "activation receipt");
        assertFalse(Files.exists(configuration));
        assertTrue(SetupWizard.hasPendingDatabaseTransition(configuration));
        assertTrue(SetupWizard.hasExistingProfile(configuration));
        Files.writeString(configuration, "committed configuration");
        assertTrue(SetupWizard.hasPendingDatabaseTransition(configuration));
        Files.delete(ActivityDatabasePath.splitReceipt(configuration));
        assertFalse(SetupWizard.hasPendingDatabaseTransition(configuration));
        assertTrue(SetupWizard.hasExistingProfile(configuration));
    }
}
