/* Copyright (C) 2026 Dennis Sheirer. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.alias;

import io.github.dsheirer.alias.AliasAdministrationService.*;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup;
import io.github.dsheirer.scanlist.ScanList;
import java.util.*;
import java.util.function.Function;

/** UI-independent import planning. CSV and future directory clients submit the same detached entries. */
public final class AliasImportService
{
    public enum Mode { UPDATE_ADD, REPLACE }
    /** Null name collections mean unspecified; empty collections explicitly remove all assignments. */
    public record Input(Alias alias, boolean radioReference, boolean groupProvided, boolean fullyEncrypted,
                        List<String> scanLists, List<String> streams, String sourceAliasList) {}
    public record Defaults(Boolean recordable, List<String> scanLists, List<String> streams) {}
    public record Change(String field, String before, String after) {}
    public record Row(int row, String result, String name, List<Change> changes, String error) {}
    public record Preview(long revision, String list, String sourceList, Mode mode, Map<String,Long> counts,
                          List<Row> rows) {}
    public static final class Plan
    {
        private final long listId;
        private final Preview preview;
        private final List<AliasEntry> saves;
        private final List<Long> deletions;
        private Plan(long listId, Preview preview, List<AliasEntry> saves, List<Long> deletions)
        {
            this.listId = listId;
            this.preview = preview;
            this.saves = List.copyOf(saves);
            this.deletions = List.copyOf(deletions);
        }
        public Preview preview() { return preview; }
    }
    private final AliasAdministrationService service;
    public AliasImportService(AliasAdministrationService service) { this.service = Objects.requireNonNull(service); }

    public Plan preview(long listId, Mode mode, List<Input> inputs, Defaults defaults)
    {
        Objects.requireNonNull(mode, "Import mode is required");
        if(inputs == null || inputs.isEmpty() || inputs.size() > AliasTransferCsv.MAX_ROWS)
            throw new IllegalArgumentException("Import must contain 1–10,000 aliases");
        TransferSnapshot snapshot = service.transferSnapshot(listId);
        Options options = snapshot.options();
        Set<String> sourceLists = inputs.stream().filter(Objects::nonNull).map(Input::sourceAliasList)
            .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        if(sourceLists.size() > 1)
            throw new IllegalArgumentException("Import rows must identify one source alias list");
        String sourceList = sourceLists.stream().findFirst().orElse(null);
        Map<List<String>,List<AliasEntry>> existing = new HashMap<>();
        snapshot.aliases().forEach(entry -> existing.computeIfAbsent(AliasTransferCsv.identity(entry.alias()),
            ignored -> new ArrayList<>()).add(entry));
        Set<List<String>> seen = new HashSet<>();
        Set<Long> retained = new HashSet<>();
        List<AliasEntry> saves = new ArrayList<>();
        List<Long> deletions = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        int index = 0;
        for(Input input: inputs)
        {
            index++;
            try
            {
                Alias source = input == null ? null : input.alias();
                if(source == null || !AliasMatchRegistry.isOperational(options.aliasList(), source.getMatchIdentifier()))
                    throw new IllegalArgumentException("Matcher is invalid or incompatible with this list");
                List<String> identity = AliasTransferCsv.identity(source);
                if(!seen.add(identity)) throw new IllegalArgumentException("Duplicate matcher in import");
                List<AliasEntry> matches = existing.getOrDefault(identity, List.of());
                if(matches.size() > 1) throw new IllegalArgumentException("Multiple existing aliases have this matcher");
                AliasEntry old = matches.isEmpty() ? null : matches.getFirst();
                if(old != null) retained.add(old.alias().getId());
                if(old == null && AliasMatchRegistry.isUnmatchedTalkgroupCatchAll(options.aliasList(), source.getMatchIdentifier()))
                    throw new IllegalArgumentException("Full talkgroup ranges belong in Alias List Defaults");
                Alias alias = old != null && input.radioReference() ?
                    RadioReferenceAliasFields.replacement(old.alias(), source.getName(), source.getDescription(),
                        source.getGroup(), input.groupProvided()) : AliasFactory.copyOf(source);
                alias.setAliasListId(listId);
                if(old != null && alias.getId() == 0) alias.setId(old.alias().getId());
                requireText(alias.getName(), "Name");
                if(alias.getMatchIdentifier() instanceof io.github.dsheirer.alias.id.esn.Esn esn)
                    requireText(esn.getEsn(), "ESN");
                if(alias.getStreamTalkgroupAlias() != null &&
                    (alias.getStreamTalkgroupAlias().getValue() < StreamAsTalkgroup.MINIMUM_VALUE ||
                    alias.getStreamTalkgroupAlias().getValue() > StreamAsTalkgroup.MAXIMUM_VALUE))
                    throw new IllegalArgumentException("Invalid stream-as talkgroup");
                if(alias.getIconName() != null && !options.iconNames().contains(alias.getIconName()) &&
                    (old == null || !Objects.equals(old.alias().getIconName(), alias.getIconName())))
                    throw new IllegalArgumentException("Unknown icon: " + alias.getIconName());
                Set<Long> scans = old == null ? Set.of() : old.scanListIds();
                if(old == null && input.radioReference())
                {
                    alias.setRecordable(options.aliasList().getUnmatchedTalkgroupPolicy().isRecordEnabled());
                    alias.setBroadcastChannels(options.aliasList().getUnmatchedTalkgroupPolicy().getStreamDestinations());
                    scans = options.unmatchedScanListIds();
                }
                List<String> scanNames = input.scanLists();
                List<String> streamNames = input.streams();
                if(old == null && defaults != null)
                {
                    if(scanNames == null) scanNames = defaults.scanLists();
                    if(streamNames == null) streamNames = defaults.streams();
                    if(input.radioReference() && defaults.recordable() != null) alias.setRecordable(defaults.recordable());
                }
                if(scanNames != null) scans = new HashSet<>(resolve(scanNames, options.scanLists(), ScanList::getName, ScanList::getId));
                if(streamNames != null) alias.setBroadcastChannels(resolve(streamNames, options.streams(),
                    BroadcastDestination::name, destination -> new BroadcastChannel(destination.configurationId(), destination.name())));
                if(old == null && input.radioReference() && input.fullyEncrypted())
                {
                    alias.setRecordable(false);
                    alias.setBroadcastChannels(List.of());
                    scans = Set.of();
                }
                if(alias.getBroadcastChannels().size() > AliasAdministrationService.MAX_BROADCAST_CHANNELS ||
                    scans.size() > AliasAdministrationService.MAX_SCAN_LISTS)
                    throw new IllegalArgumentException("Too many streaming destinations or scan lists");
                Map<String,String> after = fields(alias, scans, options);
                Map<String,String> before = old == null ? Map.of() : fields(old.alias(), old.scanListIds(), options);
                List<Change> changes = after.entrySet().stream().filter(entry -> !entry.getKey().equals("format_version") &&
                    !Objects.equals(before.get(entry.getKey()), entry.getValue()))
                    .map(entry -> new Change(entry.getKey(), before.getOrDefault(entry.getKey(), ""), entry.getValue())).toList();
                String result = old == null ? "added" : changes.isEmpty() ? "unchanged" : "updated";
                rows.add(new Row(index, result, alias.getName(), changes, null));
                if(!result.equals("unchanged")) saves.add(new AliasEntry(options.revision(), alias, scans));
            }
            catch(IllegalArgumentException exception)
            {
                rows.add(new Row(index, "error", input == null || input.alias() == null ? "" : input.alias().getName(),
                    List.of(), exception.getMessage()));
            }
        }
        if(mode == Mode.REPLACE)
        {
            for(AliasEntry entry: snapshot.aliases())
            {
                if(!retained.contains(entry.alias().getId()))
                {
                    deletions.add(entry.alias().getId());
                    rows.add(new Row(0, "deleted", entry.alias().getName(), fields(entry.alias(), entry.scanListIds(), options)
                        .entrySet().stream().filter(value -> !value.getKey().equals("format_version"))
                        .map(value -> new Change(value.getKey(), value.getValue(), "")).toList(), null));
                }
            }
        }
        Map<String,Long> counts = new LinkedHashMap<>();
        for(String result: List.of("added", "updated", "unchanged", "deleted", "error"))
            counts.put(result, rows.stream().filter(row -> row.result().equals(result)).count());
        return new Plan(listId, new Preview(options.revision(), options.aliasList().getName(), sourceList, mode,
            Collections.unmodifiableMap(counts), List.copyOf(rows)), saves, deletions);
    }

