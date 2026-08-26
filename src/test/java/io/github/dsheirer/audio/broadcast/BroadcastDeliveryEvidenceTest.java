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

package io.github.dsheirer.audio.broadcast;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.call.ResolvedCallPolicy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BroadcastDeliveryEvidenceTest
{
    private static final String EAST_CHANNEL = "aaaaaaaa-0000-0000-0000-000000000101";
    private static final String WEST_CHANNEL = "bbbbbbbb-0000-0000-0000-000000000102";
    private static final long COUNTY_ALIAS_LIST_ID = 41L;

    @Test
    void selectedLosingSiteRetainsItsOwnRouteEvidence()
    {
        ResolvedCallPolicy policy = policy(
            context(EAST_CHANNEL, COUNTY_ALIAS_LIST_ID, "County", "bc-east"),
            context(WEST_CHANNEL, COUNTY_ALIAS_LIST_ID, "County", "bc-west"));

        BroadcastDeliveryEvidence evidence = BroadcastDeliveryEvidence.from(policy);

        assertEquals(2, evidence.observations().size());
        assertTrue(evidence.matches("bc-west", COUNTY_ALIAS_LIST_ID, WEST_CHANNEL),
            "A site that lost the audio election must still retain its own delivery evidence");
        assertTrue(evidence.matches("bc-east", COUNTY_ALIAS_LIST_ID, EAST_CHANNEL));
    }

    @Test
    void routeAndChannelFromDifferentDuplicateCopiesCannotBeCombined()
    {
        ResolvedCallPolicy policy = policy(
            context(EAST_CHANNEL, COUNTY_ALIAS_LIST_ID, "County", "bc-west"),
            context(WEST_CHANNEL, COUNTY_ALIAS_LIST_ID, "County", "different-provider"));

        BroadcastDeliveryEvidence evidence = BroadcastDeliveryEvidence.from(policy);

        assertFalse(evidence.matches("bc-west", COUNTY_ALIAS_LIST_ID, WEST_CHANNEL),
            "The provider route and selected site must occur in one observation");
        assertFalse(evidence.matches("bc-west", 99L, EAST_CHANNEL),
            "Evidence from a different alias list must not be accepted");
    }

    @Test
    void stableAliasListIdSurvivesDisplayNameChangesAndChannelIdsAreCanonicalized()
    {
        BroadcastDeliveryEvidence evidence = BroadcastDeliveryEvidence.from(policy(
            context(WEST_CHANNEL.toUpperCase(), COUNTY_ALIAS_LIST_ID, "Old Display Name", "bc-west")));

        assertTrue(evidence.matches(" bc-west ", COUNTY_ALIAS_LIST_ID, WEST_CHANNEL));
        assertFalse(evidence.matches("bc-west", COUNTY_ALIAS_LIST_ID, "not-a-channel-uuid"));
        assertFalse(evidence.matches("bc-west", 0L, WEST_CHANNEL));
    }

    @Test
    void projectedCollectionsAreImmutableAndNullPolicyIsEmpty()
    {
        BroadcastDeliveryEvidence evidence = BroadcastDeliveryEvidence.from(policy(
            context(WEST_CHANNEL, COUNTY_ALIAS_LIST_ID, "County", "bc-west")));

        assertThrows(UnsupportedOperationException.class,
            () -> evidence.observations().add(evidence.observations().getFirst()));
        assertThrows(UnsupportedOperationException.class,
            () -> evidence.observations().getFirst().broadcastRoutingKeys().add("other"));
        assertEquals(BroadcastDeliveryEvidence.EMPTY, BroadcastDeliveryEvidence.from(null));
    }

    private static ResolvedCallPolicy policy(ResolvedCallPolicy.MatchContext... contexts)
    {
        return new ResolvedCallPolicy(false, false, Set.of(), List.of(contexts));
    }

    private static ResolvedCallPolicy.MatchContext context(String channelConfigurationId, long aliasListId,
                                                            String aliasListName, String route)
    {
        return new ResolvedCallPolicy.MatchContext(channelConfigurationId, aliasListId, aliasListName, "System",
            List.of(), Set.of(), AliasList.TalkgroupMatchStatus.NOT_APPLICABLE, false, false, Set.of(route));
    }
}
