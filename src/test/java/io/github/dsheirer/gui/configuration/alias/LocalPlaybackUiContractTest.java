/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.gui.configuration.alias;

import io.github.dsheirer.alias.Alias;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPlaybackUiContractTest
{
    private static final Path ALIAS_UI_DIRECTORY =
        Path.of("src/main/java/io/github/dsheirer/gui/configuration/alias");
    private static final Path RADIO_REFERENCE_UI_DIRECTORY =
        Path.of("src/main/java/io/github/dsheirer/gui/configuration/radioreference");

    @Test
    void aliasEditorsExposeReceiverLocalListenSwitch() throws Exception
    {
        String itemEditor = Files.readString(ALIAS_UI_DIRECTORY.resolve("AliasItemEditor.java"));
        String bulkEditor = Files.readString(ALIAS_UI_DIRECTORY.resolve("AliasBulkEditor.java"));
        String configurationEditor =
            Files.readString(ALIAS_UI_DIRECTORY.resolve("AliasConfigurationEditor.java"));

        assertTrue(itemEditor.contains("isListen()"));
        assertTrue(itemEditor.contains("setListen("));
        assertTrue(itemEditor.contains("new Label(\"Listen\")"));
        assertFalse(itemEditor.contains("new Label(\"Priority\")"));
        assertTrue(bulkEditor.contains("setListen("));
        assertTrue(configurationEditor.contains("new TableColumn<>(\"Listen\")"));
    }

    @Test
    void radioReferenceImportExposesEncryptedTalkgroupMutePolicy() throws Exception
    {
        for(String fileName : List.of("SystemTalkgroupSelectionEditor.java", "TalkgroupEditor.java"))
        {
            String source = Files.readString(RADIO_REFERENCE_UI_DIRECTORY.resolve(fileName));
            assertTrue(source.contains("RadioReferenceAliasPlaybackPolicy.apply("), fileName);
        }

        String selectionEditor =
            Files.readString(RADIO_REFERENCE_UI_DIRECTORY.resolve("SystemTalkgroupSelectionEditor.java"));
        assertTrue(selectionEditor.contains("Set Encrypted Talkgroups To Muted"));
        assertTrue(selectionEditor.contains("isEncryptedTalkgroupDoNotMonitor()"));
        assertTrue(selectionEditor.contains("setEncryptedTalkgroupDoNotMonitor("));
    }

    @Test
    void listenProjectionIsBinary()
    {
        Alias alias = new Alias("Listen");
        alias.setListen(false);

        assertFalse(alias.isListen());
        assertNull(alias.getMatchIdentifier());
    }
}
