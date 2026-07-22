/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.whatsnew;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReleaseNotesTest
{
    @Test
    void loadsVersionedRichTextDocument()
    {
        ReleaseNotes notes = ReleaseNotes.load("0.6.2-alpha-6").orElseThrow();

        assertEquals("0.6.2-alpha-6", notes.version());
        assertEquals("sdrtrunk-vce 0.6.2 Alpha 6", notes.title());
        assertTrue(notes.html().contains("<h1>What&rsquo;s New in Alpha 6</h1>"));
    }

    @Test
    void excludesDevelopmentBuildNames()
    {
        assertFalse(ReleaseNotes.isPublicVersion(null));
        assertFalse(ReleaseNotes.isPublicVersion("nightly"));
        assertFalse(ReleaseNotes.isPublicVersion("0.6.2-SNAPSHOT"));
        assertFalse(ReleaseNotes.isPublicVersion("local-dev"));
        assertTrue(ReleaseNotes.isPublicVersion("0.6.2-alpha-6"));
    }

    @Test
    void showsOnlyWhenVersionChanges()
    {
        assertTrue(ReleaseNotes.shouldShow("0.6.2-alpha-6", null));
        assertTrue(ReleaseNotes.shouldShow("0.6.2-alpha-6", "0.6.2-alpha-5"));
        assertFalse(ReleaseNotes.shouldShow("0.6.2-alpha-6", "0.6.2-alpha-6"));
    }
}
