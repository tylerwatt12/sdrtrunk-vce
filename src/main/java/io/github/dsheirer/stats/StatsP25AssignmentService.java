/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.stats;

import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.P25WuidAssignmentRegistry;
import io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.LongFunction;

/**
 * Shared web projection of the receiver's current P25 assignments. Only the low-priority observer scans the
 * registry and enriches labels; HTTP requests read its immutable snapshot. Database rows supply names and links,
 * never assignments. No subscription, database operation, or browser request runs on a decoder callback.
 */
final class StatsP25AssignmentService implements AutoCloseable
{
    private static final int ENRICHMENT_BATCH_SIZE = 256;
    private static final long STALE_AFTER_MS = 15_000;
    private final LongFunction<P25WuidAssignmentRegistry.Snapshot> mSource;
    private final BiFunction<String,List<Map<String,Object>>,List<Map<String,Object>>> mEnricher;
    private final long mReceiverStartedAt;
    private volatile View mView;
    private volatile long mRunGeneration;
    private ScheduledExecutorService mWorker;

    StatsP25AssignmentService(P25WuidAssignmentRegistry registry, StatsWebDatabase database)
    {
        this(registry::snapshot, database::enrichCurrentP25Assignments,
            ManagementFactory.getRuntimeMXBean().getStartTime());
    }

    StatsP25AssignmentService(LongFunction<P25WuidAssignmentRegistry.Snapshot> source,
                             BiFunction<String,List<Map<String,Object>>,List<Map<String,Object>>> enricher,
                             long receiverStartedAt)
    {
        mSource = Objects.requireNonNull(source);
        mEnricher = Objects.requireNonNull(enricher);
        mReceiverStartedAt = receiverStartedAt;
    }

    synchronized void start()
    {
        if(mWorker == null)
        {
            mWorker = Executors.newSingleThreadScheduledExecutor(new ObserverThreadFactory("P25 assignment web observer"));
            long generation = ++mRunGeneration;
            mWorker.scheduleWithFixedDelay(() -> refreshSafely(generation), 0, 2, TimeUnit.SECONDS);
        }
    }

    synchronized void stop()
    {
        if(mWorker != null)
        {
            mRunGeneration++;
            mWorker.shutdownNow();
            mWorker = null;
        }
    }

    @Override
    public void close()
    {
        stop();
    }

    private void refreshSafely(long generation)
    {
        try
        {
            refresh(System.currentTimeMillis(), generation);
        }
        catch(RuntimeException exception)
        {
            //Keep the last useful view and its original timestamp. Requests explicitly report it as stale.
            synchronized(this)
            {
                View previous = mView;
                if(previous != null && generation == mRunGeneration)
                {
                    mView = new View(previous.snapshot(), previous.assignments(), previous.changes(), true);
                }
            }
        }
    }

    void refresh(long now)
    {
        refresh(now, -1);
    }

