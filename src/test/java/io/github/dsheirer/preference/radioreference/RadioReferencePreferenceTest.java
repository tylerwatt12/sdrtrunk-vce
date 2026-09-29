/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.preference.radioreference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.preference.radioreference.RadioReferencePreference.Bookmark;
import io.github.dsheirer.preference.radioreference.RadioReferencePreference.BookmarkKind;
import io.github.dsheirer.preference.radioreference.RadioReferencePreference.PreferredAliasList;
import java.util.List;
import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;

class RadioReferencePreferenceTest
{
    @Test
    void retainsPerSystemChoiceWithoutBookmarkAndMigratesLegacyChoices() throws Exception
    {
        Preferences node = Preferences.userRoot().node(
            "/sdrtrunk-vce-tests/radioreference-" + UUID.randomUUID());
        try
        {
            RadioReferencePreference preference = new RadioReferencePreference(null, node);
            Bookmark system = new Bookmark(BookmarkKind.TRUNKED_SYSTEM, 100, 0, "", "System", "", 12L);
            Bookmark category = new Bookmark(BookmarkKind.TALKGROUP_CATEGORY, 55, 100,
                "TRUNKED_SYSTEM", "Category", "System", 13L);
            Bookmark categoryOnly = new Bookmark(BookmarkKind.TALKGROUP_CATEGORY, 66, 200,
                "TRUNKED_SYSTEM", "Other category", "Other system", 44L);
            preference.saveBookmark(system);
            preference.saveBookmark(category);
            preference.saveBookmark(categoryOnly);

            assertEquals(List.of(new PreferredAliasList(100, 12L), new PreferredAliasList(200, 44L)),
                preference.getPreferredAliasLists());
            preference.removeBookmark(system);
            preference.removeBookmark(category);
            preference.removeBookmark(categoryOnly);
            preference.savePreferredAliasList(100, 99);
            preference.savePreferredAliasList(300, 77);

            RadioReferencePreference reopened = new RadioReferencePreference(null, node);
            assertEquals(List.of(new PreferredAliasList(100, 99L), new PreferredAliasList(200, 44L),
                new PreferredAliasList(300, 77L)), reopened.getPreferredAliasLists());
            assertEquals(List.of(), reopened.getBookmarks());
            assertThrows(IllegalArgumentException.class, () -> reopened.savePreferredAliasList(0, 77));
            assertThrows(IllegalArgumentException.class, () -> reopened.savePreferredAliasList(300, 0));
        }
        finally
        {
            node.removeNode();
        }
    }

    @Test
    void explicitClearRemovesSavedNodeAndDoesNotRestoreBookmarkChoice() throws Exception
    {
        Preferences node = Preferences.userRoot().node(
            "/sdrtrunk-vce-tests/radioreference-clear-" + UUID.randomUUID());
        try
        {
            RadioReferencePreference preference = new RadioReferencePreference(null, node);
            Bookmark bookmark = new Bookmark(BookmarkKind.TRUNKED_SYSTEM, 100, 0, "", "System", "", 12L);
            preference.saveBookmark(bookmark);
            assertEquals(List.of(new PreferredAliasList(100, 12L)), preference.getPreferredAliasLists());
            assertEquals(new PreferredAliasList(100, null), preference.clearPreferredAliasList(100));
            assertFalse(node.node("preferred_alias_lists").nodeExists("100"));

            RadioReferencePreference reopened = new RadioReferencePreference(null, node);
            assertEquals(List.of(new PreferredAliasList(100, null)), reopened.getPreferredAliasLists());
            assertEquals(List.of(bookmark), reopened.getBookmarks());

            reopened.savePreferredAliasList(100, 99);
            assertEquals(List.of(new PreferredAliasList(100, 99L)),
                new RadioReferencePreference(null, node).getPreferredAliasLists());
            assertFalse(node.node("cleared_preferred_alias_lists").nodeExists("100"));
        }
        finally
        {
            node.removeNode();
        }
    }
}
