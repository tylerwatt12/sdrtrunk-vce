/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.alerts;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.module.decode.event.DecodeEventViewService;
import io.github.dsheirer.stats.WebEntityReferences;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AlertActionTest
{
    private static final String CHANNEL = "00000000-0000-4000-8000-000000000001";
    private static final String SYSTEM = "p25:12345:001";

    @Test void automaticDestinationsCoverEveryTriggerAndRespectTheToggle()
    {
        var context = AlertAction.capture(view(CHANNEL, SYSTEM, List.of(radio(100, 100)), List.of(group("TALKGROUP", 200), radio(300, 300)), "APCO25"));
        for(String trigger: AlertRule.TRIGGERS) assertEquals(AlertAction.NONE, AlertAction.automatic(trigger, context, false));
        assertEquals("talkgroup", AlertAction.automatic("call", context, true).kind());
        for(String trigger: List.of("emergency", "affiliation"))
            assertEquals("v1-r-12345-001-100", AlertAction.automatic(trigger, context, true).entityRef().get("identity_key"));
        for(String trigger: List.of("paging", "command"))
            assertEquals("v1-r-12345-001-300", AlertAction.automatic(trigger, context, true).entityRef().get("identity_key"));
        for(String trigger: List.of("tone", "dcs", "event")) assertEquals("channel", AlertAction.automatic(trigger, context, true).kind());
        assertEquals(AlertAction.LIVE, AlertAction.automatic("health", context, true));
        var patch = AlertAction.capture(view(CHANNEL, SYSTEM, List.of(), List.of(group("PATCH_GROUP", 400)), "APCO25"));
        assertEquals("patch_group", AlertAction.automatic("call", patch, true).kind());
        assertEquals("radio", AlertAction.automatic("paging", new AlertAction.Context(null, null, context.sourceRadio(), null, null), true).kind());
    }

    @Test void ambiguousOrUnavailableIdentitiesFallBackWithoutGuessing()
    {
        var ambiguous = AlertAction.capture(view(CHANNEL, SYSTEM, List.of(radio(1, 1), radio(2, 2)),
            List.of(group("TALKGROUP", 3), group("TALKGROUP", 4)), "APCO25"));
        assertEquals("channel", AlertAction.automatic("call", ambiguous, true).kind());
        assertEquals("channel", AlertAction.automatic("emergency", ambiguous, true).kind());
        assertEquals("radio_system", AlertAction.automatic("event", new AlertAction.Context(null, ambiguous.system(), null, null, null), true).kind());
        assertEquals(AlertAction.LIVE, AlertAction.automatic("call", AlertAction.Context.EMPTY, true));
        assertEquals("channel", AlertAction.automatic("call", AlertAction.capture(view(CHANNEL, SYSTEM, List.of(),
            List.of(group("TALKGROUP", 65535)), "APCO25")), true).kind());
    }

    @Test void canonicalSubscribersRemainDistinctWhenWorkingIdsAreReused()
    {
        var first = AlertAction.capture(view(CHANNEL, SYSTEM, List.of(radio(1001, 42)), List.of(), "APCO25"));
        var second = AlertAction.capture(view(CHANNEL, SYSTEM, List.of(radio(1002, 42)), List.of(), "APCO25"));
        assertNotEquals(first.sourceRadio(), second.sourceRadio());
        assertEquals("v1-r-12345-001-1001", first.sourceRadio().get("identity_key"));
        assertEquals("v1-r-12345-001-1002", second.sourceRadio().get("identity_key"));
        var foreign = new DecodeEventViewService.PartyIdentifier("Text is not identity", "RADIO", "APCO25",
            new DecodeEventViewService.CanonicalRadioIdentity(0xabcde, 2, 1001), 42);
        assertEquals("v1-r-abcde-002-1001", AlertAction.capture(view(CHANNEL, SYSTEM, List.of(foreign), List.of(), "APCO25")).sourceRadio().get("identity_key"));
    }

    @Test void protocolAndSystemOwnershipMustAgreeAndDisplayTextCannotCreateIdentity()
    {
        var textOnly = new DecodeEventViewService.PartyIdentifier("Radio 42", "RADIO", "APCO25", null, null);
        assertNull(AlertAction.capture(view(CHANNEL, SYSTEM, List.of(textOnly), List.of(), "APCO25")).sourceRadio());
        assertNull(AlertAction.capture(view(CHANNEL, "dmr:channel:" + CHANNEL, List.of(radio(100, 42)), List.of(), "APCO25")).sourceRadio());
        assertNull(AlertAction.capture(view(CHANNEL, "dmr:channel:00000000-0000-4000-8000-000000000002", List.of(), List.of(), "DMR")).system());
        var conflict = view(CHANNEL, SYSTEM, List.of(radio(100, 42)), List.of(), "APCO25");
        var mismatched = new DecodeEventViewService.EventView(conflict.eventId(), CHANNEL, 1000, 10, "CALL", "Call", "calls",
            "", "", "", "", "", null, null, "", "APCO25", conflict.fromParty(), List.of(), 1, "Channel", null, null, SYSTEM,
            new DecodeEventViewService.AlertFacts(List.of(), List.of(), List.of(), "", "p25:54321:002", "", "P25"));
        assertNull(AlertAction.capture(mismatched).system());
        assertEquals("channel", AlertAction.automatic("emergency", AlertAction.capture(mismatched), true).kind());
    }

    @Test void actionReferencesRejectUrlsAdminTargetsExtraFieldsAndKindMismatch()
    {
        assertThrows(IllegalArgumentException.class, () -> new AlertAction("admin", null));
        assertThrows(IllegalArgumentException.class, () -> new AlertAction("live", Map.of("url", "/?view=admin")));
        assertThrows(IllegalArgumentException.class, () -> new AlertAction("channel", Map.of("kind", "channel", "key", CHANNEL, "url", "/?view=admin")));
        assertThrows(IllegalArgumentException.class, () -> new AlertAction("radio", WebEntityReferences.channel(CHANNEL)));
        assertThrows(IllegalArgumentException.class, () -> new AlertAction("channel", Map.of("kind", "channel", "key", "https://example.com")));
    }

    @Test void nullFactsAreNormalizedAndImpossiblePartyEvidenceCannotCreateAnIdentity()
    {
        var basic = view(CHANNEL, SYSTEM, List.of(), List.of(), "APCO25");
        var nullFacts = new DecodeEventViewService.EventView(basic.eventId(), CHANNEL, 1000, 10, "CALL", "Call", "calls",
            "", "", "", "", "", null, null, "", "APCO25", List.of(), List.of(), 1, "Channel", null, null, SYSTEM, null);
        assertNotNull(nullFacts.alertFacts(), "EventView normalizes missing facts before observer consumers");
        assertEquals("channel", AlertAction.automatic("call", AlertAction.capture(nullFacts), true).kind());
        var mismatch = new DecodeEventViewService.PartyIdentifier("Radio 42", "RADIO", "APCO25", null, null,
            List.of(), null, List.of(), false, false, new DecodeEventViewService.CanonicalGroupIdentity(0x12345, 1, 200), 42);
        assertNull(AlertAction.capture(view(CHANNEL, SYSTEM, List.of(mismatch), List.of(), "APCO25")).sourceRadio());
        var invalidWorking = radio(1001, 0xFFFFFF);
        assertNull(AlertAction.capture(view(CHANNEL, SYSTEM, List.of(invalidWorking), List.of(), "APCO25")).sourceRadio());
    }

    @Test void missingFlagsDefaultOffAndTapActionDoesNotEnableTheNewButton() throws Exception
    {
        var rule = new AlertRule("r", "Rule", "topic", true, "call", "", "", "", "", null, null, null,
            0, "rule", false, "Title", "Body", "listen");
        assertFalse(rule.actionButtonEnabled());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var old = mapper.readTree(mapper.writeValueAsString(rule)); ((com.fasterxml.jackson.databind.node.ObjectNode)old).remove("action_button_enabled");
        assertFalse(mapper.treeToValue(old, AlertRule.class).actionButtonEnabled());
        assertFalse(TopicAlertConfiguration.defaults().builtinActionButtonEnabled());
    }

    private static DecodeEventViewService.EventView view(String channel, String system, List<DecodeEventViewService.PartyIdentifier> from,
                                                         List<DecodeEventViewService.PartyIdentifier> to, String protocol)
    {
        return new DecodeEventViewService.EventView("event", channel, 1000, 10, "CALL", "Call", "calls", "", "", "", "", "",
            null, null, "", protocol, from, to, 1, "Channel", null, null, system, null);
    }
    private static DecodeEventViewService.PartyIdentifier radio(int id, int working)
    {
        return new DecodeEventViewService.PartyIdentifier("Unused label", "RADIO", "APCO25",
            new DecodeEventViewService.CanonicalRadioIdentity(0x12345, 1, id), working);
    }
    private static DecodeEventViewService.PartyIdentifier group(String form, int id)
    {
        return new DecodeEventViewService.PartyIdentifier("Unused label", form, "APCO25", null, null, List.of(), null, List.of(), false, false,
            new DecodeEventViewService.CanonicalGroupIdentity(0x12345, 1, id), id);
    }
}
