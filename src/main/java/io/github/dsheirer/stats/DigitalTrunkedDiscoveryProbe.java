/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import io.github.dsheirer.channel.ChannelDefinition;
import io.github.dsheirer.dsp.filter.channelizer.PolyphaseChannelSource;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.dmr.*;
import io.github.dsheirer.module.decode.dmr.message.DMRMessage;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.CSBKMessage;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.Aloha;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.announcement.AdjacentSiteInformation;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.announcement.AnnounceChannelFrequency;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.announcement.AnnounceWithdrawTSCC;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.hytera.HyteraAloha;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.hytera.HyteraAnnouncement;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.hytera.HyteraAdjacentSiteInformation;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.CapacityMaxAloha;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.CapacityPlusRestChannel;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.ConnectPlusControlChannel;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.ControlChannelSystemParameters;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.dmr.message.type.SystemIdentityCode;
import io.github.dsheirer.module.decode.nxdn.*;
import io.github.dsheirer.module.decode.nxdn.layer3.NXDNLayer3Message;
import io.github.dsheirer.module.decode.nxdn.layer3.broadcast.ControlChannelInformation;
import io.github.dsheirer.module.decode.nxdn.layer3.broadcast.ServiceInformation;
import io.github.dsheirer.module.decode.nxdn.layer3.broadcast.SiteInformation;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.SiteID;
import io.github.dsheirer.module.decode.nxdn.layer3.type.TransmissionMode;
import io.github.dsheirer.module.decode.nxdn.telemetry.NXDNNetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.manager.PolyphaseChannelSourceManager;
import io.github.dsheirer.source.tuner.manager.TunerSettingsService;
import io.github.dsheirer.util.concurrent.BoundedSpscReferenceQueue;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/** Temporary CRC-enforcing DMR/NXDN checks. Sample callbacks only offer references to a bounded worker queue. */
public final class DigitalTrunkedDiscoveryProbe implements AutoCloseable
{
    static final long TIMEOUT_MS = 30_000;
    static final long MINIMUM_SPAN_MS = 2_000;
    static final int MINIMUM_OBSERVATIONS = 3;
    static final int MINIMUM_CONTROL = 20;
    static final int QUEUE_CAPACITY = 8;
    private final SourceFactory sources;
    private final DecoderFactory decoders;
    private final LongSupplier clock;
    private Session active;
    private boolean closed;

    public DigitalTrunkedDiscoveryProbe(TunerDiagnosticService diagnostics, TunerSettingsService settings)
    { this((target, frequency) -> allocate(diagnostics, settings, null, target, frequency), DigitalTrunkedDiscoveryProbe::decoder, System::currentTimeMillis); }
    public DigitalTrunkedDiscoveryProbe(TunerDiagnosticService diagnostics, TunerSettingsService.ProbeHold hold)
    { this((target, frequency) -> allocate(diagnostics, null, hold, target, frequency), DigitalTrunkedDiscoveryProbe::decoder, System::currentTimeMillis); }
    DigitalTrunkedDiscoveryProbe(SourceFactory sources, DecoderFactory decoders, LongSupplier clock)
    { this.sources = sources; this.decoders = decoders; this.clock = clock; }

    public synchronized Session open(String target, long frequency, String protocol)
    {
        if(closed || active != null) throw new IllegalStateException("A digital discovery probe is already in use");
        if(!Set.of("auto", "dmr", "nxdn").contains(protocol) || frequency <= 0)
            throw new IllegalArgumentException("Choose DMR or NXDN and a valid frequency");
        var source = sources.open(target, frequency);
        try { active = new Session(target, frequency, protocol, source); active.start(); return active; }
        catch(RuntimeException exception) { source.close(); active = null; throw exception; }
    }
    private synchronized void release(Session session) { if(active == session) active = null; }
    @Override public synchronized void close() { closed = true; if(active != null) active.close(); }

