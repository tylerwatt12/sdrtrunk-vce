/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui.configuration.alias;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Guards the create-on-Save lifecycle for a desktop Alias draft. */
class AliasItemEditorDraftUiContractTest
{
    private static final Path EDITOR =
        Path.of("src/main/java/io/github/dsheirer/gui/configuration/alias/AliasItemEditor.java");

    @Test
    void unsavedAliasEnablesSaveAndCreatesExactlyOnce() throws Exception
    {
        String source = Files.readString(EDITOR);
        String load = section(source, "public void setItem(Alias alias)",
            "private void refreshMatcherChoices(Alias alias)");
        String save = section(source, "boolean save(boolean reselectSavedAlias)", "public void dispose()");
        String discard = section(source, "void discardChanges()", "void focusAliasName()");

        assertTrue(load.contains("modifiedProperty().set(alias != null && alias.getId() == Alias.UNASSIGNED_ID)"));
        assertTrue(save.contains("boolean create = alias.getId() == Alias.UNASSIGNED_ID"));
        assertTrue(save.contains("createAlias(replacement, mLoadedRevision)"));
        assertTrue(save.contains("replaceAlias(alias.getId(), replacement, mLoadedRevision)"));
        assertTrue(discard.contains("setItem(null)"));
    }

    private static String section(String source, String start, String end)
    {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex);
        return source.substring(startIndex, endIndex);
    }
}
