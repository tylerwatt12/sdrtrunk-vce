/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SdrTrunkDatabaseBootstrapUpgradeOptionsTest
{
    @Test
    void upgradesUseTheOperatorsManualBackupByDefault()
    {
        assertFalse(SdrTrunkDatabaseBootstrap.Options.parse(new String[]{"--upgrade-current"})
            .createUpgradeBackup());
        assertFalse(SdrTrunkDatabaseBootstrap.Options.parse(new String[]{"--upgrade-managed-recordings"})
            .createUpgradeBackup());
    }

    @Test
    void legacyBackupOptOutIsANoOpAndDoesNotConfirmAManualBackup()
    {
        for(String flag: new String[]{"--upgrade-current", "--upgrade-managed-recordings"})
        {
            var options = SdrTrunkDatabaseBootstrap.Options.parse(new String[]{flag, "--no-upgrade-backup"});
            assertFalse(options.createUpgradeBackup());
            assertFalse(options.backupConfirmed());
            var confirmed = SdrTrunkDatabaseBootstrap.Options.parse(new String[]{flag, "--backup-confirmed"});
            assertTrue(confirmed.backupConfirmed());
            assertFalse(confirmed.createUpgradeBackup());
        }
    }

    @Test
    void retiredSqliteImportFlagIsRefusedInsteadOfStartingNormally()
    {
        for(String[] args: new String[][]{{"--import-sqlite"}, {"--setup-wizard", "--import-sqlite"}})
        {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> SdrTrunkDatabaseBootstrap.Options.parse(args));
            assertTrue(refusal.getMessage().contains("SQLite file import is no longer available"));
            assertTrue(refusal.getMessage().contains("previous installation folder or XML playlist"));
        }
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
        assertThrows(IllegalArgumentException.class,
            () -> SdrTrunkDatabaseBootstrap.Options.parse(new String[]{"--backup-confirmed"}));
        assertThrows(IllegalArgumentException.class,
            () -> SdrTrunkDatabaseBootstrap.Options.parse(new String[]{"--upgrade-data", "previous", "--backup-confirmed"}));
    }
}
