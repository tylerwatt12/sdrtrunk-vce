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

package io.github.dsheirer.gui.configuration.channel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.module.decode.DecoderType;
import org.junit.jupiter.api.Test;

class ChannelConfigurationEditorTest
{
    @Test
    void p25ConventionalCanSelectAnyP25ListWithoutSystemOwnership()
    {
        AliasListDefinition p25 = new AliasListDefinition("Shared P25", AliasListFamily.P25);

        assertTrue(ChannelConfigurationEditor.isAliasListCompatible(p25, DecoderType.P25_CONVENTIONAL));
        assertTrue(ChannelConfigurationEditor.isAliasListCompatible(p25, DecoderType.P25_PHASE1));
        assertTrue(ChannelConfigurationEditor.isAliasListCompatible(p25, DecoderType.P25_PHASE2));
        assertFalse(ChannelConfigurationEditor.isAliasListCompatible(p25, DecoderType.DMR));
        assertFalse(ChannelConfigurationEditor.isAliasListCompatible(p25, DecoderType.NXDN));
    }
}
