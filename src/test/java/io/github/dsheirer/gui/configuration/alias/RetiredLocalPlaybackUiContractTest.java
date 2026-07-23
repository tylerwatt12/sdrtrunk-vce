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
import io.github.dsheirer.alias.id.priority.Priority;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class RetiredLocalPlaybackUiContractTest
{
    private static final Path ALIAS_UI_DIRECTORY =
        Path.of("src/main/java/io/github/dsheirer/gui/configuration/alias");
    private static final Path RADIO_REFERENCE_UI_DIRECTORY =
        Path.of("src/main/java/io/github/dsheirer/gui/configuration/radioreference");

    @Test
    void aliasEditorsCannotExposeOrModifyReceiverLocalPlaybackPriority() throws Exception
    {
        for(String fileName : List.of("AliasItemEditor.java", "AliasBulkEditor.java",
            "AliasConfigurationEditor.java"))
        {
            String source = Files.readString(ALIAS_UI_DIRECTORY.resolve(fileName));
            assertFalse(source.contains("getPlaybackPriority("), fileName);
            assertFalse(source.contains("setCallPriority("), fileName);
            assertFalse(source.contains("new Label(\"Listen\")"), fileName);
            assertFalse(source.contains("new Label(\"Priority\")"), fileName);
            assertFalse(source.contains("new TableColumn<>(\"Listen\")"), fileName);
        }
    }

    @Test
    void radioReferenceImportCannotCreateMutedAliases() throws Exception
    {
        for(String fileName : List.of("SystemTalkgroupSelectionEditor.java", "TalkgroupEditor.java"))
        {
            String source = Files.readString(RADIO_REFERENCE_UI_DIRECTORY.resolve(fileName));
            assertFalse(source.contains("Set Encrypted Talkgroups To Muted"), fileName);
            assertFalse(source.contains("EncryptedAsDoNotMonitor"), fileName);
            assertFalse(source.contains("setEncryptedDoNotMonitor"), fileName);
            assertFalse(source.contains("Priority.DO_NOT_MONITOR"), fileName);
        }
    }

    @Test
    void legacyMonitorPriorityRemainsPartOfAliasData()
    {
        Alias alias = new Alias("Legacy priority");
        Priority priority = new Priority(12);
        alias.addAliasID(priority);

        alias.removeNonAudioIdentifiers();

        assertEquals(12, alias.getPlaybackPriority());
        assertSame(priority, alias.getAliasIdentifiers().getFirst());
    }
}
