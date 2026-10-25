/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelDefinition;
import io.github.dsheirer.channel.ChannelDefinitionCodec;
import io.github.dsheirer.channel.ChannelProtocolRegistry;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.service.radioreference.RadioReferenceChannelNames;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One short-lived signal check; saved results are retained separately without receiver resources. */
public final class SpectrumDiscoveryService implements AutoCloseable
{
    private static final long IDLE_EXPIRY_MS = 30_000;
    private static final long MAXIMUM_LIFETIME_MS = 300_000;
    private static final Logger mLog = LoggerFactory.getLogger(SpectrumDiscoveryService.class);
    private static final String START_FAILED =
        "The channel was saved but could not start. Check tuner availability and retry.";
    private final ChannelAdministrationService mChannels;
    private final TunerAdministrationService mTuners;
    private final TunerDiagnosticService mDiagnostics;
    private final TunerSettingsService mSettings;
    private final StatsWebDatabase mDatabase;
    private volatile io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver mDirectory;
    private DiscoveryAliasImportService mAliasImporter = new DiscoveryAliasImportService(
        (io.github.dsheirer.service.radioreference.RadioReferenceImportService)null);
    private final java.util.concurrent.ExecutorService mDirectoryWorker = SpectrumSearchService.directoryWorker("click-discovery-directory");
    private final ScheduledFuture<?> mExpiry;
    private final DiscoveryCompletionRegistry mCompletions = new DiscoveryCompletionRegistry();
    private Wizard mWizard;

    SpectrumDiscoveryService(ChannelAdministrationService channels, TunerAdministrationService tuners,
        TunerDiagnosticService diagnostics, TunerSettingsService settings, StatsWebDatabase database)
    {
        mChannels = channels;
        mTuners = tuners;
        mDiagnostics = diagnostics;
        mSettings = settings;
        mDatabase = database;
        mExpiry = ThreadPool.SCHEDULED.scheduleWithFixedDelay(this::expire, 5, 5, TimeUnit.SECONDS);
    }

    public void setRadioReferenceResolver(io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver resolver) { mDirectory = resolver; }
    public void setAliasImporter(io.github.dsheirer.service.radioreference.RadioReferenceImportService importer)
    { mAliasImporter = new DiscoveryAliasImportService(importer); }

