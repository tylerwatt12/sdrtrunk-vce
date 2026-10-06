/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.filter.FilterCatalog;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.channel.StandardChannel;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class DecodeEventViewServiceTest
{
    private static final String CONFIGURATION_ID = "00000000-0000-0000-0000-000000000001";
    private static final long FREQUENCY = 851_012_500L;

    @Test
    void filterCatalogIsCompleteStableAndGloballyUniqueBeforeAnyEventsArrive()
    {
        FilterCatalog catalog = DecodeEventViewService.filterCatalog();
        FilterCatalog secondRead = DecodeEventViewService.filterCatalog();
        List<String> expectedGroups = List.of("Voice Calls", "Voice Calls - Encrypted", "Data Calls",
            "Commands", "Registrations", "Other");
        List<FilterCatalog.Node> leaves = catalog.groups().stream().flatMap(group -> group.children().stream())
            .toList();
        List<String> allKeys = catalog.groups().stream()
            .flatMap(group -> java.util.stream.Stream.concat(java.util.stream.Stream.of(group.key()),
                group.children().stream().map(FilterCatalog.Node::key)))
            .toList();

        assertEquals(expectedGroups, catalog.groups().stream().map(FilterCatalog.Node::label).toList());
        assertEquals(catalog, secondRead);
        assertEquals(Set.of(), new HashSet<>(catalog.timeslots()));
        assertEquals(allKeys.size(), new HashSet<>(allKeys).size());
        assertEquals(Arrays.stream(DecodeEventType.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet()),
            leaves.stream().map(FilterCatalog.Node::key).collect(java.util.stream.Collectors.toSet()));
        assertEquals(DecodeEventType.values().length, leaves.size());
        assertTrue(leaves.stream().allMatch(leaf -> leaf.children().isEmpty()));
        assertTrue(leaves.stream().anyMatch(leaf -> leaf.key().equals("COMMAND") &&
            leaf.label().equals(DecodeEventType.COMMAND.getLabel())));
        assertTrue(catalog.groups().stream().allMatch(group -> group.key().startsWith("event-group/")));
    }

    @Test
    void keepsStableIdentityWhileProjectingEventUpdates()
    {
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL_GROUP_ENCRYPTED, 1_000L)
            .duration(250L)
            .channel(new StandardChannel(FREQUENCY))
            .details("  " + "x".repeat(600) + "  ")
            .protocol(Protocol.APCO25_PHASE2)
            .timeslot(1)
            .build();

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            DecodeEventViewService.EventView initial = service.view(CONFIGURATION_ID, event);
            event.update(1_500L);
            DecodeEventViewService.EventView updated = service.view(CONFIGURATION_ID, event);

            assertEquals(initial.eventId(), updated.eventId());
            assertEquals(250L, initial.durationMs());
            assertEquals(500L, updated.durationMs());
            assertEquals("ENCRYPTED_VOICE", updated.category());
            assertEquals(FREQUENCY, updated.frequencyHz());
            assertEquals(1, updated.timeslot());
            assertEquals("APCO25_PHASE2", updated.protocol());
            assertEquals(512, updated.details().length());
            assertTrue(updated.details().endsWith("…"));
        }
    }

    @Test
    void scopeMatchesTheConfiguredReceiverAndOptionalFrequency()
    {
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L)
            .channel(new StandardChannel(FREQUENCY))
            .timeslot(1)
            .build();

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            DecodeEventViewService.EventView view = service.view(CONFIGURATION_ID, event);

            assertTrue(new DecodeEventViewService.Scope(CONFIGURATION_ID, null, null).matches(view));
            assertTrue(new DecodeEventViewService.Scope(CONFIGURATION_ID, FREQUENCY, null).matches(view));
            assertTrue(new DecodeEventViewService.Scope(CONFIGURATION_ID, FREQUENCY, 1).matches(view));
            assertFalse(new DecodeEventViewService.Scope(CONFIGURATION_ID, FREQUENCY, 2).matches(view));
            assertFalse(new DecodeEventViewService.Scope(CONFIGURATION_ID, FREQUENCY + 1, null).matches(view));
            assertFalse(new DecodeEventViewService.Scope("other", null, null).matches(view));
        }
    }

    @Test
    void usesTheProcessingSourceFrequencyWhenTheEventHasNoChannelDescriptor()
    {
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L).build();

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            DecodeEventViewService.EventView view = service.view(CONFIGURATION_ID, event, FREQUENCY);
            assertEquals(FREQUENCY, view.frequencyHz());
            assertTrue(new DecodeEventViewService.Scope(CONFIGURATION_ID, FREQUENCY, null).matches(view));
        }
    }

    @Test
    void liveEdgeStampIsInternalAndNotSerializedToTheBrowser() throws Exception
    {
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL, 1_000L).build();

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            String json = new ObjectMapper().writeValueAsString(service.view(CONFIGURATION_ID, event));
            assertFalse(json.contains("observationEpoch"));
        }
    }

    @Test
    void projectsTypedPartiesWithoutInferringIdentityFromTheirText() throws Exception
    {
        Identifier equalWorkingRadio = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
            1_824_505, 0xBEE00, 0x49F, 1_824_505);
        Identifier differentWorkingRadio = APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(
            7001, 0xBEE00, 0x348, 1_869_911);
        Identifier group = APCO25Talkgroup.create(56832);
        Identifier qualifiedGroup = APCO25FullyQualifiedTalkgroupIdentifier.createTo(56132, 10000, 123, 56132);
        Identifier unknown = new Identifier<String>("BEE00.49F.1827273", IdentifierClass.USER, null, Role.TO)
        {
            @Override
            public Protocol getProtocol()
            {
                return Protocol.UNKNOWN;
            }
        };
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.EMERGENCY, 1_000L)
            .protocol(Protocol.APCO25)
            .identifiers(new IdentifierCollection(List.of(equalWorkingRadio, differentWorkingRadio,
                group, qualifiedGroup, unknown, differentWorkingRadio)))
            .build();

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            DecodeEventViewService.EventView view = service.view(CONFIGURATION_ID, event);
            assertEquals(equalWorkingRadio.toString(), view.fromIdentifiers());
            assertEquals(String.join(", ", differentWorkingRadio.toString(), group.toString(),
                qualifiedGroup.toString(), unknown.toString()), view.toIdentifiers());
            assertEquals(1, view.fromParty().size());
            assertEquals(4, view.toParty().size(), "the typed list shares the original text deduplication");
            assertEquals("RADIO", view.fromParty().getFirst().form());
            assertEquals("APCO25", view.fromParty().getFirst().protocol());
            assertEquals(new DecodeEventViewService.CanonicalRadioIdentity(0xBEE00, 0x49F, 1_824_505),
                view.fromParty().getFirst().canonicalIdentity());
            assertEquals(1_824_505, view.fromParty().getFirst().observedWorkingId(),
                "the projection preserves equal explicit Working ID evidence for the formatter");
            assertEquals(7001, view.toParty().getFirst().observedWorkingId());
            assertEquals(1_869_911, view.toParty().getFirst().canonicalIdentity().subscriberId());
            assertEquals("TALKGROUP", view.toParty().get(1).form());
            assertNull(view.toParty().get(1).canonicalIdentity());
            assertNull(view.toParty().get(2).canonicalIdentity());
            assertNull(view.toParty().get(3).form());
            assertNull(view.toParty().get(3).canonicalIdentity(),
                "arbitrary dotted text is never promoted into a radio identity");
            assertThrows(UnsupportedOperationException.class, () -> view.toParty().clear());

            ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
            var json = mapper.readTree(mapper.writeValueAsString(view));
            assertEquals(0xBEE00, json.path("from_party").get(0).path("canonical_identity").path("wacn").asInt());
            assertEquals(0x49F, json.path("from_party").get(0).path("canonical_identity").path("system_id").asInt());
            assertFalse(json.toString().contains("home_wacn"));
            assertFalse(json.has("observation_epoch"));
        }
    }

    @Test
    void localOrInvalidRadioEvidenceRemainsUnqualifiedAndPartyListsAreBounded()
    {
        Identifier localRadio = APCO25RadioIdentifier.createFrom(1201);
        Identifier canonicalRadio = APCO25FullyQualifiedRadioIdentifier.createFrom(0, 0xBEE00, 0x49F, 1202);
        Identifier invalidRadio = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
            1203, 0xBEE00, 0x49F, 0);
        List<Identifier> identifiers = new ArrayList<>(List.of(localRadio, canonicalRadio, invalidRadio));
        for(int index = 0; index < 40; index++)
        {
            identifiers.add(APCO25RadioIdentifier.createFrom(2000 + index));
        }
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.REGISTER, 1_000L)
            .identifiers(new IdentifierCollection(identifiers)).build();

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            DecodeEventViewService.EventView view = service.view(CONFIGURATION_ID, event);
            assertEquals(32, view.fromParty().size());
            assertNull(view.fromParty().getFirst().canonicalIdentity(),
                "ordinary local IDs are never assigned a home system by inference");
            assertNull(view.fromParty().getFirst().observedWorkingId());
            assertEquals(1202, view.fromParty().get(1).canonicalIdentity().subscriberId());
            assertNull(view.fromParty().get(1).observedWorkingId());
            assertNull(view.fromParty().get(2).canonicalIdentity(), "invalid complete identities are rejected");
            assertTrue(view.toParty().isEmpty());
            assertTrue(view.fromIdentifiers().length() <= 512);
        }
    }

    @Test
    void patchPartiesCarryDetachedTypedRadioMembersWithoutChangingRawGroupText() throws Exception
    {
        PatchGroup patch = new PatchGroup(APCO25Talkgroup.create(56132));
        patch.addPatchedTalkgroup(APCO25Talkgroup.create(91));
        patch.addPatchedRadio(APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
            1_824_505, 0xBEE00, 0x49F, 1_824_505));
        patch.addPatchedRadio(APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
            7001, 0xBEE00, 0x348, 1_869_911));
        patch.addPatchedRadio(APCO25RadioIdentifier.createFrom(1201));
        Identifier patchIdentifier = APCO25PatchGroup.create(patch);
        String rawPatch = patchIdentifier.toString();
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L)
            .protocol(Protocol.APCO25)
            .identifiers(new IdentifierCollection(List.of(patchIdentifier)))
            .details("PATCH " + rawPatch).build();

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            DecodeEventViewService.EventView view = service.view(CONFIGURATION_ID, event);
            DecodeEventViewService.PartyIdentifier party = view.toParty().getFirst();
            assertEquals(rawPatch, view.toIdentifiers());
            assertEquals(rawPatch, party.text());
            assertEquals("PATCH_GROUP", party.form());
            assertNull(party.canonicalIdentity(), "the patch itself is never presented as a radio");
            assertEquals("56132", party.patchGroup());
            assertEquals(List.of("91"), party.talkgroupMembers());
            assertFalse(party.talkgroupMembersTruncated());
            assertFalse(party.radioMembersTruncated());
            assertEquals(3, party.radioMembers().size());
            assertEquals(0x49F, party.radioMembers().get(0).canonicalIdentity().systemId());
            assertEquals(1_824_505, party.radioMembers().get(0).observedWorkingId());
            assertEquals(0x348, party.radioMembers().get(1).canonicalIdentity().systemId());
            assertEquals(7001, party.radioMembers().get(1).observedWorkingId());
            assertNull(party.radioMembers().get(2).canonicalIdentity());
            assertTrue(party.radioMembers().stream().allMatch(member ->
                "RADIO".equals(member.form()) && member.radioMembers().isEmpty()));
            assertThrows(UnsupportedOperationException.class, () -> party.radioMembers().clear());
            assertThrows(UnsupportedOperationException.class, () -> party.talkgroupMembers().clear());
            patch.getPatchedRadioIdentifiers().clear();
            patch.getPatchedTalkgroupIdentifiers().clear();
            assertEquals(3, party.radioMembers().size(), "mutable patch updates cannot change the captured projection");
            assertEquals(List.of("91"), party.talkgroupMembers());
            assertEquals("PATCH " + rawPatch, view.details());

            ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
            var json = mapper.readTree(mapper.writeValueAsString(view));
            assertEquals(0x49F, json.path("to_party").get(0).path("radio_members").get(0)
                .path("canonical_identity").path("system_id").asInt());
            assertEquals("56132", json.path("to_party").get(0).path("patch_group").asText());
            assertEquals("91", json.path("to_party").get(0).path("talkgroup_members").get(0).asText());
            assertFalse(json.path("to_party").get(0).path("talkgroup_members_truncated").asBoolean());
            assertFalse(json.path("to_party").get(0).path("radio_members_truncated").asBoolean());
        }
    }

    @Test
    void patchRadioMemberProjectionIsBoundedAndLegacyPartyConstructorHasNoMembers()
    {
        PatchGroup patch = new PatchGroup(APCO25Talkgroup.create(56132));
        for(int index = 0; index < 40; index++)
        {
            patch.addPatchedRadio(APCO25RadioIdentifier.createFrom(2000 + index));
        }
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L)
            .identifiers(new IdentifierCollection(List.of(APCO25PatchGroup.create(patch)))).build();
        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            DecodeEventViewService.PartyIdentifier party = service.view(CONFIGURATION_ID, event).toParty().getFirst();
            assertEquals(32, party.radioMembers().size());
            assertEquals("2000", party.radioMembers().getFirst().text());
            assertEquals("2031", party.radioMembers().getLast().text());
            assertTrue(party.radioMembersTruncated());
            assertFalse(party.talkgroupMembersTruncated());
            assertTrue(party.text().length() <= 512);
            assertTrue(new DecodeEventViewService.PartyIdentifier("1201", "RADIO", "APCO25", null, null)
                .radioMembers().isEmpty());
            DecodeEventViewService.PartyIdentifier capped = new DecodeEventViewService.PartyIdentifier(
                party.text(), party.form(), party.protocol(), null, null,
                java.util.Collections.nCopies(40, party.radioMembers().getFirst()));
            assertEquals(32, capped.radioMembers().size());
            assertTrue(capped.radioMembersTruncated(), "the compatibility constructor retains truncation evidence");
        }
    }

    @Test
    void structuredPatchMembersRemainCompleteWhenRawDiagnosticTextIsTruncated()
    {
        PatchGroup patch = new PatchGroup(APCO25Talkgroup.create(56132));
        for(int index = 0; index < 40; index++)
        {
            patch.addPatchedRadio(APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
                2000 + index, 0xBEE00, 0x49F, 1_824_500 + index));
            patch.addPatchedTalkgroup(APCO25Talkgroup.create(50000 + index));
        }
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L)
            .identifiers(new IdentifierCollection(List.of(APCO25PatchGroup.create(patch)))).build();
        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            DecodeEventViewService.PartyIdentifier party = service.view(CONFIGURATION_ID, event).toParty().getFirst();
            assertEquals(512, party.text().length());
            assertTrue(party.text().endsWith("…"));
            assertEquals("56132", party.patchGroup());
            assertEquals(32, party.talkgroupMembers().size());
            assertEquals("50031", party.talkgroupMembers().getLast());
            assertTrue(party.talkgroupMembersTruncated());
            assertEquals(32, party.radioMembers().size());
            assertTrue(party.radioMembersTruncated());
            assertEquals(1_824_531, party.radioMembers().getLast().canonicalIdentity().subscriberId());
            assertEquals(2031, party.radioMembers().getLast().observedWorkingId());
            assertTrue(party.radioMembers().stream().allMatch(member -> member.text().endsWith(")") &&
                member.canonicalIdentity() != null && !member.text().contains("…")),
                "normal patch labels use complete captured members rather than slicing the diagnostic string");
        }
    }

    @Test
    void blockedProjectionNeverBlocksOrProjectsOnTheDecoderCallback() throws Exception
    {
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        AtomicReference<Thread> projectionThread = new AtomicReference<>();
        DecodeEvent blocked = new DecodeEvent(DecodeEventType.CALL_GROUP, 1_000L)
        {
            @Override
            public DecodeEventType getEventType()
            {
                projectionThread.compareAndSet(null, Thread.currentThread());
                projectionEntered.countDown();

                try
                {
                    releaseProjection.await(3, TimeUnit.SECONDS);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }

                return super.getEventType();
            }
        };
        DecodeEvent ordinary = DecodeEvent.builder(DecodeEventType.CALL_GROUP, 2_000L).build();
        Channel channel = new Channel("test", Channel.ChannelType.STANDARD);
        Thread decoderThread = Thread.currentThread();

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            service.addListener(event -> { });
            service.receive(channel, blocked);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));
            long started = System.nanoTime();

            for(int x = 0; x < DecodeEventViewService.UPDATE_QUEUE_SIZE + 16; x++)
            {
                service.receive(channel, ordinary);
            }

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMs < 250, "bounded offers took " + elapsedMs + " ms");
            assertTrue(service.getDroppedObservationCount() > 0);
            assertFalse(decoderThread == projectionThread.get());
            releaseProjection.countDown();
        }
        finally
        {
            releaseProjection.countDown();
        }
    }

    @Test
    void zeroConsumersRejectIngressAndEachDemandGenerationStartsEmpty()
    {
        AtomicInteger callbacks = new AtomicInteger();
        List<Long> timestamps = new CopyOnWriteArrayList<>();
        io.github.dsheirer.sample.Listener<DecodeEventViewService.EventView> listener =
            event -> {
                timestamps.add(event.timeStartMs());
                callbacks.incrementAndGet();
            };
        Channel channel = new Channel("inactive", Channel.ChannelType.STANDARD);
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL, 1_000L).build();

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            service.getDecodeEventListener().accept(channel, event);
            assertEquals(0, service.getPendingObservationCount());
            service.addListener(listener);
            service.getDecodeEventListener().accept(channel, event);
            await(() -> callbacks.get() == 1);
            service.removeListener(listener);
            service.getDecodeEventListener().accept(channel,
                DecodeEvent.builder(DecodeEventType.CALL, 2_000L).build());
            assertEquals(0, service.getPendingObservationCount());
            assertEquals(1, callbacks.get());

            service.addListener(listener);
            assertEquals(List.of(1_000L), timestamps);
            DecodeEvent replacement = DecodeEvent.builder(DecodeEventType.CALL, 3_000L).build();
            service.getDecodeEventListener().accept(channel, replacement);
            await(() -> callbacks.get() == 2);
            assertEquals(List.of(1_000L, 3_000L), timestamps);
        }
    }

    @Test
    void blockedProjectionCloseRemainsWorkerOwnedAndCannotPublishAfterClose() throws Exception
    {
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        DecodeEvent blocked = new DecodeEvent(DecodeEventType.CALL_GROUP, 1_000L)
        {
            @Override
            public DecodeEventType getEventType()
            {
                projectionEntered.countDown();

                try
                {
                    releaseProjection.await();
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }

                return super.getEventType();
            }
        };
        DecodeEventViewService service = new DecodeEventViewService(null, null, 25, TimeUnit.MILLISECONDS);
        service.addListener(event -> callbacks.incrementAndGet());
        service.getDecodeEventListener().accept(new Channel("test", Channel.ChannelType.STANDARD), blocked);
        assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));

        service.close();
        assertFalse(service.isWorkerTerminated());
        assertEquals(0, callbacks.get());
        assertEquals(0, service.getPendingObservationCount(),
            "the worker owns and has already removed the blocked observation");

        releaseProjection.countDown();
        await(service::isWorkerTerminated);
        service.getDecodeEventListener().accept(new Channel("closed", Channel.ChannelType.STANDARD),
            DecodeEvent.builder(DecodeEventType.CALL, 2_000L).build());
        assertEquals(0, callbacks.get());
    }

    @Test
    void liveCallbackReceivesEachProjectedItemWithoutAReplayCache() throws Exception
    {
        Channel channel = new Channel("ordered", Channel.ChannelType.STANDARD);
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL, 4_000L).build();
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch callback = new CountDownLatch(1);

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            service.addListener(view -> {
                if(view.timeStartMs() == 4_000L)
                {
                    callbacks.incrementAndGet();
                }

                callback.countDown();
            });
            service.getDecodeEventListener().accept(channel, event);
            assertTrue(callback.await(2, TimeUnit.SECONDS));
            assertEquals(1, callbacks.get());
        }
    }

    private static void await(BooleanSupplier condition)
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);

        while(System.nanoTime() < deadline && !condition.getAsBoolean())
        {
            try
            {
                Thread.sleep(5);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }

        assertTrue(condition.getAsBoolean(), "condition was not met before timeout");
    }
}
