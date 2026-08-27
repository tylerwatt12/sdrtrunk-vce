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
        UpdateManifest manifest = UpdateManifest.parse(manifest("nightly", "12", "nightly-12"));
        assertEquals(2, manifest.format());
        assertEquals("nightly", manifest.track());
        assertEquals(12, manifest.build());
        assertEquals("nightly-12", manifest.version());
        assertEquals(URI.create(RELEASE_URL), manifest.releaseUri());
    }

    @Test
    void rejectsMissingAndUnsafeValues()
    {
        assertThrows(IOException.class, () ->
            UpdateManifest.parse("track=nightly\nbuild=2\nversion=test\nurl=" + RELEASE_URL));
        assertThrows(IOException.class, () ->
            UpdateManifest.parse("format=1\ntrack=nightly\nbuild=2\nversion=test\nurl=" + RELEASE_URL));
        assertThrows(IOException.class, () ->
            UpdateManifest.parse("format=2\nversion=test\nbuild=2\nurl=" + RELEASE_URL));
        assertThrows(IOException.class, () ->
            UpdateManifest.parse("format=2\ntrack=nightly\nversion=test\nurl=" + RELEASE_URL));
        assertThrows(IOException.class, () ->
            UpdateManifest.parse("format=2\ntrack=nightly\nbuild=-1\nversion=test\nurl=" + RELEASE_URL));
        assertThrows(IOException.class, () ->
            UpdateManifest.parse("format=2\ntrack=nightly\nbuild=2\nversion=test\n" +
                "url=https://example.com/releases/test"));
        assertThrows(IOException.class, () ->
            UpdateManifest.parse("format=2\ntrack=nightly\nbuild=2\nversion=test\nurl=" +
                "https://github.com/tylerwatt12/sdrtrunk-vce/releases-elsewhere/test"));
    }

    @Test
    void comparesOnlyNumericBuildWithinTrack() throws Exception
    {
        UpdateManifest newer = UpdateManifest.parse(manifest("alpha", "3", "next"));
        UpdateCheckResult available = UpdateCheckService.evaluate("alpha", 2, newer);
        assertTrue(available.isUpdateAvailable());
        assertEquals("alpha", available.track());

        assertEquals(UpdateCheckResult.State.CURRENT,
            UpdateCheckService.evaluate("alpha", 3, newer).state());
        assertEquals(UpdateCheckResult.State.CURRENT,
            UpdateCheckService.evaluate("alpha", 4, newer).state());
    }

    @Test
    void refusesManifestFromAnotherChannel() throws Exception
    {
        UpdateManifest alpha = UpdateManifest.parse(manifest("alpha", "99", "next"));
        assertEquals(UpdateCheckResult.State.UNAVAILABLE,
            UpdateCheckService.evaluate("nightly", 1, alpha).state());
    }

    @Test
    void supportsMonotonicBuildNumbersLargerThanAnInteger() throws Exception
    {
        UpdateManifest newer = UpdateManifest.parse(manifest("nightly", "9223372036854775806", "next"));
        assertTrue(UpdateCheckService.evaluate("nightly", 3_000_000_000L, newer).isUpdateAvailable());
    }

    @Test
    void usesFixedManifestLocationForEachSupportedTrack()
    {
        assertEquals("https://raw.githubusercontent.com/tylerwatt12/sdrtrunk-vce/" +
                "release/0.6.2-alpha/.github/update.properties",
            UpdateCheckService.manifestUri("alpha").toString());
        assertEquals("https://github.com/tylerwatt12/sdrtrunk-vce/releases/download/nightly/update.properties",
            UpdateCheckService.manifestUri("nightly").toString());
        assertThrows(IllegalArgumentException.class, () -> UpdateCheckService.manifestUri("main"));
        assertThrows(IllegalArgumentException.class, () -> UpdateCheckService.manifestUri("webfirst"));
        assertThrows(IllegalArgumentException.class, () -> UpdateCheckService.manifestUri("experimental"));
    }

    private static String manifest(String track, String build, String version)
    {
        return "format=2\ntrack=" + track + "\nbuild=" + build + "\nversion=" + version + "\nurl=" + RELEASE_URL;
    }
}
