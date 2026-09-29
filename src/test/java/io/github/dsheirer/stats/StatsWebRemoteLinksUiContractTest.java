/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Protects automatic Remote Links setup, one-time credentials, and remote provenance in Live. */
class StatsWebRemoteLinksUiContractTest
{
    private static final Path APP = Path.of("stats-web", "assets", "app.js");
    private static final Path FEATURE = Path.of("stats-web", "assets", "features", "remote-links.js");

    @Test
    void providesBothHostAndSenderWorkflowsWithoutReadingBackCredentials() throws Exception
    {
        String app = Files.readString(APP);
        String feature = Files.readString(FEATURE);

        assertTrue(app.contains("id: 'remote-links', label: 'Remote Links'"));
        assertTrue(app.contains("createRemoteLinksWorkspace"));
        assertTrue(feature.contains("const ROOT = '/api/v1/admin/remote-links'"));
        assertTrue(feature.contains("'Receive remote feeds'"));
        assertTrue(feature.contains("'Send local P25 feeds'"));
        assertTrue(feature.contains("'Add trusted sender'"));
        assertTrue(feature.contains("'Setting up a local channel.'"));
        assertTrue(feature.contains("'Manage feed'"));
        assertTrue(feature.contains("'Preferred P25 Alias List'"));
        assertTrue(feature.contains("'Copy this credential now. The shared secret will not be shown again.'"));
        assertTrue(feature.contains("exported_channel_configuration_ids"));
        assertTrue(feature.contains("default_alias_list_id"));
        assertTrue(feature.contains("address.required = true; address.maxLength = 255"));
        assertTrue(feature.contains("destination.maxLength = 255"));
        assertTrue(feature.contains("senderId.maxLength = 36"));
        assertTrue(feature.contains("secret.maxLength = 256"));
        assertTrue(feature.contains("name.required = true; name.maxLength = 120"));
        assertFalse(feature.contains("/adopt"));
        assertFalse(feature.contains("auto_adopt"));
        assertFalse(feature.contains("feed.adopted"));
        assertFalse(feature.contains("Forget feed"));
        assertFalse(feature.contains("innerHTML"));
        assertFalse(feature.contains("style="));
    }

    @Test
    void marksRemoteLiveSystemsWhileKeepingDetailedHealthInTheAdminWorkspace() throws Exception
    {
        String app = Files.readString(APP);
        String feature = Files.readString(FEATURE);
        String index = Files.readString(Path.of("stats-web", "index.html"));

        assertTrue(index.contains("id=\"icon-cloud\""));
        assertTrue(app.contains("function liveRemoteOriginBadge(origin, showLabel = false)"));
        assertTrue(app.contains("row.remote_origin"));
        assertTrue(app.contains("value?.remote_origin"));
        assertTrue(app.contains("origin?.remote === true ? 'Remote source' : ''"));
        assertTrue(feature.contains("listener.dependencies"));
        assertTrue(feature.contains("sender.sender_id"));
        assertTrue(feature.contains("status(feed.state)"));
        assertFalse(app.contains("origin.sender_id"));
        assertFalse(app.contains("origin.sender_name"));
        assertFalse(app.contains("origin.feed_id"));
        assertFalse(app.contains("origin.feed_name"));
        assertFalse(app.contains("origin.state"));
        assertFalse(app.contains("origin.dependency_state"));
        assertFalse(app.contains("remote_origin.destination_host"));
        assertFalse(app.contains("remote_origin.secret"));
    }
}
