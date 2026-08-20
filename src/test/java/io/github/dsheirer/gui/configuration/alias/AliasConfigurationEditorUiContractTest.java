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
        assertTrue(update.contains("mAliasTableView.refresh()"));
        assertFalse(source.contains("mAliasPredicate"));
        assertFalse(source.contains("setAliasListName("));
        assertFalse(source.contains("setSearchText("));
    }

    @Test
    void newAliasRemainsADraftUntilTheItemEditorSavesIt() throws Exception
    {
        String source = Files.readString(EDITOR);
        String newAction = section(source, "private Button getNewAliasButton()",
            "private Button getDeleteAliasButton()");
        String draftPresentation = section(source, "private void showNewAliasDraft(Alias draft)",
            "private void aliasSaved(long aliasId)");
        String durableSelection = section(source, "private void selectAliasesById(List<Long> aliasIds)",
            "private List<Alias> findAliases");

        assertTrue(newAction.contains("if(isNewAliasDraft(getAliasItemEditor().getItem()))"));
        assertTrue(newAction.contains("showNewAliasDraft(alias)"));
        assertFalse(newAction.contains("createAlias("));
        assertTrue(draftPresentation.contains("getAliasTableView().getSelectionModel().clearSelection()"));
        assertTrue(draftPresentation.contains("getAliasItemEditor().setItem(draft)"));
        assertTrue(durableSelection.contains("getAliasTableView().refresh()"));
    }

    private static String section(String source, String start, String end)
    {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex);
        return source.substring(startIndex, endIndex);
    }
}
