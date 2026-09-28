/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.source.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.source.SourceType;
import org.junit.jupiter.api.Test;

class SourceConfigRemoteTest
{
    @Test
    void canonicalizesStableRoutingIdsAndRejectsOtherIdentifiers()
    {
        SourceConfigRemote source = new SourceConfigRemote();
        source.setSenderId(" AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE ");
        source.setFeedId("11111111-2222-4333-8444-555555555555");

        assertEquals("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", source.getSenderId());
        assertEquals("11111111-2222-4333-8444-555555555555", source.getFeedId());
        assertThrows(IllegalArgumentException.class, () -> source.setSenderId("sender-name"));
        assertThrows(IllegalArgumentException.class, () -> source.setFeedId("stream-12"));
    }

    @Test
    void polymorphicJsonAndFactoryCopyPreserveOnlySavedRoutingIdentity() throws Exception
    {
        SourceConfigRemote source = new SourceConfigRemote();
        source.setSenderId("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
        source.setFeedId("11111111-2222-4333-8444-555555555555");
        source.setFrequency(851_012_500L);

        ObjectMapper mapper = new ObjectMapper();
        SourceConfiguration restored = mapper.readValue(mapper.writeValueAsString(source),
            SourceConfiguration.class);
        SourceConfigRemote remote = assertInstanceOf(SourceConfigRemote.class, restored);
        assertEquals(source.getSenderId(), remote.getSenderId());
        assertEquals(source.getFeedId(), remote.getFeedId());
        assertEquals(source.getFrequency(), remote.getFrequency());

        SourceConfigRemote copy = assertInstanceOf(SourceConfigRemote.class, SourceConfigFactory.copy(source));
        assertNotSame(source, copy);
        assertEquals(source.getSenderId(), copy.getSenderId());
        assertEquals(source.getFeedId(), copy.getFeedId());
        assertEquals(source.getFrequency(), copy.getFrequency());
        assertInstanceOf(SourceConfigRemote.class, SourceConfigFactory.getSourceConfiguration(SourceType.REMOTE));
    }
}
