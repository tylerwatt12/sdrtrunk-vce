/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.channel.ChannelDefinition;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.dmr.message.data.SlotType;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.CSBKMessage;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.Opcode;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.hytera.HyteraAdjacentSiteInformation;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.hytera.HyteraAloha;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.hytera.HyteraAnnouncement;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.CapacityMaxAloha;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.Aloha;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.announcement.AdjacentSiteInformation;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.announcement.AnnounceChannelFrequency;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.announcement.AnnounceWithdrawTSCC;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.grant.TalkgroupVoiceChannelGrant;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.ActivityUpdateMessage;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.CapacityPlusRestChannel;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.ConnectPlusControlChannel;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.ControlChannelSystemParameters;
import io.github.dsheirer.module.decode.dmr.message.data.mbc.MBCContinuationBlock;
import io.github.dsheirer.module.decode.dmr.message.type.AnnouncementType;
import io.github.dsheirer.module.decode.dmr.message.type.Model;
import io.github.dsheirer.module.decode.dmr.message.type.SystemIdentityCode;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import io.github.dsheirer.module.decode.nxdn.layer2.LICH;
import io.github.dsheirer.module.decode.nxdn.layer3.NXDNMessageType;
import io.github.dsheirer.module.decode.nxdn.layer3.broadcast.SiteInformation;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.SiteID;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.SourceEvent;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongFunction;
import org.junit.jupiter.api.Test;

/** Synthetic decoded messages exercise trust decisions; these tests do not claim IQ decoding coverage. */
class DigitalTrunkedDiscoveryProbeTest
{
    private static final long FREQUENCY = 450_000_000;

    @Test void syncLossNotificationsNeverCountAsCrcValidProtocolMessages()
    {
        var dmr = new DigitalTrunkedDiscoveryProbe.ModeEvidence("DMR",FREQUENCY);
        var nxdn = new DigitalTrunkedDiscoveryProbe.ModeEvidence("M4800",FREQUENCY);
        for(int index=0;index<100;index++)
        {
            var loss = new io.github.dsheirer.message.SyncLossMessage(index*100,1_000,io.github.dsheirer.protocol.Protocol.NXDN);
            dmr.receive(loss); nxdn.receive(loss);
        }
        assertEquals(0,dmr.metrics().validMessages()); assertEquals(0,nxdn.metrics().validMessages());
        assertEquals(0,dmr.metrics().validControlMessages()); assertNull(dmr.confirmed(30_000)); assertNull(nxdn.confirmed(30_000));
    }

    @Test void confirmsDmrTierThreeNativeSystem()
    {
        var proof = confirmed("DMR", time -> tierThree(257, 5, time));
        assertEquals("TIER_III", proof.variant());
        assertEquals("dmr:tier3:tiny:257", proof.identity().radioSystemKey());
        assertEquals(5, proof.identity().site());
        assertEquals(false, proof.settings().get("ignore_crc_checksums"));
    }

    @Test void standardAdjacentAnnouncementsConfirmCurrentOwnIdentityRatherThanNeighbor()
    {
        assertCurrentOwnIdentity("TIER_III", (network, site, time) -> adjacent(false, network, site, 258, 6, time));
    }

    @Test void standardAlohaConfirmsCurrentOwnIdentity()
    {
        assertCurrentOwnIdentity("TIER_III", DigitalTrunkedDiscoveryProbeTest::standardAloha);
    }

    @Test void motorolaCapacityMaxAlohaConfirmsCurrentOwnIdentity()
    {
        assertCurrentOwnIdentity("CAPACITY_MAX", DigitalTrunkedDiscoveryProbeTest::capacityMaxAloha);
    }

    @Test void standardFrequencyAnnouncementsConfirmCurrentOwnIdentity()
    {
        assertCurrentOwnIdentity("TIER_III", (network, site, time) -> announcement(844, network, site, time));
    }

    @Test void standardTsccAnnouncementsConfirmCurrentOwnIdentityWithoutInventingMap()
    {
        var proof = assertCurrentOwnIdentity("TIER_III", DigitalTrunkedDiscoveryProbeTest::withdrawTscc);
        assertTrue(proof.frequencyMap().isEmpty(), "A bare channel number supplies no frequency");
    }

    @Test void hyteraAnnouncementsConfirmCurrentOwnIdentity()
    {
        assertCurrentOwnIdentity("HYTERA_TIER_III", DigitalTrunkedDiscoveryProbeTest::hyteraAnnouncement);
    }

    @Test void hyteraAdjacentAnnouncementsConfirmCurrentOwnIdentityRatherThanNeighbor()
    {
        assertCurrentOwnIdentity("HYTERA_TIER_III", (network, site, time) -> adjacent(true, network, site, 258, 6, time));
    }

