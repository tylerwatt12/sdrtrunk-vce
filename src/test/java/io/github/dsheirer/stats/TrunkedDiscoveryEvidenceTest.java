/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TrunkedDiscoveryEvidenceTest
{
    @Test
    void lowP25ScoreIsNotASaveThresholdButIdentityCountAndFiniteScoreStillAreRequired()
    {
        var p25 = new P25SiteIdentity(0xABCDE, 0x123, 2, 7);
        String key = RadioSystemKey.p25(p25);
        var identity = new TrunkedDiscoveryEvidence.Identity(p25, key, key + ":2:7",
            null, p25.system(), p25.site(), null, null, null, null, null);
        assertTrue(proof("p25-phase1", "P25_PHASE_1", identity, 38, 3).verified());
        assertFalse(proof("p25-phase1", "P25_PHASE_1", identity, 19, 3).verified());
        assertFalse(proof("p25-phase1", "P25_PHASE_1", identity, 38, Double.NaN).verified());
        assertFalse(proof("p25-phase1", "P25_PHASE_1", identity, 38, -1).verified());
        assertFalse(proof("p25-phase1", "P25_PHASE_1", identity, 38, 101).verified());
        assertFalse(proof("p25-phase1", "P25_PHASE_1", null, 38, 3).verified());
    }

    @Test
    void dmrAndNxdnKeepTheirExistingQualityPolicy()
    {
        String dmrKey = RadioSystemKey.dmrTier3("SMALL", 0);
        var dmr = new TrunkedDiscoveryEvidence.Identity(null, dmrKey, dmrKey + ":8",
            0, null, 8, "SMALL", null, null, null, 0);
        String nxdnKey = RadioSystemKey.nxdnTypeC("LOCAL", 68);
        var nxdn = new TrunkedDiscoveryEvidence.Identity(null, nxdnKey, nxdnKey + ":5",
            null, 68, 5, null, "LOCAL", null, 5, null);
        assertFalse(proof("dmr", "TIER_III", dmr, 38, 3).verified());
        assertTrue(proof("dmr", "TIER_III", dmr, 38, 60).verified());
        assertFalse(proof("nxdn", "TYPE_C", nxdn, 38, 3).verified());
        assertTrue(proof("nxdn", "TYPE_C", nxdn, 38, 60).verified());
    }

    private static TrunkedDiscoveryEvidence proof(String protocol, String variant,
        TrunkedDiscoveryEvidence.Identity identity, long controls, double score)
    {
        return new TrunkedDiscoveryEvidence(protocol, variant, identity, Map.of(), List.of(), score,
            controls, controls, 169, 7000, "Test evidence");
    }
}
