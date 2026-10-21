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
import io.github.dsheirer.configuration.ConfigurationEditVersion;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.UserFeed;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Detached commands and bounded snapshots for the administrator Streaming page. No receiver callback waits on it. */
public final class StreamingAdministrationService
{
    private final ConfigurationManager mConfigurationManager;
    private final Semaphore mCommandAdmission = new Semaphore(1);
    private final Semaphore mNetworkAdmission = new Semaphore(1);
    private volatile List<UserFeed> mFeeds = List.of();
    private volatile long mFeedsExpires;

    public StreamingAdministrationService(ConfigurationManager manager)
    { mConfigurationManager = Objects.requireNonNull(manager); }

    public record Status(String configurationId, String name, String provider, String providerLabel, boolean enabled,
                         String state, String stateLabel, boolean attention, String lastError,
                         Integer queued, Integer sent, Integer agedOff, Integer errors) {}
    public record Catalog(List<Status> destinations) {}
    public record Entry(String editRevision, StreamingConfigurationCodec.Definition destination, Status status,
                        References references) {}
    public record References(int aliases, List<ListReference> aliasLists) {}
    public record ListReference(long id, String name, boolean unmatched, boolean newAliases) {}
    public record Site(String configurationId, String name, long aliasListId, String aliasListName) {}
    public record Options(List<StreamingConfigurationCodec.Provider> providers, List<Site> sites) {}
    public record AliasRow(long id, String name, long aliasListId, String aliasListName, String identifier, boolean assigned) {}
    public record AliasPage(String revision, List<AliasRow> items, int total, int offset, int limit) {}
    public record Feed(int id, String name, boolean configured) {}
    public record TestResult(boolean success, String message) {}
    public record Mutation(String editRevision, String configurationId) {}
    public record AssignmentMutation(String revision, String configurationId) {}

    public Catalog catalog()
    {
        return admitted(() -> onConfigurationThread(() -> new Catalog(
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
            return new Options(StreamingConfigurationCodec.providers(), sites);
        }));
    }

    public Entry get(String id)
    {
        return admitted(() -> onConfigurationThread(() ->
        {
            BroadcastConfiguration source = require(id);
            ConfiguredBroadcast configured = mConfigurationManager.getBroadcastModel().getConfiguredBroadcasts().stream()
                .filter(item -> item.getBroadcastConfiguration() == source).findFirst().orElseThrow();
            return new Entry(editRevision(source), StreamingConfigurationCodec.view(source), status(configured), references(id));
        }));
    }

    public StreamingConfigurationCodec.Definition template(String provider)
    { return StreamingConfigurationCodec.view(StreamingConfigurationCodec.template(provider)); }

    public Mutation save(String id, String provider, Map<String,Object> settings, String expectedEditRevision)
    {
        return admitted(() -> onConfigurationThread(() -> mConfigurationManager.applyConfigurationMutation(() ->
        {
            BroadcastConfiguration original = id == null ? StreamingConfigurationCodec.template(provider) : require(id);
            if(id != null) requireEditRevision(original, expectedEditRevision);
            if(!original.getBroadcastServerType().name().equals(provider))
                throw new IllegalArgumentException("The provider of an existing destination cannot be changed");
            BroadcastConfiguration candidate = StreamingConfigurationCodec.apply(original, settings);
            validateCandidate(candidate);
            List<BroadcastConfiguration> proposed = new ArrayList<>(mConfigurationManager.getBroadcastModel().getBroadcastConfigurations());
            if(id == null) proposed.add(candidate);
            else proposed.replaceAll(item -> item.getConfigurationId().equals(id) ? candidate : item);
            mConfigurationManager.commitAndPublishStreamingConfiguration(proposed, candidate.getConfigurationId());
            return new Mutation(editRevision(candidate), candidate.getConfigurationId());
        })));
    }