    @Test void hyteraAlohaConfirmsCurrentOwnIdentity()
    {
        assertCurrentOwnIdentity("HYTERA_TIER_III", DigitalTrunkedDiscoveryProbeTest::hyteraAloha);
    }

    @Test void changingAdjacentNeighborsCannotChangeOrInvalidateTheServingIdentity()
    {
        for(boolean hytera: List.of(false, true))
        {
            var evidence = new DigitalTrunkedDiscoveryProbe.ModeEvidence("DMR", FREQUENCY);
            confirm(evidence, time -> adjacent(hytera, 257, 5, 258, 6, time));
            evidence.receive(adjacent(hytera, 257, 5, 259, 7, 4_000));
            var proof = evidence.confirmed(5_000);
            assertNotNull(proof);
            assertEquals("dmr:tier3:tiny:257", proof.identity().radioSystemKey());
            assertEquals(5, proof.identity().site());
            assertTrue(proof.frequencyMap().isEmpty(), "Unresolved neighbor carriers must not become an own-site map");
        }
    }

    @Test void crcInvalidAnnouncementsAndConflictingCurrentOwnSicNeverConfirm()
    {
        List<OwnAnnouncementFactory> factories = List.of(
            DigitalTrunkedDiscoveryProbeTest::standardAloha,
            DigitalTrunkedDiscoveryProbeTest::capacityMaxAloha,
            (network, site, time) -> adjacent(false, network, site, 258, 6, time),
            (network, site, time) -> announcement(844, network, site, time),
            DigitalTrunkedDiscoveryProbeTest::withdrawTscc,
            DigitalTrunkedDiscoveryProbeTest::hyteraAnnouncement,
            (network, site, time) -> adjacent(true, network, site, 258, 6, time),
            DigitalTrunkedDiscoveryProbeTest::hyteraAloha);
        for(var factory: factories)
        {
            var invalid = new DigitalTrunkedDiscoveryProbe.ModeEvidence("DMR", FREQUENCY);
            for(int index = 1; index <= 20; index++)
            {
                var message = factory.create(257, 5, index * 1_000);
                message.setValid(false);
                invalid.receive(message);
            }
            assertNull(invalid.identity);
            assertEquals(20, invalid.invalid);
            assertNull(invalid.confirmed(25_000));
            var conflict = new DigitalTrunkedDiscoveryProbe.ModeEvidence("DMR", FREQUENCY);
            confirm(conflict, time -> factory.create(257, 5, time));
            assertNotNull(conflict.confirmed(5_000));
            conflict.receive(factory.create(259, 5, 6_000));
            assertNull(conflict.confirmed(7_000), "A conflicting own SIC cannot reuse the older serving identity");
        }
    }

    @Test void confirmsConnectPlusWithoutClaimingGlobalNativeIdentity()
    {
        var proof = confirmed("DMR", time -> connectPlus(12, 34, time));
        assertEquals("CONNECT_PLUS", proof.variant());
        assertEquals(12, proof.identity().network());
        assertEquals(34, proof.identity().site());
        assertNull(proof.identity().radioSystemKey());
        assertTrue(proof.identity().siteKey().contains(":frequency:" + FREQUENCY));
    }

    @Test void confirmsCapacityPlusRestChannelWithoutInventingItsFrequency()
    {
        var proof = confirmed("DMR", time -> capacityPlus(7, time));
        assertEquals("CAPACITY_PLUS", proof.variant());
        assertEquals(7, proof.identity().site());
        assertNull(proof.identity().radioSystemKey());
        assertTrue(proof.frequencyMap().isEmpty());
    }

    @Test void confirmsNxdnTypeC4800NativeSystem() { assertTypeC("M4800"); }
    @Test void confirmsNxdnTypeC9600NativeSystem() { assertTypeC("M9600"); }

    @Test void confirmsNxdnTypeDWithoutClaimingGlobalNativeIdentity()
    {
        var proof = confirmed("TYPE_D", time -> typeD(7, time));
        assertEquals("TYPE_D", proof.variant());
        assertEquals(7, proof.identity().site());
        assertNull(proof.identity().radioSystemKey());
        assertTrue(proof.identity().siteKey().contains(":frequency:" + FREQUENCY));
        assertEquals("TYPE_D", proof.settings().get("transmission_mode"));
        assertTrue(proof.frequencyMap().isEmpty());
    }

    @Test void duplicateTimestampsDoNotReplaceThreeFreshServingObservationsAcrossTwoSeconds()
    {
        var evidence = new DigitalTrunkedDiscoveryProbe.ModeEvidence("DMR", FREQUENCY);
        for(int index = 0; index < 30; index++) evidence.receive(tierThree(257, 5, 1_000));
        assertEquals(1, evidence.observations);
        assertNull(evidence.confirmed(5_000));
        evidence.receive(tierThree(257, 5, 2_000));
        evidence.receive(tierThree(257, 5, 2_999));
        assertNull(evidence.confirmed(5_000), "A 1,999 ms identity span is insufficient");
        evidence.receive(tierThree(257, 5, 3_000));
        assertNotNull(evidence.confirmed(5_000));
    }

