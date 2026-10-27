/* Copyright (C) 2026 Dennis Sheirer; SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.call.*;
import io.github.dsheirer.audio.output.JmbeAudioOutput;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy.ChannelKind;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.ChannelConfigurationIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.scanlist.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StatsDirectListenTest
{
    private static final String CHANNEL = "00000000-0000-0000-0000-000000000737";
    private static final String SYSTEM = "p25:bee00:4a7";

    @Test
    void validatesClosedCanonicalTargetsAndKeepsDirectOnlyRequestsIndependentOfTheDefaultList()
    {
        DirectListenTarget target = group(4400);
        assertEquals("{\"kind\":\"talkgroup\",\"radio_system_key\":\"p25:bee00:4a7\",\"identity_key\":\"v1-g-bee00-4a7-4400\"}", target.canonicalKey());
        assertEquals(target, DirectListenTarget.parse(target.canonicalKey()));
        for(String invalid: List.of("{}", "[]", target.canonicalKey() + "{}",
            "{\"kind\":\"channel\",\"key\":\"bad\"}",
            "{\"kind\":\"channel\",\"key\":\"" + CHANNEL + "\",\"timeslot\":3}",
            target.canonicalKey().replace("talkgroup", "radio"),
            target.canonicalKey().replace("v1-g", "v1-r"),
            target.canonicalKey().replace("v1-g-bee00-4a7-4400", "v1-g-x-x-16777215"),
            target.canonicalKey().replace("\"kind\":", "\"extra\":1,\"kind\":"),
            target.canonicalKey().replace("\"kind\":", "\"kind\":\"talkgroup\",\"kind\":")))
            assertThrows(IllegalArgumentException.class, () -> DirectListenTarget.parse(invalid), invalid);

        String query = directQuery(target);
        var request = StatsWebServerService.parseCallFeedRequest(URI.create(StatsApiV1.CALLS_FEED + "?" + query), model(), 16);
        assertTrue(request.scanListIds().isEmpty());
        assertEquals(Set.of(target), request.directTargets());
        var mixed = StatsWebServerService.parseCallFeedRequest(URI.create(StatsApiV1.CALLS_FEED + "?scan_list_id=2&" + query + "&" + query), model(), 16);
        assertEquals(Set.of(2L), mixed.scanListIds());
        assertEquals(Set.of(target), mixed.directTargets());
        String tooMany = java.util.stream.IntStream.rangeClosed(1, 33).mapToObj(id -> directQuery(group(id)))
            .collect(java.util.stream.Collectors.joining("&"));
        assertThrows(StatsApiException.class, () -> StatsWebServerService.parseCallFeedRequest(
            URI.create(StatsApiV1.CALLS_FEED + "?" + tooMany), model(), 16));
    }

    @Test
    void matchesEveryCapturedPatchMemberAndKeepsServingAndHomeSystemsSeparate()
    {
        PatchGroup patch = new PatchGroup(APCO25Talkgroup.create(500));
        for(int member = 1; member <= 64; member++) patch.addPatchedTalkgroup(APCO25Talkgroup.create(member));
        patch.addPatchedTalkgroup(APCO25FullyQualifiedTalkgroupIdentifier.createTo(0, 0xABCDE, 0x123, 700));
        var evidence = DirectListenTarget.evidence(call(APCO25PatchGroup.create(patch), Set.of(), false, 0));
        assertTrue(group(64).matches(evidence), "Matching must use full frozen membership, beyond browser metadata's 32-member cap");
        assertFalse(group(65).matches(evidence));
        assertTrue(new DirectListenTarget("patch_group", null, SYSTEM, "v1-p-bee00-4a7-500", null).matches(evidence));
        assertTrue(new DirectListenTarget("talkgroup", null, SYSTEM, "v1-g-abcde-123-700", null).matches(evidence));
        assertFalse(new DirectListenTarget("talkgroup", null, "p25:abcde:123", "v1-g-abcde-123-700", null).matches(evidence));
        assertFalse(new DirectListenTarget("talkgroup", null, SYSTEM, "v1-g-bee00-4a7-700", null).matches(evidence));
    }

    @Test
    void conventionalGroupTargetsCanSpanSlotsOrSelectOneWithoutIncludingOtherGroups()
    {
        var evidence = DirectListenTarget.evidence(call(DMRTalkgroup.create(120), Set.of(), true, 2));
        assertTrue(new DirectListenTarget("channel", CHANNEL, null, null, null).matches(evidence));
        assertTrue(new DirectListenTarget("channel", CHANNEL, null, "v1-g-x-x-120", null).matches(evidence));
        assertTrue(new DirectListenTarget("channel", CHANNEL, null, "v1-g-x-x-120", 2).matches(evidence));
        assertFalse(new DirectListenTarget("channel", CHANNEL, null, "v1-g-x-x-120", 1).matches(evidence));
        assertFalse(new DirectListenTarget("channel", CHANNEL, null, "v1-g-x-x-121", null).matches(evidence));
        assertFalse(new DirectListenTarget("channel", "00000000-0000-0000-0000-000000000738", null, null, null).matches(evidence));
        assertFalse(new DirectListenTarget("channel", CHANNEL, null, null, null)
            .matches(DirectListenTarget.evidence(call(APCO25Talkgroup.create(120), Set.of(), false, 0))));
        var conventionalP25 = call(APCO25Talkgroup.create(120), Set.of(), false, 0);
        assertFalse(new DirectListenTarget("channel", CHANNEL, null, "v1-g-x-x-16777215", null)
            .matches(DirectListenTarget.evidence(conventionalP25)));
    }

    @Test
    void preparesOnlyPublishedOrActivelyRequestedCallsAndUnionsWithoutLeakingToOtherFeeds() throws Exception
    {
        AtomicInteger renders = new AtomicInteger();
        AtomicReference<Thread> rendererThread = new AtomicReference<>();
        try(StatsWebCallService service = new StatsWebCallService(model(), null, call -> {
            rendererThread.set(Thread.currentThread());
            renders.incrementAndGet();
            return new JmbeAudioOutput.RenderedAudio(call.audioBuffers(), 8000);
        }, ignored -> null))
        {
            service.start();
            long generation = service.tryAcquireFeed();
            long edge = Long.parseLong(service.feed(Set.of(), null, 0, TimeUnit.NANOSECONDS, generation).cursor());
            assertTrue(service.refreshDirectInterests(Set.of(group(4400))));
            service.receive(call(APCO25Talkgroup.create(4400), Set.of(), false, 0));
            awaitPublished(service, 1);
            service.receive(call(APCO25Talkgroup.create(4401), Set.of(), false, 0));
            service.receive(call(APCO25Talkgroup.create(4402), Set.of(101L), false, 0));
            awaitPublished(service, 2);
            assertEquals(2, renders.get(), "Unrequested calls outside published lists must not be rendered");
            assertNotEquals(Thread.currentThread(), rendererThread.get());
            var direct = service.feed(Set.of(), Set.of(group(4400)), edge, 0, TimeUnit.NANOSECONDS, generation);
            assertEquals(1, direct.calls().size());
            assertEquals(List.of(), direct.calls().getFirst().get("scan_list_ids"));
            assertEquals(List.of(group(4400).canonicalKey()), direct.calls().getFirst().get("direct_target_keys"));
            var normal = service.feed(Set.of(2L), edge, 0, TimeUnit.NANOSECONDS, generation);
            assertEquals(1, normal.calls().size());
            assertEquals("4402", normal.calls().getFirst().get("target_id"));
            assertTrue(service.feed(Set.of(), edge, 0, TimeUnit.NANOSECONDS, generation).calls().isEmpty());
            var union = service.feed(Set.of(2L), Set.of(group(4400), group(4402)), edge, 0, TimeUnit.NANOSECONDS, generation);
            assertEquals(2, union.calls().size());
            assertNotNull(service.get(String.valueOf(direct.calls().getFirst().get("call_id"))));
            service.releaseFeed(generation);
        }
    }

    @Test
    void sharedInterestLeasesAreBoundedDeduplicatedAndExpireWithoutSleeping()
    {
        try(StatsWebCallService service = new StatsWebCallService(model()))
        {
            long now = System.nanoTime();
            for(int offset = 0; offset < StatsWebCallService.MAXIMUM_DIRECT_INTERESTS; offset += 32)
            {
                Set<DirectListenTarget> targets = new LinkedHashSet<>();
                for(int index = offset + 1; index <= offset + 32; index++) targets.add(group(index));
                assertTrue(service.refreshDirectInterests(targets, now));
            }
            assertTrue(service.refreshDirectInterests(Set.of(group(1)), now));
            assertFalse(service.refreshDirectInterests(Set.of(group(1000)), now));
            assertTrue(service.refreshDirectInterests(Set.of(group(1000)), now + StatsWebCallService.DIRECT_INTEREST_LEASE_NANOS));
        }
    }

    static DirectListenTarget group(int id)
    {
        return new DirectListenTarget("talkgroup", null, SYSTEM, "v1-g-bee00-4a7-" + id, null);
    }

    static String directQuery(DirectListenTarget target)
    {
        return "direct_target=" + URLEncoder.encode(target.canonicalKey(), StandardCharsets.UTF_8);
    }

    private static ScanListModel model()
    {
        ScanListModel model = new ScanListModel();
        model.replaceConfiguration(new ScanListConfiguration(List.of(
            new ScanList(1, 0, "Default", null, true, true),
            new ScanList(2, 1, "Dispatch", null, true, false)), Map.of(101L, Set.of(2L)), Map.of()));
        return model;
    }

    static CompletedAudioCall call(Identifier<?> target, Set<Long> aliases, boolean conventional, int timeslot)
    {
        AudioCallId id = new AudioCallId(1, 2, timeslot);
        var identifiers = new IdentifierCollection(List.of(ChannelConfigurationIdentifier.create(CHANNEL), target));
        CallLegSource source = new CallLegSource(conventional ? DecoderType.DMR : DecoderType.P25_PHASE1,
            CHANNEL, "Dispatch", null, 0, conventional ? null : new P25SiteIdentity(0xBEE00, 0x4A7, 1, 21),
            TrunkedIdentityDomain.STANDARD, conventional ? ChannelKind.CONVENTIONAL : ChannelKind.TRUNKED, !conventional);
        var snapshot = new AudioCallSnapshot(id, null, null, identifiers, Set.of(), 1000, 1100, 1, 1, 1000, 1100,
            false, true, CallEncryptionState.CLEAR, false, null, VoiceCallQuality.EMPTY, CallLegId.from(id), source, null);
        ResolvedCallPolicy captured = ResolvedCallPolicy.capture(snapshot);
        var original = captured.matchContexts().getFirst();
        var context = new ResolvedCallPolicy.MatchContext(CHANNEL, 0, source.channelKind(), original.destinationIdentities(),
            aliases, AliasList.TalkgroupMatchStatus.NOT_APPLICABLE, false, false, Set.of());
        return new CompletedAudioCall(snapshot, List.of(new float[800]), new ResolvedCallPolicy(false, false, Set.of(), List.of(context)));
    }

    private static void awaitPublished(StatsWebCallService service, long expected) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while(((Number)service.observerStatus().get("published_calls")).longValue() < expected && System.nanoTime() < deadline)
            Thread.sleep(5);
        assertEquals(expected, ((Number)service.observerStatus().get("published_calls")).longValue());
    }
}
