/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.bugreport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BugReportHardwareCollectorTest
{
    @TempDir
    Path mTemporaryDirectory;

    @Test
    void createsStructuredInventoryWithoutDiskSerialNumber()
    {
        Map<String,Object> hardware = new BugReportHardwareCollector(mTemporaryDirectory).collect();

        assertNotNull(hardware.get("operating_system"));
        assertNotNull(hardware.get("cpu"));
        assertNotNull(hardware.get("memory"));
        assertNotNull(hardware.get("data_storage"));

        Map<?,?> storage = (Map<?,?>)hardware.get("data_storage");
        assertTrue(((Number)storage.get("total_bytes")).longValue() > 0);
        assertTrue(((Number)storage.get("used_bytes")).longValue() >= 0);
        Map<?,?> disk = (Map<?,?>)storage.get("physical_disk");
        assertFalse(disk.containsKey("serial"));
        assertFalse(disk.containsKey("serial_number"));
    }
}