    private static P25DiscoveryProbe.SourceLease allocate(TunerDiagnosticService diagnostics, TunerSettingsService settings,
        TunerSettingsService.ProbeHold borrowed, String target, long frequency)
    {
        Tuner tuner = diagnostics.tunerForTarget(target);
        if(tuner == null || !(tuner.getChannelSourceManager() instanceof PolyphaseChannelSourceManager manager))
            throw new IllegalStateException("The selected receiver cannot check digital signals");
        var hold = borrowed != null ? borrowed : settings.holdForProbe(tuner);
        try
        {
            var config = new DecodeConfigDMR();
            var spec = config.getChannelSpecification();
            if(!FrequencyListenService.withinCurrentWindow(frequency, tuner.getTunerController().getFrequency(),
                spec.getBandwidth(), tuner.getTunerController().getUsableBandwidth() / 2L,
                tuner.getTunerController().getMiddleUnusableHalfBandwidth()))
                throw new IllegalArgumentException("Frequency is outside this receiver's usable window");
            var source = manager.getSourceAtCurrentCenter(new TunerChannel(frequency, spec.getBandwidth()), spec, "Trunked discovery");
            if(source == null) throw new IllegalStateException("The receiver cannot check this frequency without retuning");
            return new P25DiscoveryProbe.SourceLease()
            {
                private final AtomicBoolean stopped = new AtomicBoolean(), released = new AtomicBoolean();
                public double sampleRate() { return source.getSampleRate(); }
                public boolean valid() { return !released.get() && hold.valid(); }
                public long droppedSampleBatches() { return source instanceof PolyphaseChannelSource p ? p.getOutputQueueStatus().droppedBatches() : 0; }
                public void start(Listener<ComplexSamples> samples, Listener<SourceEvent> events)
                { source.setListener(samples); source.setSourceEventListener(events); source.start(); }
                public synchronized void stopSamples()
                { if(stopped.compareAndSet(false, true)) { try { source.setListener(null); source.removeSourceEventListener(); } finally { source.stop(); } } }
                public synchronized void close()
                { if(released.compareAndSet(false, true)) { try { stopSamples(); } finally { if(borrowed == null) hold.close(); } } }
            };
        }
        catch(RuntimeException exception) { if(borrowed == null) hold.close(); throw exception; }
    }
    private static ProbeDecoder decoder(String mode, double rate, Listener<IMessage> messages)
    {
        if("DMR".equals(mode))
        {
            DecodeConfigDMR config = new DecodeConfigDMR();
            config.setIgnoreCRCChecksums(false);
            DMRDecoder decoder = new DMRDecoder(config, false, rate);
            decoder.setMessageListener(messages); decoder.start();
            return new ProbeDecoder() { public void receive(ComplexSamples samples) { decoder.receive(samples); }
                public void reset() { decoder.reset(); } public void close() { decoder.removeMessageListener(); decoder.stop(); } };
        }
        DecodeConfigNXDN config = new DecodeConfigNXDN(); config.setTransmissionMode(TransmissionMode.valueOf(mode));
        NXDNDecoder decoder = new NXDNDecoder(config); decoder.setSampleRate(rate); decoder.setMessageListener(messages); decoder.start();
        return new ProbeDecoder() { public void receive(ComplexSamples samples) { decoder.receive(samples); }
            public void reset() { decoder.reset(); } public void close() { decoder.removeMessageListener(); decoder.stop(); } };
    }

