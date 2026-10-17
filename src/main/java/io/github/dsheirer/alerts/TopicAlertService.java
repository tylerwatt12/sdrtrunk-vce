/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.alerts;

import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.audio.tone.CompletedToneSequence;
import io.github.dsheirer.audio.tone.P25PagingToneService;
import io.github.dsheirer.module.decode.event.DecodeEventViewService;
import io.github.dsheirer.stats.WebEntityReferences;
import io.github.dsheirer.util.concurrent.BoundedMpscPairQueue;
import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Receiver-side rule engine. Producers only offer bounded detached observations; the worker alone matches and writes. */
public final class TopicAlertService implements AutoCloseable
{
    private static final Object OBSERVATION = new Object();
    static final int INGRESS_CAPACITY = 1024;
    private static final int MAXIMUM_STATE_ENTRIES = 8192;
    private static final Pattern TEMPLATE_TOKEN = Pattern.compile("\\{([a-z_]+)}");
    private final TopicAlertStore mStore;
    private final LongSupplier mClock;
    private final BoundedMpscPairQueue<Object,Object> mIngress = new BoundedMpscPairQueue<>(INGRESS_CAPACITY);
    private final ScheduledExecutorService mWorker = Executors.newSingleThreadScheduledExecutor(new ObserverThreadFactory("sdrtrunk topic alerts"));
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final AtomicBoolean mStarted = new AtomicBoolean();
    private final AtomicLong mDropped = new AtomicLong();
    private final AtomicLong mFailures = new AtomicLong();
    private final Map<String,MatchState> mStates = boundedMap();
    private final Map<String,Long> mSeenEvents = boundedMap();
    private final Map<String,String> mHealthLifecycle = boundedMap();
    private final Map<String,Long> mNotifiedHealthIncidents = boundedMap();
    private volatile TopicAlertConfiguration mConfiguration;
    private volatile Map<String,TopicAlertStore.SystemFact> mSystemFacts = Map.of();
    private volatile Predicate<DecodeEventViewService.EventView> mEventValidator = event -> {
        var origin = event.sourceBinding();
        return origin == null || event.sourceFrequencyHz() != null && origin.processingChain().getDecodeEventSource() == origin &&
            origin.processingChain().getSource() == origin.source() && origin.source().getFrequency() == event.sourceFrequencyHz();
    };
    private volatile Function<CompletedToneSequence,DecodeEventViewService.EventView> mToneProjector =
        sequence -> DecodeEventViewService.completedToneView(sequence, null);
    private AutoCloseable mToneSubscription;
    private long mWorkerRevision;
    private Map<String,AlertRule> mEvaluatedRules = Map.of();
    private long mLastPrune;
    private long mLastSystemRefresh;

    public TopicAlertService(Path database) throws IOException, SQLException
    {
        this(new TopicAlertStore(database), System::currentTimeMillis);
        start();
    }
    TopicAlertService(TopicAlertStore store, LongSupplier clock) throws IOException, SQLException
    {
        mStore = store; mClock = clock; mConfiguration = store.configuration();
    }
    private static <T> Map<String,T> boundedMap()
    {
        return new LinkedHashMap<>(128, .75f, true)
        {
            @Override protected boolean removeEldestEntry(Map.Entry<String,T> eldest) { return size() > MAXIMUM_STATE_ENTRIES; }
        };
    }

    public synchronized void start()
    {
        if(!mClosed.get() && mStarted.compareAndSet(false, true))
        {
            mToneSubscription = P25PagingToneService.getInstance().addCompletedToneListener(this::receiveCompletedToneSequence);
            mWorker.scheduleWithFixedDelay(this::drainSafely, 0, 10, TimeUnit.MILLISECONDS);
        }
    }

    public TopicAlertConfiguration configuration() { return mConfiguration; }
    public void setEventValidator(Predicate<DecodeEventViewService.EventView> validator)
    {
        if(validator != null) mEventValidator = validator;
    }
    public void setToneProjector(Function<CompletedToneSequence,DecodeEventViewService.EventView> projector)
    {
        mToneProjector = java.util.Objects.requireNonNull(projector);
    }
    public List<AlertTopic> topics() { return mConfiguration.topics(); }
    public List<AlertRule> rules() { return mConfiguration.rules(); }
    public Map<String,Object> snapshot()
    {
        return Map.of("revision", mConfiguration.revision(), "push", mConfiguration.push(), "topics", topics(), "rules", rules(),
            "builtin_action_button_enabled", mConfiguration.builtinActionButtonEnabled(),
            "observer_queue_depth", mIngress.size(), "dropped_observations", mDropped.get(), "processing_failures", mFailures.get());
    }

