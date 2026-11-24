/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.GroupAffiliationTestMessages.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.IdentityResolutionEvidence;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.module.decode.p25.P25AffiliationEvent;
import io.github.dsheirer.module.decode.p25.P25IdentityResolver;
import io.github.dsheirer.module.decode.p25.P25RadioPresence;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.bandplan.P25BandplanOverrideRegistry;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DecoderState;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUMessageFactory;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequence;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.GroupAffiliationResponse;
import io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Real decoder to assignment registry to existing ISSI page data, without hardware, ports or a database. */
class StatsP25ExtendedAffiliationTest
{
    private static final P25SiteIdentity SITE = new P25SiteIdentity(0xBEE00, 0x348, 1, 1);

    @Test
    void acceptedCaptureFeedsTheIssiGroupTableAndRefreshesOnlyTheKnownRadio()
    {
        var fixture = new Fixture();
        var subscriber = new P25SubscriberIdentity(0xABCDE, 0x456, 700_001);
        assertNotNull(fixture.resolver.register(SITE, new P25RadioPresence(subscriber, WORKING_RADIO), fixture.now - 500L, null));
        fixture.receive(capture());

        var event = fixture.events.getFirst();
        assertEquals(P25AffiliationEvent.Outcome.ACCEPTED, event.getOutcome());
        assertInstanceOf(APCO25RadioIdentifier.class, event.getRadioIdentifier());
        assertEquals(WORKING_RADIO, event.getRadioId());
        assertNotNull(event.getAssignmentObservation());
        assertEquals(subscriber, event.getAssignmentObservation().subscriber());
        assertEquals(fixture.now, event.getAssignmentObservation().observedAt());
        assertFalse(event.getDetails().contains("NO GROUP ASSIGNED"));

        var service = new StatsP25AssignmentService(fixture.resolver::snapshot, (key, rows) -> rows, 1L);
        service.refresh(fixture.now);
        Map<String,Object> state = service.currentState(RadioSystemKey.p25(SITE.wacn(), SITE.system()));
        assertEquals(1, state.get("group_association_count"));
        var row = (Map<?,?>)((List<?>)state.get("group_associations")).getFirst();
        assertEquals(0xFFC2, row.get("observed_local_id"));
        assertEquals(0xBEE00, row.get("canonical_wacn"));
        assertEquals(0x3A9, row.get("canonical_system_id"));
        assertEquals(0x3715, row.get("canonical_talkgroup_id"));
        assertEquals("group_affiliation", row.get("evidence"));

        var identifiers = new MutableIdentifierCollection(List.of(APCO25Talkgroup.create(0xFFC2)));
        fixture.manager.enrichMutableIdentifiers(identifiers, fixture.now + 1);
        var group = assertInstanceOf(APCO25FullyQualifiedTalkgroupIdentifier.class, identifiers.getToIdentifier());
        assertEquals(0x3715, group.getTalkgroup());
        assertEquals(IdentityResolutionEvidence.CONFIRMED_ASSIGNMENT, group.getResolutionEvidence());
    }

    @Test
    void failedDeniedAndRefusedResponsesDoNotCreateAssociationsOrRadioRegistrations()
    {
        for(int outcome = 1; outcome < 4; outcome++)
        {
            var fixture = new Fixture();
            fixture.receive(response(outcome, 0x3715, 0xFFC2));
            var event = fixture.events.getFirst();
            assertEquals(P25AffiliationEvent.Outcome.REJECTED, event.getOutcome());
            assertFalse(event.getDetails().contains("ACCEPTED"));
            assertTrue(fixture.resolver.snapshot(fixture.now).groupAssignments().isEmpty());
            assertTrue(fixture.resolver.snapshot(fixture.now).assignments().isEmpty());
        }
    }

    @Test
    void acceptedHomeOnlyOrReservedGroupsNeverTeachAnAssignment()
    {
        for(int[] groups : new int[][]{{0x3715, 0}, {0x3715, 0xFFFF}, {0, 0xFFC2}, {0xFFFF, 0xFFC2}})
        {
            var fixture = new Fixture();
            fixture.receive(response(0, groups[0], groups[1]));
            assertTrue(fixture.resolver.snapshot(fixture.now).groupAssignments().isEmpty());
            assertTrue(fixture.resolver.snapshot(fixture.now).assignments().isEmpty());
        }
    }

    @Test
    void requestsAndAbbreviatedResponsesCannotInventAHomeGroupAssociation()
    {
        var fixture = new Fixture();
        fixture.receive(request(0x3715));
        assertEquals(P25AffiliationEvent.Outcome.REQUESTED, fixture.events.getFirst().getOutcome());
        var bits = CorrectedBinaryMessage.loadHex("280000FFA6FFC2FFFAB20000");
        fixture.state.receive(new GroupAffiliationResponse(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1,
            bits, NAC, fixture.now + 1));
        assertEquals(P25AffiliationEvent.Outcome.ACCEPTED, fixture.events.getLast().getOutcome());
        assertTrue(fixture.resolver.snapshot(fixture.now + 1).groupAssignments().isEmpty());
    }

    @Test
    void acceptedGroupDoesNotInventANewRadioRegistration()
    {
        var fixture = new Fixture();
        fixture.receive(capture());
        assertNull(fixture.events.getFirst().getAssignmentObservation());
        assertTrue(fixture.resolver.snapshot(fixture.now).assignments().isEmpty());
        assertEquals(1, fixture.resolver.snapshot(fixture.now).groupAssignments().size());
    }

    private static class Fixture
    {
        private final long now = System.currentTimeMillis();
        private final P25IdentityResolver resolver = new P25IdentityResolver();
        private final P25TrafficChannelManager manager;
        private final P25P1DecoderState state;
        private final List<P25AffiliationEvent> events = new ArrayList<>();

        private Fixture()
        {
            var channel = new Channel("Affiliation fixture", Channel.ChannelType.STANDARD);
            channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
            channel.setP25SiteIdentity(SITE);
            manager = new P25TrafficChannelManager(channel, P25BandplanOverrideRegistry.empty(), resolver);
            state = new P25P1DecoderState(channel, manager);
            state.addDecodeEventListener(event -> {
                if(event instanceof P25AffiliationEvent affiliation) events.add(affiliation);
            });
        }

        private void receive(PDUSequence sequence)
        {
            assertTrue(sequence.isValid());
            state.receive(PDUMessageFactory.create(sequence, NAC, now));
        }
    }
}
