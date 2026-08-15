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
        assertTrue(editor.contains("FontAwesome.ANGLE_RIGHT"));
        assertTrue(editor.contains("FontAwesome.ANGLE_DOUBLE_RIGHT"));
        assertTrue(editor.contains("FontAwesome.ANGLE_LEFT"));
        assertTrue(editor.contains("FontAwesome.ANGLE_DOUBLE_LEFT"));
        assertTrue(editor.contains("button.setGraphic(new IconNode(icon))"));
        assertTrue(editor.contains("FontAwesome.PLUS"));
        assertTrue(editor.contains("FontAwesome.PENCIL"));
        assertTrue(editor.contains("FontAwesome.TRASH"));
        assertTrue(editor.contains("service.createScanList("));
        assertTrue(editor.contains("service.updateScanList("));
        assertTrue(editor.contains("service.deleteScanList("));
        assertTrue(editor.contains("entry.aliasIds().size()"));
        assertTrue(editor.contains("entry.unmatchedAliasListIds().size()"));
        assertTrue(editor.contains("selected.isDefault()"));
        assertTrue(editor.contains("column(\"Description\", \"description\""));
        assertTrue(editor.contains("updateScanListMemberships(scanList.getId()"));
    }
}
