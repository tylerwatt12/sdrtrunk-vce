/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.audio.call.AudioCallCoordinator;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDecisionOutcome;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticCallIdentity;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticCounters;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticDecision;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticEvidence;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticLeg;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticOutputPolicy;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticOverlap;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticService;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticServiceSnapshot;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticSnapshot;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticStatus;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticWinner;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallMergeProof;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallSeparationReason;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallWinnerCriterion;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Read-only administrator view of bounded, already-published logical-call diagnostics. Projection and JSON writing
 * happen only on HTTP request threads. No browser request calls back onto the resolver or decode producer threads.
 */
public final class CallMatchingHttpController
{
    public static final String PATH = "/api/v1/admin/call-matching";
    public static final int MAXIMUM_VISIBLE_DUPLICATES = 100;
    public static final int MAXIMUM_VISIBLE_COPIES = 32;
    private static final int MAXIMUM_INSPECTED_COPIES = 512;
    private static final int MAXIMUM_VISIBLE_REASONS = LogicalCallSeparationReason.values().length;
    private static final int MAXIMUM_INSPECTED_REASONS = 64;
    private static final int MAXIMUM_LABEL_CODE_POINTS = 160;
    private static final long STALE_SNAPSHOT_MILLISECONDS = 3_000L;
    private static final String HIDDEN_PATH = "[path hidden]";
    private static final String HIDDEN_SECRET = "[secret hidden]";
    private static final String HIDDEN_ENDPOINT = "[endpoint hidden]";
    private final Supplier<LogicalCallDiagnosticService> mService;
    private final Supplier<AudioCallCoordinator> mCoordinator;

    public CallMatchingHttpController(Supplier<LogicalCallDiagnosticService> service,
                                      Supplier<AudioCallCoordinator> coordinator)
    {
        mService = Objects.requireNonNull(service, "Diagnostic service source cannot be null");
        mCoordinator = Objects.requireNonNull(coordinator, "Coordinator source cannot be null");
    }

    public void handle(HttpExchange exchange) throws IOException
    {
        if(!PATH.equals(exchange.getRequestURI().getRawPath()))
        {
            WebHttpSupport.notFound(exchange);
            return;
        }
        if(!WebHttpSupport.requireNoQuery(exchange))
        {
            return;
        }
        if(!"GET".equals(exchange.getRequestMethod()))
        {
            WebHttpSupport.methodNotAllowed(exchange, "GET");
            return;
        }
        if(WebHttpSupport.hasRequestBody(exchange))
        {
            ApiHttpResponse.sendError(exchange, 400, "invalid_request", "GET requests cannot include a body");
            return;
        }

        LogicalCallDiagnosticService service = mService.get();
        AudioCallCoordinator coordinator = mCoordinator.get();

        if(service == null || coordinator == null)
        {
            unavailable(exchange);
            return;
        }

        // These are fixed-capacity snapshots. The coordinator snapshot is a volatile read, and a blocked response
        // writer retains only its own response; it cannot hold an ingress or diagnostic producer lock.
        LogicalCallDiagnosticServiceSnapshot history = service.snapshot();
        LogicalCallDiagnosticSnapshot resolver = coordinator.getDiagnosticSnapshot();
        AudioCallCoordinator.CoordinatorQueueStatus queue = coordinator.getQueueStatus();

        if(resolver == null)
        {
            unavailable(exchange);
            return;
        }

        ApiHttpResponse.sendData(exchange, 200, document(history, resolver, queue, System.currentTimeMillis()));
    }

    private static void unavailable(HttpExchange exchange) throws IOException
    {
        ApiHttpResponse.sendError(exchange, 503, "call_matching_unavailable",
            "Call matching diagnostics are unavailable");
    }