    public synchronized TopicAlertConfiguration updateConfiguration(TopicAlertConfiguration next, long expectedRevision) throws IOException, SQLException
    {
        try { mConfiguration = mStore.updateConfiguration(next, expectedRevision); return mConfiguration; }
        catch(TopicAlertStore.RevisionConflictException conflict) { throw new ConcurrentModificationException(conflict.getMessage()); }
    }
    public synchronized TopicAlertConfiguration saveTopic(AlertTopic topic, long expectedRevision) throws IOException, SQLException
    {
        return saveTopic(topic, expectedRevision, null);
    }
    public synchronized TopicAlertConfiguration saveTopic(AlertTopic topic, long expectedRevision, Boolean builtinActionButtonEnabled) throws IOException, SQLException
    {
        if(builtinActionButtonEnabled != null && (!topic.builtIn() || !AlertTopic.ADMINISTRATOR_TOPIC_ID.equals(topic.id())))
            throw new IllegalArgumentException("The built-in action setting belongs to Administrator alerts");
        List<AlertTopic> next = new ArrayList<>(topics()); next.removeIf(t -> t.id().equals(topic.id())); next.add(topic);
        return updateConfiguration(new TopicAlertConfiguration(mConfiguration.revision(), mConfiguration.push(), next, rules(),
            builtinActionButtonEnabled != null ? builtinActionButtonEnabled : mConfiguration.builtinActionButtonEnabled()), expectedRevision);
    }
    public synchronized TopicAlertConfiguration deleteTopic(String id, long expectedRevision) throws IOException, SQLException
    {
        if(AlertTopic.ADMINISTRATOR_TOPIC_ID.equals(id)) throw new IllegalArgumentException("The built-in topic cannot be deleted");
        if(rules().stream().anyMatch(r -> r.topicId().equals(id))) throw new IllegalArgumentException("Remove or move connected rules first");
        List<AlertTopic> next = topics().stream().filter(t -> !t.id().equals(id)).toList();
        return updateConfiguration(new TopicAlertConfiguration(mConfiguration.revision(), mConfiguration.push(), next, rules(), mConfiguration.builtinActionButtonEnabled()), expectedRevision);
    }
    public synchronized TopicAlertConfiguration saveRule(AlertRule rule, long expectedRevision) throws IOException, SQLException
    {
        List<AlertRule> next = new ArrayList<>(rules()); next.removeIf(r -> r.id().equals(rule.id())); next.add(rule);
        return updateConfiguration(new TopicAlertConfiguration(mConfiguration.revision(), mConfiguration.push(), topics(), next, mConfiguration.builtinActionButtonEnabled()), expectedRevision);
    }
    public synchronized TopicAlertConfiguration deleteRule(String id, long expectedRevision) throws IOException, SQLException
    {
        return updateConfiguration(new TopicAlertConfiguration(mConfiguration.revision(), mConfiguration.push(), topics(), rules().stream().filter(r -> !r.id().equals(id)).toList(), mConfiguration.builtinActionButtonEnabled()), expectedRevision);
    }

    /** Explicit administrator test: normal durable fanout without changing rule matching or repeat state. */
    public synchronized void testRule(String id, long expectedRevision) throws SQLException
    {
        TopicAlertConfiguration config = mConfiguration;
        if(config.revision() != expectedRevision)
            throw new ConcurrentModificationException("Alert settings changed. Refresh and try again.");
        AlertRule rule = config.rules().stream().filter(candidate -> candidate.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("This alert no longer exists. Refresh and try again."));
        if(!config.push().enabled())
            throw new IllegalArgumentException("Enable push notifications before testing an alert.");
        AlertTopic topic = config.topics().stream().filter(candidate -> candidate.id().equals(rule.topicId())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("This alert topic no longer exists. Refresh and try again."));
        if(!topic.visible() || !topic.enabled())
            throw new IllegalArgumentException("Make this topic available to listeners and enable it before testing an alert.");
        if(mClosed.get()) throw new IllegalArgumentException("Alert notifications are unavailable. Try again after the receiver restarts.");
        long now = mClock.getAsLong();
        Map<String,List<String>> facts = new LinkedHashMap<>();
        // Positive saved values provide useful examples; unavailable event details use normal template fallbacks.
        testFacts(rule.conditions(), facts);
        testScope(facts, "system_key", rule.systemKey()); testScope(facts, "site_id", rule.siteId());
        testScope(facts, "channel_id", rule.channelId()); testScope(facts, "protocol_family", rule.protocolFamily());
        testScope(facts, "protocol", rule.protocol());
        Map<String,Object> channel = null, system = null;
        try { if(!rule.channelId().isEmpty()) channel = WebEntityReferences.channel(rule.channelId()); }
        catch(IllegalArgumentException ignored) { }
        try { if(!rule.systemKey().isEmpty()) system = WebEntityReferences.radioSystem(rule.systemKey()); }
        catch(IllegalArgumentException ignored) { }
        AlertEvent event = new AlertEvent("test:" + UUID.randomUUID(), now, rule.trigger(), facts,
            new AlertAction.Context(channel, system, null, null, null));
        if(!publish(config, topic, rule.id(), rule.name(), event,
            "TEST: " + expand(rule.titleTemplate(), event, config.push().senderLabel(), now),
            "TEST: " + expand(rule.bodyTemplate(), event, config.push().senderLabel(), now),
            rule.clickAction(), rule.actionButtonEnabled(), now))
            throw new IllegalArgumentException("The notification queue is full. Wait for pending alerts to finish and try again.");
    }
    private static void testScope(Map<String,List<String>> facts, String field, String value)
    {
        if(!value.isEmpty()) facts.put(field, List.of(value));
    }
    private static void testFacts(AlertRule.Condition condition, Map<String,List<String>> facts)
    {
        if("none".equals(condition.op())) return;
        if(!condition.op().isEmpty())
        {
            for(AlertRule.Condition item: condition.items()) testFacts(item, facts);
        }
        else if("is".equals(condition.operator()) || "in".equals(condition.operator()))
        {
            String value = "in".equals(condition.operator()) ? condition.value().split(",", -1)[0].strip() : condition.value();
            if(!value.isBlank()) facts.putIfAbsent(condition.field(), List.of(value));
        }
    }

