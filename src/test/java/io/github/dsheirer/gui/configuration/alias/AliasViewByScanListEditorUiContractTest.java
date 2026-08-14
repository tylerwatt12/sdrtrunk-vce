/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.configuration.alias;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AliasViewByScanListEditorUiContractTest
{
    @Test
    void aliasEditorExposesTheScanListTransferTab() throws Exception
    {
        String tabs = Files.readString(Path.of(
            "src/main/java/io/github/dsheirer/gui/configuration/alias/AliasEditor.java"));
        String editor = Files.readString(Path.of(
            "src/main/java/io/github/dsheirer/gui/configuration/alias/AliasViewByScanListEditor.java"));

        assertTrue(tabs.contains("new Tab(\"Scan Lists\")"));
        assertTrue(editor.contains("createListPane(\"Alias List\""));
        assertTrue(editor.contains("createListPane(\"Scan List\""));
        assertTrue(editor.contains("new Button(\"Add >\")"));
        assertTrue(editor.contains("new Button(\"Add All >>\")"));
        assertTrue(editor.contains("new Button(\"< Remove\")"));
        assertTrue(editor.contains("new Button(\"<< Remove All\")"));
        assertTrue(editor.contains("updateScanListMemberships(scanList.getId()"));
    }
}