    private static Document document(LogicalCallDiagnosticServiceSnapshot history,
                                     LogicalCallDiagnosticSnapshot resolver,
                                     AudioCallCoordinator.CoordinatorQueueStatus queue, long now)
    {
        List<DuplicateDecision> duplicates = new ArrayList<>(MAXIMUM_VISIBLE_DUPLICATES);
        List<LogicalCallDiagnosticDecision> decisions = history.recentDuplicates();

        for(int index = decisions.size() - 1; index >= 0 && duplicates.size() < MAXIMUM_VISIBLE_DUPLICATES; index--)
        {
            LogicalCallDiagnosticDecision decision = decisions.get(index);

            if(decision != null && decision.outcome() == LogicalCallDecisionOutcome.MERGED)
            {
                duplicates.add(duplicate(decision));
            }
        }

        LogicalCallDiagnosticStatus status = history.status();
        long snapshotAge = resolver.generatedAtMs() > 0 ? Math.max(0L, now - resolver.generatedAtMs()) :
            Long.MAX_VALUE;
        ResolverStatus resolverStatus = new ResolverStatus(safeLabel(resolver.sessionId()), resolver.startedAtMs(),
            resolver.generatedAtMs(), resolver.revision(), snapshotAge,
            matchingHealth(resolver, queue, status, snapshotAge), resolver.accepting(), resolver.disposed(),
            resolver.activeLegCount(), resolver.activeCohortCount(), resolver.retainedAudioSampleCount(),
            counters(resolver.counters()));
        QueueStatus queueStatus = new QueueStatus(queue.ingressDepth(), queue.regularIngressCapacity(),
            queue.totalIngressCapacity(), queue.acceptedIngress(), queue.droppedIngress(),
            queue.droppedLifecycle(), queue.droppedOperations(), queue.abortedCalls());
        HistoryStatus historyStatus = new HistoryStatus(decisions.size(), history.duplicatesEvicted(),
            MAXIMUM_VISIBLE_DUPLICATES);
        return new Document(true, safeLabel(history.sessionId()), history.sessionStartedAtEpochMillis(),
            resolverStatus, queueStatus, diagnosticStatus(status), historyStatus, List.copyOf(duplicates));
    }

    private static String matchingHealth(LogicalCallDiagnosticSnapshot resolver,
                                         AudioCallCoordinator.CoordinatorQueueStatus queue,
                                         LogicalCallDiagnosticStatus status,
                                         long snapshotAge)
    {
        if(resolver.disposed())
        {
            return "STOPPED";
        }
        if(!resolver.accepting())
        {
            return "DRAINING";
        }
        if(snapshotAge > STALE_SNAPSHOT_MILLISECONDS)
        {
            return "UNRESPONSIVE";
        }
        if(!status.accepting())
        {
            return "WARNING";
        }
        if(queue.ingressDepth() >= pressureThreshold(queue.totalIngressCapacity()) ||
            queue.droppedIngress() > 0 || queue.droppedOperations() > 0 ||
            resolver.counters().diagnosticDecisionsRejected() > 0)
        {
            return "WARNING";
        }
        return "HEALTHY";
    }

    private static int pressureThreshold(int capacity)
    {
        return Math.max(1, (capacity * 3 + 3) / 4);
    }

    private static DuplicateDecision duplicate(LogicalCallDiagnosticDecision decision)
    {
        List<LogicalCallDiagnosticLeg> ordered = orderedCopies(decision);
        List<CopyView> copies = new ArrayList<>(ordered.size());
        int selectedIndex = 0;
        int runnerUpIndex = 0;
        LogicalCallDiagnosticWinner winner = decision.winner();
        LogicalCallDiagnosticLeg selectedCopy = winner != null ?
            ordered.stream().filter(leg -> Objects.equals(leg.legId(), winner.winnerLegId()))
                .findFirst().orElse(null) : null;
        if(selectedCopy == null)
        {
            selectedCopy = ordered.stream().filter(LogicalCallDiagnosticLeg::winner).findFirst().orElse(null);
        }

        for(int index = 0; index < ordered.size(); index++)
        {
            LogicalCallDiagnosticLeg leg = ordered.get(index);
            int copyIndex = index + 1;
            boolean selected = leg == selectedCopy;
            if(selected)
            {
                selectedIndex = copyIndex;
            }
            if(!selected && winner != null && Objects.equals(leg.legId(), winner.runnerUpLegId()))
            {
                runnerUpIndex = copyIndex;
            }
            copies.add(copy(decision, leg, copyIndex, selected));
        }

        return new DuplicateDecision(decision.decisionSequence(), decision.decidedAtMs(), "MERGED",
            identity(decision.callIdentity()), policy(decision.outputPolicy()),
            winner(winner, selectedIndex, runnerUpIndex), List.copyOf(copies),
            evidence(decision.evidence()), reasons(decision.decisionReasons()));
    }

    private static List<String> reasons(List<LogicalCallSeparationReason> source)
    {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        int inspected = 0;
        for(LogicalCallSeparationReason reason : source)
        {
            if(inspected++ >= MAXIMUM_INSPECTED_REASONS)
            {
                break;
            }
            if(reason != null)
            {
                unique.add(reason.name());
            }
            if(unique.size() >= MAXIMUM_VISIBLE_REASONS)
            {
                break;
            }
        }
        return List.copyOf(unique);
    }