    public DiscoveryAliasImportService.Result importAliases(String id, long aliasListId)
    { return mAliasImporter.importAliases(id, aliasListId); }

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
        if(!Set.of("auto", "p25-phase1", "dmr", "nxdn", "am", "nbfm").contains(protocolId))
            throw new IllegalArgumentException("Choose automatic trunked detection or a supported radio type");
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
            if("auto".equals(protocolId))
            {
                // Both protocol families borrow one reservation, exactly as in the band search. Independent
                // holdForProbe calls would contend with each other on the same receiver.
                wizard.hold = mSettings.holdForProbe(tuner);
                wizard.autoP25Probe = new P25DiscoveryProbe(mDiagnostics, wizard.hold);
                wizard.autoDigitalProbe = new DigitalTrunkedDiscoveryProbe(mDiagnostics, wizard.hold);
                wizard.probe = wizard.autoP25Probe.open(targetId, frequencyHz);
                wizard.digitalProbe = wizard.autoDigitalProbe.open(targetId, frequencyHz, "auto");
            }
            else if("p25-phase1".equals(protocolId))
            {
                wizard.hold = mSettings.holdForProbe(tuner);
                wizard.autoP25Probe = new P25DiscoveryProbe(mDiagnostics, wizard.hold);
                wizard.probe = wizard.autoP25Probe.open(targetId, frequencyHz);
            }
            else if(Set.of("dmr","nxdn").contains(protocolId))
            {
                wizard.hold = mSettings.holdForProbe(tuner);
                wizard.autoDigitalProbe = new DigitalTrunkedDiscoveryProbe(mDiagnostics, wizard.hold);
                wizard.digitalProbe = wizard.autoDigitalProbe.open(targetId, frequencyHz, protocolId);
            }
            else wizard.hold = mSettings.holdForProbe(tuner);
            mWizard = wizard;
            if(wizard.automatic) beginAutomaticCheck(wizard);
            return snapshot(wizard);
        }
        catch(RuntimeException exception)
        {
            wizard.close();
            throw exception;
        }
    }

    private void beginAutomaticCheck(Wizard wizard)
    {
        wizard.detectionWorker = new Thread(() ->
        {
            try
            {
                var result = TrunkedSignalDetector.check(wizard.probe::status, wizard.digitalProbe::status,
                    () -> wizard.closed);
                synchronized(this)
                {
                    if(wizard.closed || mWizard != wizard) return;
                    wizard.detection = result;
                    if(result.evidence() == null) wizard.close();
                    else
                    {
                        wizard.protocolId = result.evidence().protocolId();
                        // Keep the confirmed probe valid through save; the other family no longer needs samples.
                        if("p25-phase1".equals(wizard.protocolId)) wizard.digitalProbe.close();
                        else wizard.probe.close();
                    }
                }
            }
            catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
            catch(RuntimeException exception)
            {
                synchronized(this)
                {
                    if(wizard.closed || mWizard != wizard) return;
                    wizard.detectionFailure = "The receiver could not check this signal. Try again.";
                    wizard.close();
                }
            }
        }, "spectrum-trunked-check");
        wizard.detectionWorker.setDaemon(true);
        wizard.detectionWorker.setPriority(Thread.MIN_PRIORITY);
        wizard.detectionWorker.start();
    }

    public synchronized Snapshot status(String id)
    {
        Snapshot completed = completed(id);
        if(completed != null) return completed;
        Wizard wizard = requireWizard(id);
        wizard.expiresAt = Math.min(wizard.maximumExpiresAt, System.currentTimeMillis() + IDLE_EXPIRY_MS);
        return snapshot(wizard);
    }

    public synchronized Snapshot save(String id, SaveRequest request)
    {
        Snapshot completed = completed(id);
        if(completed != null) return completed;
        Wizard wizard = requireWizard(id);
        Snapshot current = snapshot(wizard);
        if(!"ready".equals(current.state())) throw new IllegalStateException("Complete identification before saving");
        SpectrumSearchService.requireDirectoryReady(List.of(wizard.directory));
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
        ChannelAdministrationService.DiscoveryCreated created;
        try
        {
            created = saveEvidence != null ? mChannels.createTrunkedDiscovered(definition,saveEvidence,request.newAliasListName(),request.revision(),true) :
                mChannels.createDiscovered(definition, identity, request.newAliasListName(), request.revision());
        }
        catch(ConfigurationManager.ConfigurationPublicationException failure)
        {
            // A committed configuration that could not publish requires a receiver restart. Its temporary RF
            // check must end even though the original failure, including any committed IDs, remains the result.
            try { wizard.close(); }
            catch(RuntimeException | Error cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            finally { if(mWizard == wizard) mWizard = null; }
            throw failure;
        }
        String aliasName = review.aliasLists().stream().filter(list -> list.id() == created.aliasListId())
            .map(ChannelAdministrationService.DiscoveryAliasList::name).findFirst()
            .orElseGet(() -> savedAliasListName(request.newAliasListName(), definition));
        wizard.aliasImport.register(created.aliasListId(), aliasName, definition.system(), wizard.directory);
        mAliasImporter.retain(wizard.id, wizard.aliasImport);
        try
        {
            Saved saved = startInitialSaved(wizard, created);
            return mCompletions.retain(savedSnapshot(current, saved, wizard.aliasImport.snapshot()));
        }
        finally
        {
            try { wizard.close(); }
            finally { if(mWizard == wizard) mWizard = null; }
        }
    }

    static String savedAliasListName(String requestedName, ChannelDefinition definition)
    {
        return requestedName != null ? requestedName.strip() :
            ChannelAdministrationService.discoveryAliasListName(definition.system(), definition.name());
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
        Snapshot completed = completed(id);
        if(completed == null) throw new IllegalStateException("Save the channel before starting it");
        Saved prior = completed.saved();
        String leaseId = prior.browseLeaseId();
        boolean running = false;
        String error = null;
        DiscoveredTuner selected = leaseId != null ? mTuners.find(completed.tunerId()) : null;
        try
        {
            if(leaseId != null && mChannels.get(prior.configurationId()).processingState() !=
                ChannelAdministrationService.ProcessingState.RUNNING)
            {
                if(selected == null)
                    throw new TunerSettingsService.SettingUnavailableException("Selected tuner is unavailable");
                requireSuccessfulStart(mSettings.startSavedChannel(selected, leaseId,
                    () -> mChannels.startAtCurrentCenter(prior.configurationId(), selected),
                    () -> mChannels.setProcessing(List.of(prior.configurationId()), true).results().getFirst()).join());
            }
            else if(leaseId == null)
                requireSuccessfulStart(mChannels.setProcessing(List.of(prior.configurationId()), true).results().getFirst());
            running = true;
        }
        catch(RuntimeException failure)
        {
            error = startFailureMessage(failure);
            mLog.warn("Unable to start saved Spectrum channel: {}", error,
                TunerSettingsService.handoffFailureForLogging(failure));
        }
        String retainedLease = selected != null && leaseId != null && mSettings.ownsBrowse(selected, leaseId) ? leaseId : null;
        List<TunerSettingsService.ChannelInfo> previous = selected != null ? mSettings.previousChannelsNotResumed(selected) :
            prior.previousChannelsNotResumed();
        return mCompletions.retain(savedSnapshot(completed,
            new Saved(prior.configurationId(), prior.aliasListId(), running, error, retainedLease, previous),
            completed.aliasImport()));
    }

    private Saved startInitialSaved(Wizard wizard, ChannelAdministrationService.DiscoveryCreated created)
    {
        boolean running = false;
        String error = null;
        try
        {
            // Detach temporary sources while retaining this wizard's reservation until the allocation worker owns it.
            wizard.closeProbeResources();
            DiscoveredTuner selected = mTuners.find(wizard.tunerId);
            if(selected == null)
                throw new TunerSettingsService.SettingUnavailableException("Selected tuner is unavailable");
            var start = (java.util.function.Supplier<ChannelAdministrationService.LifecycleResult>) () ->
            {
                if(selected.getTuner() != wizard.tuner || !wizard.current())
                    throw new TunerSettingsService.SettingUnavailableException("The tuner changed; retry the saved channel");
                return mChannels.startAtCurrentCenter(created.configurationId(), selected);
            };
            if(wizard.browseLeaseId != null)
                requireSuccessfulStart(mSettings.handoffBrowse(selected, wizard.browseLeaseId, wizard.hold, start).join());
            else
            {
                if(wizard.hold == null) wizard.hold = mSettings.holdForProbe(wizard.tuner);
                if(!wizard.hold.valid() || !wizard.current())
                    throw new TunerSettingsService.SettingUnavailableException("The tuner changed; retry the saved channel");
                requireSuccessfulStart(start.get());
            }
            wizard.browseLeaseId = null;
            running = true;
        }
        catch(RuntimeException exception)
        {
            error = startFailureMessage(exception);
            mLog.warn("Unable to start saved Spectrum channel: {}", error,
                TunerSettingsService.handoffFailureForLogging(exception));
        }
        finally
        {
            // Admission or queue failure may leave the reservation with this caller rather than the handoff worker.
            wizard.releaseHold();
            DiscoveredTuner selected = mTuners.find(wizard.tunerId);
            if(wizard.browseLeaseId != null && (selected == null || !mSettings.ownsBrowse(selected, wizard.browseLeaseId)))
                wizard.browseLeaseId = null;
        }
        DiscoveredTuner selected = mTuners.find(wizard.tunerId);
        return new Saved(created.configurationId(), created.aliasListId(), running, error, wizard.browseLeaseId,
            selected != null ? mSettings.previousChannelsNotResumed(selected) : List.of());
    }

    private Snapshot completed(String id)
    {
        Snapshot completed = mCompletions.get(id);
        if(completed == null) return null;
        var imported = mAliasImporter.retainedResult(id);
        return imported != null ? savedSnapshot(completed, completed.saved(), imported) : completed;
    }

    private static Snapshot savedSnapshot(Snapshot source, Saved saved, DiscoveryAliasImportService.Result imported)
    {
        return new Snapshot(source.sessionId(), saved.running() ? "running" : "saved", source.reason(),
            source.protocolId(), source.tunerId(), source.targetId(), source.frequencyHz(), source.expiresAtMs(),
            source.probe(), null, saved, source.digitalProbe(), source.trunkedEvidence(), source.radioReference(), imported);
    }

    static ChannelAdministrationService.LifecycleResult requireSuccessfulStart(
        ChannelAdministrationService.LifecycleResult result)
    {
        if(result == null || !result.success() || result.state() != ChannelAdministrationService.ProcessingState.RUNNING)
            throw new SavedChannelStartException(result != null && result.message() != null && !result.message().isBlank() ?
                result.message() : START_FAILED);
        return result;
    }

    static String startFailureMessage(Throwable failure)
    {
        Throwable cause = TunerSettingsService.unwrapHandoffFailure(failure);
        if(cause instanceof ChannelAdministrationService.ConfigurationBusyException)
            return "Channel configuration is busy. Try starting again.";
        if(cause instanceof SavedChannelStartException) return cause.getMessage();
        String message = TunerSettingsService.handoffFailureMessage(cause);
        return message != null ? message : START_FAILED;
    }

    private static final class SavedChannelStartException extends IllegalStateException
    {
        private SavedChannelStartException(String message) { super(message); }
    }

    public synchronized void cancel(String id)
    {
        if(mCompletions.get(id) != null) return;
        Wizard wizard = requireWizard(id);
        wizard.close();
        mWizard = null;
    }

    private Snapshot snapshot(Wizard wizard)
    {
        var detection = wizard.detection;
        P25DiscoveryProbe.Status probe = detection != null ? detection.p25() :
            wizard.probe != null ? wizard.probe.status() : null;
        var digital = detection != null ? detection.digital() :
            wizard.digitalProbe != null ? wizard.digitalProbe.status() : null;
        String state = wizard.automatic ? wizard.detectionFailure != null ? "failed" : detection == null ? "identifying" :
                detection.evidence() != null ? "ready" : "inconclusive" :
            probe != null ? "running".equals(probe.state()) ? "identifying" : probe.state() :
            digital != null ? "running".equals(digital.state()) ? "identifying" : digital.state() : "ready";
        String reason = wizard.automatic ? wizard.detectionFailure != null ? wizard.detectionFailure :
            detection == null ? "Checking P25, DMR, and NXDN trunked control channels…" :
                detection.evidence() == null ? "No supported trunked control channel was found. Choose a setup method and try again." : null :
            probe != null ? probe.reason() : digital != null ? digital.reason() : null;
        if("ready".equals(state) && wizard.evidence == null)
        {
            wizard.evidence = detection != null ? detection.evidence() :
                probe != null ? TrunkedDiscoveryEvidence.p25(probe,System.currentTimeMillis()) : digital != null ? digital.evidence() : null;
            if(wizard.evidence != null) resolveDirectory(wizard);
        }
        if(Set.of("identifying", "ready").contains(state) &&
            (!wizard.current() || wizard.hold != null && !wizard.hold.valid() ||
            wizard.probe != null && "ready".equals(state) && "p25-phase1".equals(wizard.protocolId) && !wizard.probe.valid() ||
            wizard.digitalProbe != null && "ready".equals(state) && Set.of("dmr", "nxdn").contains(wizard.protocolId) && !wizard.digitalProbe.valid()))
        {
            state = "failed";
            reason = "The tuner changed; identify this frequency again";
            if(wizard.automatic) wizard.detectionFailure = reason;
            wizard.close();
        }
        ChannelAdministrationService.DiscoveryReview review = "ready".equals(state) ?
            wizard.evidence != null ? mChannels.discoveryTrunkedReview(wizard.protocolId,wizard.frequencyHz,wizard.tuner.getPreferredName(),wizard.evidence) :
            mChannels.discoveryReview(wizard.protocolId, wizard.frequencyHz, wizard.tuner.getPreferredName(),
                siteIdentity(probe), probe != null ? probe.selectedModulation() : null) : null;
        review = directoryReview(review, wizard.directory);
        return new Snapshot(wizard.id, state, reason, wizard.protocolId, wizard.tunerId, wizard.targetId,
            wizard.frequencyHz, wizard.expiresAt, probe, review, null,
                digital, wizard.evidence, wizard.directory, wizard.aliasImport.snapshot());
    }

    static ChannelAdministrationService.DiscoveryReview directoryReview(
        ChannelAdministrationService.DiscoveryReview review,
        io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver.Result directory)
    {
        if(review == null || directory == null || directory.match() == null) return review;
        var template = review.template();
        var match = directory.match();
        String system = match.systemName() != null && !match.systemName().isBlank() ?
            match.systemName() : template.system();
        String site = match.siteName() != null && !match.siteName().isBlank() ? match.siteName() : template.site();
        Long suggested = review.suggestedAliasListId() != null ? review.suggestedAliasListId() :
            ChannelAdministrationService.suggestedDiscoveryAliasListId(review.aliasLists(), system);
        long frequency = template.source().preferredFrequencyHz() != null ? template.source().preferredFrequencyHz() :
            template.source().frequenciesHz().stream().findFirst().orElse(0L);
        template = new ChannelDefinition(null, template.protocolId(), system, site,
            RadioReferenceChannelNames.trunked(system, site, frequency).name(), null,
            suggested != null ? suggested : template.aliasListId(), template.source(),
            template.settings(), template.frequencyMap(), template.eventLogs(), template.recorders(),
            template.auxiliaryDecoders(), template.observed());
        return new ChannelAdministrationService.DiscoveryReview(review.revision(), template, review.aliasLists(),
            suggested, ChannelAdministrationService.discoveryAliasListName(system,
                review.defaultNewAliasListName()));
    }

    private void resolveDirectory(Wizard wizard)
    {
        var resolver = mDirectory;
        if(resolver == null) return;
        wizard.directory = io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver.Result.pending();
        try { wizard.directoryFuture = mDirectoryWorker.submit(() -> {
            synchronized(SpectrumDiscoveryService.this) { if(mWizard != wizard || wizard.closed) return; }
            var result = resolver.resolve(wizard.stateId,SpectrumSearchService.directoryIdentity(wizard.evidence,wizard.frequencyHz));
            synchronized(SpectrumDiscoveryService.this) { if(mWizard == wizard && !wizard.closed && !Thread.currentThread().isInterrupted()) { wizard.directory = result; wizard.evidence = DiscoveryDirectoryEnrichment.merge(wizard.evidence,result); } }
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
        mDirectoryWorker.shutdownNow();
        mAliasImporter.clear();
        mCompletions.clear();
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
        String protocolId;
        final boolean automatic;
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
        volatile boolean closed;
        P25DiscoveryProbe autoP25Probe;
        DigitalTrunkedDiscoveryProbe autoDigitalProbe;
        Thread detectionWorker;
        java.util.concurrent.Future<?> directoryFuture;
        TrunkedSignalDetector.Result detection;
        String detectionFailure;
        io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver.Result directory = io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver.Result.manual();
        TunerSettingsService.ProbeHold hold;
        final DiscoveryAliasImportService.Batch aliasImport = new DiscoveryAliasImportService.Batch();
        Wizard(String tunerId, String targetId, long frequencyHz, String protocolId, String browseLeaseId, Tuner tuner)
        {
            this.tunerId = tunerId; this.targetId = targetId; this.frequencyHz = frequencyHz;
            this.protocolId = protocolId; this.browseLeaseId = browseLeaseId; this.tuner = tuner;
            automatic = "auto".equals(protocolId);
            centerHz = tuner.getTunerController().getFrequency();
            sampleRate = tuner.getTunerController().getSampleRate();
        }
        boolean current()
        {
            return tuner.getTunerController().getFrequency() == centerHz &&
                tuner.getTunerController().getSampleRate() == sampleRate;
        }
        void closeProbeResources()
        {
            closed = true;
            if(directoryFuture != null) { directoryFuture.cancel(true); directoryFuture = null; }
            if(detectionWorker != null) detectionWorker.interrupt();
            if(probe != null) probe.close();
            if(digitalProbe != null) digitalProbe.close();
            if(autoP25Probe != null) autoP25Probe.close();
            if(autoDigitalProbe != null) autoDigitalProbe.close();
        }
        void releaseHold()
        {
            if(hold != null) { hold.close(); hold = null; }
        }
        @Override public void close()
        {
            try { closeProbeResources(); }
            finally { releaseHold(); }
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
    public record Saved(String configurationId, long aliasListId, boolean running, String startError,
                        String browseLeaseId, List<TunerSettingsService.ChannelInfo> previousChannelsNotResumed)
    {
        public Saved
        {
            previousChannelsNotResumed = List.copyOf(previousChannelsNotResumed != null ? previousChannelsNotResumed : List.of());
        }
        public Saved(String configurationId, long aliasListId, boolean running, String startError)
        { this(configurationId, aliasListId, running, startError, null, List.of()); }
    }
    public record Snapshot(String sessionId, String state, String reason, String protocolId, String tunerId,
                           String targetId, long frequencyHz, long expiresAtMs, P25DiscoveryProbe.Status probe,
                           ChannelAdministrationService.DiscoveryReview review, Saved saved, DigitalTrunkedDiscoveryProbe.Status digitalProbe,
                           TrunkedDiscoveryEvidence trunkedEvidence, io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver.Result radioReference,
                           DiscoveryAliasImportService.Result aliasImport)
    {
        public Snapshot(String sessionId, String state, String reason, String protocolId, String tunerId,
                        String targetId, long frequencyHz, long expiresAtMs, P25DiscoveryProbe.Status probe,
                        ChannelAdministrationService.DiscoveryReview review, Saved saved,
                        DigitalTrunkedDiscoveryProbe.Status digitalProbe, TrunkedDiscoveryEvidence trunkedEvidence,
                        io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver.Result radioReference)
        { this(sessionId, state, reason, protocolId, tunerId, targetId, frequencyHz, expiresAtMs, probe, review, saved,
            digitalProbe, trunkedEvidence, radioReference, DiscoveryAliasImportService.Result.EMPTY); }
    }
}
