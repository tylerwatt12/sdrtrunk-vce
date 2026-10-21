/* Copyright (C) 2026 Dennis Sheirer */
package io.github.dsheirer.audio.broadcast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallConfiguration;
import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallSiteConfiguration;
import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyFeedConfiguration;
import io.github.dsheirer.audio.broadcast.icecast.IcecastHTTPConfiguration;
import io.github.dsheirer.audio.broadcast.icecast.IcecastTCPConfiguration;
import io.github.dsheirer.audio.broadcast.openmhz.OpenMHzConfiguration;
import io.github.dsheirer.audio.broadcast.radioresolve.RadioResolveConfiguration;
import io.github.dsheirer.audio.broadcast.rdioscanner.RdioScannerConfiguration;
import io.github.dsheirer.audio.broadcast.shoutcast.v1.ShoutcastV1Configuration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BroadcastConfigurationValidityTest
{
    @Test
    void streamingProviderChecksRetainTheirExistingNullAndPortRules()
    {
        for(BroadcastConfiguration configuration: List.of(new ShoutcastV1Configuration(),
            new IcecastTCPConfiguration(), new BroadcastifyFeedConfiguration(), new IcecastHTTPConfiguration()))
        {
            assertFalse(configuration.isValid());
            configuration.setHost("");
            assertTrue(configuration.isValid(), "Existing streaming validity allows a non-null empty host");
            configuration.setPort(0);
            assertFalse(configuration.isValid());
            configuration.setPort(1);
            assertTrue(configuration.isValid());
        }
        var http = new IcecastHTTPConfiguration();
        http.setHost("example.invalid");
        http.setPassword(null);
        assertFalse(http.isValid());
        http.setPassword("");
        assertTrue(http.isValid());
        http.setMountPoint("audio");
        assertEquals("/audio", http.getMountPoint());
    }

    @Test
    void uploadProviderChecksRetainTheirExistingNullEmptyAndIdentityRules()
    {
        var calls = new BroadcastifyCallConfiguration();
        assertFalse(calls.isValid());
        calls.setSystemID(1);
        calls.setApiKey("");
        assertTrue(calls.isValid());
        calls.setApiKey(null);
        assertFalse(calls.isValid());

        var site = new BroadcastifyCallSiteConfiguration();
        site.setSystemID(1);
        site.setApiKey("");
        site.setAliasListId(1);
        assertFalse(site.isValid());
        site.setChannelConfigurationId(UUID.randomUUID().toString());
        assertTrue(site.isValid());
        site.setChannelConfigurationId("malformed");
        assertFalse(site.isValid());

        var rdio = new RdioScannerConfiguration();
        rdio.setSystemID(1);
        rdio.setApiKey("");
        assertTrue(rdio.isValid());
        rdio.setSystemID(0);
        assertFalse(rdio.isValid());

        var openMhz = new OpenMHzConfiguration();
        openMhz.setHost("");
        openMhz.setSystemName("");
        openMhz.setApiKey("");
        assertTrue(openMhz.isValid());
        openMhz.setSystemName(null);
        assertFalse(openMhz.isValid());

        var radioResolve = new RadioResolveConfiguration();
        radioResolve.setApiKey("");
        assertFalse(radioResolve.isValid());
        radioResolve.setApiKey(" ");
        assertTrue(radioResolve.isValid());
        radioResolve.setHost("");
        assertFalse(radioResolve.isValid());
    }

    @Test
    void everyProviderKeepsItsStoredJsonShapeAndValues() throws Exception
    {
        var mapper = new ObjectMapper();
        for(var provider: StreamingConfigurationCodec.providers())
        {
            BroadcastConfiguration original = StreamingConfigurationCodec.template(provider.id());
            original.setName("Fixture");
            original.setEnabled(false);
            original.setDelay(123L);
            original.setMaximumRecordingAge(456L);
            //ConfigurationDatabaseStore writes using the declared base type's canonical subtype names.
            String json = mapper.writerFor(BroadcastConfiguration.class).writeValueAsString(original);
            BroadcastConfiguration loaded = mapper.readValue(json, BroadcastConfiguration.class);
            assertEquals(mapper.readTree(json),
                mapper.readTree(mapper.writerFor(BroadcastConfiguration.class).writeValueAsString(loaded)), provider.id());
            assertEquals(original.isValid(), loaded.isValid(), provider.id());
            assertEquals(original.getConfigurationId(), loaded.getConfigurationId());
            assertFalse(json.contains("changeListeners"));
            assertFalse(json.contains("stateSnapshot"));
        }
    }
}
