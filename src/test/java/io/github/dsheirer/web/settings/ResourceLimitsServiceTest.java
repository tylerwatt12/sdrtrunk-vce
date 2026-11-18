/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.web.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceLimitsServiceTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void savesHeapAndReportsRestartRequired(@TempDir Path directory) throws IOException
    {
        Path configuration = directory.resolve("conf/resources.properties");
        ResourceLimitsService service = new ResourceLimitsService(configuration);

        Map<String,Object> updated = service.update(MAPPER.readTree("{\"maxHeapMb\":6144}"));

        assertTrue(Files.exists(configuration));
        assertEquals(6144, service.requestedHeapMb());
        assertEquals(Boolean.TRUE, updated.get("restartRequired"));
        assertNotNull(updated.get("restartReason"));
    }

    @Test
    void rejectsOutOfRangeHeap(@TempDir Path directory)
    {
        ResourceLimitsService service = new ResourceLimitsService(directory.resolve("resources.properties"));

        assertThrows(IllegalArgumentException.class,
            () -> service.update(MAPPER.readTree("{\"maxHeapMb\":64}")));
        assertThrows(IllegalArgumentException.class,
            () -> service.update(MAPPER.readTree("{\"maxHeapMb\":131072}")));
    }

    @Test
    void rejectsOutOfRangeNiceAndNonInteger(@TempDir Path directory)
    {
        ResourceLimitsService service = new ResourceLimitsService(directory.resolve("resources.properties"));

        assertThrows(IllegalArgumentException.class,
            () -> service.update(MAPPER.readTree("{\"nice\":25}")));
        assertThrows(IllegalArgumentException.class,
            () -> service.update(MAPPER.readTree("{\"nice\":\"high\"}")));
    }

    @Test
    void leavesUnspecifiedFieldsUnchanged(@TempDir Path directory) throws IOException
    {
        ResourceLimitsService service = new ResourceLimitsService(directory.resolve("resources.properties"));

        service.update(MAPPER.readTree("{\"maxHeapMb\":4096,\"cpuQuotaPercent\":250}"));
        service.update(MAPPER.readTree("{\"cpuQuotaPercent\":150}"));

        Map<String,Object> snapshot = service.snapshot();
        @SuppressWarnings("unchecked")
        Map<String,Object> limits = (Map<String,Object>) snapshot.get("limits");

        assertEquals(4096, limits.get("maxHeapMb"));
        assertEquals(150, limits.get("cpuQuotaPercent"));
    }

    @Test
    void snapshotWithoutSavedLimitsDoesNotRequireRestart(@TempDir Path directory)
    {
        ResourceLimitsService service = new ResourceLimitsService(directory.resolve("resources.properties"));

        Map<String,Object> snapshot = service.snapshot();

        assertFalse((Boolean) snapshot.get("restartRequired"));
        assertNotNull(snapshot.get("live"));
    }
}
