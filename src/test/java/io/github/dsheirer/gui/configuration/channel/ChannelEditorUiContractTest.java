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

package io.github.dsheirer.gui.configuration.channel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Source contract for the channel list action column.
 */
class ChannelEditorUiContractTest
{
    private static final Path CHANNEL_EDITOR =
        Path.of("src/main/java/io/github/dsheirer/gui/configuration/channel/ChannelEditor.java");

    @Test
    void actionColumnRetainsItsPreferredWidth() throws Exception
    {
        String source = Files.readString(CHANNEL_EDITOR);

        assertTrue(source.contains("mButtonBox.setMinWidth(Region.USE_PREF_SIZE)"));
        assertTrue(source.contains("mButtonBox.getChildren().addAll(getNewButton(), getCloneButton(), " +
            "getDeleteButton())"));
    }
}
