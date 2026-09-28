/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.broadcast;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasConfigurationSnapshot;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.audio.broadcast.broadcastify.*;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.UserFeed;
import java.awt.GraphicsEnvironment;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javafx.application.Platform;

/** Detached commands and bounded snapshots for the administrator Streaming page. No receiver callback waits on it. */
public final class StreamingAdministrationService
{
    private static final long FX_QUEUE_TIMEOUT_SECONDS = 15;
    private final ConfigurationManager mConfigurationManager;
    private final boolean mUseDesktopThread;
    private final Semaphore mCommandAdmission = new Semaphore(1);
    private final Semaphore mNetworkAdmission = new Semaphore(1);
    private volatile List<UserFeed> mFeeds = List.of();
    private volatile long mFeedsExpires;

    public StreamingAdministrationService(ConfigurationManager manager)
    { this(manager, !GraphicsEnvironment.isHeadless()); }
    StreamingAdministrationService(ConfigurationManager manager, boolean desktop)
    { mConfigurationManager = Objects.requireNonNull(manager); mUseDesktopThread = desktop; }

    public record Status(String configurationId, String name, String provider, String providerLabel, boolean enabled,
                         String state, String stateLabel, boolean attention, String lastError,
                         Integer queued, Integer sent, Integer agedOff, Integer errors) {}
    public record Catalog(String revision, List<Status> destinations) {}
    public record Entry(String revision, StreamingConfigurationCodec.Definition destination, Status status,
                        References references) {}
    public record References(int aliases, List<ListReference> aliasLists) {}
    public record ListReference(long id, String name, boolean unmatched, boolean newAliases) {}
    public record Site(String configurationId, String name, long aliasListId, String aliasListName) {}
    public record Options(String revision, List<StreamingConfigurationCodec.Provider> providers, List<Site> sites) {}
    public record AliasRow(long id, String name, long aliasListId, String aliasListName, String identifier, boolean assigned) {}
    public record AliasPage(String revision, List<AliasRow> items, int total, int offset, int limit) {}
    public record Feed(int id, String name, boolean configured) {}
    public record TestResult(boolean success, String message) {}
    public record Mutation(String revision, String configurationId) {}

    public Catalog catalog()
    {
        return admitted(() -> onConfigurationThread(() -> new Catalog(revision(),
            mConfigurationManager.getBroadcastModel().getConfiguredBroadcasts().stream().map(this::status).toList())));
    }

    public Options options()
    {
        return admitted(() -> onConfigurationThread(() ->
        {
            List<Site> sites = new ArrayList<>();
            for(var list: mConfigurationManager.getAliasModel().aliasListDefinitions())
                for(var channel: BroadcastifyCallSiteConfiguration.eligibleChannels(
                    mConfigurationManager.getChannelModel().getChannels(), list))
                    sites.add(new Site(channel.getConfigurationId(),
                        String.join(" · ", Objects.toString(channel.getSystem(), ""),
                            Objects.toString(channel.getSite(), ""), Objects.toString(channel.getName(), "")),
                        list.getId(), list.getName()));
            return new Options(revision(), StreamingConfigurationCodec.providers(), sites);
        }));
    }

    public Entry get(String id)
    {
        return admitted(() -> onConfigurationThread(() ->
        {
            BroadcastConfiguration source = require(id);
            ConfiguredBroadcast configured = mConfigurationManager.getBroadcastModel().getConfiguredBroadcasts().stream()
                .filter(item -> item.getBroadcastConfiguration() == source).findFirst().orElseThrow();
            return new Entry(revision(), StreamingConfigurationCodec.view(source), status(configured), references(id));
        }));
    }

    public StreamingConfigurationCodec.Definition template(String provider)
    { return StreamingConfigurationCodec.view(StreamingConfigurationCodec.template(provider)); }

    public Mutation save(String id, String provider, Map<String,Object> settings, String expectedRevision)
    {
        return admitted(() -> onConfigurationThread(() -> mConfigurationManager.applyConfigurationMutation(() ->
        {
            requireRevision(expectedRevision);
            BroadcastConfiguration original = id == null ? StreamingConfigurationCodec.template(provider) : require(id);
            if(!original.getBroadcastServerType().name().equals(provider))
                throw new IllegalArgumentException("The provider of an existing destination cannot be changed");
            BroadcastConfiguration candidate = StreamingConfigurationCodec.apply(original, settings);
            validateCandidate(candidate);
            List<BroadcastConfiguration> proposed = new ArrayList<>(mConfigurationManager.getBroadcastModel().getBroadcastConfigurations());
            if(id == null) proposed.add(candidate);
            else proposed.replaceAll(item -> item.getConfigurationId().equals(id) ? candidate : item);
            mConfigurationManager.commitAndPublishStreamingConfiguration(proposed, candidate.getConfigurationId());
            return new Mutation(revision(), candidate.getConfigurationId());
        })));
    }

