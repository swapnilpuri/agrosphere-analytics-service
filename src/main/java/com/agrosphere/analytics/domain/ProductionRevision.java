package com.agrosphere.analytics.domain;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Minimal facts from an independently schema/semantically validated full collection-v2 event.
 * fingerprint is SHA-256 of the complete canonical validated envelope, not just these facts.
 * No actor, worker, note or measurement evidence is retained here. No JSON adapter is installed. */
public record ProductionRevision(UUID eventId, String fingerprint, Kind kind, Instant sourceAt,
        UUID collectionId, long version, UUID estateId, UUID blockId, LocalDate collectionDate,
        ZoneId estateTimezone, Provenance provenance, Status status, BigDecimal wetKg, BigDecimal dryKg) {
    public enum Kind { RECORDED, DRC_RECORDED, ADJUSTED, VOIDED }
    public enum Provenance { SIMULATED, REAL }
    public enum Status { PENDING_DRC, CALCULATED, VOIDED }
    public ProductionRevision {
        Objects.requireNonNull(eventId); Objects.requireNonNull(collectionId);
        Objects.requireNonNull(estateId); Objects.requireNonNull(blockId);
        Objects.requireNonNull(kind); Objects.requireNonNull(provenance); Objects.requireNonNull(status);
        Objects.requireNonNull(estateTimezone); Objects.requireNonNull(sourceAt); Objects.requireNonNull(collectionDate);
        if (sourceAt.getNano() % 1000 != 0 || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}") || version < 0)
            throw new IllegalArgumentException("Invalid source identity/version");
        if (collectionDate.getYear() < 1 || collectionDate.getYear() > 9999 ||
                sourceAt.atOffset(ZoneOffset.UTC).getYear() < 1 || sourceAt.atOffset(ZoneOffset.UTC).getYear() > 9999)
            throw new IllegalArgumentException("Unsupported date/instant");
        wetKg = mass(wetKg, false);
        if (wetKg.signum() <= 0) throw new IllegalArgumentException("Positive collection mass required");
        if (dryKg != null) dryKg = mass(dryKg, true);
        if (dryKg != null && dryKg.compareTo(wetKg) > 0) throw new IllegalArgumentException("Dry exceeds wet mass");
        if (status == Status.CALCULATED && dryKg == null || status == Status.PENDING_DRC && dryKg != null)
            throw new IllegalArgumentException("DRC state inconsistent");
        if (kind == Kind.RECORDED && (version != 0 || status != Status.PENDING_DRC) ||
                kind != Kind.RECORDED && version == 0 ||
                kind == Kind.DRC_RECORDED && status != Status.CALCULATED ||
                kind == Kind.VOIDED && status != Status.VOIDED ||
                kind != Kind.VOIDED && status == Status.VOIDED)
            throw new IllegalArgumentException("Event kind/state inconsistent");
    }
    private static BigDecimal mass(BigDecimal value, boolean zeroAllowed) {
        Objects.requireNonNull(value);
        if (value.stripTrailingZeros().scale() > 3 || value.signum() < 0 ||
                value.compareTo(new BigDecimal("999999.999")) > 0 || !zeroAllowed && value.signum() == 0)
            throw new IllegalArgumentException("Invalid source mass");
        return value.setScale(3);
    }
}
