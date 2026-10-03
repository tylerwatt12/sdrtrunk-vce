/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelDefinition;
import io.github.dsheirer.channel.ChannelDefinitionCodec;
import io.github.dsheirer.channel.ChannelProtocolRegistry;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerSettingsService;
import io.github.dsheirer.util.ThreadPool;
import io.github.dsheirer.web.tuner.TunerAdministrationService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** One short-lived spectrum wizard. All projection and configuration work stays off receiver callbacks. */
public final class SpectrumDiscoveryService implements AutoCloseable
{
    private static final long IDLE_EXPIRY_MS = 30_000;
    private static final long MAXIMUM_LIFETIME_MS = 300_000;
    private final ChannelAdministrationService mChannels;
    private final TunerAdministrationService mTuners;
    private final TunerDiagnosticService mDiagnostics;
    private final TunerSettingsService mSettings;
    private final StatsWebDatabase mDatabase;
    private final P25DiscoveryProbe mProbe;
    private final DigitalTrunkedDiscoveryProbe mDigitalProbe;
    private volatile io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver mDirectory;
    private final java.util.concurrent.ExecutorService mDirectoryWorker = SpectrumSearchService.directoryWorker("click-discovery-directory");
    private final ScheduledFuture<?> mExpiry;
    private Wizard mWizard;

    SpectrumDiscoveryService(ChannelAdministrationService channels, TunerAdministrationService tuners,
        TunerDiagnosticService diagnostics, TunerSettingsService settings, StatsWebDatabase database)
    {
        mChannels = channels;
        mTuners = tuners;
        mDiagnostics = diagnostics;
        mSettings = settings;
        mDatabase = database;
        mProbe = new P25DiscoveryProbe(diagnostics, settings);
        mDigitalProbe = new DigitalTrunkedDiscoveryProbe(diagnostics, settings);
        mExpiry = ThreadPool.SCHEDULED.scheduleWithFixedDelay(this::expire, 5, 5, TimeUnit.SECONDS);
    }

    public void setRadioReferenceResolver(io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver resolver) { mDirectory = resolver; }

    public synchronized Eligibility eligibility(String tunerId, long frequencyHz)
    {
        DiscoveredTuner discovered = requireTuner(tunerId);
        if(!discovered.isAvailable() || !discovered.hasTuner())
            return new Eligibility(false, "The selected tuner is unavailable", List.of());
        Tuner tuner = discovered.getTuner();
        if(!FrequencyListenService.withinCurrentWindow(frequencyHz, tuner.getTunerController().getFrequency(),
            12500, tuner.getTunerController().getUsableBandwidth() / 2L,
            tuner.getTunerController().getMiddleUnusableHalfBandwidth()))
            return new Eligibility(false, "Select a frequency inside the tuner's usable receiver window", List.of());
        List<Object> matches = new ArrayList<>(mChannels.discoveryFrequencyMatches(frequencyHz));
        matches.addAll(mDatabase.discoveryFrequencyOwners(frequencyHz));
        return new Eligibility(matches.isEmpty(), matches.isEmpty() ? null :
            eligibilityReason(matches), List.copyOf(matches));
    }

    public synchronized Snapshot open(String tunerId, long frequencyHz, String protocolId, String browseLeaseId)
    { return open(tunerId,frequencyHz,protocolId,browseLeaseId,null); }

    public synchronized Snapshot open(String tunerId,long frequencyHz,String protocolId,String browseLeaseId,Integer stateId)
    {
        expire();
        if(mWizard != null) throw new IllegalStateException("Another channel discovery is already in progress");
        if(!Set.of("p25-phase1", "dmr", "nxdn", "am", "nbfm").contains(protocolId))
            throw new IllegalArgumentException("Choose P25 Phase 1, DMR, NXDN, AM, or NBFM");
        Eligibility eligible = eligibility(tunerId, frequencyHz);
        if(!eligible.eligible()) throw new IllegalStateException(eligible.reason());
        DiscoveredTuner discovered = requireTuner(tunerId);
        if(browseLeaseId != null && !mSettings.verifyBrowse(discovered, browseLeaseId))
            throw new IllegalStateException("Spectrum browsing expired; reopen the Spectrum page");
        Tuner tuner = discovered.getTuner();
        String targetId = mDiagnostics.targetIdFor(tuner);
        Wizard wizard = new Wizard(tunerId, targetId, frequencyHz, protocolId, browseLeaseId, tuner);
        wizard.stateId = stateId;
        try
        {
            if("p25-phase1".equals(protocolId)) wizard.probe = mProbe.open(targetId, frequencyHz);
            else if(Set.of("dmr","nxdn").contains(protocolId)) wizard.digitalProbe = mDigitalProbe.open(targetId,frequencyHz,protocolId);
            else wizard.hold = mSettings.holdForProbe(tuner);
            mWizard = wizard;
            return snapshot(wizard);
        }
        catch(RuntimeException exception)
        {
            wizard.close();
            throw exception;
        }
    }

