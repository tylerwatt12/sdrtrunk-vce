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

class StatsWebDisplaySettingsUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");
    private static final Path APP_CSS = Path.of("stats-web", "assets", "app.css");

    @Test
    void webSettingsExposeOneReceiverWideEncryptionDetailsToggle() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String settings = function(source, "async function renderAdminWebSettings()");
        String display = function(source, "async function renderAdminWebDisplaySettings()");
        String admin = function(source, "async function renderAdmin()");

        assertTrue(settings.contains("await renderAdminWebDisplaySettings()"));
        assertTrue(settings.contains("await renderAdminRadioReferenceSettings()"));
        assertTrue(admin.contains("await renderAdminWebSettings()"));
        assertTrue(display.contains("'/api/v1/admin/web-display'"));
        assertTrue(display.contains("show_encryption_details"));
        assertTrue(display.contains("method: 'PUT'"));
        assertTrue(display.contains("toggle.checked = previous"));
        assertTrue(display.contains("serviceStatus.web_display = configuration"));
        assertTrue(display.contains("'Show encryption algorithm and key'"));
    }

    @Test
    void liveStatusUsesTheReceiverSettingWithoutRemovingEncryptionFromTheFeed() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String live = function(source, "function liveSystemsSection(onSelectionChange)");
        String service = Files.readString(Path.of("src", "main", "java", "io", "github", "dsheirer",
            "stats", "StatsWebServerService.java"));
        String liveService = Files.readString(Path.of("src", "main", "java", "io", "github", "dsheirer",
            "stats", "StatsLiveService.java"));

        assertTrue(live.contains("serviceStatus?.web_display?.show_encryption_details !== false"));
        assertTrue(live.contains("showEncryptionDetails && row.status === 'ENCRYPTED'"));
        assertTrue(service.contains("status.put(\"webDisplay\", mWebDisplaySettingsService.configuration())"));
        assertTrue(service.contains("WebCapability.ADMIN_SETTINGS, webDisplaySettingsController::handle"));
        assertTrue(liveService.contains("putText(row, \"encryption_details\", snapshot.encryptionDetails()"));
    }

    @Test
    void toggleHasAResponsiveReadableControl() throws Exception
    {
        String css = Files.readString(APP_CSS);
        assertTrue(css.contains(".admin-toggle-control"));
        assertTrue(css.contains(".admin-toggle-copy"));
        assertTrue(css.contains(".admin-toggle-control:has(input:disabled)"));
        assertTrue(css.contains("padding: 14px 12px"));
        assertTrue(css.contains("border-bottom: 1px solid var(--line)"));
        assertFalse(css.contains(".web-display-settings {\n  max-width:"));
    }

    private static String function(String source, String marker)
    {
        int start = source.indexOf(marker);
        assertTrue(start >= 0, marker);
        int opening = source.indexOf('{', start);
        int depth = 0;

        for(int index = opening; index < source.length(); index++)
        {
            char character = source.charAt(index);

            if(character == '{')
            {
                depth++;
            }
            else if(character == '}' && --depth == 0)
            {
                return source.substring(start, index + 1);
            }
        }

        throw new AssertionError("Unclosed function: " + marker);
    }
}
