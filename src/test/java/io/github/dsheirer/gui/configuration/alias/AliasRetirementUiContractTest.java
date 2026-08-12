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

class AliasRetirementUiContractTest
{
    private static final Path CONFIGURATION_EDITOR =
        Path.of("src/main/java/io/github/dsheirer/gui/configuration/ConfigurationEditor.java");
    private static final Path ALIAS_EDITOR =
        Path.of("src/main/java/io/github/dsheirer/gui/configuration/alias/AliasEditor.java");
    private static final Path ALIAS_CONFIGURATION_EDITOR =
        Path.of("src/main/java/io/github/dsheirer/gui/configuration/alias/AliasConfigurationEditor.java");

    @Test
    void aliasActionsKeepReadableLabelsAndTooltips() throws Exception
    {
        String source = Files.readString(ALIAS_CONFIGURATION_EDITOR);

        assertTrue(source.contains("new Button(\"New\")"));
        assertTrue(source.contains("new Button(\"Clone\")"));
        assertTrue(source.contains("new MenuButton(\"Move To\")"));
        assertTrue(source.contains("new Button(\"Delete\")"));
        assertTrue(source.contains("button.setMinWidth(Region.USE_PREF_SIZE)"));
        assertTrue(source.contains("button.setTooltip(new Tooltip(tooltip))"));
    }

    @Test
    void identifierTabExplainsThatItIsABrowser() throws Exception
    {
        String source = Files.readString(ALIAS_EDITOR);

        assertTrue(source.contains("new Tab(\"Browse by Identifier\")"));
    }

    @Test
    void retirementNoticeAppearsOnceAndRoutesToWebOrSettings() throws Exception
    {
        String configurationEditor = Files.readString(CONFIGURATION_EDITOR);
        String aliasEditor = Files.readString(ALIAS_EDITOR);

        assertTrue(configurationEditor.contains("getAliasEditor().showRetirementNotice()"));
        assertTrue(aliasEditor.contains("if(!mRetirementNoticeShown)"));
        assertTrue(aliasEditor.contains("mRetirementNoticeShown = true"));
        assertTrue(aliasEditor.contains("Java Alias Editor Retirement"));
        assertTrue(aliasEditor.contains("Open Web Alias Editor"));
        assertTrue(aliasEditor.contains("PreferenceEditorType.WEB_SERVER"));
        assertTrue(aliasEditor.contains("navigation.aliasEditorUri()"));
    }
}