    public List<AlertDevice> devices() throws SQLException { return mStore.devices(); }
    public Optional<AlertDevice> device(String id) throws SQLException { return mStore.device(id); }
    public Optional<AlertDevice> findDeviceByTokenHash(String hash) throws SQLException { return mStore.findDeviceByTokenHash(hash); }
    public Optional<AlertDevice> byRemoteDeviceId(String id) throws SQLException { return mStore.byRemoteDeviceId(id); }
    public void saveDevice(AlertDevice device) throws SQLException { mStore.saveDevice(device); }
    public void markDeviceExpired(String id, boolean expired) throws SQLException { mStore.markDeviceExpired(id, expired); }
    public boolean markDeviceSubscriptionExpired(String id, String endpoint, String p256dh, String auth) throws SQLException
    {
        return mStore.markDeviceSubscriptionExpired(id, endpoint, p256dh, auth);
    }
    public void unsubscribe(String id) throws SQLException { mStore.unsubscribe(id); }
    public void saveEnrollment(AlertEnrollment enrollment) throws SQLException { mStore.saveEnrollment(enrollment); }
    public Optional<AlertEnrollment> enrollment(String id) throws SQLException { return mStore.enrollment(id); }
    public Optional<AlertEnrollment> findEnrollmentByTokenHash(String hash) throws SQLException { return mStore.findEnrollmentByTokenHash(hash); }
    public Optional<AlertEnrollment> byRemoteEnrollmentId(String id) throws SQLException { return mStore.byRemoteEnrollmentId(id); }
    public List<AlertEnrollment> pendingEnrollments(int limit) throws SQLException { return mStore.pendingEnrollments(limit); }
    public void completeEnrollment(AlertEnrollment enrollment, AlertDevice device) throws SQLException { mStore.completeEnrollment(enrollment, device); }
    public Map<String,Object> history(List<String> topicIds, int limit, int offset) throws SQLException { return mStore.history(topicIds, limit, offset); }
    public Optional<Map<String,Object>> historyItem(String id) throws SQLException { return mStore.historyItem(id); }
    public Optional<String> sourceCallUuid(String id) throws SQLException { return mStore.sourceCallUuid(id); }
    public List<Map<String,Object>> historyBySourceCallReferences(List<String> sources) throws SQLException
    {
        return mStore.historyBySourceCallReferences(sources);
    }
    public Optional<AlertDelivery> delivery(long id) throws SQLException { return mStore.delivery(id); }
    public List<AlertDelivery> deliveriesForAlert(String alertId) throws SQLException { return mStore.deliveriesForAlert(alertId); }
    public Optional<AlertDelivery> deliveryForRemote(String alertId, String remoteDeviceId) throws SQLException { return mStore.deliveryForRemote(alertId, remoteDeviceId); }
    public void queueRelayAlert(String alertId, String remoteAlertId) throws SQLException { mStore.queueRelayAlert(alertId, remoteAlertId); }
    public List<AlertDelivery> queuedRelayAlerts(int limit) throws SQLException { return mStore.queuedRelayAlerts(limit); }
    public void retryRelayAlert(String alertId, String error, long retryAtMs) throws SQLException { mStore.retryRelayAlert(alertId, error, retryAtMs); }
    public List<AlertDelivery> pendingDeliveries(int limit) throws SQLException
    {
        if(!mConfiguration.push().enabled()) return List.of();
        long now = mClock.getAsLong(); List<AlertDelivery> result = new ArrayList<>();
        for(AlertDelivery delivery: mStore.pendingDeliveries(limit, now))
        {
            Optional<AlertDevice> device = mStore.device(delivery.deviceId());
            if(device.isPresent() && !device.get().subscriptionExpired() && delivery.method().equals(mConfiguration.push().mode())) result.add(delivery);
        }
        return List.copyOf(result);
    }
    public void completeDelivery(long id, String state, String remoteId, String error, long retryAtMs) throws SQLException { mStore.completeDelivery(id, state, remoteId, error, retryAtMs); }