    private static List<LogicalCallDiagnosticLeg> orderedCopies(LogicalCallDiagnosticDecision decision)
    {
        List<LogicalCallDiagnosticLeg> legs = decision.legs();
        List<LogicalCallDiagnosticLeg> inspected =
            legs.subList(0, Math.min(MAXIMUM_INSPECTED_COPIES, legs.size()));
        List<LogicalCallDiagnosticLeg> ordered =
            new ArrayList<>(Math.min(MAXIMUM_VISIBLE_COPIES, inspected.size()));
        LogicalCallDiagnosticWinner winner = decision.winner();
        LogicalCallDiagnosticLeg selected = null;
        if(winner != null)
        {
            selected = inspected.stream().filter(leg -> Objects.equals(leg.legId(), winner.winnerLegId()))
                .findFirst().orElse(null);
        }
        if(selected == null)
        {
            selected = inspected.stream().filter(LogicalCallDiagnosticLeg::winner).findFirst().orElse(null);
        }
        if(selected != null)
        {
            ordered.add(selected);
        }
        LogicalCallDiagnosticLeg selectedCopy = selected;
        LogicalCallDiagnosticLeg runnerUp = null;
        if(winner != null)
        {
            runnerUp = inspected.stream().filter(leg -> leg != selectedCopy &&
                Objects.equals(leg.legId(), winner.runnerUpLegId())).findFirst().orElse(null);
            if(runnerUp != null)
            {
                ordered.add(runnerUp);
            }
        }
        for(LogicalCallDiagnosticLeg leg : inspected)
        {
            if(ordered.size() >= MAXIMUM_VISIBLE_COPIES)
            {
                break;
            }
            if(leg != selected && leg != runnerUp)
            {
                ordered.add(leg);
            }
        }
        return ordered;
    }

    private static CallIdentityView identity(LogicalCallDiagnosticCallIdentity identity)
    {
        if(identity == null)
        {
            return null;
        }
        return new CallIdentityView(safeLabel(identity.protocol()), safeLabel(identity.decoder()),
            identity.startTimestamp(), identity.endTimestamp(), identity.resolvedTimestamp(),
            identity.resolutionWaitMilliseconds(), safeLabel(identity.destinationValue()),
            safeLabel(identity.destinationAlias()), safeLabel(identity.sourceValue()),
            safeLabel(identity.sourceAlias()), identity.encryptionState().name(), identity.wacn(),
            identity.system(), safeLabel(identity.aliasListName()), identity.uniqueLearnedSiteCount());
    }

    private static OutputPolicyView policy(LogicalCallDiagnosticOutputPolicy policy)
    {
        return policy != null ? new OutputPolicyView(policy.recordRequested(), policy.streamRoutingKeyCount(),
            policy.browserOffered()) : null;
    }

    private static WinnerView winner(LogicalCallDiagnosticWinner winner, int selectedIndex, int runnerUpIndex)
    {
        return winner != null ? new WinnerView(selectedIndex, runnerUpIndex, winner.criterion().name(),
            criterionValue(winner.winnerValue(), winner.criterion()),
            criterionValue(winner.runnerUpValue(), winner.criterion())) : null;
    }

    private static CriterionValueView criterionValue(LogicalCallDiagnosticWinner.CriterionValue value,
                                                     LogicalCallWinnerCriterion criterion)
    {
        if(value == null)
        {
            return null;
        }
        // The coordinator's final tie breakers may carry configuration and physical-leg identifiers. The comparison
        // reason remains useful, but the identifier itself is not needed to explain why one copy was selected.
        if(criterion == LogicalCallWinnerCriterion.CHANNEL_CONFIGURATION_ID ||
            criterion == LogicalCallWinnerCriterion.CALL_LEG_ID)
        {
            return new CriterionValueView("stable tie-breaker", null, null);
        }
        return new CriterionValueView(safeLabel(value.display()), value.numerator(), value.denominator());
    }