    @Test void weakCrcInvalidAndPoorQualitySignalsNeverBecomeVerified()
    {
        var weak = new DigitalTrunkedDiscoveryProbe.ModeEvidence("DMR", FREQUENCY);
        for(int index = 1; index <= 3; index++) weak.receive(tierThree(257, 5, index * 1_000));
        assertNull(weak.confirmed(5_000), "Repeated identity alone is insufficient without enough control units");
        var invalid = new DigitalTrunkedDiscoveryProbe.ModeEvidence("DMR", FREQUENCY);
        for(int index = 1; index <= 30; index++)
        {
            var message = tierThree(257, 5, index * 1_000);
            message.setValid(false);
            invalid.receive(message);
        }
        assertNull(invalid.identity);
        assertEquals(30, invalid.invalid);
        assertNull(invalid.confirmed(35_000));
        var poor = new DigitalTrunkedDiscoveryProbe.ModeEvidence("DMR", FREQUENCY);
        confirm(poor, time -> tierThree(257, 5, time));
        for(int index = 0; index < 30; index++)
        {
            var message = grant(802, 6_000 + index);
            message.setValid(false);
            poor.receive(message);
        }
        assertNull(poor.confirmed(7_000), "Control quality below 60 percent is insufficient");

        var invalidNxdn = new DigitalTrunkedDiscoveryProbe.ModeEvidence("M4800", FREQUENCY);
        for(int index = 1; index <= 30; index++)
        {
            var message = typeC(341, 837, 12, index * 1_000, LICH.RCCH_OUTBOUND_SINGLE_CAC_NORMAL);
            message.setValid(false);
            invalidNxdn.receive(message);
        }
        assertNull(invalidNxdn.identity);
        assertEquals(30, invalidNxdn.invalid);
        assertNull(invalidNxdn.confirmed(35_000));
    }

    @Test void conventionalDmrActivityAndNxdnRdchDoNotClaimTrunkedServingIdentity()
    {
        var dmr = new DigitalTrunkedDiscoveryProbe.ModeEvidence("DMR", FREQUENCY);
        var nxdn = new DigitalTrunkedDiscoveryProbe.ModeEvidence("M4800", FREQUENCY);
        for(int index = 1; index <= 30; index++)
        {
            var bits = new CorrectedBinaryMessage(32);
            bits.load(0, 4, 1);
            dmr.receive(new ActivityUpdateMessage(bits, index * 1_000, 1));
            nxdn.receive(typeC(341, 837, 12, index * 1_000, LICH.RDCH_OUTBOUND_SINGLE_FACCH1_FACCH1));
        }
        assertNull(dmr.identity);
        assertNull(nxdn.identity);
        assertNull(dmr.confirmed(35_000));
        assertNull(nxdn.confirmed(35_000));
    }

    @Test void conflictingNativeIdentityOrRanInvalidatesProofUntilReset()
    {
        var dmr = new DigitalTrunkedDiscoveryProbe.ModeEvidence("DMR", FREQUENCY);
        confirm(dmr, time -> tierThree(257, 5, time));
        assertNotNull(dmr.confirmed(5_000));
        dmr.receive(tierThree(258, 5, 6_000));
        assertNull(dmr.confirmed(7_000));
        dmr.reset();
        confirm(dmr, time -> tierThree(258, 5, time));
        assertNotNull(dmr.confirmed(7_000));

        var nxdn = new DigitalTrunkedDiscoveryProbe.ModeEvidence("M4800", FREQUENCY);
        confirm(nxdn, time -> typeC(341, 837, 12, time, LICH.RCCH_OUTBOUND_SINGLE_CAC_NORMAL));
        assertNotNull(nxdn.confirmed(5_000));
        nxdn.receive(typeC(341, 837, 13, 6_000, LICH.RCCH_OUTBOUND_SINGLE_CAC_NORMAL));
        assertNull(nxdn.confirmed(7_000));
        nxdn.reset();
        confirm(nxdn, time -> typeC(341, 837, 13, time, LICH.RCCH_OUTBOUND_SINGLE_CAC_NORMAL));
        assertEquals(13, nxdn.confirmed(7_000).identity().ran());
    }

    @Test void localVariantSiteNumbersAtDifferentFrequenciesRemainSeparate()
    {
        for(String mode: List.of("DMR", "TYPE_D"))
        {
            LongFunction<IMessage> factory = "DMR".equals(mode) ? time -> capacityPlus(7, time) : time -> typeD(7, time);
            var first = new DigitalTrunkedDiscoveryProbe.ModeEvidence(mode, FREQUENCY);
            var second = new DigitalTrunkedDiscoveryProbe.ModeEvidence(mode, FREQUENCY + 1_000_000);
            confirm(first, factory);
            confirm(second, factory);
            assertNotEquals(first.confirmed(5_000).identity().siteKey(), second.confirmed(5_000).identity().siteKey());
        }
    }

