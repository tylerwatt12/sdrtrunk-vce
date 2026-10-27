/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.portable.PortableDataRootLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class VceUpgradeSettingsReportTest
{
    @TempDir Path temporary;

    @Test
    void completionExplicitlyDisclosesExcludedHistoryWithoutInventingASourceRowCount() throws Exception
    {
        Path source = Files.createDirectories(temporary.resolve("old/data"));
        Path target = Files.createDirectories(temporary.resolve("new")).resolve("data");
        Path database = Format62TestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(source));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var query = connection.createStatement();
            var rows = query.executeQuery("SELECT count(*) FROM receiver_activity_event"))
        {
            assertTrue(rows.next());
            assertTrue(rows.getLong(1) > 0);
        }

        var result = VceUpgradeService.upgrade(source, target, MigrationContent.CONFIGURATION_ONLY, true, null, null);

        try(PortableDataRootLock ignored = result.lock())
        {
            String report = result.migration().helperOutput();
            assertTrue(report.contains("RESET saved Activity and statistics: excluded by your settings-only choice."), report);
            assertTrue(report.contains("Source history was not copied, counted or scanned."), report);
        }
    }
}
