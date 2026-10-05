/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SdrTrunkDatabaseBootstrapUpgradeOptionsTest
{
    @Test
    void upgradesKeepARecoveryBackupByDefault()
    {
        assertTrue(SdrTrunkDatabaseBootstrap.Options.parse(new String[]{"--upgrade-current"})
            .createUpgradeBackup());
        assertTrue(SdrTrunkDatabaseBootstrap.Options.parse(new String[]{"--upgrade-managed-recordings"})
            .createUpgradeBackup());
    }

    @Test
    void eitherExplicitUpgradeCanDisableItsBackup()
    {
        for(String flag: new String[]{"--upgrade-current", "--upgrade-managed-recordings"})
            assertFalse(SdrTrunkDatabaseBootstrap.Options.parse(new String[]{flag, "--no-upgrade-backup"})
                .createUpgradeBackup());
    }

    @Test
    void backupOptOutCannotSilentlyApplyToARegularLaunchOrImport()
    {
        assertThrows(IllegalArgumentException.class,
            () -> SdrTrunkDatabaseBootstrap.Options.parse(new String[]{"--no-upgrade-backup"}));
        assertThrows(IllegalArgumentException.class,
            () -> SdrTrunkDatabaseBootstrap.Options.parse(new String[]{"--fresh", "--no-upgrade-backup"}));
        assertThrows(IllegalArgumentException.class,
            () -> SdrTrunkDatabaseBootstrap.Options.parse(new String[]{"--upgrade-data", "previous", "--no-upgrade-backup"}));
    }
}
