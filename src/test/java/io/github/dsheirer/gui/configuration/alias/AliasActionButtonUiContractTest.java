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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Source contract for the Alias list action column.
 */
class AliasActionButtonUiContractTest
{
    private static final Path ALIAS_CONFIGURATION_EDITOR =
        Path.of("src/main/java/io/github/dsheirer/gui/configuration/alias/AliasConfigurationEditor.java");

    @Test
    void actionColumnAndButtonsRetainTheirPreferredWidths() throws Exception
    {
        String source = Files.readString(ALIAS_CONFIGURATION_EDITOR);

        assertTrue(source.contains("mButtonBox.setMinWidth(Region.USE_PREF_SIZE)"));
        assertTrue(source.contains("configureAliasActionButton(mNewAliasButton"));
        assertTrue(source.contains("configureAliasActionButton(mCloneAliasButton"));
        assertTrue(source.contains("configureAliasActionButton(mMoveToAliasButton"));
        assertTrue(source.contains("configureAliasActionButton(mDeleteAliasButton"));
        assertTrue(source.contains("button.setMinWidth(Region.USE_PREF_SIZE)"));
    }
}
