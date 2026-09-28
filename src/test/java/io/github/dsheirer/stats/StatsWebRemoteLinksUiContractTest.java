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

/** Protects Remote Links discovery, one-time credentials, and remote provenance in Live. */
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
        assertTrue(feature.contains("'Adopt remote feed'"));
        assertTrue(feature.contains(
            "'Disconnected and removed advertisements remain visible. Removing a managed feed deletes only '"));
        assertTrue(feature.contains("'its local channel; the sender may advertise it again.'"));
        assertTrue(feature.contains("'Copy this credential now. The shared secret will not be shown again.'"));
        assertTrue(feature.contains("exported_channel_configuration_ids"));
        assertTrue(feature.contains("default_alias_list_id"));
        assertFalse(feature.contains("innerHTML"));
        assertFalse(feature.contains("style="));
    }

    @Test
    void marksRemoteLiveSystemsAndSurfacesDependencyHealth() throws Exception
    {
        String app = Files.readString(APP);
        String feature = Files.readString(FEATURE);
        String index = Files.readString(Path.of("stats-web", "index.html"));

        assertTrue(index.contains("id=\"icon-cloud\""));
        assertTrue(app.contains("function liveRemoteOriginBadge(origin, showLabel = false)"));
        assertTrue(app.contains("row.remote_origin"));
        assertTrue(app.contains("value?.remote_origin"));
        assertTrue(app.contains("origin.dependency_state"));
        assertTrue(feature.contains("listener.dependencies"));
        assertFalse(app.contains("remote_origin.destination_host"));
        assertFalse(app.contains("remote_origin.secret"));
    }
}
