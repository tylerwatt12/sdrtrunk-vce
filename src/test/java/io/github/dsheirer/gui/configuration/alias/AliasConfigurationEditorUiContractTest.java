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

/** Guards the selection-clearing portion of the desktop alias editor lifecycle. */
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
        String refresh = section(source, "private void scheduleAliasSelectionRefresh()",
            "private FilteredList<Alias> getAliasFilteredList()");
        String selection = section(source, "private void setAliases()", "private AliasItemEditor getAliasItemEditor()");

        assertTrue(listener.contains("getAliasTableView().getSelectionModel().clearSelection()"));
        assertTrue(listener.contains("scheduleAliasSelectionRefresh()"));
        assertTrue(update.contains("setPredicate(new AliasPredicate("));
        assertTrue(selection.indexOf("getAliasItemEditor().save()") <
            selection.indexOf("new ArrayList<>(getAliasTableView().getSelectionModel().getSelectedItems())"));
        assertTrue(refresh.contains("mAliasSelectionRefreshRequested = true"));
        assertTrue(refresh.contains("if(mAliasSelectionRefreshRequested)"));
        assertTrue(source.contains("prepareForAliasListRefresh()\n    {\n" +
            "        getAliasTableView().getSelectionModel().clearSelection();"));
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
