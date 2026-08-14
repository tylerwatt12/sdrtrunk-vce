/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui.configuration.alias;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Guards the selection-clearing portion of the desktop Alias editor lifecycle. */
class AliasConfigurationEditorUiContractTest
{
    private static final Path EDITOR =
        Path.of("src/main/java/io/github/dsheirer/gui/configuration/alias/AliasConfigurationEditor.java");

    @Test
    void listSwitchInstallsFreshFilterAndClearsTheSelectedEditor() throws Exception
    {
        String source = Files.readString(EDITOR);
        String listener = section(source, "selectedItemProperty()", "private Button getNewAliasListButton()");
        String update = section(source, "private void update()", "private AliasListDefinition getAliasListDefinition");
        String selectionRefresh = section(source, "private void setAliases()", "private boolean resolveModifiedAliasDraft()");

        assertTrue(listener.contains("getAliasTableView().getSelectionModel().clearSelection()"));
        assertTrue(listener.contains("scheduleAliasSelectionRefresh()"));
        assertTrue(selectionRefresh.contains("getAliasItemEditor().setItem(null)"));
        assertTrue(update.contains("setPredicate(new AliasPredicate("));
        assertFalse(source.contains("mAliasPredicate"));
        assertFalse(source.contains("setAliasListName("));
        assertFalse(source.contains("setSearchText("));
    }

    private static String section(String source, String start, String end)
    {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex);
        return source.substring(startIndex, endIndex);
    }
}
