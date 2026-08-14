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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPlaybackUiContractTest
{
    private static final Path ALIAS_UI_DIRECTORY =
        Path.of("src/main/java/io/github/dsheirer/gui/configuration/alias");
    private static final Path RADIO_REFERENCE_UI_DIRECTORY =
        Path.of("src/main/java/io/github/dsheirer/gui/configuration/radioreference");

    @Test
    void aliasEditorsDoNotExposeReceiverLocalPlaybackControls() throws Exception
    {
        String itemEditor = Files.readString(ALIAS_UI_DIRECTORY.resolve("AliasItemEditor.java"));
        String bulkEditor = Files.readString(ALIAS_UI_DIRECTORY.resolve("AliasBulkEditor.java"));
        String configurationEditor =
            Files.readString(ALIAS_UI_DIRECTORY.resolve("AliasConfigurationEditor.java"));

        assertFalse(itemEditor.contains("getPlaybackPriority()"));
        assertFalse(itemEditor.contains("setCallPriority("));
        assertTrue(itemEditor.contains(".replaceAlias("));
        assertTrue(itemEditor.contains("getAliasModel().getAlias(edited.getId())"),
            "Reset must reload the canonical durable-ID row instead of blessing a stale editor object");
        assertTrue(!itemEditor.contains("setItem(getItem())"));
        assertTrue(configurationEditor.lines()
            .filter(line -> line.contains("resolveModifiedAliasDraft()"))
            .count() >= 8, "Page commands must resolve a dirty draft before advancing the shared revision");
        assertTrue(configurationEditor.contains("clearDeletedAliasDraft(deleted.aliasIds())"));
        assertFalse(itemEditor.contains("new Label(\"Listen\")"));
        assertFalse(itemEditor.contains("new Label(\"Priority\")"));
        assertFalse(bulkEditor.contains("new Label(\"Listen\")"));
        assertFalse(bulkEditor.contains("new Label(\"Priority\")"));
        assertTrue(bulkEditor.contains("new AliasAdministrationService.BulkEdit("));
        assertTrue(bulkEditor.contains(".bulkEdit("));
        assertFalse(configurationEditor.contains("new TableColumn<>(\"Listen\")"));
    }

    @Test
    void radioReferenceImportDoesNotExposeReceiverMutePolicy() throws Exception
    {
        String selectionEditor =
            Files.readString(RADIO_REFERENCE_UI_DIRECTORY.resolve("SystemTalkgroupSelectionEditor.java"));
        String talkgroupEditor = Files.readString(RADIO_REFERENCE_UI_DIRECTORY.resolve("TalkgroupEditor.java"));
        assertFalse(selectionEditor.contains("RadioReferenceAliasPlaybackPolicy.apply("));
        assertFalse(talkgroupEditor.contains("RadioReferenceAliasPlaybackPolicy.apply("));
        assertFalse(selectionEditor.contains("Set Encrypted Talkgroups To Muted"));
        assertFalse(selectionEditor.contains("isEncryptedTalkgroupDoNotMonitor()"));
        assertFalse(selectionEditor.contains("setEncryptedTalkgroupDoNotMonitor("));
    }

    @Test
    void aliasModelDoesNotExposePlaybackPriority()
    {
        assertThrows(NoSuchMethodException.class, () -> Alias.class.getMethod("getPlaybackPriority"));
        assertThrows(NoSuchMethodException.class, () -> Alias.class.getMethod("setCallPriority", int.class));
    }
}
