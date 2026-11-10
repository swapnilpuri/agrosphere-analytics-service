package com.agrosphere.analytics.domain;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Minimal facts from a fully validated TelemetryObservationRecorded v1 SIMULATED envelope.
 * The future adapter must validate all fields/identities and supply SHA-256 of the full canonical
 * envelope, including fields omitted here. This type is neither an event parser nor an adapter. */
public record EnvironmentalObservation(UUID eventId, String fingerprint, UUID observationId,
        UUID deviceId, UUID estateId, UUID blockId, Instant observedAt, Instant receivedAt,
        Kind kind, String unit, String value, Instant intervalStart, Instant intervalEnd) {
    public enum Kind { AIR_TEMPERATURE, SOIL_MOISTURE, INTERVAL_RAINFALL }
    public EnvironmentalObservation {
        Objects.requireNonNull(eventId); Objects.requireNonNull(observationId); Objects.requireNonNull(deviceId);
        Objects.requireNonNull(estateId); Objects.requireNonNull(blockId); Objects.requireNonNull(kind);
        instant(observedAt); instant(receivedAt);
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Full validated fingerprint required");
        if (value == null || value.length() > 20 || !value.matches("-?(?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,6})?"))
            throw new IllegalArgumentException("Invalid exact decimal string");
        var number = new BigDecimal(value);
        var expectedUnit = switch (kind) {
            case AIR_TEMPERATURE -> "CELSIUS";
            case SOIL_MOISTURE -> "PERCENT";
            case INTERVAL_RAINFALL -> "MILLIMETRE";
        };
        if (!expectedUnit.equals(unit) || kind == Kind.SOIL_MOISTURE &&
                (number.signum() < 0 || number.compareTo(new BigDecimal("100")) > 0))
            throw new IllegalArgumentException("Kind/unit/range mismatch");
        if (observedAt.isAfter(receivedAt.plusSeconds(300))) throw new IllegalArgumentException("Future skew");
        if (kind == Kind.INTERVAL_RAINFALL) {
            instant(intervalStart); instant(intervalEnd);
            if (number.signum() < 0 || !intervalStart.isBefore(intervalEnd) || intervalEnd.isAfter(observedAt))
                throw new IllegalArgumentException("Rainfall interval/range mismatch");
        } else if (intervalStart != null || intervalEnd != null) throw new IllegalArgumentException("Unexpected interval");
    }
    public static void instant(Instant value) {
        Objects.requireNonNull(value);
        int year = value.atOffset(ZoneOffset.UTC).getYear();
        if (year < 1 || year > 9999 || value.getNano() % 1_000_000 != 0)
            throw new IllegalArgumentException("UTC millisecond instant in years 0001..9999 required");
    }
}