    @Test void includesOnlyResolvedOverTheAirDmrMaps()
    {
        var evidence = new DigitalTrunkedDiscoveryProbe.ModeEvidence("DMR", FREQUENCY);
        confirm(evidence, time -> tierThree(257, 5, time));
        assertTrue(evidence.confirmed(5_000).frequencyMap().isEmpty(), "Bare LCN grants have no reliable frequency");
        evidence.receive(announcement(844, 6_000));
        evidence.receive(announcement(844, 11_000));
        assertEquals(List.of(new ChannelDefinition.FrequencyMapEntry(844, 140_043_750, 150_043_750)),
            evidence.confirmed(12_000).frequencyMap());
    }

    @Test void blockedConsumerAndFullQueueNeverRunDecoderOnProducerAndGapClearsProof() throws Exception
    {
        var source = new FakeSource();
        var clock = new AtomicLong(1_000);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var resets = new AtomicInteger();
        var threads = new CopyOnWriteArrayList<String>();
        try(var probe = new DigitalTrunkedDiscoveryProbe((target, frequency) -> source,
            (mode, rate, messages) -> new DigitalTrunkedDiscoveryProbe.ProbeDecoder()
            {
                public void receive(ComplexSamples samples)
                {
                    threads.add(Thread.currentThread().getName());
                    if(calls.incrementAndGet() == 1)
                    {
                        messages.receive(tierThree(257, 5, 1_000));
                        entered.countDown();
                        waitLatch(release);
                    }
                }
                public void reset() { resets.incrementAndGet(); }
                public void close() { }
            }, clock::get))
        {
            var session = probe.open("recording", FREQUENCY, "dmr");
            source.emit(samples(1_000));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                for(int index = 0; index < 4_096; index++) source.emit(samples(2_000 + index));
            });
            assertEquals(1, calls.get(), "Overflow must never call the blocked decoder from the sample callback");
            release.countDown();
            await(() -> calls.get() >= 9);
            source.emit(samples(10_000));
            await(() -> resets.get() == 1);
            clock.set(1_000 + DigitalTrunkedDiscoveryProbe.TIMEOUT_MS);
            await(() -> "inconclusive".equals(session.status().state()));
            assertNull(session.status().evidence());
            assertTrue(session.status().droppedBuffers() >= 4_088);
            assertTrue(threads.stream().allMatch("digital-trunked-discovery"::equals));
            await(() -> source.closed.get() == 1);
        }
        finally { release.countDown(); }
    }

    @Test void cancellationReleasesSourceOnceAndAllowsRetryDuringSlowDecode() throws Exception
    {
        var first = new FakeSource();
        var second = new FakeSource();
        var sources = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try(var probe = new DigitalTrunkedDiscoveryProbe((target, frequency) -> sources.getAndIncrement() == 0 ? first : second,
            (mode, rate, messages) -> decoder(samples -> { entered.countDown(); waitLatch(release); }), System::currentTimeMillis))
        {
            var session = probe.open("recording", FREQUENCY, "dmr");
            first.emit(samples(1_000));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            session.close();
            session.close();
            assertEquals("closed", session.status().state());
            assertFalse(session.valid());
            assertNull(session.status().evidence());
            assertEquals(1, first.closed.get());
            var next = probe.open("recording", FREQUENCY, "dmr");
            next.close();
            assertEquals(1, second.closed.get());
        }
        finally { release.countDown(); }
    }

    @Test void upstreamDropDuringDecodeRejectsOtherwiseConfirmedProof() throws Exception
    {
        var source = new FakeSource();
        var clock = new AtomicLong(1_000);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try(var probe = new DigitalTrunkedDiscoveryProbe((target, frequency) -> source,
            (mode, rate, messages) -> decoder(samples -> {
                sendConfirmed(messages, time -> tierThree(257, 5, time));
                entered.countDown();
                waitLatch(release);
            }), clock::get))
        {
            var session = probe.open("recording", FREQUENCY, "dmr");
            clock.set(5_000);
            source.emit(samples(1_000));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            source.upstreamDrops.incrementAndGet();
            release.countDown();
            await(() -> "failed".equals(session.status().state()));
            assertNull(session.status().evidence());
            await(() -> source.closed.get() == 1);
        }
        finally { release.countDown(); }
    }

    @Test void equalQualityNxdnRatesForTheSameSiteRemainInconclusive() throws Exception
    {
        assertNxdnModeAmbiguity(false, "too close");
    }

    @Test void differentConfirmedNxdnSitesRemainInconclusive() throws Exception
    {
        assertNxdnModeAmbiguity(true, "disagree");
    }

    @Test void duplicateTypeCFramesInTypeDDecoderDoNotCreatePhysicalRateAmbiguity() throws Exception
    {
        assertCanonicalNxdnSubtype(false);
    }

    @Test void duplicateTypeDFramesInTypeCDecoderKeepConservativeTypeDProof() throws Exception
    {
        assertCanonicalNxdnSubtype(true);
    }

    @Test void nxdnSubtypeAliasesDoNotCountControlOrServingIdentityInWrongMode()
    {
        var typeDMode = new DigitalTrunkedDiscoveryProbe.ModeEvidence("TYPE_D", FREQUENCY);
        confirm(typeDMode, time -> typeC(341, 837, 12, time, LICH.RCCH_OUTBOUND_SINGLE_CAC_NORMAL));
        assertEquals(20, typeDMode.metrics().validMessages(), "Nominal decoding telemetry remains visible");
        assertEquals(0, typeDMode.metrics().validControlMessages());
        assertEquals(0, typeDMode.metrics().identityObservations());
        assertNull(typeDMode.confirmed(5_000));
        for(String mode: List.of("M4800", "M9600"))
        {
            var typeCMode = new DigitalTrunkedDiscoveryProbe.ModeEvidence(mode, FREQUENCY);
            confirm(typeCMode, time -> typeD(7, time));
            assertEquals(20, typeCMode.metrics().validMessages());
            assertEquals(0, typeCMode.metrics().validControlMessages());
            assertEquals(0, typeCMode.metrics().identityObservations());
            assertNull(typeCMode.confirmed(5_000));
        }
    }

    @Test void invalidOppositeNxdnSubtypeControlsStillRejectPoorQuality()
    {
        for(String mode: List.of("M4800", "M9600", "TYPE_D"))
        {
            boolean typeD = "TYPE_D".equals(mode);
            var evidence = new DigitalTrunkedDiscoveryProbe.ModeEvidence(mode, FREQUENCY);
            confirm(evidence, time -> typeD ? typeD(7, time) :
                typeC(341, 837, 12, time, LICH.RCCH_OUTBOUND_SINGLE_CAC_NORMAL));
            assertNotNull(evidence.confirmed(5_000));
            for(int index = 0; index < 30; index++)
            {
                var invalid = typeD ? typeC(341, 837, 12, 4_000 + index, LICH.RCCH_OUTBOUND_SINGLE_CAC_NORMAL) : typeD(7, 4_000 + index);
                invalid.setValid(false);
                evidence.receive(invalid);
            }
            assertEquals(20, evidence.metrics().validControlMessages());
            assertEquals(30, evidence.metrics().invalidControlMessages(), "An invalid subtype cannot justify removing control errors");
            assertEquals(40.0, evidence.metrics().qualityPct());
            assertEquals(3, evidence.metrics().identityObservations(), "Invalid controls must not alter trusted identity");
            assertNull(evidence.confirmed(6_000), "Prior 60% quality threshold must still reject damaged signals");
        }
    }

    @Test void readyProofLosingItsSourceReservationReleasesHoldAndAllowsRetry() throws Exception
    {
        var first = new FakeSource();
        var second = new FakeSource();
        var sources = new AtomicInteger();
        var clock = new AtomicLong(1_000);
        try(var probe = new DigitalTrunkedDiscoveryProbe((target, frequency) -> sources.getAndIncrement() == 0 ? first : second,
            (mode, rate, messages) -> decoder(samples -> sendConfirmed(messages, time -> tierThree(257, 5, time))), clock::get))
        {
            var session = probe.open("recording", FREQUENCY, "dmr");
            clock.set(5_000);
            first.emit(samples(1_000));
            await(() -> "ready".equals(session.status().state()));
            assertEquals(0, first.closed.get(), "A ready result retains the source reservation for review");
            first.reservationValid = false;
            assertEquals("failed", session.status().state());
            assertNull(session.status().evidence());
            assertFalse(session.valid());
            assertEquals(1, first.closed.get(), "Stale ready proof must release its held source");
            var next = probe.open("recording", FREQUENCY, "dmr");
            next.close();
            assertEquals(1, second.closed.get());
        }
    }

    private static void assertNxdnModeAmbiguity(boolean differentSites, String expectedReason) throws Exception
    {
        var source = new FakeSource();
        var clock = new AtomicLong(1_000);
        try(var probe = new DigitalTrunkedDiscoveryProbe((target, frequency) -> source,
            (mode, rate, messages) -> decoder(samples -> {
                if("M4800".equals(mode) || "M9600".equals(mode))
                {
                    int system = differentSites && "M9600".equals(mode) ? 342 : 341;
                    sendConfirmed(messages, time -> typeC(system, 837, 12, time, LICH.RCCH_OUTBOUND_SINGLE_CAC_NORMAL));
                }
            }), clock::get))
        {
            var session = probe.open("recording", FREQUENCY, "nxdn");
            clock.set(5_000);
            source.emit(samples(1_000));
            await(() -> "inconclusive".equals(session.status().state()));
            assertTrue(session.status().reason().contains(expectedReason));
            assertNull(session.status().evidence());
            assertEquals(2, session.status().modes().stream().filter(mode -> mode.validControlMessages() >= 20).count());
            await(() -> source.closed.get() == 1);
        }
    }

    private static void assertCanonicalNxdnSubtype(boolean typeD) throws Exception
    {
        var source = new FakeSource();
        var clock = new AtomicLong(1_000);
        try(var probe = new DigitalTrunkedDiscoveryProbe((target, frequency) -> source,
            (mode, rate, messages) -> decoder(samples -> {
                if("M4800".equals(mode) || "TYPE_D".equals(mode))
                    sendConfirmed(messages, time -> typeD ? typeD(7, time) :
                        typeC(341, 837, 12, time, LICH.RCCH_OUTBOUND_SINGLE_CAC_NORMAL));
            }), clock::get))
        {
            var session = probe.open("recording", FREQUENCY, "nxdn");
            clock.set(5_000);
            source.emit(samples(1_000));
            await(() -> "ready".equals(session.status().state()));
            var proof = session.status().evidence();
            assertNotNull(proof);
            assertEquals(typeD ? "TYPE_D" : "TYPE_C", proof.variant());
            assertEquals(typeD ? "TYPE_D" : "M4800", proof.settings().get("transmission_mode"));
            assertEquals(1, session.status().modes().stream().filter(mode -> mode.validControlMessages() >= 20).count());
            if(typeD) assertNull(proof.identity().radioSystemKey(), "Type-D SiteID remains frequency-scoped");
            else assertEquals("nxdn-c:global:341", proof.identity().radioSystemKey());
            session.close();
            assertEquals(1, source.closed.get());
        }
    }

    private static void assertTypeC(String mode)
    {
        var proof = confirmed(mode, time -> typeC(341, 837, 12, time, LICH.RCCH_OUTBOUND_SINGLE_CAC_NORMAL));
        assertEquals("TYPE_C", proof.variant());
        assertEquals("nxdn-c:global:341", proof.identity().radioSystemKey());
        assertEquals(837, proof.identity().site());
        assertEquals(12, proof.identity().ran());
        assertEquals(mode, proof.settings().get("transmission_mode"));
        assertTrue(proof.frequencyMap().isEmpty());
    }

    private static TrunkedDiscoveryEvidence assertCurrentOwnIdentity(String variant, OwnAnnouncementFactory factory)
    {
        var proof = confirmed("DMR", time -> factory.create(257, 5, time));
        assertEquals(variant, proof.variant());
        assertEquals("dmr:tier3:tiny:257", proof.identity().radioSystemKey());
        assertEquals(257, proof.identity().network());
        assertEquals(5, proof.identity().site());
        assertEquals("TINY", proof.identity().model());
        return proof;
    }

    private static TrunkedDiscoveryEvidence confirmed(String mode, LongFunction<IMessage> factory)
    {
        var evidence = new DigitalTrunkedDiscoveryProbe.ModeEvidence(mode, FREQUENCY);
        confirm(evidence, factory);
        var proof = evidence.confirmed(5_000);
        assertNotNull(proof, mode + " should confirm repeated valid serving identity");
        assertTrue(proof.verified());
        return proof;
    }

    private static void confirm(DigitalTrunkedDiscoveryProbe.ModeEvidence evidence, LongFunction<IMessage> factory)
    { sendConfirmed(evidence::receive, factory); }

    private static void sendConfirmed(Listener<IMessage> messages, LongFunction<IMessage> factory)
    {
        for(int index = 1; index <= 3; index++) messages.receive(factory.apply(index * 1_000));
        //Repeated packets count toward control quality, but not fresh identity observations.
        for(int index = 0; index < 17; index++) messages.receive(factory.apply(3_000));
    }

    //Field builders follow the existing DMR/NXDN monitor tests. Validity represents CRC success from the decoder.
    private static ControlChannelSystemParameters tierThree(int network, int site, long timestamp)
    {
        var bits = new CorrectedBinaryMessage(32);
        bits.load(0, 4, 2);
        bits.load(6, 9, network);
        bits.load(15, 3, site);
        var result = new ControlChannelSystemParameters(bits, timestamp, 1);
        result.setValid(true);
        return result;
    }

    private static ConnectPlusControlChannel connectPlus(int network, int site, long timestamp)
    {
        var bits = new CorrectedBinaryMessage(32);
        bits.load(0, 4, 10);
        bits.load(4, 12, network);
        bits.load(16, 8, site);
        var result = new ConnectPlusControlChannel(bits, timestamp, 1);
        result.setValid(true);
        return result;
    }

    private static CapacityPlusRestChannel capacityPlus(int site, long timestamp)
    {
        var bits = new CorrectedBinaryMessage(32);
        bits.load(15, 5, 5);
        bits.load(20, 5, site);
        var result = new CapacityPlusRestChannel(bits, timestamp, 1);
        result.setValid(true);
        return result;
    }

    private static SiteInformation typeC(int system, int site, int ran, long timestamp, LICH lich)
    {
        var bits = new CorrectedBinaryMessage(176);
        bits.load(10, 10, system);
        bits.load(20, 12, site);
        var result = new SiteInformation(bits, timestamp, NXDNMessageType.CONTROL_OUT_24_BC_SITE_INFORMATION, ran, lich);
        result.setValid(true);
        return result;
    }

    private static SiteID typeD(int site, long timestamp)
    {
        var bits = new CorrectedBinaryMessage(32);
        bits.load(3, 2, 1);
        bits.load(8, 5, site);
        var result = new SiteID(bits, timestamp, NXDNMessageType.TYPE_D_SCCH_OUT_INFO_4_SITE_ID, 0,
            LICH.RTCH_2_OUTBOUND_SUPER_VOICE_VOICE);
        result.setValid(true);
        return result;
    }

    private static TalkgroupVoiceChannelGrant grant(int lcn, long timestamp)
    {
        var bits = new CorrectedBinaryMessage(80);
        bits.load(16, 12, lcn);
        var result = new TalkgroupVoiceChannelGrant(DMRSyncPattern.BASE_STATION_DATA, bits, null, slotType(), timestamp, 1);
        result.setValid(true);
        return result;
    }

    private static AnnounceChannelFrequency announcement(int lcn, long timestamp)
    {
        return announcement(lcn, 257, 5, timestamp);
    }

    private static AnnounceChannelFrequency announcement(int lcn, int network, int site, long timestamp)
    {
        var bits = new CorrectedBinaryMessage(80);
        bits.load(22, 12, lcn);
        bits.load(34, 10, 150);
        bits.load(44, 13, 350);
        bits.load(57, 10, 140);
        bits.load(67, 13, 350);
        var continuation = new MBCContinuationBlock(DMRSyncPattern.BASE_STATION_DATA, bits, null, slotType(), timestamp, 1);
        var header = announcementBits(Opcode.STANDARD_ANNOUNCEMENT, AnnouncementType.CHANNEL_FREQUENCY_ANNOUNCEMENT,
            network, site);
        var result = new AnnounceChannelFrequency(DMRSyncPattern.BASE_STATION_DATA, header,
            null, slotType(), timestamp, 1, continuation);
        assertOwnParsed(result.getSystemIdentityCode(), network, site);
        result.setValid(true);
        return result;
    }

    private static AdjacentSiteInformation adjacent(boolean hytera, int ownNetwork, int ownSite,
                                                     int neighborNetwork, int neighborSite, long timestamp)
    {
        var bits = announcementBits(hytera ? Opcode.HYTERA_08_ANNOUNCEMENT : Opcode.STANDARD_ANNOUNCEMENT,
            AnnouncementType.ADJACENT_SITE_INFORMATION, ownNetwork, ownSite);
        loadTinySic(bits, 21, neighborNetwork, neighborSite);
        bits.load(68, 12, 844);
        var result = hytera ? new HyteraAdjacentSiteInformation(DMRSyncPattern.BASE_STATION_DATA,
            bits, null, slotType(), timestamp, 1) : new AdjacentSiteInformation(DMRSyncPattern.BASE_STATION_DATA,
            bits, null, slotType(), timestamp, 1);
        assertOwnParsed(result.getSystemIdentityCode(), ownNetwork, ownSite);
        assertEquals(neighborNetwork, result.getNeighborSystemIdentityCode().getNetwork().getValue());
        assertEquals(neighborSite, result.getNeighborSystemIdentityCode().getSite().getValue());
        result.setValid(true);
        return result;
    }

    private static AnnounceWithdrawTSCC withdrawTscc(int network, int site, long timestamp)
    {
        var bits = announcementBits(Opcode.STANDARD_ANNOUNCEMENT, AnnouncementType.ANNOUNCE_OR_WITHDRAW_TSCC,
            network, site);
        bits.set(33);
        bits.load(56, 12, 844);
        var result = new AnnounceWithdrawTSCC(DMRSyncPattern.BASE_STATION_DATA, bits, null, slotType(), timestamp, 1);
        assertOwnParsed(result.getSystemIdentityCode(), network, site);
        result.setValid(true);
        return result;
    }

    private static HyteraAnnouncement hyteraAnnouncement(int network, int site, long timestamp)
    {
        var bits = announcementBits(Opcode.HYTERA_08_ANNOUNCEMENT, AnnouncementType.GENERAL_SITE_INFORMATION,
            network, site);
        var result = new HyteraAnnouncement(DMRSyncPattern.BASE_STATION_DATA, bits, null, slotType(), timestamp, 1);
        assertOwnParsed(result.getSystemIdentityCode(), network, site);
        result.setValid(true);
        return result;
    }

    private static HyteraAloha hyteraAloha(int network, int site, long timestamp)
    {
        var bits = identityCsbkBits(Opcode.HYTERA_68_ALOHA, network, site);
        var result = new HyteraAloha(DMRSyncPattern.BASE_STATION_DATA, bits, null, slotType(), timestamp, 1);
        assertOwnParsed(result.getSystemIdentityCode(), network, site);
        result.setValid(true);
        return result;
    }

    private static Aloha standardAloha(int network, int site, long timestamp)
    {
        var bits = identityCsbkBits(Opcode.STANDARD_ALOHA, network, site);
        var result = new Aloha(DMRSyncPattern.BASE_STATION_DATA, bits, null, slotType(), timestamp, 1);
        assertOwnParsed(result.getSystemIdentityCode(), network, site);
        result.setValid(true);
        return result;
    }

    private static CapacityMaxAloha capacityMaxAloha(int network, int site, long timestamp)
    {
        var bits = identityCsbkBits(Opcode.MOTOROLA_CAPMAX_ALOHA, network, site);
        var result = new CapacityMaxAloha(DMRSyncPattern.BASE_STATION_DATA, bits, null, slotType(), timestamp, 1);
        assertOwnParsed(result.getSystemIdentityCode(), network, site);
        result.setValid(true);
        return result;
    }

    private static CorrectedBinaryMessage announcementBits(Opcode opcode, AnnouncementType type, int network, int site)
    {
        var bits = identityCsbkBits(opcode, network, site);
        bits.load(16, 5, type.getValue());
        return bits;
    }

    private static CorrectedBinaryMessage identityCsbkBits(Opcode opcode, int network, int site)
    {
        var bits = new CorrectedBinaryMessage(80);
        bits.load(2, 6, opcode.getValue());
        bits.load(8, 8, opcode.getVendor().getValue());
        //Offsets come from the existing CSBK getters; Tiny SIC widths match the monitor's short-LC fixture.
        loadTinySic(bits, 40, network, site);
        return bits;
    }

    private static void loadTinySic(CorrectedBinaryMessage bits, int offset, int network, int site)
    {
        bits.load(offset, 2, 0);
        bits.load(offset + 2, 9, network);
        bits.load(offset + 11, 3, site);
    }

    private static void assertOwnParsed(SystemIdentityCode sic, int network, int site)
    {
        assertEquals(Model.TINY, sic.getModel());
        assertEquals(network, sic.getNetwork().getValue());
        assertEquals(site, sic.getSite().getValue());
    }

    private interface OwnAnnouncementFactory
    {
        CSBKMessage create(int network, int site, long timestamp);
    }

    private static SlotType slotType()
    {
        var bits = new CorrectedBinaryMessage(24);
        bits.load(8, 4, 3);
        return new SlotType(bits);
    }

    private static ComplexSamples samples(long timestamp)
    { return new ComplexSamples(new float[]{1}, new float[]{0}, timestamp); }

    private static DigitalTrunkedDiscoveryProbe.ProbeDecoder decoder(Listener<ComplexSamples> listener)
    {
        return new DigitalTrunkedDiscoveryProbe.ProbeDecoder()
        {
            public void receive(ComplexSamples samples) { listener.receive(samples); }
            public void reset() { }
            public void close() { }
        };
    }

    private static void await(BooleanSupplier condition) throws Exception
    {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "Timed out waiting for digital probe worker");
    }

    private static void waitLatch(CountDownLatch latch)
    {
        try { latch.await(3, TimeUnit.SECONDS); }
        catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
    }

    private static final class FakeSource implements P25DiscoveryProbe.SourceLease
    {
        final AtomicInteger closed = new AtomicInteger();
        final AtomicLong upstreamDrops = new AtomicLong();
        volatile boolean reservationValid = true;
        private volatile Listener<ComplexSamples> samples;
        public double sampleRate() { return 50_000; }
        public boolean valid() { return reservationValid && closed.get() == 0; }
        public long droppedSampleBatches() { return upstreamDrops.get(); }
        public void start(Listener<ComplexSamples> samples, Listener<SourceEvent> events) { this.samples = samples; }
        public void stopSamples() { samples = null; }
        public void close() { stopSamples(); closed.compareAndSet(0, 1); }
        void emit(ComplexSamples sample) { var listener = samples; if(listener != null) listener.receive(sample); }
    }
}