    /** Safe from a receiver callback: no locks, projection, database, network, or fallback work. */
    public boolean observe(AlertEvent event)
    {
        return offer(event, OBSERVATION);
    }
    private boolean offer(Object event, Object origin)
    {
        if(event == null || mClosed.get() || !mConfiguration.push().enabled()) return false;
        if(mIngress.offer(event, origin)) return true;
        mDropped.incrementAndGet(); return false;
    }

    /** Shared completed outcomes are handed off directly; neither Activity nor recording enables this path. */
    public void receiveCompletedToneSequence(CompletedToneSequence sequence)
    {
        if(sequence != null && !sequence.tones().isEmpty()) offer(sequence, OBSERVATION);
    }

    /** Called on the existing DecodeEventViewService observer worker, after its lifecycle/source validation. */
    public void receiveEventView(DecodeEventViewService.EventView view)
    {
        if(view == null || mClosed.get() || !mConfiguration.push().enabled() || !mEventValidator.test(view)) return;
        DecodeEventType type;
        try { type = DecodeEventType.valueOf(view.eventType()); } catch(IllegalArgumentException exception) { return; }
        Map<String,List<String>> facts = new LinkedHashMap<>();
        add(facts, "channel_id", view.configurationId()); add(facts, "system_key", view.servingSystemKey()); add(facts, "protocol", view.protocol());
        add(facts, "event_type", view.eventType()); add(facts, "channel", view.channelName()); add(facts, "duration_seconds", Double.toString(view.durationMs() / 1000.0));
        if(DecodeEventType.VOICE_CALLS.contains(type) || DecodeEventType.VOICE_CALLS_ENCRYPTED.contains(type))
            add(facts, "encrypted", Boolean.toString(DecodeEventType.VOICE_CALLS_ENCRYPTED.contains(type)));
        if(type == DecodeEventType.EMERGENCY) add(facts, "emergency", "true");
        String servingKey = view.alertFacts() != null && view.alertFacts().systemKey() != null ? view.alertFacts().systemKey() : view.servingSystemKey();
        parties(facts, view.fromParty(), true, servingKey); parties(facts, view.toParty(), false, servingKey);
        add(facts, "radio_name", view.fromAliases()); add(facts, "talkgroup_name", view.toAliases());
        if(view.alertFacts() != null)
        {
            facts.put("alias_id", view.alertFacts().aliasIds().stream().map(String::valueOf).toList());
            facts.put("tone", view.alertFacts().toneCodes()); facts.put("dcs", view.alertFacts().dcsCodes()); add(facts, "site_id", view.alertFacts().siteId());
            add(facts, "site_name", view.alertFacts().siteName()); add(facts, "protocol_family", view.alertFacts().protocolFamily());
            if(view.alertFacts().systemKey() != null) facts.put("system_key", List.of(view.alertFacts().systemKey()));
        }
        if(facts.getOrDefault("system_key", List.of()).isEmpty() && ("DMR".equals(view.protocol()) || "NXDN".equals(view.protocol())))
        {
            TopicAlertStore.SystemFact system = mSystemFacts.get(view.configurationId());
            //Historical assignment cannot relabel a new event from a restarted or retuned receiver.
            if(system != null && system.lastSeenMs() >= view.timeStartMs()) add(facts, "system_key", system.systemKey());
        }
        String trigger = type == DecodeEventType.EMERGENCY ? "emergency" : type == DecodeEventType.PAGE || type == DecodeEventType.CALL_ALERT ? "paging" :
            type == DecodeEventType.AFFILIATE ? "affiliation" : DecodeEventType.VOICE_CALLS.contains(type) || DecodeEventType.VOICE_CALLS_ENCRYPTED.contains(type) ? "call" :
            DecodeEventType.COMMANDS.contains(type) ? "command" : "event";
        AlertAction.Context actionContext = AlertAction.capture(view);
        String sourceCallUuid = io.github.dsheirer.audio.call.SourceCallReference.fromToken(view.alertFacts().activityCallToken());
        offer(new AlertEvent(view.eventId(), Math.max(1, view.timeStartMs()), trigger, facts, actionContext, sourceCallUuid), view);
        //Tone rules consume the completed native-call outcome exactly once, rather than legacy codec identifier updates.
        if(!facts.getOrDefault("dcs", List.of()).isEmpty()) offer(new AlertEvent(view.eventId() + ":dcs", Math.max(1, view.timeStartMs()), "dcs", facts, actionContext, sourceCallUuid), view);
    }
    private static void parties(Map<String,List<String>> facts, List<DecodeEventViewService.PartyIdentifier> parties, boolean source, String servingKey)
    {
        for(var party: parties)
        {
            Integer local = party.observedLocalId();
            if(party.canonicalIdentity() != null)
            {
                var radio = party.canonicalIdentity();
                local = party.observedWorkingId() != null ? party.observedWorkingId() :
                    java.util.Objects.equals(servingKey, io.github.dsheirer.module.decode.traffic.RadioSystemKey.p25(radio.wacn(), radio.systemId())) ? radio.subscriberId() : null;
            }
            if(party.canonicalGroupIdentity() != null && local == null)
            {
                var group = party.canonicalGroupIdentity();
                if(java.util.Objects.equals(servingKey, io.github.dsheirer.module.decode.traffic.RadioSystemKey.p25(group.wacn(), group.systemId()))) local = group.talkgroupId();
            }
            if("RADIO".equals(party.form()) && local != null && local > 0) add(facts, source ? "radio" : "target_radio", local.toString());
            if(("TALKGROUP".equals(party.form()) || "PATCH_GROUP".equals(party.form())) && local != null && local > 0) add(facts, "talkgroup", local.toString());
            parties(facts, party.talkgroupMembers(), source, servingKey); parties(facts, party.radioMembers(), source, servingKey);
        }
    }
    private static void add(Map<String,List<String>> facts, String key, String value)
    {
        if(value != null && !value.isBlank())
        {
            List<String> previous = facts.getOrDefault(key, List.of());
            if(previous.size() < 64 && !previous.contains(value)) { List<String> next = new ArrayList<>(previous); next.add(value); facts.put(key, List.copyOf(next)); }
        }
    }

