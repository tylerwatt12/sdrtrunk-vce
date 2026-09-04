/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.module.decode.dmr.DMRConventionalCallEvent;
import io.github.dsheirer.module.decode.nxdn.NXDNConventionalCallEvent;
import org.junit.jupiter.api.Test;

/** Focused mapper coverage for the immutable conventional producer records. */
class ReceiverActivityMapperTest
{
    private static final String CONFIGURATION_ID = "223e4567-e89b-42d3-a456-426614174000";

    @Test
    void mapsDmrCompletionUsingOnlyTheSavedConfigurationId()
    {
        DMRConventionalCallEvent event = new DMRConventionalCallEvent(1_000, 2_000, CONFIGURATION_ID,
            461_125_000, 2, DMRConventionalCallEvent.TargetKind.PRIVATE, null, 101, 202, true);
        ReceiverActivityRecords.DmrConventionalCall record = new ReceiverActivityMapper().map(event);

        assertEquals(CONFIGURATION_ID, record.configurationId());
        assertEquals(461_125_000, record.frequencyHertz());
        assertEquals(2, record.timeslot());
        assertEquals(ReceiverActivityRecords.DmrTargetKind.PRIVATE, record.targetKind());
        assertEquals(101, record.sourceRadioId());
        assertEquals(202, record.targetRadioId());
        assertTrue(record.encrypted());
    }

    @Test
    void mapsNxdnCompletionUsingOnlyTheSavedConfigurationId()
    {
        NXDNConventionalCallEvent event = new NXDNConventionalCallEvent(1_000, 2_000, CONFIGURATION_ID,
            461_125_000, NXDNConventionalCallEvent.TargetKind.GROUP, 91, 101, null, true);
        ReceiverActivityRecords.NxdnConventionalCall record = new ReceiverActivityMapper().map(event);

        assertEquals(CONFIGURATION_ID, record.configurationId());
        assertEquals(ReceiverActivityRecords.NxdnTargetKind.GROUP, record.targetKind());
        assertEquals(91, record.talkgroupId());
        assertEquals(101, record.sourceRadioId());
        assertNull(record.targetRadioId());
        assertTrue(record.encrypted());
    }

    @Test
    void rejectsMissingOrNonCanonicalConfigurationIdentity()
    {
        ReceiverActivityMapper mapper = new ReceiverActivityMapper();
        assertNull(mapper.map(new DMRConventionalCallEvent(1_000, 2_000, null, 461_125_000, 1,
            DMRConventionalCallEvent.TargetKind.UNKNOWN, null, null, null, false)));
        assertNull(mapper.map(new NXDNConventionalCallEvent(1_000, 2_000, "display-name", 461_125_000,
            NXDNConventionalCallEvent.TargetKind.UNKNOWN, null, null, null, false)));
    }
}
