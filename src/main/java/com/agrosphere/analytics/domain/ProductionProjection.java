package com.agrosphere.analytics.domain;

import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.agrosphere.analytics.domain.ProductionRevision.*;

/** Immutable pure transition model. Not a persistence, AMQP or replay adapter. */
public final class ProductionProjection {
    private ProductionProjection() {}
    public enum Outcome { APPLIED, DUPLICATE, OLDER_VERSION, CONFLICT, UNSUPPORTED_REAL }
    public record Scope(UUID estateId, UUID blockId) {}
    public record Issue(Scope scope, UUID collectionId, UUID eventId, String reason) {}
    public record History(ProductionRevision latest, Map<Long, UUID> observedVersions) {
        public History {
            Objects.requireNonNull(latest); observedVersions = Map.copyOf(observedVersions);
            if (latest.provenance() != Provenance.SIMULATED ||
                    !latest.eventId().equals(observedVersions.get(latest.version())) ||
                    observedVersions.keySet().stream().anyMatch(v -> v < 0 || v > latest.version()))
                throw new IllegalArgumentException("Invalid highest-version ledger");
        }
        public BigInteger missingVersions() {
            return BigInteger.valueOf(latest.version()).add(BigInteger.ONE)
                    .subtract(BigInteger.valueOf(observedVersions.size()));
        }
    }
    public record Receipt(UUID collectionId, long version, Scope scope, String fingerprint) {}
    public record State(Map<UUID, Receipt> receipts, Map<UUID, History> collections,
                        Set<Issue> issues, Map<Scope, Instant> lastProcessedAt, Map<Scope, Instant> latestSourceAt) {
        public State { receipts = Map.copyOf(receipts); collections = Map.copyOf(collections); issues = Set.copyOf(issues);
            lastProcessedAt = Map.copyOf(lastProcessedAt); latestSourceAt = Map.copyOf(latestSourceAt);
            collections.forEach((id, history) -> {
                if (!id.equals(history.latest().collectionId())) throw new IllegalArgumentException("Ledger identity mismatch");
            });
        }
        public static State empty() { return new State(Map.of(), Map.of(), Set.of(), Map.of(), Map.of()); }
    }
    public record Transition(State state, Outcome outcome) {}
    public static Transition apply(State state, ProductionRevision event, Instant processedAt) {
        Objects.requireNonNull(state); Objects.requireNonNull(event); Objects.requireNonNull(processedAt);
        var receipt = new Receipt(event.collectionId(), event.version(), scope(event), event.fingerprint());
        var previousReceipt = state.receipts().get(event.eventId());
        if (receipt.equals(previousReceipt)) return new Transition(state, Outcome.DUPLICATE);
        var receipts = new HashMap<>(state.receipts());
        var collections = new HashMap<>(state.collections()); var issues = new HashSet<>(state.issues());
        var processed = new HashMap<>(state.lastProcessedAt());
        processed.merge(scope(event), processedAt, ProductionProjection::later);
        var sourceTimes = new HashMap<>(state.latestSourceAt());
        if (event.provenance() == Provenance.SIMULATED) sourceTimes.merge(scope(event), event.sourceAt(), ProductionProjection::later);
        Outcome outcome;
        if (previousReceipt != null) {
            issues.add(new Issue(previousReceipt.scope(), previousReceipt.collectionId(), event.eventId(), "EVENT_ID_CONFLICT"));
            issues.add(issue(event, "EVENT_ID_CONFLICT")); outcome = Outcome.CONFLICT;
            processed.merge(previousReceipt.scope(), processedAt, ProductionProjection::later);
        } else {
            receipts.put(event.eventId(), receipt);
            var prior = collections.get(event.collectionId());
            if (event.provenance() == Provenance.REAL) {
                issues.add(issue(event, "UNSUPPORTED_REAL")); outcome = Outcome.UNSUPPORTED_REAL;
            } else if (prior != null && (!scope(prior.latest()).equals(scope(event)) ||
                    !prior.latest().estateTimezone().equals(event.estateTimezone()) ||
                    prior.observedVersions().containsKey(event.version()) ||
                    prior.latest().status() == Status.VOIDED && event.version() > prior.latest().version() && event.status() != Status.VOIDED)) {
                issues.add(new Issue(scope(prior.latest()), prior.latest().collectionId(), event.eventId(), "REVISION_CONFLICT")); issues.add(issue(event, "REVISION_CONFLICT")); outcome = Outcome.CONFLICT;
                processed.merge(scope(prior.latest()), processedAt, ProductionProjection::later);
            } else {
                var versions = prior == null ? new HashMap<Long, UUID>() : new HashMap<>(prior.observedVersions());
                versions.put(event.version(), event.eventId());
                boolean replace = prior == null || event.version() > prior.latest().version();
                collections.put(event.collectionId(), new History(replace ? event : prior.latest(), versions));
                outcome = replace ? Outcome.APPLIED : Outcome.OLDER_VERSION;
            }
        }
        return new Transition(new State(receipts, collections, issues, processed, sourceTimes), outcome);
    }
    private static Scope scope(ProductionRevision event) { return new Scope(event.estateId(), event.blockId()); }
    private static Issue issue(ProductionRevision event, String reason) { return new Issue(scope(event), event.collectionId(), event.eventId(), reason); }
    private static Instant later(Instant a, Instant b) { return a == null || b.isAfter(a) ? b : a; }

