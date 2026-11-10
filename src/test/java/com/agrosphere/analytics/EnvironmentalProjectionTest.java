package com.agrosphere.analytics;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.agrosphere.analytics.application.ReadEnvironmentalHistory;
import com.agrosphere.analytics.domain.EnvironmentalObservation;
import static com.agrosphere.analytics.domain.EnvironmentalObservation.*;
import static com.agrosphere.analytics.domain.EnvironmentalProjection.*;

class EnvironmentalProjectionTest {
    static UUID id(long n) { return new UUID(0, n); }
    static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    static final Instant PROCESSED = START.plusSeconds(90000);
    static Query query() { return new Query(id(1), id(2), START, START.plusSeconds(86400)); }
    static EnvironmentalObservation fact(long event, long observation, long device, Instant at, Kind kind, String value) {
        return new EnvironmentalObservation(id(event), String.format("%064x", event), id(observation), id(device), id(1), id(2),
                at, at.plusSeconds(1), kind, switch (kind) {
                    case AIR_TEMPERATURE -> "CELSIUS"; case SOIL_MOISTURE -> "PERCENT"; case INTERVAL_RAINFALL -> "MILLIMETRE";
                }, value, kind == Kind.INTERVAL_RAINFALL ? at.minusSeconds(7200) : null,
                kind == Kind.INTERVAL_RAINFALL ? at : null);
    }
    static EnvironmentalObservation temp(long n, Instant at) { return fact(n, n+100, n+200, at, Kind.AIR_TEMPERATURE, "-2.125000"); }
    static State ingest(EnvironmentalObservation... rows) {
        var state = State.empty();
        for (var row : rows) state = apply(state, row, PROCESSED).state();
        return state;
    }
    @Test void halfOpenBoundaryUsesObservationRatherThanReceiptAndAcceptsExactly24Hours() {
        var first = temp(1, START); var end = temp(2, query().to()); var before = temp(3, START.minusMillis(1));
        assertEquals(List.of(first), rebuild(ingest(end, first, before), query()).observations());
        assertThrows(IllegalArgumentException.class, () -> new Query(id(1), id(2), START, START));
        assertThrows(IllegalArgumentException.class, () -> new Query(id(1), id(2), START, START.minusMillis(1)));
        assertThrows(IllegalArgumentException.class, () -> new Query(id(1), id(2), START, START.plusMillis(86400001)));
        assertThrows(IllegalArgumentException.class, () -> new Query(id(1), id(2), START.plusNanos(1), query().to()));
    }
    @Test void exactZeroNegativeAndRainIntervalsArePreservedWithoutDeviceAggregation() {
        var rain = fact(3, 103, 50, START, Kind.INTERVAL_RAINFALL, "0.000000");
        var soil = fact(2, 102, 50, START, Kind.SOIL_MOISTURE, "0");
        var cold = temp(1, START);
        var view = rebuild(ingest(rain, cold, soil), query());
        assertEquals(List.of(cold, soil, rain), view.observations());
        assertEquals("-2.125000", view.observations().getFirst().value());
        assertTrue(rain.intervalStart().isBefore(query().from()));
        assertEquals(Map.of(Kind.AIR_TEMPERATURE, 1, Kind.SOIL_MOISTURE, 1, Kind.INTERVAL_RAINFALL, 1), view.returnedKindCounts());
        assertEquals("OBSERVED", view.availability()); assertEquals("NONE", rebuild(State.empty(), query()).availability());
    }
    @Test void rejectsInvalidValuesUnitsIntervalsAndPrecision() {
        for (var value : List.of("1e0", "01", "NaN", "1.0000001", "1000000000000", ""))
            assertThrows(IllegalArgumentException.class, () -> fact(1, 101, 201, START, Kind.AIR_TEMPERATURE, value));
        for (var value : List.of("-0.1", "100.000001"))
            assertThrows(IllegalArgumentException.class, () -> fact(1, 101, 201, START, Kind.SOIL_MOISTURE, value));
        assertThrows(IllegalArgumentException.class, () -> fact(1, 101, 201, START, Kind.INTERVAL_RAINFALL, "-1"));
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentalObservation(id(1), "0".repeat(64), id(2), id(3), id(4), id(5), START, START, Kind.SOIL_MOISTURE, "CELSIUS", "10", null, null));
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentalObservation(id(1), "0".repeat(64), id(2), id(3), id(4), id(5), START, START, Kind.INTERVAL_RAINFALL, "MILLIMETRE", "1", START, START));
        assertThrows(IllegalArgumentException.class, () -> temp(1, START.plusNanos(1)));
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentalObservation(id(1), "0".repeat(64), id(2), id(3), id(4), id(5), START.plusSeconds(301), START, Kind.AIR_TEMPERATURE, "CELSIUS", "1", null, null));
    }
    @Test void duplicateIsNoOpAndChangedEventOrObservationIdentityCannotReplaceOriginal() {
        var original = temp(1, START); var state = ingest(original);
        assertSame(state, apply(state, original, PROCESSED.plusSeconds(10)).state());
        var conflict = fact(1, 101, 201, START, Kind.AIR_TEMPERATURE, "4");
        var reused = fact(2, 101, 202, START.plusSeconds(1), Kind.AIR_TEMPERATURE, "5");
        assertEquals(Outcome.CONFLICT, apply(state, conflict, PROCESSED).outcome());
        state = apply(apply(state, conflict, PROCESSED).state(), reused, PROCESSED).state();
        assertEquals(List.of(original), rebuild(state, query()).observations());
        assertEquals(2, rebuild(state, query()).conflictCount());
        assertEquals("INCOMPLETE", rebuild(state, query()).projectionState());
        var retried = apply(state, reused, PROCESSED.plusSeconds(1));
        assertEquals(Outcome.DUPLICATE, retried.outcome()); assertSame(state, retried.state());
        assertEquals(2, rebuild(apply(state, conflict, PROCESSED).state(), query()).conflictCount());
    }
    @Test void fullEnvelopeFingerprintIsPartOfIdentityEvenWhenMinimalFactsAreEqual() {
        var row = temp(1, START);
        var changed = new EnvironmentalObservation(row.eventId(), "a".repeat(64), row.observationId(), row.deviceId(), row.estateId(), row.blockId(), row.observedAt(), row.receivedAt(), row.kind(), row.unit(), row.value(), null, null);
        assertEquals(Outcome.CONFLICT, apply(ingest(row), changed, PROCESSED).outcome());
    }
    @Test void crossScopeConflictMarksBothTrustedScopesWithoutMovingFacts() {
        var row = temp(1, START);
        var moved = new EnvironmentalObservation(row.eventId(), "b".repeat(64), row.observationId(), row.deviceId(), id(8), id(9), row.observedAt(), row.receivedAt(), row.kind(), row.unit(), row.value(), null, null);
        var state = apply(ingest(row), moved, PROCESSED).state();
        assertEquals(1, rebuild(state, query()).conflictCount());
        var view = rebuild(state, new Query(id(8), id(9), query().from(), query().to()));
        assertEquals("NONE", view.availability()); assertEquals("INCOMPLETE", view.projectionState());
        assertEquals(1, view.conflictCount()); assertTrue(view.observations().isEmpty());
        var narrow = rebuild(state, new Query(id(1), id(2), START.plusSeconds(1), START.plusSeconds(2)));
        assertEquals(1, narrow.conflictCount()); assertEquals("NONE", narrow.availability());
    }
    @Test void sampleCapExactCountAndCanonicalUuidOrdering() {
        var state = State.empty();
        // UUID.compareTo uses signed longs; canonical string order must still be used.
        var unsigned = new EnvironmentalObservation(id(99999), "f".repeat(64), UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), id(50), id(1), id(2), START, START, Kind.AIR_TEMPERATURE, "CELSIUS", "1", null, null);
        for (int i = 1000; i >= 1; i--) state = apply(state, temp(i, START), PROCESSED).state();
        assertFalse(rebuild(state, query()).truncated());
        assertEquals("1000", rebuild(state, query()).totalMatchingCount().toString());
        state = apply(state, unsigned, PROCESSED).state();
        var view = rebuild(state, query());
        assertEquals("1001", view.totalMatchingCount().toString()); assertEquals(1000, view.observations().size());
        assertTrue(view.truncated()); assertEquals(1000, view.returnedKindCounts().get(Kind.AIR_TEMPERATURE));
        assertEquals(id(101), view.observations().getFirst().observationId());
        assertFalse(view.observations().contains(unsigned));
        assertFalse(rebuild(ingest(temp(1, START)), query()).truncated());
    }
    @Test void lateArrivalsRebuildToSameOrderedHistoryForEveryPermutation() {
        var rows = List.of(temp(1, START.plusSeconds(5)), temp(2, START), temp(3, START.plusSeconds(1)));
        var expected = rebuild(ingest(rows.toArray(EnvironmentalObservation[]::new)), query());
        for (int a=0; a<3; a++) for (int b=0; b<3; b++) for (int c=0; c<3; c++) {
            if (a==b || b==c || a==c) continue;
            var state = ingest(rows.get(a), rows.get(b), rows.get(c), rows.get(a));
            assertEquals(expected, rebuild(state, query()));
            assertThrows(UnsupportedOperationException.class, () -> state.facts().clear());
        }
        assertEquals(START.plusSeconds(6), expected.latestSourceEventAt());
        assertNull(rebuild(ingest(rows.getFirst()), new Query(id(8), id(9), START, query().to())).lastProcessedAt());
    }
    @Test void roleAndEstateChecksPrecedeReferencesAndProjectionAndFailuresPropagate() {
        var calls = new ArrayList<String>();
        var reader = new ReadEnvironmentalHistory(q -> calls.add("reference"), q -> { calls.add("projection"); return rebuild(State.empty(), q); });
        for (var role : List.of("SUPERVISOR", "WORKER", "VIEWER"))
            assertThrows(SecurityException.class, () -> reader.read(query(), Set.of(role), Set.of(id(1))));
        assertThrows(SecurityException.class, () -> reader.read(query(), Set.of("ESTATE_MANAGER"), Set.of(id(9))));
        assertTrue(calls.isEmpty());
        for (var role : List.of("ESTATE_ADMIN", "ESTATE_MANAGER", "AUDITOR", "PLATFORM_ADMIN", "PLANTATION_ADMIN")) {
            calls.clear(); reader.read(query(), Set.of(role), Set.of(id(1)));
            assertEquals(List.of("reference", "projection"), calls);
        }
        calls.clear(); reader.read(query(), Set.of("PLATFORM_ADMIN"), Set.of());
        var unavailable = new ReadEnvironmentalHistory(q -> { throw new IllegalStateException("reference unavailable"); }, q -> { fail("Read before active reference"); return null; });
        assertThrows(IllegalStateException.class, () -> unavailable.read(query(), Set.of("AUDITOR"), Set.of(id(1))));
    }
}