    /** Low-rate health snapshots are already projected away from receiver callbacks. Only lifecycle changes alert. */
    public synchronized void observeHealth(Map<String,Object> snapshot)
    {
        if(snapshot == null || mClosed.get() || !mConfiguration.push().enabled()) return;
        Set<String> present = new HashSet<>();
        for(Object raw: list(snapshot.get("active")))
        {
            if(!(raw instanceof Map<?,?> incident)) continue;
            String key = snapshot.get("started_at_ms") + ":" + incident.get("occurrence_id"); present.add(key);
            String signature = incident.get("severity") + ":" + incident.get("title");
            String previous = mHealthLifecycle.put(key, signature);
            String phase = previous == null ? "opened" : !previous.equals(signature) ? "escalated" : "active";
            observeHealthIncident(key, incident, phase);
        }
        for(Object raw: list(snapshot.get("resolved")))
        {
            if(!(raw instanceof Map<?,?> incident)) continue;
            String key = snapshot.get("started_at_ms") + ":" + incident.get("occurrence_id");
            if(mHealthLifecycle.remove(key) != null) observeHealthIncident(key, incident, "recovered");
        }
        mHealthLifecycle.keySet().retainAll(present);
    }
    private void observeHealthIncident(String key, Map<?,?> incident, String state)
    {
        Map<String,List<String>> facts = new LinkedHashMap<>();
        add(facts, "incident_id", key);
        add(facts, "issue", String.valueOf(incident.get("code"))); add(facts, "issue_name", String.valueOf(incident.get("title")));
        add(facts, "severity", String.valueOf(incident.get("severity"))); add(facts, "state", state); add(facts, "scope", String.valueOf(incident.get("scope")));
        long now = mClock.getAsLong(); long opened = incident.get("opened_at_ms") instanceof Number n ? n.longValue() : now;
        add(facts, "condition_age_seconds", Long.toString(Math.max(0, now - opened) / 1000));
        observe(new AlertEvent("health:" + key + ':' + ("active".equals(state) ? "active" : state + ':' + incident.get("severity")), now, "health", facts));
    }
    private static List<?> list(Object value) { return value instanceof List<?> values ? values : List.of(); }

