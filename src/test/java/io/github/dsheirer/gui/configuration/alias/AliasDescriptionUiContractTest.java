/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.gui.configuration.alias;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AliasDescriptionUiContractTest
{
    private static final Path ALIAS_UI_DIRECTORY =
        Path.of("src/main/java/io/github/dsheirer/gui/configuration/alias");
    private static final Path RADIO_REFERENCE_UI_DIRECTORY =
        Path.of("src/main/java/io/github/dsheirer/gui/configuration/radioreference");

    @Test
    void desktopAliasListAndEditorExposeDescription() throws Exception
    {
        String configurationEditor =
            Files.readString(ALIAS_UI_DIRECTORY.resolve("AliasConfigurationEditor.java"));
        String aliasPredicate = Files.readString(ALIAS_UI_DIRECTORY.resolve("AliasPredicate.java"));
        String itemEditor = Files.readString(ALIAS_UI_DIRECTORY.resolve("AliasItemEditor.java"));

        assertTrue(configurationEditor.contains("descriptionColumn.setText(\"Description\")"));
        assertTrue(aliasPredicate.contains("contains(alias.getDescription())"));
        assertTrue(itemEditor.contains("new Label(\"Description\")"));
        assertTrue(itemEditor.contains("replacement.setDescription(getDescriptionField().getText())"));
        assertTrue(itemEditor.contains(".replaceAlias("));
    }

    @Test
    void radioReferenceImporterDistinguishesSourceAndSavedDescription() throws Exception
    {
        String editor = Files.readString(RADIO_REFERENCE_UI_DIRECTORY.resolve("TalkgroupEditor.java"));
        String decoder = Files.readString(RADIO_REFERENCE_UI_DIRECTORY.resolve("RadioReferenceDecoder.java"));

        assertTrue(editor.contains("new Label(\"RadioReference Description\")"));
        assertTrue(editor.contains("new Label(\"Saved Description\")"));
        assertTrue(editor.contains("alias.setDescription(getAliasDescriptionTextField().getText())"));
        assertTrue(decoder.contains("alias.setDescription(talkgroup.getDescription())"));
    }
}
