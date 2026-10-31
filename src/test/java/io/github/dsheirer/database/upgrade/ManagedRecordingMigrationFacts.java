/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

/** Old-format preservation snapshots also require every prospective quality measurement to remain unknown. */
final class ManagedRecordingMigrationFacts
{
    static final Set<String> QUALITY_COLUMNS = Set.of("voice_decoded_frames", "voice_repeated_frames",
        "voice_concealed_frames", "voice_missing_frames", "voice_fec_errors", "voice_fec_protected_bits");

    private ManagedRecordingMigrationFacts() {}

    static boolean isUnmeasuredQualityColumn(String table, ResultSet row, int column) throws SQLException
    {
        if(!"recording_call".equals(table) || !QUALITY_COLUMNS.contains(row.getMetaData().getColumnName(column)))
            return false;
        assertNull(row.getObject(column), "A historical recording cannot acquire an inferred quality measurement");
        return true;
    }
}
