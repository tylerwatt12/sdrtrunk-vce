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

class JavaAliasListDefaultsUiContractTest
{
    @Test
    void exposesSharedDefaultsAndPerAliasScanListsWithSafetyCopy() throws Exception
    {
        String editor = Files.readString(Path.of(
            "src/main/java/io/github/dsheirer/gui/configuration/alias/AliasConfigurationEditor.java"));
        String item = Files.readString(Path.of(
            "src/main/java/io/github/dsheirer/gui/configuration/alias/AliasItemEditor.java"));
        String dialog = Files.readString(Path.of(
            "src/main/java/io/github/dsheirer/gui/configuration/alias/AliasListDefaultsDialog.java"));

        assertTrue(editor.contains("new Button(\"Alias List Defaults\")"));
        assertTrue(editor.contains("AliasListDefaultsDialog.show"));
        assertTrue(editor.contains("setInitialScanListIds"));
        assertTrue(item.contains("new TitledPane(\"Scan List\""));
        assertTrue(item.contains("createAlias(replacement, scanListIds, mLoadedRevision)"));
        assertTrue(item.contains("replaceAlias(alias.getId(), replacement, scanListIds, mLoadedRevision)"));
        assertTrue(dialog.contains("new TitledPane(\"Recording\""));
        assertTrue(dialog.contains("new TitledPane(\"Streaming\""));
        assertTrue(dialog.contains("including sensitive traffic"));
        assertTrue(dialog.contains("new ScrollPane(content)"));
        assertTrue(dialog.contains("scroll.setFitToWidth(true)"));
        assertTrue(dialog.contains("ScrollPane.ScrollBarPolicy.NEVER"));
        assertTrue(dialog.contains("screenHeight - 220"));
        assertTrue(dialog.contains("event.consume()"));
    }
}