    public record Status(String state, String reason, String targetId, long frequencyHz, long elapsedMs,
        long timeoutMs, long droppedBuffers, TrunkedDiscoveryEvidence evidence, List<ModeMetrics> modes)
    {
        public Status(String state,String reason,String targetId,long frequencyHz,long elapsedMs,long timeoutMs,long droppedBuffers,TrunkedDiscoveryEvidence evidence)
        { this(state,reason,targetId,frequencyHz,elapsedMs,timeoutMs,droppedBuffers,evidence,List.of()); }
    }
    public record ModeMetrics(String mode,long validMessages,long validControlMessages,long invalidControlMessages,
        int identityObservations,long identitySpanMs,double qualityPct,boolean conflictingIdentity,TrunkedDiscoveryEvidence.Identity identity) { }
    public final class Session implements AutoCloseable
    {
        private final String target, protocol;
        private final long frequency, started;
        private final double sampleRate;
        private final P25DiscoveryProbe.SourceLease source;
        private final BoundedSpscReferenceQueue<ComplexSamples> queue = new BoundedSpscReferenceQueue<>(QUEUE_CAPACITY);
        private final AtomicLong dropped = new AtomicLong();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<String> failure = new AtomicReference<>();
        private final AtomicReference<Status> status = new AtomicReference<>();
        private final Thread worker;
        private long sampleGeneration;
        private volatile List<ModeMetrics> latestModes = List.of();
        Session(String target, long frequency, String protocol, P25DiscoveryProbe.SourceLease source)
        {
            this.target = target; this.frequency = frequency; this.protocol = protocol; this.source = source; sampleRate = source.sampleRate();
            started = clock.getAsLong(); status.set(snapshot("running", "Checking DMR and NXDN trunked broadcasts…", null));
            worker = new Thread(this::drain, "digital-trunked-discovery"); worker.setDaemon(true); worker.setPriority(Thread.MIN_PRIORITY);
        }
        private void start() { source.start(this::offer, this::event); worker.start(); }
        void offer(ComplexSamples samples)
        { if(!closed.get() && "running".equals(status.get().state()) && samples != null && !queue.offer(samples, dropped.get())) dropped.incrementAndGet(); }
        void event(SourceEvent event)
        {
            if(event == null) return;
            switch(event.getEvent())
            {
                case NOTIFICATION_ERROR_STATE, NOTIFICATION_TUNER_SHUTDOWN, NOTIFICATION_STOP_SAMPLE_STREAM -> failure.compareAndSet(null, "The receiver stopped supplying samples. Retry discovery.");
                case NOTIFICATION_FREQUENCY_CHANGE -> { if(event.hasValue() && event.getValue().longValue() != frequency) failure.compareAndSet(null, "The checked frequency changed. Retry discovery."); }
                case NOTIFICATION_SAMPLE_RATE_CHANGE, NOTIFICATION_CHANNEL_SAMPLE_RATE_CHANGE -> { if(event.hasValue() && Double.compare(event.getValue().doubleValue(), sampleRate) != 0) failure.compareAndSet(null, "The sample rate changed. Retry discovery."); }
                default -> { }
            }
        }
        private void drain()
        {
            Map<ModeEvidence,ProbeDecoder> modes = new LinkedHashMap<>();
            long generation = 0;
            try
            {
                if(!"nxdn".equals(protocol)) add(modes, "DMR");
                if(!"dmr".equals(protocol)) { add(modes, "M4800"); add(modes, "M9600"); add(modes, "TYPE_D"); }
                while(!closed.get())
                {
                    if(failure.get() != null || !source.valid() || source.droppedSampleBatches() > 0)
                    { finish("failed", failure.get() != null ? failure.get() : "The receiver changed or dropped samples. Retry discovery.", null); break; }
                    var samples = queue.poll();
                    if(samples != null)
                    {
                        if(queue.lastPolledMetadata() != generation)
                        { generation = queue.lastPolledMetadata(); sampleGeneration = generation;
                            //NXDN does not expose a complete framer reset. Recreate every decoder at a queue gap.
                            modes.forEach((e,d) -> { d.reset(); d.close(); }); modes.clear();
                            if(!"nxdn".equals(protocol)) add(modes,"DMR");
                            if(!"dmr".equals(protocol)) { add(modes,"M4800"); add(modes,"M9600"); add(modes,"TYPE_D"); } }
                        modes.values().forEach(d -> d.receive(samples));
                    }
                    long now = clock.getAsLong();
                    latestModes = modes.keySet().stream().map(ModeEvidence::metrics).toList();
                    List<TrunkedDiscoveryEvidence> confirmed = modes.keySet().stream().map(e -> e.confirmed(now)).filter(Objects::nonNull).toList();
                    if(generation == dropped.get() && !confirmed.isEmpty() && now - started >= MINIMUM_SPAN_MS)
                    {
                        Set<String> identities = new HashSet<>(); confirmed.forEach(e -> identities.add(e.identity().siteKey()));
                        if(identities.size() != 1) finish("inconclusive", "Digital decoder identities disagree. Retry discovery.", null);
                        else
                        {
                            var ranked = confirmed.stream().sorted(java.util.Comparator.comparingDouble(TrunkedDiscoveryEvidence::qualityPct).reversed()).toList();
                            if(ranked.size() > 1 && ranked.getFirst().qualityPct()-ranked.get(1).qualityPct() < 5)
                                finish("inconclusive","The digital decoder modes were too close to choose an accurate channel rate. Retry discovery.",null);
                            else finish("ready", "A trunked digital site was confirmed.", ranked.getFirst());
                        }
                        break;
                    }
                    if(now - started >= TIMEOUT_MS)
                    { finish("inconclusive", "No consistent DMR or NXDN trunked serving broadcasts were confirmed. Conventional signals are not trunked search results.", null); break; }
                    var previous = status.get();
                    if(!closed.get() && "running".equals(previous.state())) status.compareAndSet(previous,snapshot("running","Checking DMR and NXDN trunked broadcasts…",null));
                    if(samples == null) LockSupport.parkNanos(5_000_000L);
                }
            }
            catch(RuntimeException exception) { finish("failed", "Digital discovery could not check this signal. Retry discovery.", null); }
            finally { modes.values().forEach(ProbeDecoder::close); queue.clear(); }
        }
        private void add(Map<ModeEvidence,ProbeDecoder> modes, String mode)
        { var evidence = new ModeEvidence(mode, frequency); modes.put(evidence, decoders.create(mode, sampleRate, evidence::receive)); }
        private Status snapshot(String state, String reason, TrunkedDiscoveryEvidence evidence)
        { return new Status(state, reason, target, frequency, Math.max(0, clock.getAsLong()-started), TIMEOUT_MS, dropped.get(), evidence, latestModes); }
        private void finish(String state, String reason, TrunkedDiscoveryEvidence evidence)
        {
            if(closed.get()) return;
            if("ready".equals(state) && (!valid() || sampleGeneration != dropped.get())) { state = "failed"; reason = "The receiver changed. Retry discovery."; evidence = null; }
            status.set(snapshot(state, reason, evidence));
            try { source.stopSamples(); }
            catch(RuntimeException exception) { state = "failed"; status.set(snapshot(state, "The receiver could not finish discovery. Retry discovery.", null)); }
            if(!"ready".equals(state)) { source.close(); release(this); }
        }
        public Status status()
        {
            Status current = status.get();
            if("ready".equals(current.state()) && !valid() &&
                status.compareAndSet(current,snapshot("failed","The receiver changed. Retry discovery.",null))) close();
            return status.get();
        }
        public boolean valid() { return !closed.get() && failure.get() == null && source.valid() && source.droppedSampleBatches() == 0; }
        @Override public void close()
        { if(closed.compareAndSet(false,true))
            {
                status.updateAndGet(previous -> "running".equals(previous.state()) ? snapshot("closed","Discovery was cancelled.",null) : previous);
                try { source.close(); } finally { worker.interrupt(); release(this); }
            }
        }
    }

