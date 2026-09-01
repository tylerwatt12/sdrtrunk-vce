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
        ReleaseNotes notes = ReleaseNotes.load("0.6.2-alpha-11").orElseThrow();

        assertEquals("0.6.2-alpha-11", notes.version());
        assertEquals("sdrtrunk-vce 0.6.2 Alpha 11", notes.title());
        assertTrue(notes.html().contains("<h1>What’s New in sdrtrunk-vce 0.6.2 Alpha 11</h1>"));
    }

    @Test
    void excludesDevelopmentBuildNames()
    {
        assertFalse(ReleaseNotes.isPublicVersion(null));
        assertFalse(ReleaseNotes.isPublicVersion("nightly"));
        assertFalse(ReleaseNotes.isPublicVersion("0.6.2-SNAPSHOT"));
        assertFalse(ReleaseNotes.isPublicVersion("local-dev"));
        assertTrue(ReleaseNotes.isPublicVersion("0.6.2-alpha-11"));
    }

    @Test
    void showsOnlyWhenVersionChanges()
    {
        assertTrue(ReleaseNotes.shouldShow("0.6.2-alpha-11", null));
        assertTrue(ReleaseNotes.shouldShow("0.6.2-alpha-11", "0.6.2-alpha-10"));
        assertFalse(ReleaseNotes.shouldShow("0.6.2-alpha-11", "0.6.2-alpha-11"));
    }
}