    public synchronized Snapshot status(String id)
    {
        Wizard wizard = requireWizard(id);
        wizard.expiresAt = Math.min(wizard.maximumExpiresAt, System.currentTimeMillis() + IDLE_EXPIRY_MS);
        return snapshot(wizard);
    }

    public synchronized Snapshot save(String id, SaveRequest request)
    {
        Wizard wizard = requireWizard(id);
        if(wizard.saved != null) return snapshot(wizard);
        Snapshot current = snapshot(wizard);
        if(!"ready".equals(current.state())) throw new IllegalStateException("Complete identification before saving");
        Eligibility eligibility = eligibility(wizard.tunerId, wizard.frequencyHz);
        if(!eligibility.eligible()) throw new IllegalStateException(eligibility.reason());
        ChannelAdministrationService.DiscoveryReview review = current.review();
        ChannelDefinition template = review.template();
        Map<String,Object> settings = new LinkedHashMap<>(request.settings() != null ? request.settings() : Map.of());
        P25SiteIdentity identity = siteIdentity(current.probe());
        TrunkedDiscoveryEvidence saveEvidence = wizard.evidence != null ?
            wizard.evidence.withManualFrequencyMap(request.frequencyMap()) : null;
        if(saveEvidence == null && !request.frequencyMap().isEmpty())
            throw new IllegalArgumentException("This protocol does not use a channel map");
        if(saveEvidence != null) settings.putAll(saveEvidence.settings());
        ChannelDefinition.Source source = template.source();
        long savedFrequency = saveEvidence != null && saveEvidence.servingFrequencyHz() != null ?
            saveEvidence.servingFrequencyHz() : wizard.frequencyHz;
        if(savedFrequency != wizard.frequencyHz)
            source = new ChannelDefinition.Source(List.of(savedFrequency), null, null, savedFrequency,
                source.preferredTuner(), source.rotationDelayMs(), source.sourceType(), source.senderId(), source.feedId());
        ChannelDefinition definition = new ChannelDefinition(null, wizard.protocolId, request.system(),
            request.site(), request.name(), null, request.aliasListId(), source, settings,
            saveEvidence != null ? saveEvidence.frequencyMap() : List.of(), List.of(), List.of(), List.of(), ChannelDefinition.Observed.EMPTY);
        ChannelProtocolRegistry registry = new ChannelProtocolRegistry();
        AliasListDefinition validationList = new AliasListDefinition("Discovery", registry.require(wizard.protocolId).aliasFamily());
        validationList.setId(definition.aliasListId() > 0 ? definition.aliasListId() : 1);
        ChannelDefinition validation = new ChannelDefinition(null, definition.protocolId(), definition.system(),
            definition.site(), definition.name(), null, validationList.getId(), definition.source(), definition.settings(),
            definition.frequencyMap(), List.of(), List.of(), List.of(), ChannelDefinition.Observed.EMPTY);
        int bandwidth = new ChannelDefinitionCodec(registry).toChannel(validation, validationList, null)
            .getDecodeConfiguration().getChannelSpecification().getBandwidth();
        if(!FrequencyListenService.withinCurrentWindow(savedFrequency, wizard.centerHz, bandwidth,
            wizard.tuner.getTunerController().getUsableBandwidth() / 2L,
            wizard.tuner.getTunerController().getMiddleUnusableHalfBandwidth()))
            throw new IllegalArgumentException("The selected bandwidth extends outside the usable receiver window");
        wizard.saved = saveEvidence != null ? mChannels.createTrunkedDiscovered(definition,saveEvidence,request.newAliasListName(),request.revision(),true) :
            mChannels.createDiscovered(definition, identity, request.newAliasListName(), request.revision());
        wizard.close();
        return startSaved(wizard);
    }

    static String eligibilityReason(List<Object> matches)
    {
        List<String> names = matches.stream().map(match ->
        {
            if(match instanceof ChannelAdministrationService.DiscoveryFrequencyMatch channel)
                return channel.name() != null ? channel.name() : "";
            if(match instanceof Map<?,?> channel)
            {
                Object name = channel.get("name");
                return name != null ? String.valueOf(name) : "";
            }
            return "";
        }).map(String::trim).filter(name -> !name.isEmpty()).distinct().toList();
        if(names.isEmpty()) return "This frequency belongs to a known channel.";
        if(names.size() == 1) return "This frequency belongs to " + names.getFirst() + ".";
        return "This frequency belongs to " + names.getFirst() + " and " + (names.size() - 1) +
            " other channel" + (names.size() == 2 ? "." : "s.");
    }

    public synchronized Snapshot start(String id)
    {
        Wizard wizard = requireWizard(id);
        return startSaved(wizard);
    }