    private void refresh(long now, long generation)
    {
        P25WuidAssignmentRegistry.Snapshot snapshot = mSource.apply(now);
        Map<String,List<Map<String,Object>>> assignments = new LinkedHashMap<>();
        for(P25WuidAssignmentRegistry.Entry entry: snapshot.assignments())
        {
            String key = RadioSystemKey.p25(entry.servingWacn(), entry.servingSystem());
            Map<String,Object> row = identityRow(key, entry.subscriber(), entry.workingId());
            row.put("established_at_ms", entry.establishedAt());
            row.put("confirmed_at_ms", entry.confirmedAt());
            row.put("expires_at_ms", entry.expiresAt() == Long.MAX_VALUE ? null : entry.expiresAt());
            row.put("evidence", entry.evidence().name().toLowerCase(Locale.ROOT));
            row.put("lease_source", entry.leaseSource().name().toLowerCase(Locale.ROOT));
            putSource(row, entry.site(), entry.configurationId());
            assignments.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        Map<String,List<Map<String,Object>>> changes = new LinkedHashMap<>();
        for(P25WuidAssignmentRegistry.Change change: snapshot.recentChanges())
        {
            String key = change.servingWacn() != null && change.servingSystem() != null ?
                RadioSystemKey.p25(change.servingWacn(), change.servingSystem()) : "*";
            Map<String,Object> row = identityRow(key, change.subscriber(), change.workingId());
            row.put("sequence", change.sequence());
            row.put("changed_at_ms", change.changedAt());
            row.put("previous_working_id", change.previousWorkingId());
            row.put("change", switch(change.change())
            {
                case REGISTERED -> "assigned";
                case OBSERVATION_GAP, RESET, CONTENTION, SATURATION -> "needs_confirmation";
                default -> change.change().name().toLowerCase(Locale.ROOT);
            });
            row.put("change_reason", change.change().name().toLowerCase(Locale.ROOT));
            if("needs_confirmation".equals(row.get("change")))
            {
                row.put("invalidation_scope", "*".equals(key) ? "receiver" : "system");
            }
            putSource(row, change.site(), change.configurationId());
            changes.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        View view = new View(snapshot, enrich(assignments), enrich(changes), snapshot.stale());
        synchronized(this)
        {
            if(generation < 0 || generation == mRunGeneration)
            {
                mView = view;
            }
        }
    }

    private Map<String,List<Map<String,Object>>> enrich(Map<String,List<Map<String,Object>>> grouped)
    {
        Map<String,List<Map<String,Object>>> result = new LinkedHashMap<>();
        for(var group: grouped.entrySet())
        {
            List<Map<String,Object>> rows = new ArrayList<>();
            List<Map<String,Object>> input = group.getValue();
            for(int from = 0; from < input.size(); from += ENRICHMENT_BATCH_SIZE)
            {
                List<Map<String,Object>> batch = input.subList(from, Math.min(input.size(), from + ENRICHMENT_BATCH_SIZE));
                List<Map<String,Object>> enriched = "*".equals(group.getKey()) ? batch :
                    mEnricher.apply(group.getKey(), batch);
                for(Map<String,Object> row: enriched)
                {
                    rows.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
                }
            }
            result.put(group.getKey(), List.copyOf(rows));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String,Object> identityRow(String key, P25SubscriberIdentity subscriber, Integer workingId)
    {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("radio_system_key", key);
        row.put("observed_working_id", workingId);
        if(subscriber != null)
        {
            row.put("identity_source", "registration_mapping");
            row.put("canonical_wacn", subscriber.homeWacn());
            row.put("canonical_system_id", subscriber.homeSystemId());
            row.put("canonical_subscriber_id", subscriber.subscriberId());
            row.put("canonical_identity", Map.of("wacn", subscriber.homeWacn(), "system_id", subscriber.homeSystemId(),
                "subscriber_id", subscriber.subscriberId()));
            row.put("canonical_identity_display", subscriber.display());
        }
        return row;
    }

    private static void putSource(Map<String,Object> row, P25SiteIdentity site, String configurationId)
    {
        Map<String,Object> source = new LinkedHashMap<>();
        if(configurationId != null)
        {
            row.put("configuration_id", configurationId);
            source.put("configuration_id", configurationId);
            try
            {
                WebEntityRef.put(source, WebEntityRef.channel(configurationId));
            }
            catch(IllegalArgumentException ignored)
            {
                //A source without a durable channel configuration remains useful evidence without a guessed link.
            }
        }
        if(site != null)
        {
            source.put("rfss", site.rfss());
            source.put("site_id", site.site());
        }
        row.put("observed_on", source);
    }

    Map<String,Object> currentState(String key)
    {
        requireP25(key);
        return currentState(key, mView);
    }

    /** Builds state from the same immutable view used by an assignment page. */
    private Map<String,Object> currentState(String key, View view)
    {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("radio_system_key", key);
        result.put("receiver_started_at_ms", mReceiverStartedAt);
        result.put("snapshot_at_ms", view != null ? view.snapshot().asOf() : null);
        result.put("generation", view != null ? view.snapshot().generation() : 0L);
        boolean stale = stale(view);
        result.put("snapshot_stale", stale);
        List<Map<String,Object>> rows = view != null ? view.assignments().getOrDefault(key, List.of()) : List.of();
        P25WuidAssignmentRegistry.SystemObservation system = view != null ? view.snapshot().systems().stream()
            .filter(value -> key.equals(RadioSystemKey.p25(value.servingWacn(), value.servingSystem())))
            .findFirst().orElse(null) : null;
        String state = "learning";
        if(view != null)
        {
            if(!stale && (system == null || !system.observing()) && rows.isEmpty()) state = "stopped";
            else if(stale) state = "needs_confirmation";
            else if(!rows.isEmpty()) state = "current";
            else if(system != null && system.gapAt() > 0 || view.snapshot().generation() > 0) state = "needs_confirmation";
        }
        result.put("state", state);
        result.put("current_assignment_count", stale ? 0 : rows.size());
        result.put("retained_assignment_count", stale ? rows.size() : 0);
        long meaningful = rows.stream().filter(row -> meaningfulMapping(key, row, false)).count();
        result.put("meaningful_assignment_count", stale ? 0L : meaningful);
        result.put("retained_meaningful_assignment_count", stale ? meaningful : 0L);
        result.put("observed_channel_count", system != null ? system.configurationIds().size() : 0);
        long confirmed = rows.stream().mapToLong(row -> number(row.get("confirmed_at_ms"))).max().orElse(0);
        result.put("last_confirmation_ms", confirmed > 0 ? confirmed : null);
        result.put("observation_gap_at_ms", system != null && system.gapAt() > 0 ? system.gapAt() : null);
        result.put("dropped_mutations", view != null ? view.snapshot().droppedMutations() : 0L);
        result.put("saturated_mutations", view != null ? view.snapshot().saturatedMutations() : 0L);
        return result;
    }

    Map<String,Object> currentAssignments(String key, StatsRequest request)
    {
        return page(key, request, false);
    }

    Map<String,Object> recentChanges(String key, StatsRequest request)
    {
        return page(key, request, true);
    }

    private Map<String,Object> page(String key, StatsRequest request, boolean recent)
    {
        requireP25(key);
        int limit = request.limit();
        int offset = request.offset();
        String search = request.search();
        String configurationId = request.text("configuration_id");
        boolean roamingOnly = request.booleanValue("roaming_only", false);
        boolean meaningfulOnly = request.booleanValue("meaningful_only", false);
        Integer homeWacn = boundedIdentity(request, "home_wacn", 0xFFFFF);
        Integer homeSystem = boundedIdentity(request, "home_system_id", 0xFFF);
        Integer subscriber = boundedIdentity(request, "subscriber_id", RadioSystemIdentityKey.MAX_P25_RADIO_ID);
        String sort = request.sort(recent ? "changed_at" : "confirmed_at");
        Comparator<Map<String,Object>> comparator = comparator(sort, recent);
        if(request.descending(true))
        {
            comparator = comparator.reversed();
        }
        comparator = comparator.thenComparing(row -> String.valueOf(row.get("canonical_identity_display")))
            .thenComparingLong(row -> number(row.get("observed_working_id")))
            .thenComparingLong(row -> number(row.get("sequence")));
        View view = mView;
        List<Map<String,Object>> candidates = new ArrayList<>();
        if(view != null)
        {
            candidates.addAll((recent ? view.changes() : view.assignments()).getOrDefault(key, List.of()));
            if(recent)
            {
                for(Map<String,Object> global: view.changes().getOrDefault("*", List.of()))
                {
                    Map<String,Object> scoped = new LinkedHashMap<>(global);
                    scoped.put("radio_system_key", key);
                    candidates.add(scoped);
                }
            }
        }
        String query = search != null ? search.toLowerCase(Locale.ROOT) : null;
        List<Map<String,Object>> filtered = candidates.stream()
            .filter(row -> isInvalidation(row) || !meaningfulOnly ||
                (!recent || !"refreshed".equals(row.get("change_reason"))) && meaningfulMapping(key, row, recent))
            .filter(row -> isInvalidation(row) || configurationId == null || configurationId.equals(row.get("configuration_id")))
            .filter(row -> isInvalidation(row) || !roamingOnly || row.get("canonical_wacn") != null &&
                !key.equals(RadioSystemKey.p25((int)number(row.get("canonical_wacn")),
                    (int)number(row.get("canonical_system_id")))))
            .filter(row -> isInvalidation(row) || matches(row, "canonical_wacn", homeWacn) && matches(row, "canonical_system_id", homeSystem) &&
                matches(row, "canonical_subscriber_id", subscriber))
            .filter(row -> isInvalidation(row) || query == null || searchable(row).contains(query))
            .sorted(comparator).toList();
        int from = Math.min(offset, filtered.size());
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("rows", filtered.subList(from, Math.min(filtered.size(), from + limit)));
        result.put("limit", limit);
        result.put("offset", offset);
        result.put("total_count", filtered.size());
        result.put("has_more", from + limit < filtered.size());
        Map<String,Object> state = currentState(key, view);
        result.put("snapshot_at_ms", state.get("snapshot_at_ms"));
        result.put("snapshot_stale", state.get("snapshot_stale"));
        result.put("current_state", state);
        result.put("radio_system_key", key);
        return result;
    }

    private static Comparator<Map<String,Object>> comparator(String sort, boolean recent)
    {
        if("canonical_identity".equals(sort))
        {
            return Comparator.<Map<String,Object>>comparingLong(row -> number(row.get("canonical_wacn")))
                .thenComparingLong(row -> number(row.get("canonical_system_id")))
                .thenComparingLong(row -> number(row.get("canonical_subscriber_id")));
        }
        String field = switch(sort)
        {
            case "canonical_identity" -> "canonical_identity_display";
            case "alias" -> "alias_name";
            case "working_id" -> "observed_working_id";
            case "confirmed_at", "confirmed_at_ms" -> recent ? null : "confirmed_at_ms";
            case "expires_at", "expires_at_ms" -> recent ? null : "expires_at_ms";
            case "evidence" -> recent ? null : "evidence";
            case "changed_at", "changed_at_ms" -> recent ? "changed_at_ms" : null;
            case "change" -> recent ? "change" : null;
            default -> null;
        };
        if(field == null)
        {
            throw new StatsApiException(400, "invalid_parameter", "Unsupported assignment sort", "sort");
        }
        return switch(field)
        {
            case "observed_working_id", "confirmed_at_ms", "expires_at_ms", "changed_at_ms" ->
                Comparator.comparingLong(row -> number(row.get(field)));
            default -> Comparator.comparing(row -> String.valueOf(row.getOrDefault(field, "")),
                String.CASE_INSENSITIVE_ORDER);
        };
    }

    private static String searchable(Map<String,Object> row)
    {
        StringBuilder text = new StringBuilder();
        for(String field: List.of("canonical_identity_display", "canonical_wacn", "canonical_system_id",
            "canonical_subscriber_id", "observed_working_id", "previous_working_id", "alias_name",
            "last_talker_alias", "home_system_name", "channel_name", "site_name", "configuration_id"))
        {
            appendSearchValue(text, row.get(field));
        }
        if(row.get("observed_on") instanceof Map<?,?> source)
        {
            for(String field: List.of("name", "site_name", "configuration_id", "rfss", "site_id"))
            {
                appendSearchValue(text, source.get(field));
            }
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static void appendSearchValue(StringBuilder text, Object value)
    {
        if(value instanceof String || value instanceof Number)
        {
            text.append(value).append(' ');
        }
    }

    private static Integer boundedIdentity(StatsRequest request, String field, int maximum)
    {
        Integer value = request.optionalIdentifier(field);
        if(value != null && (value > maximum || "subscriber_id".equals(field) && value == 0))
        {
            throw new StatsApiException(400, "invalid_parameter", field + " is outside its identity range", field);
        }
        return value;
    }

    private static boolean matches(Map<String,Object> row, String field, Integer value)
    {
        return value == null || row.get(field) != null && number(row.get(field)) == value.longValue();
    }

    private static boolean isInvalidation(Map<String,Object> row)
    {
        return row.containsKey("invalidation_scope");
    }

    /** Hides only a fully known ordinary local address; incomplete evidence remains visible. */
    private static boolean meaningfulMapping(String servingKey, Map<String,Object> row, boolean recent)
    {
        if(!(row.get("canonical_wacn") instanceof Number wacn) ||
            !(row.get("canonical_system_id") instanceof Number system) ||
            !(row.get("canonical_subscriber_id") instanceof Number subscriber))
        {
            return true;
        }
        if(!servingKey.equals(RadioSystemKey.p25(wacn.intValue(), system.intValue())))
        {
            return true;
        }
        //A move back to the home ID still changes a previously remapped address.
        if(recent && row.get("previous_working_id") instanceof Number previous &&
            previous.longValue() != subscriber.longValue())
        {
            return true;
        }
        return !(row.get("observed_working_id") instanceof Number working) ||
            working.longValue() != subscriber.longValue();
    }

    private static long number(Object value)
    {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static boolean stale(View view)
    {
        return view == null || view.stale() || System.currentTimeMillis() - view.snapshot().asOf() > STALE_AFTER_MS;
    }

    private static void requireP25(String key)
    {
        if(!RadioSystemKey.isP25Native(key))
        {
            throw new StatsApiException(404, "not_found", "ISSI assignments are available only for P25 systems");
        }
    }

    private record View(P25WuidAssignmentRegistry.Snapshot snapshot,
                        Map<String,List<Map<String,Object>>> assignments,
                        Map<String,List<Map<String,Object>>> changes, boolean stale)
    {
    }
}