    public Mutation delete(String id, String expectedRevision)
    {
        return admitted(() -> onConfigurationThread(() -> mConfigurationManager.applyConfigurationMutation(() ->
        {
            requireRevision(expectedRevision); require(id);
            References references = references(id);
            if(references.aliases() > 0 || !references.aliasLists().isEmpty())
                throw new IllegalArgumentException("Remove this destination from its aliases and Alias List defaults before deleting it");
            List<BroadcastConfiguration> proposed = mConfigurationManager.getBroadcastModel().getBroadcastConfigurations()
                .stream().filter(item -> !item.getConfigurationId().equals(id)).toList();
            mConfigurationManager.commitAndPublishStreamingConfiguration(proposed, id);
            return new Mutation(revision(), id);
        })));
    }

    public AliasPage aliases(String id, String search, boolean assignedOnly, int offset, int limit)
    {
        if(offset < 0 || limit < 1 || limit > 100 || search == null || search.length() > 256)
            throw new IllegalArgumentException("Invalid alias search");
        return admitted(() -> onConfigurationThread(() ->
        {
            require(id);
            String term = search.toLowerCase(Locale.ROOT);
            List<Alias> filtered = mConfigurationManager.getAliasModel().getAliases().stream()
                .filter(alias -> !assignedOnly || alias.hasBroadcastConfiguration(id))
                .filter(alias -> term.isEmpty() || (Objects.toString(alias.getName(), "") + " " +
                    Objects.toString(alias.getAliasListName(), "") + " " + Objects.toString(alias.getMatchIdentifier(), ""))
                    .toLowerCase(Locale.ROOT).contains(term)).toList();
            List<AliasRow> items = filtered.stream().skip(offset).limit(limit).map(alias ->
                new AliasRow(alias.getId(), alias.getName(), alias.getAliasListId(), alias.getAliasListName(),
                    Objects.toString(alias.getMatchIdentifier(), ""), alias.hasBroadcastConfiguration(id))).toList();
            return new AliasPage(revision(), items, filtered.size(), offset, limit);
        }));
    }

