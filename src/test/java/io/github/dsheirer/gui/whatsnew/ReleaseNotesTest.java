/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.whatsnew;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ReleaseNotesTest
{
    private static final Path RELEASE_NOTES = Path.of("src/main/resources/release-notes");

    @Test
    void loadsEveryVersionedRichTextDocument() throws IOException, NoSuchAlgorithmException
    {
        List<String> versions;

        try(var files = Files.list(RELEASE_NOTES))
        {
            versions = files.filter(path -> path.getFileName().toString().endsWith(".properties"))
                .map(path -> path.getFileName().toString().replaceFirst("\\.properties$", ""))
                .sorted()
                .toList();
        }

        assertFalse(versions.isEmpty());

        for(String version: versions)
        {
            ReleaseNotes notes = ReleaseNotes.load(version).orElseThrow();
            assertEquals(version, notes.version());
            assertFalse(notes.title().isBlank());
            assertTrue(notes.html().contains("<h1>"));

            Properties metadata = new Properties();

            try(var reader = Files.newBufferedReader(RELEASE_NOTES.resolve(version + ".properties")))
            {
                metadata.load(reader);
            }

            if("approved".equalsIgnoreCase(metadata.getProperty("status", "").trim()))
            {
                String actualHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(RELEASE_NOTES.resolve(version + ".html"))));
                assertEquals(metadata.getProperty("approved-sha256"), actualHash);
            }
        }
    }

    @Test
    void excludesDevelopmentBuildNames()
    {
        assertFalse(ReleaseNotes.isPublicVersion(null));
        assertFalse(ReleaseNotes.isPublicVersion("nightly"));
        assertFalse(ReleaseNotes.isPublicVersion("0.6.2-SNAPSHOT"));
        assertFalse(ReleaseNotes.isPublicVersion("local-dev"));
        assertTrue(ReleaseNotes.isPublicVersion("0.6.2-alpha-99"));
    }

    @Test
    void showsOnlyWhenVersionChanges()
    {
        assertTrue(ReleaseNotes.shouldShow("0.6.2-alpha-99", null));
        assertTrue(ReleaseNotes.shouldShow("0.6.2-alpha-99", "0.6.2-alpha-98"));
        assertFalse(ReleaseNotes.shouldShow("0.6.2-alpha-99", "0.6.2-alpha-99"));
    }
}
