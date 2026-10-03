/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import io.github.dsheirer.channel.*;
import io.github.dsheirer.source.tuner.Tuner;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrunkedSpectrumSearchServiceTest
{
    private static final long CENTER = 452_000_000, A = CENTER-100_000, B = CENTER+100_000;
    private static final List<SpectrumSearchService.Range> RANGE = List.of(new SpectrumSearchService.Range(CENTER-500_000,CENTER+500_000));

    @Test void recordingWindowObservesTwiceAndChecksCandidatesWithoutAnyRetune() throws Exception
    {
        var lease = new Lease(true,A,B); var channels = new Channels();
        try(var service = service(lease,channels,(l,f,c) -> evidence("nxdn","TYPE_C","nxdn-c:global:341",7,f)))
        {
            var result = complete(service,service.open("recording","browse",RANGE,750).jobId());
            assertEquals(2,result.progress().completed()); assertEquals(2,result.progress().total());
            assertEquals(1,result.candidates().size(),"The exact native site seen on two controls must deduplicate");
            assertEquals("nxdn",result.candidates().getFirst().protocolId());
            assertEquals("M4800",result.candidates().getFirst().trunkedEvidence().settings().get("transmission_mode"));
            assertTrue(lease.tuned.stream().allMatch(center -> center == CENTER));
            service.cancel(result.jobId()); assertEquals(1,lease.closes.get());
        }
    }
    @Test void recordingRangeOutsideCaptureFailsAndReleasesBeforeStartingWorker()
    {
        var lease = new Lease(true,A); var channels = new Channels();
        try(var service = service(lease,channels,(l,f,c) -> null))
        {
            assertThrows(IllegalArgumentException.class,() -> service.open("recording","browse",
                List.of(new SpectrumSearchService.Range(CENTER-2_000_000,CENTER+500_000)),750));
            assertEquals(1,lease.closes.get()); assertEquals(0,lease.observations);
        }
    }
    @Test void frequencyScopedVariantsNeverGroupUnrelatedSitesByLocalSiteNumber() throws Exception
    {
        var lease = new Lease(true,A,B); var channels = new Channels();
        try(var service = service(lease,channels,(l,f,c) -> evidence("dmr","CAPACITY_PLUS",null,7,f)))
        {
            var result = complete(service,service.open("recording","browse",RANGE,750).jobId());
            assertEquals(2,result.candidates().size()); assertEquals(2,result.aliasGroups().size());
            assertNotEquals(result.candidates().getFirst().aliasGroupId(),result.candidates().getLast().aliasGroupId());
        }
    }
    @Test void explicitMapsFillUnknownNumbersButCannotChangeConfirmedMapping() throws Exception
    {
        var lease = new Lease(true,A); var channels = new Channels();
        try(var service = service(lease,channels,(l,f,c) -> evidence("dmr","TIER_III","dmr:tier3:tiny:341",7,f)))
        {
            var result = complete(service,service.open("recording","browse",RANGE,750).jobId());
            var row = result.candidates().getFirst(); var group = result.aliasGroups().getFirst();
            var conflict = service.save(result.jobId(),new SpectrumSearchService.SaveRequest(result.revision(),
                List.of(new SpectrumSearchService.SaveCandidate(row.candidateId(),"Site",false,List.of(new ChannelDefinition.FrequencyMapEntry(1,B,0)))),
                List.of(new SpectrumSearchService.AliasChoice(group.groupId(),0,"Test"))));
            assertFalse(conflict.candidates().getFirst().saved()); assertTrue(conflict.candidates().getFirst().saveError().contains("conflicts"));
            var saved = service.save(result.jobId(),new SpectrumSearchService.SaveRequest(conflict.revision(),
                List.of(new SpectrumSearchService.SaveCandidate(row.candidateId(),"Site",false,List.of(new ChannelDefinition.FrequencyMapEntry(2,B,0)))),
                List.of(new SpectrumSearchService.AliasChoice(group.groupId(),0,"Test"))));
            assertTrue(saved.candidates().getFirst().saved());
            assertEquals(List.of(new ChannelDefinition.FrequencyMapEntry(1,A,0),new ChannelDefinition.FrequencyMapEntry(2,B,0)),channels.saved.frequencyMap());
            assertEquals("dmr",channels.saved.protocolId()); assertEquals("TRUNKED",channels.saved.settings().get("channel_mode"));
        }
    }
    @Test void cancellingGenericCheckStopsWorkAndReleasesOnce() throws Exception
    {
        var lease = new Lease(true,A); var entered = new CountDownLatch(1); var channels = new Channels();
        try(var service = service(lease,channels,(l,f,c) -> { entered.countDown(); new CountDownLatch(1).await(); return null; }))
        {
            var job = service.open("recording","browse",RANGE,750);
            assertTrue(entered.await(2,TimeUnit.SECONDS)); service.cancel(job.jobId());
            assertEquals(1,lease.closes.get()); assertNull(channels.saved);
            assertThrows(SpectrumSearchService.SearchExpiredException.class,() -> service.status(job.jobId()));
        }
    }

    private static SpectrumSearchService service(Lease lease,Channels channels,SpectrumSearchService.TrunkedProbeCheck check)
    { return new SpectrumSearchService(channels,(id,browse) -> lease,check,System::currentTimeMillis,() -> new SpectrumSearchService.Catalog(List.of(),null,List.of(),null),true); }
    private static SpectrumSearchService.Snapshot complete(SpectrumSearchService service,String id) throws Exception
    {
        long until = System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(System.nanoTime()<until) { var status = service.status(id); if("complete".equals(status.phase())) return status; if("failed".equals(status.phase())) fail(status.reason()); Thread.sleep(5); }
        throw new AssertionError("Search did not complete");
    }
    private static TrunkedDiscoveryEvidence evidence(String protocol,String variant,String key,int site,long frequency)
    {
        String scope = key != null ? key : protocol+':'+variant+":frequency:"+frequency;
        var identity = new TrunkedDiscoveryEvidence.Identity(null,key,scope+":site:"+site,protocol.equals("dmr") ? 341 : null,341,site,
            protocol.equals("dmr") ? "TINY" : null,protocol.equals("nxdn") ? "GLOBAL" : null,null,12,1);
        return new TrunkedDiscoveryEvidence(protocol,variant,identity,protocol.equals("dmr") ? Map.of("channel_mode","TRUNKED","ignore_crc_checksums",false) :
            Map.of("channel_mode","TRUNKED","transmission_mode","M4800"),List.of(new ChannelDefinition.FrequencyMapEntry(1,frequency,0)),95,100,40,2,System.currentTimeMillis(),"Confirmed");
    }
    private static class Lease implements SpectrumSearchHardware.Lease
    {
        final boolean fixed; final long[] frequencies; final List<Long> tuned = new ArrayList<>(); final AtomicInteger closes = new AtomicInteger(); boolean valid = true; int observations;
        Lease(boolean fixed,long... frequencies) { this.fixed = fixed; this.frequencies = frequencies; }
        public Tuner tuner() { return null; } public String targetId() { return "recording"; } public long usableBandwidthHz() { return 2_000_000; }
        public long sampleRateHz() { return 2_000_000; } public long middleUnusableHalfBandwidthHz() { return 0; }
        public long minimumFrequencyHz() { return CENTER-1_000_000; } public long maximumFrequencyHz() { return CENTER+1_000_000; }
        public long centerFrequencyHz() { return CENTER; } public boolean fixedWindow() { return fixed; }
        public void tune(long frequency) { if(fixed) assertEquals(CENTER,frequency); tuned.add(frequency); }
        public List<SpectrumPeakDetector.Peak> observe(long dwell,BooleanSupplier cancelled)
        { observations++; return Arrays.stream(frequencies).mapToObj(f -> new SpectrumPeakDetector.Peak(f,-30,10,10)).toList(); }
        public P25DiscoveryProbe.Session probe(long frequency) { throw new AssertionError("Generic test seam owns checking"); }
        public boolean valid() { return valid; } public <T>T handoff(long center,Supplier<T> start) { valid = false; return start.get(); }
        public void close() { valid = false; closes.incrementAndGet(); }
    }
    private static class Channels implements SpectrumSearchService.Channels
    {
        long revision = 1; ChannelDefinition saved;
        public long revision() { return revision; } public SpectrumSearchService.KnownChannel known(long frequency) { return null; }
        public ChannelAdministrationService.DiscoveryReview review(long frequency,String preferred,io.github.dsheirer.module.decode.p25.P25SiteIdentity identity,String modulation) { throw new AssertionError(); }
        public ChannelAdministrationService.DiscoveryReview reviewTrunked(long frequency,String preferred,TrunkedDiscoveryEvidence evidence)
        {
            var definition = new ChannelDefinition(null,evidence.protocolId(),evidence.systemName(),evidence.siteName(),"Found",null,0,
                new ChannelDefinition.Source(List.of(frequency),null,null,frequency,preferred,null),evidence.settings(),evidence.frequencyMap(),List.of(),List.of(),List.of(),null);
            return new ChannelAdministrationService.DiscoveryReview(revision,definition,List.of(),null,"Test");
        }
        public ChannelAdministrationService.DiscoveryCreated create(ChannelDefinition definition,io.github.dsheirer.module.decode.p25.P25SiteIdentity identity,String alias,long revision,boolean auto) { throw new AssertionError(); }
        public ChannelAdministrationService.DiscoveryCreated createTrunked(ChannelDefinition definition,TrunkedDiscoveryEvidence evidence,String alias,long expected,boolean auto)
        { assertEquals(revision,expected); saved = definition; revision++; return new ChannelAdministrationService.DiscoveryCreated("saved",1); }
        public ChannelAdministrationService.LifecycleResult start(String id,String tuner,Tuner runtime,boolean handoff) { throw new AssertionError(); }
    }
}
