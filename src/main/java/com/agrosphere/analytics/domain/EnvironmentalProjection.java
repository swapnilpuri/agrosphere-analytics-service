package com.agrosphere.analytics.domain;

import java.math.BigInteger;
import java.time.*;
import java.util.*;
import static com.agrosphere.analytics.domain.EnvironmentalObservation.*;

/** Pure immutable evidence model. No database, consumer, HTTP endpoint or raw event archive. */
public final class EnvironmentalProjection {
    private EnvironmentalProjection() {}
    public enum Outcome { APPLIED, DUPLICATE, CONFLICT }
    public record Scope(UUID estateId, UUID blockId) {}
    public record Issue(Scope scope, UUID observationId, UUID eventId, String reason) {}
    public record State(Map<UUID, EnvironmentalObservation> receipts, Map<UUID, EnvironmentalObservation> facts,
                        Set<Issue> issues, Map<Scope, Instant> lastProcessedAt, Map<Scope, Instant> latestSourceEventAt) {
        public State {
            receipts = Map.copyOf(receipts); facts = Map.copyOf(facts); issues = Set.copyOf(issues);
            lastProcessedAt = Map.copyOf(lastProcessedAt); latestSourceEventAt = Map.copyOf(latestSourceEventAt);
            receipts.forEach((id, fact) -> { if (!id.equals(fact.eventId())) throw new IllegalArgumentException("Receipt identity"); });
            var savedReceipts = receipts;
            facts.forEach((id, fact) -> { if (!id.equals(fact.observationId()) || !fact.equals(savedReceipts.get(fact.eventId())))
                throw new IllegalArgumentException("Immutable fact identity"); });
        }
        public static State empty() { return new State(Map.of(), Map.of(), Set.of(), Map.of(), Map.of()); }
    }
    public record Transition(State state, Outcome outcome) {}
    public static Scope scope(EnvironmentalObservation fact) { return new Scope(fact.estateId(), fact.blockId()); }
    private static Instant later(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
    public static Transition apply(State state, EnvironmentalObservation event, Instant processedAt) {
        Objects.requireNonNull(state); Objects.requireNonNull(event); instant(processedAt);
        var previous = state.receipts().get(event.eventId());
        if (event.equals(previous)) return new Transition(state, Outcome.DUPLICATE);
        var receipts = new HashMap<>(state.receipts()); var facts = new HashMap<>(state.facts());
        var issues = new HashSet<>(state.issues()); var processed = new HashMap<>(state.lastProcessedAt());
        var source = new HashMap<>(state.latestSourceEventAt());
        processed.merge(scope(event), processedAt, EnvironmentalProjection::later);
        source.merge(scope(event), event.receivedAt(), EnvironmentalProjection::later);
        var original = previous != null ? previous : facts.get(event.observationId());
        if (previous == null) receipts.put(event.eventId(), event);
        if (original != null) {
            var reason = previous != null ? "EVENT_ID_CONFLICT" : "OBSERVATION_ID_CONFLICT";
            issues.add(new Issue(scope(original), original.observationId(), event.eventId(), reason));
            issues.add(new Issue(scope(event), event.observationId(), event.eventId(), reason));
            processed.merge(scope(original), processedAt, EnvironmentalProjection::later);
        } else facts.put(event.observationId(), event);
        return new Transition(new State(receipts, facts, issues, processed, source),
                original == null ? Outcome.APPLIED : Outcome.CONFLICT);
    }
    public record Query(UUID estateId, UUID blockId, Instant from, Instant to) {
        public Query {
            Objects.requireNonNull(estateId); Objects.requireNonNull(blockId); instant(from); instant(to);
            if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofHours(24)) > 0)
                throw new IllegalArgumentException("Explicit half-open UTC window must be positive and at most 24 hours");
        }
    }
    public record View(List<EnvironmentalObservation> observations, BigInteger totalMatchingCount,
            boolean truncated, Map<Kind, Integer> returnedKindCounts, String availability, String projectionState,
            long conflictCount, Instant lastProcessedAt, Instant latestSourceEventAt) {
        public View { observations = List.copyOf(observations); returnedKindCounts = Map.copyOf(returnedKindCounts); }
    }
    /** Reconstruct an ordered view from minimal immutable facts, preserving each device/value/interval.
     * Metadata/issues are scope-wide; narrowing a window cannot hide conflicts. No cadence/coverage inference. */
    public static View rebuild(State state, Query query) {
        var selectedScope = new Scope(query.estateId(), query.blockId());
        var matching = state.facts().values().stream().filter(f -> scope(f).equals(selectedScope) &&
                !f.observedAt().isBefore(query.from()) && f.observedAt().isBefore(query.to()))
                .sorted(Comparator.comparing(EnvironmentalObservation::observedAt)
                        .thenComparing(f -> f.observationId().toString())).toList();
        var rows = matching.stream().limit(1000).toList();
        var counts = new EnumMap<Kind, Integer>(Kind.class);
        for (var kind : Kind.values()) counts.put(kind, 0);
        rows.forEach(f -> counts.merge(f.kind(), 1, Integer::sum));
        long conflicts = state.issues().stream().filter(i -> i.scope().equals(selectedScope)).map(Issue::eventId).distinct().count();
        return new View(rows, BigInteger.valueOf(matching.size()), matching.size() > 1000, counts,
                matching.isEmpty() ? "NONE" : "OBSERVED", conflicts > 0 ? "INCOMPLETE" : "OBSERVED_ONLY",
                conflicts, state.lastProcessedAt().get(selectedScope), state.latestSourceEventAt().get(selectedScope));
    }
}