    private void drainSafely()
    {
        try { drain(); } catch(IOException | SQLException | RuntimeException exception) { mFailures.incrementAndGet(); }
    }
    void drain() throws IOException, SQLException
    {
        TopicAlertConfiguration config = mConfiguration;
        if(config.revision() != mWorkerRevision)
        {
            Map<String,AlertRule> nextRules = new HashMap<>(); config.rules().forEach(rule -> nextRules.put(rule.id(), rule));
            Set<String> changed = new HashSet<>(mEvaluatedRules.keySet()); changed.addAll(nextRules.keySet());
            changed.removeIf(id -> java.util.Objects.equals(mEvaluatedRules.get(id), nextRules.get(id)));
            mStates.keySet().removeIf(key -> changed.stream().anyMatch(id -> key.startsWith(id + ':')));
            mSeenEvents.keySet().removeIf(key -> changed.stream().anyMatch(id -> key.startsWith(id + ':')));
            mNotifiedHealthIncidents.keySet().removeIf(key -> changed.stream().anyMatch(id -> key.startsWith(id + ':')));
            mEvaluatedRules = Map.copyOf(nextRules); mWorkerRevision = config.revision();
        }
        for(int count = 0; count < 256 && !mClosed.get(); count++)
        {
            var next = mIngress.poll(); if(next == null) break;
            if(config.push().enabled() && (!(next.second() instanceof DecodeEventViewService.EventView source) || mEventValidator.test(source)))
            {
                if(next.first() instanceof CompletedToneSequence sequence)
                {
                    DecodeEventViewService.EventView view = mToneProjector.apply(sequence);
                    if(view != null)
                    {
                        AlertEvent event = completedToneEvent(view, sequence);
                        if(event != null) process(config, event, mClock.getAsLong());
                    }
                }
                else if(next.first() instanceof AlertEvent event) process(config, event, mClock.getAsLong());
            }
        }
        long now = mClock.getAsLong();
        if(now - mLastPrune >= 60_000) { mStore.prune(now); mLastPrune = now; }
        if(now - mLastSystemRefresh >= 5000) { mSystemFacts = mStore.currentSystemFacts(); mLastSystemRefresh = now; }
    }
    private static AlertEvent completedToneEvent(DecodeEventViewService.EventView view, CompletedToneSequence sequence)
    {
        Map<String,List<String>> facts = new LinkedHashMap<>();
        add(facts, "channel_id", view.configurationId()); add(facts, "system_key", view.servingSystemKey());
        add(facts, "protocol", view.protocol()); add(facts, "event_type", view.eventType());
        add(facts, "channel", view.channelName()); add(facts, "duration_seconds", Double.toString(view.durationMs() / 1000.0));
        if(sequence.snapshot().encryptionState().isKnown()) add(facts, "encrypted", Boolean.toString(sequence.snapshot().isEncrypted()));
        String servingKey = view.alertFacts().systemKey() != null ? view.alertFacts().systemKey() : view.servingSystemKey();
        parties(facts, view.fromParty(), true, servingKey); parties(facts, view.toParty(), false, servingKey);
        add(facts, "radio_name", view.fromAliases()); add(facts, "talkgroup_name", view.toAliases());
        facts.put("alias_id", view.alertFacts().aliasIds().stream().map(String::valueOf).toList());
        facts.put("tone", view.alertFacts().toneCodes());
        add(facts, "site_id", view.alertFacts().siteId()); add(facts, "site_name", view.alertFacts().siteName());
        add(facts, "protocol_family", view.alertFacts().protocolFamily());
        if(servingKey != null) facts.put("system_key", List.of(servingKey));
        return facts.get("tone").isEmpty() ? null : new AlertEvent(view.eventId(), Math.max(1, sequence.completedTimestamp()),
            "tone", facts, AlertAction.capture(view),
            io.github.dsheirer.audio.call.SourceCallReference.fromToken(sequence.snapshot().activityCallToken()));
    }