    public record Query(UUID estateId, UUID blockId, LocalDate from, LocalDate to) {
        public Query {
            Objects.requireNonNull(estateId); Objects.requireNonNull(blockId); Objects.requireNonNull(from); Objects.requireNonNull(to);
            if (from.getYear() < 1 || to.getYear() > 9999 || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 366)
                throw new IllegalArgumentException("Inclusive query range must contain 1..367 dates");
        }
    }
    public record Metrics(long activeCollectionCount, long pendingDrcCount, long calculatedCollectionCount,
                          long voidedCollectionCount, BigDecimal totalWetLatexKg, BigDecimal pendingWetLatexKg,
                          BigDecimal calculatedWetLatexKg, BigDecimal knownDryRubberKg, boolean isComplete) {}
    public record Day(LocalDate date, Metrics metrics) {} // null metrics = no observed contribution roots, not zero production
    public record SourceVersion(UUID collectionId, long aggregateVersion, UUID sourceEventId, Instant sourceEventAt, LocalDate collectionDate) {}
    public record View(List<Day> days, List<SourceVersion> sourceVersionSample, boolean sourceVersionSampleTruncated, BigInteger missingSourceVersionCount, long conflictCount,
                       long unsupportedCount, Instant lastProcessedAt, Instant latestSourceEventAt) {
        public View { days = List.copyOf(days); sourceVersionSample = List.copyOf(sourceVersionSample); }
    }
    /** Rebuild from the highest-version ledger. No receipt-time grouping or rounded-exact-mass shortcut. */
    public static View rebuild(State state, Query query) {
        var matching = state.collections().values().stream().filter(h -> scope(h.latest()).equals(new Scope(query.estateId(), query.blockId()))).toList();
        var days = new ArrayList<Day>();
        for (LocalDate day = query.from(); !day.isAfter(query.to()); ) {
            final var key = day;
            var rows = matching.stream().map(History::latest).filter(r -> r.collectionDate().equals(key)).toList();
            Metrics metrics = null;
            if (!rows.isEmpty()) {
                long pending = 0, calculated = 0, voided = 0;
                var wet = BigDecimal.ZERO.setScale(3); var pendingWet = wet; var calculatedWet = wet; BigDecimal dry = null;
                for (var row : rows) {
                    if (row.status() == Status.VOIDED) { voided++; continue; }
                    wet = wet.add(row.wetKg());
                    if (row.status() == Status.PENDING_DRC) { pending++; pendingWet = pendingWet.add(row.wetKg()); }
                    else { calculated++; calculatedWet = calculatedWet.add(row.wetKg()); dry = dry == null ? row.dryKg() : dry.add(row.dryKg()); }
                }
                metrics = new Metrics(pending + calculated, pending, calculated, voided, wet, pendingWet, calculatedWet, dry, pending == 0);
            }
            days.add(new Day(day, metrics));
            if (day.equals(query.to())) break;
            day = day.plusDays(1);
        }
        var scope = new Scope(query.estateId(), query.blockId());
        var issues = state.issues().stream().filter(x -> x.scope().equals(scope)).toList();
        var missing = matching.stream().map(History::missingVersions).reduce(BigInteger.ZERO, BigInteger::add);
        var sources = matching.stream().map(History::latest)
                .filter(r -> !r.collectionDate().isBefore(query.from()) && !r.collectionDate().isAfter(query.to()))
                .sorted(Comparator.comparing(r -> r.collectionId().toString()))
                .map(r -> new SourceVersion(r.collectionId(), r.version(), r.eventId(), r.sourceAt(), r.collectionDate())).toList();
        // Scope-wide gaps/issues are deliberately not hidden by a narrow date filter.
        return new View(days, sources.stream().limit(100).toList(), sources.size() > 100, missing,
                issues.stream().filter(x -> !x.reason().equals("UNSUPPORTED_REAL")).map(Issue::eventId).distinct().count(),
                issues.stream().filter(x -> x.reason().equals("UNSUPPORTED_REAL")).count(),
                state.lastProcessedAt().get(scope), state.latestSourceAt().get(scope));
    }
}