    private static CopyView copy(LogicalCallDiagnosticDecision decision, LogicalCallDiagnosticLeg leg,
                                 int index, boolean selected)
    {
        LogicalCallDiagnosticOverlap overlap = LogicalCallDiagnosticOverlap.forCopy(decision, leg).orElse(null);
        return new CopyView(index, selected, safeLabel(leg.decoder()), safeLabel(leg.channelName()),
            leg.wacn(), leg.system(), leg.rfss(), leg.site(), leg.startTimestamp(), leg.endTimestamp(),
            leg.durationMilliseconds(), leg.expectedFrameCount(), leg.observedFrameCount(),
            leg.usableFrameCount(), leg.decodedFrameCount(), leg.repeatedFrameCount(),
            leg.concealedFrameCount(), leg.missingFrameCount(), leg.fecErrorCount(),
            leg.fecProtectedBitCount(), leg.qualityPercent(), leg.missingAndConcealedRate(),
            leg.repeatedFrameRate(), leg.normalizedFecErrorRate(), leg.retainedAudioSampleCount(),
            leg.ingressLoss(), leg.audioTruncated(), overlap != null ? new OverlapView(
                overlap.overlapMilliseconds(), overlap.shorterCopyOverlapPercent(),
                overlap.selectedCopyCoveragePercent(), overlap.startOffsetFromSelectedMilliseconds(),
                overlap.endOffsetFromSelectedMilliseconds()) : null);
    }

    private static EvidenceView evidence(LogicalCallDiagnosticEvidence evidence)
    {
        if(evidence == null)
        {
            return new EvidenceView(0, 0, 0, Map.of(), Map.of());
        }
        Map<String,Long> proofs = new LinkedHashMap<>();
        for(Map.Entry<LogicalCallMergeProof,Long> entry : evidence.mergeProofCounts().entrySet())
        {
            proofs.put(entry.getKey().name().toLowerCase(Locale.ROOT), entry.getValue());
        }
        Map<String,Long> reasons = new LinkedHashMap<>();
        for(Map.Entry<LogicalCallSeparationReason,Long> entry : evidence.rejectionReasonCounts().entrySet())
        {
            reasons.put(entry.getKey().name().toLowerCase(Locale.ROOT), entry.getValue());
        }
        return new EvidenceView(evidence.confirmedDuplicatePairCount(), evidence.separatedPairCount(),
            evidence.uncertainPairCount(), Map.copyOf(proofs), Map.copyOf(reasons));
    }

    private static ResolverCounters counters(LogicalCallDiagnosticCounters source)
    {
        return new ResolverCounters(source.acceptedIngress(), source.droppedIngress(), source.droppedLifecycle(),
            source.droppedOperations(), source.abortedLegs(), source.completedReceiverLegs(),
            source.eligibleReceiverLegs(), source.emittedLogicalCalls(), source.mergedLogicalCalls(),
            source.mergedReceiverCopies(), source.independentLogicalCalls(), source.failOpenLogicalCalls(),
            source.separatedPairComparisons(), source.diagnosticDecisionsOffered(),
            source.diagnosticDecisionsRejected());
    }

    private static DiagnosticStatus diagnosticStatus(LogicalCallDiagnosticStatus status)
    {
        return new DiagnosticStatus(status.accepting(), status.decisionsObserved(),
            status.recordsRejectedAfterClose());
    }

    private static String safeLabel(String value)
    {
        if(value == null)
        {
            return null;
        }
        String text = value.strip();
        String lower = text.toLowerCase(Locale.ROOT);
        if(lower.contains("-----begin ") || lower.contains("password=") || lower.contains("password:") ||
            lower.contains("token=") || lower.contains("token:") || lower.contains("api_key=") ||
            lower.contains("api-key=") || lower.contains("apikey=") || looksLikeCredentialUrl(text))
        {
            return HIDDEN_SECRET;
        }
        if(text.contains("://"))
        {
            return HIDDEN_ENDPOINT;
        }
        if(looksLikeAbsolutePath(text))
        {
            return HIDDEN_PATH;
        }

        StringBuilder cleaned = new StringBuilder(Math.min(text.length(), MAXIMUM_LABEL_CODE_POINTS));
        int copiedCodePoints = 0;
        for(int offset = 0; offset < text.length() && copiedCodePoints < MAXIMUM_LABEL_CODE_POINTS;)
        {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            cleaned.appendCodePoint(Character.isISOControl(codePoint) ? ' ' : codePoint);
            copiedCodePoints++;
        }
        return cleaned.toString();
    }

    private static boolean looksLikeCredentialUrl(String text)
    {
        int scheme = text.indexOf("://");
        return scheme > 0 && text.indexOf('@', scheme + 3) > scheme + 3;
    }

