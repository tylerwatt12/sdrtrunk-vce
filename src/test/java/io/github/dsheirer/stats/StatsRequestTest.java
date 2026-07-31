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
            "/api/talkgroup?scope=p25:BEE00:348&talkgroup_id=0x348" +
                "&limit=900&offset=-2&q=county%20system"));

        assertEquals("p25:BEE00:348", request.requiredText("scope"));
        assertEquals(0x348, request.requiredIdentifier("talkgroup_id"));
        assertEquals(StatsRequest.MAX_LIMIT, request.limit());
        assertEquals(0, request.offset());
        assertEquals("county system", request.search());

        StatsRequest largeOffset = StatsRequest.from(URI.create("/api/system/talkgroups?offset=2147483647"));
        assertEquals(StatsRequest.MAX_OFFSET, largeOffset.offset());
    }

    @Test
    void rejectsInvalidRequiredIdentifiers()
    {
        StatsRequest request = StatsRequest.from(URI.create("/api/talkgroup?talkgroup_id=not-an-id"));
        assertThrows(StatsApiException.class, () -> request.requiredText("scope"));
        assertThrows(StatsApiException.class, () -> request.requiredIdentifier("talkgroup_id"));
    }
}
