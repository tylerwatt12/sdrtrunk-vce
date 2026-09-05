/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25NetworkConfigurationSnapshotTest
{
    @Test
    void defensivelyCopiesTopLevelAndNestedPatchLists()
    {
        List<P25NetworkConfigurationSnapshot.Channel> channels = new ArrayList<>();
        channels.add(new P25NetworkConfigurationSnapshot.Channel("primary_control", "1-1", 851_012_500L,
            806_012_500L, false, 1));
        List<Integer> talkgroups = new ArrayList<>(List.of(1001, 1002));
        List<Integer> radios = new ArrayList<>(List.of(2001));
        List<P25NetworkConfigurationSnapshot.PatchGroup> patches = new ArrayList<>();
        patches.add(new P25NetworkConfigurationSnapshot.PatchGroup(1000, 1, talkgroups, radios));

        P25NetworkConfigurationSnapshot snapshot = new P25NetworkConfigurationSnapshot("P25_PHASE_1",
            new P25NetworkConfigurationSnapshot.Network(0xBEE00, 0x348, 0x293, null), null, channels,
            List.of(), List.of(), patches, List.of());

        channels.clear();
        patches.clear();
        talkgroups.add(1003);
        radios.add(2002);

        assertEquals(1, snapshot.channels().size());
        assertEquals(List.of(1001, 1002), snapshot.patchGroups().getFirst().localTalkgroupIds());
        assertEquals(List.of(2001), snapshot.patchGroups().getFirst().localRadioIds());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.channels().clear());
        assertThrows(UnsupportedOperationException.class,
            () -> snapshot.patchGroups().getFirst().localTalkgroupIds().add(1004));
    }
}