    private static boolean looksLikeAbsolutePath(String text)
    {
        for(int index = 0; index < text.length(); index++)
        {
            if(text.regionMatches(true, index, "file:/", 0, 6))
            {
                return true;
            }
            if(index > 0 && !isPathBoundary(text.charAt(index - 1)))
            {
                continue;
            }
            char first = text.charAt(index);
            if(first == '/' && index + 1 < text.length() && !Character.isWhitespace(text.charAt(index + 1)) ||
                first == '\\' && index + 1 < text.length() && text.charAt(index + 1) == '\\' ||
                index + 2 < text.length() && Character.isLetter(first) && text.charAt(index + 1) == ':' &&
                    (text.charAt(index + 2) == '/' || text.charAt(index + 2) == '\\'))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean isPathBoundary(char character)
    {
        return !Character.isLetterOrDigit(character);
    }

    private record Document(boolean available, String sessionId, long sessionStartedAtEpochMillis,
                            ResolverStatus resolver, QueueStatus queue, DiagnosticStatus diagnosticStatus,
                            HistoryStatus history, List<DuplicateDecision> duplicates)
    {
    }

    private record ResolverStatus(String sessionId, long startedAtMs, long generatedAtMs, long revision,
                                  long snapshotAgeMs, String healthState, boolean accepting, boolean disposed,
                                  int activeLegCount, int activeCohortCount, long retainedAudioSampleCount,
                                  ResolverCounters counters)
    {
    }

    private record ResolverCounters(long acceptedIngress, long droppedIngress, long droppedLifecycle,
                                    long droppedOperations, long abortedLegs, long completedReceiverLegs,
                                    long eligibleReceiverLegs, long emittedLogicalCalls, long mergedLogicalCalls,
                                    long mergedReceiverCopies, long independentLogicalCalls,
                                    long failOpenLogicalCalls, long separatedPairComparisons,
                                    long diagnosticDecisionsOffered, long diagnosticDecisionsRejected)
    {
    }

    private record QueueStatus(int ingressDepth, int regularIngressCapacity, int totalIngressCapacity,
                               long acceptedIngress, long droppedIngress, long droppedLifecycle,
                               long droppedOperations, long abortedCalls)
    {
    }

    private record DiagnosticStatus(boolean accepting, long decisionsObserved,
                                    long recordsRejectedAfterClose)
    {
    }

    private record HistoryStatus(int duplicatesRetained, long duplicatesEvicted, int limit)
    {
    }

    private record DuplicateDecision(long decisionSequence, long decidedAtMs, String outcome,
                                     CallIdentityView callIdentity, OutputPolicyView outputPolicy,
                                     WinnerView winner, List<CopyView> legs, EvidenceView evidence,
                                     List<String> decisionReasons)
    {
    }

    private record CallIdentityView(String protocol, String decoder, long startTimestamp, long endTimestamp,
                                    long resolvedTimestamp, long resolutionWaitMilliseconds,
                                    String destinationValue, String destinationAlias, String sourceValue,
                                    String sourceAlias, String encryptionState, Integer wacn, Integer system,
                                    String aliasListName, int uniqueLearnedSiteCount)
    {
    }

    private record OutputPolicyView(boolean recordRequested, int streamRoutingKeyCount, boolean browserOffered)
    {
    }

    private record WinnerView(int selectedCopyIndex, int runnerUpCopyIndex, String criterion,
                              CriterionValueView winnerValue, CriterionValueView runnerUpValue)
    {
    }

    private record CriterionValueView(String display, Long numerator, Long denominator)
    {
    }

    private record CopyView(int copyIndex, boolean selected, String decoder, String channelName,
                            Integer wacn, Integer system, Integer rfss, Integer site, long startTimestamp,
                            long endTimestamp, long durationMilliseconds, long expectedFrameCount,
                            long observedFrameCount, long usableFrameCount, long decodedFrameCount,
                            long repeatedFrameCount, long concealedFrameCount, long missingFrameCount,
                            long fecErrorCount, long fecProtectedBitCount, double qualityPercent,
                            double missingAndConcealedRate, double repeatedFrameRate,
                            double normalizedFecErrorRate, long retainedAudioSampleCount,
                            boolean ingressLoss, boolean audioTruncated, OverlapView overlap)
    {
    }

    private record OverlapView(long overlapMilliseconds, double shorterCopyOverlapPercent,
                               double selectedCopyCoveragePercent, long startOffsetFromSelectedMilliseconds,
                               long endOffsetFromSelectedMilliseconds)
    {
    }

    private record EvidenceView(long confirmedDuplicatePairCount, long separatedPairCount,
                                long uncertainPairCount, Map<String,Long> mergeProofCounts,
                                Map<String,Long> rejectionReasonCounts)
    {
    }
}