    private void process(TopicAlertConfiguration config, AlertEvent event, long now) throws SQLException
    {
        if("health".equals(event.trigger()) && !"active".equals(event.first("state")))
        {
            AlertTopic builtin = config.topics().stream().filter(t -> t.builtIn() && t.visible() && t.enabled()).findFirst().orElse(null);
            if(builtin != null && firstEvent("builtin:" + event.id(), now))
                publish(config, builtin, "builtin-health", "Administrator alerts", event, event.first("issue_name"), event.first("scope") + " · " + event.first("severity") + " · " + event.first("state"), "details", config.builtinActionButtonEnabled(), now);
        }
        for(AlertRule rule: config.rules())
        {
            boolean decodedEvent = !Set.of("health", "tone", "dcs").contains(event.trigger());
            if(!rule.enabled() || !(rule.trigger().equals(event.trigger()) || "event".equals(rule.trigger()) && decodedEvent) || !scopeMatches(rule, event) || !scheduleMatches(rule.schedule(), now)) continue;
            AlertTopic topic = config.topics().stream().filter(t -> t.id().equals(rule.topicId()) && t.visible() && t.enabled()).findFirst().orElse(null);
            boolean recovered = "recovered".equals(event.first("state"));
            String incidentKey = rule.id() + ':' + event.first("incident_id");
            if(topic == null || (recovered ? !rule.notifyRecovery() || !mNotifiedHealthIncidents.containsKey(incidentKey) : match(rule.conditions(), event) != Match.YES)) continue;
            if(!recovered && "sustained".equals(rule.timing().mode()) && mNotifiedHealthIncidents.containsKey(incidentKey)) continue;
            String stateKey = rule.id() + ':' + repeatIdentity(rule, event);
            MatchState state = mStates.computeIfAbsent(stateKey, ignored -> new MatchState());
            if(!"sustained".equals(rule.timing().mode()) && !firstEvent(rule.id() + ':' + event.id() + ":observed", now)) continue;
            if(!recovered && !timingMatches(rule, event, state, now)) continue;
            if(!firstEvent(rule.id() + ':' + event.id() + ':' + (recovered ? "recovery" : "match"), now)) continue;
            long previous = state.lastNotification;
            if(!recovered && previous > 0 && now - previous < rule.cooldownMinutes() * 60_000L) continue;
            if(publish(config, topic, rule.id(), rule.name(), event, expand(rule.titleTemplate(), event, config.push().senderLabel(), now), expand(rule.bodyTemplate(), event, config.push().senderLabel(), now), rule.clickAction(), rule.actionButtonEnabled(), now))
            {
                state.lastNotification = now;
                if("health".equals(event.trigger()))
                {
                    if(recovered) mNotifiedHealthIncidents.remove(incidentKey);
                    else mNotifiedHealthIncidents.put(incidentKey, now);
                }
            }
        }
    }
    private boolean publish(TopicAlertConfiguration config, AlertTopic topic, String ruleId, String alertName, AlertEvent event, String title, String body, String click, boolean actionButtonEnabled, long now) throws SQLException
    {
        List<AlertDevice> destinations = mStore.devicesForTopic(topic.id(), config.push().mode());
        boolean saved = mStore.publish(topic, ruleId, alertName, event, bounded(title, 128), bounded(body, 2048), click,
            AlertAction.automatic(event.trigger(), event.actionContext(), actionButtonEnabled), destinations, now);
        if(!saved) mDropped.incrementAndGet(); return saved;
    }
    private boolean firstEvent(String key, long now) { return mSeenEvents.putIfAbsent(key, now) == null; }
    private static boolean timingMatches(AlertRule rule, AlertEvent event, MatchState state, long now)
    {
        if("sustained".equals(rule.timing().mode()))
        {
            if("active".equals(event.first("state")) || "opened".equals(event.first("state")) || "escalated".equals(event.first("state")))
            {
                try { return Long.parseLong(event.first("condition_age_seconds")) >= rule.timing().minutes() * 60L; } catch(NumberFormatException ignored) { return false; }
            }
            return false;
        }
        if("health".equals(event.trigger()) && "active".equals(event.first("state"))) return false;
        if(event.id().equals(state.lastEvent)) return false;
        state.lastEvent = event.id(); long previous = state.lastMatch; state.lastMatch = now;
        if("quiet".equals(rule.timing().mode())) return previous > 0 && now - previous >= rule.timing().minutes() * 60_000L;
        if("count".equals(rule.timing().mode()))
        {
            while(!state.matches.isEmpty() && state.matches.peekFirst() <= now - rule.timing().windowMinutes() * 60_000L) state.matches.removeFirst();
            state.matches.addLast(now); while(state.matches.size() > rule.timing().count()) state.matches.removeFirst();
            if(state.matches.size() >= rule.timing().count()) { state.matches.clear(); return true; } return false;
        }
        return true;
    }