    /** All monitor mutation, snapshot projection and trust decisions run on the temporary decoder worker. */
    static final class ModeEvidence
    {
        final String mode;
        final long frequency;
        final DMRNetworkConfigurationMonitor dmr = new DMRNetworkConfigurationMonitor();
        final NXDNNetworkConfigurationMonitor nxdn = new NXDNNetworkConfigurationMonitor();
        long valid, control, invalid, first, last;
        int observations;
        boolean conflict;
        TrunkedDiscoveryEvidence.Identity identity;
        String variant;
        List<ChannelDefinition.FrequencyMapEntry> map = List.of();
        ModeEvidence(String mode, long frequency) { this.mode = mode; this.frequency = frequency; }
        void reset() { dmr.reset(); nxdn.reset(); valid = control = invalid = first = last = 0; observations = 0; conflict = false; identity = null; map = List.of(); }
        void receive(IMessage message)
        {
            if(message == null || !(message instanceof DMRMessage || message instanceof NXDNMessage)) return;
            if(message.isValid()) valid++;
            if(message instanceof DMRMessage value)
            {
                var sic = servingSystemIdentity(value);
                boolean serving = sic != null || value instanceof ConnectPlusControlChannel || value instanceof CapacityPlusRestChannel;
                boolean controlMessage = serving || value instanceof CSBKMessage;
                if(controlMessage) { if(value.isValid()) control++; else invalid++; }
                if(!value.isValid()) return;
                dmr.process(value);
                var snapshot = dmr.getSnapshot();
                if(serving && snapshot != null && (sic != null || snapshot.site() != null))
                {
                    variant = value instanceof HyteraAloha || value instanceof HyteraAnnouncement || value instanceof HyteraAdjacentSiteInformation ? "HYTERA_TIER_III" :
                        value instanceof CapacityMaxAloha ? "CAPACITY_MAX" : snapshot.variant();
                    if(variant == null && sic != null) variant = "TIER_III";
                    if(variant == null) return;
                    Integer network = sic != null ? sic.getNetwork().getValue() : snapshot.network();
                    Integer site = sic != null ? sic.getSite().getValue() : value instanceof ConnectPlusControlChannel c ? c.getSite().getValue() : value instanceof CapacityPlusRestChannel c ? c.getSite().getValue() : snapshot.site();
                    if(value instanceof ConnectPlusControlChannel c) network = c.getNetwork().getValue();
                    String model = sic != null ? sic.getModel().name() : snapshot.model();
                    String key = Set.of("TIER_III", "CAPACITY_MAX", "HYTERA_TIER_III").contains(variant) ? RadioSystemKey.dmrTier3(model, network) : null;
                    if(Set.of("TIER_III", "CAPACITY_MAX", "HYTERA_TIER_III").contains(variant) && key == null) return;
                    String scope = key != null ? key : "dmr:" + variant.toLowerCase(Locale.ROOT) + ":frequency:" + frequency;
                    observe(new TrunkedDiscoveryEvidence.Identity(null,key,scope + ":site:" + site,network,network,site,model,null,null,null,
                        snapshot.colorCodeTimeslot1() != null ? snapshot.colorCodeTimeslot1() : snapshot.colorCodeTimeslot2()), message.getTimestamp());
                }
                if(snapshot != null) map = snapshot.channels().stream().filter(c -> c.logicalChannelNumber() != null && c.logicalChannelNumber() > 0 && c.downlink() != null && c.downlink() > 0 &&
                    c.frequencySource() == DMRNetworkConfigurationSnapshot.FrequencySource.OVER_THE_AIR)
                    .map(c -> new ChannelDefinition.FrequencyMapEntry(c.logicalChannelNumber(), c.downlink(), c.uplink() != null ? c.uplink() : 0)).distinct().toList();
            }
            else if(message instanceof NXDNLayer3Message value)
            {
                //The 2400-symbol Type-C and Type-D decoders can both frame a Type-C broadcast. Use the
                //validated received message subtype to choose its canonical mode before trusting site evidence.
                //M4800 versus M9600 still represents a physical rate choice and retains the ambiguity guard.
                //An invalid message cannot establish its subtype: keep counting it in the quality denominator.
                if(value.isValid() && "TYPE_D".equals(mode) != value.isTypeD()) return;
                boolean controlCarrier = value.getLICH() != null && (value.getLICH().getRFChannel() == io.github.dsheirer.module.decode.nxdn.layer2.RFChannel.RCCH || value.getLICH().getRFChannel() == io.github.dsheirer.module.decode.nxdn.layer2.RFChannel.RTCHC || value.isTypeD());
                boolean serving = controlCarrier && (value instanceof SiteInformation || value instanceof ServiceInformation || value instanceof ControlChannelInformation || value instanceof SiteID);
                boolean controlMessage = serving || value.getMessageType().name().startsWith("CONTROL_OUT_") || value.isTypeD();
                if(controlMessage) { if(value.isValid()) control++; else invalid++; }
                if(!value.isValid()) return;
                nxdn.process(value);
                var snapshot = nxdn.getSnapshot();
                if(serving && snapshot != null)
                {
                    variant = snapshot.variant(); var location = snapshot.currentLocation();
                    var broadcastLocation = value instanceof SiteInformation v ? v.getLocationID() : value instanceof ServiceInformation v ? v.getLocationID() : value instanceof ControlChannelInformation v ? v.getLocationID() : null;
                    if(broadcastLocation != null)
                        location = new NXDNNetworkConfigurationSnapshot.Location(broadcastLocation.getCategory().getValue(),broadcastLocation.getSystem().getValue(),broadcastLocation.getSiteOrIntegrator().getValue(),null);
                    if("TYPE_C".equals(variant) && location != null && location.site() != null)
                    {
                        String key = RadioSystemKey.nxdnTypeC(location.category(), location.system());
                        if(key != null) observe(new TrunkedDiscoveryEvidence.Identity(null,key,key + ":site:" + location.site(),null,location.system(),location.site(),null,location.category(),null,snapshot.ran(),null),message.getTimestamp());
                    }
                    else if("TYPE_D".equals(variant) && snapshot.typeDSite() != null)
                    {
                        //Type-D SiteID has no globally unique system identity: never group different frequencies by it.
                        observe(new TrunkedDiscoveryEvidence.Identity(null,null,"nxdn:type-d:frequency:" + frequency + ":site:" + snapshot.typeDSite(),null,
                            null,snapshot.typeDSite(),null,"TYPE_D",null,snapshot.ran(),null),message.getTimestamp());
                    }
                }
                if(snapshot != null) map = snapshot.controlChannels().stream().filter(c -> c.channelNumber() != null && c.channelNumber() > 0 && c.downlink() != null && c.downlink() > 0 && "DFA".equals(c.allocation()))
                    .map(c -> new ChannelDefinition.FrequencyMapEntry(c.channelNumber(),c.downlink(),c.uplink() != null ? c.uplink() : 0)).distinct().toList();
            }
        }
        /** Current-own SIC only: adjacent/vote channels and traffic LC never supply serving identity. */
        private static SystemIdentityCode servingSystemIdentity(DMRMessage message)
        {
            return switch(message)
            {
                case Aloha value -> value.getSystemIdentityCode();
                case CapacityMaxAloha value -> value.getSystemIdentityCode();
                case ControlChannelSystemParameters value -> value.getSystemIdentityCode();
                case HyteraAloha value -> value.getSystemIdentityCode();
                case HyteraAnnouncement value -> value.getSystemIdentityCode();
                //This getter is the sending site's SIC; getNeighborSystemIdentityCode() is deliberately excluded.
                case AdjacentSiteInformation value -> value.getSystemIdentityCode();
                case AnnounceChannelFrequency value -> value.getSystemIdentityCode();
                case AnnounceWithdrawTSCC value -> value.getSystemIdentityCode();
                default -> null;
            };
        }
        private void observe(TrunkedDiscoveryEvidence.Identity next, long time)
        {
            if(identity != null && (!identity.siteKey().equals(next.siteKey()) ||
                !compatible(identity.network(),next.network()) || !compatible(identity.system(),next.system()) ||
                !compatible(identity.site(),next.site()) || !compatible(identity.model(),next.model()) ||
                !compatible(identity.category(),next.category()) || !compatible(identity.integrator(),next.integrator()) ||
                !compatible(identity.ran(),next.ran()) || !compatible(identity.colorCode(),next.colorCode())))
            { conflict = true; return; }
            if(observations > 0 && time <= last) return;
            if(identity == null) { identity = next; first = time; }
            else if(identity.ran() == null && next.ran() != null || identity.colorCode() == null && next.colorCode() != null) identity = next;
            last = time; observations++;
        }
        private static boolean compatible(Object a,Object b) { return a == null || b == null || a.equals(b); }
        ModeMetrics metrics()
        { return new ModeMetrics(mode,valid,control,invalid,observations,Math.max(0,last-first),control+invalid > 0 ? control*100.0/(control+invalid) : 0,conflict,identity); }
        TrunkedDiscoveryEvidence confirmed(long now)
        {
            double quality = control + invalid > 0 ? control * 100.0 / (control + invalid) : 0;
            if(conflict || identity == null || observations < MINIMUM_OBSERVATIONS || last-first < MINIMUM_SPAN_MS || control < MINIMUM_CONTROL || quality < 60) return null;
            return new TrunkedDiscoveryEvidence("DMR".equals(mode) ? "dmr" : "nxdn",variant,identity,
                "DMR".equals(mode) ? Map.of("channel_mode","TRUNKED", "ignore_crc_checksums",false) : Map.of("channel_mode","TRUNKED", "transmission_mode",mode),
                map,quality,valid,control,invalid,now,"Repeated CRC-valid trunked serving identity confirmed");
        }
    }
    interface SourceFactory { P25DiscoveryProbe.SourceLease open(String target, long frequency); }
    interface DecoderFactory { ProbeDecoder create(String mode,double sampleRate,Listener<IMessage> messages); }
    interface ProbeDecoder extends AutoCloseable { void receive(ComplexSamples samples); void reset(); @Override void close(); }
}