    public Mutation delete(String id, String expectedEditRevision)
    {
        return admitted(() -> onConfigurationThread(() -> mConfigurationManager.applyConfigurationMutation(() ->
        {
            requireEditRevision(require(id), expectedEditRevision);
            References references = references(id);
            if(references.aliases() > 0 || !references.aliasLists().isEmpty())
                throw new IllegalArgumentException("Remove this destination from its aliases and Alias List defaults before deleting it");
            List<BroadcastConfiguration> proposed = mConfigurationManager.getBroadcastModel().getBroadcastConfigurations()
                .stream().filter(item -> !item.getConfigurationId().equals(id)).toList();
            mConfigurationManager.commitAndPublishStreamingConfiguration(proposed, id);
            return new Mutation(null, id);
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
            return new AliasPage(assignmentRevision(id), items, filtered.size(), offset, limit);
        }));
    }

    /** Applies only explicit changes, preserving selections outside the current search/page. */
    public AssignmentMutation assign(String id, List<Long> add, List<Long> remove, String expectedRevision)
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
            BroadcastConfiguration destination = require(id);
            if(!assignmentRevision(id).equals(expectedRevision))
                throw new StaleRevisionException("Assignments changed. Your edits are still here. Reload current values before saving");
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
            return new AssignmentMutation(assignmentRevision(id), id);
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

    public Mutation addFeed(int feedId)
    {
        if(System.nanoTime() > mFeedsExpires) throw new IllegalArgumentException("Refresh the available feeds before adding one");
        UserFeed feed = mFeeds.stream().filter(item -> item.id() == feedId).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Refresh the available feeds before adding one"));
        return admitted(() -> onConfigurationThread(() -> mConfigurationManager.applyConfigurationMutation(() ->
        {
            var candidate = BroadcastifyFeedConfiguration.from(feed);
            candidate.setEnabled(false);
            if(mConfigurationManager.getBroadcastModel().getBroadcastConfigurations().stream().anyMatch(item ->
                item instanceof BroadcastifyFeedConfiguration existing && existing.getFeedID() == feedId))
                throw new IllegalArgumentException("This feed is already configured");
            validateCandidate(candidate);
            List<BroadcastConfiguration> proposed = new ArrayList<>(mConfigurationManager.getBroadcastModel().getBroadcastConfigurations());
            proposed.add(candidate);
            mConfigurationManager.commitAndPublishStreamingConfiguration(proposed, candidate.getConfigurationId());
            return new Mutation(editRevision(candidate), candidate.getConfigurationId());
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
    /** Saved content only: sender state and changes to other destinations do not invalidate an editor. */
    private static String editRevision(BroadcastConfiguration configuration)
    { return ConfigurationEditVersion.of(configuration); }
    private static void requireEditRevision(BroadcastConfiguration configuration, String expected)
    {
        if(!editRevision(configuration).equals(expected))
            throw new StaleRevisionException("This destination changed. Your edits are still here. Reload current values before saving");
    }
    /** Alias names and other settings do not change the routing choices this editor can overwrite. */
    private String assignmentRevision(String id)
    {
        return ConfigurationEditVersion.of(mConfigurationManager.getAliasModel().getAliases().stream()
            .filter(alias -> alias.hasBroadcastConfiguration(id)).map(Alias::getId).sorted().toList());
    }
    private <T> T admitted(Supplier<T> operation)
    {
        if(!mCommandAdmission.tryAcquire()) throw new ConfigurationBusyException();
        try { return operation.get(); } finally { mCommandAdmission.release(); }
    }
    public static final class NotFoundException extends RuntimeException {}
    public static final class StaleRevisionException extends RuntimeException
    { public StaleRevisionException(String message) { super(message); } }
    public static final class ConfigurationBusyException extends RuntimeException {}
    public static final class NotInitializedException extends RuntimeException {}
    private <T> T onConfigurationThread(Supplier<T> operation)
    {
        try
        {
            return mConfigurationManager.executeConfigurationTask(() ->
            {
                return operation.get();
            });
        }
        catch(io.github.dsheirer.configuration.ConfigurationExecutor.BusyException exception)
        {
            throw new ConfigurationBusyException();
        }
    }

    private void requireInitialized()
    {
        if(!mConfigurationManager.isInitialized()) throw new NotInitializedException();
    }

}