    static boolean scopeMatches(AlertRule rule, AlertEvent event)
    {
        return (rule.channelId().isEmpty() || event.values("channel_id").contains(rule.channelId())) &&
            (rule.systemKey().isEmpty() || event.values("system_key").contains(rule.systemKey())) &&
            (rule.siteId().isEmpty() || event.values("site_id").contains(rule.siteId())) &&
            (rule.protocolFamily().isEmpty() || event.values("protocol_family").contains(rule.protocolFamily())) &&
            (rule.protocol().isEmpty() || event.values("protocol").contains(rule.protocol()));
    }
    enum Match { YES, NO, UNKNOWN }
    static Match match(AlertRule.Condition condition, AlertEvent event)
    {
        if(!condition.op().isEmpty())
        {
            List<Match> children = condition.items().stream().map(item -> match(item, event)).toList();
            if("all".equals(condition.op())) return children.contains(Match.NO) ? Match.NO : children.contains(Match.UNKNOWN) ? Match.UNKNOWN : Match.YES;
            if("any".equals(condition.op())) return children.contains(Match.YES) ? Match.YES : children.contains(Match.UNKNOWN) ? Match.UNKNOWN : Match.NO;
            return children.contains(Match.YES) ? Match.NO : children.contains(Match.UNKNOWN) ? Match.UNKNOWN : Match.YES;
        }
        List<String> actual = event.values(condition.field());
        if("exists".equals(condition.operator())) return actual.isEmpty() ? Match.NO : Match.YES;
        if(actual.isEmpty()) return Match.UNKNOWN;
        String expected = normalize(condition.value());
        boolean result = switch(condition.operator())
        {
            case "is" -> actual.stream().anyMatch(value -> normalize(value).equals(expected));
            case "not" -> actual.stream().noneMatch(value -> normalize(value).equals(expected));
            case "in" -> actual.stream().anyMatch(value -> java.util.Arrays.stream(condition.value().split(",")).anyMatch(item -> normalize(value).equals(normalize(item))));
            case "contains" -> actual.stream().anyMatch(value -> normalize(value).contains(expected));
            case "gte", "lte" -> actual.stream().anyMatch(value -> compareNumber(value, condition.value(), condition.operator()));
            default -> false;
        };
        return result ? Match.YES : Match.NO;
    }
    private static boolean compareNumber(String value, String expected, String operator)
    {
        try { double actual = Double.parseDouble(value), target = Double.parseDouble(expected); return Double.isFinite(actual) && Double.isFinite(target) && ("gte".equals(operator) ? actual >= target : actual <= target); }
        catch(NumberFormatException ignored) { return false; }
    }
    private static String normalize(String value)
    {
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return "yes".equals(normalized) ? "true" : "no".equals(normalized) ? "false" : normalized;
    }
    static boolean scheduleMatches(AlertRule.Schedule schedule, long now)
    {
        if("always".equals(schedule.mode())) return true;
        ZonedDateTime local = Instant.ofEpochMilli(now).atZone(ZoneId.of(schedule.timeZone()));
        LocalTime time = local.toLocalTime(), start = LocalTime.parse(schedule.start()), end = LocalTime.parse(schedule.end());
        int day = local.getDayOfWeek().getValue() - 1;
        if(start.equals(end)) return schedule.days().contains(day);
        if(start.isBefore(end)) return schedule.days().contains(day) && !time.isBefore(start) && time.isBefore(end);
        return !time.isBefore(start) ? schedule.days().contains(day) : time.isBefore(end) && schedule.days().contains((day + 6) % 7);
    }

    private static String repeatIdentity(AlertRule rule, AlertEvent event)
    {
        String source = event.first("system_key").isEmpty() ? event.first("channel_id") : event.first("system_key");
        return switch(rule.repeatKey())
        {
            case "radio" -> source + ':' + event.first("radio");
            case "talkgroup" -> source + ':' + event.first("talkgroup");
            case "pair" -> source + ':' + event.first("radio") + ':' + event.first("talkgroup");
            case "channel" -> event.first("channel_id");
            default -> "all";
        };
    }
    private static String expand(String template, AlertEvent event, String sender, long now)
    {
        Matcher matcher = TEMPLATE_TOKEN.matcher(template); StringBuilder result = new StringBuilder();
        while(matcher.find())
        {
            String field = matcher.group(1); String replacement = switch(field)
            {
                case "vce" -> sender;
                case "time" -> Instant.ofEpochMilli(now).toString();
                case "radio" -> event.first("radio_name").isEmpty() ? event.first("radio") : event.first("radio_name");
                case "talkgroup" -> event.first("talkgroup_name").isEmpty() ? event.first("talkgroup") : event.first("talkgroup_name");
                case "issue" -> event.first("issue_name").isEmpty() ? event.first("issue") : event.first("issue_name");
                case "site" -> event.first("site_name").isEmpty() ? event.first("site_id") : event.first("site_name");
                case "event" -> event.first("event_type");
                default -> event.first(field);
            };
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement.isEmpty() ? "Unknown" : replacement));
        }
        matcher.appendTail(result); return result.toString();
    }
    private static String bounded(String value, int maximum) { return value == null || value.isBlank() ? "VCE alert" : value.substring(0, Math.min(maximum, value.length())); }

    public long droppedObservations() { return mDropped.get(); }
    @Override public synchronized void close()
    {
        if(!mClosed.compareAndSet(false, true)) return;
        if(mToneSubscription != null)
        {
            try { mToneSubscription.close(); } catch(Exception ignored) { }
            mToneSubscription = null;
        }
        mWorker.shutdown();
        try { mWorker.awaitTermination(2, TimeUnit.SECONDS); } catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
    private static final class MatchState
    {
        private long lastNotification;
        private long lastMatch;
        private String lastEvent;
        private final ArrayDeque<Long> matches = new ArrayDeque<>();
    }
}