    /** Applies only explicit changes, preserving selections outside the current search/page. */
    public Mutation assign(String id, List<Long> add, List<Long> remove, String expectedRevision)
    {
        if(add == null || remove == null || add.size() + remove.size() > 500 ||
            add.stream().anyMatch(Objects::isNull) || remove.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Select at most 500 aliases per save");
        Set<Long> added = new HashSet<>(add), removed = new HashSet<>(remove), changed = new HashSet<>(added);
        if(added.size() != add.size() || removed.size() != remove.size() || !Collections.disjoint(added, removed))
            throw new IllegalArgumentException("Alias changes contain duplicates");
        changed.addAll(removed);
        return admitted(() -> onConfigurationThread(() -> mConfigurationManager.applyConfigurationMutation(() ->
        {
            requireRevision(expectedRevision);
            BroadcastConfiguration destination = require(id);
            AliasConfigurationSnapshot proposed = mConfigurationManager.createDetachedAliasConfigurationSnapshot();
            Set<Long> found = new HashSet<>();
            for(Alias alias: proposed.aliases())
            {
                if(changed.contains(alias.getId())) found.add(alias.getId());
                if(added.contains(alias.getId())) alias.addBroadcastChannel(new BroadcastChannel(id, destination.getName()));
                if(removed.contains(alias.getId())) alias.removeBroadcastConfiguration(id);
            }
            if(!found.equals(changed)) throw new IllegalArgumentException("An alias no longer exists; reload and try again");
            if(!changed.isEmpty()) mConfigurationManager.commitAndPublishAliasConfiguration(proposed,
                new ConfigurationManager.AliasConfigurationPublication(changed, false, false, false));
            return new Mutation(revision(), id);
        })));
    }

    public List<Feed> feeds()
    {
        if(!mNetworkAdmission.tryAcquire()) throw new ConfigurationBusyException();
        try
        {
            mConfigurationManager.ensureStoredRadioReferenceSession();
            List<UserFeed> feeds = List.copyOf(mConfigurationManager.getRadioReferenceDirectoryService().userFeeds());
            if(feeds.size() > 1000) throw new IllegalStateException("Too many feeds returned by the directory");
            mFeeds = feeds; mFeedsExpires = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
            return admitted(() -> onConfigurationThread(() -> feeds.stream().map(feed ->
                new Feed(feed.id(), feed.description(), mConfigurationManager.getBroadcastModel().getBroadcastConfigurations()
                    .stream().anyMatch(item -> item instanceof BroadcastifyFeedConfiguration configured &&
                        configured.getFeedID() == feed.id()))).toList()));
        }
        catch(ConfigurationBusyException exception) { throw exception; }
        catch(Exception exception) { throw new IllegalArgumentException("Unable to load feeds. Check the RadioReference account and try again"); }
        finally { mNetworkAdmission.release(); }
    }

    public Mutation addFeed(int feedId, String expectedRevision)
    {
        if(System.nanoTime() > mFeedsExpires) throw new IllegalArgumentException("Refresh the available feeds before adding one");
        UserFeed feed = mFeeds.stream().filter(item -> item.id() == feedId).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Refresh the available feeds before adding one"));
        return admitted(() -> onConfigurationThread(() -> mConfigurationManager.applyConfigurationMutation(() ->
        {
            requireRevision(expectedRevision);
            var candidate = BroadcastifyFeedConfiguration.from(feed);
            candidate.setEnabled(false);
            if(mConfigurationManager.getBroadcastModel().getBroadcastConfigurations().stream().anyMatch(item ->
                item instanceof BroadcastifyFeedConfiguration existing && existing.getFeedID() == feedId))
                throw new IllegalArgumentException("This feed is already configured");
            validateCandidate(candidate);
            List<BroadcastConfiguration> proposed = new ArrayList<>(mConfigurationManager.getBroadcastModel().getBroadcastConfigurations());
            proposed.add(candidate);
            mConfigurationManager.commitAndPublishStreamingConfiguration(proposed, candidate.getConfigurationId());
            return new Mutation(revision(), candidate.getConfigurationId());
        })));
    }

    public TestResult test(String id, String provider, Map<String,Object> settings)
    {
        if(!mNetworkAdmission.tryAcquire()) throw new ConfigurationBusyException();
        try
        {
            BroadcastConfiguration draft = admitted(() -> onConfigurationThread(() ->
            {
                BroadcastConfiguration source = id == null ? StreamingConfigurationCodec.template(provider) : require(id);
                if(!source.getBroadcastServerType().name().equals(provider) ||
                    !StreamingConfigurationCodec.testable(source.getBroadcastServerType()))
                    throw new IllegalArgumentException("This provider does not have a connection test");
                return StreamingConfigurationCodec.apply(source, settings);
            }));
            BroadcastifyCallConfiguration calls = (BroadcastifyCallConfiguration)draft;
            if(calls.getApiKey() == null || calls.getApiKey().isBlank() || calls.getSystemID() < 1)
                throw new IllegalArgumentException("Enter an API key and System ID before testing");
            String result = BroadcastifyCallBroadcaster.testConnection(calls);
            boolean success = result != null && result.toLowerCase(Locale.ROOT).startsWith("ok") && result.endsWith("Status Code:200");
            return new TestResult(success, success ? "Connection test succeeded" :
                "Connection test failed. Check the service address, API key, System ID, and network connection");
        }
        finally { mNetworkAdmission.release(); }
    }

    private void validateCandidate(BroadcastConfiguration candidate)
    {
        if(mConfigurationManager.getBroadcastModel().getBroadcastConfigurations().stream().anyMatch(item ->
            !item.getConfigurationId().equals(candidate.getConfigurationId()) && Objects.equals(item.getName(), candidate.getName())))
            throw new IllegalArgumentException("A destination with this name already exists");
        if(candidate.isEnabled() && candidate instanceof BroadcastifyCallSiteConfiguration site &&
            !site.hasValidSiteSelection(mConfigurationManager.getAliasModel(), mConfigurationManager.getChannelModel().getChannels()))
            throw new IllegalArgumentException("Choose an eligible Alias List and trunked site before enabling this destination");
    }

    private References references(String id)
    {
        int count = (int)mConfigurationManager.getAliasModel().getAliases().stream().filter(alias -> alias.hasBroadcastConfiguration(id)).count();
        List<ListReference> lists = mConfigurationManager.getAliasModel().aliasListDefinitions().stream().map(list ->
            new ListReference(list.getId(), list.getName(), list.getUnmatchedTalkgroupPolicy().getStreamDestinations().stream()
                .anyMatch(route -> id.equals(route.getConfigurationId())), list.getNewAliasBehavior().getStreamDestinations().stream()
                .anyMatch(route -> id.equals(route.getConfigurationId())))).filter(list -> list.unmatched() || list.newAliases()).toList();
        return new References(count, lists);
    }

    private Status status(ConfiguredBroadcast configured)
    {
        BroadcastConfiguration configuration = configured.getBroadcastConfiguration();
        AbstractAudioBroadcaster<?> sender = configured.getAudioBroadcaster();
        BroadcastState state = !configuration.isEnabled() ? BroadcastState.DISABLED :
            sender != null ? sender.getBroadcastState() : configuration.isValid() ? BroadcastState.READY : BroadcastState.CONFIGURATION_ERROR;
        if(state == null) state = BroadcastState.READY;
        BroadcastState error = sender != null ? sender.getLastBadBroadcastState() : null;
        return new Status(configuration.getConfigurationId(), configuration.getName(), configuration.getBroadcastServerType().name(),
            configuration.getBroadcastServerType().toString(), configuration.isEnabled(), state.name(), state.toString(),
            configuration.isEnabled() && state != BroadcastState.CONNECTED && state != BroadcastState.READY && state != BroadcastState.CONNECTING,
            error == null ? null : error.toString(), sender == null ? null : sender.getAudioQueueSizeSnapshot(),
            sender == null ? null : sender.getStreamedAudioCount(), sender == null ? null : sender.getAgedOffAudioCount(),
            sender == null ? null : sender.getAudioErrorCount());
    }

    private BroadcastConfiguration require(String id)
    {
        try { if(!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
        catch(Exception exception) { throw new IllegalArgumentException("Invalid destination identity"); }
        BroadcastConfiguration source = mConfigurationManager.getBroadcastModel().getBroadcastConfiguration(id);
        if(source == null) throw new NotFoundException();
        return source;
    }
    private String revision()
    { return mConfigurationManager.getStreamingConfigurationRevision() + ":" + mConfigurationManager.getAliasConfigurationRevision() +
        ":" + mConfigurationManager.getChannelConfigurationRevision(); }
    private void requireRevision(String expected)
    { if(!revision().equals(expected)) throw new StaleRevisionException(); }
    private <T> T admitted(Supplier<T> operation)
    {
        if(!mCommandAdmission.tryAcquire()) throw new ConfigurationBusyException();
        try { return operation.get(); } finally { mCommandAdmission.release(); }
    }
    public static final class NotFoundException extends RuntimeException {}
    public static final class StaleRevisionException extends RuntimeException {}
    public static final class ConfigurationBusyException extends RuntimeException {}
    public static final class NotInitializedException extends RuntimeException {}
    private <T> T onConfigurationThread(Supplier<T> operation)
    {
        Objects.requireNonNull(operation);
        requireInitialized();
        if(!mUseDesktopThread)
        {
            AtomicReference<T> result = new AtomicReference<>();
            AtomicReference<RuntimeException> failure = new AtomicReference<>();
            mConfigurationManager.runHeadlessWebConfigurationTask(() ->
            {
                try
                {
                    requireInitialized();
                    result.set(operation.get());
                }
                catch(RuntimeException exception)
                {
                    failure.set(exception);
                }
            });
            if(failure.get() != null) throw failure.get();
            return result.get();
        }
        if(Platform.isFxApplicationThread()) return operation.get();

        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicReference<DispatchState> state = new AtomicReference<>(DispatchState.QUEUED);
        Platform.runLater(() ->
        {
            if(!state.compareAndSet(DispatchState.QUEUED, DispatchState.RUNNING)) return;
            try
            {
                requireInitialized();
                result.complete(operation.get());
            }
            catch(Throwable throwable)
            {
                result.completeExceptionally(throwable);
            }
        });
        try
        {
            return result.get(FX_QUEUE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        catch(TimeoutException exception)
        {
            if(state.compareAndSet(DispatchState.QUEUED, DispatchState.CANCELLED))
                throw new ConfigurationBusyException();
            return joinResult(result);
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            if(state.compareAndSet(DispatchState.QUEUED, DispatchState.CANCELLED))
                throw new ConfigurationBusyException();
            return joinResult(result);
        }
        catch(ExecutionException exception)
        {
            if(exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new CompletionException(exception.getCause());
        }
    }

    private static <T> T joinResult(CompletableFuture<T> result)
    {
        try
        {
            return result.join();
        }
        catch(CompletionException exception)
        {
            if(exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw exception;
        }
    }

    private void requireInitialized()
    {
        if(!mConfigurationManager.isInitialized()) throw new NotInitializedException();
    }

    private enum DispatchState { QUEUED, RUNNING, CANCELLED }
}
