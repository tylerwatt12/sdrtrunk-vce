/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

class StatsRequestTest
{
    @Test
    void parsesHexIdentifiersAndBoundsPaging()
    {
        StatsRequest request = StatsRequest.from(URI.create(
            "/api/system?wacn=BEE00&system_id=0x348&limit=900&offset=-2&q=county%20system"));

        assertEquals(0xBEE00, request.requiredIdentifier("wacn"));
        assertEquals(0x348, request.requiredIdentifier("system_id"));
        assertEquals(StatsRequest.MAX_LIMIT, request.limit());
        assertEquals(0, request.offset());
        assertEquals("county system", request.search());
    }

    @Test
    void rejectsInvalidRequiredIdentifiers()
    {
        StatsRequest request = StatsRequest.from(URI.create("/api/system?wacn=not-an-id"));
        assertThrows(StatsApiException.class, () -> request.requiredIdentifier("wacn"));
        assertThrows(StatsApiException.class, () -> request.requiredIdentifier("system_id"));
    }
}