    private Snapshot startSaved(Wizard wizard)
    {
        if(wizard.saved == null) throw new IllegalStateException("Save the channel before starting it");
        try
        {
            var start = (java.util.function.Supplier<ChannelAdministrationService.LifecycleResult>) () ->
            {
                DiscoveredTuner selected = requireTuner(wizard.tunerId);
                if(selected.getTuner() != wizard.tuner || !wizard.current())
                    throw new IllegalStateException("The selected tuner changed");
                return mChannels.startAtCurrentCenter(wizard.saved.configurationId(), selected);
            };
            ChannelAdministrationService.LifecycleResult result;
            if(wizard.browseLeaseId != null)
                result = mSettings.handoffBrowse(requireTuner(wizard.tunerId), wizard.browseLeaseId, start).join();
            else
                try(var hold = mSettings.holdForProbe(wizard.tuner))
                {
                    if(!wizard.current()) throw new IllegalStateException("The tuner changed");
                    result = start.get();
                }
            wizard.browseLeaseId = null;
            wizard.running = result.success() && result.state() ==
                ChannelAdministrationService.ProcessingState.RUNNING;
            wizard.startError = wizard.running ? null : result.message();
        }
        catch(RuntimeException exception)
        {
            if(wizard.browseLeaseId != null &&
                !mSettings.verifyBrowse(mTuners.find(wizard.tunerId), wizard.browseLeaseId))
                wizard.browseLeaseId = null;
            wizard.startError = "The channel was saved but could not start. Check tuner availability and retry.";
        }
        return snapshot(wizard);
    }

    public synchronized void cancel(String id)
    {
        Wizard wizard = requireWizard(id);
        wizard.close();
        mWizard = null;
    }

    private Snapshot snapshot(Wizard wizard)
    {
        P25DiscoveryProbe.Status probe = wizard.probe != null ? wizard.probe.status() : null;
        var digital = wizard.digitalProbe != null ? wizard.digitalProbe.status() : null;
        String state = wizard.saved != null ? wizard.running ? "running" : "saved" :
            probe != null ? "running".equals(probe.state()) ? "identifying" : probe.state() :
            digital != null ? "running".equals(digital.state()) ? "identifying" : digital.state() : "ready";
        String reason = probe != null ? probe.reason() : digital != null ? digital.reason() : null;
        if("ready".equals(state) && wizard.evidence == null)
        {
            wizard.evidence = probe != null ? TrunkedDiscoveryEvidence.p25(probe,System.currentTimeMillis()) : digital != null ? digital.evidence() : null;
            if(wizard.evidence != null) resolveDirectory(wizard);
        }
        if(wizard.saved == null && (!wizard.current() || wizard.hold != null && !wizard.hold.valid() ||
            wizard.probe != null && "ready".equals(state) && !wizard.probe.valid() ||
            wizard.digitalProbe != null && "ready".equals(state) && !wizard.digitalProbe.valid()))
        {
            state = "failed";
            reason = "The tuner changed; identify this frequency again";
            wizard.close();
        }
        ChannelAdministrationService.DiscoveryReview review = "ready".equals(state) ?
            wizard.evidence != null ? mChannels.discoveryTrunkedReview(wizard.protocolId,wizard.frequencyHz,wizard.tuner.getPreferredName(),wizard.evidence) :
            mChannels.discoveryReview(wizard.protocolId, wizard.frequencyHz, wizard.tuner.getPreferredName(),
                siteIdentity(probe), probe != null ? probe.selectedModulation() : null) : null;
        if(review != null && wizard.directory.match() != null)
        {
            var template = review.template(); var match = wizard.directory.match();
            template = new ChannelDefinition(null,template.protocolId(),match.systemName(),match.siteName(),match.siteName(),null,template.aliasListId(),template.source(),template.settings(),template.frequencyMap(),template.eventLogs(),template.recorders(),template.auxiliaryDecoders(),template.observed());
            review = new ChannelAdministrationService.DiscoveryReview(review.revision(),template,review.aliasLists(),review.suggestedAliasListId(),review.defaultNewAliasListName());
        }
        return new Snapshot(wizard.id, state, reason, wizard.protocolId, wizard.tunerId, wizard.targetId,
            wizard.frequencyHz, wizard.expiresAt, probe, review, wizard.saved != null ?
                new Saved(wizard.saved.configurationId(), wizard.saved.aliasListId(), wizard.running,
                    wizard.startError) : null, digital, wizard.evidence, wizard.directory);
    }