    public MutationResult apply(Plan plan)
    {
        if(plan.preview.counts().get("error") > 0) throw new IllegalArgumentException("Resolve import errors before applying");
        if(plan.saves.isEmpty() && plan.deletions.isEmpty())
        {
            long revision = service.currentRevision();
            if(revision != plan.preview.revision()) throw new StaleRevisionException(plan.preview.revision(), revision);
            return new MutationResult(revision, plan.listId, List.of(), 0);
        }
        return service.applyTransfer(plan.listId, plan.saves, plan.deletions, plan.preview.revision());
    }

    public String export(long listId)
    {
        TransferSnapshot snapshot = service.transferSnapshot(listId);
        Set<List<String>> identities = new HashSet<>();
        List<Map<String,String>> rows = new ArrayList<>();
        for(AliasEntry entry: snapshot.aliases())
        {
            if(!identities.add(AliasTransferCsv.identity(entry.alias())))
                throw new IllegalArgumentException(
                    "Transfer export requires one alias per exact matcher; resolve duplicate matchers first");
            rows.add(fields(entry.alias(), entry.scanListIds(), snapshot.options()));
        }
        return AliasTransferCsv.write(rows);
    }

    private static Map<String,String> fields(Alias alias, Set<Long> scans, Options options)
    {
        List<String> scanNames = scans.stream().map(id -> options.scanLists().stream().filter(list -> list.getId() == id)
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Missing scan list")).getName()).toList();
        List<String> streamNames = alias.getBroadcastChannels().stream().map(route -> options.streams().stream()
            .filter(stream -> stream.configurationId().equals(route.getConfigurationId())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Missing streaming destination")).name()).toList();
        resolve(scanNames, options.scanLists(), ScanList::getName, ScanList::getId);
        resolve(streamNames, options.streams(), BroadcastDestination::name, BroadcastDestination::configurationId);
        return AliasTransferCsv.fields(alias, options.aliasList().getName(), scanNames, streamNames);
    }

    private static <T,R> List<R> resolve(List<String> names, List<T> choices, Function<T,String> name, Function<T,R> value)
    {
        if(names.size() > 500 || new HashSet<>(names).size() != names.size())
            throw new IllegalArgumentException("Too many or duplicate assignment names");
        return names.stream().map(requested ->
        {
            List<T> matches = choices.stream().filter(choice -> Objects.equals(requested, name.apply(choice))).toList();
            if(matches.size() != 1) throw new IllegalArgumentException("Unknown or ambiguous assignment name: " + requested);
            return value.apply(matches.getFirst());
        }).toList();
    }

    private static void requireText(String value, String field)
    {
        if(value == null || value.isBlank())
            throw new IllegalArgumentException(field + " is empty");
    }
}
