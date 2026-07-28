/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.startup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoordinatedStartupDialogJmbeTest
{
    @Test
    void detectsMbeChannelsBeforeAutoStart()
    {
        Channel p25 = new Channel("P25");
        p25.setDecodeConfiguration(new DecodeConfigP25Phase1());
        Channel analog = new Channel("Analog");
        analog.setDecodeConfiguration(new DecodeConfigNBFM());

        assertTrue(CoordinatedStartupDialog.channelsRequireJmbe(List.of(analog, p25)));
        assertFalse(CoordinatedStartupDialog.channelsRequireJmbe(List.of(analog)));
        assertFalse(CoordinatedStartupDialog.channelsRequireJmbe(List.of(new Channel("Unconfigured"))));
        assertFalse(CoordinatedStartupDialog.channelsRequireJmbe(List.of()));
        assertFalse(CoordinatedStartupDialog.channelsRequireJmbe(null));
    }
}