    private void resolveDirectory(Wizard wizard)
    {
        var resolver = mDirectory;
        if(resolver == null) return;
        wizard.directory = io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver.Result.pending();
        try { mDirectoryWorker.execute(() -> {
            synchronized(SpectrumDiscoveryService.this) { if(mWizard != wizard || wizard.closed) return; }
            var result = resolver.resolve(wizard.stateId,SpectrumSearchService.directoryIdentity(wizard.evidence,wizard.frequencyHz));
            synchronized(SpectrumDiscoveryService.this) { if(mWizard == wizard && !wizard.closed) { wizard.directory = result; wizard.evidence = DiscoveryDirectoryEnrichment.merge(wizard.evidence,result); } }
        }); } catch(java.util.concurrent.RejectedExecutionException exception) { wizard.directory = io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver.Result.manual("unavailable","RadioReference is busy. Review the on-air identity."); }
    }

    private static P25SiteIdentity siteIdentity(P25DiscoveryProbe.Status probe)
    {
        P25DiscoveryProbe.Identity identity = probe != null ? probe.identity() : null;
        return identity != null ? new P25SiteIdentity(identity.wacn(), identity.system(), identity.rfss(),
            identity.site()) : null;
    }

    private DiscoveredTuner requireTuner(String id)
    {
        DiscoveredTuner tuner = mTuners.find(id);
        if(tuner == null) throw new IllegalArgumentException("The selected tuner is no longer available");
        return tuner;
    }

    private Wizard requireWizard(String id)
    {
        expire();
        if(mWizard == null || !mWizard.id.equals(id))
            throw new IllegalStateException("Channel discovery expired; begin again");
        return mWizard;
    }

    private synchronized void expire()
    {
        if(mWizard != null && System.currentTimeMillis() > mWizard.expiresAt)
        {
            mWizard.close();
            mWizard = null;
        }
    }

    @Override public synchronized void close()
    {
        mExpiry.cancel(false);
        closeActiveSession();
        mProbe.close();
        mDigitalProbe.close();
        mDirectoryWorker.shutdownNow();
    }

    public synchronized void closeActiveSession()
    {
        if(mWizard != null) mWizard.close();
        mWizard = null;
    }

    private static final class Wizard implements AutoCloseable
    {
        final String id = UUID.randomUUID().toString();
        final String tunerId;
        final String targetId;
        final long frequencyHz;
        final String protocolId;
        final Tuner tuner;
        final long centerHz;
        final double sampleRate;
        final long maximumExpiresAt = System.currentTimeMillis() + MAXIMUM_LIFETIME_MS;
        long expiresAt = System.currentTimeMillis() + IDLE_EXPIRY_MS;
        String browseLeaseId;
        P25DiscoveryProbe.Session probe;
        DigitalTrunkedDiscoveryProbe.Session digitalProbe;
        TrunkedDiscoveryEvidence evidence;
        Integer stateId;
        boolean closed;
        io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver.Result directory = io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver.Result.manual();
        TunerSettingsService.ProbeHold hold;
        ChannelAdministrationService.DiscoveryCreated saved;
        boolean running;
        String startError;
        Wizard(String tunerId, String targetId, long frequencyHz, String protocolId, String browseLeaseId, Tuner tuner)
        {
            this.tunerId = tunerId; this.targetId = targetId; this.frequencyHz = frequencyHz;
            this.protocolId = protocolId; this.browseLeaseId = browseLeaseId; this.tuner = tuner;
            centerHz = tuner.getTunerController().getFrequency();
            sampleRate = tuner.getTunerController().getSampleRate();
        }
        boolean current()
        {
            return tuner.getTunerController().getFrequency() == centerHz &&
                tuner.getTunerController().getSampleRate() == sampleRate;
        }
        @Override public void close()
        {
            closed = true;
            if(probe != null) probe.close();
            if(digitalProbe != null) digitalProbe.close();
            if(hold != null) { hold.close(); hold = null; }
        }
    }

    public record Eligibility(boolean eligible, String reason, List<Object> matches) {}
    public record SaveRequest(String system, String site, String name, long aliasListId,
                              String newAliasListName, Map<String,Object> settings, long revision,
                              List<ChannelDefinition.FrequencyMapEntry> frequencyMap)
    {
        public SaveRequest { frequencyMap = List.copyOf(frequencyMap != null ? frequencyMap : List.of()); }
        public SaveRequest(String system, String site, String name, long aliasListId,
                           String newAliasListName, Map<String,Object> settings, long revision)
        { this(system, site, name, aliasListId, newAliasListName, settings, revision, List.of()); }
    }
    public record Saved(String configurationId, long aliasListId, boolean running, String startError) {}
    public record Snapshot(String sessionId, String state, String reason, String protocolId, String tunerId,
                           String targetId, long frequencyHz, long expiresAtMs, P25DiscoveryProbe.Status probe,
                           ChannelAdministrationService.DiscoveryReview review, Saved saved, DigitalTrunkedDiscoveryProbe.Status digitalProbe,
                           TrunkedDiscoveryEvidence trunkedEvidence, io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver.Result radioReference) {}
}
