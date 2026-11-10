package com.agrosphere.analytics;

import com.agrosphere.analytics.domain.*;
import com.agrosphere.analytics.application.ReadProductionHistory;
import java.math.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.agrosphere.analytics.domain.ProductionRevision.*;
import static com.agrosphere.analytics.domain.ProductionProjection.*;

class ProductionProjectionTest {
    static final UUID ESTATE=id(1), BLOCK=id(2), ROOT=id(3);
    static final LocalDate DAY=LocalDate.of(2026,1,1);
    static final Instant NOW=Instant.parse("2026-01-03T00:00:00Z");
    static UUID id(int n) { return new UUID(0,n); }
    static ProductionRevision revision(int event,long version, Status status, LocalDate day, String wet,String dry) {
        return new ProductionRevision(id(event),String.format("%064x",event),version==0 ? Kind.RECORDED : status==Status.VOIDED ? Kind.VOIDED : Kind.ADJUSTED,
            NOW.minusSeconds(10), ROOT,version,ESTATE,BLOCK,day,ZoneId.of("Pacific/Kiritimati"),Provenance.SIMULATED,status,
            new BigDecimal(wet),dry==null?null:new BigDecimal(dry));
    }
    static ProductionRevision root(ProductionRevision r, UUID root) {
        return new ProductionRevision(r.eventId(),r.fingerprint(),r.kind(),r.sourceAt(),root,r.version(),r.estateId(),r.blockId(),r.collectionDate(),r.estateTimezone(),r.provenance(),r.status(),r.wetKg(),r.dryKg());
    }
    static State add(State s,ProductionRevision r) { return ProductionProjection.apply(s,r,NOW).state(); }
    static Query query() { return new Query(ESTATE,BLOCK,DAY,DAY.plusDays(1)); }
    @Test void noDataIsNotZeroProductionOrInventedDrc() {
        var view=rebuild(State.empty(),query()); assertEquals(2,view.days().size()); assertNull(view.days().getFirst().metrics());
        assertNull(view.lastProcessedAt()); assertTrue(view.sourceVersionSample().isEmpty());
        var s=add(State.empty(),revision(10,0,Status.PENDING_DRC,DAY,"10",null));
        var m=rebuild(s,query()).days().getFirst().metrics();
        assertEquals(new BigDecimal("10.000"),m.pendingWetLatexKg()); assertNull(m.knownDryRubberKg()); assertFalse(m.isComplete());
    }
    @Test void sumsProducerRoundedDryValuesWithExactDecimalsAndNoCollectionCapOnTotals() {
        var s=State.empty();
        for(int n=10;n<13;n++) s=add(s,root(revision(n,1,Status.CALCULATED,DAY,"999999.999","0.334"),id(n)));
        var m=rebuild(s,query()).days().getFirst().metrics();
        // Independent fixture oracle: three rounded collection values, NOT round(3 * .3335).
        assertEquals(new BigDecimal("1.002"),m.knownDryRubberKg());
        assertEquals(new BigDecimal("2999999.997"),m.totalWetLatexKg()); assertEquals(3,m.calculatedCollectionCount());
    }
    @Test void explicitZeroDrcIsDistinctFromMissingDrc() {
        var s=add(State.empty(),revision(10,1,Status.CALCULATED,DAY,"1","0"));
        assertEquals(new BigDecimal("0.000"),rebuild(s,query()).days().getFirst().metrics().knownDryRubberKg());
    }
    @Test void duplicateHasNoSecondEffectOrNewProcessingTimestamp() {
        var r=revision(10,0,Status.PENDING_DRC,DAY,"10",null);var s=add(State.empty(),r);
        var t=ProductionProjection.apply(s,r,NOW.plusSeconds(10));assertEquals(Outcome.DUPLICATE,t.outcome());assertSame(s,t.state());
    }
    @Test void correctionMovesOnlyCurrentContributionBetweenCollectionDates() {
        var s=add(State.empty(),revision(10,0,Status.PENDING_DRC,DAY,"10",null));
        s=add(s,revision(11,1,Status.CALCULATED,DAY.plusDays(1),"12","3.456"));
        var v=rebuild(s,query());assertNull(v.days().getFirst().metrics());
        assertEquals(new BigDecimal("12.000"),v.days().get(1).metrics().totalWetLatexKg());
        assertEquals(DAY.plusDays(1),v.sourceVersionSample().getFirst().collectionDate());
    }
    @Test void highestBeforeCreateRecordsGapThenLateVersionsCloseGapWithoutResurrection() {
        var s=add(State.empty(),revision(13,3,Status.VOIDED,DAY.plusDays(1),"12","3"));
        assertEquals(new BigInteger("3"),rebuild(s,query()).missingSourceVersionCount());
        s=add(s,revision(10,0,Status.PENDING_DRC,DAY,"10",null));
        s=add(s,revision(12,2,Status.CALCULATED,DAY.plusDays(1),"12","3"));
        s=add(s,revision(11,1,Status.CALCULATED,DAY,"10","2"));
        var v=rebuild(s,query());assertEquals(BigInteger.ZERO,v.missingSourceVersionCount());
        var m=v.days().get(1).metrics();assertEquals(1,m.voidedCollectionCount());assertEquals(0,m.activeCollectionCount());assertNull(m.knownDryRubberKg());
        assertEquals(Outcome.CONFLICT,ProductionProjection.apply(s,revision(14,4,Status.CALCULATED,DAY,"10","2"),NOW).outcome());
    }
    @Test void allRevisionDeliveryOrdersRebuildIdenticallyAgainstOracle() {
        var revisions=List.of(revision(10,0,Status.PENDING_DRC,DAY,"10",null),revision(11,1,Status.CALCULATED,DAY,"10","3"),
            revision(12,2,Status.CALCULATED,DAY.plusDays(1),"12","4"),revision(13,3,Status.VOIDED,DAY.plusDays(1),"12","4"));
        for(var order:permutations(revisions)) {
            var s=State.empty();for(var r:order) {s=add(s,r);s=add(s,r);}
            var v=rebuild(s,query());assertNull(v.days().getFirst().metrics());assertEquals(BigInteger.ZERO,v.missingSourceVersionCount());
            assertEquals(0,v.conflictCount());assertEquals(1,v.days().get(1).metrics().voidedCollectionCount());
            assertEquals(new BigDecimal("0.000"),v.days().get(1).metrics().totalWetLatexKg());
        }
    }
    static List<List<ProductionRevision>> permutations(List<ProductionRevision> values) {
        if(values.isEmpty())return List.of(List.of());var result=new ArrayList<List<ProductionRevision>>();
        for(var value:values){var rest=new ArrayList<>(values);rest.remove(value);for(var suffix:permutations(rest)){var next=new ArrayList<ProductionRevision>();next.add(value);next.addAll(suffix);result.add(next);}}return result;
    }
    @Test void reusedEventAndEqualVersionDifferentEventAreVisibleConflicts() {
        var s=add(State.empty(),revision(10,0,Status.PENDING_DRC,DAY,"10",null));
        // The full canonical-envelope fingerprint must change when any validated content changes.
        var original=revision(10,0,Status.PENDING_DRC,DAY,"11",null);
        var changed=new ProductionRevision(original.eventId(),"f".repeat(64),original.kind(),original.sourceAt(),ROOT,0,ESTATE,BLOCK,DAY,original.estateTimezone(),original.provenance(),original.status(),original.wetKg(),null);
        var conflict=ProductionProjection.apply(s,changed,NOW);assertEquals(Outcome.CONFLICT,conflict.outcome());
        assertEquals(new BigDecimal("10.000"),rebuild(conflict.state(),query()).days().getFirst().metrics().totalWetLatexKg());
        var equal=ProductionProjection.apply(s,revision(11,0,Status.PENDING_DRC,DAY,"10",null),NOW);
        assertEquals(Outcome.CONFLICT,equal.outcome());assertEquals(1,rebuild(equal.state(),query()).conflictCount());
    }
    @Test void unsupportedRealHasNoContributionAndOnlyMinimalReceipt() {
        var r=revision(10,0,Status.PENDING_DRC,DAY,"10",null);
        r=new ProductionRevision(r.eventId(),r.fingerprint(),r.kind(),r.sourceAt(),ROOT,0,ESTATE,BLOCK,DAY,r.estateTimezone(),Provenance.REAL,r.status(),r.wetKg(),null);
        var t=ProductionProjection.apply(State.empty(),r,NOW);assertEquals(Outcome.UNSUPPORTED_REAL,t.outcome());
        assertTrue(t.state().collections().isEmpty());assertNull(rebuild(t.state(),query()).days().getFirst().metrics());
        assertEquals(1,rebuild(t.state(),query()).unsupportedCount());
        var unsupported=r;
        assertThrows(IllegalArgumentException.class,()->new History(unsupported,Map.of(0L,unsupported.eventId())));
    }
    @Test void scopeWideMetadataDoesNotLeakOtherEstateActivity() {
        var s=add(State.empty(),revision(10,0,Status.PENDING_DRC,DAY,"10",null));
        var q=new Query(id(999),id(998),DAY,DAY);var v=rebuild(s,q);
        assertNull(v.lastProcessedAt());assertNull(v.latestSourceEventAt());assertEquals(0,v.conflictCount());assertTrue(v.sourceVersionSample().isEmpty());
    }
    @Test void hugeVersionGapUsesArithmeticNotRangeAllocation() {
        var s=add(State.empty(),revision(10,Long.MAX_VALUE,Status.CALCULATED,DAY,"10","2"));
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE),rebuild(s,query()).missingSourceVersionCount());
    }
    @Test void sourceSampleIsBoundedAndLexicallySorted() {
        var s=State.empty();for(int n=10;n<112;n++)s=add(s,root(revision(n,0,Status.PENDING_DRC,DAY,"1",null),id(n)));
        var v=rebuild(s,query());assertEquals(100,v.sourceVersionSample().size());assertTrue(v.sourceVersionSampleTruncated());
        assertEquals(102,v.days().getFirst().metrics().activeCollectionCount());
    }
    @Test void queryBoundsAndSourcePrecisionAreValidated() {
        assertDoesNotThrow(()->new Query(ESTATE,BLOCK,DAY,DAY.plusDays(366)));
        assertThrows(IllegalArgumentException.class,()->new Query(ESTATE,BLOCK,DAY,DAY.plusDays(367)));
        assertThrows(IllegalArgumentException.class,()->new Query(ESTATE,BLOCK,DAY,DAY.minusDays(1)));
        assertThrows(IllegalArgumentException.class,()->revision(10,0,Status.PENDING_DRC,DAY,"1.0001",null));
        assertThrows(IllegalArgumentException.class,()->revision(10,1,Status.CALCULATED,DAY,"1","2"));
    }
    @Test void immutableAttributionConflictIsVisibleInBothScopesWithoutMovingMass() {
        var s=add(State.empty(),revision(10,0,Status.PENDING_DRC,DAY,"10",null));
        var next=revision(11,1,Status.CALCULATED,DAY,"10","3");
        var changed=new ProductionRevision(next.eventId(),next.fingerprint(),next.kind(),next.sourceAt(),ROOT,1,id(90),id(91),DAY,next.estateTimezone(),next.provenance(),next.status(),next.wetKg(),next.dryKg());
        var t=ProductionProjection.apply(s,changed,NOW);assertEquals(Outcome.CONFLICT,t.outcome());
        assertEquals(new BigDecimal("10.000"),rebuild(t.state(),query()).days().getFirst().metrics().totalWetLatexKg());
        var other=rebuild(t.state(),new Query(id(90),id(91),DAY,DAY));assertNull(other.days().getFirst().metrics());assertEquals(1,other.conflictCount());
        assertEquals(1,rebuild(t.state(),query()).conflictCount());
        assertThrows(UnsupportedOperationException.class,()->s.collections().clear());
    }
    @Test void sourceTimezoneAndMicrosecondPrecisionArePreserved() {
        var s=add(State.empty(),revision(10,0,Status.PENDING_DRC,DAY,"10",null));var next=revision(11,1,Status.CALCULATED,DAY,"10","3");
        var changed=new ProductionRevision(next.eventId(),next.fingerprint(),next.kind(),next.sourceAt(),ROOT,1,ESTATE,BLOCK,DAY,ZoneId.of("UTC"),next.provenance(),next.status(),next.wetKg(),next.dryKg());
        assertEquals(Outcome.CONFLICT,ProductionProjection.apply(s,changed,NOW).outcome());
        assertThrows(IllegalArgumentException.class,()->new ProductionRevision(next.eventId(),next.fingerprint(),next.kind(),next.sourceAt().plusNanos(1),ROOT,1,ESTATE,BLOCK,DAY,next.estateTimezone(),next.provenance(),next.status(),next.wetKg(),next.dryKg()));
        // Source date is authoritative even when source event instant has a different local day.
        assertEquals(DAY,rebuild(s,query()).days().getFirst().date());
    }
    @Test void queryAuthorizationPrecedesAllReferencesAndProjectionReads() {
        var calls=new ArrayList<String>();var service=new ReadProductionHistory(q->calls.add("references"),q->{calls.add("projection");return rebuild(State.empty(),q);});
        for(var role:List.of("VIEWER","FIELD_WORKER","FIELD_SUPERVISOR","AGRONOMIST"))assertThrows(SecurityException.class,()->service.read(query(),Set.of(role),Set.of(ESTATE)));
        assertThrows(SecurityException.class,()->service.read(query(),Set.of("ESTATE_MANAGER"),Set.of(id(99))));assertTrue(calls.isEmpty());
        service.read(query(),Set.of("AUDITOR"),Set.of(ESTATE));assertEquals(List.of("references","projection"),calls);
    }
    @Test void dependencyFailureDoesNotBecomeEmptySuccess() {
        var calls=new ArrayList<String>();var service=new ReadProductionHistory(q->{throw new IllegalStateException("unavailable");},q->{calls.add("projection");return rebuild(State.empty(),q);});
        assertThrows(IllegalStateException.class,()->service.read(query(),Set.of("PLATFORM_ADMIN"),Set.of()));assertTrue(calls.isEmpty());
    }
}
