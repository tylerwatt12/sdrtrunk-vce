/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.application.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.Test;

class UpdateManifestTest
{
    private static final String RELEASE_URL = "https://github.com/tylerwatt12/sdrtrunk-vce/releases/tag/test";

    @Test
    void parsesMinimalManifest() throws Exception
    {
        UpdateManifest manifest = UpdateManifest.parse("build=12\nversion=webfirst-12\nurl=" + RELEASE_URL);
        assertEquals(12, manifest.build());
        assertEquals("webfirst-12", manifest.version());
        assertEquals(URI.create(RELEASE_URL), manifest.releaseUri());
    }

    @Test
    void rejectsMissingAndUnsafeValues()
    {
        assertThrows(IOException.class, () -> UpdateManifest.parse("version=test\nurl=" + RELEASE_URL));
        assertThrows(IOException.class, () ->
            UpdateManifest.parse("build=-1\nversion=test\nurl=" + RELEASE_URL));
        assertThrows(IOException.class, () ->
            UpdateManifest.parse("build=2\nversion=test\nurl=https://example.com/releases/test"));
        assertThrows(IOException.class, () ->
            UpdateManifest.parse("build=2\nversion=test\nurl=" +
                "https://github.com/tylerwatt12/sdrtrunk-vce/releases-elsewhere/test"));
    }

    @Test
    void comparesOnlyNumericBuildWithinTrack() throws Exception
    {
        UpdateManifest newer = UpdateManifest.parse("build=3\nversion=next\nurl=" + RELEASE_URL);
        UpdateCheckResult available = UpdateCheckService.evaluate("webfirst", 2, newer);
        assertTrue(available.isUpdateAvailable());
        assertEquals("webfirst", available.track());

        assertEquals(UpdateCheckResult.State.CURRENT,
            UpdateCheckService.evaluate("webfirst", 3, newer).state());
        assertEquals(UpdateCheckResult.State.CURRENT,
            UpdateCheckService.evaluate("webfirst", 4, newer).state());
    }

    @Test
    void usesFixedManifestLocationForEachSupportedTrack()
    {
        assertEquals("https://raw.githubusercontent.com/tylerwatt12/sdrtrunk-vce/main/.github/update.properties",
            UpdateCheckService.manifestUri("main").toString());
        assertEquals("https://raw.githubusercontent.com/tylerwatt12/sdrtrunk-vce/webfirst/.github/update.properties",
            UpdateCheckService.manifestUri("webfirst").toString());
        assertThrows(IllegalArgumentException.class, () -> UpdateCheckService.manifestUri("experimental"));
    }
}
